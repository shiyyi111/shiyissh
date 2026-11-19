package com.shiyi.domain.agent.graph.node;

import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;

/**
 * 图节点契约。
 * <p>
 * 每个节点只做一件事：读 state → 干活 → 返回增量。节点之间不互相调用，
 * 也不共享字段，所有数据都通过 state 传递，这样单个节点可以被单独测试、单独替换。
 *
 * @author shiyi
 */
public interface GraphNode {

    /** 节点名，必须与图里 addNode 的名字一致。 */
    String name();

    /** 节点行为。 */
    AsyncNodeActionWithConfig action();
}