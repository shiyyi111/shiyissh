import { create } from "zustand";
import { persist } from "zustand/middleware";

export type Language = "zh-CN" | "en-US";

export interface PrefsState {
  language: Language;
  terminalFontSize: number;
  terminalFontFamily: string;
  sendWithEnter: boolean;
  apiBaseUrl: string;
  apiKey: string;

  setLanguage: (language: Language) => void;
  setTerminalFontSize: (size: number) => void;
  setTerminalFontFamily: (family: string) => void;
  setSendWithEnter: (value: boolean) => void;
  setApiBaseUrl: (url: string) => void;
  setApiKey: (key: string) => void;
}

export const FONT_STACKS = [
  {
    label: "JetBrains Mono",
    value: '"JetBrains Mono", "Cascadia Code", Consolas, monospace',
  },
  { label: "Cascadia Code", value: '"Cascadia Code", Consolas, monospace' },
  { label: "Consolas", value: 'Consolas, "Courier New", monospace' },
  { label: "等宽系统字体", value: "ui-monospace, SFMono-Regular, monospace" },
];

export const usePrefsStore = create<PrefsState>()(
  persist(
    (set) => ({
      language: "zh-CN",
      terminalFontSize: 13,
      terminalFontFamily: FONT_STACKS[0].value,
      sendWithEnter: true,
      apiBaseUrl: "https://api.deepseek.com/v1",
      apiKey: "",

      setLanguage: (language) => set({ language }),
      setTerminalFontSize: (terminalFontSize) =>
        set({ terminalFontSize: Math.min(20, Math.max(11, terminalFontSize)) }),
      setTerminalFontFamily: (terminalFontFamily) => set({ terminalFontFamily }),
      setSendWithEnter: (sendWithEnter) => set({ sendWithEnter }),
      setApiBaseUrl: (apiBaseUrl) => set({ apiBaseUrl }),
      setApiKey: (apiKey) => set({ apiKey }),
    }),
    { name: "shyissh.prefs" },
  ),
);