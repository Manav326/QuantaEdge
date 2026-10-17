'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTenantBranding } from './TenantBrandingProvider';

export type AppearanceMode = 'system' | 'light' | 'dark';

type ColorThemeContextValue = {
  mode: AppearanceMode;
  setMode: (mode: AppearanceMode) => void;
};

const APPEARANCE_KEY = 'qe.appearance-mode.v1';
const modes: AppearanceMode[] = ['system', 'light', 'dark'];
const roles = [
  'primary', 'onPrimary', 'primaryText', 'primaryContainer', 'onPrimaryContainer',
  'accent', 'onAccent', 'accentText', 'background', 'onBackground', 'surface', 'onSurface',
  'surfaceElevated', 'onSurfaceElevated', 'text', 'textMuted', 'border', 'borderSubtle', 'focus',
  'success', 'onSuccess', 'successContainer', 'onSuccessContainer',
  'warning', 'onWarning', 'warningContainer', 'onWarningContainer',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'selectedBackground', 'selectedText', 'navBackground', 'navText',
  'navActiveBackground', 'navActiveText', 'disabledBackground', 'disabledText',
] as const;

const defaultPalette = {
  light: {
    primary: '#F97316', onPrimary: '#291400', primaryText: '#9A3412', primaryContainer: '#FFF0E5',
    onPrimaryContainer: '#7C2D12', accent: '#C2410C', onAccent: '#FFFFFF', accentText: '#9A3412',
    background: '#F8FAFC', onBackground: '#172033', surface: '#FFFFFF', onSurface: '#172033',
    surfaceElevated: '#FFFFFF', onSurfaceElevated: '#172033', text: '#172033', textMuted: '#475467',
    border: '#737D91', borderSubtle: '#D1D5DB', focus: '#9A3412', success: '#166534',
    onSuccess: '#FFFFFF', successContainer: '#DCFCE7', onSuccessContainer: '#14532D',
    warning: '#92400E', onWarning: '#FFFFFF', warningContainer: '#FEF3C7', onWarningContainer: '#78350F',
    error: '#B42318', onError: '#FFFFFF', errorContainer: '#FEE2E2', onErrorContainer: '#7F1D1D',
    selectedBackground: '#FFF0E5', selectedText: '#9A3412', navBackground: '#361805',
    navText: '#FFFFFF', navActiveBackground: '#F97316', navActiveText: '#291400',
    disabledBackground: '#E5E7EB', disabledText: '#4B5563',
  },
  dark: {
    primary: '#F97316', onPrimary: '#291400', primaryText: '#FDBA74', primaryContainer: '#48220D',
    onPrimaryContainer: '#FFF0E5', accent: '#C2410C', onAccent: '#FFFFFF', accentText: '#FDBA74',
    background: '#0B1220', onBackground: '#F3F4F6', surface: '#111827', onSurface: '#F3F4F6',
    surfaceElevated: '#182235', onSurfaceElevated: '#F3F4F6', text: '#F3F4F6', textMuted: '#D1D5DB',
    border: '#6B7280', borderSubtle: '#4B5563', focus: '#FDBA74', success: '#4ADE80',
    onSuccess: '#052E16', successContainer: '#14532D', onSuccessContainer: '#DCFCE7',
    warning: '#FCD34D', onWarning: '#422006', warningContainer: '#713F12', onWarningContainer: '#FEF3C7',
    error: '#FDA4AF', onError: '#450A0A', errorContainer: '#7F1D1D', onErrorContainer: '#FEE2E2',
    selectedBackground: '#48220D', selectedText: '#FDBA74', navBackground: '#180D05',
    navText: '#FFFFFF', navActiveBackground: '#F97316', navActiveText: '#291400',
    disabledBackground: '#273244', disabledText: '#9CA3AF',
  },
} satisfies Record<'light' | 'dark', Record<(typeof roles)[number], string>>;

