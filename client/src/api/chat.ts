/**
 * AI 对话接口（对齐后端 GraphAgentController）
 *
 * POST /api/agent/graph/chat_stream           流式对话（别名 POST /api/agent/graph/chat）
 * POST /api/agent/graph/approval/{approvalId} 审批决策，返回同样是 SSE 流
 *
 * SSE 契约
 * - 每行一个 JSON；心跳行以 ":" 开头，直接跳过
 * - 事件共 9 类：meta / node / text / tool_call / tool_result / dispatch_start / dispatch_end / done / error
 * - 会话 id：响应头 X-Session-Id 与首帧 meta.sessionId 都会带（后端在缺省时生成 UUID）
 * - done 事件在命中"需要人工确认"的命令时会带 approvalId，前端必须弹确认并调审批接口，
 *   否则图会停在 approval 节点直到超时
 */

import { apiUrl, authHeaders } from './request'

/** 后端默认 Agent 名（GraphAgentRegistry 默认 sshOperator，可用 ai.agent.graph.default-agent 覆盖） */
export const SSH_AGENT_NAME = 'sshOperator'

/** 兼容旧引用：历史代码把它当"智能体 ID"用，后端实际按 agentName 匹配 */
export const SSH_AGENT_ID = SSH_AGENT_NAME

/** 当前未接入登录体系，统一使用 default 用户（userId 由请求体传入；后端要求 X-Api-Key） */
export const CHAT_USER_ID = 'default'

export type ChatSseEventName =
  | 'meta'
  | 'node'
  | 'text'
  | 'tool_call'
  | 'tool_result'
  | 'dispatch_start'
  | 'dispatch_end'
  | 'done'
  | 'error'

/** chat_stream / approval 推送的单行事件 */
export interface ChatSseEvent {
  event: ChatSseEventName | string
  type?: string
  /** text 增量 / done 全文 / error 文案 */
  content?: string
  /** 累计全文（text 事件用于打字机回填） */
  fullText?: string
  toolCallId?: string
  toolName?: string
  status?: string
  /** node 事件：当前执行的图节点 */
  node?: string
  traceId?: string
  /** meta 事件：后端分配的会话 id */
  sessionId?: string
  /** done 事件：待审批单号（存在时必须走审批接口） */
  approvalId?: string
  stopReason?: string
  rounds?: number
  toolCalls?: number
  /** dispatch_start / dispatch_end：子智能体名、层级、耗时 */
  agent?: string
  depth?: number
  task?: string
  elapsedMs?: number
  tokens?: number
  attempts?: number
  usage?: Record<string, unknown>
  summarySkipped?: boolean
}

/** chat_stream 请求体（与后端 GraphChatRequest 对齐） */
export interface ChatStreamPayload {
  message: string
  sessionId?: string | null
  userId?: string
  terminalSessionId?: string | null
  maxRounds?: number
  agentName?: string
}

export interface SseHandlers {
  onEvent: (event: ChatSseEvent) => void
  /** 从响应头 X-Session-Id 拿到后端会话 id（首轮尤其重要） */
  onSessionId?: (sessionId: string) => void
  signal?: AbortSignal
}

/** 逐行解析 SSE 响应体，把 JSON 事件交给 handlers */
async function readSse(res: Response, handlers: SseHandlers): Promise<void> {
  if (!res.body) {
    throw new Error('当前环境不支持流式响应')
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''

  const dispatch = (line: string) => {
    const text = line.trim()
    if (!text) return
    // 心跳行（以冒号开头）直接跳过
    if (text.startsWith(':')) return
    try {
      handlers.onEvent(JSON.parse(text) as ChatSseEvent)
    } catch {
      // 非 JSON 行（异常输出、日志混入）直接忽略
    }
  }

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let index = buffer.indexOf('\n')
      while (index >= 0) {
        dispatch(buffer.slice(0, index))
        buffer = buffer.slice(index + 1)
        index = buffer.indexOf('\n')
      }
    }
    if (buffer) dispatch(buffer)
  } finally {
    reader.releaseLock()
  }
}

/** 发起一轮流式对话 */
export async function chatStream(
  payload: ChatStreamPayload,
  handlers: SseHandlers,
): Promise<void> {
  const res = await fetch(apiUrl('/api/agent/graph/chat_stream'), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({
      ...payload,
      userId: payload.userId ?? CHAT_USER_ID,
      agentName: payload.agentName ?? SSH_AGENT_NAME,
    }),
    signal: handlers.signal,
  })

  if (!res.ok) {
    throw new Error(`流式请求失败：HTTP ${res.status}`)
  }
  const sessionId = res.headers.get('X-Session-Id')
  if (sessionId) handlers.onSessionId?.(sessionId)

  await readSse(res, handlers)
}

/**
 * 审批决策：批准会立即续跑（优先从检查点恢复执行原命令），驳回则结束本轮。
 * 返回的同样是 SSE 流，事件格式与 chat_stream 完全一致。
 */
export async function decideApproval(
  approvalId: string,
  approved: boolean,
  handlers: SseHandlers & { decidedBy?: string },
): Promise<void> {
  const res = await fetch(apiUrl(`/api/agent/graph/approval/${encodeURIComponent(approvalId)}`), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...authHeaders() },
    body: JSON.stringify({ approved, decidedBy: handlers.decidedBy ?? CHAT_USER_ID }),
    signal: handlers.signal,
  })

  if (!res.ok) {
    throw new Error(`审批请求失败：HTTP ${res.status}`)
  }
  await readSse(res, handlers)
}