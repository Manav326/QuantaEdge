'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
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
  logoUrl?: string | null;
  appIconUrl?: string | null;
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

function approvedAssetUrl(url: unknown, code: string, type: 'logo' | 'app-icon'): url is string {
  if (typeof url !== 'string') return false;
  const prefix = '/api/v1/institution/public/branding/' + code + '/assets/' + type + '?v=';
  if (!url.startsWith(prefix)) return false;
  return /^\d+$/.test(url.slice(prefix.length));
}

/** Update identity metadata only; theme tokens/presets are managed by ColorThemeProvider. */
function applyTenantIdentity(brand: TenantBrand | null) {
  const root = document.documentElement;
  if (brand) root.dataset.tenantBrandCode = brand.institutionCode;
  else delete root.dataset.tenantBrandCode;

  const title = brand?.displayName?.trim();
  document.title = title || DEFAULT_TITLE;
  const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
  if (description) description.content = title ? 'Learning and progress with ' + title + '.' : DEFAULT_DESCRIPTION;
  const appName = document.querySelector<HTMLMetaElement>('meta[name="application-name"]');
  if (appName) appName.content = title || 'QuantaEdge';

  const iconUrl = brand?.appIconUrl && approvedAssetUrl(brand.appIconUrl, brand.institutionCode, 'app-icon')
    ? brand.appIconUrl
    : brand?.logoUrl && approvedAssetUrl(brand.logoUrl, brand.institutionCode, 'logo')
      ? brand.logoUrl
      : null;
  const links = document.querySelectorAll<HTMLLinkElement>('link[rel~="icon"], link[rel="apple-touch-icon"]');
  links.forEach(link => {
    if (iconUrl) {
      link.href = iconUrl;
    } else if (brand && title && link.rel.includes('icon')) {
      // Fallback lettermark uses the active canonical theme token instead of defining a new palette.
      const color = getComputedStyle(root).getPropertyValue('--qe-brand-orange').trim() || '#F97316';
      const letter = title.slice(0, 1).replace(/[<>&"]/g, '');
      const svg = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="14" fill="' +
        color + '"/><text x="32" y="43" font-size="38" font-family="Arial,sans-serif" font-weight="700" text-anchor="middle" fill="#ffffff">' +
        letter + '</text></svg>';
      link.href = 'data:image/svg+xml,' + encodeURIComponent(svg);
    } else {
      link.href = link.rel === 'apple-touch-icon' ? '/branding/quantaedge-icon.png' : '/favicon.svg';
    }
  });
}

async function fetchTenantBrand(codeInput: string): Promise<TenantBrand> {
  const code = normalizedCode(codeInput);
  if (!code) throw new Error('Institution code is invalid.');
  const response = await fetch('/api/v1/institution/public/branding/' + encodeURIComponent(code), {
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'X-QE-Workspace': 'INSTITUTION' },
  });
  const body = await response.json().catch(() => ({})) as Partial<TenantBrand> & { message?: string; detail?: string };
  if (!response.ok) throw new Error(body.message || body.detail || 'School branding is unavailable.');
  if (normalizedCode(body.institutionCode) !== code || typeof body.displayName !== 'string' || !body.displayName.trim()) {
    throw new Error('The school branding response is incomplete.');
  }
  return {
    ...body,
    institutionCode: code,
    displayName: body.displayName.trim(),
    logoUrl: approvedAssetUrl(body.logoUrl, code, 'logo') ? body.logoUrl : null,
    appIconUrl: approvedAssetUrl(body.appIconUrl, code, 'app-icon') ? body.appIconUrl : null,
  };
}

function urlHasTenantParam(): boolean {
  const query = new URLSearchParams(window.location.search);
  return ['institution', 'institutionCode', 'institution_code', 'tenant'].some(key => query.has(key));
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
    // Branding is not an authentication control; storage denial falls back to the current page.
  }
}

export function TenantBrandingProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [brand, setBrand] = useState<TenantBrand | null>(null);
  const [isResolving, setIsResolving] = useState(false);

  const resolveInstitutionCode = useCallback(async (input: string): Promise<boolean> => {
    const code = normalizedCode(input);
    if (!code) return false;
    setIsResolving(true);
    try {
      const next = await fetchTenantBrand(code);
      setBrand(next);
      saveSessionCode(code);
      applyTenantIdentity(next);
      return true;
    } catch {
      saveSessionCode(null);
      setBrand(null);
      applyTenantIdentity(null);
      return false;
    } finally {
      setIsResolving(false);
    }
  }, []);

  const clearTenantBrand = useCallback(() => {
    saveSessionCode(null);
    setBrand(null);
    applyTenantIdentity(null);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      const hasUrlCode = urlHasTenantParam();
      const urlCode = codeFromCurrentUrl();
      const candidate = urlCode || (hasUrlCode ? null : readSessionCode());
      if (hasUrlCode && !urlCode) saveSessionCode(null);
      if (candidate) {
        setIsResolving(true);
        try {
          const next = await fetchTenantBrand(candidate);
          if (cancelled) return;
          setBrand(next);
          saveSessionCode(candidate);
          applyTenantIdentity(next);
          return;
        } catch {
          if (cancelled) return;
          if (hasUrlCode) saveSessionCode(null);
          setBrand(null);
          applyTenantIdentity(null);
        } finally {
          if (!cancelled) setIsResolving(false);
        }
        return;
      }

      // Existing learners can inherit the school's brand only when membership is unambiguous.
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
              applyTenantIdentity(next);
              return;
            }
          }
        } catch {
          // Membership lookup is best-effort and must not block ordinary learner access.
        }
      }
      if (!cancelled) {
        setBrand(null);
        applyTenantIdentity(null);
      }
    };
    void run();
    return () => { cancelled = true; };
  }, [pathname]);

  const value = useMemo(() => ({ brand, isResolving, resolveInstitutionCode, clearTenantBrand }),
    [brand, isResolving, resolveInstitutionCode, clearTenantBrand]);
  return <TenantBrandingContext.Provider value={value}>{children}</TenantBrandingContext.Provider>;
}

export function useTenantBranding() {
  return useContext(TenantBrandingContext);
}

export function TenantBrandMark({ className = '' }: { className?: string }) {
  const { brand } = useTenantBranding();
  const title = brand?.displayName?.trim();
  const logoUrl = brand && approvedAssetUrl(brand.logoUrl, brand.institutionCode, 'logo') ? brand.logoUrl : null;
  if (brand) {
    return logoUrl
      ? <img className={className} src={logoUrl} alt="" aria-hidden="true" />
      : <span className={className} role="img" aria-label={title || 'School'}>{(title || 'S').slice(0, 1).toLocaleUpperCase()}</span>;
  }
  return <img className={className} src="/branding/quantaedge-icon.png" alt="" aria-hidden="true" />;
}
