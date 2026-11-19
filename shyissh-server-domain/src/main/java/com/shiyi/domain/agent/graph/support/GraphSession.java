package com.shiyi.domain.agent.graph.support;

import com.shiyi.domain.agent.model.entity.ChatMessageEntity;
import com.shiyi.domain.agent.service.IChatContextService;
import com.shiyi.domain.agent.service.ILongTermMemoryService;
import com.shiyi.domain.agent.service.context.TokenEstimator;
import com.shiyi.domain.agent.service.prompt.PromptEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 图链路的会话历史装载器（短期窗口）。
 * <p>
 * 冷启动恢复的流程：
 * <pre>
 *   读历史：longTermMemoryService.getRecentMessages(sessionId, 50) 作为候选池
 *                              → 按优先级裁剪 → 三道硬闸 → 作为 [messages] 的初始值
 * </pre>
 * 这里委托 {@link ILongTermMemoryService}（内部对 DB 异常做了降级），
 * 图节点不直接碰仓储接口。
 * <p>
 * 过滤规则（与 {@link GraphMessages#buildHistory} 一致）：只把 user/assistant 的纯文本
 * 重新送给模型；{@code tool} 消息需要 tool_call_id 才能正确关联，跨请求重放容易错位，
 * 因此不作为历史注入。工具痕迹另作处理：{@code [observe]} 落库时把命令原文写在输出首行
 * （{@link #COMMAND_TRACE_PREFIX}），装载时由 {@link #expandToolTraces} 折叠成一条
 * "曾执行命令：…" 的 assistant 文本，保证换一轮对话后模型仍知道上轮跑过哪条命令。
 * <p>
 * <b>闸门顺序（token 主约束，轮数/条数是安全上限）：</b>
 * <pre>
 *   ⓪ 优先级裁剪 —— 重要性低的先让位（重要性由 reducer 按 role/内容推断，不再读数据库列）
 *   ① 6000 token —— 真正的预算约束：从最新往前装，装满为止
 *   ② 12 轮      —— 防止"历史跨度太大"，只在上限范围内兜底
 *   ③ 30 条      —— 防止"一轮多次工具调用"产生密集消息（12 轮正常最多 24 条，故取 30 留余量）
 *   ④ 首条不变量 —— 兜底收口：拼给模型的第一条必须是 user
 * </pre>
 * ①~③ 三道硬闸都尽力把切点对齐到 user 上，但"不需要裁"时它们直接原样返回、一次对齐都不做，
 * 候选池头部仍可能落在 assistant 或命令痕迹上。因此真正保证顺序的是 ④：无论前面裁没裁，
 * 最后都从头部丢到首条 user 为止；整段没有 user 就整段交给摘要器——宁可失忆，也不给
 * "system 之后第一条是 assistant"的畸形序列（部分模型会直接报 user/assistant 顺序错误）。
 * <p>
 * 6000 token 的来历：yml 里 {@code sshOperator.instruction} 约 1600 汉字≈1600 token，
 * 富化上下文（当前任务/长期记忆/系统环境/关键事件/工具摘要）300~1500，
 * 本轮工具回填最多约 2000×2 —— 不可压缩部分约 4000~5000，
 * 历史给 6000 后单次峰值约 13K，仍在 32K/128K 模型的舒适区。
 *
 * @author shiyi
 */
public final class GraphSession {

    private static final Logger log = LoggerFactory.getLogger(GraphSession.class);

    /**
     * 从 DB 读取的最大条数（候选池，不直接进 prompt，后面还要过优先级裁剪与三道硬闸）。
     * <p>
     * 取 100 而不是 50：一轮排障往往产生 1 条 user + 1 条 assistant + 若干条 tool，
     * 100 条原始记录约覆盖十四轮（一轮 1 user + 1 assistant + 若干 tool）；候选池太小，
     * 后面几道闸还没开始裁就已经没历史可选。多读的成本只是一次索引查询，真正占 token 的是裁剪后的结果。
     */
    public static final int DEFAULT_HISTORY_LIMIT = 100;

    /**
     * 闸②的轮数上限：只作为"防止语义跨度太大"的安全上限，不是主约束。
     * <p>
     * 真正的约束是 {@link #DEFAULT_HISTORY_TOKEN_BUDGET}。8 轮短对话往往只有一两千 token，
     * 若把轮数卡在 4 轮，等于把预算内的上下文白扔掉；因此把轮数放到 12，让 token 闸决定实际能装多少轮。
     */
    public static final int DEFAULT_HISTORY_MAX_TURNS = 12;

    /** 闸①：历史部分的 token 预算。 */
    public static final int DEFAULT_HISTORY_TOKEN_BUDGET = 6000;

    /**
     * 闸③的条数上限：兜底，不是主约束。
     * <p>
     * 它防的是"reducer 保留了大量短消息、轮数和 token 都没超但条数失控"这种极端分布。
     * 12 轮正常对话最多 24 条（一 user 一 assistant），因此上限取 30——太小会让它抢先于轮数闸生效，
     * 把"12 轮"实际压成"10 轮"，闸①的配置就形同虚设。
     */
    public static final int DEFAULT_HISTORY_MAX_MESSAGES = 30;

    private GraphSession() {
    }

    /**
     * 加载会话历史并转成 Spring AI 的 {@link Message} 列表。
     *
     * @param memoryService 长期记忆服务（可为 null）
     * @param sessionId     会话 ID
     * @return 时间正序的历史消息；无数据或异常时返回空列表，绝不抛异常
     */
    public static List<Message> loadHistory(ILongTermMemoryService memoryService, String sessionId) {
        return loadHistory(memoryService, null, sessionId);
    }

    /**
     * 加载会话历史，并在转成 {@link Message} 之前先过一遍"权重压缩"预算裁剪。
     * <p>
     * 完全同一条链路：
     * <pre>
     *   HybridReducer = PriorityReducer（保重要：错误、路径、工具链）
     *                 + SlidingWindowReducer（保新近）
     *                 + 强制保底最近 2 个完整消息组
     * </pre>
     * 三道闸（12 轮 / 6000 token / 30 条）解决的是"硬截断"，
     * 而 reducer 解决的是"硬截断之前先按重要性挑一遍"——两者不能互相替代：
     * 只做硬截断会砍掉早期的关键错误信息，只做 reducer 则无法挡住单轮巨量工具输出。
     * <p>
     * 优先级由 {@code PriorityReducer.inferPriority} 按 role 与内容现算（工具报错 CRITICAL、路径/命令 HIGH），
     * 不再持久化到 {@code chat_message}：落库的优先级会随裁剪规则演进过期，而推断永远与当前规则一致。
     * <p>
     * <b>为什么 reducer 在前、三道闸在后</b>：reducer 的输出顺序仍是原序，且不会拆散
     * assistant(tool_calls)+tool 的配对；把它放在最前面，后面的轮次/条数裁剪才有干净输入。
     *
     * @param memoryService     长期记忆服务（可为 null）
     * @param chatContextService 上下文服务，提供 {@code trimHistory}（可为 null，为 null 时退化为纯硬闸）
     * @param sessionId         会话 ID
     * @return 时间正序的历史消息；无数据或异常时返回空列表，绝不抛异常
     */
    public static List<Message> loadHistory(ILongTermMemoryService memoryService,
                                            IChatContextService chatContextService,
                                            String sessionId) {
        return loadHistoryDetailed(memoryService, chatContextService, sessionId).messages();
    }

    /**
     * 装载会话历史，并**带回被裁掉的那部分**。
     * <p>
     * 为什么要带回：三道闸只做"丢"，不做"留"。历史一旦超预算，早期那句"上次重启后
     * nginx 起不来"就被彻底抹掉了，模型第 5 轮再问"为什么还是不行"时完全不知道上文。
     * 把这部分交给摘要器压成几句话，等于用 200 token 换回"没有失忆"。
     *
     * @return {@code messages} 为实际拼进 prompt 的历史；{@code dropped} 为被三道闸丢掉的消息
     *         （时间正序，可能为空）
     */
    public static LoadedHistory loadHistoryDetailed(ILongTermMemoryService memoryService,
                                                    IChatContextService chatContextService,
                                                    String sessionId) {
        if (memoryService == null || sessionId == null || sessionId.isBlank()) {
            return new LoadedHistory(List.of(), List.of());
        }
        try {
            List<ChatMessageEntity> entities = memoryService.getRecentMessages(sessionId, DEFAULT_HISTORY_LIMIT);
            if (entities == null || entities.isEmpty()) {
                return new LoadedHistory(List.of(), List.of());
            }
            // tool 行本身不进 prompt（缺 tool_call_id 无法重放），但把它折成"曾执行命令：xxx"
            // 的助手痕迹，模型才知道这些命令已经查过、不必重复执行。
            entities = expandToolTraces(entities);
            PriorityTrim priorityTrim = chatContextService == null
                    ? new PriorityTrim(entities, List.of())
                    : trimByPriority(entities, chatContextService);
            List<Message> messages = new ArrayList<>();
            List<ChatMessageEntity> keptEntities = new ArrayList<>();
            for (ChatMessageEntity entity : priorityTrim.kept()) {
                Message message = toMessage(entity);
                if (message != null) {
                    messages.add(message);
                    keptEntities.add(entity);
                }
            }
            List<Message> all = new ArrayList<>(messages);
            // 顺序：token 才是真约束，先按预算装；轮数是"防止语义跨度太大"的安全上限；条数是最后兜底。
            // 反过来（轮数在前）会出现"8 轮一共 2000 token 却被硬砍到 12 轮"的浪费。
            messages = trimToTokenBudget(messages, DEFAULT_HISTORY_TOKEN_BUDGET);
            messages = trimToRecentTurns(messages, DEFAULT_HISTORY_MAX_TURNS);
            messages = capMessages(messages, DEFAULT_HISTORY_MAX_MESSAGES);
            // 闸④：首条不变量。①~③ 在"不需要裁"时都直接原样返回，不做任何对齐，
            // 因此候选池头部（最新 100 行的第 100 行）落在 assistant 或命令痕迹上时，
            // 会被原样拼进 prompt，模型看到的开头就成了"自己说过的话"。
            // 这里无条件收口：丢到首条 user 为止；整段没有 user 就整段交给摘要器。
            // messages 始终是 all 的后缀，下面 droppedFromTail 的前缀算术才成立。
            int firstUser = 0;
            while (firstUser < messages.size() && !(messages.get(firstUser) instanceof UserMessage)) {
                firstUser++;
            }
            if (firstUser >= messages.size()) {
                messages = List.of();
            } else if (firstUser > 0) {
                messages = new ArrayList<>(messages.subList(firstUser, messages.size()));
            }
            // 被丢掉的 = reducer 按优先级挑掉的 + 三道硬闸从最老处切掉的前缀。
            // 只算前缀会漏掉 reducer 丢掉的那部分：它们同样永远不会回到 prompt 里，
            // 而且 reducer 丢的恰恰是散落在各处的早期上下文，更是摘要该覆盖的对象。
            int droppedFromTail = Math.max(0, all.size() - messages.size());
            Map<ChatMessageEntity, Integer> originalOrder = new IdentityHashMap<>();
            for (int i = 0; i < entities.size(); i++) {
                originalOrder.put(entities.get(i), i);
            }
            List<ChatMessageEntity> droppedEntities = new ArrayList<>(priorityTrim.removed());
            if (droppedFromTail > 0) {
                droppedEntities.addAll(keptEntities.subList(0, droppedFromTail));
            }
            // 摘要器按时间顺序读，reducer 挑掉的那部分位置是散的，先按原始先后排一遍
            droppedEntities.sort(Comparator.comparingInt(e -> originalOrder.getOrDefault(e, Integer.MAX_VALUE)));
            List<Message> dropped = new ArrayList<>(droppedEntities.size());
            List<Long> droppedIds = new ArrayList<>(droppedEntities.size());
            long droppedMaxId = 0L;
            for (ChatMessageEntity entity : droppedEntities) {
                Message message = toMessage(entity);
                if (message != null) {
                    dropped.add(message);
                    Long id = entity.getId();
                    droppedIds.add(id == null ? 0L : id);
                    if (id != null && id > droppedMaxId) {
                        droppedMaxId = id;
                    }
                }
            }
            return new LoadedHistory(messages, dropped, droppedIds, droppedMaxId);
        } catch (Exception e) {
            log.warn("[graph] 加载会话历史失败，按空历史继续 sessionId={}, err={}", sessionId, e.getMessage());
            return new LoadedHistory(List.of(), List.of());
        }
    }

    /**
     * 历史装载结果。
     *
     * @param messages       真正拼进 prompt 的部分
     * @param dropped        被三道闸丢掉的部分（时间正序），供摘要器压缩
     * @param droppedIds     与 {@code dropped} 逐条对齐的消息主键，用于摘要游标推进
     * @param droppedMaxId   被丢弃消息的最大 id（0 表示没有），写入摘要表的 {@code covered_until_id}
     */
    public record LoadedHistory(List<Message> messages, List<Message> dropped,
                                List<Long> droppedIds, long droppedMaxId) {

        /** 兼容旧调用：没有游标信息时按“全量拿不到”处理。 */
        public LoadedHistory(List<Message> messages, List<Message> dropped) {
            this(messages, dropped, List.of(), 0L);
        }
    }

    /**
     * 把 DB 实体转成 reducer 需要的 {@code Map}（键与 {@code PriorityReducer} 约定一致），
     * 跑完预算裁剪后再用引用相等映射回实体。
     * <p>
     * 这里用 {@link java.util.IdentityHashMap} 而不是内容匹配：同一会话里"继续"、
     * "好的"这类重复内容很常见，内容匹配会把后面那几条映射到第一条上。
     * <p>
     * 只送 user/assistant（与 {@link #toMessage} 口径一致），tool 消息需要 tool_call_id
     * 才能重新配对，跨请求重放容易错位。
     */
    static PriorityTrim trimByPriority(List<ChatMessageEntity> entities,
                                       IChatContextService chatContextService) {
        Map<Map<String, Object>, ChatMessageEntity> entityByMap = new IdentityHashMap<>();
        List<Map<String, Object>> maps = new ArrayList<>();
        for (ChatMessageEntity entity : entities) {
            if (entity == null || entity.getRole() == null) {
                continue;
            }
            if (!"user".equals(entity.getRole()) && !"assistant".equals(entity.getRole())) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", entity.getRole());
            map.put("content", entity.getContent() == null ? "" : entity.getContent());
            maps.add(map);
            entityByMap.put(map, entity);
        }
        if (maps.isEmpty()) {
            return new PriorityTrim(entities, List.of());
        }
        List<Map<String, Object>> keptMaps = chatContextService.trimHistory(maps, DEFAULT_HISTORY_TOKEN_BUDGET);
        if (keptMaps == null || keptMaps.isEmpty()) {
            // 裁剪器返回空属于异常情况，宁可退回未裁剪列表（随后还有三道硬闸兜底），
            // 此时"被挑掉"也是空的——否则会把整段历史都当成待摘要内容。
            return new PriorityTrim(entities, List.of());
        }
        // 用引用相等判断"留下了哪些"：reducer 返回的就是送进去的那批 map 对象，
        // 内容相等的另两条消息不会被误认成 kept。
        Set<Map<String, Object>> keptSet = Collections.newSetFromMap(new IdentityHashMap<>());
        keptSet.addAll(keptMaps);
        List<ChatMessageEntity> kept = new ArrayList<>(keptMaps.size());
        List<ChatMessageEntity> removed = new ArrayList<>();
        // 按原始顺序遍历 maps（而不是 keptMaps，后者可能被打乱），保证 kept 的顺序与历史一致
        for (Map<String, Object> map : maps) {
            ChatMessageEntity entity = entityByMap.get(map);
            if (entity == null) {
                continue;
            }
            if (keptSet.contains(map)) {
                kept.add(entity);
            } else {
                removed.add(entity);
            }
        }
        if (kept.isEmpty()) {
            // 全被挑掉也按异常处理（一条都不留会让 [messages] 从 assistant 开始）
            return new PriorityTrim(entities, List.of());
        }
        return new PriorityTrim(kept, removed);
    }

    /**
     * 优先级裁剪的结果：留下哪些、挑掉哪些。
     * <p>
     * 带上 {@code removed} 是为了让摘要有机会覆盖"被 reducer 挑掉的早期上下文"——
     * 它们同样走不进 prompt，如果只把硬闸切掉的前缀交给摘要器，这部分就是永久失忆。
     *
     * @param kept    进入后续硬闸的实体（保持原始时间顺序）
     * @param removed 被 reducer 挑掉的实体（保持原始时间顺序）
     */
    record PriorityTrim(List<ChatMessageEntity> kept, List<ChatMessageEntity> removed) {
    }

    /**
     * 估算一条消息的 token。
     *
     * <p>统一走 {@link TokenEstimator}：中文一字约一 token、英文四字符约一 token。
     * 早先用的"字符数 / 2"会把中文低估约一半——24000 的总预算对中文就几乎永远不触发，
     * 长会话被撑爆也发现不了。
     */
    public static int estimateTokens(Message message) {
        if (message == null || message.getText() == null) {
            return 0;
        }
        return TokenEstimator.estimateMessage(message.getText());
    }

    /** 闸②：只保留最近 {@code maxTurns} 轮，切点对齐到 user 消息。 */
    static List<Message> trimToRecentTurns(List<Message> messages, int maxTurns) {
        if (messages == null || messages.isEmpty() || maxTurns <= 0) {
            return messages == null ? List.of() : messages;
        }
        int seen = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                seen++;
                if (seen == maxTurns) {
                    return new ArrayList<>(messages.subList(i, messages.size()));
                }
            }
        }
        return messages;
    }

    /**
     * 闸①：从最新往前累加 token，超出预算就从整轮边界截断。
     * <p>
     * 至少要保留最后一轮（即使它就超预算）——否则模型会完全失忆，
     * 那比"上下文偏长"更糟。
     */
    static List<Message> trimToTokenBudget(List<Message> messages, int budget) {
        if (messages == null || messages.isEmpty() || budget <= 0) {
            return messages == null ? List.of() : messages;
        }
        int total = 0;
        int start = messages.size();
        for (int i = messages.size() - 1; i >= 0; i--) {
            int cost = estimateTokens(messages.get(i));
            if (start < messages.size() && total + cost > budget) {
                break;
            }
            total += cost;
            start = i;
        }
        if (start <= 0) {
            return messages;
        }
        // 切点必须落在 user 上
        int aligned = start;
        while (aligned < messages.size() && !(messages.get(aligned) instanceof UserMessage)) {
            aligned++;
        }
        if (aligned >= messages.size()) {
            // 极端情况：切点之后没有 user 了（预算只够放最后一条 assistant）。
            // 失忆比超长更糟，回退到"最后一条 user 开始"的整轮。
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i) instanceof UserMessage) {
                    return new ArrayList<>(messages.subList(i, messages.size()));
                }
            }
            return new ArrayList<>(messages.subList(messages.size() - 1, messages.size()));
        }
        return new ArrayList<>(messages.subList(aligned, messages.size()));
    }

    /**
     * 闸③：条数上限。
     * <p>
     * 与闸①②一样，切点必须落在 user 上：一轮里可能有多条 tool 消息，
     * 直接按条数截会把"提问"留在外面、把"回答"留在里面，模型看到的第一条就是 assistant。
     */
    static List<Message> capMessages(List<Message> messages, int maxMessages) {
        if (messages == null || messages.isEmpty() || maxMessages <= 0 || messages.size() <= maxMessages) {
            return messages == null ? List.of() : messages;
        }
        int start = messages.size() - maxMessages;
        // 切点往后挪到最近的 user；找不到（极端情况）就整体保留，交给上层的 token 预算兜底
        int aligned = start;
        while (aligned < messages.size() && !(messages.get(aligned) instanceof UserMessage)) {
            aligned++;
        }
        if (aligned >= messages.size()) {
            return messages;
        }
        return new ArrayList<>(messages.subList(aligned, messages.size()));
    }

    /**
     * 落库时 tool 行带一行命令回显（{@link GraphSshToolService} 执行前由 {@code [observe]} 写入），
     * 用来在历史里还原"这条命令已经执行过"。
     */
    public static final String COMMAND_TRACE_PREFIX = "[命令] ";

    /**
     * 历史重建时，折叠出来的助手痕迹前缀。
     * <p>
     * 与 {@link #COMMAND_TRACE_PREFIX} 是两个方向：前者是落库格式（tool 行首行），
     * 后者是重建格式（{@code assistant} 消息正文）。两者分开是为了"改落库格式不影响重建格式"。
     */
    public static final String COMMAND_TRACE_OUTPUT_PREFIX = "曾执行命令：";

    /**
     * 近期痕迹保留输出摘要的条数；更早的只留"命令 + 时间 + 状态"。
     * <p>
     * 5 条的取值理由：一次排障的"有效证据"通常集中在前几次命令（{@code df -h}、{@code systemctl status}、
     * {@code tail}），再往前翻的命令多数已经失败或被推翻，只留命令就够防重复执行了。
     * 每条约 40~320 字符，5 条约 600~1600 字符，占 6000 token 预算的 3%~8%，不抢对话的位置。
     */
    private static final int TRACE_DETAIL_LIMIT = 5;

    /** 成功痕迹的输出摘要上限（首行通常很短，这个值只防脏数据）。 */
    private static final int TRACE_SUCCESS_CHARS = 120;

    /** 失败痕迹的输出摘要上限（错误行偶尔很长，多给一点线索便于跨轮定位）。 */
    private static final int TRACE_FAILURE_CHARS = 300;

    /** 输出超过该长度时在痕迹里标注总字符数，让模型知道"摘要之外还有内容"。 */
    private static final int TRACE_SIZE_HINT_THRESHOLD = 400;

    /** 痕迹里的时间格式：不写年份，省 3 个字符且足够区分同一会话内的先后。 */
    private static final DateTimeFormatter TRACE_TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /**
     * 把 tool 行折叠成 {@code assistant} 痕迹：
     * <pre>
     *   曾执行命令：df -h（11-14 14:05，成功）→ Filesystem Size Used Avail Use% Mounted on｜输出 3,412 字符
     *   曾执行命令：systemctl restart nginx（11-14 14:07，失败）→ Job for nginx.service failed because ...
     *   曾执行命令：df -h（11-14 13:52，成功）          &lt;-- 更早的痕迹只留命令+状态
     * </pre>
     * <p>
     * 为什么不是直接把 tool 行放回 prompt：{@code ToolResponseMessage} 必须与配对的
     * {@code assistant(tool_calls)} 相邻，跨请求重放时 tool_call_id 很容易错位，
     * 轻则模型困惑、重则 400。所以用普通文本承载。
     * <p>
     * 为什么不能只留命令（早期实现）：模型只知道"跑过"，不知道"成没成、结果是什么"，
     * 于是把上一步的失败当成功继续往下推，或者重复执行同一条命令。落库的 tool 行里
     * 本来就有完整输出（{@code [命令] cmd\n输出...}），只是装载时被整段丢掉了——
     * 这里按"成败 + 一行摘要"取回来，代价约 40~200 字符/条。
     * <p>
     * 为什么只有最近 {@link #TRACE_DETAIL_LIMIT} 条带摘要：历史预算 6000 token 是硬约束，
     * 100 条候选池若每条都带摘要（最坏 300 字符）就是 3 万字符，会把真实对话挤掉。
     * 更早的痕迹退化成"命令+时间+状态"，既避免模型重复执行，又不再占预算。
     *
     * @param entities 原始实体（含 tool 行）
     * @return 不含 tool 行的实体列表；没有可还原的 tool 行时原样返回
     */
    static List<ChatMessageEntity> expandToolTraces(List<ChatMessageEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return entities;
        }
        int toolTotal = 0;
        for (ChatMessageEntity entity : entities) {
            if (isToolEntity(entity)) {
                toolTotal++;
            }
        }
        List<ChatMessageEntity> expanded = new ArrayList<>(entities.size());
        int toolSeen = 0;
        for (ChatMessageEntity entity : entities) {
            if (!isToolEntity(entity)) {
                expanded.add(entity);
                continue;
            }
            toolSeen++;
            CommandTrace trace = parseCommandTrace(entity.getContent(), entity.getToolName());
            if (trace == null) {
                // 非 executeCommand 的工具（dispatchSubAgent / wikiSearch 等）或早期数据没有回显，保持跳过
                continue;
            }
            // 候选池按时间正序，靠后的才是最近的；只有最近 N 条值得带输出摘要
            boolean detailed = toolTotal - toolSeen < TRACE_DETAIL_LIMIT;
            String content = buildTraceContent(trace, entity.getCreatedAt(), detailed);
            ChatMessageEntity folded = new ChatMessageEntity();
            folded.setSessionId(entity.getSessionId());
            folded.setRole("assistant");
            folded.setContent(content);
            // token 数必须按**折叠后的内容**重算：原值是按"[命令] + 完整输出"估的，
            // 直接沿用会让历史预算虚高好几倍，把真正该留的对话裁掉。
            folded.setTokenCount(TokenEstimator.estimateMessage(content));
            folded.setCreatedAt(entity.getCreatedAt());
            expanded.add(folded);
        }
        return expanded;
    }

    /** 折叠后的痕迹正文：命令 + 时间 + 成败 +（可选）代表行与输出规模。 */
    private static String buildTraceContent(CommandTrace trace, Date createdAt, boolean detailed) {
        StringBuilder sb = new StringBuilder(COMMAND_TRACE_OUTPUT_PREFIX.length() + trace.command().length() + 24);
        sb.append(COMMAND_TRACE_OUTPUT_PREFIX).append(trace.command());
        sb.append("（").append(formatTraceTime(createdAt)).append("，")
                .append(trace.success() ? "成功" : "失败").append("）");
        if (!detailed) {
            return sb.toString();
        }
        String keyLine = ToolResultEvaluator.keyLine(trace.output(), trace.success());
        if (!keyLine.isBlank()) {
            sb.append("→ ").append(clipTraceText(keyLine,
                    trace.success() ? TRACE_SUCCESS_CHARS : TRACE_FAILURE_CHARS));
        }
        if (trace.output().length() > TRACE_SIZE_HINT_THRESHOLD) {
            // 只给了一行摘要，必须让模型知道"结果不止这些"，否则它会以为输出就这么短
            sb.append("｜输出 ").append(trace.output().length()).append(" 字符");
        }
        return sb.toString();
    }

    /**
     * 解析落库格式 {@code "[命令] cmd\n输出..."}。
     * <p>
     * 成败是**装载时复判**的：落库时 {@code [observe]} 已经判过一次（还用它推进任务态、
     * 写失败信号记忆），但那个结论没有落进 chat_message（实体没有 status 字段），
     * 所以这里用同一套判据（{@link ToolResultEvaluator#isSuccess}）对输出重算一次。
     * 判据是错误特征词而不是退出码，因此结论是"像失败"而不是"确定失败"。
     *
     * @param content  落库内容
     * @param toolName 工具名（落库时写入，可为 null——早期数据没有）
     * @return 解析结果；不是命令回显格式时返回 null
     */
    private static CommandTrace parseCommandTrace(String content, String toolName) {
        if (content == null || !content.startsWith(COMMAND_TRACE_PREFIX)) {
            return null;
        }
        int end = content.indexOf('\n');
        String command = (end > 0
                ? content.substring(COMMAND_TRACE_PREFIX.length(), end)
                : content.substring(COMMAND_TRACE_PREFIX.length())).trim();
        if (command.isEmpty()) {
            return null;
        }
        String output = (end > 0 && end + 1 <= content.length()) ? content.substring(end + 1) : "";
        boolean success = ToolResultEvaluator.isSuccess(toolName, output);
        return new CommandTrace(command, output, success);
    }

    private static boolean isToolEntity(ChatMessageEntity entity) {
        return entity != null && "tool".equalsIgnoreCase(entity.getRole());
    }

    /** 形如 {@code 11-14 14:05}；没有时间戳的早期数据退化为"时间未知"。 */
    private static String formatTraceTime(Date createdAt) {
        if (createdAt == null) {
            return "时间未知";
        }
        return TRACE_TIME_FORMAT.format(createdAt.toInstant().atZone(ZoneId.systemDefault()));
    }

    private static String clipTraceText(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…";
    }

    /** 一条 tool 行解析后的事实：命令、输出、复判出来的成败。 */
    private record CommandTrace(String command, String output, boolean success) {
    }

    /**
     * 单条实体转 Spring AI 消息；tool/system 与空内容返回 null（跳过）。
     * <p>
     * 空 content 的 assistant 是"纯工具调用轮"，它的命令信息由 {@link #expandToolTraces} 从配对的
     * tool 行还原，因此这里仍返回 null。
     */
    private static Message toMessage(ChatMessageEntity entity) {
        if (entity == null || entity.getContent() == null || entity.getContent().isBlank()) {
            return null;
        }
        String role = entity.getRole();
        String content = entity.getContent();
        if ("user".equalsIgnoreCase(role)) {
            // 历史里存的是原始用户输入；若碰巧带了信封标记，取出用户真正说的那句话
            return new UserMessage(PromptEnvelope.extractUserMessage(content));
        }
        if ("assistant".equalsIgnoreCase(role)) {
            return new AssistantMessage(content);
        }
        return null;
    }
}
