'use client';

import Link from 'next/link';
import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import { LanguageSwitcher, LocaleText, useLocale } from '../../components/LanguageProvider';

async function readApi(response: Response): Promise<any> {
  const raw = await response.text();
  if (!raw.trim()) return {};
  try { return JSON.parse(raw); }
  catch { throw new Error('The server returned an unexpected response (' + response.status + ').'); }
}

export default function StudentLoginPage() {
  const router = useRouter();
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
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
      if (!response.ok) throw new Error(body.message || tx('Login details match नहीं हुईं। Parent से check करके दोबारा प्रयास करें।','Login details did not match. Check with your parent and try again.'));
      router.replace('/student'); router.refresh();
    } catch (e: any) {
      setError(e?.message || tx('Sign in नहीं हो पाया। दोबारा प्रयास करें।','Unable to sign in. Please try again.'));
    } finally { setBusy(false); }
  }

  return (
    <main className="auth-page">
      <div className="qe-language-auth-float"><LanguageSwitcher /></div>
      <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
      <section className="auth-card student-login-card">
        <Link href="/" className="auth-back-link"><LocaleText hinglish="← QuantaEdge पर वापस" english="← Back to QuantaEdge" /></Link>
        <span className="eyebrow"><LocaleText hinglish="STUDENT LOGIN" english="STUDENT LOGIN" /></span>
        <h1><LocaleText hinglish="आपकी learning space।" english="Your learning space." /></h1>
        <p><LocaleText hinglish="अपने Parent का registered mobile number और आपके लिए बनाया गया student username और password डालें।" english="Enter your parent’s registered mobile number and the student username and password created for you." /></p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        <form className="auth-form" onSubmit={submit}>
          <label><LocaleText hinglish="Parent का registered mobile number" english="Parent’s registered mobile number" />
            <input value={parentMobile} onChange={e => setParentMobile(e.target.value.replace(/\D/g, '').slice(0, 10))} placeholder={tx('Parent का 10 अंकों का mobile number', '10-digit parent mobile number')} inputMode="numeric" autoComplete="tel" required />
          </label>
          <label><LocaleText hinglish="आपका username" english="Your username" />
            <input value={username} onChange={e => setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g, '').slice(0, 32))} placeholder={tx('अपना username डालें', 'Enter your username')} autoComplete="username" required />
          </label>
          <label><LocaleText hinglish="आपका password" english="Your password" />
            <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={tx('अपना password डालें', 'Enter your password')} autoComplete="current-password" required />
          </label>
          <button type="submit" className="button button-dark full" disabled={busy || parentMobile.length !== 10 || username.length < 3 || !password}>{busy ? <LocaleText hinglish="आपकी learning space खुल रही है…" english="Opening your learning space…" /> : <LocaleText hinglish="Student sign in करें →" english="Sign in as student →" />}</button>
        </form>
        <div className="auth-mode-links"><Link href="/login"><LocaleText hinglish="Parent / guardian login" english="Parent / guardian login" /></Link></div>
        <small className="auth-note"><LocaleText hinglish="इस login से सिर्फ आपकी learning profile खुलेगी। Sign in नहीं हो रहा? Parent से कहें कि Parent Login → बच्चों के Profiles में आपका username और password set करें।" english="This login opens only your learning profile. Having trouble signing in? Ask your parent to set your username and password under Parent Login → Children’s profiles." /></small>
      </section>
    </main>
  );
}
