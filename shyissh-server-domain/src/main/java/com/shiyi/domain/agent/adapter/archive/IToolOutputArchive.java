package com.shiyi.domain.agent.adapter.archive;

/**
 * 工具输出归档接口。
 * <p>
 * 解决的问题：一条命令的输出可能有几万字符，回填给模型必须截断（否则一次
 * {@code tail -n 5000} 就能把上下文撑爆），但截断意味着"被丢掉的那部分永久消失"——
 * 事后复盘、评测集回放、人工排查"当时到底报了什么错"都做不到。
 * <p>
 * 所以把"给模型看的"和"留档的"分开：给模型的是截断版（头尾各留一段），
 * 完整原文落到文件里，路径回填给模型与数据库记录。模型知道有这份文件、
 * 知道它有多大，就能判断要不要用更精确的命令再查一次。
 * <p>
 * 放在 domain 的 adapter 包下、由 infrastructure 实现，是因为"落到哪个介质"
 * 是基础设施细节（本地文件、对象存储、S3 都行），领域层只关心"归档这件事"。
 *
 * @author shiyi
 */
public interface IToolOutputArchive {

    /**
     * 归档一份过长的工具输出。
     *
     * @param sessionId 会话 ID，用于分目录，便于按会话清理
     * @param toolCallId 工具调用 ID（模型生成），用于同一轮内区分不同命令
     * @param toolName   工具名
     * @param content    完整输出原文
     * @return 可读的归档路径；归档失败返回 null（调用方据此只回填截断版，不阻断主流程）
     */
    String archive(String sessionId, String toolCallId, String toolName, String content);
}