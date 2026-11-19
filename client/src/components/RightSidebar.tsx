import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Icon, type IconName } from "../lib/icons";
import { formatTime } from "../lib/format";
import { AGENTS, findAgent, type AgentId } from "../lib/ai";
import {
  useChatStore,
  type ChatMessage,
  type DispatchStep,
  type PendingApproval,
  type ToolCallRecord,
} from "../store/chatStore";
import { useConnectionStore } from "../stores/connectionStore";
import {
  RIGHT_MAX,
  RIGHT_MIN,
  useUiStore,
} from "../store/uiStore";
import { usePrefsStore } from "../store/prefsStore";
import { Resizer } from "./Resizer";

const QUICK_ACTIONS: { icon: IconName; label: string; prompt: string }[] = [
  {
    icon: "wifi",
    label: "检查服务器状态",
    prompt: "帮我检查一下当前服务器状态，看看有没有异常",
  },
  {
    icon: "file",
    label: "分析最近日志",
    prompt: "最近日志里有没有报错？给我排查思路",
  },
  {
    icon: "code",
    label: "生成部署脚本",
    prompt: "帮我写一个可以回滚的部署脚本",
  },
  {
    icon: "layers",
    label: "Docker 容器排查",
    prompt: "容器一直重启，怎么排查？",
  },
];

export function RightSidebar() {
  const visible = useUiStore((s) => s.rightVisible);
  const width = useUiStore((s) => s.rightWidth);
  const setWidth = useUiStore((s) => s.setRightWidth);
  const toggleRight = useUiStore((s) => s.toggleRight);
  const notify = useUiStore((s) => s.notify);

  const sessions = useChatStore((s) => s.sessions);
  const activeSessionId = useChatStore((s) => s.activeSessionId);
  const newSession = useChatStore((s) => s.newSession);
  const selectSession = useChatStore((s) => s.selectSession);
  const send = useChatStore((s) => s.send);
  const streaming = useChatStore((s) => s.streaming);

  const [menu, setMenu] = useState<"history" | "agent" | null>(null);

  const session = sessions.find((s) => s.id === activeSessionId) ?? null;
  const messages = session?.messages ?? [];

  if (!visible) return null;

  return (
    <>
      <Resizer
        side="right"
        width={width}
        min={RIGHT_MIN}
        max={RIGHT_MAX}
        onResize={setWidth}
        onDoubleClick={() => setWidth(380)}
      />
      <aside className="right-sidebar" style={{ width }}>
        <div className="right-head">
          <div className="right-title">
            <span className="spark">
              <Icon name="sparkles" size={15} />
            </span>
            AI 助手
          </div>
          <span style={{ flex: 1 }} />
          <div style={{ position: "relative" }}>
            <button
              className="icon-btn"
              title="历史对话"
              onClick={() => setMenu(menu === "history" ? null : "history")}
            >
              <Icon name="history" size={15} />
            </button>
            {menu === "history" && (
              <div className="menu" style={{ right: 0, top: 32, width: 240 }}>
                {sessions.length === 0 ? (
                  <div style={{ padding: "8px 10px", color: "var(--text-muted)", fontSize: 12 }}>
                    暂无历史对话
                  </div>
                ) : (
                  sessions.slice(0, 8).map((s) => (
                    <button
                      key={s.id}
                      onClick={() => {
                        selectSession(s.id);
                        setMenu(null);
                      }}
                      style={{
                        color:
                          s.id === activeSessionId ? "var(--accent)" : undefined,
                      }}
                    >
                      <Icon name="message-plus" size={13} />
                      <span
                        style={{
                          overflow: "hidden",
                          textOverflow: "ellipsis",
                          whiteSpace: "nowrap",
                        }}
                      >
                        {s.title}
                      </span>
                    </button>
                  ))
                )}
              </div>
            )}
          </div>
          <button
            className="icon-btn"
            title="新建对话"
            onClick={() => {
              newSession();
              setMenu(null);
            }}
          >
            <Icon name="message-plus" size={15} />
          </button>
          <button className="icon-btn" title="收起 AI 助手" onClick={toggleRight}>
            <Icon name="close" size={14} />
          </button>
        </div>

        <div className="chat-scroll" id="chat-scroll">
          {messages.length === 0 ? (
            <ChatEmpty
              onPick={(prompt) => {
                if (!activeSessionId) newSession();
                send(prompt);
              }}
              onExplain={() =>
                notify("拆解工具：把复杂任务拆成可执行步骤（演示）")
              }
            />
          ) : (
            messages.map((m) => <MessageRow key={m.id} message={m} />)
          )}
        </div>

        <Composer
          streaming={streaming}
          onSend={send}
          agentMenuOpen={menu === "agent"}
          onToggleAgentMenu={() =>
            setMenu(menu === "agent" ? null : "agent")
          }
          onCloseAgentMenu={() => setMenu(null)}
        />
      </aside>
    </>
  );
}

