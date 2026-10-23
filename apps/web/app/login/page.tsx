'use client';


import { apiFetch } from '../components/apiClient';
import Link from 'next/link';
import { FormEvent, useState } from 'react';
import GoogleSignInButton from '../components/GoogleSignInButton';
import PasswordField from '../components/PasswordField';
import { useLocale } from "../components/LanguageProvider";
import { useTenantBranding } from '../components/TenantBrandingProvider';
import PublicAuthFrame from "../components/PublicAuthFrame";

type Method = 'mobile' | 'username';

async function readJson(response: Response): Promise<any> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.detail || body.error || 'The request could not be completed.');
  return body;
}

function redirectAfterLogin(body: any): void {
  // Only this same-origin, known workflow path is accepted as a post-login return target.
  const requestedReturn = new URLSearchParams(window.location.search).get('returnTo');
  const safeReturn = requestedReturn === '/account-deletion' ? requestedReturn : null;
  const parentSessionConfirmed = body.workspace === 'PARENT' && body.role === 'PARENT' &&
    Number(body.userId) > 0 && !(Number(body.studentId) > 0);
  const studentSessionConfirmed = body.workspace === 'STUDENT' && body.role === 'STUDENT' &&
    Number(body.studentId) > 0;
  if (parentSessionConfirmed) {
    if (safeReturn === '/account-deletion') {
      window.location.replace('/account-deletion');
      return;
    }
    window.location.replace('/parent');
    return;
  }
  if (studentSessionConfirmed) {
    if (safeReturn === '/account-deletion') {
      window.location.replace('/account-deletion');
      return;
    }
    window.location.replace('/student');
    return;
  }
  // This unified login page can serve both roles, but must never route a mismatched
  // role/workspace pair into either authenticated area.
  if (body.role !== 'PARENT' && body.role !== 'STUDENT') {
    throw new Error('The app could not confirm a Parent- or Student-scoped session.');
  }
  if (body.workspace !== 'PARENT' && body.workspace !== 'STUDENT') {
    throw new Error('The app could not confirm a Parent- or Student-scoped workspace.');
  }
  throw new Error('The account session could not be confirmed. Please sign in again.');
}

