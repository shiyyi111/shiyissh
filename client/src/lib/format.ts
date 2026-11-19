import type { SSHConnection } from "../types";
export function formatTime(ts: number): string {
  const d = new Date(ts);
  return d.toLocaleTimeString("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

export function formatRelative(ts: number): string {
  const diff = Date.now() - ts;
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  const d = new Date(ts);
  return `${d.getMonth() + 1}月${d.getDate()}日`;
}

export function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

export function initialsOf(name: string): string {
  const clean = name.replace(/[^\p{L}\p{N}]/gu, "");
  if (!clean) return "SS";
  return clean.slice(0, 2).toUpperCase();
}
/** 连接地址显示：user@host 或 user@host:port */
export function serverAddress(c: SSHConnection): string {
  return c.port && c.port !== 22
    ? `${c.username}@${c.host}:${c.port}`
    : `${c.username}@${c.host}`;
}

/** 连接显示名 */
export function serverLabel(c: SSHConnection): string {
  return c.name || serverAddress(c);
}
