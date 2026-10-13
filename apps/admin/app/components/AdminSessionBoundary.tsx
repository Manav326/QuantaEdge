'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';

type StaffSession = { role?: string; staffId?: number; permissions?: string[] };
let recoveryInstalled = false;
const AUTH_REQUEST_TIMEOUT_MS = 8_000;
const SESSION_CHECK_TIMEOUT_MS = 10_000;

function boundedFetch(fetcher: typeof window.fetch, input: RequestInfo | URL, init?: RequestInit, timeoutMs = AUTH_REQUEST_TIMEOUT_MS) {
  const inheritedSignal = init?.signal ?? (input instanceof Request ? input.signal : undefined);
  const controller = new AbortController();
  const relayAbort = () => controller.abort(inheritedSignal?.reason);
  if (inheritedSignal?.aborted) controller.abort(inheritedSignal.reason);
  else inheritedSignal?.addEventListener('abort', relayAbort, { once: true });
  const timer = window.setTimeout(() => controller.abort(new DOMException('Request timed out.', 'TimeoutError')), timeoutMs);
  return fetcher(input, { ...init, signal: controller.signal }).finally(() => {
    window.clearTimeout(timer);
    inheritedSignal?.removeEventListener('abort', relayAbort);
  });
}

function withSessionTimeout<T>(operation: Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error('Session verification timed out. Please retry.')), SESSION_CHECK_TIMEOUT_MS);
    operation.then(
      value => { window.clearTimeout(timer); resolve(value); },
      error => { window.clearTimeout(timer); reject(error); },
    );
  });
}

function navigateWithFallback(router: ReturnType<typeof useRouter>, href: string) {
  const sourcePath = window.location.pathname;
  router.replace(href);
  window.setTimeout(() => {
    if (window.location.pathname === sourcePath) window.location.replace(href);
  }, 1_500);
}

function workspaceRequest(input: RequestInfo | URL, init?: RequestInit): Request {
  const headers = new Headers(input instanceof Request ? input.headers : undefined);
  new Headers(init?.headers).forEach((value, key) => headers.set(key, value));
  headers.set('X-QE-Workspace', 'EDUCATION_ADMIN');
  return new Request(input instanceof Request ? input.clone() : input, { ...init, headers });
}

function installRefreshRecovery() {
  if (recoveryInstalled || typeof window === 'undefined') return () => {};
  recoveryInstalled = true;
  const nativeFetch = window.fetch.bind(window);
  let refreshInFlight: Promise<boolean> | null = null;
  const excluded = ['/api/v1/auth/refresh', '/api/v1/auth/logout', '/api/v1/auth/request-otp', '/api/v1/auth/verify-otp', '/api/v1/auth/parent-login', '/api/v1/auth/student-login', '/api/v1/auth/preview-login'];
  const enhancedFetch: typeof window.fetch = async (input, init) => {
    let target: URL;
    try {
      const raw = input instanceof Request ? input.url : input instanceof URL ? input.toString() : String(input);
      target = new URL(raw, window.location.origin);
    } catch { return nativeFetch(input, init); }
    const isApi = target.origin === window.location.origin && target.pathname.startsWith('/api/v1/');
    const skip = !isApi || excluded.some(path => target.pathname.startsWith(path));
    // Attach the portal identity to every API request, including login, refresh,
    // and logout. Ports do not isolate browser cookies on localhost.
    const requestInput = isApi ? workspaceRequest(input, init) : input;
    const requestInit = isApi ? undefined : init;
    const retryInput = requestInput instanceof Request ? requestInput.clone() : requestInput;
    const response = isApi && target.pathname.startsWith('/api/v1/auth/')
      ? await boundedFetch(nativeFetch, requestInput, requestInit)
      : await nativeFetch(requestInput, requestInit);
    if (skip || response.status !== 401) return response;
    if (!refreshInFlight) refreshInFlight = boundedFetch(nativeFetch, workspaceRequest('/api/v1/auth/refresh', { method: 'POST', cache: 'no-store' })).then(r => r.ok).catch(() => false).finally(() => { refreshInFlight = null; });
    if (!await refreshInFlight) {
      // A different tab may have won the rotating-refresh-token race and already
      // updated the shared browser cookies. Retry once before declaring expiry.
      const crossTabRetry = await boundedFetch(nativeFetch, retryInput);
      if (crossTabRetry.ok) return crossTabRetry;
      if (crossTabRetry.status === 401) window.dispatchEvent(new Event('qe:session-expired'));
      return crossTabRetry;
    }
    const retriedResponse = await boundedFetch(nativeFetch, retryInput);
    if (retriedResponse.status === 401) window.dispatchEvent(new Event('qe:session-expired'));
    return retriedResponse;
  };
  window.fetch = enhancedFetch;
  return () => { if (window.fetch === enhancedFetch) window.fetch = nativeFetch; recoveryInstalled = false; };
}

