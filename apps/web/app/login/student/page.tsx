'use client';

import { UiText, useUiTranslation } from '../../i18n/useUiTranslation';
import Link from 'next/link';
import { FormEvent, useState } from 'react';
import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import { LanguageSwitcher, useLocale } from '../../components/LanguageProvider';

async function readApi(response: Response): Promise<any> {
  const raw = await response.text();
  if (!raw.trim()) return {};
  try { return JSON.parse(raw); }
  catch { throw new Error('The server returned an unexpected response (' + response.status + ').'); }
}

export default function StudentLoginPage() {
  const t = useUiTranslation();
  const { locale } = useLocale();
  const [parentIdentifier, setParentIdentifier] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(''); setBusy(true);
    try {
      const response = await fetch('/api/v1/auth/student-login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ parentIdentifier: parentIdentifier.trim(), username: username.trim(), password }),
      });
      const body = await readApi(response);
      if (!response.ok) throw new Error(body.message || t('student.login.detailsMismatch'));
      // Treat only an explicitly issued Student session as a successful Student login.
      if (body.role !== 'STUDENT' || body.workspace !== 'STUDENT' || Number(body.studentId) <= 0) {
        throw new Error(t('student.login.sessionNotConfirmed'));
      }
      // A full navigation lets the browser commit the HttpOnly scoped cookies before
      // the server-side route guard evaluates /student.
      window.location.replace('/student');
    } catch (e: any) {
      setError(e?.message || t('student.login.signInFailed'));
    } finally { setBusy(false); }
  }

  return (
    <main className="auth-page">
      <div className="qe-language-auth-float"><LanguageSwitcher /></div>
      <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
      <section className="auth-card student-login-card">
        <Link href="/" className="auth-back-link"><UiText message="legacy.web.back.to.quantaedge.afca99d4" /></Link>
        <span className="eyebrow"><UiText message="legacy.web.student.login.82db4c55" /></span>
        <h1><UiText message="legacy.web.your.learning.space.41b6b37f" /></h1>
        <p>{locale === 'english' ? 'Enter your parent’s verified Gmail or mobile number, plus the username and password they created for you.' : 'अपने माता-पिता का सत्यापित Gmail या मोबाइल नंबर और उनके द्वारा बनाया गया यूज़रनेम व पासवर्ड दर्ज करें।'}</p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        <form className="auth-form" onSubmit={submit}>
          <label>{locale === 'english' ? 'Parent’s verified Gmail or mobile number' : 'माता-पिता का सत्यापित Gmail या मोबाइल नंबर'}
            <input value={parentIdentifier} onChange={e => setParentIdentifier(e.target.value.trimStart())}
              placeholder={locale === 'english' ? 'parent@gmail.com or 10-digit mobile' : 'parent@gmail.com या 10 अंकों का मोबाइल'}
              autoComplete="username" required />
          </label>
          <label><UiText message="legacy.web.your.username.5d5c4011" />
            <input value={username} onChange={e => setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g, '').slice(0, 32))} placeholder={t('student.login.usernamePlaceholder')} autoComplete="username" required />
          </label>
          <label><UiText message="legacy.web.your.password.8a50627d" />
            <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={t('student.login.passwordPlaceholder')} autoComplete="current-password" required />
          </label>
          <button type="submit" className="button button-dark full" disabled={busy || parentIdentifier.trim().length < 5 || username.length < 3 || !password}>{busy ? <UiText message="legacy.web.opening.your.learning.space.74d347a2" /> : <UiText message="legacy.web.sign.in.as.student.d481cb79" />}</button>
        </form>
        <div className="auth-mode-links"><Link href="/login"><UiText message="legacy.web.parent.guardian.login.b45fedd1" /></Link></div>
        <small className="auth-note"><UiText message="legacy.web.this.login.opens.only.your.learning.pr.39010a3a" /></small>
      </section>
    </main>
  );
}