function ChatEmpty({
  onPick,
  onExplain,
}: {
  onPick: (prompt: string) => void;
  onExplain: () => void;
}) {
  const connections = useConnectionStore((s) => s.connections);
  const activeId = useConnectionStore((s) => s.currentConnectionId);
  const active = connections.find((s) => s.id === activeId) ?? null;

  return (
    <div className="chat-empty">
      <span className="chat-empty-avatar">
        <Icon name="sparkles" size={26} />
      </span>
      <h3>开始对话</h3>
      <p>
        {active
          ? `可以问我关于 ${active.name} 的问题；打开终端后，我还能直接在上面执行命令。`
          : "连接服务器并打开终端后，我可以直接执行命令、读日志、写脚本。"}
      </p>

      <div className="quick-actions">
        {QUICK_ACTIONS.map((qa) => (
          <button
            key={qa.label}
            className="quick-action"
            onClick={() => onPick(qa.prompt)}
          >
            <span className="qa-icon">
              <Icon name={qa.icon} size={15} />
            </span>
            {qa.label}
          </button>
        ))}
        <button className="quick-action" onClick={onExplain}>
          <span className="qa-icon">
            <Icon name="flask" size={15} />
          </span>
          拆解工具：把任务拆成步骤
        </button>
      </div>
    </div>
  );
}

function MessageRow({ message }: { message: ChatMessage }) {
  const isUser = message.role === "user";
  return (
    <div className={`msg ${isUser ? "user" : "assistant"}`}>
      <span className="msg-avatar">{isUser ? "我" : <Icon name="sparkles" size={13} />}</span>
      <div className="msg-body">
        <div className="bubble">
          {message.pending && !message.content ? (
            <span className="typing" aria-label="正在生成">
              <i />
              <i />
              <i />
            </span>
          ) : isUser ? (
            <span style={{ whiteSpace: "pre-wrap" }}>{message.content}</span>
          ) : (
            <MarkdownView content={message.content} />
          )}
        </div>
        {!isUser && message.pending && message.node && (
          <div className="msg-node">
            <Icon name="sparkles" size={11} />
            <span>{NODE_LABELS[message.node] ?? message.node}</span>
          </div>
        )}
        {!isUser && <DispatchSteps steps={message.steps} />}
        {!isUser && <ToolCallList tools={message.tools} />}
        {!isUser && message.approval && <ApprovalCard approval={message.approval} />}
        {message.error && (
          <div className="msg-error">
            <Icon name="close" size={11} />
            <span>{message.error}</span>
          </div>
        )}
        {!message.pending && (
          <span className="msg-time">{formatTime(message.createdAt)}</span>
        )}
      </div>
    </div>
  );
}

/** 图节点 → 人话（后端 node 事件里是英文节点名） */
const NODE_LABELS: Record<string, string> = {
  intent: "理解你的意图…",
  enrich: "整理上下文…",
  llm: "思考中…",
  guard: "检查命令安全性…",
  tool: "执行命令中…",
  observe: "分析执行结果…",
  approval: "等待你确认…",
  answer: "生成回答…",
};

