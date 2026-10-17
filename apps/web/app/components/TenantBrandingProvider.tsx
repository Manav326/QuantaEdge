'use client';

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname } from 'next/navigation';

const BRAND_SESSION_KEY = 'qe.institution.brand-code.v1';
const CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{2,31}$/;
const DEFAULT_TITLE = 'QuantaEdge | Smarter Decisions. Greater Growth.';
const DEFAULT_DESCRIPTION = 'AI-powered learning for Hindi-medium students, starting with Bihar Board Classes 6–8.';

export type TenantBrand = {
  institutionCode: string;
  displayName: string;
  logoAssetKey?: string | null;
  appIconAssetKey?: string | null;
  /** Only a same-origin, approved asset-serving endpoint is accepted for rendering. */
  logoUrl?: string | null;
  storeListingUrl?: string | null;
  brandingVersion?: number;
  themeTokens?: Record<string, unknown>;
};

type TenantBrandingValue = {
  brand: TenantBrand | null;
  isResolving: boolean;
  resolveInstitutionCode: (code: string) => Promise<boolean>;
  clearTenantBrand: () => void;
};

const TenantBrandingContext = createContext<TenantBrandingValue>({
  brand: null,
  isResolving: false,
  resolveInstitutionCode: async () => false,
  clearTenantBrand: () => undefined,
});

function normalizedCode(input: unknown): string | null {
  const value = String(input ?? '').trim().toUpperCase();
  return CODE_PATTERN.test(value) ? value : null;
}

function safeColor(value: unknown): string | null {
  return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value) ? value : null;
}

