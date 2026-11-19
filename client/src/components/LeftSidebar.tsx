import { Icon } from "../lib/icons";
import { SIDEBAR_MAX, SIDEBAR_MIN, useUiStore } from "../store/uiStore";
import { Resizer } from "./Resizer";
import { ServersPanel } from "./sidebar/ServersPanel";
import { FilesPanel } from "./sidebar/FilesPanel";
import { SftpPanel } from "./sidebar/SftpPanel";

interface LeftSidebarProps {
  onNewConnection: () => void;
  onEditServer: (id: string) => void;
  onDeleteServer: (id: string) => void;
}

const TITLES: Record<string, string> = {
  servers: "SSH 服务器",
  files: "远程文件",
  sftp: "SFTP 传输",
};

export function LeftSidebar({
  onNewConnection,
  onEditServer,
  onDeleteServer,
}: LeftSidebarProps) {
  const visible = useUiStore((s) => s.sidebarVisible);
  const view = useUiStore((s) => s.sidebarView);
  const width = useUiStore((s) => s.sidebarWidth);
  const setWidth = useUiStore((s) => s.setSidebarWidth);
  const toggleSidebar = useUiStore((s) => s.toggleSidebar);

  if (!visible) return null;

  return (
    <>
      <aside className="sidebar" style={{ width }}>
        <div className="sidebar-head">
          <span className="sidebar-title">{TITLES[view] ?? "资源管理器"}</span>
          <div className="sidebar-actions">
            {view === "servers" && (
              <button
                className="icon-btn"
                title="新建连接"
                onClick={onNewConnection}
              >
                <Icon name="plus" size={15} />
              </button>
            )}
            <button
              className="icon-btn"
              title="隐藏左侧栏"
              onClick={toggleSidebar}
            >
              <Icon name="close" size={14} />
            </button>
          </div>
        </div>

        {view === "servers" && (
          <ServersPanel
            onNewConnection={onNewConnection}
            onEditServer={onEditServer}
            onDeleteServer={onDeleteServer}
          />
        )}
        {view === "files" && <FilesPanel />}
        {view === "sftp" && <SftpPanel />}
      </aside>

      <Resizer
        side="left"
        width={width}
        min={SIDEBAR_MIN}
        max={SIDEBAR_MAX}
        onResize={setWidth}
        onDoubleClick={() => setWidth(268)}
      />
    </>
  );
}