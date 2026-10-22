'use client';

import { apiFetch } from './apiClient';
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

export type AdminAppearanceMode = 'institution' | 'system' | 'light' | 'dark';

export type AdminTenantBrand = {
  institutionCode: string;
  displayName: string;
  tagline?: string | null;
  logoUrl?: string | null;
  appIconUrl?: string | null;
  brandingVersion?: number;
  themeTokens?: Record<string, unknown>;
};

type AdminThemeContextValue = {
  brand: AdminTenantBrand | null;
  institutionCode: string;
  isResolving: boolean;
  mode: AdminAppearanceMode;
  setMode: (mode: AdminAppearanceMode) => void;
};

const APPEARANCE_KEY = 'qe.appearance-mode.v1';
const APPEARANCE_MODES: AdminAppearanceMode[] = ['institution', 'system', 'dark', 'light'];
const TOKEN_ROLES = [
  'primary', 'onPrimary', 'primaryText', 'primaryContainer', 'onPrimaryContainer',
  'accent', 'onAccent', 'accentText', 'accentContainer', 'onAccentContainer',
  'background', 'onBackground', 'surface', 'onSurface', 'surfaceElevated', 'onSurfaceElevated',
  'text', 'textMuted', 'border', 'borderSubtle', 'focus',
  'success', 'onSuccess', 'successContainer', 'onSuccessContainer',
  'warning', 'onWarning', 'warningContainer', 'onWarningContainer',
  'error', 'onError', 'errorContainer', 'onErrorContainer',
  'selectedBackground', 'selectedText', 'navBackground', 'navText',
  'navActiveBackground', 'navActiveText', 'disabledBackground', 'disabledText',
] as const;

type TokenRole = (typeof TOKEN_ROLES)[number];
type Palette = Record<TokenRole, string>;

const DEFAULT_PALETTE: Record<'light' | 'dark', Palette> = {
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
};

const AdminThemeContext = createContext<AdminThemeContextValue>({
  brand: null,
  institutionCode: '',
  isResolving: false,
  mode: 'institution',
  setMode: () => undefined,
});

function normalizedInstitutionCode(value: unknown): string | null {
  const code = String(value ?? '').trim().toUpperCase();
  return /^[A-Z0-9][A-Z0-9_-]{2,31}$/.test(code) ? code : null;
}

function safeColor(value: unknown): string | null {
  return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value) ? value.toUpperCase() : null;
}

function cssToken(role: string): string {
  return '--qe-color-' + role.replace(/[A-Z]/g, letter => '-' + letter.toLowerCase());
}

function isAppearanceMode(value: unknown): value is AdminAppearanceMode {
  return typeof value === 'string' && APPEARANCE_MODES.includes(value as AdminAppearanceMode);
}

function configuredAppearance(brand: AdminTenantBrand | null): 'system' | 'light' | 'dark' {
  const value = brand?.themeTokens?.appearance;
  return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
}

function effectiveAppearance(
  mode: AdminAppearanceMode,
  configuredMode: 'system' | 'light' | 'dark',
  systemDark: boolean,
): 'light' | 'dark' {
  const selected = mode === 'institution' ? configuredMode : mode;
  return selected === 'system' ? (systemDark ? 'dark' : 'light') : selected;
}

