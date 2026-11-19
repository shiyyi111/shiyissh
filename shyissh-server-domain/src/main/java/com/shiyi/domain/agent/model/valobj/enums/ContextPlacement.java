package com.shiyi.domain.agent.model.valobj.enums;

/**
 * Context 在最终 Prompt 中的放置位置。
 *
 * <p>STABLE_PREFIX 用于会话内低频变化、能帮助模型保持任务目标的上下文；
 * EPHEMERAL_SUFFIX 用于实时状态、工具反馈等高频变化内容。
 *
 * <p>为什么要有这个区分：服务端前缀缓存（prompt cache）按"前缀完全一致"命中，
 * 把每轮都变的动态内容放进稳定前缀，等于让缓存永远命中不了。
 * 所以"放前"还是"放后"不是排版问题，是省不省钱、快不快的问题。
 *
 * @author shiyi
 */
public enum ContextPlacement {
    STABLE_PREFIX,
    EPHEMERAL_SUFFIX
}
