'use client';

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export type PeopleAppearanceMode = 'system' | 'light' | 'dark';
type ResolvedAppearance = 'light' | 'dark';

type PeopleThemeContextValue = {
  mode: PeopleAppearanceMode;
  resolvedAppearance: ResolvedAppearance;
  cycleMode: () => void;
};

const STORAGE_KEY = 'qe-hrms-appearance-mode.v1';
const MODES: PeopleAppearanceMode[] = ['system', 'dark', 'light'];
const PeopleThemeContext = createContext<PeopleThemeContextValue | null>(null);

function isAppearanceMode(value: string | null): value is PeopleAppearanceMode {
  return value === 'system' || value === 'light' || value === 'dark';
}

export default function PeopleThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<PeopleAppearanceMode>('system');
  const [systemAppearance, setSystemAppearance] = useState<ResolvedAppearance>('light');
  const [ready, setReady] = useState(false);

  useEffect(() => {
    let storedMode: string | null = null;
    try {
      storedMode = window.localStorage.getItem(STORAGE_KEY);
    } catch {
      // Theme preferences are optional; the current page still works without storage.
    }

    if (isAppearanceMode(storedMode)) setMode(storedMode);

    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const updateSystemAppearance = () => {
      setSystemAppearance(media.matches ? 'dark' : 'light');
    };
    updateSystemAppearance();
    media.addEventListener('change', updateSystemAppearance);
    setReady(true);

    return () => media.removeEventListener('change', updateSystemAppearance);
  }, []);

  const resolvedAppearance: ResolvedAppearance =
    mode === 'system' ? systemAppearance : mode;

  useEffect(() => {
    if (!ready) return;

    const root = document.documentElement;
    root.setAttribute('data-qe-hrms-appearance', resolvedAppearance);
    root.style.colorScheme = resolvedAppearance;

    try {
      window.localStorage.setItem(STORAGE_KEY, mode);
    } catch {
      // Keep the selected theme active for this session if storage is unavailable.
    }
  }, [mode, resolvedAppearance, ready]);

  const cycleMode = useCallback(() => {
    setMode(current => {
      const index = MODES.indexOf(current);
      return MODES[(index + 1) % MODES.length];
    });
  }, []);

  const value = useMemo(
    () => ({ mode, resolvedAppearance, cycleMode }),
    [mode, resolvedAppearance, cycleMode],
  );

  return <PeopleThemeContext.Provider value={value}>{children}</PeopleThemeContext.Provider>;
}

function usePeopleTheme(): PeopleThemeContextValue {
  const value = useContext(PeopleThemeContext);
  if (!value) throw new Error('PeopleAppearanceButton must be used inside PeopleThemeProvider.');
  return value;
}

/** Shared toggle used by the authenticated workspace and public account screens. */
export function PeopleAppearanceButton() {
  const { mode, cycleMode } = usePeopleTheme();
  const modeLabel = mode[0].toUpperCase() + mode.slice(1);
  const icon = mode === 'dark' ? '☾' : mode === 'light' ? '☀' : '◐';

  return (
    <button
      type="button"
      className="qe-people-appearance-toggle"
      onClick={cycleMode}
      title={`Appearance: ${modeLabel}. Activate to change the theme.`}
      aria-label={`Appearance mode is ${modeLabel}. Activate to change the theme.`}
      aria-live="polite"
    >
      <span className="qe-people-appearance-icon" aria-hidden="true">{icon}</span>
      <span className="qe-people-appearance-label">{modeLabel}</span>
    </button>
  );
}
