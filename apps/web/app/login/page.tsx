'use client';

import Link from 'next/link';
import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import { LanguageSwitcher, LocaleText, useLocale } from '../components/LanguageProvider';

type Mode = 'login' | 'register' | 'reset';
type Step = 'mobile' | 'otp';
type LoginMethod = 'password' | 'otp';

async function readApi(response: Response): Promise<any> {
  const raw = await response.text();
  if (!raw.trim()) return {};
  try { return JSON.parse(raw); }
  catch { throw new Error('The server returned an unexpected response (' + response.status + ').'); }
}

export default function ParentLoginPage() {
  const router = useRouter();
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const [mode, setMode] = useState<Mode>('login');
  const [loginMethod, setLoginMethod] = useState<LoginMethod>('password');
  const [step, setStep] = useState<Step>('mobile');
  const [mobile, setMobile] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [otp, setOtp] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirmNewPassword, setConfirmNewPassword] = useState('');
  const [devCode, setDevCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const otpFlow = mode !== 'login' || loginMethod === 'otp';

  function resetState(next: Mode) {
    setMode(next); setLoginMethod('password'); setStep('mobile'); setOtp(''); setDevCode('');
    setError(''); setNotice(''); setPassword(''); setConfirmPassword('');
    setNewPassword(''); setConfirmNewPassword('');
  }

  function switchLoginMethod(next: LoginMethod) {
    setLoginMethod(next); setStep('mobile'); setOtp(''); setDevCode('');
    setError(''); setNotice('');
  }

  async function signInWithPassword() {
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await fetch('/api/v1/auth/parent-login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mobile: mobile.trim(), password }),
      });
      const body = await readApi(response);
      if (!response.ok) {
        const message = String(body.message || '');
        if (message.toLowerCase().includes('password has not been set')) {
          throw new Error('No password is set on this parent account yet. Choose “OTP se sign in karein” to enter your dashboard, or use “Password bhool gaye?” to create one.');
        }
        throw new Error(message || 'Unable to sign in. Check your mobile number and password.');
      }
      router.replace('/parent'); router.refresh();
    } catch (e: any) {
      setError(e?.message || 'Unable to sign in. Please try again.');
    } finally { setBusy(false); }
  }

  async function requestOtp() {
    setError(''); setNotice('');
    if (mode === 'register') {
      if (displayName.trim().length < 2) { setError('Enter your name.'); return; }
      if (password.length < 8) { setError('Choose a password with at least 8 characters.'); return; }
      if (password !== confirmPassword) { setError('The passwords do not match.'); return; }
    }
    setBusy(true);
    try {
      const purpose = mode === 'register' ? 'SIGNUP' : mode === 'reset' ? 'PASSWORD_RESET' : 'LOGIN';
      const response = await fetch('/api/v1/auth/request-otp', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mobile: mobile.trim(), purpose }),
      });
      const body = await readApi(response);
      if (!response.ok) throw new Error(body.message || 'Unable to send the verification code.');
      setDevCode(body.devCode || ''); setOtp(''); setStep('otp');
      setNotice(mode === 'register'
        ? tx('आपके mobile number पर OTP भेज दिया गया है।', 'An OTP has been sent to your mobile number.')
        : mode === 'reset'
          ? tx('Registered mobile number पर password reset OTP भेज दिया गया है।', 'A password reset OTP has been sent to your registered mobile number.')
          : tx('Registered mobile number पर login OTP भेज दिया गया है।', 'A login OTP has been sent to your registered mobile number.'));
    } catch (e: any) {
      setError(e?.message || 'Unable to send the verification code.');
    } finally { setBusy(false); }
  }

  async function verifyOtp() {
    setError(''); setNotice('');
    if (otp.trim().length !== 6) { setError('Enter the 6-digit OTP.'); return; }
    if (mode === 'reset') {
      if (newPassword.length < 8) { setError('Choose a password with at least 8 characters.'); return; }
      if (newPassword !== confirmNewPassword) { setError('The new passwords do not match.'); return; }
    }
    setBusy(true);
    try {
      const purpose = mode === 'register' ? 'SIGNUP' : mode === 'reset' ? 'PASSWORD_RESET' : 'LOGIN';
      const payload: Record<string, string> = { mobile: mobile.trim(), otp: otp.trim(), purpose };
      if (mode === 'register') {
        payload.displayName = displayName.trim();
        payload.password = password;
      } else if (mode === 'reset') {
        payload.password = newPassword;
      }
      const response = await fetch('/api/v1/auth/verify-otp', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload),
      });
      const body = await readApi(response);
      if (!response.ok) throw new Error(body.message || 'Verification failed. Please try again.');
      router.replace(mode === 'register' ? '/parent/children' : '/parent');
      router.refresh();
    } catch (e: any) {
      setError(e?.message || 'Verification failed. Please try again.');
    } finally { setBusy(false); }
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mode === 'login' && loginMethod === 'password') await signInWithPassword();
    else if (step === 'mobile') await requestOtp();
    else await verifyOtp();
  }

  const submitLabel = mode === 'login'
    ? loginMethod === 'password'
      ? (busy ? tx('Sign in हो रहा है…', 'Signing in…') : tx('Sign in करें', 'Sign in'))
      : busy
        ? (step === 'mobile' ? tx('Login OTP भेजा जा रहा है…', 'Sending login OTP…') : tx('Verify हो रहा है…', 'Verifying…'))
        : step === 'mobile' ? tx('Login OTP भेजें →', 'Send login OTP →') : tx('OTP verify करके sign in करें →', 'Verify OTP and sign in →')
    : mode === 'register'
      ? busy
        ? (step === 'mobile' ? tx('OTP भेजा जा रहा है…', 'Sending OTP…') : tx('Account बन रहा है…', 'Creating account…'))
        : step === 'mobile' ? tx('Mobile verify करें →', 'Verify mobile →') : tx('Parent account बनाएँ →', 'Create parent account →')
      : busy
        ? (step === 'mobile' ? tx('OTP भेजा जा रहा है…', 'Sending OTP…') : tx('Password reset हो रहा है…', 'Resetting password…'))
        : step === 'mobile' ? tx('Password reset OTP भेजें →', 'Send password reset OTP →') : tx('Password reset करें →', 'Reset password →');

  return (
    <main className="auth-page">
      <div className="qe-language-auth-float"><LanguageSwitcher /></div>
      <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
      <section className="auth-card parent-auth-card">
        <Link href="/" className="auth-back-link"><LocaleText hinglish="← QuantaEdge पर वापस" english="← Back to QuantaEdge" /></Link>
        <span className="eyebrow">{mode === 'login' ? <LocaleText hinglish="PARENT / GUARDIAN" english="PARENT / GUARDIAN" /> : mode === 'register' ? <LocaleText hinglish="FAMILY ACCOUNT बनाएँ" english="CREATE FAMILY ACCOUNT" /> : <LocaleText hinglish="ACCOUNT RECOVERY" english="ACCOUNT RECOVERY" />}</span>
        <h1>{mode === 'login' ? <LocaleText hinglish="वापस स्वागत है।" english="Welcome back." /> : mode === 'register' ? <LocaleText hinglish="आपकी family learning space।" english="Your family learning space." /> : <LocaleText hinglish="नया password बनाएँ।" english="Create a new password." />}</h1>
        <p>{mode === 'login'
          ? <LocaleText hinglish="अपने registered mobile number और password से sign in करें। Password set नहीं है तो OTP sign-in चुनें।" english="Sign in with your registered mobile number and password. If you haven't set a password, choose OTP sign-in." />
          : mode === 'register'
            ? <LocaleText hinglish="Mobile number verify करें और parent password बनाएँ। फिर एक ही dashboard से बच्चों के Profiles manage करें।" english="Verify your mobile number and create a parent password. Then manage all your children's profiles from one dashboard." />
            : <LocaleText hinglish="Registered mobile पर आया OTP verify करके नया password बनाएँ। आगे से इसी password से sign in कर सकते हैं।" english="Verify the OTP sent to your registered mobile number to create a new password." />}</p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        {notice && <div className="auth-message" role="status">{notice}</div>}

        <form className="auth-form" onSubmit={submit}>
          {mode === 'register' && <label><LocaleText hinglish="Parent / guardian का नाम" english="Parent / guardian name" />
            <input value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder={tx('अपना पूरा नाम लिखें', 'Enter your full name')} autoComplete="name" maxLength={120} required={step === 'mobile'} disabled={step === 'otp'} />
          </label>}
          <label><LocaleText hinglish="Registered mobile number" english="Registered mobile number" />
            <input value={mobile} onChange={e => setMobile(e.target.value.replace(/\D/g, '').slice(0, 10))} placeholder={tx('10 अंकों का mobile number डालें', 'Enter 10-digit mobile number')} inputMode="numeric" autoComplete="tel" required disabled={otpFlow && step === 'otp'} />
          </label>
          {mode === 'login' && loginMethod === 'password' && <label><LocaleText hinglish="Password" english="Password" />
            <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={tx('अपना password डालें', 'Enter your password')} autoComplete="current-password" required />
          </label>}
          {mode === 'register' && step === 'mobile' && <>
            <label><LocaleText hinglish="Password बनाएँ" english="Create password" />
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={tx('कम से कम 8 characters', 'At least 8 characters')} autoComplete="new-password" minLength={8} required />
            </label>
            <label><LocaleText hinglish="Password दोबारा डालें" english="Re-enter password" />
              <input type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)} placeholder={tx('वही password दोबारा डालें', 'Re-enter the same password')} autoComplete="new-password" minLength={8} required />
            </label>
          </>}
          {otpFlow && step === 'otp' && <>
            {devCode && <div className="auth-dev-code"><span><LocaleText hinglish="Local development OTP" english="Local development OTP" /></span><strong>{devCode}</strong></div>}
            <label><LocaleText hinglish="6-digit OTP" english="6-digit OTP" />
              <input value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))} placeholder={tx('OTP डालें', 'Enter OTP')} inputMode="numeric" autoComplete="one-time-code" required />
            </label>
            {mode === 'reset' && <>
              <label><LocaleText hinglish="नया password" english="New password" />
                <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)} placeholder="Kam se kam 8 characters" autoComplete="new-password" minLength={8} required />
              </label>
              <label><LocaleText hinglish="नया password दोबारा डालें" english="Re-enter new password" />
                <input type="password" value={confirmNewPassword} onChange={e => setConfirmNewPassword(e.target.value)} placeholder={tx('वही password दोबारा डालें', 'Re-enter the same password')} autoComplete="new-password" minLength={8} required />
              </label>
            </>}
          </>}
          <button type="submit" className="button button-dark full" disabled={busy || mobile.length !== 10 ||
            (mode === 'login' && loginMethod === 'password' && !password) ||
            (mode === 'register' && step === 'mobile' && (displayName.trim().length < 2 || password.length < 8 || password !== confirmPassword)) ||
            (otpFlow && step === 'otp' && otp.length !== 6) ||
            (mode === 'reset' && step === 'otp' && (newPassword.length < 8 || newPassword !== confirmNewPassword))}>
            {submitLabel}
          </button>
          {otpFlow && step === 'otp' && <button type="button" className="auth-secondary-action" onClick={() => { setStep('mobile'); setOtp(''); setDevCode(''); setError(''); setNotice(''); }} disabled={busy}><LocaleText hinglish="← Mobile number बदलें" english="← Change mobile number" /></button>}
        </form>

        <div className="auth-mode-links">
          {mode === 'login' ? <>
            <button type="button" onClick={() => resetState('reset')}><LocaleText hinglish="Password भूल गए?" english="Forgot password?" /></button>
            <button type="button" onClick={() => switchLoginMethod(loginMethod === 'password' ? 'otp' : 'password')}>
              {loginMethod === 'password' ? <LocaleText hinglish="OTP से sign in करें" english="Sign in with OTP" /> : <LocaleText hinglish="Password से sign in करें" english="Sign in with password" />}
            </button>
            <button type="button" onClick={() => resetState('register')}><LocaleText hinglish="Parent account बनाएँ" english="Create parent account" /></button>
          </> : <button type="button" onClick={() => resetState('login')}><LocaleText hinglish="Parent sign in पर वापस" english="Back to parent sign-in" /></button>}
        </div>
        <div className="auth-separator"><span><LocaleText hinglish="या" english="OR" /></span></div>
        <Link href="/login/student" className="auth-alt-link"><LocaleText hinglish="Student login" english="Student login" /> <span>→</span></Link>
        <small className="auth-note"><LocaleText hinglish="Registration और password reset के लिए OTP ज़रूरी है। Parent sign-in के लिए भी OTP का option available है।" english="OTP is required for registration and password reset. Parents can also sign in with an OTP." /></small>
      </section>
    </main>
  );
}