export default function LoginPage() {
  const { locale } = useLocale();
  const { brand, institutionContextRequested } = useTenantBranding();
  const english = locale === 'english';
  const [method, setMethod] = useState<Method>('mobile');
  const [mobile, setMobile] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [otp, setOtp] = useState('');
  const [devCode, setDevCode] = useState('');
  const [otpRequested, setOtpRequested] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  function changeMethod(next: Method) {
    setMethod(next); setOtp(''); setDevCode(''); setOtpRequested(false); setError(''); setNotice('');
    setMobile(''); setUsername(''); setPassword('');
  }

  async function mobileSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(''); setNotice('');
    if (!otpRequested && mobile.length !== 10) {
      setError(english ? 'Enter a valid 10-digit mobile number.' : 'मान्य 10 अंकों का मोबाइल नंबर दर्ज करें।');
      return;
    }
    if (otpRequested && otp.length !== 6) {
      setError(english ? 'Enter the 6-digit OTP.' : '6 अंकों का OTP दर्ज करें।');
      return;
    }
    setBusy(true);
    try {
      if (!otpRequested) {
        const body = await readJson(await apiFetch('/api/v1/auth/request-otp', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ mobile, purpose: 'LOGIN' }),
        }));
        setDevCode(String(body.devCode || ''));
        setOtpRequested(true);
        setNotice(english ? 'Enter the code sent to your verified mobile number.' : 'सत्यापित मोबाइल नंबर पर आया कोड दर्ज करें।');
        return;
      }
      const body = await readJson(await apiFetch('/api/v1/auth/verify-otp', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mobile, otp, purpose: 'LOGIN' }),
      }));
      redirectAfterLogin(body);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Mobile sign-in could not be completed.');
    } finally { setBusy(false); }
  }

  async function usernameSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setError(''); setNotice('');
    if (username.trim().length < 3 || !password) {
      setError(english ? 'Enter your username and password.' : 'यूज़रनेम और पासवर्ड दर्ज करें।');
      return;
    }
    setBusy(true);
    try {
      const body = await readJson(await apiFetch('/api/v1/auth/account-login', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: username.trim(), password }),
      }));
      redirectAfterLogin(body);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Username/password sign-in could not be completed.');
    } finally { setBusy(false); }
  }

  return (
    <PublicAuthFrame story="account">
      <section className="auth-card parent-auth-card" aria-labelledby="login-heading">
        <Link href="/" className="auth-back-link">{brand ? (english ? 'Back to ' + brand.displayName : brand.displayName + ' पर वापस जाएँ') : institutionContextRequested ? (english ? 'Back to learning space' : 'सीखने के स्थान पर वापस जाएँ') : (english ? 'Back to QuantaEdge' : 'QuantaEdge पर वापस जाएँ')}</Link>
        <span className="eyebrow">{english ? 'YOUR LEARNING SPACE' : 'आपकी सीखने की जगह'}</span>
        <h1 id="login-heading">{english ? 'Welcome back' : 'वापसी पर स्वागत है'}</h1>
        <p>{english ? 'Sign in with verified Gmail, mobile OTP, or your username and password.' : 'सत्यापित Gmail, मोबाइल OTP या यूज़रनेम और पासवर्ड से साइन इन करें।'}</p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        {notice && <div className="auth-message" role="status">{notice}</div>}
        <GoogleSignInButton locale={english ? 'en' : 'hi'} context="signin" />
        <div className="auth-separator"><span>{english ? 'OR SIGN IN WITH' : 'या इससे साइन इन करें'}</span></div>
        <div className="auth-method-switch" role="tablist" aria-label={english ? 'Sign-in method' : 'साइन-इन तरीका'}>
          <button type="button" role="tab" aria-selected={method === 'mobile'} className={method === 'mobile' ? 'is-active' : ''}
            onClick={() => changeMethod('mobile')}>{english ? 'Mobile OTP' : 'मोबाइल OTP'}</button>
          <button type="button" role="tab" aria-selected={method === 'username'} className={method === 'username' ? 'is-active' : ''}
            onClick={() => changeMethod('username')}>{english ? 'Username & password' : 'यूज़रनेम और पासवर्ड'}</button>
        </div>
        {method === 'mobile' ? (
          <form className="auth-form" onSubmit={mobileSubmit}>
            {!otpRequested && <label>{english ? 'Verified mobile number' : 'सत्यापित मोबाइल नंबर'}
              <input value={mobile} onChange={e => setMobile(e.target.value.replace(/\D/g, '').slice(0, 10))}
                placeholder={english ? '10-digit mobile number' : '10 अंकों का मोबाइल नंबर'}
                inputMode="numeric" autoComplete="tel-national" required />
            </label>}
            {otpRequested && <>
              <p className="auth-note">{english ? 'Code sent to: ' : 'कोड भेजा गया: '}+91 {mobile}</p>
              {devCode && <div className="auth-dev-code"><span>{english ? 'Development OTP' : 'डेवलपमेंट OTP'}</span><strong>{devCode}</strong></div>}
              <label>{english ? '6-digit OTP' : '6 अंकों का OTP'}
                <input value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  inputMode="numeric" autoComplete="one-time-code" maxLength={6} required />
              </label>
            </>}
            <button type="submit" className="button button-dark full" disabled={busy || (!otpRequested && mobile.length !== 10) || (otpRequested && otp.length !== 6)}>
              {busy ? (english ? 'Please wait…' : 'कृपया प्रतीक्षा करें…') : otpRequested
                ? (english ? 'Verify and sign in' : 'सत्यापित करें और साइन इन करें')
                : (english ? 'Send OTP' : 'OTP भेजें')}
            </button>
            {otpRequested && <button type="button" className="auth-secondary-action" disabled={busy} onClick={() => {
              setOtpRequested(false); setOtp(''); setDevCode(''); setError(''); setNotice('');
            }}>{english ? 'Change mobile number' : 'मोबाइल नंबर बदलें'}</button>}
          </form>
        ) : (
          <form className="auth-form" onSubmit={usernameSubmit}>
            <label>{english ? 'Username' : 'यूज़रनेम'}
              <input value={username} onChange={e => setUsername(e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g, '').slice(0, 32))}
                autoComplete="username" required />
            </label>
            <label>{english ? 'Password' : 'पासवर्ड'}
              <PasswordField value={password} onChange={e => setPassword(e.target.value)}
                autoComplete="current-password" required />
            </label>
            <button type="submit" className="button button-dark full" disabled={busy || username.trim().length < 3 || !password}>
              {busy ? (english ? 'Signing in…' : 'साइन इन हो रहा है…') : (english ? 'Sign in' : 'साइन इन करें')}
            </button>
          </form>
        )}
        <div className="auth-mode-links">
          <Link href="/signup">{english ? 'Create an account' : 'खाता बनाएँ'}</Link>
          <Link href="/login/student">{english ? 'Young Learner sign-in' : 'बाल शिक्षार्थी साइन-इन'}</Link>
        </div>
        <p className="auth-note">{english
          ? 'Gmail is marked verified only after Google verifies the identity token. Mobile is marked verified only after a successful OTP.'
          : 'Google पहचान की पुष्टि के बाद ही Gmail और सफल OTP के बाद ही मोबाइल सत्यापित दिखेगा।'}</p>
      </section>
    </PublicAuthFrame>
  );
}
