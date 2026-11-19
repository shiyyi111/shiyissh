package com.shiyi.domain.agent.graph.support;

import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;

/**
 * token 级流式桥。
 * <p>
 * SAA 的图只提供节点边界事件（VALUES / SNAPSHOTS），模型逐字生成的增量图看不到。
 * 这里在 [llm] 节点内部开一根管子，把 chatModel.stream() 的每一块转成字符串推出去，
 * Controller 再把"图进度"和"token 流"用 {@code Flux.merge} 合成一条 SSE 流。
 * <p>
 * 线程模型：Spring AI 的 stream 在 Reactor 线程上回调，Sinks.unicast 允许多线程 emit，
 * tryEmitNext 失败时只丢帧不死锁（丢帧比卡死好）。
 *
 * @author shiyi
 */
public class TokenStreamBridge {

    private final Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();

    /**
     * 结构化事件管（tool_call / tool_result）。
     * <p>
     * 工具事件由节点自己发：执行工具时往这根管子里塞一条 JSON，Controller 再翻译成 SSE 事件。
     * 单独一根管子，避免把 JSON 混进 token 文本流（前端按 event 字段分支渲染）。
     */
    private final Sinks.Many<Map<String, Object>> eventSink = Sinks.many().unicast().onBackpressureBuffer();

    /** 累计用量快照，供 done 事件带给前端（绝对累计值，不是增量）。 */
    private final java.util.concurrent.atomic.AtomicInteger promptTokens = new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.concurrent.atomic.AtomicInteger completionTokens = new java.util.concurrent.atomic.AtomicInteger();

    private final java.util.concurrent.atomic.AtomicInteger totalTokens = new java.util.concurrent.atomic.AtomicInteger();

    /** Controller 订阅这条流，逐字推 SSE。 */
    public Flux<String> tokens() {
        return sink.asFlux();
    }

    /** Controller 订阅结构化事件流。 */
    public Flux<Map<String, Object>> events() {
        return eventSink.asFlux();
    }

    /** 推一条结构化事件（例如 {@code tool_call}）。 */
    public void emitEvent(Map<String, Object> event) {
        if (event != null && !event.isEmpty()) {
            eventSink.tryEmitNext(event);
        }
    }

    /** 手动推一块文本（比如非流式场景下的整段回答）。 */
    public void emit(String text) {
        if (text != null && !text.isEmpty()) {
            sink.tryEmitNext(text);
        }
    }

    /** 记录累计用量（由 [llm] 节点每轮上报绝对值，覆盖写）。 */
    public void recordUsage(Integer prompt, Integer completion, Integer total) {
        if (prompt != null && prompt >= 0) {
            promptTokens.set(prompt);
        }
        if (completion != null && completion >= 0) {
            completionTokens.set(completion);
        }
        if (total != null && total >= 0) {
            totalTokens.set(total);
        }
    }

    /** 当前累计用量快照，写入 done 事件，前端/日志可据此算成本。 */
    public Map<String, Object> usage() {
        Map<String, Object> usage = new java.util.LinkedHashMap<>();
        usage.put("promptTokens", promptTokens.get());
        usage.put("completionTokens", completionTokens.get());
        usage.put("totalTokens", totalTokens.get());
        return usage;
    }

    /** 接上模型流：每块增量自动推出，结束或出错时关闭管子。 */
    public void attach(Flux<ChatResponse> modelStream) {
        modelStream
                .map(response -> response.getResult() == null
                        ? ""
                        : response.getResult().getOutput().getText())
                .doOnNext(this::emit)
                .doOnComplete(this::complete)
                .doOnError(this::error)
                .subscribe();
    }

    public void complete() {
        sink.tryEmitComplete();
        eventSink.tryEmitComplete();
    }

    public void error(Throwable throwable) {
        sink.tryEmitError(throwable);
        eventSink.tryEmitError(throwable);
    }
}