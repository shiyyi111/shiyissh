import { useCallback, useEffect, useState } from "react";
import { TitleBar } from "./components/TitleBar";
import { ActivityBar } from "./components/ActivityBar";
import { LeftSidebar } from "./components/LeftSidebar";
import { TerminalPane } from "./components/TerminalPane";
import { RightSidebar } from "./components/RightSidebar";
import { StatusBar } from "./components/StatusBar";
import { SettingsModal } from "./components/SettingsModal";
import { SSHConnectionModal } from "./components/SSHConnectionModal";
import { ConfirmDialog } from "./components/ConfirmDialog";
import { Toasts } from "./components/Toasts";
import { applyThemeToDom } from "./lib/themes";
import { useThemeStore } from "./store/themeStore";
import { useUiStore } from "./store/uiStore";
import { useConnectionStore } from "./stores/connectionStore";
import { useTerminalStore } from "./store/terminalStore";

export default function App() {
  const theme = useThemeStore((s) => s.theme);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);
  const toggleRight = useUiStore((s) => s.toggleRight);
  const openSettings = useUiStore((s) => s.openSettings);
  const notify = useUiStore((s) => s.notify);

  const [formOpen, setFormOpen] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [confirmId, setConfirmId] = useState<string | null>(null);

  // 主题落地到 DOM（data-theme + color-scheme）
  useEffect(() => {
    applyThemeToDom(theme);
  }, [theme]);

  // 启动时从后端加载 SSH 连接列表
  useEffect(() => {
    void useConnectionStore.getState().fetchConnections();
  }, []);

  // 窗口宽度不足时（例如桌面端首次启动的 800×600 默认尺寸）自动收起右侧 AI 面板，
// 放大后自动还原；一旦用户自己点过按钮，就以用户的选择为准。
  useEffect(() => {
    const apply = () => {
      const ui = useUiStore.getState();
      if (ui.rightUserSet) return;
      const wide = window.innerWidth >= 1000;

      if (wide) {
        if (!ui.rightVisible) ui.setRightVisible(true);
        return;
      }
      if (ui.rightVisible) {
        ui.setRightVisible(false);
        ui.notify("窗口较窄，已收起 AI 面板 · Ctrl+J 可展开");
      }
    };

    apply();
    let timer = 0;
    const onResize = () => {
      window.clearTimeout(timer);
      timer = window.setTimeout(apply, 180);
    };
    window.addEventListener("resize", onResize);
    return () => {
      window.clearTimeout(timer);
      window.removeEventListener("resize", onResize);
    };
  }, []);

  const openNewConnection = useCallback(() => {
    setEditingId(null);
    setFormOpen(true);
  }, []);

  const openEditServer = useCallback((id: string) => {
    setEditingId(id);
    setFormOpen(true);
  }, []);

  // 全局快捷键
  useEffect(() => {
    const onKeyDown = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing =
        target?.tagName === "INPUT" ||
        target?.tagName === "TEXTAREA" ||
        target?.isContentEditable;

      if (e.ctrlKey && !e.shiftKey && !e.altKey) {
        switch (e.key.toLowerCase()) {
          case "b":
            e.preventDefault();
            toggleSidebar();
            return;
          case "j":
            e.preventDefault();
            toggleRight();
            return;
          case ",":
            e.preventDefault();
            openSettings("general");
            return;
          case "n":
            if (!typing) {
              e.preventDefault();
              openNewConnection();
            }
            return;
          case "l":
            if (!typing) {
              e.preventDefault();
              useTerminalStore.getState().clearActive();
            }
            return;
          default:
            return;
        }
      }

      if (e.key === "Escape") {
        const ui = useUiStore.getState();
        if (ui.settingsOpen) ui.closeSettings();
        return;
      }

      // Ctrl+Up / Ctrl+Down 切换标签
      if (e.ctrlKey && (e.key === "ArrowUp" || e.key === "ArrowDown")) {
        if (typing) return;
        e.preventDefault();
        const store = useTerminalStore.getState();
        const index = store.tabs.findIndex((t) => t.id === store.activeTabId);
        if (index < 0 || store.tabs.length < 2) return;
        const next =
          e.key === "ArrowUp"
            ? (index - 1 + store.tabs.length) % store.tabs.length
            : (index + 1) % store.tabs.length;
        store.activate(store.tabs[next].id);
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [toggleSidebar, toggleRight, openSettings, openNewConnection]);

  const pendingDelete = useConnectionStore((s) =>
    s.connections.find((srv) => srv.id === confirmId),
  );

  return (
    <div className="app">
      <TitleBar onNewConnection={openNewConnection} />

      <div className="app-body">
        <ActivityBar />
        <LeftSidebar
          onNewConnection={openNewConnection}
          onEditServer={openEditServer}
          onDeleteServer={(id) => setConfirmId(id)}
        />
        <main className="workspace">
          <TerminalPane onNewConnection={openNewConnection} />
        </main>
        <RightSidebar />
      </div>

      <StatusBar />

      <SSHConnectionModal
        open={formOpen}
        serverId={editingId}
        onClose={() => setFormOpen(false)}
      />

      <SettingsModal />

      <ConfirmDialog
        open={confirmId !== null}
        danger
        title="删除这条连接？"
        description={
          pendingDelete
            ? `将删除「${pendingDelete.name}」（${pendingDelete.username}@${pendingDelete.host}:${pendingDelete.port}）。该操作无法撤销，已打开的会话会被关闭。`
            : ""
        }
        confirmText="删除"
        onCancel={() => setConfirmId(null)}
        onConfirm={() => {
          if (!confirmId) return;
          const server = useConnectionStore.getState().connections.find((s) => s.id === confirmId);
          const id = confirmId;
          setConfirmId(null);
          void useConnectionStore
            .getState()
            .removeConnection(id)
            .then(() => {
              const error = useConnectionStore.getState().error;
              if (error) {
                notify(`删除失败：${error}`);
                return;
              }
              useTerminalStore.getState().closeByServer(id);
              notify(server ? `已删除 ${server.name}` : "已删除连接");
            });
        }}
      />

      <Toasts />
    </div>
  );
}