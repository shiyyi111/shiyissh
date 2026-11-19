import { create } from "zustand";
import { persist } from "zustand/middleware";

export type LeftView = "servers" | "files" | "sftp";
export type SettingsTab = "general" | "appearance" | "terminal" | "about";

export const SIDEBAR_MIN = 200;
export const SIDEBAR_MAX = 460;
export const RIGHT_MIN = 300;
export const RIGHT_MAX = 640;

interface UiState {
  sidebarVisible: boolean;
  sidebarView: LeftView;
  sidebarWidth: number;
  rightVisible: boolean;
  rightWidth: number;
  /** 用户是否手动切换过右侧面板：手动选择优先于窄窗口的自动收起 */
  rightUserSet: boolean;
  settingsOpen: boolean;
  settingsTab: SettingsTab;
  toast: { id: number; text: string } | null;

  toggleSidebar: () => void;
  revealView: (view: LeftView) => void;
  setSidebarWidth: (width: number) => void;
  toggleRight: () => void;
  setRightVisible: (visible: boolean, byUser?: boolean) => void;
  setRightWidth: (width: number) => void;
  openSettings: (tab?: SettingsTab) => void;
  closeSettings: () => void;
  notify: (text: string) => void;
  clearToast: () => void;
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      sidebarVisible: true,
      sidebarView: "servers",
      sidebarWidth: 268,
      rightVisible: true,
      rightWidth: 380,
      rightUserSet: false,
      settingsOpen: false,
      settingsTab: "general",
      toast: null,

      toggleSidebar: () => set({ sidebarVisible: !get().sidebarVisible }),

      revealView: (view) => {
        const same = get().sidebarVisible && get().sidebarView === view;
        set({ sidebarView: view, sidebarVisible: !same });
      },

      setSidebarWidth: (width) =>
        set({
          sidebarWidth: Math.min(SIDEBAR_MAX, Math.max(SIDEBAR_MIN, width)),
        }),

      toggleRight: () =>
        set((s) => ({ rightVisible: !s.rightVisible, rightUserSet: true })),

      setRightVisible: (visible, byUser = false) =>
        set(byUser ? { rightVisible: visible, rightUserSet: true } : { rightVisible: visible }),

      setRightWidth: (width) =>
        set({ rightWidth: Math.min(RIGHT_MAX, Math.max(RIGHT_MIN, width)) }),

      openSettings: (tab = "general") =>
        set({ settingsOpen: true, settingsTab: tab }),

      closeSettings: () => set({ settingsOpen: false }),

      notify: (text) => set({ toast: { id: Date.now(), text } }),

      clearToast: () => set({ toast: null }),
    }),
    {
      name: "shyissh.ui",
      partialize: (s) => ({
        sidebarVisible: s.sidebarVisible,
        sidebarView: s.sidebarView,
        sidebarWidth: s.sidebarWidth,
        rightVisible: s.rightVisible,
        rightWidth: s.rightWidth,
        rightUserSet: s.rightUserSet,
      }),
    },
  ),
);