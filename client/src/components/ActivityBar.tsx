import { Icon, type IconName } from "../lib/icons";
import { useTerminalStore } from "../store/terminalStore";
import { useUiStore, type LeftView } from "../store/uiStore";
import { useThemeStore } from "../store/themeStore";

const VIEWS: { id: LeftView; icon: IconName; label: string }[] = [
  { id: "servers", icon: "server", label: "SSH 服务器" },
  { id: "files", icon: "folder-tree", label: "远程文件" },
  { id: "sftp", icon: "upload-cloud", label: "SFTP 传输" },
];

export function ActivityBar() {
  const sidebarVisible = useUiStore((s) => s.sidebarVisible);
  const sidebarView = useUiStore((s) => s.sidebarView);
  const revealView = useUiStore((s) => s.revealView);
  const openSettings = useUiStore((s) => s.openSettings);
  const tabs = useTerminalStore((s) => s.tabs);
  const theme = useThemeStore((s) => s.theme);
  const setTheme = useThemeStore((s) => s.setTheme);

  return (
    <nav className="activitybar" aria-label="主视图导航">
      {VIEWS.map((view) => {
        const active = sidebarVisible && sidebarView === view.id;
        return (
          <button
            key={view.id}
            className={`activity-item${active ? " active" : ""}`}
            title={view.label}
            aria-label={view.label}
            aria-pressed={active}
            onClick={() => revealView(view.id)}
          >
            <Icon name={view.icon} size={19} />
            {view.id === "servers" && tabs.length > 0 && (
              <span
                style={{
                  position: "absolute",
                  top: 5,
                  right: 5,
                  width: 6,
                  height: 6,
                  borderRadius: "50%",
                  background: "var(--success)",
                }}
              />
            )}
          </button>
        );
      })}

      <span className="activity-spacer" />

      <button
        className="activity-item"
        title={`主题：${theme === "light" ? "亮色" : "深色"}（点击切换）`}
        aria-label="切换主题"
        onClick={() => setTheme(theme === "light" ? "dark" : "light")}
      >
        <Icon name="palette" size={19} />
      </button>

      <button
        className="activity-item"
        title="设置"
        aria-label="设置"
        onClick={() => openSettings("general")}
      >
        <Icon name="settings" size={19} />
      </button>
    </nav>
  );
}