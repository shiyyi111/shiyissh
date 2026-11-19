import type { ITheme } from "@xterm/xterm";

export type ThemeId = "dark" | "light" | "github-dark" | "forest";

export interface ThemeMeta {
  id: ThemeId;
  name: string;
  description: string;
  dark: boolean;
}

export const THEMES: ThemeMeta[] = [
  {
    id: "dark",
    name: "深空",
    description: "石墨灰 + 薄荷绿，适合长时间盯终端",
    dark: true,
  },
  {
    id: "github-dark",
    name: "GitHub Dark",
    description: "GitHub 官方深色调，冷静克制",
    dark: true,
  },
  {
    id: "forest",
    name: "森林",
    description: "墨绿底色，柔和护眼",
    dark: true,
  },
  {
    id: "light",
    name: "亮色",
    description: "白昼模式，明亮清爽",
    dark: false,
  },
];

export const DEFAULT_THEME: ThemeId = "dark";

export function isThemeId(value: unknown): value is ThemeId {
  return THEMES.some((t) => t.id === value);
}

export function findTheme(id: ThemeId): ThemeMeta {
  return THEMES.find((t) => t.id === id) ?? THEMES[0];
}

export function applyThemeToDom(id: ThemeId) {
  const root = document.documentElement;
  root.setAttribute("data-theme", id);
  root.style.colorScheme = findTheme(id).dark ? "dark" : "light";
}

function cssVar(styles: CSSStyleDeclaration, name: string): string {
  return styles.getPropertyValue(name).trim();
}

/**
 * 终端配色直接从当前主题的 CSS 变量读取，
 * 保证切换主题时终端与界面颜色始终一致。
 */
export function readTerminalTheme(): ITheme {
  const styles = getComputedStyle(document.documentElement);
  const v = (name: string) => cssVar(styles, name);
  return {
    background: v("--terminal-bg"),
    foreground: v("--terminal-fg"),
    cursor: v("--accent"),
    cursorAccent: v("--terminal-bg"),
    selectionBackground: v("--selection"),
    black: v("--ansi-0"),
    red: v("--ansi-1"),
    green: v("--ansi-2"),
    yellow: v("--ansi-3"),
    blue: v("--ansi-4"),
    magenta: v("--ansi-5"),
    cyan: v("--ansi-6"),
    white: v("--ansi-7"),
    brightBlack: v("--ansi-8"),
    brightRed: v("--ansi-9"),
    brightGreen: v("--ansi-10"),
    brightYellow: v("--ansi-11"),
    brightBlue: v("--ansi-12"),
    brightMagenta: v("--ansi-13"),
    brightCyan: v("--ansi-14"),
    brightWhite: v("--ansi-15"),
  };
}