'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';

type StaffSession = { role?: string; staffId?: number; permissions?: string[] };
let recoveryInstalled = false;

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
    const retryInput = input instanceof Request ? input.clone() : input;
    const response = await nativeFetch(input, init);
    if (skip || (response.status !== 401 && response.status !== 403)) return response;
    if (!refreshInFlight) refreshInFlight = nativeFetch('/api/v1/auth/refresh', { method: 'POST', cache: 'no-store' }).then(r => r.ok).catch(() => false).finally(() => { refreshInFlight = null; });
    if (!await refreshInFlight) return response;
    return nativeFetch(retryInput, init);
  };
  window.fetch = enhancedFetch;
  return () => { if (window.fetch === enhancedFetch) window.fetch = nativeFetch; recoveryInstalled = false; };
}

function staffHome(session: StaffSession) {
  const permissions = Array.isArray(session.permissions) ? session.permissions : [];
  if (session.role === 'ADMIN') return '/';
  if (permissions.includes('CONTENT_VIEW')) return '/content';
  if (permissions.includes('AUDIT_VIEW')) return '/employees?view=audit';
  return '/login';
}

async function logoutToSwitch() {
  await fetch('/api/v1/auth/logout', { method: 'POST' }).catch(() => undefined);
  window.location.replace('/login');
}

if (typeof window !== 'undefined') installRefreshRecovery();

export default function AdminSessionBoundary({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [ready, setReady] = useState(false);
  const [blocked, setBlocked] = useState(false);
  const isLogin = pathname === '/login';
  const isSession = pathname === '/session';
  useEffect(() => installRefreshRecovery(), []);
  useEffect(() => {
    let alive = true;
    if (isSession) { setReady(true); setBlocked(false); return; }
    setReady(false); setBlocked(false);
    const check = async () => {
      setReady(false);
      setBlocked(false);
      try {
        const response = await fetch('/api/v1/auth/me', { cache: 'no-store' });
        if (!alive) return;
        if (!response.ok) {
          if (isLogin) setReady(true); else router.replace('/login');
          return;
        }
        const session = await response.json() as StaffSession;
        if (!alive) return;
        if (!session.staffId) { setBlocked(true); return; }
        const home = staffHome(session);
        if (isLogin || (pathname === '/' && home !== '/')) { router.replace(home); return; }
        setReady(true);
      } catch {
        if (alive) { if (isLogin) setReady(true); else router.replace('/login'); }
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
  if (isSession) return <>{children}</>;
  if (blocked) return <main className="qe-session-gate"><section><img src="/branding/quantaedge-icon.png" alt="" width="48" height="48" /><span className="eyebrow">SECURE WORKSPACE</span><h1>This sign-in belongs to the learning site.</h1><p>To keep accounts separate, sign out before entering the staff console.</p><button className="button button-dark" onClick={() => void logoutToSwitch()}>Log out to switch accounts</button></section></main>;
  if (!ready) return <main className="qe-session-gate" aria-live="polite"><section><img src="/branding/quantaedge-icon.png" alt="" width="48" height="48" /><span className="eyebrow">QUANTAEDGE STAFF</span><h1>Verifying secure access…</h1><p>Your staff role and saved session are being checked.</p></section></main>;
  return <>{children}</>;
}