function updateTenantTheme(brand: TenantBrand | null) {
  const root = document.documentElement;
  const tokens = brand?.themeTokens ?? {};
  const primary = safeColor(tokens.primary);
  const accent = safeColor(tokens.accent);
  const background = safeColor(tokens.background);
  const surface = safeColor(tokens.surface);
  const text = safeColor(tokens.text);
  const variables: Record<string, string | null> = {
    '--institution-primary': primary,
    '--institution-accent': accent,
    '--institution-background': background,
    '--institution-surface': surface,
    '--institution-text': text,
    '--qe-brand-orange': primary,
    '--purple': primary,
    '--purple-dark': accent,
    '--qe-brand-orange-deep': accent,
    '--purple-soft': safeColor(tokens.softSurface),
    '--paper': background,
    '--card': surface,
    '--ink': text,
  };
  Object.entries(variables).forEach(([name, value]) => {
    if (value) root.style.setProperty(name, value);
    else root.style.removeProperty(name);
  });
  if (brand) root.dataset.tenantBrandCode = brand.institutionCode;
  else delete root.dataset.tenantBrandCode;

  const title = brand?.displayName?.trim();
  document.title = title || DEFAULT_TITLE;
  const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
  if (description) description.content = title ? `Learning and progress with ${title}.` : DEFAULT_DESCRIPTION;
  const appName = document.querySelector<HTMLMetaElement>('meta[name="application-name"]');
  if (appName) appName.content = title || 'QuantaEdge';

  // A school lettermark prevents the browser tab from continuing to show the Q mark.
  // Uploaded image keys are not URLs: only an explicit same-origin asset URL is eligible.
  const icon = document.querySelector<HTMLLinkElement>('link[rel="icon"]');
  if (icon) {
    if (brand && title) {
      const color = primary || '#F97316';
      const letter = title.slice(0, 1).replace(/[<>&"]/g, '');
      const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="${color}"/><text x="32" y="43" font-size="38" font-family="Arial,sans-serif" font-weight="700" text-anchor="middle" fill="#ffffff">${letter}</text></svg>`;
      icon.href = `data:image/svg+xml,${encodeURIComponent(svg)}`;
    } else {
      icon.href = '/favicon.svg';
    }
  }
}

async function fetchTenantBrand(code: string): Promise<TenantBrand> {
  const response = await fetch(`/api/v1/institution/public/branding/${encodeURIComponent(code)}`, {
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'X-QE-Workspace': window.location.pathname.startsWith('/student') ? 'STUDENT' : 'PARENT' },
  });
  const body = await response.json().catch(() => ({})) as Partial<TenantBrand> & { message?: string; detail?: string };
  if (!response.ok) throw new Error(body.message || body.detail || 'School branding is unavailable.');
  if (normalizedCode(body.institutionCode) !== code || typeof body.displayName !== 'string' || !body.displayName.trim()) {
    throw new Error('The school branding response is incomplete.');
  }
  // The API currently returns an asset key rather than a public URL. Never treat that
  // key as a URL; the initials fallback remains until the approved asset route exists.
  const logoUrl = typeof body.logoUrl === 'string' &&
    body.logoUrl.startsWith('/api/v1/institution/public/branding-assets/')
    ? body.logoUrl
    : null;
  return { ...body, institutionCode: code, displayName: body.displayName.trim(), logoUrl };
}

function codeFromCurrentUrl(): string | null {
  const query = new URLSearchParams(window.location.search);
  return normalizedCode(
    query.get('institution') || query.get('institutionCode') ||
    query.get('institution_code') || query.get('tenant')
  );
}

function readSessionCode(): string | null {
  try { return normalizedCode(window.sessionStorage.getItem(BRAND_SESSION_KEY)); }
  catch { return null; }
}

function saveSessionCode(code: string | null) {
  try {
    if (code) window.sessionStorage.setItem(BRAND_SESSION_KEY, code);
    else window.sessionStorage.removeItem(BRAND_SESSION_KEY);
  } catch {
    // Branding is not an authentication control; storage denial falls back to this page only.
  }
}

export function TenantBrandingProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [brand, setBrand] = useState<TenantBrand | null>(null);
  const [isResolving, setIsResolving] = useState(false);

  async function resolveInstitutionCode(input: string): Promise<boolean> {
    const code = normalizedCode(input);
    if (!code) return false;
    setIsResolving(true);
    try {
      const next = await fetchTenantBrand(code);
      setBrand(next);
      saveSessionCode(code);
      updateTenantTheme(next);
      return true;
    } catch {
      return false;
    } finally {
      setIsResolving(false);
    }
  }

  function clearTenantBrand() {
    saveSessionCode(null);
    setBrand(null);
    updateTenantTheme(null);
  }

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      const urlCode = codeFromCurrentUrl();
      const candidate = urlCode || readSessionCode();
      if (candidate) {
        setIsResolving(true);
        try {
          const next = await fetchTenantBrand(candidate);
          if (cancelled) return;
          setBrand(next);
          saveSessionCode(candidate);
          updateTenantTheme(next);
          return;
        } catch {
          if (cancelled) return;
          if (urlCode) saveSessionCode(null);
          setBrand(null);
          updateTenantTheme(null);
        } finally {
          if (!cancelled) setIsResolving(false);
        }
        return;
      }

      // Existing learner identities can automatically resolve branding when they have
      // exactly one active institution membership. Multiple memberships require an
      // explicit institution link so one school's branding is never guessed.
      if (pathname.startsWith('/student')) {
        try {
          const response = await fetch('/api/v1/institution/memberships/me', {
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { 'X-QE-Workspace': 'STUDENT' },
          });
          if (response.ok) {
            const body = await response.json() as { institutions?: Array<{
              tenant_code?: string; institution_status?: string; membership_status?: string;
            }> };
            const active = (body.institutions || []).filter(item =>
              item.institution_status === 'ACTIVE' && item.membership_status === 'ACTIVE');
            if (active.length === 1 && active[0].tenant_code) {
              const next = await fetchTenantBrand(active[0].tenant_code);
              if (cancelled) return;
              setBrand(next);
              saveSessionCode(next.institutionCode);
              updateTenantTheme(next);
              return;
            }
          }
        } catch {
          // Membership lookup is best-effort; it must not block ordinary learner access.
        }
      }
      if (!cancelled) {
        setBrand(null);
        updateTenantTheme(null);
      }
    };
    void run();
    return () => { cancelled = true; };
  }, [pathname]);

  const value = useMemo(() => ({ brand, isResolving, resolveInstitutionCode, clearTenantBrand }),
    [brand, isResolving]);
  return <TenantBrandingContext.Provider value={value}>{children}</TenantBrandingContext.Provider>;
}

export function useTenantBranding() {
  return useContext(TenantBrandingContext);
}

export function TenantBrandMark({ className = '' }: { className?: string }) {
  const { brand } = useTenantBranding();
  const title = brand?.displayName?.trim();
  const logoUrl = brand?.logoUrl && brand.logoUrl.startsWith('/api/v1/institution/public/branding-assets/')
    ? brand.logoUrl
    : null;
  if (brand) {
    return logoUrl
      ? <img className={className} src={logoUrl} alt="" aria-hidden="true" />
      : <span className={className} role="img" aria-label={title || 'School'}>{(title || 'S').slice(0, 1).toLocaleUpperCase()}</span>;
  }
  return <img className={className} src="/branding/quantaedge-icon.png" alt="" aria-hidden="true" />;
}
