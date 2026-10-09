'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';

type Session = { role?: string; staffId?: number; studentId?: number; userId?: number; permissions?: string[] };
let fetchRecoveryInstalled = false;

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
    if (!refreshed) return response;
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

function logoutAndSwitch() {
  void fetch('/api/v1/auth/logout', { method: 'POST' }).finally(() => window.location.replace('/'));
}

if (typeof window !== 'undefined') installRefreshRecovery();

export default function SessionBoundary({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [blockedRole, setBlockedRole] = useState<string | null>(null);
  const isPublicEntry = pathname === '/' || pathname === '/login' || pathname === '/login/student';
  const isProtected = pathname.startsWith('/parent') || pathname.startsWith('/student');
  const requiresCheck = isPublicEntry || isProtected;

  useEffect(() => installRefreshRecovery(), []);

  useEffect(() => {
    let alive = true;
    setReady(!requiresCheck);
    setBlockedRole(null);
    if (!requiresCheck) return () => { alive = false; };
    setReady(false);
    const check = async () => {
      setReady(false);
      setBlockedRole(null);
      try {
        const response = await fetch('/api/v1/auth/me', { cache: 'no-store' });
        if (!alive) return;
        if (!response.ok) {
          if (isProtected) router.replace(pathname.startsWith('/student') ? '/login/student' : '/login');
          else setReady(true);
          return;
        }
        const session = await response.json() as Session;
        if (!alive) return;
        const home = roleHome(session);
        if (isPublicEntry && home) { router.replace(home); return; }
        if (isProtected && home && !pathname.startsWith(home)) { router.replace(home); return; }
        if ((isPublicEntry || isProtected) && !home) {
          setBlockedRole(session.role || (session.staffId ? 'STAFF' : 'UNKNOWN'));
          return;
        }
        setReady(true);
      } catch {
        if (alive) {
          if (isProtected) router.replace(pathname.startsWith('/student') ? '/login/student' : '/login');
          else setReady(true);
        }
      }
    };
    void check();
    const onHistory = () => { void check(); };
    window.addEventListener('pageshow', onHistory);
    window.addEventListener('popstate', onHistory);
    return () => {
      alive = false;
      window.removeEventListener('pageshow', onHistory);
      window.removeEventListener('popstate', onHistory);
    };
  }, [pathname, router, isPublicEntry, isProtected, requiresCheck]);

  if (blockedRole) return <main className="qe-session-gate"><section><img src="/branding/quantaedge-icon.png" alt="" width="48" height="48" /><span className="eyebrow">SECURE WORKSPACE</span><h1>This account belongs to another workspace.</h1><p>Your current sign-in is for {blockedRole === 'ADMIN' || blockedRole === 'STAFF' ? 'the staff console' : 'a different account type'}. Sign out before switching accounts.</p><button className="button button-dark" onClick={logoutAndSwitch}>Log out to switch accounts</button></section></main>;
  if (requiresCheck && !ready) return <main className="qe-session-gate" aria-live="polite"><section><img src="/branding/quantaedge-icon.png" alt="" width="48" height="48" /><span className="eyebrow">QUANTAEDGE</span><h1>Checking your workspace…</h1><p>Your account and saved session are being verified.</p></section></main>;
  return <>{children}</>;
}
