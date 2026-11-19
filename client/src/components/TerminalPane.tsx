import { useCallback, useEffect, useRef } from "react";
import { Terminal } from "@xterm/xterm";
import { FitAddon } from "@xterm/addon-fit";
import "@xterm/xterm/css/xterm.css";
import { Icon } from "../lib/icons";
import { readTerminalTheme } from "../lib/themes";
import { useConnect } from "../lib/useConnect";
import { useTerminalStore, type TerminalTab } from "../store/terminalStore";
import { useConnectionStore } from "../stores/connectionStore";
import { serverAddress } from "../lib/format";
import type { SSHConnection } from "../types";
import { usePrefsStore } from "../store/prefsStore";
import { useThemeStore } from "../store/themeStore";
import { useUiStore } from "../store/uiStore";
import {
  closeTerminal,
  openTerminal,
  readFromTerminal,
  resizeTerminal,
  writeToTerminal,
} from "../api/sshTerminal";

/** 轮询间隔：后端是缓冲区模型，没有推送通道，靠定时拉取 */
const POLL_INTERVAL_MS = 150;

interface TerminalPaneProps {
  onNewConnection: () => void;
}

export function TerminalPane({ onNewConnection }: TerminalPaneProps) {
  const tabs = useTerminalStore((s) => s.tabs);
  const activeTabId = useTerminalStore((s) => s.activeTabId);
  const activate = useTerminalStore((s) => s.activate);
  const closeTab = useTerminalStore((s) => s.closeTab);

  const connections = useConnectionStore((s) => s.connections);
  const activeId = useConnectionStore((s) => s.currentConnectionId);
  const disconnect = useConnectionStore((s) => s.disconnect);
  const connect = useConnect();

  const activeTab = tabs.find((t) => t.id === activeTabId) ?? null;

  const activeServer: SSHConnection | null =
    connections.find((s) => s.id === activeId) ?? null;

  /** 关闭标签页时同步关闭后端终端会话 */
  const handleCloseTab = useCallback(
    (tab: TerminalTab) => {
      if (tab.sessionId) void closeTerminal(tab.sessionId);
      closeTab(tab.id);
      if (tab.serverId === activeId) disconnect(tab.serverId);
    },
    [closeTab, disconnect, activeId],
  );

  return (
    <section className="terminal-area">
      <div className="tabbar">
        {tabs.map((tab) => (
          <div
            key={tab.id}
            className={`tab${tab.id === activeTabId ? " active" : ""}`}
            onClick={() => activate(tab.id)}
            onAuxClick={(e) => {
              if (e.button === 1) handleCloseTab(tab);
            }}
            role="tab"
            tabIndex={0}
            aria-selected={tab.id === activeTabId}
            title={`${tab.title} · ${tab.user}@${tab.host}`}
          >
            <Icon name="terminal" size={13} />
            <span className="tab-label">{tab.title}</span>
            <span
              className="tab-close"
              onClick={(e) => {
                e.stopPropagation();
                handleCloseTab(tab);
              }}
              role="button"
              aria-label="关闭标签"
            >
              <Icon name="close" size={11} />
            </span>
          </div>
        ))}
        {tabs.length > 0 && (
          <button
            className="icon-btn"
            style={{ alignSelf: "center", marginLeft: 4 }}
            title="新建连接"
            onClick={onNewConnection}
          >
            <Icon name="plus" size={14} />
          </button>
        )}
      </div>

      {activeTab ? (
        <XTermSurface tab={activeTab} />
      ) : (
        <WelcomeView
          server={activeServer ?? connections[0] ?? null}
          hasServers={connections.length > 0}
          onConnect={(server) => connect(server)}
          onNewConnection={onNewConnection}
        />
      )}
    </section>
  );
}

interface XTermSurfaceProps {
  tab: TerminalTab;
}

