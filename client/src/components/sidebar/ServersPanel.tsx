import { useMemo, useState } from "react";
import { Icon } from "../../lib/icons";
import { initialsOf } from "../../lib/format";
import { useConnect } from "../../lib/useConnect";
import { useConnectionStore } from "../../stores/connectionStore";
import { serverAddress } from "../../lib/format";

interface ServersPanelProps {
  onNewConnection: () => void;
  onEditServer: (id: string) => void;
  onDeleteServer: (id: string) => void;
}

export function ServersPanel({
  onNewConnection,
  onEditServer,
  onDeleteServer,
}: ServersPanelProps) {
  const [query, setQuery] = useState("");
  const connections = useConnectionStore((s) => s.connections);
  const activeId = useConnectionStore((s) => s.currentConnectionId);
  const connectingId = useConnectionStore((s) => s.connections.find((c) => c.status === 2)?.id ?? null);
  const connect = useConnect();

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return connections;
    return connections.filter((s) =>
      [s.name, s.host, s.username].some((v) => v.toLowerCase().includes(q)),
    );
  }, [connections, query]);

  const online = filtered.filter(
    (s) => s.id === activeId || s.id === connectingId,
  );
  const offline = filtered.filter(
    (s) => s.id !== activeId && s.id !== connectingId,
  );

  const renderItem = (id: string) => {
    const server = connections.find((s) => s.id === id);
    if (!server) return null;
    const isActive = server.id === activeId;
    const isConnecting = server.id === connectingId;
    return (
      <div
        key={server.id}
        className={`server-item${isActive ? " active" : ""}`}
        onDoubleClick={() => connect(server)}
        onClick={() => {
          if (isActive) return;
          connect(server);
        }}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => {
          if (e.key === "Enter") connect(server);
        }}
        title="点击连接，右键菜单可编辑"
      >
        <span className={`server-avatar${isActive ? "" : " muted"}`}>
          {initialsOf(server.name)}
        </span>
        <span className="server-meta">
          <span className="server-name">{server.name}</span>
          <span className="server-host">
            {isConnecting ? "正在连接…" : serverAddress(server)}
          </span>
        </span>
        <span className="row-actions" onClick={(e) => e.stopPropagation()}>
          <button
            className="icon-btn"
            title={isActive ? "已连接" : "连接"}
            onClick={() => connect(server)}
            disabled={isConnecting}
          >
            <Icon
              name={isConnecting ? "refresh" : "play"}
              size={13}
              className={isConnecting ? "spin" : undefined}
            />
          </button>
          <button
            className="icon-btn"
            title="编辑"
            onClick={() => onEditServer(server.id)}
          >
            <Icon name="pencil" size={13} />
          </button>
          <button
            className="icon-btn"
            title="删除"
            onClick={() => onDeleteServer(server.id)}
          >
            <Icon name="trash" size={13} />
          </button>
        </span>
      </div>
    );
  };

  return (
    <>
      <div className="sidebar-search">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="搜索服务器 / 主机"
          spellCheck={false}
        />
      </div>

      <div className="sidebar-body">
        {connections.length === 0 ? (
          <div className="empty-state">
            <span className="empty-icon">
              <Icon name="server" size={20} />
            </span>
            <p>
              还没有配置服务器。
              <br />
              添加一条连接信息后即可开始会话。
            </p>
            <button className="btn primary sm" onClick={onNewConnection}>
              <Icon name="plus" size={14} />
              新建连接
            </button>
          </div>
        ) : filtered.length === 0 ? (
          <div className="empty-state">
            <span className="empty-icon">
              <Icon name="search" size={20} />
            </span>
            <p>没有匹配「{query}」的服务器</p>
          </div>
        ) : (
          <>
            {online.length > 0 && (
              <>
                <div className="sidebar-section">已连接</div>
                {online.map((s) => renderItem(s.id))}
              </>
            )}
            {offline.length > 0 && (
              <>
                <div className="sidebar-section">
                  {online.length ? "其他服务器" : "全部服务器"}
                </div>
                {offline.map((s) => renderItem(s.id))}
              </>
            )}
          </>
        )}
      </div>

      <div
        style={{
          flex: "none",
          padding: "8px 10px",
          borderTop: "1px solid var(--border)",
        }}
      >
        <button
          className="btn block sm"
          onClick={onNewConnection}
          title="新建 SSH 连接"
        >
          <Icon name="plus" size={14} />
          新建连接
        </button>
      </div>
    </>
  );
}