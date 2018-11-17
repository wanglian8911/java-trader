package trader.service.md;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TransferQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

import trader.common.beans.BeansContainer;
import trader.common.beans.Lifecycle;
import trader.common.exchangeable.Exchangeable;
import trader.common.util.FileUtil;
import trader.common.util.IOUtil;
import trader.common.util.StringUtil;
import trader.common.util.TraderHomeUtil;

/**
 * 异步保存行情数据
 */
public class MarketDataSaver implements Lifecycle, MarketDataListener {
    private static Logger logger = LoggerFactory.getLogger(MarketDataSaver.class);

    /**
     * 主动刷新间隔(ms)
     */
    private static final int FLUSH_INTERVAL = 15*1000;

    private static class WriterInfo implements AutoCloseable {
        private Writer writer;
        /**
         * 上传刷新时间
         */
        private long flushTime;
        /**
         * 已经写数据, 需要刷新
         */
        private boolean needFlush;

        public WriterInfo(BufferedWriter writer) {
            this.writer = writer;
            flushTime = System.currentTimeMillis();
        }

        boolean flush() throws IOException
        {
            long currTime = System.currentTimeMillis();
            boolean result = false;
            if ( needFlush && (currTime-flushTime)>FLUSH_INTERVAL ) {
                writer.flush();
                needFlush = false;
                flushTime = currTime;
                result = true;
            }
            return result;
        }

        @Override
        public void close() throws Exception {
            writer.flush();
            writer.close();
        }

        public void appendLine(String string) throws IOException {
            writer.write(string);
            writer.write("\n");
            needFlush = true;
        }

    }
    private MarketDataService marketDataService;
    private ExecutorService executorService;
    private TransferQueue<MarketData> marketDataQueue = new LinkedTransferQueue<>();
    private Map<String, WriterInfo> writerMap = new HashMap<>();
    private File dataDir;
    private Thread saveThread;
    private volatile boolean stop;

    public MarketDataSaver(MarketDataService marketDataService){
        this.marketDataService = marketDataService;
    }

    @Override
    public void init(BeansContainer beansContainer)
    {
        executorService = beansContainer.getBean(ExecutorService.class);
        dataDir = TraderHomeUtil.getDirectory(TraderHomeUtil.DIR_MARKETDATA);
        dataDir.mkdirs();
        executorService.execute(() -> {
            saveThreadFunc();
        });
    }

    @Override
    public void destroy() {
        stop = true;
        if ( saveThread!=null ) {
            saveThread.interrupt();
        }
    }

    @Override
    public void onMarketData(MarketData marketData){
        marketDataQueue.offer(marketData);
    }

    public void saveThreadFunc()
    {
        saveThread = Thread.currentThread();
        StringBuilder rowBuf = new StringBuilder(1024);
        while( !stop ){
            MarketData marketData = null;
            try {
                marketData = marketDataQueue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {}
            if ( null==marketData) {
                flushAllWriters();
                continue;
            }
            try {
                WriterInfo writerInfo = getOrCreateWriter(marketData);
                rowBuf.setLength(0);
                marketData.toCsvRow(rowBuf);
                writerInfo.appendLine(rowBuf.toString());
            } catch (Throwable e) {
                logger.error("Write market data file failed",e);
            }
        }
        for( WriterInfo writerInfo:writerMap.values()){
            try {
                writerInfo.close();
            }catch(Throwable e){}
        }
        writerMap.clear();
        saveThread = null;
    }

    /**
     * 按需刷新
     */
    private void flushAllWriters() {
        for(Iterator<Map.Entry<String, WriterInfo>> it=writerMap.entrySet().iterator(); it.hasNext();){
            Map.Entry<String, WriterInfo> writerInfoEntry = it.next();
            String key = writerInfoEntry.getKey();
            WriterInfo writerInfo = writerInfoEntry.getValue();
            try {
                writerInfo.flush();
            }catch(Throwable t){
                logger.error("Writer "+key+" flush failed", t);
                it.remove();
            }
        }
    }

    private WriterInfo getOrCreateWriter(MarketData marketData) throws IOException
    {
        String producerId = marketData.producerId;
        Exchangeable instrumentId = marketData.instrumentId;
        String writerKey = marketData.producerId+"-"+instrumentId.id();
        WriterInfo writerInfo = writerMap.get(writerKey);
        if ( null==writerInfo ){
            File file = new File(dataDir, marketData.tradingDay+"/"+producerId+"/"+instrumentId+".csv");
            File producerDir = file.getParentFile();
            if( !producerDir.exists()) {
                producerDir.mkdirs();
                saveProviderProps(producerDir, producerId);
            }
            writerInfo = new WriterInfo( IOUtil.createBufferedWriter(file, StringUtil.UTF8, true) );
            if ( file.length()==0 ){
                writerInfo.writer.write(marketData.getCsvHead());
                writerInfo.writer.write("\n");
            }
            writerMap.put(writerKey, writerInfo);
        }
        return writerInfo;
    }

    /**
     * 为每个producer目录保存一个标准 producer.json文件
     */
    private void saveProviderProps(File mdProviderDir, String producerId)
    {
        MarketDataProducer.Type producerType = MarketDataProducer.Type.ctp;
        MarketDataProducer mdProducer = marketDataService.getProducer(producerId);
        if ( mdProducer!=null ) {
            producerType = mdProducer.getType();
        }
        JsonObject json =new JsonObject();
        json.addProperty("id", producerId);
        json.addProperty("type", producerType.name());
        try{
            FileUtil.save(new File(mdProviderDir,"producer.json"), json.toString());
        }catch(Throwable t) {}
    }
}