function XTermSurface({ tab }: XTermSurfaceProps) {
  const hostRef = useRef<HTMLDivElement>(null);
  const termRef = useRef<Terminal | null>(null);
  const sessionRef = useRef<string | null>(null);
  const pollRef = useRef<number | null>(null);
  const resizeTimerRef = useRef<number | null>(null);
  const renderedRef = useRef(0);

  const fontFamily = usePrefsStore((s) => s.terminalFontFamily);
  const fontSize = usePrefsStore((s) => s.terminalFontSize);
  const theme = useThemeStore((s) => s.theme);
  const notify = useUiStore((s) => s.notify);

  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    let disposed = false;

    const term = new Terminal({
      fontFamily,
      fontSize,
      lineHeight: 1.35,
      letterSpacing: 0,
      cursorBlink: true,
      cursorStyle: "bar",
      scrollback: 4000,
      allowProposedApi: true,
      theme: readTerminalTheme(),
      convertEol: false,
    });
    const fit = new FitAddon();
    term.loadAddon(fit);
    term.open(host);
    try {
      fit.fit();
    } catch {
      /* 容器尚未完成布局 */
    }
    termRef.current = term;

    /** 开始轮询后端缓冲区，把增量输出写进终端 */
    const startPolling = (sessionId: string) => {
      if (pollRef.current) window.clearInterval(pollRef.current);
      pollRef.current = window.setInterval(async () => {
        if (disposed) return;
        const resp = await readFromTerminal(sessionId);
        if (disposed) return;
        if (resp.code === "0000") {
          const out = resp.data?.output;
          if (out) {
            term.write(out);
            useTerminalStore.getState().append(tab.id, out);
            // 后端已判定通道断开：继续轮询只会反复拿到同一段提示，必须停掉
            if (out.includes("[连接已断开]")) {
              if (pollRef.current) window.clearInterval(pollRef.current);
              pollRef.current = null;
              useTerminalStore.getState().setSession(tab.id, null);
              notify("SSH 连接已断开，请重新连接");
            }
          }
        } else {
          // 会话失效：停止轮询并提示
          if (pollRef.current) window.clearInterval(pollRef.current);
          pollRef.current = null;
          notify(resp.info || "终端会话已断开");
        }
      }, POLL_INTERVAL_MS);
    };

    /** 按当前容器尺寸校正行列数（字体/面板宽度变化后必须重算） */
    const ensureSize = () => {
      try {
        fit.fit();
      } catch {
        /* 容器尚未完成布局 */
      }
    };
    ensureSize();

    const live = useTerminalStore.getState().tabs.find((t) => t.id === tab.id);
    const existingSession = live?.sessionId ?? null;

    if (existingSession) {
      // 切回已经打开过的标签：复用会话，重放已有 buffer
      sessionRef.current = existingSession;
      const buffer = live?.buffer ?? [];
      if (buffer.length > 0) {
        term.write(`\u001b[2J\u001b[3J\u001b[H${buffer.join("")}`);
        renderedRef.current = buffer.length;
      }
      startPolling(existingSession);
    } else {
      void (async () => {
        // 等一帧，让 xterm 拿到最终尺寸后再报告给服务端 PTY
        await new Promise<void>((resolve) => {
          window.requestAnimationFrame(() => resolve());
        });
        if (disposed) return;
        ensureSize();
        const resp = await openTerminal(tab.serverId, term.cols, term.rows);
        if (disposed) return;
        if (resp.code !== "0000" || !resp.data) {
          notify(resp.info || "打开终端失败");
          return;
        }
        const { sessionId, initialOutput } = resp.data;
        sessionRef.current = sessionId;
        useTerminalStore.getState().setSession(tab.id, sessionId);
        if (initialOutput) {
          term.write(initialOutput);
          useTerminalStore.getState().append(tab.id, initialOutput);
          renderedRef.current = 1;
        }
        // 打开会话时报告的是 fit 后的尺寸，再补一次以确保 PTY 与终端一致
        void resizeTerminal(sessionId, term.cols, term.rows);
        startPolling(sessionId);
      })();
    }

    // 真实 PTY：字符直接转发，不做本地回显（服务端 shell 自己会回显）
    const dataSub = term.onData((data) => {
      const sessionId = sessionRef.current;
      if (!sessionId) return;
      void writeToTerminal(sessionId, data);
    });

    const scheduleResize = () => {
      const sessionId = sessionRef.current;
      if (!sessionId) return;
      if (resizeTimerRef.current) window.clearTimeout(resizeTimerRef.current);
      resizeTimerRef.current = window.setTimeout(() => {
        void resizeTerminal(sessionId, term.cols, term.rows);
      }, 250);
    };

    const observer = new ResizeObserver(() => {
      try {
        fit.fit();
      } catch {
        /* 容器尺寸为 0 时忽略 */
      }
      scheduleResize();
    });
    observer.observe(host);

    const raf = window.requestAnimationFrame(() => {
      try {
        fit.fit();
      } catch {
        /* ignore */
      }
      scheduleResize();
    });

    return () => {
      disposed = true;
      window.cancelAnimationFrame(raf);
      observer.disconnect();
      if (pollRef.current) {
        window.clearInterval(pollRef.current);
        pollRef.current = null;
      }
      if (resizeTimerRef.current) {
        window.clearTimeout(resizeTimerRef.current);
        resizeTimerRef.current = null;
      }
      dataSub.dispose();
      term.dispose();
      termRef.current = null;
      sessionRef.current = null;
      renderedRef.current = 0;
      // 注意：切换标签不关闭后端会话，会话 id 保留在 store，切回时复用
    };
  }, [tab.id, tab.serverId, fontFamily, fontSize, notify]);

  // 主题 / 字体联动
  useEffect(() => {
    const term = termRef.current;
    if (!term) return;
    term.options.theme = readTerminalTheme();
    term.options.fontFamily = fontFamily;
    term.options.fontSize = fontSize;
    term.refresh(0, term.rows - 1);
  }, [theme, fontFamily, fontSize]);

  return <div className="terminal-host" ref={hostRef} />;
}

