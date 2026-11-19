import { create } from "zustand";
import { persist } from "zustand/middleware";
import {
  CHAT_USER_ID,
  chatStream,
  decideApproval,
  SSH_AGENT_ID,
  type ChatSseEvent,
} from "../api/chat";
import { AGENTS, type AgentId } from "../lib/ai";
import { useTerminalStore } from "./terminalStore";

/** 一次工具调用的展示记录（对应后端 tool_call / tool_result 事件） */
export interface ToolCallRecord {
  toolCallId: string;
  toolName: string;
  status: "running" | "success" | "error";
  content: string;
}

/** dispatch_start / dispatch_end：子智能体派发过程（父流上的可视化事件） */
export interface DispatchStep {
  kind: "dispatch_start" | "dispatch_end";
  agent: string;
  depth?: number;
  status?: string;
  elapsedMs?: number;
}

/** 待人工确认的命令（done 事件带 approvalId 时出现） */
export interface PendingApproval {
  approvalId: string;
  content: string;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: number;
  pending?: boolean;
  error?: string;
  /** AI 在执行过程中调用的工具（SSH 命令） */
  tools?: ToolCallRecord[];
  /** node 事件：当前正在执行的图节点，用于"正在做什么"提示 */
  node?: string;
  /** 子智能体派发过程（dispatch_start / dispatch_end） */
  steps?: DispatchStep[];
  /** 需要人工确认的命令：不弹确认，图会卡到超时 */
  approval?: PendingApproval;
}

export interface ChatSession {
  id: string;
  title: string;
  messages: ChatMessage[];
  createdAt: number;
  updatedAt: number;
  /** 后端会话 ID（检查点线程）；首轮由后端生成并通过响应头 / meta 回传，之后每轮复用 */
  backendSessionId?: string | null;
}

interface ChatState {
  sessions: ChatSession[];
  activeSessionId: string | null;
  agentId: AgentId;
  streaming: boolean;

  newSession: () => string;
  selectSession: (id: string) => void;
  deleteSession: (id: string) => void;
  setAgent: (id: AgentId) => void;
  send: (text: string) => void;
  /** 审批决策：批准后立即续跑，驳回则结束本轮 */
  decide: (approvalId: string, approved: boolean) => void;
  stop: () => void;
  clearAll: () => void;
}

