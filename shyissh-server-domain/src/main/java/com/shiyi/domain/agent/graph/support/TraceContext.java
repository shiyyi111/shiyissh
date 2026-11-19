package com.shiyi.domain.agent.graph.support;

import org.slf4j.MDC;

import java.util.Map;
import java.util.UUID;

/**
 * 一次请求的追踪标识。
 * <p>
 * 为什么需要它：一次排查会跨多条日志、多个线程（HTTP worker、图节点、子智能体、并发批量派发），
 * 出问题时要能把"这条 502 是哪个请求触发的、它派了几个子智能体、每个子智能体跑了几轮"
 * 从几千行日志里串起来，只能靠一个贯穿全程的 id。
 * <p>
 * 为什么用 MDC 而不是把 traceId 一路当参数传：日志是 logback 打的，它只认 MDC；
 * 业务参数里再传一份的好处是"能进 state、能被 SSE 带给前端"，所以两者都做：
 * state 里放一份给业务用（见 {@code GraphStateKeys#TRACE_ID}），MDC 里放一份给日志用。
 * <p>
 * 线程边界要手动处理：MDC 是 ThreadLocal，异步任务不会自动继承，
 * 所以跨线程时用 {@link #wrap(Runnable)} 显式搬运。
 *
 * @author shiyi
 */
public final class TraceContext {

    /** MDC 键名必须与 logback-spring.xml 的 {@code %X{trace-id}} 一致。 */
    public static final String MDC_KEY = "trace-id";

    /** 前端/网关可透传的请求头。 */
    public static final String HEADER = "X-Trace-Id";

    private TraceContext() {
    }

    /** 生成一个新 id（16 位十六进制，足够区分且不占日志宽度）。 */
    public static String create() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /** 当前线程的 id，没有则空串。 */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "" : value;
    }

    /** 优先沿用上游传进来的 id（便于和网关/前端日志对齐），没有就新建。 */
    public static String continueOrCreate(String incoming) {
        if (incoming != null && !incoming.isBlank()) {
            return incoming.strip();
        }
        String current = current();
        return current.isBlank() ? create() : current;
    }

    /** 绑定到当前线程。 */
    public static void put(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    /** 解绑（必须成对调用，否则线程池复用时会串号）。 */
    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /**
     * 把任务包一层：执行前恢复调用方的 MDC，执行后清理。
     * <p>
     * 用于并发派发（{@code CompletableFuture}）与子智能体线程——它们拿不到父线程的 ThreadLocal。
     */
    public static Runnable wrap(Runnable task) {
        Map<String, String> snapshot = MDC.getCopyOfContextMap();
        return () -> {
            if (snapshot != null) {
                MDC.setContextMap(snapshot);
            }
            try {
                task.run();
            } finally {
                clear();
            }
        };
    }
}