/** Apply the same semantic roles and legacy aliases used by the learner web. */
function applyPalette(mode: 'light' | 'dark', rawTokens: Record<string, unknown> | null) {
  const root = document.documentElement;
  const supplied = rawTokens?.[mode] && typeof rawTokens[mode] === 'object'
    ? rawTokens[mode] as Record<string, unknown>
    : null;
  const palette = Object.fromEntries(
    TOKEN_ROLES.map(role => [role, safeColor(supplied?.[role]) || DEFAULT_PALETTE[mode][role]]),
  ) as Palette;

  for (const role of TOKEN_ROLES) root.style.setProperty(cssToken(role), palette[role]);
  root.style.setProperty('--qe-color-surface-subtle', mode === 'dark' ? '#202C3F' : '#F0F3F8');

  // Keep legacy admin screens theme-aware while their styles move to semantic tokens.
  const aliases: Record<string, string> = {
    '--ink': palette.text,
    '--muted': palette.textMuted,
    '--paper': palette.background,
    '--brand-primary': palette.primary,
    '--brand-accent': palette.accent,
    '--brand-soft': palette.primaryContainer,
    '--qe-text': palette.text,
    '--qe-muted': palette.textMuted,
    '--qe-border': palette.borderSubtle,
    '--qe-surface': palette.surface,
    '--qe-panel': palette.surface,
    '--qe-surface-raised': palette.surfaceElevated,
    '--qe-soft': palette.primaryContainer,
    '--qe-input': palette.surfaceElevated,
    '--qe-orange': palette.primaryText,
    '--qe-accent': palette.accentText,
    '--card': palette.surface,
    '--line': palette.borderSubtle,
    '--purple': palette.primaryText,
    '--purple-dark': palette.accentText,
    '--purple-soft': palette.primaryContainer,
    '--qe-brand-orange': palette.primary,
    '--qe-brand-orange-deep': palette.accent,
    '--green': palette.success,
    '--qe-answer-correct-text': palette.success,
    '--qe-answer-correct-bg': palette.successContainer,
    '--qe-answer-correct-border': palette.success,
    '--qe-answer-incorrect-text': palette.error,
    '--qe-answer-incorrect-bg': palette.errorContainer,
    '--qe-answer-incorrect-border': palette.error,
    '--qe-answer-neutral-text': palette.textMuted,
    '--qe-answer-neutral-bg': palette.surfaceElevated,
    '--qe-answer-neutral-border': palette.borderSubtle,
  };
  for (const [name, value] of Object.entries(aliases)) root.style.setProperty(name, value);

  // Older institution records only carry a few theme colours instead of light/dark palettes.
  // Preserve that compatibility exactly as the learner web does while new saves use full roles.
  if (!supplied && rawTokens) {
    const legacyAliases: Record<string, string> = {
      primary: '--qe-color-primary',
      accent: '--qe-color-accent',
      background: '--qe-color-background',
      surface: '--qe-color-surface',
      text: '--qe-color-text',
    };
    for (const [key, variable] of Object.entries(legacyAliases)) {
      const color = safeColor(rawTokens[key] ?? rawTokens[key === 'primary' ? 'brandPrimary' : key === 'accent' ? 'brandAccent' : key]);
      if (color) root.style.setProperty(variable, color);
    }
    const primary = safeColor(rawTokens.primary ?? rawTokens.brandPrimary);
    const accent = safeColor(rawTokens.accent ?? rawTokens.brandAccent);
    const background = safeColor(rawTokens.background);
    const surface = safeColor(rawTokens.surface);
    if (primary) root.style.setProperty('--qe-brand-orange', primary);
    if (accent) root.style.setProperty('--qe-brand-orange-deep', accent);
    if (background) root.style.setProperty('--paper', background);
    if (surface) root.style.setProperty('--card', surface);
  }
}

function approvedAssetUrl(value: unknown, code: string, assetType: 'logo' | 'app-icon'): value is string {
  if (typeof value !== 'string') return false;
  const prefix = '/api/v1/institution/public/branding/' + code + '/assets/' + assetType + '?v=';
  return value.startsWith(prefix) && /^\d+$/.test(value.slice(prefix.length));
}

async function fetchInstitutionBrand(code: string): Promise<AdminTenantBrand> {
  const response = await apiFetch('/api/v1/institution/public/branding/' + encodeURIComponent(code), {
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'X-QE-Workspace': 'EDUCATION_ADMIN' },
  });
  const body = await response.json().catch(() => ({})) as Partial<AdminTenantBrand> & { message?: string; detail?: string };
  if (!response.ok) throw new Error(body.message || body.detail || 'Institution branding is unavailable.');
  if (normalizedInstitutionCode(body.institutionCode) !== code || typeof body.displayName !== 'string' || !body.displayName.trim()) {
    throw new Error('The institution branding response is incomplete.');
  }
  return {
    institutionCode: code,
    displayName: body.displayName.trim(),
    tagline: typeof body.tagline === 'string' ? body.tagline.trim() : '',
    logoUrl: approvedAssetUrl(body.logoUrl, code, 'logo') ? body.logoUrl : null,
    appIconUrl: approvedAssetUrl(body.appIconUrl, code, 'app-icon') ? body.appIconUrl : null,
    brandingVersion: typeof body.brandingVersion === 'number' ? body.brandingVersion : undefined,
    themeTokens: body.themeTokens && typeof body.themeTokens === 'object' ? body.themeTokens : undefined,
  };
}

