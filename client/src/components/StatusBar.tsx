import { Icon } from "../lib/icons";
import { THEMES } from "../lib/themes";
import { useConnectionStore } from "../stores/connectionStore";
import { useTerminalStore } from "../store/terminalStore";
import { useThemeStore } from "../store/themeStore";
import { usePrefsStore } from "../store/prefsStore";
import { useUiStore } from "../store/uiStore";

export function StatusBar() {
  const connections = useConnectionStore((s) => s.connections);
  const activeId = useConnectionStore((s) => s.currentConnectionId);
  const connectingId = useConnectionStore((s) => s.connections.find((c) => c.status === 2)?.id ?? null);
  const tabs = useTerminalStore((s) => s.tabs);
  const theme = useThemeStore((s) => s.theme);
  const fontSize = usePrefsStore((s) => s.terminalFontSize);
  const openSettings = useUiStore((s) => s.openSettings);

  const active = connections.find((s) => s.id === activeId) ?? null;
  const connecting = connections.find((s) => s.id === connectingId) ?? null;
  const themeName = THEMES.find((t) => t.id === theme)?.name ?? "深空";

  return (
    <footer className="statusbar">
      <span className="item">
        <span className={`dot ${active ? "on" : "off"}`} />
        {connecting
          ? "正在连接…"
          : active
            ? `${active.username}@${active.host}:${active.port}`
            : "未连接"}
      </span>

      <span className="item">
        <Icon name="terminal" size={12} />
        {tabs.length} 个会话
      </span>

      <span className="item">
        <Icon name="server" size={12} />
        {connections.length} 台服务器
      </span>

      <span className="spacer" />

      <button
        className="item accent"
        onClick={() => openSettings("appearance")}
        title="切换主题"
        style={{ cursor: "pointer" }}
      >
        <Icon name="palette" size={12} />
        {themeName}
      </button>

      <button
        className="item"
        onClick={() => openSettings("terminal")}
        title="终端设置"
        style={{ cursor: "pointer" }}
      >
        {fontSize}px
      </button>

      <span className="item accent">shyissh v0.1.0</span>
    </footer>
  );
}