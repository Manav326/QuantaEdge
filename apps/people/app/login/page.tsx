'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';

async function api(path: string, body: Record<string, unknown>) {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'HRMS' },
    body: JSON.stringify(body),
  });
  const raw = await response.text();
  let data: Record<string, any> = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw }; }
  if (!response.ok) throw new Error(data.message || data.detail || data.error || `Sign-in failed (${response.status}).`);
  return data;
}

export default function PeopleLogin() {
  const router = useRouter();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function signIn() {
    setBusy(true);
    setError('');
    try {
      const session = await api('/api/v1/auth/hrms-login', { username: username.trim(), password });
      const meResponse = await fetch('/api/v1/auth/me', {
        headers: { 'X-QE-Workspace': 'HRMS' },
        credentials: 'same-origin',
        cache: 'no-store',
      });
      const me = await meResponse.json();
      if (!meResponse.ok || !me.staffId || me.workspace !== 'HRMS') {
        throw new Error(me.message || 'Could not validate the HRMS session.');
      }
      router.replace(session.mustChangePassword || me.mustChangePassword ? '/change-password' : '/');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not sign in to People Operations.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-wrap">
      <aside className="login-story">
        <div className="brand">QuantaEdge <span>PEOPLE OPERATIONS</span></div>
        <div>
          <div className="eyebrow" style={{ color: '#a9c7ff' }}>SECURE HRMS WORKSPACE</div>
          <h1>Great learning starts with great people.</h1>
          <p>Employee records, attendance, leave, payroll and team operations in a dedicated, access-controlled workspace.</p>
        </div>
        <footer className="small" style={{ color: '#c3d0e5' }}>QUANTAEDGE · PEOPLE OPERATIONS</footer>
      </aside>
      <section className="login-panel">
        <div className="login-card">
          <div className="eyebrow">EMPLOYEE ACCESS</div>
          <h2>Sign in to People Operations</h2>
          <p>Use the HRMS username and password assigned to your account. HRMS sign-in does not use OTP.</p>
          {error && <div className="error" role="alert">{error}</div>}
          <form onSubmit={(event) => { event.preventDefault(); if (!busy) void signIn(); }}>
            <label htmlFor="username">Username</label>
            <input
              id="username"
              className="input"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              placeholder="HRMS_Admin"
              required
            />
            <label htmlFor="password">Password</label>
            <input
              id="password"
              className="input"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
            <button className="primary" disabled={busy || !username.trim() || !password}>
              {busy ? 'Signing in securely…' : 'Sign in securely'}
            </button>
          </form>
          <p className="small">For a forgotten password, contact the authorised People Operations administrator. Never share your password.</p>
        </div>
      </section>
    </main>
  );
}
