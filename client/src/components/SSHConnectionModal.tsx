import { useEffect, useState } from "react";
import { Icon } from "../lib/icons";
import { useConnectionStore } from "../stores/connectionStore";
import type { SSHConnection } from "../types";
import { useUiStore } from "../store/uiStore";

interface SSHConnectionModalProps {
  open: boolean;
  serverId: string | null;
  onClose: () => void;
}

type AuthMethod = "password" | "key";

interface Draft {
  name: string;
  host: string;
  port: string;
  username: string;
  authType: AuthMethod;
  password: string;
  privateKey: string;
  note: string;
}

const EMPTY: Draft = {
  name: "",
  host: "",
  port: "22",
  username: "root",
  authType: "password",
  password: "",
  privateKey: "",
  note: "",
};

function fromServer(server: SSHConnection): Draft {
  return {
    name: server.name,
    host: server.host,
    port: String(server.port),
    username: server.username,
    authType: server.authType === 2 ? "key" : "password",
    password: server.password ?? "",
    privateKey: server.privateKey ?? "",
    note: "",
  };
}

export function SSHConnectionModal({ open, serverId, onClose }: SSHConnectionModalProps) {
  const connections = useConnectionStore((s) => s.connections);
  const createConnection = useConnectionStore((s) => s.createConnection);
  const updateConnection = useConnectionStore((s) => s.updateConnection);
  const notify = useUiStore((s) => s.notify);

  const editing = connections.find((s) => s.id === serverId) ?? null;

  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!open) return;
    setDraft(editing ? fromServer(editing) : EMPTY);
    setError(null);
    setSaving(false);
  }, [open, editing]);

  if (!open) return null;

  const set = <K extends keyof Draft>(key: K, value: Draft[K]) =>
    setDraft((d) => ({ ...d, [key]: value }));

  const save = async () => {
    if (!draft.host.trim()) {
      setError("请填写主机地址");
      return;
    }
    if (!draft.username.trim()) {
      setError("请填写用户名");
      return;
    }
    const port = Number(draft.port);
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      setError("端口需要在 1 - 65535 之间");
      return;
    }

    const base = {
      name: draft.name.trim() || `${draft.username.trim()}@${draft.host.trim()}`,
      host: draft.host.trim(),
      port,
      username: draft.username.trim(),
      authType: draft.authType,
      password: draft.password,
      privateKey: draft.privateKey,
      note: draft.note,
    };

    setSaving(true);
    setError(null);
    const payload = {
      connectionId: editing?.id,
      connectionName: base.name.trim() || `${base.username}@${base.host}`,
      host: base.host,
      port: base.port,
      username: base.username,
      authType: base.authType === "key" ? 2 : 1,
      password: base.authType === "password" ? base.password || undefined : undefined,
      privateKey: base.authType === "key" ? base.privateKey || undefined : undefined,
      connectTimeout: 30,
      keepaliveInterval: 60,
      compression: false,
      strictHostKeyCheck: true,
    };
    const ok = editing
      ? await updateConnection(payload)
      : await createConnection(payload);
    setSaving(false);

    if (!ok) {
      setError(useConnectionStore.getState().error ?? "保存失败");
      return;
    }
    notify(editing ? `已保存 ${base.name}` : `已添加 ${base.name}`);
    onClose();
  };

  return (
    <div className="overlay" onClick={onClose}>
      <div
        className="modal wide"
        onClick={(e) => e.stopPropagation()}
        role="dialog"
        aria-modal="true"
        aria-label={editing ? "编辑连接" : "新建连接"}
      >
        <div className="modal-head">
          <Icon name="server" size={16} />
          <h2>{editing ? "编辑 SSH 连接" : "新建 SSH 连接"}</h2>
          <button className="icon-btn" onClick={onClose} title="关闭">
            <Icon name="close" size={15} />
          </button>
        </div>

        <div className="modal-body">
          <div className="form-grid">
            <div className="field full">
              <label htmlFor="f-name">显示名称</label>
              <input
                id="f-name"
                value={draft.name}
                onChange={(e) => set("name", e.target.value)}
                placeholder="例如：生产 · Web 服务器"
              />
            </div>

            <div className="field">
              <label htmlFor="f-host">主机地址 *</label>
              <input
                id="f-host"
                value={draft.host}
                onChange={(e) => set("host", e.target.value)}
                placeholder="10.0.12.31 或 example.com"
                spellCheck={false}
              />
            </div>

            <div className="field">
              <label htmlFor="f-port">端口</label>
              <input
                id="f-port"
                value={draft.port}
                onChange={(e) => set("port", e.target.value.replace(/[^\d]/g, ""))}
                placeholder="22"
                inputMode="numeric"
              />
            </div>

            <div className="field">
              <label htmlFor="f-user">用户名 *</label>
              <input
                id="f-user"
                value={draft.username}
                onChange={(e) => set("username", e.target.value)}
                placeholder="root"
                spellCheck={false}
              />
            </div>

            <div className="field">
              <label>认证方式</label>
              <div className="segmented" style={{ alignSelf: "flex-start" }}>
                <button
                  className={draft.authType === "password" ? "on" : ""}
                  onClick={() => set("authType", "password")}
                >
                  密码
                </button>
                <button
                  className={draft.authType === "key" ? "on" : ""}
                  onClick={() => set("authType", "key")}
                >
                  私钥
                </button>
              </div>
            </div>

            {draft.authType === "password" ? (
              <div className="field full">
                <label htmlFor="f-pass">密码</label>
                <input
                  id="f-pass"
                  type="password"
                  value={draft.password}
                  onChange={(e) => set("password", e.target.value)}
                  placeholder="连接时使用的密码"
                />
                <span className="hint">密码通过后端接口保存，不在客户端落盘。</span>
              </div>
            ) : (
              <>
                <div className="field full">
                  <label htmlFor="f-key">私钥内容</label>
                  <textarea
                    id="f-key"
                    rows={4}
                    value={draft.privateKey}
                    onChange={(e) => set("privateKey", e.target.value)}
                    placeholder={"-----BEGIN OPENSSH PRIVATE KEY-----\n…"}
                    spellCheck={false}
                  />
                  <span className="hint">
                    粘贴私钥内容（不是路径），由后端保存并用于认证。
                  </span>
                </div>
              </>
            )}

            <div className="field full">
              <label htmlFor="f-note">备注</label>
              <input
                id="f-note"
                value={draft.note}
                onChange={(e) => set("note", e.target.value)}
                placeholder="用途说明，例如：Nginx + Node 集群入口"
              />
            </div>
          </div>

          {error && (
            <p style={{ color: "var(--danger)", fontSize: 12, margin: "12px 0 0" }}>
              {error}
            </p>
          )}
        </div>

        <div className="modal-foot">
          <span className="spacer" />
          <button className="btn ghost" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" onClick={save} disabled={saving}>
            <Icon name="check" size={14} />
            {saving ? "保存中…" : editing ? "保存修改" : "添加连接"}
          </button>
        </div>
      </div>
    </div>
  );
}