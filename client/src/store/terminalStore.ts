import { create } from "zustand";
import type { SSHConnection } from "../types";

export interface TerminalTab {
  id: string;
  serverId: string;
  title: string;
  user: string;
  host: string;
  port: number;
  cwd: string;
  /** 后端终端会话 ID（null 表示还在打开中或已失效） */
  sessionId: string | null;
  buffer: string[];
  history: string[];
  createdAt: number;
}

interface TerminalState {
  tabs: TerminalTab[];
  activeTabId: string | null;

  openTab: (server: SSHConnection) => string;
  closeTab: (id: string) => void;
  setSession: (id: string, sessionId: string | null) => void;
  activate: (id: string) => void;
  append: (id: string, text: string) => void;
  clearBuffer: (id: string) => void;
  setCwd: (id: string, cwd: string) => void;
  pushHistory: (id: string, line: string) => void;
  clearActive: () => void;
  closeByServer: (serverId: string) => void;
  reset: () => void;
}

const uid = () =>
  `tab_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;

export const useTerminalStore = create<TerminalState>()((set, get) => ({
  tabs: [],
  activeTabId: null,

  openTab: (server) => {
    const existing = get().tabs.find((t) => t.serverId === server.id);
    if (existing) {
      set({ activeTabId: existing.id });
      return existing.id;
    }
    const tab: TerminalTab = {
      id: uid(),
      serverId: server.id,
      title: server.name || `${server.username}@${server.host}`,
      user: server.username,
      host: server.host,
      port: server.port,
      cwd: "~",
      sessionId: null,
      buffer: [],
      history: [],
      createdAt: Date.now(),
    };
    set({ tabs: [...get().tabs, tab], activeTabId: tab.id });
    return tab.id;
  },

  closeTab: (id) => {
    const tabs = get().tabs.filter((t) => t.id !== id);
    const wasActive = get().activeTabId === id;
    set({
      tabs,
      activeTabId: wasActive ? (tabs[tabs.length - 1]?.id ?? null) : get().activeTabId,
    });
  },

  activate: (id) => set({ activeTabId: id }),

  setSession: (id, sessionId) =>
    set({
      tabs: get().tabs.map((t) => (t.id === id ? { ...t, sessionId } : t)),
    }),

  append: (id, text) =>
    set({
      tabs: get().tabs.map((t) =>
        t.id === id ? { ...t, buffer: [...t.buffer, text] } : t,
      ),
    }),

  clearBuffer: (id) =>
    set({
      tabs: get().tabs.map((t) =>
        t.id === id ? { ...t, buffer: [], cwd: "~" } : t,
      ),
    }),

  setCwd: (id, cwd) =>
    set({ tabs: get().tabs.map((t) => (t.id === id ? { ...t, cwd } : t)) }),

  pushHistory: (id, line) =>
    set({
      tabs: get().tabs.map((t) =>
        t.id === id && line.trim()
          ? { ...t, history: [...t.history.filter((h) => h !== line), line].slice(-60) }
          : t,
      ),
    }),

  clearActive: () => {
    const id = get().activeTabId;
    if (!id) return;
    set({
      tabs: get().tabs.map((t) => (t.id === id ? { ...t, buffer: [], cwd: "~" } : t)),
    });
  },

  closeByServer: (serverId) => {
    const tabs = get().tabs.filter((t) => t.serverId !== serverId);
    const activeStillOpen = tabs.some((t) => t.id === get().activeTabId);
    set({
      tabs,
      activeTabId: activeStillOpen
        ? get().activeTabId
        : (tabs[tabs.length - 1]?.id ?? null),
    });
  },

  reset: () => set({ tabs: [], activeTabId: null }),
}));