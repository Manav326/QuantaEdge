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
    const response = await nativeFetch(requestInput, requestInit);
    // Refresh only on an authentication failure. A 403 is an authorization decision.
    if (skip || response.status !== 401) return response;

    if (!refreshInFlight) {
      refreshInFlight = nativeFetch(
        hrmsRequest('/api/v1/auth/refresh', { method: 'POST', cache: 'no-store' }),
      ).then((result) => result.ok).catch(() => false).finally(() => { refreshInFlight = null; });
    }
    if (!await refreshInFlight) {
      // Another tab may already have rotated the refresh cookie; retry once before signing out.
      const crossTabRetry = await nativeFetch(retryInput);
      if (crossTabRetry.status === 401) window.dispatchEvent(new Event('qe:hrms-session-expired'));
      return crossTabRetry;
    }
    const retriedResponse = await nativeFetch(retryInput);
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
        const response = await fetch('/api/v1/auth/me', { cache: 'no-store' });
        if (!alive) return;
        if (!response.ok) {
          if (isLogin) setReady(true);
          else router.replace('/login');
          return;
        }
        const session = await response.json() as HrmsSession;
        if (!alive) return;
        if (!session.staffId || session.workspace !== 'HRMS') {
          if (isLogin) setReady(true);
          else router.replace('/login');
          return;
        }
        if (session.mustChangePassword && !isPasswordChange) {
          router.replace('/change-password');
          return;
        }
        if (!session.mustChangePassword && isPasswordChange) {
          router.replace('/');
          return;
        }
        if (isLogin) {
          router.replace(session.mustChangePassword ? '/change-password' : '/');
          return;
        }
        setReady(true);
      } catch {
        if (!alive) return;
        if (isLogin) setReady(true);
        else router.replace('/login');
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
    const onExpired = () => window.location.replace('/login');
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
