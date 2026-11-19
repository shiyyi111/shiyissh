import { useEffect } from "react";
import { Icon } from "../lib/icons";
import { useUiStore } from "../store/uiStore";

export function Toasts() {
  const toast = useUiStore((s) => s.toast);
  const clearToast = useUiStore((s) => s.clearToast);

  useEffect(() => {
    if (!toast) return;
    const timer = window.setTimeout(clearToast, 2400);
    return () => window.clearTimeout(timer);
  }, [toast, clearToast]);

  if (!toast) return null;

  return (
    <div className="toast-wrap" aria-live="polite">
      <div className="toast" key={toast.id}>
        <span className="toast-icon">
          <Icon name="check" size={14} />
        </span>
        {toast.text}
      </div>
    </div>
  );
}