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
/** Central request/response path; the caller still receives the original Response.
 * Use caller mode only when the caller intentionally handles expected auth probes or renders
 * the failure inline. All other API requests retain global notification handling by default.
 */
export type ApiFetchOptions = { notificationMode?: 'global' | 'caller' };

/**
 * Coalesce only identical, in-flight same-origin API GETs. This is deliberately not a
 * resolved-response cache: callers that request no-store/forced refreshes or own an
 * AbortSignal keep their normal independent network behavior.
 */
const inFlightApiGets = new Map<string, Promise<Response>>();

function inFlightApiGetKey(
  input: RequestInfo | URL,
  init: RequestInit | undefined,
  notificationMode: ApiFetchOptions['notificationMode'],
): string | null {
  if (!isApiRequest(input) || (typeof Request !== 'undefined' && input instanceof Request)) return null;
  if (init?.signal || (init?.method && init.method.toUpperCase() !== 'GET')) return null;
  if (init?.cache && init.cache !== 'default') return null;

  try {
    const request = new Request(input, init);
    if (request.method !== 'GET' || request.cache !== 'default' || request.signal.aborted) return null;

    const url = new URL(request.url);
    if (typeof window === 'undefined' || url.origin !== window.location.origin) return null;

    const cacheControl = request.headers.get('cache-control') ?? '';
    const pragma = request.headers.get('pragma') ?? '';
    if (request.headers.has('authorization') || request.headers.has('range')
      || /\bno-cache\b|\bno-store\b/i.test(cacheControl) || /\bno-cache\b/i.test(pragma)) {
      return null;
    }

    const headers: [string, string][] = [];
    request.headers.forEach((value, key) => headers.push([key, value]));
    headers.sort(([left], [right]) => left.localeCompare(right));

    return JSON.stringify({
      url: request.url,
      credentials: request.credentials,
      mode: request.mode,
      redirect: request.redirect,
      headers,
      notificationMode: notificationMode === 'caller' ? 'caller' : 'global',
    });
  } catch {
    // A request shape we cannot safely normalize falls back to ordinary fetch().
    return null;
  }
}

/** Central request/response path; the caller still receives its own Response instance.
 * Use caller mode only when the caller intentionally handles expected auth probes or renders
 * the failure inline. All other API requests retain global notification handling by default.
 */
export async function apiFetch(
  input: RequestInfo | URL,
  init?: RequestInit,
  options: ApiFetchOptions = {},
): Promise<Response> {
  const isApi = options.notificationMode !== 'caller' && isApiRequest(input);
  const key = inFlightApiGetKey(input, init, options.notificationMode);
  let response: Response;

  try {
    if (key) {
      let pending = inFlightApiGets.get(key);
      if (!pending) {
        pending = (async () => {
          let sharedResponse: Response;
          try {
            sharedResponse = await fetch(input, init);
          } catch (error) {
            const isAbort = Boolean(error && typeof error === 'object' && 'name' in error &&
              (error as { name?: unknown }).name === 'AbortError');
            if (isApi && !isAbort) notifyUser(networkErrorMessage(), 'error');
            throw error;
          }

          // Inspect once per shared network request; each caller receives a separate clone.
          if (isApi) await inspectApiResponse(sharedResponse);
          return sharedResponse;
        })();

        inFlightApiGets.set(key, pending);
        const requestPromise = pending;
        void requestPromise.then(
          () => { if (inFlightApiGets.get(key) === requestPromise) inFlightApiGets.delete(key); },
          () => { if (inFlightApiGets.get(key) === requestPromise) inFlightApiGets.delete(key); },
        );
      }

      response = (await pending).clone();
    } else {
      response = await fetch(input, init);
    }
  } catch (error) {
    const isAbort = Boolean(error && typeof error === 'object' && 'name' in error &&
      (error as { name?: unknown }).name === 'AbortError');
    // A shared request reports its network error inside the shared promise, once.
    if (isApi && !key && !isAbort) notifyUser(networkErrorMessage(), 'error');
    throw error;
  }

  if (isApi && !key) await inspectApiResponse(response);
  return response;
}