const uid = (prefix: string) =>
  `${prefix}_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;

function titleOf(text: string): string {
  const clean = text.replace(/\s+/g, " ").trim();
  return clean.length > 22 ? `${clean.slice(0, 22)}…` : clean || "新对话";
}

/** 当前流式请求的中断句柄 */
let streamAbort: AbortController | null = null;

const toToolStatus = (status?: string): ToolCallRecord["status"] =>
  status === "error" ? "error" : status === "running" ? "running" : "success";

interface EventContext {
  patchReply: (fn: (m: ChatMessage) => ChatMessage) => void;
  patchSession: (fn: (s: ChatSession) => ChatSession) => void;
  rememberSessionId: (sid: string) => void;
}

/** 把后端 9 类 SSE 事件映射到消息状态（send 与审批续跑共用） */
function applyBackendEvent(event: ChatSseEvent, ctx: EventContext) {
  switch (event.event) {
    case "meta":
      if (event.sessionId) ctx.rememberSessionId(event.sessionId);
      return;
    case "node":
      ctx.patchReply((m) => ({ ...m, node: event.node }));
      return;
    case "text":
      // fullText 是累计全文，直接覆盖
      ctx.patchReply((m) => ({ ...m, content: event.fullText ?? event.content ?? m.content }));
      return;
    case "tool_call": {
      const record: ToolCallRecord = {
        toolCallId: event.toolCallId ?? uid("tool"),
        toolName: event.toolName ?? "executeCommand",
        status: toToolStatus(event.status),
        content: "",
      };
      ctx.patchReply((m) => ({ ...m, tools: [...(m.tools ?? []), record] }));
      return;
    }
    case "tool_result":
      ctx.patchReply((m) => ({
        ...m,
        tools: (m.tools ?? []).map((t) =>
          t.toolCallId === event.toolCallId
            ? { ...t, status: toToolStatus(event.status), content: event.content ?? "" }
            : t,
        ),
      }));
      return;
    case "dispatch_start":
    case "dispatch_end": {
      const step: DispatchStep = {
        kind: event.event,
        agent: event.agent ?? "sub-agent",
        depth: event.depth,
        status: event.status,
        elapsedMs: event.elapsedMs,
      };
      ctx.patchReply((m) => ({ ...m, steps: [...(m.steps ?? []), step] }));
      return;
    }
    case "done":
      ctx.patchReply((m) => ({
        ...m,
        content: event.content || m.content,
        pending: false,
        // 命中需要人工确认：保留审批卡，等用户点批准 / 驳回
        approval: event.approvalId
          ? {
              approvalId: event.approvalId,
              content: event.content || "该命令需要人工确认后才能执行",
            }
          : undefined,
      }));
      return;
    case "error":
      ctx.patchReply((m) => ({
        ...m,
        error: event.content || "对话出错",
        pending: false,
      }));
      return;
    default:
      return;
  }
}

export const useChatStore = create<ChatState>()(
  persist(
    (set, get) => ({
      sessions: [],
      activeSessionId: null,
      agentId: SSH_AGENT_ID,
      streaming: false,

      newSession: () => {
        const session: ChatSession = {
          id: uid("chat"),
          title: "新对话",
          messages: [],
          createdAt: Date.now(),
          updatedAt: Date.now(),
          backendSessionId: null,
        };
        set({
          sessions: [session, ...get().sessions].slice(0, 30),
          activeSessionId: session.id,
        });
        return session.id;
      },

      selectSession: (id) => set({ activeSessionId: id }),

      deleteSession: (id) => {
        const sessions = get().sessions.filter((s) => s.id !== id);
        set({
          sessions,
          activeSessionId:
            get().activeSessionId === id ? (sessions[0]?.id ?? null) : get().activeSessionId,
        });
      },

      setAgent: (agentId) => set({ agentId }),

      send: (text) => {
        const content = text.trim();
        if (!content || get().streaming) return;

        let sessionId = get().activeSessionId;
        if (!sessionId) sessionId = get().newSession();

        const userMsg: ChatMessage = {
          id: uid("m"),
          role: "user",
          content,
          createdAt: Date.now(),
        };
        const replyId = uid("m");
        const pendingMsg: ChatMessage = {
          id: replyId,
          role: "assistant",
          content: "",
          createdAt: Date.now(),
          pending: true,
          tools: [],
        };

        const patchSession = (fn: (s: ChatSession) => ChatSession) =>
          set({
            sessions: get().sessions.map((s) => (s.id === sessionId ? fn(s) : s)),
          });

        patchSession((s) => ({
          ...s,
          title: s.messages.length === 0 ? titleOf(content) : s.title,
          messages: [...s.messages, userMsg, pendingMsg],
          updatedAt: Date.now(),
        }));

        const patchReply = (fn: (m: ChatMessage) => ChatMessage) =>
          patchSession((s) => ({
            ...s,
            updatedAt: Date.now(),
            messages: s.messages.map((m) => (m.id === replyId ? fn(m) : m)),
          }));

        // 上一次请求还没结束就再发：先中断
        streamAbort?.abort();
        const controller = new AbortController();
        streamAbort = controller;
        set({ streaming: true });

        void (async () => {
          try {
            // 1. 会话 id：后端在响应头 X-Session-Id 与首帧 meta.sessionId 里回传，
            //    首轮先留空，拿到后写回；后续轮次复用同一个检查点线程
            let backendSessionId =
              get().sessions.find((s) => s.id === sessionId)?.backendSessionId ?? null;
            const rememberSessionId = (sid: string) => {
              backendSessionId = sid;
              patchSession((s) => ({ ...s, backendSessionId: sid }));
            };

            // 2. 取当前激活终端标签的会话 ID，让 AI 把命令真正写进这个终端
            const terminal = useTerminalStore.getState();
            const activeTab = terminal.tabs.find((t) => t.id === terminal.activeTabId);
            const terminalSessionId = activeTab?.sessionId ?? undefined;

            // 3. 流式对话
            await chatStream(
              {
                agentName: get().agentId,
                userId: CHAT_USER_ID,
                sessionId: backendSessionId,
                terminalSessionId,
                message: content,
              },
              {
                signal: controller.signal,
                onSessionId: rememberSessionId,
                onEvent: (event) =>
                  applyBackendEvent(event, { patchReply, patchSession, rememberSessionId }),
              },
            );

            patchReply((m) => ({ ...m, pending: false }));
          } catch (err) {
            const aborted = (err as Error)?.name === "AbortError";
            // 后端重启或检查点线程被清理后，旧的 backendSessionId 会失效：
            // 清掉它，下一轮由后端重新生成，避免一直报会话不存在
            if (!aborted) {
              patchSession((s) => ({ ...s, backendSessionId: null }));
            }
            patchReply((m) => ({
              ...m,
              pending: false,
              error: aborted ? undefined : (err as Error)?.message || "对话失败，请检查后端是否已启动",
            }));
          } finally {
            if (streamAbort === controller) streamAbort = null;
            set({ streaming: false });
          }
        })();
      },

      decide: (approvalId, approved) => {
        const sessionId = get().activeSessionId;
        if (!sessionId || get().streaming) return;

        const controller = new AbortController();
        streamAbort = controller;
        set({ streaming: true });

        const patchSession = (fn: (s: ChatSession) => ChatSession) =>
          set({
            sessions: get().sessions.map((s) => (s.id === sessionId ? fn(s) : s)),
          });
        const patchReply = (fn: (m: ChatMessage) => ChatMessage) =>
          patchSession((s) => ({
            ...s,
            updatedAt: Date.now(),
            messages: s.messages.map((m) =>
              m.approval?.approvalId === approvalId ? fn(m) : m,
            ),
          }));
        const rememberSessionId = (sid: string) =>
          patchSession((s) => ({ ...s, backendSessionId: sid }));

        void (async () => {
          try {
            await decideApproval(approvalId, approved, {
              signal: controller.signal,
              onSessionId: rememberSessionId,
              onEvent: (event) =>
                applyBackendEvent(event, { patchReply, patchSession, rememberSessionId }),
            });
            patchReply((m) => ({ ...m, pending: false }));
          } catch (err) {
            const aborted = (err as Error)?.name === "AbortError";
            patchReply((m) => ({
              ...m,
              pending: false,
              error: aborted ? undefined : (err as Error)?.message || "审批请求失败",
            }));
          } finally {
            if (streamAbort === controller) streamAbort = null;
            set({ streaming: false });
          }
        })();
      },

      stop: () => {
        streamAbort?.abort();
        streamAbort = null;
        set({ streaming: false });
        const activeId = get().activeSessionId;
        set({
          sessions: get().sessions.map((s) =>
            s.id === activeId
              ? {
                  ...s,
                  messages: s.messages.map((m) => (m.pending ? { ...m, pending: false } : m)),
                }
              : s,
          ),
        });
      },

      clearAll: () => {
        streamAbort?.abort();
        streamAbort = null;
        set({ sessions: [], activeSessionId: null, streaming: false });
      },
    }),
    {
      name: "shyissh.chat",
      partialize: (s) => ({
        sessions: s.sessions.map((session) => ({
          ...session,
          messages: session.messages.filter((m) => !m.pending),
        })),
        activeSessionId: s.activeSessionId,
        agentId: s.agentId,
      }),
      onRehydrateStorage: () => (state) => {
        if (state) {
          state.streaming = false;
          // 旧版本（本地假回复时期）往 localStorage 存过 agentId="inline"，
          // 后端没有这个智能体，请求会直接失败。这里校正回真实装配的 agent
          if (!AGENTS.some((a) => a.id === state.agentId)) {
            state.agentId = SSH_AGENT_ID;
          }
          // 清理上次异常退出遗留的 pending 消息
          state.sessions = state.sessions.map((s) => ({
            ...s,
            messages: s.messages.map((m) => ({ ...m, pending: false })),
          }));
        }
      },
    },
  ),
);

export function useAgents() {
  return AGENTS;
}