import { create } from "zustand";
import { persist } from "zustand/middleware";
import {
  DEFAULT_THEME,
  applyThemeToDom,
  isThemeId,
  type ThemeId,
} from "../lib/themes";

interface ThemeState {
  theme: ThemeId;
  setTheme: (id: ThemeId) => void;
  toggleDark: () => void;
}

export const useThemeStore = create<ThemeState>()(
  persist(
    (set, get) => ({
      theme: DEFAULT_THEME,
      setTheme: (id) => {
        applyThemeToDom(id);
        set({ theme: id });
      },
      toggleDark: () => {
        const current = get().theme;
        const next: ThemeId = current === "light" ? "dark" : "light";
        applyThemeToDom(next);
        set({ theme: next });
      },
    }),
    {
      name: "shyissh.theme",
      onRehydrateStorage: () => (state) => {
        applyThemeToDom(isThemeId(state?.theme) ? state.theme : DEFAULT_THEME);
      },
    },
  ),
);