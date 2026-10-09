'use client';

import Link from 'next/link';
import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';

async function readApi(response: Response): Promise<any> {
  const raw = await response.text();
  if (!raw.trim()) return {};
  try { return JSON.parse(raw); }
  catch { throw new Error('The server returned an unexpected response (' + response.status + ').'); }
}

export default function StudentLoginPage() {
  const router = useRouter();
  const [parentMobile, setParentMobile] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(''); setBusy(true);
    try {
      const response = await fetch('/api/v1/auth/student-login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parentMobile: parentMobile.trim(), username: username.trim(), password }),
      });
      const body = await readApi(response);
      if (!response.ok) throw new Error(body.message || 'Login details did not match. Check with your parent and try again.');
      router.replace('/student'); router.refresh();
    } catch (e: any) {
      setError(e?.message || 'Unable to sign in. Please try again.');
    } finally { setBusy(false); }
  }

  return (
    <main className="auth-page">
      <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
      <section className="auth-card student-login-card">
        <Link href="/" className="auth-back-link">← Back to QuantaEdge</Link>
        <span className="eyebrow">STUDENT LOGIN</span>
        <h1>Your learning space.</h1>
        <p>Enter your parent’s registered mobile number and the student username and password your parent created for you.</p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        <form className="auth-form" onSubmit={submit}>
          <label>Parent’s registered mobile number
            <input value={parentMobile} onChange={e => setParentMobile(e.target.value.replace(/\D/g, '').slice(0, 10))} placeholder="10-digit parent mobile" inputMode="numeric" autoComplete="tel" required />
          </label>
          <label>Your username
            <input value={username} onChange={e => setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g, '').slice(0, 32))} placeholder="Enter your username" autoComplete="username" required />
          </label>
          <label>Your password
            <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="Enter your password" autoComplete="current-password" required />
          </label>
          <button type="submit" className="button button-dark full" disabled={busy || parentMobile.length !== 10 || username.length < 3 || !password}>{busy ? 'Opening your learning space…' : 'Student sign in →'}</button>
        </form>
        <div className="auth-mode-links"><Link href="/login">Parent / guardian login</Link></div>
        <small className="auth-note">Your student login opens only your learning profile. If you can’t sign in yet, ask your parent to open Parent Login → Manage child profiles and create your username and password first.</small>
      </section>
    </main>
  );
}