const ColorThemeContext = createContext<ColorThemeContextValue>({
  mode: 'system',
  setMode: () => undefined,
});

function isMode(value: unknown): value is AppearanceMode {
  return typeof value === 'string' && modes.includes(value as AppearanceMode);
}

function safeColor(value: unknown): string | null {
  return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value) ? value.toUpperCase() : null;
}

function cssToken(role: string): string {
  return '--qe-color-' + role.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase());
}

function effectiveMode(mode: AppearanceMode, systemDark: boolean): 'light' | 'dark' {
  return mode === 'system' ? (systemDark ? 'dark' : 'light') : mode;
}

function applyPalette(mode: 'light' | 'dark', rawTokens: Record<string, unknown> | null) {
  const root = document.documentElement;
  for (const role of roles) root.style.removeProperty(cssToken(role));
  const supplied = rawTokens && rawTokens[mode] && typeof rawTokens[mode] === 'object'
    ? rawTokens[mode] as Record<string, unknown>
    : null;
  const palette: Record<(typeof roles)[number], string> = supplied
    ? Object.fromEntries(roles.map(role => [role, safeColor(supplied[role]) || defaultPalette[mode][role]])) as Record<(typeof roles)[number], string>
    : defaultPalette[mode];

  for (const role of roles) root.style.setProperty(cssToken(role), palette[role]);

  // These aliases keep existing components themed while they are migrated to semantic tokens.
  root.style.setProperty('--muted', palette.textMuted);
  root.style.setProperty('--paper', palette.background);
  root.style.setProperty('--card', palette.surface);
  root.style.setProperty('--line', palette.borderSubtle);
  root.style.setProperty('--purple', palette.primaryText);
  root.style.setProperty('--purple-dark', palette.accentText);
  root.style.setProperty('--purple-soft', palette.primaryContainer);
  root.style.setProperty('--qe-brand-orange', palette.primary);
  root.style.setProperty('--qe-brand-orange-deep', palette.accent);
  root.style.setProperty('--green', palette.success);
  root.style.setProperty('--qe-answer-correct-text', palette.success);
  root.style.setProperty('--qe-answer-correct-bg', palette.successContainer);
  root.style.setProperty('--qe-answer-correct-border', palette.success);
  root.style.setProperty('--qe-answer-correct-strong-bg', palette.successContainer);
  root.style.setProperty('--qe-answer-correct-option-border', palette.success);
  root.style.setProperty('--qe-answer-incorrect-text', palette.error);
  root.style.setProperty('--qe-answer-incorrect-bg', palette.errorContainer);
  root.style.setProperty('--qe-answer-incorrect-border', palette.error);
  root.style.setProperty('--qe-answer-incorrect-strong-bg', palette.errorContainer);
  root.style.setProperty('--qe-answer-incorrect-option-border', palette.error);
  root.style.setProperty('--qe-answer-neutral-text', palette.textMuted);
  root.style.setProperty('--qe-answer-neutral-bg', palette.surfaceElevated);
  root.style.setProperty('--qe-answer-neutral-border', palette.borderSubtle);

  // Older v1 records have no generated palettes. Honour their five familiar settings,
  // while the API upgrades them to the complete schema the next time they are saved.
  if (!supplied && rawTokens) {
    const aliases: Record<string, string> = {
      primary: '--qe-color-primary',
      accent: '--qe-color-accent',
      background: '--qe-color-background',
      surface: '--qe-color-surface',
      text: '--qe-color-text',
    };
    for (const [key, variable] of Object.entries(aliases)) {
      const color = safeColor(rawTokens[key] ?? rawTokens[key === 'primary' ? 'brandPrimary' : key === 'accent' ? 'brandAccent' : key]);
      if (color) root.style.setProperty(variable, color);
    }
    const legacyPrimary = safeColor(rawTokens.primary ?? rawTokens.brandPrimary);
    const legacyAccent = safeColor(rawTokens.accent ?? rawTokens.brandAccent);
    const legacyBackground = safeColor(rawTokens.background);
    const legacySurface = safeColor(rawTokens.surface);
    if (legacyPrimary) root.style.setProperty('--qe-brand-orange', legacyPrimary);
    if (legacyAccent) root.style.setProperty('--qe-brand-orange-deep', legacyAccent);
    if (legacyBackground) root.style.setProperty('--paper', legacyBackground);
    if (legacySurface) root.style.setProperty('--card', legacySurface);
  } else if (!rawTokens) {
    // Remove tenant inline aliases so the canonical QuantaEdge defaults in CSS take over.
    for (const variable of [
      '--ink', '--muted', '--paper', '--card', '--line', '--purple', '--purple-dark', '--purple-soft',
      '--qe-brand-orange', '--qe-brand-orange-deep', '--green',
      '--qe-answer-correct-text', '--qe-answer-correct-bg', '--qe-answer-correct-border',
      '--qe-answer-correct-strong-bg', '--qe-answer-correct-option-border',
      '--qe-answer-incorrect-text', '--qe-answer-incorrect-bg', '--qe-answer-incorrect-border',
      '--qe-answer-incorrect-strong-bg', '--qe-answer-incorrect-option-border',
      '--qe-answer-neutral-text', '--qe-answer-neutral-bg', '--qe-answer-neutral-border',
    ]) root.style.removeProperty(variable);
    for (const role of roles) root.style.removeProperty(cssToken(role));
  }
}

