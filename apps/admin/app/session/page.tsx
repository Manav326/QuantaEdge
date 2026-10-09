'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';

type StaffSession = { role?: string; staffId?: number; permissions?: string[] };
function staffHome(session: StaffSession) {
  const permissions = Array.isArray(session.permissions) ? session.permissions : [];
  if (session.role === 'ADMIN') return '/';
  if (permissions.includes('CONTENT_VIEW')) return '/content';
  if (permissions.includes('AUDIT_VIEW')) return '/employees?view=audit';
  return null;
}
export default function AdminSessionLandingPage() {
  const router = useRouter();
  const [message, setMessage] = useState('Checking your staff session…');
  const [error, setError] = useState('');
  async function resolveSession() {
    setError('');
    setMessage('Checking your staff session…');
    try {
      const response = await fetch('/api/v1/auth/me', { cache: 'no-store' });
      if (response.ok) {
        const identity = await response.json() as StaffSession;
        const home = identity.staffId ? staffHome(identity) : null;
        if (home) { router.replace(home); return; }
        setError('This sign-in does not have staff-console access. Sign out before switching accounts.');
        return;
      }
      if (response.status === 401 || response.status === 403) {
        await fetch('/api/v1/auth/logout', { method: 'POST' }).catch(() => undefined);
        window.location.replace('/login');
        return;
      }
      setError('Your session could not be verified. Check the connection and retry.');
    } catch {
      setError('Your session could not be verified. Check the connection and retry.');
    }
    setMessage('Secure session check could not be completed.');
  }
  async function logoutToSwitch() {
    await fetch('/api/v1/auth/logout', { method: 'POST' }).catch(() => undefined);
    window.location.replace('/login');
  }
  useEffect(() => { void resolveSession(); }, []);
  return <main className="qe-session-gate"><section><img src="/branding/quantaedge-icon.png" alt="" width="48" height="48" /><span className="admin-kicker">QUANTAEDGE STAFF</span><h1>{error ? 'Account check needed' : 'Checking secure access…'}</h1><p>{error || message}</p>{error ? <div className="qe-session-gate-actions"><button className="button button-dark" onClick={() => void resolveSession()}>Retry</button><button className="button button-light" onClick={() => void logoutToSwitch()}>Log out and switch account</button></div> : null}</section></main>;
}
