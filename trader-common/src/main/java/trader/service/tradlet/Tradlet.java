package trader.service.tradlet;

import trader.service.md.MarketData;
import trader.service.ta.LeveledTimeSeries;

/**
 * 交易策略实例, 可以动态加载和释放.
 * <BR>Tradlet实例必须属于某个TradletGroup关联, 在初始化时, 从BeansContainer.getBean(TradletGroup.class)方式拿到
 * <BR>
 * <BR>Tradlet与Playbook与PolicyId的关系
 * <LI>TradletGroup可以有多个Tradlet
 * <LI>Tradlet可以有多个各自的Action, 每个Action代表一个实际的开仓平仓动作原因. 格式为: tradletId.actionName, 这样从actionId可以反推tradletId
 * <P>一个Tradlet有多个Action的原因: 开仓/平仓策略很多是不同参数调整的结果. 如果每个Policy都对应个一个Tradlet实现类,
 * 那么就无法共享数据结构, 消耗过多CPU和内存.
 * <LI>TradletGroup可以有多个活动的Playbook实例
 * <LI>Playbook 在生命周期中, 会被TradletGroup的所有Tradlet关注, 但是只能有一个实际的平仓动作
 */
public interface Tradlet {

    /**
     * 初始化
     */
    public void init(TradletContext context) throws Exception;

    /**
     * 销毁实例, 释放资源
     */
    public void destroy();

    /**
     * 当有新的行情切片来的时候
     */
    public void onTick(MarketData marketData);

    /**
     * 当有新的分钟线产生, 这个函数在新的Bar所在的Tick后调用
     */
    public void onNewBar(LeveledTimeSeries series);

    /**
     * 当新的一秒来到时, 如果上一秒没有行情数据, 会主动调用这个函数.
     * <BR>当中场休息或不活跃合约时, 这个函数会被调用
     */
    public void onNoopSecond();

}
