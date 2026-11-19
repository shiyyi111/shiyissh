package com.shiyi.domain.agent.graph.support;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 模型主动保存记忆时的入参。
 * <p>
 * 自动抽取只能识别固定特征（系统类型、软件版本、失败信号），而真实排查中很多经验
 * 是"这次发现 /opt 单独挂载、写满的是那块盘"这种规则抓不到的内容。
 * 让模型自己判断该不该记、记什么，是这个工具存在的理由；
 * 代价是要防重复写入，所以 memoryKey 用内容哈希，同一段话重复保存会走更新而不是新增。
 *
 * @param content 要记住的一句话结论，写成脱离上下文也能看懂的形式
 * @param kind    类型：PREFERENCE（用户偏好）/ ENVIRONMENT（环境事实）/ SOFTWARE（软件版本）/ CASE（排查案例）
 * @author shiyi
 */
public record SaveMemoryRequest(
        @JsonPropertyDescription("要记住的结论，写清对象和值，例如：这台机器的 /opt 单独挂载在 /dev/vdb1，容量 200G")
        String content,
        @JsonPropertyDescription("记忆类型：PREFERENCE 用户偏好、ENVIRONMENT 环境事实、SOFTWARE 软件版本、CASE 排查案例，无法判断时填 ENVIRONMENT")
        String kind) {
}