/** 子智能体派发过程 */
function DispatchSteps({ steps }: { steps?: DispatchStep[] }) {
  if (!steps || steps.length === 0) return null;
  const running = new Map<string, DispatchStep>();
  return (
    <div className="dispatch-list">
      {steps.map((step, index) => {
        const key = `${step.agent}-${index}`;
        if (step.kind === "dispatch_start") running.set(step.agent, step);
        const status =
          step.kind === "dispatch_start"
            ? "running"
            : step.status === "error"
              ? "error"
              : "success";
        return (
          <div key={key} className={`dispatch-card ${status}`}>
            <Icon name="layers" size={11} />
            <span className="dispatch-agent">{step.agent}</span>
            <span className="dispatch-state">
              {step.kind === "dispatch_start"
                ? "已派发"
                : status === "error"
                  ? "失败"
                  : `完成${step.elapsedMs ? ` · ${(step.elapsedMs / 1000).toFixed(1)}s` : ""}`}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/** 需要人工确认的命令：不点确认，图会停在 approval 节点直到超时 */
function ApprovalCard({ approval }: { approval: PendingApproval }) {
  const decide = useChatStore((s) => s.decide);
  const streaming = useChatStore((s) => s.streaming);
  return (
    <div className="approval-card">
      <div className="approval-head">
        <Icon name="lock" size={12} />
        <span>这条命令需要你确认</span>
      </div>
      <pre className="approval-command">{approval.content}</pre>
      <div className="approval-actions">
        <button
          type="button"
          className="approval-approve"
          disabled={streaming}
          onClick={() => decide(approval.approvalId, true)}
        >
          批准执行
        </button>
        <button
          type="button"
          className="approval-reject"
          disabled={streaming}
          onClick={() => decide(approval.approvalId, false)}
        >
          驳回
        </button>
      </div>
    </div>
  );
}

/** AI 执行过的 SSH 命令及输出 */
function ToolCallList({ tools }: { tools?: ToolCallRecord[] }) {
  if (!tools || tools.length === 0) return null;
  return (
    <div className="tool-list">
      {tools.map((tool) => (
        <div key={tool.toolCallId} className={`tool-card ${tool.status}`}>
          <div className="tool-head">
            <Icon name="terminal" size={12} />
            <span className="tool-name">{tool.toolName}</span>
            <span className="tool-status">
              {tool.status === "running" ? "执行中" : tool.status === "error" ? "失败" : "已执行"}
            </span>
          </div>
          {tool.content ? (
            <pre className="tool-output">{tool.content.trim()}</pre>
          ) : (
            <div className="tool-empty">等待命令输出…</div>
          )}
        </div>
      ))}
    </div>
  );
}

function MarkdownView({ content }: { content: string }) {
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          pre: ({ children }) => <CodeBlock>{children}</CodeBlock>,
          a: ({ children, href }) => (
            <a href={href} target="_blank" rel="noreferrer">
              {children}
            </a>
          ),
          table: ({ children }) => (
            <div className="md-table-wrap">
              <table>{children}</table>
            </div>
          ),
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

function CodeBlock({ children }: { children?: React.ReactNode }) {
  const [copied, setCopied] = useState(false);
  const preRef = useRef<HTMLPreElement>(null);

  const copy = useCallback(async () => {
    const text = preRef.current?.innerText ?? "";
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {
      setCopied(false);
    }
  }, []);

  return (
    <div style={{ position: "relative" }}>
      <pre ref={preRef}>{children}</pre>
      <button
        className="icon-btn"
        onClick={copy}
        title="复制代码"
        style={{
          position: "absolute",
          top: 4,
          right: 4,
          width: 24,
          height: 24,
          background: "var(--bg-elevated)",
          border: "1px solid var(--border)",
        }}
      >
        <Icon name={copied ? "check" : "copy"} size={12} />
      </button>
    </div>
  );
}

interface ComposerProps {
  streaming: boolean;
  onSend: (text: string) => void;
  agentMenuOpen: boolean;
  onToggleAgentMenu: () => void;
  onCloseAgentMenu: () => void;
}

function Composer({
  streaming,
  onSend,
  agentMenuOpen,
  onToggleAgentMenu,
  onCloseAgentMenu,
}: ComposerProps) {
  const [text, setText] = useState("");
  const [boxHeight, setBoxHeight] = useState(96);
  const taRef = useRef<HTMLTextAreaElement>(null);
  const agentId = useChatStore((s) => s.agentId);
  const setAgent = useChatStore((s) => s.setAgent);
  const stop = useChatStore((s) => s.stop);
  const sendWithEnter = usePrefsStore((s) => s.sendWithEnter);
  const agent = findAgent(agentId);

  const submit = () => {
    if (!text.trim() || streaming) return;
    onSend(text);
    setText("");
  };

  // 拖拽调整输入区高度
  const dragState = useRef<{ y: number; h: number } | null>(null);
  const onDragStart = (e: React.PointerEvent<HTMLDivElement>) => {
    dragState.current = { y: e.clientY, h: boxHeight };
    e.currentTarget.setPointerCapture(e.pointerId);
    const onMove = (ev: PointerEvent) => {
      if (!dragState.current) return;
      const next = dragState.current.h - (ev.clientY - dragState.current.y);
      setBoxHeight(Math.min(280, Math.max(64, next)));
    };
    const onUp = () => {
      dragState.current = null;
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
    };
    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
  };

  useEffect(() => {
    if (taRef.current) taRef.current.focus();
  }, []);

  return (
    <div className="composer">
      <div
        className="composer-drag"
        onPointerDown={onDragStart}
        title="拖拽调整输入框高度"
      />
      <div className="composer-box">
        <textarea
          ref={taRef}
          value={text}
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              if (sendWithEnter) {
                e.preventDefault();
                submit();
              }
            } else if (e.key === "Enter" && e.ctrlKey) {
              e.preventDefault();
              submit();
            }
          }}
          placeholder={`向 ${agent.name} 提问…（Enter 发送，Shift+Enter 换行）`}
          style={{ height: boxHeight - 44 }}
          spellCheck={false}
        />
        <div className="composer-tools">
          <button
            className="icon-btn"
            title="添加附件"
            onClick={() => useUiStore.getState().notify("附件上传：演示占位")}
          >
            <Icon name="paperclip" size={15} />
          </button>
          <button
            className="icon-btn"
            title="插入图片"
            onClick={() => useUiStore.getState().notify("图片输入：演示占位")}
          >
            <Icon name="image" size={15} />
          </button>
          <button
            className="icon-btn"
            title="收藏此对话"
            onClick={() => useUiStore.getState().notify("已收藏当前对话")}
          >
            <Icon name="star" size={15} />
          </button>
          <span className="spacer" />
          {streaming ? (
            <button className="send-btn" title="停止生成" onClick={stop}>
              <Icon name="close" size={14} />
            </button>
          ) : (
            <button
              className="send-btn"
              title="发送"
              disabled={!text.trim()}
              onClick={submit}
            >
              <Icon name="send" size={14} />
            </button>
          )}
        </div>
      </div>

      <div className="composer-foot">
        <div style={{ position: "relative" }}>
          <button className="model-select" onClick={onToggleAgentMenu}>
            <Icon name="cpu" size={12} />
            {agent.name}
            <Icon name="chevron-down" size={11} />
          </button>
          {agentMenuOpen && (
            <div
              className="menu"
              style={{ left: 0, bottom: 30, width: 240 }}
              onMouseLeave={onCloseAgentMenu}
            >
              {AGENTS.map((a) => (
                <button
                  key={a.id}
                  onClick={() => {
                    setAgent(a.id as AgentId);
                    onCloseAgentMenu();
                  }}
                  style={{
                    color: a.id === agentId ? "var(--accent)" : undefined,
                    alignItems: "flex-start",
                  }}
                >
                  <Icon name="cpu" size={13} />
                  <span>
                    <span style={{ display: "block" }}>{a.name}</span>
                    <span style={{ fontSize: 10.5, color: "var(--text-muted)" }}>
                      {a.model} · {a.hint}
                    </span>
                  </span>
                </button>
              ))}
            </div>
          )}
        </div>
        <span style={{ flex: 1 }} />
        <span>{sendWithEnter ? "Enter 发送" : "Ctrl+Enter 发送"}</span>
      </div>
    </div>
  );
}