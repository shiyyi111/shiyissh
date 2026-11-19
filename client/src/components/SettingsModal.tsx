import { useEffect, useState } from "react";
import { Icon, type IconName } from "../lib/icons";
import { THEMES } from "../lib/themes";
import { useThemeStore } from "../store/themeStore";
import { usePrefsStore, FONT_STACKS } from "../store/prefsStore";
import { useUiStore, type SettingsTab } from "../store/uiStore";
import { useChatStore } from "../store/chatStore";
import { useConnectionStore } from "../stores/connectionStore";

const TABS: { id: SettingsTab; label: string; icon: IconName }[] = [
  { id: "general", label: "通用", icon: "settings" },
  { id: "appearance", label: "外观", icon: "palette" },
  { id: "terminal", label: "终端", icon: "terminal" },
  { id: "about", label: "关于", icon: "info" },
];

export function SettingsModal() {
  const open = useUiStore((s) => s.settingsOpen);
  const tab = useUiStore((s) => s.settingsTab);
  const openSettings = useUiStore((s) => s.openSettings);
  const closeSettings = useUiStore((s) => s.closeSettings);
  const notify = useUiStore((s) => s.notify);

  const theme = useThemeStore((s) => s.theme);
  const setTheme = useThemeStore((s) => s.setTheme);

  const language = usePrefsStore((s) => s.language);
  const setLanguage = usePrefsStore((s) => s.setLanguage);
  const fontSize = usePrefsStore((s) => s.terminalFontSize);
  const setFontSize = usePrefsStore((s) => s.setTerminalFontSize);
  const fontFamily = usePrefsStore((s) => s.terminalFontFamily);
  const setFontFamily = usePrefsStore((s) => s.setTerminalFontFamily);
  const sendWithEnter = usePrefsStore((s) => s.sendWithEnter);
  const setSendWithEnter = usePrefsStore((s) => s.setSendWithEnter);
  const apiBaseUrl = usePrefsStore((s) => s.apiBaseUrl);
  const setApiBaseUrl = usePrefsStore((s) => s.setApiBaseUrl);
  const apiKey = usePrefsStore((s) => s.apiKey);
  const setApiKey = usePrefsStore((s) => s.setApiKey);

  const clearChat = useChatStore((s) => s.clearAll);

  const serverUrl = useConnectionStore((s) => s.serverUrl);
  const setServerUrl = useConnectionStore((s) => s.setServerUrl);
  const fetchConnections = useConnectionStore((s) => s.fetchConnections);
  const [serverUrlInput, setServerUrlInput] = useState(serverUrl);

  useEffect(() => {
    if (open) setServerUrlInput(serverUrl);
  }, [open, serverUrl]);

  const applyServerUrl = () => {
    const next = serverUrlInput.trim().replace(/\/+$/, "");
    if (next === serverUrl) return;
    setServerUrl(next);
    void fetchConnections();
    notify(next ? `后端地址已切换为 ${next}` : "已恢复默认后端地址（dev 走本地代理）");
  };

  if (!open) return null;

  return (
    <div className="overlay" onClick={closeSettings}>
      <div
        className="modal wide"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label="设置"
      >
        <div className="modal-head">
          <Icon name="settings" size={16} />
          <h2>设置</h2>
          <button className="icon-btn" onClick={closeSettings} title="关闭">
            <Icon name="close" size={15} />
          </button>
        </div>

        <div className="settings-layout">
          <nav className="settings-nav" aria-label="设置分区">
            {TABS.map((item) => (
              <button
                key={item.id}
                className={tab === item.id ? "on" : ""}
                onClick={() => openSettings(item.id)}
              >
                <Icon name={item.icon} size={15} />
                {item.label}
              </button>
            ))}
          </nav>

          <div className="settings-content">
            {tab === "general" && (
              <>
                <h3 className="settings-section-title">通用</h3>
                <p className="settings-section-desc">
                  界面语言、后端与 AI 服务端地址、默认交互行为。
                </p>

                <div className="settings-row">
                  <div className="label">
                    <strong>界面语言</strong>
                    <small>切换后立即生效</small>
                  </div>
                  <select
                    value={language}
                    onChange={(e) =>
                      setLanguage(e.target.value === "en-US" ? "en-US" : "zh-CN")
                    }
                  >
                    <option value="zh-CN">简体中文</option>
                    <option value="en-US">English</option>
                  </select>
                </div>

                <div className="settings-row">
                  <div className="label">
                    <strong>后端服务地址</strong>
                    <small>SSH 连接 API 所在服务；留空则 dev 走本地代理，生产用默认地址</small>
                  </div>
                  <input
                    value={serverUrlInput}
                    onChange={(e) => setServerUrlInput(e.target.value)}
                    onBlur={applyServerUrl}
                    onKeyDown={(e) => {
                      if (e.key === "Enter") applyServerUrl();
                    }}
                    placeholder="http://127.0.0.1:8091"
                    spellCheck={false}
                  />
                </div>

                <div className="settings-row">
                  <div className="label">
                    <strong>AI 服务端地址</strong>
                    <small>兼容 OpenAI 协议的 /chat/completions 接口</small>
                  </div>
                  <input
                    value={apiBaseUrl}
                    onChange={(e) => setApiBaseUrl(e.target.value)}
                    placeholder="https://api.deepseek.com/v1"
                    spellCheck={false}
                  />
                </div>

                <div className="settings-row">
                  <div className="label">
                    <strong>API Key</strong>
                    <small>仅保存在本机存储，不会上传</small>
                  </div>
                  <input
                    type="password"
                    value={apiKey}
                    onChange={(e) => setApiKey(e.target.value)}
                    placeholder="sk-…"
                  />
                </div>

                <div className="settings-row">
                  <div className="label">
                    <strong>发送快捷键</strong>
                    <small>对话输入框的默认发送方式</small>
                  </div>
                  <div className="segmented">
                    <button
                      className={sendWithEnter ? "on" : ""}
                      onClick={() => setSendWithEnter(true)}
                    >
                      Enter 发送
                    </button>
                    <button
                      className={!sendWithEnter ? "on" : ""}
                      onClick={() => setSendWithEnter(false)}
                    >
                      Ctrl+Enter
                    </button>
                  </div>
                </div>
              </>
            )}

            {tab === "appearance" && (
              <>
                <h3 className="settings-section-title">外观</h3>
                <p className="settings-section-desc">
                  主题统一管理所有界面颜色与终端配色，切换后即时同步。
                </p>

                <div className="theme-grid">
                  {THEMES.map((t) => (
                    <button
                      key={t.id}
                      className={`theme-card${theme === t.id ? " on" : ""}`}
                      onClick={() => setTheme(t.id)}
                      title={t.description}
                    >
                      <div className="theme-preview" data-theme={t.id}>
                        <span
                          className="pv-side"
                          style={{ background: "var(--bg-app)" }}
                        />
                        <span className="pv-main" style={{ background: "var(--bg-panel)" }}>
                          <span
                            className="pv-line"
                            style={{ width: "62%", background: "var(--accent)" }}
                          />
                          <span
                            className="pv-line"
                            style={{ width: "88%", background: "var(--text-dim)" }}
                          />
                          <span
                            className="pv-line"
                            style={{ width: "48%", background: "var(--text-muted)" }}
                          />
                          <span
                            className="pv-line"
                            style={{ width: "74%", background: "var(--terminal-fg)", opacity: 0.5 }}
                          />
                        </span>
                      </div>
                      <div className="theme-card-foot">
                        <span style={{ flex: 1 }}>{t.name}</span>
                        {theme === t.id && <Icon name="check" size={13} />}
                      </div>
                    </button>
                  ))}
                </div>

                <p
                  className="settings-section-desc"
                  style={{ marginTop: 14, marginBottom: 0 }}
                >
                  当前主题：{THEMES.find((t) => t.id === theme)?.name} · 所有组件颜色均来自
                  CSS 变量，未硬编码色值。
                </p>
              </>
            )}

            {tab === "terminal" && (
              <>
                <h3 className="settings-section-title">终端</h3>
                <p className="settings-section-desc">
                  终端字体与字号会立即应用到所有会话，并与当前主题联动。
                </p>

                <div className="settings-row">
                  <div className="label">
                    <strong>字体</strong>
                    <small>等宽字体，优先使用系统已安装的字体</small>
                  </div>
                  <select
                    value={fontFamily}
                    onChange={(e) => setFontFamily(e.target.value)}
                  >
                    {FONT_STACKS.map((f) => (
                      <option key={f.label} value={f.value}>
                        {f.label}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="settings-row">
                  <div className="label">
                    <strong>字号</strong>
                    <small>11 - 20 px</small>
                  </div>
                  <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                    <input
                      type="range"
                      min={11}
                      max={20}
                      value={fontSize}
                      onChange={(e) => setFontSize(Number(e.target.value))}
                      style={{ width: 160, padding: 0, border: "none", background: "none" }}
                    />
                    <span
                      style={{
                        fontFamily: "var(--font-mono)",
                        fontSize: 12,
                        color: "var(--text-dim)",
                        minWidth: 40,
                      }}
                    >
                      {fontSize}px
                    </span>
                  </div>
                </div>

                <div
                  style={{
                    marginTop: 12,
                    padding: "10px 12px",
                    borderRadius: "var(--radius-md)",
                    border: "1px solid var(--border)",
                    background: "var(--terminal-bg)",
                    color: "var(--terminal-fg)",
                    fontFamily,
                    fontSize,
                    lineHeight: 1.5,
                  }}
                >
                  <div style={{ color: "var(--ansi-2)" }}>deploy@web ~ $ ls</div>
                  <div style={{ color: "var(--ansi-4)" }}>apps/ &nbsp;logs/ &nbsp;backups/</div>
                  <div style={{ color: "var(--text-muted)" }}>
                    # 预览：这条命令的输出样式
                  </div>
                </div>
              </>
            )}

            {tab === "about" && (
              <>
                <h3 className="settings-section-title">关于 shyissh</h3>
                <p className="settings-section-desc">
                  面向运维场景的 SSH + AI 工作台，Tauri 桌面端。
                </p>

                <div className="about-list">
                  <div className="about-row">
                    <span>版本</span>
                    <span>0.1.0</span>
                  </div>
                  <div className="about-row">
                    <span>技术栈</span>
                    <span>Tauri 2 · React 19 · Vite 8 · xterm.js</span>
                  </div>
                  <div className="about-row">
                    <span>主题系统</span>
                    <span>CSS 变量 + zustand 持久化</span>
                  </div>
                  <div className="about-row">
                    <span>终端引擎</span>
                    <span>@xterm/xterm 6</span>
                  </div>
                  <div className="about-row">
                    <span>许可证</span>
                    <span>MIT</span>
                  </div>
                </div>

                <div className="danger-zone">
                  <h4>危险操作</h4>
                  <p>清空本机保存的对话历史（服务器配置不受影响）。</p>
                  <button
                    className="btn danger sm"
                    onClick={() => {
                      clearChat();
                      notify("已清空对话历史");
                    }}
                  >
                    <Icon name="trash" size={13} />
                    清空对话历史
                  </button>
                </div>
              </>
            )}
          </div>
        </div>

        <div className="modal-foot">
          <span className="spacer" />
          <button className="btn primary" onClick={closeSettings}>
            完成
          </button>
        </div>
      </div>
    </div>
  );
}