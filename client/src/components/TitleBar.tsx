import { Icon } from "../lib/icons";
import { serverAddress } from "../lib/format";
import { useConnectionStore } from "../stores/connectionStore";
import { useUiStore } from "../store/uiStore";
import { useConnect } from "../lib/useConnect";

interface TitleBarProps {
  onNewConnection: () => void;
}

export function TitleBar({ onNewConnection }: TitleBarProps) {
  const sidebarVisible = useUiStore((s) => s.sidebarVisible);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);
  const rightVisible = useUiStore((s) => s.rightVisible);
  const toggleRight = useUiStore((s) => s.toggleRight);
  const openSettings = useUiStore((s) => s.openSettings);

  const connections = useConnectionStore((s) => s.connections);
  const activeId = useConnectionStore((s) => s.currentConnectionId);
  const connectingId = useConnectionStore((s) => s.connections.find((c) => c.status === 2)?.id ?? null);
  const disconnect = useConnectionStore((s) => s.disconnect);
  const connect = useConnect();

  const active = connections.find((s) => s.id === activeId) ?? null;
  const connecting = connections.find((s) => s.id === connectingId) ?? null;

  const status = connecting ? "connecting" : active ? "connected" : "offline";
  const chipText = connecting
    ? `正在连接 ${serverAddress(connecting)}`
    : active
      ? serverAddress(active)
      : "未连接服务器";

  const handleChip = () => {
    if (active) {
      const target = active;
      // 断开是异步的：等结果再提示，避免后端失败时仍然显示“已断开”
      void disconnect(target.id).then((ok) => {
        useUiStore
          .getState()
          .notify(
            ok
              ? `已断开 ${target.name}`
              : `断开失败：${useConnectionStore.getState().error ?? "未知原因"}`,
          );
      });
      return;
    }
    const fallback = connections[0];
    if (fallback) connect(fallback);
    else onNewConnection();
  };

  return (
    <header className="titlebar" data-tauri-drag-region>
      <button
        className="icon-btn"
        title={sidebarVisible ? "隐藏左侧栏 (Ctrl+B)" : "显示左侧栏 (Ctrl+B)"}
        onClick={toggleSidebar}
      >
        <Icon name="panel-left" size={16} />
      </button>

      <div className="titlebar-brand">
        <span className="brand-glyph">S</span>
        <span className="brand-name">
          shy<em>issh</em>
        </span>
      </div>

      <span className="titlebar-sep" />

      <div className="titlebar-center">
        <button
          className={`conn-chip${active ? " connected" : ""}`}
          onClick={handleChip}
          title={
            connecting
              ? "正在建立连接"
              : active
                ? `已连接 · 点击断开 (${active.name})`
                : `点击连接${connections.length ? ` ${connections[0].name}` : ""}`
          }
        >
          <span
            className={`dot ${status === "connected" ? "on" : status === "connecting" ? "" : "off"}`}
            style={
              status === "connecting"
                ? { background: "var(--warning)" }
                : undefined
            }
          />
          <span className="conn-chip-text">{chipText}</span>
          {active && <Icon name="power" size={12} />}
        </button>
      </div>

      <button
        className="icon-btn"
        title="新建 SSH 连接"
        onClick={onNewConnection}
      >
        <Icon name="plus" size={16} />
      </button>

      <span className="titlebar-sep" />

      <button
        className={`icon-btn${rightVisible ? " active" : ""}`}
        title={rightVisible ? "隐藏 AI 助手 (Ctrl+J)" : "显示 AI 助手 (Ctrl+J)"}
        onClick={toggleRight}
      >
        <Icon name="sparkles" size={16} />
      </button>

      <button
        className="icon-btn"
        title="设置"
        onClick={() => openSettings("general")}
      >
        <Icon name="settings" size={16} />
      </button>
    </header>
  );
}