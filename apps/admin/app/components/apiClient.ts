'use client';

import { notifyUser } from './GlobalNotificationHost';
import { DEFAULT_UI_LOCALE, resolveUiLocale, translateUi } from '../i18n/catalog';

type Tone = 'info' | 'success' | 'warning' | 'error';
type UserNotice = { message: string; tone: Tone };
function cleanMessage(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const clean = value.replace(/\s+/g, ' ').trim();
  if (!clean || /<(?:!doctype|html|head|body|script)\b/i.test(clean)) return null;
  if (/traceback \(most recent call last\)|\bat\s+\S+\s+\(/i.test(clean)) return null;
  if (/^[\[{]\s*["']/.test(clean)) return null;
  return clean.slice(0, 700);
}
function selectedLocale() {
  try {
    const match = document.cookie.match(/(?:^|;\s*)qe-locale=([^;]+)/);
    const cookieLocale = match ? decodeURIComponent(match[1]) : null;
    return resolveUiLocale(cookieLocale ?? window.localStorage.getItem('qe-locale')) ?? DEFAULT_UI_LOCALE;
  } catch { return DEFAULT_UI_LOCALE; }
}
function fallbackApiError(status: number) {
  return translateUi(selectedLocale(), 'common.notifications.requestFailed', { status });
}
function networkErrorMessage() {
  return translateUi(selectedLocale(), 'common.notifications.networkError');
}
function isApiRequest(input: RequestInfo | URL): boolean {
  try {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    const origin = typeof window === 'undefined' ? 'http://localhost' : window.location.origin;
    const pathname = new URL(raw, origin).pathname;
    return /(^|\/)(api|v[0-9]+)(\/|$)/i.test(pathname) || /\/graphql\/?$/i.test(pathname);
  } catch { return false; }
}
function isJsonContentType(value: string | null) {
  return Boolean(value && (value.includes('application/json') || value.includes('+json')));
}
function noticeTone(value: unknown): Tone | null {
  if (typeof value !== 'string') return null;
  const level = value.trim().toLowerCase();
  if (level === 'error' || level === 'failed' || level === 'failure') return 'error';
  if (level === 'warning' || level === 'warn') return 'warning';
  if (level === 'success' || level === 'successful') return 'success';
  if (level === 'info' || level === 'notice') return 'info';
  return null;
}
function messageFromPayload(value: unknown, depth = 0): string | null {
  if (depth > 5 || value == null) return null;
  const direct = cleanMessage(value);
  if (direct) return direct;
  if (Array.isArray(value)) {
    for (const item of value) {
      const message = messageFromPayload(item, depth + 1);
      if (message) return message;
    }
    return null;
  }
  if (typeof value !== 'object') return null;
  const record = value as Record<string, unknown>;
  for (const key of ['userMessage', 'message', 'msg', 'error', 'detail', 'title', 'description', 'reason', 'error_description', 'errors']) {
    const message = messageFromPayload(record[key], depth + 1);
    if (message) return message;
  }
  return null;
}
function explicitApiNotice(payload: unknown): UserNotice | null {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) return null;
  const record = payload as Record<string, unknown>;
  const level = noticeTone(record.level ?? record.severity);
  let candidate = record.notification ?? record.notice ?? record.userMessage ?? record.warning;
  if (candidate == null && level && record.message != null) candidate = record.message;
  if (candidate == null) return null;
  const fromWarningField = record.warning != null && candidate === record.warning;
  if (typeof candidate === 'object' && !Array.isArray(candidate)) {
    const detail = candidate as Record<string, unknown>;
    const message = cleanMessage(detail.message ?? detail.text) ??
      messageFromPayload(detail.message ?? detail.text ?? detail.detail);
    if (!message) return null;
    return { message, tone: noticeTone(detail.tone ?? detail.level) ?? level ?? (fromWarningField ? 'warning' : 'info') };
  }
  const message = cleanMessage(candidate) ?? messageFromPayload(candidate);
  return message ? { message, tone: level ?? (fromWarningField ? 'warning' : 'info') } : null;
}
function plainTextError(value: string, contentType: string | null): string | null {
  if (!value.trim() || /html/i.test(contentType || '')) return null;
  if (/^[\[{]\s*["']/.test(value.trim())) return null;
  return cleanMessage(value);
}
const INSTITUTION_CODE_PATTERN = /^[A-Z0-9][A-Z0-9_-]{2,31}$/;
const TENANT_BRANDING_SESSION_KEY = 'qe.institution.brand-code.v1';
const TENANT_BRANDING_COOKIES = [
  { name: 'qe-tenant-student', path: '/student' },
  { name: 'qe-tenant-parent', path: '/parent' },
  { name: 'qe-tenant-login-student', path: '/login/student' },
  { name: 'qe-tenant-signup', path: '/signup' },
  { name: 'qe-tenant-session', path: '/session' },
] as const;

function normalizedInstitutionCode(value: unknown): string | null {
  const code = String(value ?? '').trim().toUpperCase();
  return INSTITUTION_CODE_PATTERN.test(code) ? code : null;
}

/**
 * The tenant code is an untrusted selector, not an authorization grant. The API still checks
 * membership and course entitlement. Attach it centrally so UI login and API requests send the
 * same selector that TenantBrandingProvider used to render the current institution.
 */
function currentPageInstitutionCode(): string | null {
  if (typeof window === 'undefined') return null;
  try {
    const query = new URLSearchParams(window.location.search);
    const queryKey = ['institution', 'institutionCode', 'institution_code', 'tenant']
      .find(key => query.has(key));
    if (queryKey) return normalizedInstitutionCode(query.get(queryKey));

    const path = window.location.pathname;
    if (path.startsWith('/school/')) {
      return normalizedInstitutionCode(path.split('/').filter(Boolean)[1]);
    }

    const matchingCookie = TENANT_BRANDING_COOKIES.find(cookie =>
      path === cookie.path || path.startsWith(cookie.path + '/'));
    if (matchingCookie) {
      const cookie = document.cookie.split(';').map(part => part.trim())
        .find(part => part.startsWith(matchingCookie.name + '='));
      if (cookie) {
        try {
          return normalizedInstitutionCode(decodeURIComponent(cookie.slice(matchingCookie.name.length + 1)));
        } catch {
          // Invalid scoped metadata blocks fallback to a possibly stale sessionStorage value.
          return null;
        }
      }
    }
    return normalizedInstitutionCode(window.sessionStorage.getItem(TENANT_BRANDING_SESSION_KEY));
  } catch {
    return null;
  }
}

function withInstitutionContext(input: RequestInfo | URL, init?: RequestInit): RequestInit | undefined {
  if (typeof window === 'undefined' || !isApiRequest(input)) return init;
  try {
    const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url;
    if (new URL(raw, window.location.origin).origin !== window.location.origin) return init;

    const inputHeaders = typeof Request !== 'undefined' && input instanceof Request ? input.headers : undefined;
    const headers = new Headers(inputHeaders);
    new Headers(init?.headers).forEach((value, key) => headers.set(key, value));
    if (!headers.has('X-QE-Institution-Code')) {
      const institutionCode = currentPageInstitutionCode();
      if (institutionCode) headers.set('X-QE-Institution-Code', institutionCode);
    }
    return { ...init, headers };
  } catch {
    return init;
  }
}


async function inspectApiResponse(response: Response) {
  const contentType = response.headers.get('content-type');
  const length = Number(response.headers.get('content-length') || 0);
  if (response.ok && (!isJsonContentType(contentType) || length > 512 * 1024)) return;
  if (!response.ok && length > 1024 * 1024) {
    notifyUser(fallbackApiError(response.status), 'error');
    return;
  }
  let body = '';
  try { body = await response.clone().text(); } catch { /* Use status fallback. */ }
  let payload: unknown = null;
  if (body.trim()) { try { payload = JSON.parse(body); } catch { /* Plain-text failure handled below. */ } }
  const explicit = explicitApiNotice(payload);
  if (!response.ok) {
    notifyUser(explicit?.message ?? messageFromPayload(payload) ??
      plainTextError(body, contentType) ?? fallbackApiError(response.status), 'error');
  } else if (explicit) {
    notifyUser(explicit.message, explicit.tone);
  }
}
/** Central request/response path; the caller still receives the original Response. */
export async function apiFetch(input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
  const isApi = isApiRequest(input);
  let response: Response;
  try { response = await fetch(input, withInstitutionContext(input, init)); }
  catch (error) {
    const isAbort = Boolean(error && typeof error === 'object' && 'name' in error &&
      (error as { name?: unknown }).name === 'AbortError');
    if (isApi && !isAbort) notifyUser(networkErrorMessage(), 'error');
    throw error;
  }
  if (isApi) await inspectApiResponse(response);
  return response;
}
