'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname } from 'next/navigation';

const BRAND_SESSION_KEY = 'qe.institution.brand-code.v1';
const JOIN_SESSION_KEY = 'qe.institution.pending-join-code.v1';
const JOIN_TENANT_SESSION_KEY = 'qe.institution.pending-join-tenant-code.v1';
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
  institutionContextRequested: boolean;
  resolveInstitutionCode: (code: string) => Promise<boolean>;
  clearTenantBrand: () => void;
};

const TenantBrandingContext = createContext<TenantBrandingValue>({
  brand: null,
  isResolving: false,
  institutionContextRequested: false,
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
function applyTenantIdentity(brand: TenantBrand | null, institutionContextRequested = false) {
  const root = document.documentElement;
  if (brand) root.dataset.tenantBrandCode = brand.institutionCode;
  else delete root.dataset.tenantBrandCode;

  const title = brand?.displayName?.trim();
  document.title = title || (institutionContextRequested ? 'Learning space' : DEFAULT_TITLE);
  const description = document.querySelector<HTMLMetaElement>('meta[name="description"]');
  if (description) description.content = title ? 'Learning and progress with ' + title + '.' : institutionContextRequested ? 'Your school learning space.' : DEFAULT_DESCRIPTION;
  const appName = document.querySelector<HTMLMetaElement>('meta[name="application-name"]');
  if (appName) appName.content = title || (institutionContextRequested ? 'Learning space' : 'QuantaEdge');

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
      link.href = institutionContextRequested ? '/favicon.svg' : link.rel === 'apple-touch-icon' ? '/branding/quantaedge-icon.png' : '/favicon.svg';
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

function hasControlCharacters(value: string): boolean {
  return Array.from(value).some(character => {
    const code = character.charCodeAt(0);
    return code <= 31 || code === 127;
  });
}

function readPendingJoinTenantCode(): string | null {
  try { return normalizedCode(window.sessionStorage.getItem(JOIN_TENANT_SESSION_KEY)); }
  catch { return null; }
}

function joinCodeFromCurrentUrl(): string | null {
  const query = new URLSearchParams(window.location.search);
  const raw = query.get('joinCode') || query.get('join_code') || query.get('join');
  const value = raw?.trim() || '';
  return value.length >= 12 && value.length <= 256 && !hasControlCharacters(value) ? value : null;
}

function urlHasJoinParam(): boolean {
  const query = new URLSearchParams(window.location.search);
  return ['joinCode', 'join_code', 'join'].some(key => query.has(key));
}

function readPendingJoinCode(): string | null {
  try {
    const value = (window.sessionStorage.getItem(JOIN_SESSION_KEY) || '').trim();
    return value.length >= 12 && value.length <= 256 && !hasControlCharacters(value) ? value : null;
  } catch { return null; }
}

function savePendingJoinCode(code: string | null, institutionCode: string | null = null) {
  try {
    if (code) window.sessionStorage.setItem(JOIN_SESSION_KEY, code);
    else window.sessionStorage.removeItem(JOIN_SESSION_KEY);
    const tenantCode = code ? normalizedCode(institutionCode) : null;
    if (tenantCode) window.sessionStorage.setItem(JOIN_TENANT_SESSION_KEY, tenantCode);
    else window.sessionStorage.removeItem(JOIN_TENANT_SESSION_KEY);
  } catch {
    // A manually entered join code remains available if browser storage is unavailable.
  }
}

export function TenantBrandingProvider({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const [brand, setBrand] = useState<TenantBrand | null>(null);
  const [isResolving, setIsResolving] = useState(true);
  const [institutionContextRequested, setInstitutionContextRequested] = useState(false);

  const resolveInstitutionCode = useCallback(async (input: string): Promise<boolean> => {
    const code = normalizedCode(input);
    if (!code) return false;
    setIsResolving(true);
    setInstitutionContextRequested(true);
    try {
      const next = await fetchTenantBrand(code);
      setBrand(next);
      setInstitutionContextRequested(true);
      saveSessionCode(code);
      applyTenantIdentity(next);
      return true;
    } catch {
      saveSessionCode(null);
      setBrand(null);
      applyTenantIdentity(null, true);
      return false;
    } finally {
      setIsResolving(false);
    }
  }, []);

  const clearTenantBrand = useCallback(() => {
    saveSessionCode(null);
    setBrand(null);
    setInstitutionContextRequested(false);
    applyTenantIdentity(null);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      setIsResolving(true);
      const hasUrlCode = urlHasTenantParam();
      const urlCode = codeFromCurrentUrl();
      const hasUrlJoin = urlHasJoinParam();
      const urlJoin = joinCodeFromCurrentUrl();
      if (hasUrlJoin) savePendingJoinCode(urlJoin, urlCode);
      else if (urlCode && readPendingJoinCode() && readPendingJoinTenantCode() !== urlCode) savePendingJoinCode(null);
      const storedCode = hasUrlCode ? null : readSessionCode();
      const candidate = urlCode || storedCode;
      const storedJoinTenant = readPendingJoinTenantCode();
      let contextRequested = Boolean(hasUrlCode || candidate || hasUrlJoin || storedJoinTenant);
      setInstitutionContextRequested(contextRequested);
      if (hasUrlCode && !urlCode) saveSessionCode(null);

      const redeemPendingJoin = async (expectedInstitutionCode: string | null): Promise<string | null> => {
        if (!pathname.startsWith('/student')) return null;
        const joinCode = readPendingJoinCode();
        if (!joinCode) return null;
        try {
          const response = await fetch('/api/v1/institution/join', {
            method: 'POST',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-QE-Workspace': 'STUDENT' },
            body: JSON.stringify({ code: joinCode, institutionCode: expectedInstitutionCode || '' }),
          });
          if (!response.ok) {
            // Keep the invite while unauthenticated so it can be redeemed after sign-in.
            if (response.status !== 401 && response.status !== 403 && response.status < 500) {
              savePendingJoinCode(null);
            }
            return null;
          }
          const joined = await response.json() as { institutionCode?: string };
          const joinedCode = normalizedCode(joined.institutionCode);
          if (expectedInstitutionCode && joinedCode !== expectedInstitutionCode) {
            savePendingJoinCode(null);
            return null;
          }
          savePendingJoinCode(null);
          return joinedCode;
        } catch {
          return null;
        }
      };

      if (candidate) {
        setIsResolving(true);
        try {
          const next = await fetchTenantBrand(candidate);
          if (cancelled) return;
          setBrand(next);
          setInstitutionContextRequested(true);
          saveSessionCode(candidate);
          applyTenantIdentity(next);
          const joinedCode = await redeemPendingJoin(candidate);
          if (joinedCode && joinedCode !== candidate) {
            const joinedBrand = await fetchTenantBrand(joinedCode);
            if (cancelled) return;
            setBrand(joinedBrand);
            setInstitutionContextRequested(true);
            saveSessionCode(joinedCode);
            applyTenantIdentity(joinedBrand);
          }
          return;
        } catch {
          if (cancelled) return;
          saveSessionCode(null);
          setBrand(null);
          applyTenantIdentity(null, true);
        } finally {
          if (!cancelled) setIsResolving(false);
        }
        return;
      }

      // Learner invitation links may redeem a school membership after an existing account signs in.
      const joinedCode = await redeemPendingJoin(readPendingJoinTenantCode());
      if (joinedCode) {
        try {
          const joinedBrand = await fetchTenantBrand(joinedCode);
          if (cancelled) return;
          setBrand(joinedBrand);
          setInstitutionContextRequested(true);
          saveSessionCode(joinedCode);
          applyTenantIdentity(joinedBrand);
          if (!cancelled) setIsResolving(false);
          return;
        } catch {
          // Do not block the learner workspace if brand assets are temporarily unavailable.
        }
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
            }>; requiresInstitutionContext?: boolean };
            if (body.requiresInstitutionContext) {
              contextRequested = true;
              setInstitutionContextRequested(true);
            }
            const active = (body.institutions || []).filter(item =>
              item.institution_status === 'ACTIVE' && item.membership_status === 'ACTIVE');
            if (active.length === 1 && active[0].tenant_code) {
              const next = await fetchTenantBrand(active[0].tenant_code);
              if (cancelled) return;
              setBrand(next);
              setInstitutionContextRequested(true);
              saveSessionCode(next.institutionCode);
              applyTenantIdentity(next);
              if (!cancelled) setIsResolving(false);
              return;
            }
          }
        } catch {
          // Membership lookup is best-effort and must not block ordinary learner access.
        }
      }
      if (!cancelled) {
        setBrand(null);
        applyTenantIdentity(null, contextRequested);
        setInstitutionContextRequested(contextRequested);
        setIsResolving(false);
      }
    };
    void run();
    return () => { cancelled = true; };
  }, [pathname]);

  const value = useMemo(() => ({ brand, isResolving, institutionContextRequested, resolveInstitutionCode, clearTenantBrand }),
    [brand, isResolving, institutionContextRequested, resolveInstitutionCode, clearTenantBrand]);
  return <TenantBrandingContext.Provider value={value}>{children}</TenantBrandingContext.Provider>;
}

export function useTenantBranding() {
  return useContext(TenantBrandingContext);
}

export function TenantBrandMark({ className = '' }: { className?: string }) {
  const { brand, institutionContextRequested } = useTenantBranding();
  const title = brand?.displayName?.trim();
  const logoUrl = brand && approvedAssetUrl(brand.logoUrl, brand.institutionCode, 'logo') ? brand.logoUrl : null;
  if (brand) {
    return logoUrl
      ? <img className={className} src={logoUrl} alt="" aria-hidden="true" />
      : <span className={className} role="img" aria-label={title || 'School'}>{(title || 'S').slice(0, 1).toLocaleUpperCase()}</span>;
  }
  return institutionContextRequested
    ? <span className={className} aria-hidden="true" />
    : <img className={className} src="/branding/quantaedge-icon.png" alt="" aria-hidden="true" />;
}