export function AdminThemeProvider({
  institutionCode,
  enabled = true,
  children,
}: {
  institutionCode: string;
  enabled?: boolean;
  children: ReactNode;
}) {
  const code = normalizedInstitutionCode(institutionCode) || '';
  const [brand, setBrand] = useState<AdminTenantBrand | null>(null);
  const [isResolving, setIsResolving] = useState(false);
  const [savedMode, setSavedMode] = useState<AdminAppearanceMode | null>(null);
  const mode = savedMode ?? 'institution';

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem(APPEARANCE_KEY);
      setSavedMode(isAppearanceMode(saved) ? saved : null);
    } catch {
      setSavedMode(null);
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key !== APPEARANCE_KEY) return;
      setSavedMode(isAppearanceMode(event.newValue) ? event.newValue : null);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  useEffect(() => {
    let active = true;
    if (!enabled || !code) {
      setBrand(null);
      setIsResolving(false);
      return () => { active = false; };
    }

    setBrand(null);
    setIsResolving(true);
    fetchInstitutionBrand(code)
      .then(next => { if (active) setBrand(next); })
      .catch(() => { if (active) setBrand(null); })
      .finally(() => { if (active) setIsResolving(false); });

    return () => { active = false; };
  }, [code, enabled]);

  useEffect(() => {
    const root = document.documentElement;
    const media = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = () => {
      const actual = effectiveAppearance(mode, configuredAppearance(brand), media.matches);
      root.dataset.qeAppearance = actual;
      root.style.colorScheme = actual;
      const tokens = brand?.themeTokens && typeof brand.themeTokens === 'object'
        ? brand.themeTokens
        : null;
      applyPalette(actual, tokens);
    };
    apply();
    media.addEventListener?.('change', apply);
    return () => media.removeEventListener?.('change', apply);
  }, [brand, mode]);

  const setMode = useCallback((next: AdminAppearanceMode) => {
    setSavedMode(next);
    try { window.localStorage.setItem(APPEARANCE_KEY, next); } catch { /* Appearance preference is optional. */ }
  }, []);

  const value = useMemo(
    () => ({ brand, institutionCode: code, isResolving, mode, setMode }),
    [brand, code, isResolving, mode, setMode],
  );
  return <AdminThemeContext.Provider value={value}>{children}</AdminThemeContext.Provider>;
}

export function useAdminTenantTheme() {
  return useContext(AdminThemeContext);
}

export function AdminAppearanceModeButton() {
  const { brand, mode, setMode } = useAdminTenantTheme();
  const nextMode: AdminAppearanceMode =
    mode === 'institution' ? 'system' : mode === 'system' ? 'dark' : mode === 'dark' ? 'light' : 'institution';
  const defaultLabel = brand?.displayName || 'Institution default';
  const modeName: Record<AdminAppearanceMode, string> = {
    institution: defaultLabel,
    system: 'System',
    dark: 'Dark',
    light: 'Light',
  };
  const currentLabel = mode === 'institution' ? defaultLabel + ' appearance' : modeName[mode] + ' appearance';
  const icon = mode === 'institution' ? '⌂' : mode === 'system' ? '◐' : mode === 'dark' ? '☾' : '☀';

  return (
    <button
      type="button"
      className="qe-appearance-toggle"
      onClick={() => setMode(nextMode)}
      aria-label={currentLabel + '. Activate to switch to ' + modeName[nextMode] + ' appearance.'}
      title={currentLabel + ' · next: ' + modeName[nextMode]}
      aria-live="polite"
    >
      <span aria-hidden="true">{icon}</span>
      <span className="qe-appearance-toggle-label">{modeName[mode]}</span>
    </button>
  );
}
