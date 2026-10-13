'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';

type Identity = { role?: string; workspace?: string; staffId?: number; studentId?: number; userId?: number };
const SESSION_CHECK_TIMEOUT_MS = 10_000;

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

function homeFor(identity: Identity) {
  const workspace = identity.workspace;
  const hasStudentIdentity = Number(identity.studentId) > 0
    && !(Number(identity.userId) > 0)
    && !(Number(identity.staffId) > 0);
  const hasParentIdentity = Number(identity.userId) > 0
    && !(Number(identity.studentId) > 0)
    && !(Number(identity.staffId) > 0);

  if (identity.role === 'STUDENT' && workspace === 'STUDENT' && hasStudentIdentity) return '/student';
  if (identity.role === 'PARENT' && workspace === 'PARENT' && hasParentIdentity) return '/parent';
  return null;
}

export default function SessionLandingPage() {
  const router = useRouter();
  const [message, setMessage] = useState('Aapka secure session check ho raha hai…');
  const [error, setError] = useState('');
  async function resolveSession() {
    setError('');
    setMessage('Aapka secure session check ho raha hai…');
    try {
      const response = await withSessionTimeout((async () => {
        let current = await fetch('/api/v1/auth/me', {
          headers: { 'X-QE-Workspace': 'PARENT' }, cache: 'no-store',
        });
        if (!current.ok) {
          const studentResponse = await fetch('/api/v1/auth/me', {
            headers: { 'X-QE-Workspace': 'STUDENT' }, cache: 'no-store',
          });
          current = studentResponse;
        }
        return current;
      })());
      if (response.ok) {
        const identity = await response.json() as Identity;
        const home = homeFor(identity);
        if (home) { navigateWithFallback(router, home); return; }
        // A staff or stale legacy cookie should never force a learner to sign out.
        window.location.replace('/');
        return;
      }
      if (response.status === 401 || response.status === 403) {
        await Promise.all([
          fetch('/api/v1/auth/logout', {
            method: 'POST', headers: { 'X-QE-Workspace': 'PARENT' },
          }).catch(() => undefined),
          fetch('/api/v1/auth/logout', {
            method: 'POST', headers: { 'X-QE-Workspace': 'STUDENT' },
          }).catch(() => undefined),
        ]);
        window.location.replace('/');
        return;
      }
      setError('Session verify nahi ho paaya. Network check karke dobara try karein.');
    } catch {
      setError('Session verify nahi ho paaya. Network check karke dobara try karein.');
    }
    setMessage('Secure session check complete nahi ho paaya.');
  }
  async function logoutToSwitch() {
    await Promise.all([
      fetch('/api/v1/auth/logout', {
        method: 'POST', headers: { 'X-QE-Workspace': 'PARENT' },
      }).catch(() => undefined),
      fetch('/api/v1/auth/logout', {
        method: 'POST', headers: { 'X-QE-Workspace': 'STUDENT' },
      }).catch(() => undefined),
    ]);
    window.location.replace('/');
  }
  useEffect(() => { void resolveSession(); }, []);
  return <main className="qe-session-gate"><section><img src="/branding/quantaedge-icon.png" alt="" width="48" height="48" /><span className="eyebrow">QUANTAEDGE</span><h1>{error ? 'Account check needed' : 'Checking your account…'}</h1><p>{error || message}</p>{error ? <div className="qe-session-gate-actions"><button className="button button-dark" onClick={() => void resolveSession()}>Dobara try karein</button><button className="button button-light" onClick={() => void logoutToSwitch()}>Sign out karke home par jaayein</button></div> : null}</section></main>;
}
