import { useEffect, useRef, useState } from "react";
import { Icon } from "../../lib/icons";
import { useConnectionStore } from "../../stores/connectionStore";
import { useUiStore } from "../../store/uiStore";

interface Transfer {
  id: string;
  name: string;
  dir: "up" | "down";
  size: string;
  progress: number;
  done: boolean;
}

const SAMPLE_FILES = [
  { name: "deploy.sh", size: "2.4 KB" },
  { name: "nginx.conf", size: "6.1 KB" },
  { name: "db-backup.sql.gz", size: "184 MB" },
  { name: "app.log", size: "12.8 MB" },
];

const uid = () => `tr_${Date.now().toString(36)}${Math.random().toString(36).slice(2, 4)}`;

export function SftpPanel() {
  const activeId = useConnectionStore((s) => s.currentConnectionId);
  const connections = useConnectionStore((s) => s.connections);
  const active = connections.find((s) => s.id === activeId) ?? null;
  const notify = useUiStore((s) => s.notify);
  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    timerRef.current = window.setInterval(() => {
      setTransfers((list) =>
        list.map((t) => {
          if (t.done) return t;
          const next = Math.min(100, t.progress + 6 + Math.random() * 12);
          return { ...t, progress: next, done: next >= 100 };
        }),
      );
    }, 260);
    return () => {
      if (timerRef.current) window.clearInterval(timerRef.current);
    };
  }, []);

  const enqueue = (dir: "up" | "down") => {
    const sample = SAMPLE_FILES[Math.floor(Math.random() * SAMPLE_FILES.length)];
    setTransfers((list) => [
      {
        id: uid(),
        name: sample.name,
        dir,
        size: sample.size,
        progress: 0,
        done: false,
      },
      ...list,
    ]);
    notify(dir === "up" ? `开始上传 ${sample.name}` : `开始下载 ${sample.name}`);
  };

  if (!active) {
    return (
      <div className="empty-state">
        <span className="empty-icon">
          <Icon name="upload-cloud" size={20} />
        </span>
        <p>
          未连接服务器。
          <br />
          连接后可在此拖拽上传或下载文件。
        </p>
      </div>
    );
  }

  const pending = transfers.filter((t) => !t.done).length;

  return (
    <div className="sidebar-body" style={{ height: "100%" }}>
      <div
        className="dropzone"
        onClick={() => enqueue("up")}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => e.key === "Enter" && enqueue("up")}
        title="点击模拟一次上传"
      >
        <Icon name="upload-cloud" size={20} />
        拖拽文件到此处上传
        <span style={{ fontSize: 11, opacity: 0.7 }}>或点击选择文件（演示）</span>
      </div>

      <div
        style={{
          display: "flex",
          gap: 6,
          padding: "0 10px 6px",
        }}
      >
        <button className="btn sm" style={{ flex: 1 }} onClick={() => enqueue("up")}>
          <Icon name="arrow-up" size={13} />
          上传
        </button>
        <button className="btn sm" style={{ flex: 1 }} onClick={() => enqueue("down")}>
          <Icon name="download" size={13} />
          下载
        </button>
      </div>

      <div className="sidebar-section">传输队列（{pending} 进行中）</div>

      {transfers.length === 0 ? (
        <div className="empty-state" style={{ paddingTop: 14 }}>
          <p>暂无传输任务</p>
        </div>
      ) : (
        transfers.map((t) => (
          <div className="transfer-item" key={t.id}>
            <div className="transfer-top">
              <Icon
                name={t.dir === "up" ? "arrow-up" : "download"}
                size={13}
                style={{ color: t.done ? "var(--success)" : "var(--accent)" }}
              />
              <span style={{ flex: "none", maxWidth: 110, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
                {t.name}
              </span>
              <span className="path">{t.size}</span>
              <span style={{ fontSize: 11, color: t.done ? "var(--success)" : "var(--text-muted)" }}>
                {t.done ? "完成" : `${Math.round(t.progress)}%`}
              </span>
            </div>
            <div className="progress">
              <span style={{ width: `${t.progress}%` }} />
            </div>
          </div>
        ))
      )}
    </div>
  );
}