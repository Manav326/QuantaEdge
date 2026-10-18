'use client';

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { usePathname } from 'next/navigation';

const BRAND_SESSION_KEY = 'qe.institution.brand-code.v1';
const JOIN_SESSION_KEY = 'qe.institution.pending-join-code.v1';
const JOIN_TENANT_SESSION_KEY = 'qe.institution.pending-join-tenant-code.v1';
const ENROLLMENT_ISSUE_SESSION_KEY = 'qe.institution.enrollment-issue.v1';
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

export type EnrollmentIssueKind = 'invalid' | 'rate-limited' | 'temporary' | 'mismatch';

type TenantBrandingValue = {
  brand: TenantBrand | null;
  isResolving: boolean;
  institutionContextRequested: boolean;
  enrollmentIssue: EnrollmentIssueKind | null;
  clearEnrollmentIssue: () => void;
  retryEnrollment: () => void;
  resolveInstitutionCode: (code: string) => Promise<boolean>;
  clearTenantBrand: () => void;
};

const TenantBrandingContext = createContext<TenantBrandingValue>({
  brand: null,
  isResolving: false,
  institutionContextRequested: false,
  enrollmentIssue: null,
  clearEnrollmentIssue: () => undefined,
  retryEnrollment: () => undefined,
  resolveInstitutionCode: async () => false,
  clearTenantBrand: () => undefined,
});

function storedEnrollmentIssue(): EnrollmentIssueKind | null {
  try {
    const value = window.sessionStorage.getItem(ENROLLMENT_ISSUE_SESSION_KEY);
    return value === 'invalid' || value === 'rate-limited' || value === 'temporary' || value === 'mismatch'
      ? value
      : null;
  } catch { return null; }
}

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

function tenantCodeFromSchoolPath(): string | null {
  const segments = window.location.pathname.split('/').filter(Boolean);
  // The school slug is stable across /school/{code}/, /login/student and signup paths.
  return segments.length >= 2 && segments[0].toLowerCase() === 'school'
    ? normalizedCode(segments[1])
    : null;
}

function urlHasTenantParam(): boolean {
  const query = new URLSearchParams(window.location.search);
  return ['institution', 'institutionCode', 'institution_code', 'tenant'].some(key => query.has(key)) ||
    window.location.pathname.startsWith('/school/');
}

function codeFromCurrentUrl(): string | null {
  const query = new URLSearchParams(window.location.search);
  return normalizedCode(
    query.get('institution') || query.get('institutionCode') ||
    query.get('institution_code') || query.get('tenant') || tenantCodeFromSchoolPath()
  );
}

function readSessionCode(): string | null {
  try { return normalizedCode(window.sessionStorage.getItem(BRAND_SESSION_KEY)); }
  catch { return null; }
}

const SERVER_BRANDING_COOKIES = [
  { name: 'qe-tenant-student', path: '/student' },
  { name: 'qe-tenant-login-student', path: '/login/student' },
  { name: 'qe-tenant-signup', path: '/signup' },
  { name: 'qe-tenant-session', path: '/session' },
] as const;

function saveTenantMetadataCookies(code: string | null) {
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  for (const cookie of SERVER_BRANDING_COOKIES) {
    if (code) {
      document.cookie = cookie.name + '=' + encodeURIComponent(code) +
        '; Path=' + cookie.path + '; SameSite=Lax' + secure;
    } else {
      document.cookie = cookie.name + '=; Max-Age=0; Path=' + cookie.path + '; SameSite=Lax' + secure;
    }
  }
}

function saveSessionCode(code: string | null) {
  try {
    if (code) window.sessionStorage.setItem(BRAND_SESSION_KEY, code);
    else window.sessionStorage.removeItem(BRAND_SESSION_KEY);
  } catch {
    // session storage can be disabled; server-visible metadata cookies are independent.
  }
  saveTenantMetadataCookies(code);
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
  const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  const raw = query.get('joinCode') || query.get('join_code') || query.get('join')
    || fragment.get('joinCode') || fragment.get('join_code') || fragment.get('join');
  const value = raw?.trim() || '';
  return value.length >= 12 && value.length <= 256 && !hasControlCharacters(value) ? value : null;
}

