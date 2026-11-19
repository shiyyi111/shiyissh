package com.shiyi.trigger.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiyi.domain.agent.model.entity.AgentApprovalEntity;
import com.shiyi.domain.agent.service.approval.AgentApprovalService;
import com.shiyi.domain.agent.service.chat.GraphChatService;
import com.shiyi.domain.agent.graph.GraphStateKeys;
import com.shiyi.domain.agent.graph.support.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SAA 图链路的 HTTP 入口：一次对话（SSE 流式）+ 一条人工审批单的决策。
 * <p>
 * 两个端点的分工：
 * <pre>
 *   POST /api/agent/graph/chat_stream   跑一轮图，逐条推 {"type":"node"|"text"|"done"|"error"}
 *   POST /api/agent/graph/approval/{id} 对 [approval] 产生的审批单做决策；批准则用同一条命令复跑一轮
 * </pre>
 * 并发保护（{@link #runningSessions}）、心跳、防缓冲头都在这一层做：
 * 图本身不知道有几个客户端在听，也不知道连接会不会断，这些都属于"传输"而不是"业务"。
 *
 * @author shiyi
 */
@RestController
@RequestMapping("/api/agent/graph")
public class GraphAgentController {

    private static final Logger log = LoggerFactory.getLogger(GraphAgentController.class);

    /** 同一个会话不允许同时跑两个 ReAct 循环（与老 Controller 一致）。 */
    private final Set<String> runningSessions = ConcurrentHashMap.newKeySet();

    @Resource
    private GraphChatService graphChatService;

    @Resource
    private AgentApprovalService agentApprovalService;

    @RequestMapping(value = {"chat_stream", "chat"}, method = RequestMethod.POST)
    public ResponseBodyEmitter chatStream(@RequestBody GraphChatRequest requestDTO) {
        try {
            log.info("SAA 图流式对话 userId:{} sessionId:{} terminalSessionId:{} message:{}",
                    requestDTO.userId(), requestDTO.sessionId(), requestDTO.terminalSessionId(), requestDTO.message());

            // 1. 补齐 sessionId
            String sessionId = requestDTO.sessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                sessionId = UUID.randomUUID().toString();
            }

            // 1-1. 追踪 ID：优先沿用网关/前端传来的 X-Trace-Id，没有就新建。
            // 它绑到当前线程的 MDC（日志里每一行都会带上），并通过 SSE 首帧 meta 事件回给前端，
            // 用户报障时贴一个 id 就能定位整轮请求（含子智能体那几条线程的日志）。
            String traceId = TraceContext.continueOrCreate(requestHeader(TraceContext.HEADER));
            TraceContext.put(traceId);

            // 2. 并发保护：同一个会话不允许同时跑两个循环
            if (!runningSessions.add(sessionId)) {
                log.warn("会话正在处理中，拒绝并发请求 sessionId={}", sessionId);
                ResponseBodyEmitter busyEmitter = new ResponseBodyEmitter(10_000L);
                try {
                    Map<String, Object> busyEvent = new HashMap<>();
                    busyEvent.put("event", GraphStateKeys.EVENT_ERROR);
                    busyEvent.put("content", "上一条消息还在处理中，请稍候再发送（或先点击停止）");
                    busyEmitter.send(new ObjectMapper().writeValueAsString(busyEvent) + "\n");
                } catch (Exception ignored) {
                }
                busyEmitter.complete();
                return busyEmitter;
            }

            // 3. 关闭中间层缓冲（等价于 SseEmitter#extendResponse）
            ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes != null) {
                HttpServletResponse response = attributes.getResponse();
                if (response != null) {
                    response.setHeader("Cache-Control", "no-cache, no-transform");
                    response.setHeader("X-Accel-Buffering", "no");
                    response.setHeader("Connection", "keep-alive");
                    // 回显追踪 ID，前端 DevTools 里能直接看到本次请求的 id
                    response.setHeader(TraceContext.HEADER, traceId);
                    // 与 meta 帧同源：前端从响应头或首帧任取其一
                    response.setHeader("X-Session-Id", sessionId);
                }
            }

            // 4. 创建 SSE 发射器（10 分钟超时）
            ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);

            // 5. 异步执行，避免阻塞 HTTP 线程
            String finalSessionId = sessionId;
            String finalTraceId = traceId;
            Thread worker = new Thread(() -> {
                // 新线程不会继承父线程的 MDC，必须显式绑定：图节点、工具、子智能体都在这个线程链上打日志。
                TraceContext.put(finalTraceId);
                AtomicBoolean streaming = new AtomicBoolean(true);
                Thread heartbeatThread = new Thread(() -> {
                    try {
                        while (streaming.get()) {
                            Thread.sleep(15000);
                            try {
                                emitter.send(": heartbeat\n\n");
                            } catch (Exception e) {
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        // 正常退出
                    }
                }, "sse-heartbeat-" + finalSessionId);
                heartbeatThread.setDaemon(true);
                heartbeatThread.start();

                Disposable disposable = null;
                // 错误回调里要 dispose 掉订阅，但 disposable 只能在 subscribe 返回后拿到，
                // 因此用 AtomicReference 中转（否则 lambda 捕获不到 effectively-final 变量）。
                AtomicReference<Disposable> disposableRef = new AtomicReference<>();
                try {
                    // 首帧 meta：把 traceId 先告诉前端，后续 text/tool/done 事件都可以按它归档。
                    Map<String, Object> metaEvent = new LinkedHashMap<>();
                    metaEvent.put("event", GraphStateKeys.EVENT_META);
                    metaEvent.put("type", GraphStateKeys.EVENT_META);
                    // sessionId 必须回传：chat_stream 会在缺失时由后端补 UUID，不回传前端就永远拿不到它。
                    metaEvent.put("sessionId", finalSessionId);
                    metaEvent.put("traceId", finalTraceId);
                    emitter.send(new ObjectMapper().writeValueAsString(metaEvent) + "\n");

                    disposable = graphChatService.handleMessageStream(
                                    requestDTO.userId(), finalSessionId,
                                    requestDTO.message(), requestDTO.terminalSessionId(),
                                    requestDTO.agentName())
                            .subscribe(
                                    line -> {
                                        try {
                                            emitter.send(line + "\n");
                                        } catch (Exception e) {
                                            log.debug("客户端已断开，停止推送 session={}", finalSessionId);
                                        }
                                    },
                                    error -> {
                                        log.error("SAA 图执行异常 session={}", finalSessionId, error);
                                        try {
                                            Map<String, Object> errorEvent = new HashMap<>();
                                            errorEvent.put("event", GraphStateKeys.EVENT_ERROR);
                                            errorEvent.put("content", String.valueOf(error.getMessage()));
                                            emitter.send(new ObjectMapper().writeValueAsString(errorEvent) + "\n");
                                        } catch (Exception ignored) {
                                        }
                                        // 收尾三件事缺一不可：停心跳、释放会话锁、结束 emitter。
                                        // 少了任何一件，前端会一直等到 10 分钟超时才收到断开。
                                        streaming.set(false);
                                        Disposable d = disposableRef.get();
                                        if (d != null && !d.isDisposed()) {
                                            d.dispose();
                                        }
                                        emitter.complete();
                                        runningSessions.remove(finalSessionId);
                                    },
                                    () -> {
                                        streaming.set(false);
                                        emitter.complete();
                                        runningSessions.remove(finalSessionId);
                                    });
                    disposableRef.set(disposable);
                } catch (Exception e) {
                    log.error("SAA 图启动失败 session={}", finalSessionId, e);
                    streaming.set(false);
                    try {
                        Map<String, Object> errorEvent = new HashMap<>();
                        errorEvent.put("event", GraphStateKeys.EVENT_ERROR);
                        errorEvent.put("content", String.valueOf(e.getMessage()));
                        emitter.send(new ObjectMapper().writeValueAsString(errorEvent) + "\n");
                    } catch (Exception ignored) {
                    }
                    emitter.complete();
                    runningSessions.remove(finalSessionId);
                } finally {
                    // 线程池/长连接复用线程，必须解绑，否则下一条请求会串到旧的 traceId。
                    TraceContext.clear();
                    if (disposable == null) {
                        runningSessions.remove(finalSessionId);
                    }
                }
            }, "graph-chat-" + finalSessionId);
            worker.setDaemon(true);
            worker.start();

            emitter.onTimeout(() -> {
                log.warn("SAA 图 SSE 超时 session={}", finalSessionId);
                runningSessions.remove(finalSessionId);
                emitter.complete();
            });
            emitter.onCompletion(() -> runningSessions.remove(finalSessionId));

            return emitter;
        } catch (Exception e) {
            log.error("SAA 图流式对话失败", e);
            ResponseBodyEmitter errorEmitter = new ResponseBodyEmitter(10_000L);
            try {
                Map<String, Object> errorEvent = new HashMap<>();
                errorEvent.put("event", GraphStateKeys.EVENT_ERROR);
                errorEvent.put("content", String.valueOf(e.getMessage()));
                errorEmitter.send(new ObjectMapper().writeValueAsString(errorEvent) + "\n");
            } catch (Exception ignored) {
            }
            errorEmitter.complete();
            return errorEmitter;
        }
    }

    /**
     * 人工审批决策：批准则从断点续跑（或复跑），驳回则直接结束。
     * <p>
     * 两条批准路径，优先级从高到低：
     * <ol>
     *   <li><b>断点续跑</b>（容器里配了检查点存储时）：图在 [approval] 前中断过，检查点里存着那一刻的完整 state，
     *       包括模型当初生成的那条工具调用。把裁决写回 state 再 {@code withResume()}，
     *       流程直接进 [tool] 执行<b>原来那条命令</b>，然后 observe → llm 继续收口。
     *       好处是"人批准的"和"真执行的"能保证是同一条命令，且模型能接着看到执行结果；</li>
     *   <li><b>复跑</b>（没配检查点，或审批单上没记线程 ID 的老单据）：用同一条历史、同一个终端、同一个 Agent
     *       重新发起一轮请求，并把已批准命令作为 {@code approvedCommand} 传下去，[guard] 对原文完全一致的命令直接放行。</li>
     * </ol>
     * 返回的仍是 SSE 流（与 chat_stream 同一套事件格式），前端复用现有渲染逻辑即可。
     *
     * @param approvalId 审批单号
     * @param request    决策请求体
     */
    @RequestMapping(value = "approval/{approvalId}", method = RequestMethod.POST)
    public ResponseBodyEmitter decideApproval(@PathVariable("approvalId") String approvalId,
                                              @RequestBody(required = false) ApprovalDecisionRequest request) {
        boolean approved = request != null && Boolean.TRUE.equals(request.approved());
        String decidedBy = request == null || request.decidedBy() == null ? "" : request.decidedBy();
        ObjectMapper mapper = new ObjectMapper();
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(10 * 60 * 1000L);

        AgentApprovalService.DecideResult decision = agentApprovalService.decide(approvalId, approved, decidedBy);
        if (!decision.changed()) {
            // 已被处理过 / 单号不存在：明确告诉前端"没执行"，而不是静默成功
            sendEvent(emitter, mapper, "done", "审批单不存在或已被处理，本次没有执行任何命令");
            emitter.complete();
            return emitter;
        }
        AgentApprovalEntity approval = decision.approval();
        if (!approved) {
            sendEvent(emitter, mapper, "done", "已驳回，命令未执行");
            emitter.complete();
            return emitter;
        }

        String sessionId = approval.getSessionId() == null || approval.getSessionId().isBlank()
                ? UUID.randomUUID().toString()
                : approval.getSessionId();
        if (!runningSessions.add(sessionId)) {
            log.warn("会话正在处理中，拒绝审批复跑 sessionId={}", sessionId);
            sendEvent(emitter, mapper, "error", "该会话正在执行中，请稍后再确认");
            emitter.complete();
            return emitter;
        }

        // 优先从检查点续跑：同一条命令原样执行，不经过模型重新生成
        String threadId = approval.getThreadId();
        boolean canResume = graphChatService.checkpointEnabled()
                && threadId != null && !threadId.isBlank();
        String message = canResume ? null
                : "用户已确认执行命令：" + approval.getCommand()
                + "。请直接执行该命令并汇报执行结果，不要再询问确认。";
        Thread worker = new Thread(() -> {
            AtomicReference<Disposable> disposableRef = new AtomicReference<>();
            try {
                Flux<String> stream = canResume
                        ? graphChatService.resumeApproval(approval.getUserId(), sessionId, threadId)
                        : graphChatService.handleMessageStream(
                                approval.getUserId(), sessionId, message,
                                approval.getTerminalSessionId(), approval.getAgentName(),
                                // 兜底复跑：把"已批准命令"带进这一轮，[guard] 只对原文完全一致的命令放行
                                approval.getCommand());
                Disposable disposable = stream
                        .subscribe(
                                line -> {
                                    try {
                                        emitter.send(line + "\n");
                                    } catch (Exception e) {
                                        log.debug("客户端已断开，停止推送 session={}", sessionId);
                                    }
                                },
                                error -> {
                                    log.error("审批复跑失败 session={}", sessionId, error);
                                    sendEvent(emitter, mapper, "error", String.valueOf(error.getMessage()));
                                    emitter.complete();
                                    runningSessions.remove(sessionId);
                                },
                                () -> {
                                    emitter.complete();
                                    runningSessions.remove(sessionId);
                                });
                disposableRef.set(disposable);
            } catch (Exception e) {
                log.error("审批复跑启动失败 session={}", sessionId, e);
                sendEvent(emitter, mapper, "error", String.valueOf(e.getMessage()));
                emitter.complete();
                runningSessions.remove(sessionId);
            }
        }, "graph-approval-" + sessionId);
        worker.setDaemon(true);
        worker.start();

        emitter.onTimeout(() -> {
            log.warn("审批复跑 SSE 超时 session={}", sessionId);
            runningSessions.remove(sessionId);
            emitter.complete();
        });
        emitter.onCompletion(() -> runningSessions.remove(sessionId));
        return emitter;
    }

    /** 发一条 SSE 事件；连接已断时吞掉异常（调用方不该因为客户端断开而中断业务）。 */
    private void sendEvent(ResponseBodyEmitter emitter, ObjectMapper mapper, String type, String content) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("type", type);
            event.put("content", content == null ? "" : content);
            emitter.send(mapper.writeValueAsString(event) + "\n");
        } catch (Exception ignored) {
            // 客户端已断开
        }
    }

    /** 读请求头；拿不到请求上下文（如单测直调）时返回空串。 */
    private String requestHeader(String name) {
        try {
            ServletRequestAttributes attributes =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attributes == null) {
                return "";
            }
            String value = attributes.getRequest().getHeader(name);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 审批决策请求体。
     *
     * @param approved  true=批准并立即复跑，false=驳回
     * @param decidedBy 决策人（审计用）
     */
    public record ApprovalDecisionRequest(Boolean approved, String decidedBy) {
    }

    /**
     * 请求体（字段与老的 {@code ChatRequestDTO} 对齐）。
     *
     * @param message           用户消息
     * @param sessionId         会话 id（空则新建）
     * @param userId            用户 id
     * @param terminalSessionId 终端会话 id（真 SSH 时使用）
     * @param maxRounds         本轮最大模型调用轮数，默认 3
     * @author shiyi
     */
    public record GraphChatRequest(String message, String sessionId, String userId,
                                   String terminalSessionId, Integer maxRounds, String agentName) {
    }
}