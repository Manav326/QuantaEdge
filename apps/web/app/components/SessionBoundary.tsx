'use client';

import { useEffect, useState } from 'react';
import { LanguageSwitcher, LocaleText } from './LanguageProvider';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import { usePathname, useRouter } from 'next/navigation';

type Session = { role?: string; staffId?: number; studentId?: number; userId?: number; permissions?: string[] };
type WorkspaceRole = 'student' | 'parent';
let fetchRecoveryInstalled = false;
let verifiedWorkspace: WorkspaceRole | null = null;
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
  const explicitWorkspace = headers.get('X-QE-Workspace');
  const path = window.location.pathname;
  const inferredWorkspace = path.startsWith('/student') || path === '/login/student' ? 'STUDENT' : 'PARENT';
  headers.set('X-QE-Workspace', explicitWorkspace || inferredWorkspace);
  return new Request(input instanceof Request ? input.clone() : input, { ...init, headers });
}

function installRefreshRecovery() {
  if (fetchRecoveryInstalled || typeof window === 'undefined') return () => {};
  fetchRecoveryInstalled = true;
  const nativeFetch = window.fetch.bind(window);
  let refreshInFlight: Promise<boolean> | null = null;
  const excluded = ['/api/v1/auth/refresh', '/api/v1/auth/logout', '/api/v1/auth/request-otp', '/api/v1/auth/verify-otp', '/api/v1/auth/parent-login', '/api/v1/auth/student-login', '/api/v1/auth/preview-login'];
  const enhancedFetch: typeof window.fetch = async (input, init) => {
    let target: URL | null = null;
    try {
      const raw = input instanceof Request ? input.url : input instanceof URL ? input.toString() : String(input);
      target = new URL(raw, window.location.origin);
    } catch { return nativeFetch(input, init); }
    const isApi = target.origin === window.location.origin && target.pathname.startsWith('/api/v1/');
    const skip = !isApi || excluded.some(path => target!.pathname.startsWith(path));
    // Attach the portal identity to every API request, including login, refresh,
    // and logout. Ports do not isolate browser cookies on localhost.
    const requestInput = isApi ? workspaceRequest(input, init) : input;
    const requestInit = isApi ? undefined : init;
    const retryInput = requestInput instanceof Request ? requestInput.clone() : requestInput;
    const response = isApi && target.pathname.startsWith('/api/v1/auth/')
      ? await boundedFetch(nativeFetch, requestInput, requestInit)
      : await nativeFetch(requestInput, requestInit);
    if (skip || response.status !== 401) return response;
    if (!refreshInFlight) {
      refreshInFlight = boundedFetch(nativeFetch, workspaceRequest('/api/v1/auth/refresh', { method: 'POST', cache: 'no-store' }))
        .then(result => result.ok).catch(() => false).finally(() => { refreshInFlight = null; });
    }
    const refreshed = await refreshInFlight;
    if (!refreshed) {
      // Another tab may already have rotated the shared refresh cookie.
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
  return () => {
    if (window.fetch === enhancedFetch) window.fetch = nativeFetch;
    fetchRecoveryInstalled = false;
  };
}

function roleHome(session: Session): string | null {
  if (session.studentId || session.role === 'STUDENT') return '/student';
  if (session.role === 'PARENT') return '/parent';
  return null;
}
function workspaceRole(home: string | null): WorkspaceRole | null {
  if (home?.startsWith('/student')) return 'student';
  if (home?.startsWith('/parent')) return 'parent';
  return null;
}
if (typeof window !== 'undefined') installRefreshRecovery();

export default function SessionBoundary({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const isPublicEntry = pathname === '/' || pathname === '/login' || pathname === '/login/student';
  const protectedWorkspace: WorkspaceRole | null = pathname.startsWith('/student') ? 'student' : pathname.startsWith('/parent') ? 'parent' : null;
  const isProtected = protectedWorkspace !== null;
  const requiresCheck = isPublicEntry || isProtected;

  useEffect(() => installRefreshRecovery(), []);

  useEffect(() => {
    let alive = true;
    if (!requiresCheck) {
      verifiedWorkspace = null;
      setReady(true);
      return () => { alive = false; };
    }

    // Validate sign-in at workspace entry. On intra-workspace navigation, reuse
    // the verified role: API routes still enforce authentication and the shared
    // refresh interceptor handles expired access sessions without blanking the UI.
    const check = async (force = false) => {
      if (isPublicEntry) verifiedWorkspace = null;
      if (!force && protectedWorkspace && verifiedWorkspace === protectedWorkspace) {
        setReady(true);
        return;
      }
      setReady(false);
      try {
        const response = await withSessionTimeout((async () => {
          let current = await fetch('/api/v1/auth/me', { cache: 'no-store' });
          // At the public landing page, either a parent or student may already be signed in.
          // Probe the student scope separately so going Back does not hide an active student session.
          if (!current.ok && pathname === '/') {
            const studentResponse = await fetch('/api/v1/auth/me', {
              headers: { 'X-QE-Workspace': 'STUDENT' }, cache: 'no-store',
            });
            if (studentResponse.ok) current = studentResponse;
          }
          return current;
        })());
        if (!alive) return;
        if (!response.ok) {
          verifiedWorkspace = null;
          if (isProtected) navigateWithFallback(router, pathname.startsWith('/student') ? '/login/student' : '/login');
          else setReady(true);
          return;
        }
        const signedIn = await response.json() as Session;
        if (!alive) return;
        const home = roleHome(signedIn);
        const homeWorkspace = workspaceRole(home);
        if (isPublicEntry && home) {
          verifiedWorkspace = homeWorkspace;
          navigateWithFallback(router, home);
          return;
        }
        if (protectedWorkspace && home && !pathname.startsWith(home)) {
          verifiedWorkspace = homeWorkspace;
          navigateWithFallback(router, home);
          return;
        }
        if ((isPublicEntry || isProtected) && !home) {
          verifiedWorkspace = null;
          // A staff/legacy session must not block the learning site. Let the
          // appropriate public entry render or send a protected route to login.
          if (isProtected) navigateWithFallback(router, pathname.startsWith('/student') ? '/login/student' : '/login');
          else setReady(true);
          return;
        }
        verifiedWorkspace = protectedWorkspace;
        setReady(true);
      } catch {
        if (alive) {
          verifiedWorkspace = null;
          if (isProtected) navigateWithFallback(router, pathname.startsWith('/student') ? '/login/student' : '/login');
          else setReady(true);
        }
      }
    };
    void check();
    const onPageShow = (event: PageTransitionEvent) => {
      if (!event.persisted) return;
      document.body.classList.add('qe-session-bfcache-guard');
      verifiedWorkspace = null;
      void check(true).finally(() => document.body.classList.remove('qe-session-bfcache-guard'));
    };
    const onPopState = () => {
      verifiedWorkspace = null;
      void check(true).finally(() => document.body.classList.remove('qe-session-bfcache-guard'));
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
  }, [pathname, router, isPublicEntry, protectedWorkspace, requiresCheck, isProtected]);


  useEffect(() => {
    if (!isProtected || !ready) return;
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
  }, [isProtected, ready]);

  useEffect(() => {
    const onSessionExpired = () => {
      if (isProtected) window.location.replace(pathname.startsWith('/student') ? '/login/student' : '/login');
    };
    window.addEventListener('qe:session-expired', onSessionExpired);
    return () => window.removeEventListener('qe:session-expired', onSessionExpired);
  }, [isProtected, pathname]);

  if (requiresCheck && !ready) return <main className="qe-session-gate" aria-live="polite"><section><div className="qe-session-language"><LanguageSwitcher /></div><QuantaEdgeBrand href={null} variant="compact" className="qe-session-brand" /><span className="eyebrow">QUANTAEDGE</span><h1><LocaleText hinglish="आपका workspace check हो रहा है…" english="Checking your workspace…" /></h1><p><LocaleText hinglish="आपका account और saved session verify किया जा रहा है।" english="Your account and saved session are being verified." /></p></section></main>;
  return <>{children}</>;
}