interface WelcomeViewProps {
  server: SSHConnection | null;
  hasServers: boolean;
  onConnect: (server: SSHConnection) => void;
  onNewConnection: () => void;
}

function WelcomeView({
  server,
  hasServers,
  onConnect,
  onNewConnection,
}: WelcomeViewProps) {
  const connections = useConnectionStore((s) => s.connections);

  return (
    <div className="welcome">
      <div className="welcome-card">
        <div className="welcome-logo">S</div>
        <h1 className="welcome-title">shyissh</h1>
        <p className="welcome-sub">
          {hasServers
            ? "一个把 SSH 连接、命令会话与 AI 助手放在同一窗口里的工作台。从左侧选择一台服务器即可开始。"
            : "还没有配置服务器。新建一条 SSH 连接后，就能在这里打开命令会话。"}
        </p>

        <div className="welcome-actions">
          {server ? (
            <button className="btn primary" onClick={() => onConnect(server)}>
              <Icon name="play" size={14} />
              连接 {server.name}
            </button>
          ) : null}
          <button className="btn" onClick={onNewConnection}>
            <Icon name="plus" size={14} />
            新建连接
          </button>
        </div>

        <div className="kbd-row">
          <span><kbd>Ctrl</kbd><kbd>B</kbd> 侧边栏</span>
          <span><kbd>Ctrl</kbd><kbd>J</kbd> AI 助手</span>
          <span><kbd>Ctrl</kbd><kbd>N</kbd> 新连接</span>
          <span><kbd>Ctrl</kbd><kbd>,</kbd> 设置</span>
        </div>

        {connections.length > 0 && (
          <p className="welcome-sub" style={{ fontSize: 11.5, color: "var(--text-muted)" }}>
            已配置 {connections.length} 台服务器 ·{" "}
            {server ? `最近可用：${serverAddress(server)}` : "尚未选择"}
          </p>
        )}
      </div>
    </div>
  );
}