import { useCallback, useEffect, useRef, useState } from "react";

interface ResizerProps {
  side: "left" | "right";
  width: number;
  min: number;
  max: number;
  onResize: (width: number) => void;
  onDoubleClick?: () => void;
}

/** 面板之间的拖拽条：按住拖动调整相邻面板宽度 */
export function Resizer({
  side,
  width,
  min,
  max,
  onResize,
  onDoubleClick,
}: ResizerProps) {
  const [dragging, setDragging] = useState(false);
  const startRef = useRef({ x: 0, width });

  const onPointerDown = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      e.preventDefault();
      startRef.current = { x: e.clientX, width };
      setDragging(true);
      e.currentTarget.setPointerCapture(e.pointerId);
      document.body.style.cursor = "col-resize";
    },
    [width],
  );

  useEffect(() => {
    if (!dragging) return;

    const onMove = (e: PointerEvent) => {
      const delta = e.clientX - startRef.current.x;
      const next =
        side === "left"
          ? startRef.current.width + delta
          : startRef.current.width - delta;
      onResize(Math.min(max, Math.max(min, next)));
    };
    const onUp = () => {
      setDragging(false);
      document.body.style.cursor = "";
    };

    window.addEventListener("pointermove", onMove);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
    return () => {
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
    };
  }, [dragging, side, min, max, onResize]);

  return (
    <div
      className={`resizer${dragging ? " dragging" : ""}`}
      role="separator"
      aria-orientation="vertical"
      aria-label="拖拽调整面板宽度"
      onPointerDown={onPointerDown}
      onDoubleClick={onDoubleClick}
      title="拖拽调整宽度，双击恢复默认"
    />
  );
}