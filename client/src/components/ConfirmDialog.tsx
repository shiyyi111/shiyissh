import { Icon } from "../lib/icons";

interface ConfirmDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmText?: string;
  danger?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmText = "确认",
  danger = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!open) return null;
  return (
    <div className="overlay" onClick={onCancel}>
      <div
        className="modal narrow"
        style={{ maxWidth: 420 }}
        onClick={(e) => e.stopPropagation()}
        role="alertdialog"
        aria-modal="true"
        aria-label={title}
      >
        <div className="modal-head">
          <Icon name={danger ? "alert" : "info"} size={16} />
          <h2>{title}</h2>
        </div>
        <div className="modal-body">
          <p style={{ margin: 0, fontSize: 12.5, color: "var(--text-dim)", lineHeight: 1.7 }}>
            {description}
          </p>
        </div>
        <div className="modal-foot">
          <span className="spacer" />
          <button className="btn ghost" onClick={onCancel}>
            取消
          </button>
          <button className={`btn ${danger ? "danger" : "primary"}`} onClick={onConfirm}>
            {confirmText}
          </button>
        </div>
      </div>
    </div>
  );
}