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
import { useUiTranslation } from '../i18n/UiLocaleProvider';

export type PeopleAppearanceMode = 'system' | 'light' | 'dark';

type PeopleAppearanceContextValue = {
  mode: PeopleAppearanceMode;
  setMode: (mode: PeopleAppearanceMode) => void;
  cycleMode: () => void;
};

const STORAGE_KEY = 'qe.people.appearance-mode.v1';
const MODES: PeopleAppearanceMode[] = ['system', 'light', 'dark'];
const PeopleAppearanceContext = createContext<PeopleAppearanceContextValue | null>(null);

function isMode(value: unknown): value is PeopleAppearanceMode {
  return value === 'system' || value === 'light' || value === 'dark';
}

function storedMode(): PeopleAppearanceMode {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return isMode(value) ? value : 'system';
  } catch {
    return 'system';
  }
}

function applyMode(mode: PeopleAppearanceMode) {
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false;
  const appearance = mode === 'system' ? (prefersDark ? 'dark' : 'light') : mode;
  const root = document.documentElement;

  root.dataset.qePeopleAppearance = appearance;
  root.dataset.qePeopleAppearanceMode = mode;
  root.style.colorScheme = appearance;
}

export function PeopleAppearanceProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<PeopleAppearanceMode>('system');

  const setMode = useCallback((next: PeopleAppearanceMode) => {
    setModeState(next);
    applyMode(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Keep the selected theme active for this tab when storage is unavailable.
    }
  }, []);

  const cycleMode = useCallback(() => {
    const index = MODES.indexOf(mode);
    setMode(MODES[(index + 1) % MODES.length]);
  }, [mode, setMode]);

  useEffect(() => {
    const initial = storedMode();
    setModeState(initial);
    applyMode(initial);

    const onStorage = (event: StorageEvent) => {
      if (event.key !== STORAGE_KEY) return;
      const next = isMode(event.newValue) ? event.newValue : 'system';
      setModeState(next);
      applyMode(next);
    };

    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const onSystemThemeChange = () => {
      if (storedMode() === 'system') applyMode('system');
    };

    window.addEventListener('storage', onStorage);
    media.addEventListener?.('change', onSystemThemeChange);
    return () => {
      window.removeEventListener('storage', onStorage);
      media.removeEventListener?.('change', onSystemThemeChange);
    };
  }, []);

  const value = useMemo(() => ({ mode, setMode, cycleMode }), [mode, setMode, cycleMode]);
  return <PeopleAppearanceContext.Provider value={value}>{children}</PeopleAppearanceContext.Provider>;
}

export function usePeopleAppearance() {
  const context = useContext(PeopleAppearanceContext);
  if (!context) throw new Error('usePeopleAppearance must be used inside PeopleAppearanceProvider');
  return context;
}

/** One accessible appearance control is shared by the workspace and auth screens. */
export function PeopleAppearanceToggle({ compact = false }: { compact?: boolean }) {
  const { mode, cycleMode } = usePeopleAppearance();
  const { t } = useUiTranslation();
  const modeLabel = t('people.appearance.' + mode);
  const icon = mode === 'dark' ? '☾' : mode === 'light' ? '☀' : '◐';
  const label = t('people.appearance.label', { mode: modeLabel });
  const accessibleLabel = t('people.appearance.toggle', { mode: modeLabel });

  return (
    <button
      type="button"
      className={'qe-people-appearance-toggle' + (compact ? ' is-compact' : '')}
      onClick={cycleMode}
      aria-label={accessibleLabel}
      title={accessibleLabel}
      data-appearance-mode={mode}
    >
      <span className="qe-people-appearance-icon" aria-hidden="true">{icon}</span>
      <span className="qe-people-appearance-label">{compact ? modeLabel : label}</span>
    </button>
  );
}