function staffHome(session: StaffSession) {
  const permissions = Array.isArray(session.permissions) ? session.permissions : [];
  if (session.role === 'ADMIN') return '/';
  if (permissions.includes('CONTENT_VIEW')) return '/content';
  if (permissions.includes('AUDIT_VIEW')) return '/employees?view=audit';
  return null;
}

if (typeof window !== 'undefined') installRefreshRecovery();

export default function AdminSessionBoundary({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const isLogin = pathname === '/login';
  const isSession = pathname === '/session';
  useEffect(() => installRefreshRecovery(), []);
  useEffect(() => {
    let alive = true;
    if (isSession) { setReady(true); return; }
    setReady(false);
    const check = async () => {
      setReady(false);
      try {
        const response = await withSessionTimeout(fetch('/api/v1/auth/me', { cache: 'no-store' }));
        if (!alive) return;
        if (!response.ok) {
          if (isLogin) setReady(true); else navigateWithFallback(router, '/login');
          return;
        }
        const session = await response.json() as StaffSession;
        if (!alive) return;
        if (!session.staffId) {
          // A legacy cookie from the learning site must never trap the staff portal
          // on a workspace warning. Route to this portal's own sign-in instead.
          if (isLogin) setReady(true); else navigateWithFallback(router, '/login');
          return;
        }
        const home = staffHome(session);
        if (!home) {
          if (isLogin) setReady(true);
          else navigateWithFallback(router, '/login');
          return;
        }
        if (isLogin || (pathname === '/' && home !== '/')) { navigateWithFallback(router, home); return; }
        setReady(true);
      } catch {
        if (alive) { if (isLogin) setReady(true); else navigateWithFallback(router, '/login'); }
      }
    };
    void check();
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) document.body.classList.add('qe-session-bfcache-guard');
      void check().finally(() => document.body.classList.remove('qe-session-bfcache-guard'));
    };
    const onPopState = () => {
      void check().finally(() => document.body.classList.remove('qe-session-bfcache-guard'));
    };
    const onPageHide = () => document.body.classList.add('qe-session-bfcache-guard');
    window.addEventListener('pageshow', onPageShow);
    window.addEventListener('popstate', onPopState);
    window.addEventListener('pagehide', onPageHide);
    return () => {
      alive = false;
      window.removeEventListener('pageshow', onPageShow);
      window.removeEventListener('popstate', onPopState);
      window.removeEventListener('pagehide', onPageHide);
      document.body.classList.remove('qe-session-bfcache-guard');
    };
  }, [pathname, router, isLogin, isSession]);

  useEffect(() => {
    if (isLogin || isSession || !ready) return;
    let lastActivity = Date.now();
    const markActivity = () => { lastActivity = Date.now(); };
    const activityEvents = ['pointerdown', 'keydown', 'scroll', 'wheel', 'touchstart'];
    activityEvents.forEach(name => window.addEventListener(name, markActivity, { passive: true }));
    const heartbeat = window.setInterval(() => {
      if (document.visibilityState !== 'visible') return;
      if (Date.now() - lastActivity > 5 * 60 * 1000) return;
      void fetch('/api/v1/auth/me', { cache: 'no-store' }).catch(() => undefined);
    }, 2 * 60 * 1000);
    return () => {
      window.clearInterval(heartbeat);
      activityEvents.forEach(name => window.removeEventListener(name, markActivity));
    };
  }, [ready, isLogin, isSession]);

  useEffect(() => {
    const onSessionExpired = () => {
      if (!isLogin && !isSession) window.location.replace('/login');
    };
    window.addEventListener('qe:session-expired', onSessionExpired);
    return () => window.removeEventListener('qe:session-expired', onSessionExpired);
  }, [isLogin, isSession]);

  if (isSession) return <>{children}</>;
  if (!ready) return <main className="qe-session-gate" aria-live="polite"><section><img src="/branding/quantaedge-icon.png" alt="" width="48" height="48" /><span className="eyebrow">QUANTAEDGE STAFF</span><h1>Verifying secure access…</h1><p>Your staff role and saved session are being checked.</p></section></main>;
  return <>{children}</>;
}
