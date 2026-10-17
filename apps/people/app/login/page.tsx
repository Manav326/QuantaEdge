'use client';
import { PeopleButton, PeopleFeedback } from '../components/PeopleUI';
import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import { useUiTranslation, PeopleLanguageSwitcher } from '../i18n/UiLocaleProvider';

import { useState } from 'react';
import { useRouter } from 'next/navigation';

async function api(path: string, body: Record<string, unknown>, statusMessage: (status: number) => string) {
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
  if (!response.ok) throw new Error(data.message || data.detail || data.error || statusMessage(response.status));
  return data;
}

export default function PeopleLogin() {
  const router = useRouter();
  const { t } = useUiTranslation();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function signIn() {
    setBusy(true);
    setError('');
    try {
      const session = await api('/api/v1/auth/hrms-login', { username: username.trim(), password }, status => t('people.auth.signInFailed', { status }));
      const meResponse = await fetch('/api/v1/auth/me', {
        headers: { 'X-QE-Workspace': 'HRMS' },
        credentials: 'same-origin',
        cache: 'no-store',
      });
      const me = await meResponse.json();
      if (!meResponse.ok || !me.staffId || me.workspace !== 'HRMS') {
        throw new Error(me.message || t('people.auth.sessionInvalid'));
      }
      router.replace(session.mustChangePassword || me.mustChangePassword ? '/change-password' : '/');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('people.auth.signInError'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-wrap">
      <aside className="login-story">
        <div className="qe-people-login-brand"><QuantaEdgeBrand href={null} variant="auth" className="qe-people-login-brand-lockup" /><span>{t('people.auth.brandTitle')}</span></div>
        <div>
          <div className="eyebrow">{t('people.auth.secureWorkspace')}</div>
          <h1>{t('people.auth.loginHeadline')}</h1>
          <p>{t('people.auth.loginDescription')}</p>
        </div>
        <footer className="small" style={{ color: '#c3d0e5' }}>{t('people.auth.brandTitle')}</footer>
      </aside>
      <section className="login-panel">
        <div className="login-card">
          <div className="qe-people-auth-language"><PeopleLanguageSwitcher /></div>
          <div className="eyebrow">{t('people.auth.employeeAccess')}</div>
          <h2>{t('people.auth.loginTitle')}</h2>
          <p>{t('people.auth.loginInstructions')}</p>
          {error && <PeopleFeedback tone="danger" role="alert">{error}</PeopleFeedback>}
          <form onSubmit={(event) => { event.preventDefault(); if (!busy) void signIn(); }}>
            <label htmlFor="username">{t('people.auth.username')}</label>
            <input
              id="username"
              className="input"
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              placeholder="HRMS_Admin"
              required
            />
            <label htmlFor="password">{t('people.auth.password')}</label>
            <input
              id="password"
              className="input"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete="current-password"
              required
            />
            <PeopleButton variant="primary" loading={busy} disabled={!username.trim() || !password}>
              {busy ? t('people.auth.signingIn') : t('people.auth.signIn')}
            </PeopleButton>
          </form>
          <p className="small">{t('people.auth.forgottenPassword')}</p>
        </div>
      </section>
    </main>
  );
}
