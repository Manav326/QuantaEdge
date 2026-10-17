'use client';
import { PeopleButton, PeopleFeedback } from '../components/PeopleUI';
import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import { useUiTranslation, PeopleLanguageSwitcher } from '../i18n/UiLocaleProvider';

import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';

export default function ChangeHrmsPassword() {
  const router = useRouter();
  const { t } = useUiTranslation();
  const [currentPassword, setCurrentPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    if (newPassword.length < 12) {
      setError(t('people.auth.shortPassword'));
      return;
    }
    if (newPassword !== confirmPassword) {
      setError(t('people.auth.passwordMismatch'));
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
      if (!response.ok) throw new Error(result.message || result.detail || result.error || t('people.auth.passwordChangeFailed'));
      router.replace('/');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('people.auth.passwordChangeFailed'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-wrap">
      <aside className="login-story">
        <div className="qe-people-login-brand"><QuantaEdgeBrand href={null} variant="auth" className="qe-people-login-brand-lockup" /><span>{t('people.auth.brandTitle')}</span></div>
        <div>
          <div className="eyebrow">{t('people.auth.oneTimeSetup')}</div>
          <h1>{t('people.auth.changeHeadline')}</h1>
          <p>{t('people.auth.changeDescription')}</p>
        </div>
        <footer className="small" style={{ color: '#c3d0e5' }}>{t('people.auth.brandTitle')}</footer>
      </aside>
      <section className="login-panel">
        <div className="login-card">
          <div className="qe-people-auth-language"><PeopleLanguageSwitcher /></div>
          <div className="eyebrow">{t('people.auth.passwordChangeRequired')}</div>
          <h2>{t('people.auth.newPasswordTitle')}</h2>
          <p>{t('people.auth.newPasswordInstructions')}</p>
          {error && <PeopleFeedback tone="danger" role="alert">{error}</PeopleFeedback>}
          <form onSubmit={submit}>
            <label htmlFor="current-password">{t('people.auth.currentPassword')}</label>
            <input id="current-password" className="input" type="password" autoComplete="current-password" value={currentPassword} onChange={(event) => setCurrentPassword(event.target.value)} required />
            <label htmlFor="new-password">{t('people.auth.newPassword')}</label>
            <input id="new-password" className="input" type="password" autoComplete="new-password" minLength={12} value={newPassword} onChange={(event) => setNewPassword(event.target.value)} required />
            <label htmlFor="confirm-password">{t('people.auth.confirmNewPassword')}</label>
            <input id="confirm-password" className="input" type="password" autoComplete="new-password" minLength={12} value={confirmPassword} onChange={(event) => setConfirmPassword(event.target.value)} required />
            <PeopleButton variant="primary" loading={busy} disabled={!currentPassword || newPassword.length < 12 || newPassword !== confirmPassword}>
              {busy ? t('people.auth.updatingPassword') : t('people.auth.changePassword')}
            </PeopleButton>
          </form>
        </div>
      </section>
    </main>
  );
}
