/**
 * SSH 终端接口
 * 对应后端 /api/v1/ssh/terminal/*
 *
 * 后端模型是「PTY 缓冲区读写」：open 拿到 sessionId，之后 write 输入、read 轮询取输出。
 */
import { get, post, type ApiResponse } from './request'

const BASE = '/api/v1/ssh/terminal'

/** open 返回：会话 ID + 打开瞬间已产生的输出（登录提示、MOTD 等） */
export interface TerminalOpenResult {
  sessionId: string
  connectionId: string
  initialOutput: string | null
}

export interface TerminalReadResult {
  output: string | null
}

export interface TerminalExecResult {
  output: string | null
}

/** 打开终端会话 */
export function openTerminal(
  connectionId: string,
  cols: number,
  rows: number,
): Promise<ApiResponse<TerminalOpenResult>> {
  return post<TerminalOpenResult>(`${BASE}/open`, { connectionId, cols, rows })
}

/** 执行一条命令（一次性拿输出，不用于交互式终端） */
export function execCommand(
  sessionId: string,
  command: string,
): Promise<ApiResponse<TerminalExecResult>> {
  return post<TerminalExecResult>(`${BASE}/exec`, { sessionId, command })
}

/** 写入原始输入（按键、粘贴） */
export function writeToTerminal(
  sessionId: string,
  input: string,
): Promise<ApiResponse<void>> {
  return post<void>(`${BASE}/write`, { sessionId, input })
}

/** 读取终端当前输出（取走缓冲区） */
export function readFromTerminal(
  sessionId: string,
): Promise<ApiResponse<TerminalReadResult>> {
  return get<TerminalReadResult>(`${BASE}/read`, { sessionId })
}

/** 调整终端窗口大小 */
export function resizeTerminal(
  sessionId: string,
  cols: number,
  rows: number,
): Promise<ApiResponse<void>> {
  return post<void>(`${BASE}/resize`, { sessionId, cols, rows })
}

/** 关闭终端会话 */
export function closeTerminal(sessionId: string): Promise<ApiResponse<void>> {
  return post<void>(`${BASE}/close`, undefined, { sessionId })
}