package com.shiyi.domain.agent.service.context;

/**
 * 全仓统一的 token 估算口径。
 * <p>
 * 为什么需要单独一个类：这个数字同时被三处使用——历史裁剪的预算判断（{@code GraphSession}）、
 * 优先级 reducer 的分组回填（{@code PriorityReducer}）、消息落库时的 {@code tokenCount} 字段，
 * 以及每轮请求把用量累加到 {@code TOTAL_TOKENS} 与上限比较的地方。三处口径不一致时，
 * 会出现"裁剪器认为放得下、真实请求却超限"，或"预算闸永远不触发"这类很难查的问题。
 *
 * <h2>为什么不用 {@code 字符数 / 2}</h2>
 * 这个口径对中文是低估的：主流 BPE 词表（qwen / deepseek / gpt 系列）里一个常用汉字通常是
 * 1~1.5 个 token，即 1000 字的回答≈1000~1500 token，而 {@code length()/2} 只算 500。
 * 低估的直接后果是 24000 的总预算对中文几乎永远不触发，长会话被撑爆而不自知。
 * 反过来对英文它又高估（4 个字符≈1 token，{@code /2} 算成 2 个）。
 *
 * <h2>这里的口径</h2>
 * <ul>
 *   <li>中日韩文字与全角标点：1 字符 ≈ 1 token（略保守，宁可高估）；</li>
 *   <li>ASCII 及其它字符：4 字符 ≈ 1 token，向上取整；</li>
 *   <li>每条消息额外加 {@link #MESSAGE_OVERHEAD}，覆盖 role、分隔符、tool_call 结构等固定开销。</li>
 * </ul>
 * 它仍然是估算而不是真实分词：引入 tokenizer（jtokkit 等）要把词表打进包、还要跟上游模型版本对齐，
 * 对一个以"够用就好"为目标的预算闸来说不值得；这里的目标是**量级正确、方向偏保守**。
 *
 * @author shiyi
 */
public final class TokenEstimator {

    /**
     * 单条消息的固定开销：role 标记、分隔符、消息包装。
     * <p>
     * OpenAI 官方文档给的参考值是每条消息约 3~4 token，这里取 4，宁可略高。
     */
    public static final int MESSAGE_OVERHEAD = 4;

    private TokenEstimator() {
    }

    /**
     * 估算一段文本的 token 数。
     *
     * @param text 文本，可为 null
     * @return 估算值；空文本返回 0
     */
    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        // 英文按 4 字符 1 token 向上取整；中文按 1 字 1 token
        return cjk + (other + 3) / 4;
    }

    /**
     * 估算一条消息（含包装开销）的 token 数。
     *
     * @param text 消息正文，可为 null
     * @return 估算值；正文为空时仍返回固定开销
     */
    public static int estimateMessage(String text) {
        return estimate(text) + MESSAGE_OVERHEAD;
    }

    /**
     * 判断是否中日韩文字或全角标点。
     * <p>
     * 覆盖：CJK 统一表意文字（含扩展 A）、CJK 标点、日文假名、韩文音节、全角字符。
     * 这些字符在主流 BPE 词表里基本是"一字一 token"，所以单独计数。
     *
     * @param c 字符
     * @return 是否按"一字一 token"计算
     */
    public static boolean isCjk(char c) {
        if (c < 0x2E80) {
            return false;
        }
        return (c >= 0x2E80 && c <= 0x9FFF)     // CJK 部首补充 / 统一表意文字
                || (c >= 0x3000 && c <= 0x303F)  // CJK 标点
                || (c >= 0x3040 && c <= 0x30FF)  // 日文平假名 / 片假名
                || (c >= 0x3400 && c <= 0x4DBF)  // 扩展 A
                || (c >= 0xAC00 && c <= 0xD7AF)  // 韩文音节
                || (c >= 0xF900 && c <= 0xFAFF)  // 兼容表意文字
                || (c >= 0xFF00 && c <= 0xFFEF); // 全角字符
    }
}