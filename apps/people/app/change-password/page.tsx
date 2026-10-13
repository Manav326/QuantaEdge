'use client';

import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';

export default function ChangeHrmsPassword() {
  const router = useRouter();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    if (newPassword.length < 12) {
      setError('Choose a new password with at least 12 characters.');
      return;
    }
    if (newPassword !== confirmPassword) {
      setError('The new passwords do not match.');
      return;
    }
    setBusy(true);
    try {
      const response = await fetch('/api/v1/auth/hrms/change-password', {
        method: 'POST',
        credentials: 'same-origin',
        cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'HRMS' },
        body: JSON.stringify({ currentPassword, newPassword }),
      });
      const raw = await response.text();
      let result: Record<string, any> = {};
      try { result = raw ? JSON.parse(raw) : {}; } catch { result = { message: raw }; }
      if (!response.ok) throw new Error(result.message || result.detail || result.error || 'Password could not be changed.');
      router.replace('/');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Password could not be changed.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-wrap">
      <aside className="login-story">
        <div className="brand">QuantaEdge <span>PEOPLE OPERATIONS</span></div>
        <div>
          <div className="eyebrow" style={{ color: '#a9c7ff' }}>ONE-TIME ACCOUNT SETUP</div>
          <h1>Secure your HRMS access.</h1>
          <p>Before opening employee or payroll information, replace the initial password with a strong, private password.</p>
        </div>
        <footer className="small" style={{ color: '#c3d0e5' }}>QUANTAEDGE · PEOPLE OPERATIONS</footer>
      </aside>
      <section className="login-panel">
        <div className="login-card">
          <div className="eyebrow">PASSWORD CHANGE REQUIRED</div>
          <h2>Create a new password</h2>
          <p>Use at least 12 characters. Do not reuse the initial or another account password.</p>
          {error && <div className="error" role="alert">{error}</div>}
          <form onSubmit={submit}>
            <label htmlFor="current-password">Current password</label>
            <input id="current-password" className="input" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} required />
            <label htmlFor="new-password">New password</label>
            <input id="new-password" className="input" type="password" autoComplete="new-password" minLength={12} value={newPassword} onChange={(event) => setNewPassword(event.target.value)} required />
            <label htmlFor="confirm-password">Confirm new password</label>
            <input id="confirm-password" className="input" type="password" autoComplete="new-password" minLength={12} value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} required />
            <button className="primary" disabled={busy || !currentPassword || newPassword.length < 12 || newPassword !== confirmPassword}>
              {busy ? 'Updating securely…' : 'Change password and continue'}
            </button>
          </form>
        </div>
      </section>
    </main>
  );
}
