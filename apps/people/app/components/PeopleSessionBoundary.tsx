'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';

type HrmsSession = {
  authenticated?: boolean;
  staffId?: number;
  role?: string;
  workspace?: string;
  mustChangePassword?: boolean;
};

let refreshRecoveryInstalled = false;
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

function hrmsRequest(input: RequestInfo | URL, init?: RequestInit): Request {
  const headers = new Headers(input instanceof Request ? input.headers : undefined);
  new Headers(init?.headers).forEach((value, key) => headers.set(key, value));
  headers.set('X-QE-Workspace', 'HRMS');
  return new Request(input instanceof Request ? input.clone() : input, { ...init, headers });
}

function installHrmsRefreshRecovery() {
  if (refreshRecoveryInstalled || typeof window === 'undefined') return () => {};
  refreshRecoveryInstalled = true;
  const nativeFetch = window.fetch.bind(window);
  let refreshInFlight: Promise<boolean> | null = null;
  const excluded = [
    '/api/v1/auth/refresh',
    '/api/v1/auth/logout',
    '/api/v1/auth/request-otp',
    '/api/v1/auth/verify-otp',
    '/api/v1/auth/parent-login',
    '/api/v1/auth/student-login',
    '/api/v1/auth/preview-login',
    '/api/v1/auth/hrms-login',
  ];
  const enhancedFetch: typeof window.fetch = async (input, init) => {
    let target: URL;
    try {
      const raw = input instanceof Request ? input.url : input instanceof URL ? input.toString() : String(input);
      target = new URL(raw, window.location.origin);
    } catch {
      return nativeFetch(input, init);
    }
    const isApi = target.origin === window.location.origin && target.pathname.startsWith('/api/v1/');
    const skip = !isApi || excluded.some((path) => target.pathname.startsWith(path));
    const requestInput = isApi ? hrmsRequest(input, init) : input;
    const requestInit = isApi ? undefined : init;
    const retryInput = requestInput instanceof Request ? requestInput.clone() : requestInput;
    const response = isApi && target.pathname.startsWith('/api/v1/auth/')
      ? await boundedFetch(nativeFetch, requestInput, requestInit)
      : await nativeFetch(requestInput, requestInit);
    // Refresh only on an authentication failure. A 403 is an authorization decision.
    if (skip || response.status !== 401) return response;

    if (!refreshInFlight) {
      refreshInFlight = boundedFetch(
        nativeFetch, hrmsRequest('/api/v1/auth/refresh', { method: 'POST', cache: 'no-store' }),
      ).then((result) => result.ok).catch(() => false).finally(() => { refreshInFlight = null; });
    }
    if (!await refreshInFlight) {
      // Another tab may already have rotated the refresh cookie; retry once before signing out.
      const crossTabRetry = await boundedFetch(nativeFetch, retryInput);
      if (crossTabRetry.status === 401) window.dispatchEvent(new Event('qe:hrms-session-expired'));
      return crossTabRetry;
    }
    const retriedResponse = await boundedFetch(nativeFetch, retryInput);
    if (retriedResponse.status === 401) window.dispatchEvent(new Event('qe:hrms-session-expired'));
    return retriedResponse;
  };
  window.fetch = enhancedFetch;
  return () => {
    if (window.fetch === enhancedFetch) window.fetch = nativeFetch;
    refreshRecoveryInstalled = false;
  };
}

if (typeof window !== 'undefined') installHrmsRefreshRecovery();

export default function PeopleSessionBoundary({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const isLogin = pathname === '/login';
  const isPasswordChange = pathname === '/change-password';

  useEffect(() => installHrmsRefreshRecovery(), []);

  useEffect(() => {
    let alive = true;
    const check = async (force = false) => {
      if (!force) setReady(false);
      try {
        const response = await withSessionTimeout(fetch('/api/v1/auth/me', { cache: 'no-store' }));
        if (!alive) return;
        if (!response.ok) {
          if (isLogin) setReady(true);
          else navigateWithFallback(router, '/login');
          return;
        }
        const session = await response.json() as HrmsSession;
        if (!alive) return;
        if (!session.staffId || session.workspace !== 'HRMS') {
          if (isLogin) setReady(true);
          else navigateWithFallback(router, '/login');
          return;
        }
        if (session.mustChangePassword && !isPasswordChange) {
          navigateWithFallback(router, '/change-password');
          return;
        }
        if (!session.mustChangePassword && isPasswordChange) {
          navigateWithFallback(router, '/');
          return;
        }
        if (isLogin) {
          navigateWithFallback(router, session.mustChangePassword ? '/change-password' : '/');
          return;
        }
        setReady(true);
      } catch {
        if (!alive) return;
        if (isLogin) setReady(true);
        else navigateWithFallback(router, '/login');
      }
    };

    void check();
    const onPageShow = (event: PageTransitionEvent) => {
      if (event.persisted) void check(true);
    };
    const onPopState = () => void check(true);
    window.addEventListener('pageshow', onPageShow);
    window.addEventListener('popstate', onPopState);
    return () => {
      alive = false;
      window.removeEventListener('pageshow', onPageShow);
      window.removeEventListener('popstate', onPopState);
    };
  }, [pathname, router, isLogin, isPasswordChange]);

  useEffect(() => {
    if (!ready || isLogin) return;
    let lastActivity = Date.now();
    const markActivity = () => { lastActivity = Date.now(); };
    const activityEvents = ['pointerdown', 'keydown', 'scroll', 'wheel', 'touchstart'];
    activityEvents.forEach((name) => window.addEventListener(name, markActivity, { passive: true }));
    const heartbeat = window.setInterval(() => {
      if (document.visibilityState !== 'visible' || Date.now() - lastActivity > 5 * 60 * 1000) return;
      void fetch('/api/v1/auth/me', { cache: 'no-store' }).catch(() => undefined);
    }, 2 * 60 * 1000);
    return () => {
      window.clearInterval(heartbeat);
      activityEvents.forEach((name) => window.removeEventListener(name, markActivity));
    };
  }, [ready, isLogin]);

  useEffect(() => {
    const onExpired = () => {
      // The login page probes /auth/me; never reload it repeatedly if no session exists.
      if (window.location.pathname !== '/login') window.location.replace('/login');
    };
    window.addEventListener('qe:hrms-session-expired', onExpired);
    return () => window.removeEventListener('qe:hrms-session-expired', onExpired);
  }, []);

  if (!ready && !isLogin) {
    return <main className="login-wrap" aria-live="polite"><section className="login-panel"><div className="login-card"><div className="eyebrow">PEOPLE OPERATIONS</div><h2>Verifying your secure session</h2><p>Please wait while QuantaEdge verifies your HRMS access.</p></div></section></main>;
  }
  if (!ready && isLogin) {
    return <main className="login-wrap" aria-live="polite"><section className="login-panel"><div className="login-card"><div className="eyebrow">PEOPLE OPERATIONS</div><h2>Checking existing session</h2><p>Verifying your secure HRMS session.</p></div></section></main>;
  }
  return <>{children}</>;
}