function urlHasJoinParam(): boolean {
  const query = new URLSearchParams(window.location.search);
  const fragment = new URLSearchParams(window.location.hash.replace(/^#/, ''));
  return ['joinCode', 'join_code', 'join'].some(key => query.has(key) || fragment.has(key));
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
  const [enrollmentIssue, setEnrollmentIssue] = useState<EnrollmentIssueKind | null>(() => storedEnrollmentIssue());
  const [enrollmentRetryVersion, setEnrollmentRetryVersion] = useState(0);

  const setStoredEnrollmentIssue = useCallback((issue: EnrollmentIssueKind | null) => {
    setEnrollmentIssue(issue);
    try {
      if (issue) window.sessionStorage.setItem(ENROLLMENT_ISSUE_SESSION_KEY, issue);
      else window.sessionStorage.removeItem(ENROLLMENT_ISSUE_SESSION_KEY);
    } catch {
      // A status message should not prevent access if storage is blocked.
    }
  }, []);

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
    savePendingJoinCode(null);
    setBrand(null);
    setInstitutionContextRequested(false);
    setStoredEnrollmentIssue(null);
    applyTenantIdentity(null);
  }, []);

  const clearEnrollmentIssue = useCallback(() => setStoredEnrollmentIssue(null), [setStoredEnrollmentIssue]);
  const retryEnrollment = useCallback(() => setEnrollmentRetryVersion(version => version + 1), []);

  useEffect(() => {
    let cancelled = false;
    const run = async () => {
      setIsResolving(true);
      const hasUrlCode = urlHasTenantParam();
      const urlCode = codeFromCurrentUrl();
      const hasUrlJoin = urlHasJoinParam();
      const urlJoin = joinCodeFromCurrentUrl();
      if (hasUrlJoin) {
        setStoredEnrollmentIssue(null);
        savePendingJoinCode(urlJoin, urlCode);
        // Invite secrets live in the URL fragment for transport; once safely stored in
        // session storage, remove them from the address bar/history before authentication.
        // Invitation secrets must never remain in the address bar/history, including malformed values.
        const cleanQuery = new URLSearchParams(window.location.search);
        for (const key of ['joinCode', 'join_code', 'join']) cleanQuery.delete(key);
        const safeSearch = cleanQuery.toString();
        window.history.replaceState(
          window.history.state,
          '',
          window.location.pathname + (safeSearch ? '?' + safeSearch : '')
        );
      } else if (urlCode && readPendingJoinCode() && readPendingJoinTenantCode() !== urlCode) {
        savePendingJoinCode(null);
      }
      const pendingJoinCodeAtStart = readPendingJoinCode();
      const storedJoinTenant = readPendingJoinTenantCode();
      const storedCode = hasUrlCode || (pendingJoinCodeAtStart && !storedJoinTenant) ? null : readSessionCode();
      const candidate = urlCode || storedCode;
      let contextRequested = Boolean(hasUrlCode || candidate || hasUrlJoin || storedJoinTenant || pendingJoinCodeAtStart);
      setInstitutionContextRequested(contextRequested);
      if (hasUrlCode && !urlCode) saveSessionCode(null);

      const redeemPendingJoin = async (expectedInstitutionCode: string | null): Promise<string | null> => {
        if (!pathname.startsWith('/student')) return null;
        const joinCode = readPendingJoinCode();
        if (!joinCode) return null;
        setEnrollmentIssue(null);
        try {
          const response = await fetch('/api/v1/institution/join', {
            method: 'POST',
            credentials: 'same-origin',
            cache: 'no-store',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json', 'X-QE-Workspace': 'STUDENT' },
            body: JSON.stringify({ code: joinCode, institutionCode: expectedInstitutionCode || '' }),
          });
          if (!response.ok) {
            if (response.status === 401 || response.status === 403) {
              // Preserve the invitation while auth/workspace recovery redirects the learner to sign in.
              return null;
            }
            if ([400, 404, 410, 422].includes(response.status)) {
              savePendingJoinCode(null);
              setStoredEnrollmentIssue('invalid');
            } else if (response.status === 429) {
              setStoredEnrollmentIssue('rate-limited');
            } else {
              // Server/network failures are retryable; keep this tab's invitation for a safe retry.
              setStoredEnrollmentIssue('temporary');
            }
            return null;
          }
          const joined = await response.json() as { institutionCode?: string };
          const joinedCode = normalizedCode(joined.institutionCode);
          if (!joinedCode || (expectedInstitutionCode && joinedCode !== expectedInstitutionCode)) {
            savePendingJoinCode(null);
            setStoredEnrollmentIssue('mismatch');
            return null;
          }
          savePendingJoinCode(null);
          setStoredEnrollmentIssue(null);
          return joinedCode;
        } catch {
          // The server may have committed membership before the connection failed.
          // Repeating redemption is safe because the API is idempotent for active memberships.
          setStoredEnrollmentIssue('temporary');
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

          // A remembered school may belong to the previous account on this browser.
          // After learner authentication, validate it against this account before retaining it.
          if (pathname.startsWith('/student') && !joinedCode && !urlCode && !hasUrlJoin &&
              !storedJoinTenant && !readPendingJoinCode() && !enrollmentIssue) {
            const membershipResponse = await fetch(
              '/api/v1/institution/memberships/me?institutionCode=' + encodeURIComponent(candidate),
              { credentials: 'same-origin', cache: 'no-store',
                headers: { 'X-QE-Workspace': 'STUDENT', 'Accept': 'application/json' } }
            );
            if (membershipResponse.ok) {
              const scoped = await membershipResponse.json() as { institutions?: Array<{
                tenant_code?: string; institution_status?: string; membership_status?: string;
              }> };
              const belongsToCandidate = (scoped.institutions || []).some(item =>
                normalizedCode(item.tenant_code) === candidate &&
                item.institution_status === 'ACTIVE' && item.membership_status === 'ACTIVE');
              if (!belongsToCandidate) {
                saveSessionCode(null);
                const fallbackResponse = await fetch('/api/v1/institution/memberships/me', {
                  credentials: 'same-origin', cache: 'no-store',
                  headers: { 'X-QE-Workspace': 'STUDENT', 'Accept': 'application/json' },
                });
                let fallbackCode: string | null = null;
                if (fallbackResponse.ok) {
                  const fallback = await fallbackResponse.json() as { institutions?: Array<{
                    tenant_code?: string; institution_status?: string; membership_status?: string;
                  }>; requiresInstitutionContext?: boolean };
                  const active = (fallback.institutions || []).filter(item =>
                    item.institution_status === 'ACTIVE' && item.membership_status === 'ACTIVE');
                  if (active.length === 1) fallbackCode = normalizedCode(active[0].tenant_code);
                  if (fallback.requiresInstitutionContext) setInstitutionContextRequested(true);
                }
                if (fallbackCode) {
                  const fallbackBrand = await fetchTenantBrand(fallbackCode);
                  if (cancelled) return;
                  setBrand(fallbackBrand);
                  setInstitutionContextRequested(true);
                  saveSessionCode(fallbackCode);
                  applyTenantIdentity(fallbackBrand);
                } else {
                  setBrand(null);
                  setInstitutionContextRequested(true);
                  applyTenantIdentity(null, true);
                }
                return;
              }
            }
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
      const expectedJoinTenant = readPendingJoinTenantCode();
      const joinedCode = await redeemPendingJoin(expectedJoinTenant);
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
          // Membership exists; a transient branding read must not block learner access.
        }
      }
      if (hasUrlJoin || pendingJoinCodeAtStart || enrollmentIssue) {
        // Never substitute another previously used school when an invitation has no resolvable tenant.
        setBrand(null);
        setInstitutionContextRequested(true);
        applyTenantIdentity(null, true);
        if (!cancelled) setIsResolving(false);
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
  }, [pathname, enrollmentRetryVersion]);

  const value = useMemo(() => ({
    brand, isResolving, institutionContextRequested, enrollmentIssue,
    clearEnrollmentIssue, retryEnrollment, resolveInstitutionCode, clearTenantBrand,
  }), [brand, isResolving, institutionContextRequested, enrollmentIssue, clearEnrollmentIssue, retryEnrollment, resolveInstitutionCode, clearTenantBrand]);
  return <TenantBrandingContext.Provider value={value}>{children}</TenantBrandingContext.Provider>;
}

export function useTenantBranding() {
  return useContext(TenantBrandingContext);
}

export function TenantBrandMark({ className = '' }: { className?: string }) {
  const { brand, institutionContextRequested, isResolving } = useTenantBranding();
  const title = brand?.displayName?.trim();
  const logoUrl = brand && approvedAssetUrl(brand.logoUrl, brand.institutionCode, 'logo') ? brand.logoUrl : null;
  if (brand) {
    return logoUrl
      ? <img className={className} src={logoUrl} alt="" aria-hidden="true" />
      : <span className={className} role="img" aria-label={title || 'School'}>{(title || 'S').slice(0, 1).toLocaleUpperCase()}</span>;
  }
  return institutionContextRequested || isResolving
    ? <span className={className} aria-hidden="true" />
    : <img className={className} src="/branding/quantaedge-icon.png" alt="" aria-hidden="true" />;
}
