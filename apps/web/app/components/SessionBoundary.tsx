'use client';

import { useEffect, useState } from 'react';
import { LanguageSwitcher, LocaleText } from './LanguageProvider';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import { usePathname, useRouter } from 'next/navigation';

type Session = { role?: string; staffId?: number; studentId?: number; userId?: number; permissions?: string[] };
type WorkspaceRole = 'student' | 'parent';
let fetchRecoveryInstalled = false;
let verifiedWorkspace: WorkspaceRole | null = null;

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
    const retryInput = input instanceof Request ? input.clone() : input;
    const response = await nativeFetch(input, init);
    if (skip || (response.status !== 401 && response.status !== 403)) return response;
    if (!refreshInFlight) {
      refreshInFlight = nativeFetch('/api/v1/auth/refresh', { method: 'POST', cache: 'no-store' })
        .then(result => result.ok).catch(() => false).finally(() => { refreshInFlight = null; });
    }
    const refreshed = await refreshInFlight;
    if (!refreshed) {
      if (response.status === 401) window.dispatchEvent(new Event('qe:session-expired'));
      return response;
    }
    return nativeFetch(retryInput, init);
  };
  window.fetch = enhancedFetch;
  return () => {
    if (window.fetch === enhancedFetch) window.fetch = nativeFetch;
    fetchRecoveryInstalled = false;
  };
}

function roleHome(session: Session): string | null {
  if (session.studentId || session.role === 'STUDENT') return '/student';
  if (session.userId && session.role === 'PARENT') return '/parent';
  return null;
}
function workspaceRole(home: string | null): WorkspaceRole | null {
  if (home?.startsWith('/student')) return 'student';
  if (home?.startsWith('/parent')) return 'parent';
  return null;
}
function logoutAndSwitch() {
  void fetch('/api/v1/auth/logout', { method: 'POST' }).finally(() => {
    verifiedWorkspace = null;
    window.location.replace('/');
  });
}

if (typeof window !== 'undefined') installRefreshRecovery();

export default function SessionBoundary({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [blockedRole, setBlockedRole] = useState<string | null>(null);
  const isPublicEntry = pathname === '/' || pathname === '/login' || pathname === '/login/student';
  const protectedWorkspace: WorkspaceRole | null = pathname.startsWith('/student') ? 'student' : pathname.startsWith('/parent') ? 'parent' : null;
  const isProtected = protectedWorkspace !== null;
  const requiresCheck = isPublicEntry || isProtected;

  useEffect(() => installRefreshRecovery(), []);

  useEffect(() => {
    let alive = true;
    if (!requiresCheck) {
      verifiedWorkspace = null;
      setBlockedRole(null);
      setReady(true);
      return () => { alive = false; };
    }

    // Validate sign-in at workspace entry. On intra-workspace navigation, reuse
    // the verified role: API routes still enforce authentication and the shared
    // refresh interceptor handles expired access sessions without blanking the UI.
    const check = async (force = false) => {
      if (isPublicEntry) verifiedWorkspace = null;
      if (!force && protectedWorkspace && verifiedWorkspace === protectedWorkspace) {
        setBlockedRole(null);
        setReady(true);
        return;
      }
      setReady(false);
      setBlockedRole(null);
      try {
        const response = await fetch('/api/v1/auth/me', { cache: 'no-store' });
        if (!alive) return;
        if (!response.ok) {
          verifiedWorkspace = null;
          if (isProtected) router.replace(pathname.startsWith('/student') ? '/login/student' : '/login');
          else setReady(true);
          return;
        }
        const signedIn = await response.json() as Session;
        if (!alive) return;
        const home = roleHome(signedIn);
        const homeWorkspace = workspaceRole(home);
        if (isPublicEntry && home) {
          verifiedWorkspace = homeWorkspace;
          router.replace(home);
          return;
        }
        if (protectedWorkspace && home && !pathname.startsWith(home)) {
          verifiedWorkspace = homeWorkspace;
          router.replace(home);
          return;
        }
        if ((isPublicEntry || isProtected) && !home) {
          verifiedWorkspace = null;
          setBlockedRole(signedIn.role || (signedIn.staffId ? 'STAFF' : 'UNKNOWN'));
          return;
        }
        verifiedWorkspace = protectedWorkspace;
        setReady(true);
      } catch {
        if (alive) {
          verifiedWorkspace = null;
          if (isProtected) router.replace(pathname.startsWith('/student') ? '/login/student' : '/login');
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
    if (!isProtected || !ready || blockedRole) return;
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
  }, [isProtected, ready, blockedRole]);

  useEffect(() => {
    const onSessionExpired = () => {
      if (isProtected) window.location.replace(pathname.startsWith('/student') ? '/login/student' : '/login');
    };
    window.addEventListener('qe:session-expired', onSessionExpired);
    return () => window.removeEventListener('qe:session-expired', onSessionExpired);
  }, [isProtected, pathname]);

  if (blockedRole) return <main className="qe-session-gate"><section><div className="qe-session-language"><LanguageSwitcher /></div><QuantaEdgeBrand href={null} variant="compact" className="qe-session-brand" /><span className="eyebrow"><LocaleText hinglish="SECURE WORKSPACE" english="SECURE WORKSPACE" /></span><h1><LocaleText hinglish="यह account किसी दूसरे workspace का है।" english="This account belongs to another workspace." /></h1><p><LocaleText hinglish={blockedRole === 'ADMIN' || blockedRole === 'STAFF' ? 'आपका current sign-in staff console के लिए है। Account बदलने से पहले sign out करें।' : 'आपका current sign-in दूसरे account type के लिए है। Account बदलने से पहले sign out करें।'} english={blockedRole === 'ADMIN' || blockedRole === 'STAFF' ? 'Your current sign-in is for the staff console. Sign out before switching accounts.' : 'Your current sign-in is for a different account type. Sign out before switching accounts.'} /></p><button className="button button-dark" onClick={logoutAndSwitch}><LocaleText hinglish="Account बदलने के लिए sign out करें" english="Log out to switch accounts" /></button></section></main>;
  if (requiresCheck && !ready) return <main className="qe-session-gate" aria-live="polite"><section><div className="qe-session-language"><LanguageSwitcher /></div><QuantaEdgeBrand href={null} variant="compact" className="qe-session-brand" /><span className="eyebrow">QUANTAEDGE</span><h1><LocaleText hinglish="आपका workspace check हो रहा है…" english="Checking your workspace…" /></h1><p><LocaleText hinglish="आपका account और saved session verify किया जा रहा है।" english="Your account and saved session are being verified." /></p></section></main>;
  return <>{children}</>;
}
