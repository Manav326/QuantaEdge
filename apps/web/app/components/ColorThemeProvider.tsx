'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTenantBranding } from './TenantBrandingProvider';

export type AppearanceMode = 'institution' | 'system' | 'light' | 'dark';
type ConfiguredAppearanceMode = Exclude<AppearanceMode, 'institution'>;

type ColorThemeContextValue = {
  mode: AppearanceMode;
  setMode: (mode: AppearanceMode) => void;
};

const APPEARANCE_KEY = 'qe.appearance-mode.v1';
const modes: AppearanceMode[] = ['institution', 'system', 'dark', 'light'];
const roles = [
  'primary', 'onPrimary', 'primaryText', 'primaryContainer', 'onPrimaryContainer',
  'accent', 'onAccent', 'accentText', 'accentContainer', 'onAccentContainer', 'background', 'onBackground', 'surface', 'onSurface',
  'surfaceElevated', 'onSurfaceElevated', 'text', 'textMuted', 'border', 'borderSubtle', 'focus',
  'success', 'onSuccess', 'successContainer', 'onSuccessContainer',
  'warning', 'onWarning', 'warningContainer', 'onWarningContainer',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'selectedBackground', 'selectedText', 'navBackground', 'navText',
  'navActiveBackground', 'navActiveText', 'disabledBackground', 'disabledText',
] as const;

const defaultPalette = {
  light: {
    primary: '#F97316', onPrimary: '#111827', primaryText: '#B0561C', primaryContainer: '#FEF1E8',
    onPrimaryContainer: '#111827', accent: '#0F766E', onAccent: '#FFFFFF', accentText: '#0F766E',
    accentContainer: '#E7F1F0', onAccentContainer: '#111827',
    background: '#F5F7FB', onBackground: '#172033', surface: '#FFFFFF', onSurface: '#172033',
    surfaceElevated: '#FFFFFF', onSurfaceElevated: '#172033', text: '#172033', textMuted: '#536176',
    border: '#7B8798', borderSubtle: '#D7DEE8', focus: '#F06F17', success: '#166534',
    onSuccess: '#FFFFFF', successContainer: '#DCFCE7', onSuccessContainer: '#14532D',
    warning: '#92400E', onWarning: '#FFFFFF', warningContainer: '#FEF3C7', onWarningContainer: '#78350F',
    error: '#B42318', onError: '#FFFFFF', errorContainer: '#FEE2E2', onErrorContainer: '#7F1D1D',
    selectedBackground: '#FEF1E8', selectedText: '#111827', navBackground: '#F5F7FB',
    navText: '#334155', navActiveBackground: '#FEF1E8', navActiveText: '#111827',
    disabledBackground: '#E5E7EB', disabledText: '#4B5563',
  },
  dark: {
    primary: '#F97316', onPrimary: '#111827', primaryText: '#F97316', primaryContainer: '#362F33',
    onPrimaryContainer: '#FFFFFF', accent: '#0F766E', onAccent: '#FFFFFF', accentText: '#5AA09B',
    accentContainer: '#1A303E', onAccentContainer: '#FFFFFF',
    background: '#121A28', onBackground: '#F4F7FB', surface: '#1B2637', onSurface: '#F4F7FB',
    surfaceElevated: '#243247', onSurfaceElevated: '#F4F7FB', text: '#F4F7FB', textMuted: '#B6C2D2',
    border: '#66758B', borderSubtle: '#354156', focus: '#F97316', success: '#4ADE80',
    onSuccess: '#052E16', successContainer: '#123B2A', onSuccessContainer: '#C9F7D7',
    warning: '#FCD34D', onWarning: '#422006', warningContainer: '#463515', onWarningContainer: '#FDE68A',
    error: '#FDA4AF', onError: '#450A0A', errorContainer: '#451C28', onErrorContainer: '#FFE1E8',
    selectedBackground: '#362F33', selectedText: '#FFFFFF', navBackground: '#18243A',
    navText: '#F4F7FB', navActiveBackground: '#362F33', navActiveText: '#FFFFFF',
    disabledBackground: '#273244', disabledText: '#9CA3AF',
  },
} satisfies Record<'light' | 'dark', Record<(typeof roles)[number], string>>;

const ColorThemeContext = createContext<ColorThemeContextValue>({
  mode: 'institution',
  setMode: () => undefined,
});

function isMode(value: unknown): value is AppearanceMode {
  return typeof value === 'string' && modes.includes(value as AppearanceMode);
}

function isConfiguredMode(value: unknown): value is ConfiguredAppearanceMode {
  return value === 'system' || value === 'light' || value === 'dark';
}

function safeColor(value: unknown): string | null {
  return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value) ? value.toUpperCase() : null;
}

function cssToken(role: string): string {
  return '--qe-color-' + role.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase());
}

function effectiveMode(mode: AppearanceMode, configuredMode: ConfiguredAppearanceMode, systemDark: boolean): 'light' | 'dark' {
  const selectedMode = mode === 'institution' ? configuredMode : mode;
  return selectedMode === 'system' ? (systemDark ? 'dark' : 'light') : selectedMode;
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
  root.style.setProperty('--ink', palette.text);
  root.style.setProperty('--muted', palette.textMuted);
  root.style.setProperty('--paper', palette.background);
  root.style.setProperty('--brand-primary', palette.primary);
  root.style.setProperty('--brand-accent', palette.accent);
  root.style.setProperty('--brand-soft', palette.primaryContainer);
  // Compatibility aliases are consumed by older structured lesson/resource surfaces.
  root.style.setProperty('--qe-text', palette.text);
  root.style.setProperty('--qe-muted', palette.textMuted);
  root.style.setProperty('--qe-border', palette.borderSubtle);
  root.style.setProperty('--qe-surface', palette.surface);
  root.style.setProperty('--qe-surface-raised', palette.surfaceElevated);
  root.style.setProperty('--qe-soft', palette.primaryContainer);
  root.style.setProperty('--qe-input', palette.surfaceElevated);
  root.style.setProperty('--qe-orange', palette.primaryText);
  root.style.setProperty('--qe-accent', palette.accentText);
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
  const configuredMode: ConfiguredAppearanceMode = isConfiguredMode(rawConfiguredMode) ? rawConfiguredMode : 'system';
  // Keep the user's choice separate from the institution's default. This lets them return to
  // the school's configured light/dark/system policy after temporarily overriding it.
  const mode = savedMode ?? 'institution';

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
      const actual = effectiveMode(mode, configuredMode, media.matches);
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
  const { brand } = useTenantBranding();
  const nextMode: AppearanceMode =
    mode === 'institution' ? 'system' : mode === 'system' ? 'dark' : mode === 'dark' ? 'light' : 'institution';
  const defaultModeLabel = brand?.displayName ? 'School' : 'Default';
  const modeName: Record<AppearanceMode, string> = {
    institution: defaultModeLabel, system: 'System', dark: 'Dark', light: 'Light',
  };
  const label = mode === 'institution'
    ? defaultModeLabel + ' appearance setting'
    : modeName[mode] + ' appearance';
  const icon = mode === 'institution' ? '⌂' : mode === 'system' ? '◐' : mode === 'dark' ? '☾' : '☀';
  return (
    <button
      type="button"
      className="qe-appearance-toggle"
      onClick={() => setMode(nextMode)}
      aria-label={label + '. Activate to switch to ' + modeName[nextMode] + ' appearance.'}
      title={label + ' · next: ' + modeName[nextMode]}
      aria-live="polite"
    >
      <span aria-hidden="true">{icon}</span>
      <span className="qe-appearance-toggle-label">{modeName[mode]}</span>
    </button>
  );
}
