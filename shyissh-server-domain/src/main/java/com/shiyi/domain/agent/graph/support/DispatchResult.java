package com.shiyi.domain.agent.graph.support;

import com.alibaba.fastjson2.JSON;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 子智能体派发的结构化回传信封。
 * <p>
 * 为什么不用一段裸文本：父模型拿到「（子智能体未给出结论）」和「（派发失败：图未初始化）」
 * 这两种文本时，行为应当不同 —— 前者可以换个问法重试，后者重试多少次都没用。
 * 纯文本只能靠措辞去猜，而措辞不属于契约；把 status 显式写进结构里，父模型才有判断依据。
 * <pre>
 * {"agent":"sshDiagnosisAgent","status":"ok","summary":"磁盘 /var 使用率 92%","stopReason":"completed","elapsedMs":3210,"tokens":1520,"attempts":1}
 * </pre>
 * status 三态：
 * <ul>
 *   <li>{@link #STATUS_OK} —— 子智能体正常给出结论；</li>
 *   <li>{@link #STATUS_EMPTY} —— 跑完了但没产出内容（模型没答、或答了空串）；</li>
 *   <li>{@link #STATUS_ERROR} —— 没跑起来或执行中途异常（参数为空、深度超限、找不到 Agent、图未初始化、超时、抛异常）。</li>
 * </ul>
 * {@code attempts} 是本次派发真正尝试的次数（1 = 一次成功；>1 说明触发过超时/异常重试；
 * 前置校验拒绝时为 0，因为一次都没跑）。父模型据此判断"是偶发抖动还是稳定失败"。
 *
 * @author shiyi
 */
public record DispatchResult(String agentName,
                             String status,
                             String summary,
                             String stopReason,
                             long elapsedMs,
                             int tokens,
                             int attempts) {

    /** 子智能体正常收口，summary 即最终结论。 */
    public static final String STATUS_OK = "ok";
    /** 子智能体执行完成但没有产出内容。 */
    public static final String STATUS_EMPTY = "empty";
    /** 子智能体没能执行（前置校验失败或执行异常）。 */
    public static final String STATUS_ERROR = "error";

    /** 拒发/异常：不需要耗时与用量，统一置 0，避免父模型误以为"花了很多 token 还没结果"。 */
    public static DispatchResult failed(String agentName, String summary) {
        return new DispatchResult(agentName, STATUS_ERROR, summary, "rejected", 0L, 0, 0);
    }

    /** 拼成回填给模型的 JSON。字段顺序固定，便于日志与人工比对。 */
    public String toEnvelope() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("agent", agentName);
        envelope.put("status", status);
        envelope.put("summary", summary);
        envelope.put("stopReason", stopReason);
        envelope.put("elapsedMs", elapsedMs);
        envelope.put("tokens", tokens);
        envelope.put("attempts", attempts);
        return JSON.toJSONString(envelope);
    }
}