export function ColorThemeProvider({ children }: { children: ReactNode }) {
  const { brand } = useTenantBranding();
  const [savedMode, setSavedMode] = useState<AppearanceMode | null>(null);
  const rawConfiguredMode = brand?.themeTokens?.appearance;
  const configuredMode: AppearanceMode = isMode(rawConfiguredMode) ? rawConfiguredMode : 'system';
  const mode = savedMode ?? configuredMode;

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(APPEARANCE_KEY);
      setSavedMode(isMode(saved) ? saved : null);
    } catch {
      setSavedMode(null);
    }
  }, []);

  const setMode = useCallback((next: AppearanceMode) => {
    setSavedMode(next);
    try { window.localStorage.setItem(APPEARANCE_KEY, next); } catch { /* Preferences are optional. */ }
  }, []);

  useEffect(() => {
    const root = document.documentElement;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => {
      const actual = effectiveMode(mode, media.matches);
      root.dataset.qeAppearance = actual;
      root.style.colorScheme = actual;
      const tokens = brand?.themeTokens && typeof brand.themeTokens === 'object'
        ? brand.themeTokens as Record<string, unknown>
        : null;
      applyPalette(actual, tokens);
    };
    apply();
    media.addEventListener?.('change', apply);
    return () => media.removeEventListener?.('change', apply);
  }, [brand, mode]);

  const value = useMemo(() => ({ mode, setMode }), [mode, setMode]);
  return <ColorThemeContext.Provider value={value}>{children}</ColorThemeContext.Provider>;
}

export function AppearanceModeButton() {
  const { mode, setMode } = useContext(ColorThemeContext);
  const nextMode: AppearanceMode = mode === 'system' ? 'dark' : mode === 'dark' ? 'light' : 'system';
  const label = mode === 'system' ? 'System appearance' : mode === 'dark' ? 'Dark appearance' : 'Light appearance';
  const icon = mode === 'system' ? '◐' : mode === 'dark' ? '☾' : '☀';
  return (
    <button
      type="button"
      className="qe-appearance-toggle"
      onClick={() => setMode(nextMode)}
      aria-label={label + '. Activate to switch to ' + nextMode + ' appearance.'}
      title={label + ' · next: ' + nextMode}
      aria-live="polite"
    >
      <span aria-hidden="true">{icon}</span>
      <span className="qe-appearance-toggle-label">{mode === 'system' ? 'System' : mode === 'dark' ? 'Dark' : 'Light'}</span>
    </button>
  );
}
