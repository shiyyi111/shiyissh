package com.shiyi.domain.agent.graph.support;

import com.alibaba.fastjson2.JSON;
import com.shiyi.domain.agent.service.prompt.PromptEnvelope;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图里对消息历史的读操作集合。
 * <p>
 * 这几个方法直接在 Spring AI 的 {@code Message} 上做同样的事，抽出来是为了：
 * <ul>
 *   <li>{@code EnrichNode} 每轮都要构一次 history / recentCommands，逻辑只写一遍；</li>
 *   <li>单测可以直接测这些纯函数，不用起图。</li>
 * </ul>
 *
 * @author shiyi
 */
public final class GraphMessages {

    /** 最近命令最多保留几条。 */
    public static final int MAX_RECENT_COMMANDS = 5;

    private GraphMessages() {
    }

    /** 从图状态里取消息列表（类型不安全，统一在这里做一次转换）。 */
    @SuppressWarnings("unchecked")
    public static List<Message> messages(com.alibaba.cloud.ai.graph.OverAllState state) {
        Object value = state.value(com.shiyi.domain.agent.graph.GraphStateKeys.MESSAGES).orElse(null);
        if (value instanceof List<?> list) {
            return (List<Message>) list;
        }
        return List.of();
    }

    /** 最后一条用户消息的下标，没有则 -1。 */
    public static int lastUserIndex(List<Message> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    /** 最后一条用户消息的文本（未剥信封）。 */
    public static String lastUserText(List<Message> messages) {
        int index = lastUserIndex(messages);
        if (index < 0) {
            return "";
        }
        String text = messages.get(index).getText();
        return text == null ? "" : text;
    }

    /**
     * 把消息历史转成 {@code List<Map>} 交给 PromptService。
     * <p>
     * 跳过工具响应（模型不需要把 ToolResponseMessage 当成对话内容）、跳过空文本；
     * 用户消息要先剥掉信封，否则历史里会带着上一轮的上下文再被拼一次。
     *
     * @param messages     当前消息列表
     * @param excludeIndex 需要排除的下标（当前这轮的用户消息），-1 表示不排除
     */
    public static List<Map<String, Object>> buildHistory(List<Message> messages, int excludeIndex) {
        List<Map<String, Object>> history = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (i == excludeIndex) {
                continue;
            }
            Message message = messages.get(i);
            if (message instanceof ToolResponseMessage) {
                continue;
            }
            String text = message.getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            String role = message instanceof UserMessage ? "user" : "assistant";
            if ("user".equals(role)) {
                text = PromptEnvelope.extractUserMessage(text);
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("role", role);
            row.put("content", text);
            history.add(row);
        }
        return history;
    }

    /** 从工具调用里提取执行过的命令（末 N 条）。 */
    public static List<String> extractCommands(List<Message> messages) {
        List<String> commands = new ArrayList<>();
        for (Message message : messages) {
            if (!(message instanceof AssistantMessage assistant)) {
                continue;
            }
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                if (!"executeCommand".equals(call.name())) {
                    continue;
                }
                String command = parseCommand(call.arguments());
                if (command != null && !command.isBlank() && !"null".equals(command)) {
                    commands.add(command);
                }
            }
        }
        if (commands.size() <= MAX_RECENT_COMMANDS) {
            return commands;
        }
        return new ArrayList<>(commands.subList(commands.size() - MAX_RECENT_COMMANDS, commands.size()));
    }

    /**
     * 折叠重复的工具输出。
     * <p>
     * 判定"重复"的口径是<b>工具名 + 参数 + 输出内容三者都相同</b>，缺一不可。
     * 只看工具名和参数是不够的：同一条 {@code df -h} 连打三次，输出可能因为磁盘正在被写满而完全不同，
     * 这个差异恰恰是排查的关键信息；把它折叠成"与上次相同"等于把最有价值的证据删掉，
     * 还会主动误导模型（占位文案会告诉它"结论参考第 1 次"）。
     * <p>
     * 真正被折叠的是"输出也逐字相同"的重复，例如连续几轮 {@code uptime} 结果没变。
     * 折叠只改<b>回填给模型的文本</b>，不动 tool_call_id，也不动落库内容——
     * 配对关系必须完整（模型侧要能对上 tool_calls），原始输出在 [observe] 落库时已单独保存。
     * <p>
     * 方法幂等：已经折叠过的文本再跑一遍，得到的还是同一段说明——
     * 因为占位文本与第 N 次不同，不会再次命中。
     * <p>
     * 方法幂等：已经折叠过的文本再跑一遍，得到的还是同一段说明。
     *
     * @param messages 当前消息列表（不会被修改）
     * @return 折叠后的新列表；没有重复时原样返回
     */
    public static List<Message> foldDuplicateToolOutputs(List<Message> messages) {
        // 指纹 = 工具名 + 完整参数（不止 command：dispatchSubAgent 的参数是任务描述，不能只比工具名）
        Map<String, String> fingerprintByCallId = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message instanceof AssistantMessage assistant) {
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    fingerprintByCallId.put(call.id(), call.name() + "|" + normalize(call.arguments()));
                }
            }
        }
        if (fingerprintByCallId.isEmpty()) {
            return messages;
        }

        Map<String, Integer> firstSeenAt = new LinkedHashMap<>();
        List<Message> folded = new ArrayList<>(messages.size());
        int ordinal = 0;
        boolean anyFolded = false;
        for (Message message : messages) {
            if (!(message instanceof ToolResponseMessage toolResponse)) {
                folded.add(message);
                continue;
            }
            List<ToolResponseMessage.ToolResponse> rebuilt = new ArrayList<>(toolResponse.getResponses().size());
            boolean changed = false;
            for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
                ordinal++;
                String callPrint = fingerprintByCallId.getOrDefault(response.id(),
                        response.name() + "|" + normalize(response.responseData()));
                // 指纹必须带上输出：同一命令两次执行输出不同（磁盘变满、负载变化、日志新增）时，
                // 第二次是"新信息"而不是"重复"，折叠掉就丢了判断依据。
                String fingerprint = callPrint + "#" + normalize(response.responseData());
                Integer firstAt = firstSeenAt.get(fingerprint);
                if (firstAt == null) {
                    firstSeenAt.put(fingerprint, ordinal);
                    rebuilt.add(response);
                    continue;
                }
                rebuilt.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(),
                        foldNote(firstAt, ordinal)));
                changed = true;
                anyFolded = true;
            }
            folded.add(changed ? ToolResponseMessage.builder().responses(rebuilt).build() : message);
        }
        return anyFolded ? folded : messages;
    }

    /** 折叠后的占位内容：保持 JSON 形状，模型一眼能看出"这条命令执行过、输出与之前那次完全相同"。 */
    private static String foldNote(int firstAt, int currentAt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "duplicate");
        body.put("stdout", "[重复调用] 与第 " + firstAt + " 次执行的是同一条命令（本次为第 " + currentAt
                + " 次），且两次输出完全相同，已折叠。结论可参考第 " + firstAt + " 次的结果；"
                + "若关心两次之间发生了什么变化，请执行能体现差异的命令（例如只看关键行：df -h | tail -2、grep 关键字）。");
        return JSON.toJSONString(body);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String parseCommand(String arguments) {
        try {
            return JSON.parseObject(arguments).getString("command");
        } catch (Exception e) {
            return null;
        }
    }
}