import { useCallback } from "react";
import { useConnectionStore } from "../stores/connectionStore";
import type { SSHConnection } from "../types";
import { useTerminalStore } from "../store/terminalStore";
import { useUiStore } from "../store/uiStore";

/**
 * 真实 SSH 连接：
 * 调用后端 POST /api/v1/ssh/connect，成功后才打开终端标签页；
 * 标签页挂载后由 TerminalPane 调 /api/v1/ssh/terminal/open 建立 PTY 会话。
 * 失败只提示原因，不打开空终端。
 */
export function useConnect() {
  return useCallback(async (server: SSHConnection) => {
    const state = useConnectionStore.getState();
    if (state.connections.some((c) => c.status === 2)) return;

    if (state.currentConnectionId === server.id) {
      useUiStore.getState().notify(`当前已连接 ${server.name}`);
      return;
    }

    const ok = await state.connect(server.id);
    if (!ok) {
      const message = useConnectionStore.getState().error || "连接失败";
      useUiStore.getState().notify(`${server.name}：${message}`);
      return;
    }

    useTerminalStore.getState().openTab(server);
    useUiStore.getState().notify(`已连接到 ${server.name}`);
  }, []);
}