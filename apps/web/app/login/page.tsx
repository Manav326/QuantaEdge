'use client';

import Link from 'next/link';
import { FormEvent, useState } from 'react';
import { useRouter } from 'next/navigation';
import QuantaEdgeBrand from '../components/QuantaEdgeBrand';

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
          throw new Error('No password is set on this parent account yet. Choose “Sign in with OTP” to enter your dashboard, or use “Forgot password?” to create one.');
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
        ? 'A verification code has been sent to your mobile number.'
        : mode === 'reset'
          ? 'A password reset code has been sent to your registered mobile number.'
          : 'A login code has been sent to your registered mobile number.');
    } catch (e: any) {
      setError(e?.message || 'Unable to send the verification code.');
    } finally { setBusy(false); }
  }

  async function verifyOtp() {
    setError(''); setNotice('');
    if (otp.trim().length !== 6) { setError('Enter the 6-digit verification code.'); return; }
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
      ? (busy ? 'Signing in…' : 'Sign in')
      : (busy ? (step === 'mobile' ? 'Sending login code…' : 'Verifying…') : (step === 'mobile' ? 'Send login OTP →' : 'Verify OTP and sign in →'))
    : mode === 'register'
      ? (busy ? (step === 'mobile' ? 'Sending code…' : 'Creating account…') : (step === 'mobile' ? 'Verify mobile number →' : 'Create parent account →'))
      : (busy ? (step === 'mobile' ? 'Sending code…' : 'Resetting password…') : (step === 'mobile' ? 'Send password reset code →' : 'Reset password →'));

  return (
    <main className="auth-page">
      <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
      <section className="auth-card parent-auth-card">
        <Link href="/" className="auth-back-link">← Back to QuantaEdge</Link>
        <span className="eyebrow">{mode === 'login' ? 'PARENT / GUARDIAN' : mode === 'register' ? 'CREATE FAMILY ACCOUNT' : 'ACCOUNT RECOVERY'}</span>
        <h1>{mode === 'login' ? 'Welcome back.' : mode === 'register' ? 'Your family learning space.' : 'Create a new password.'}</h1>
        <p>{mode === 'login'
          ? 'Sign in with your registered mobile number and password, or choose OTP sign-in if you have not set a password yet.'
          : mode === 'register'
            ? 'Verify your mobile number once and create your parent password. Then add and manage your children from one dashboard.'
            : 'We’ll verify your registered mobile with a one-time code. You can use your new password for future sign-ins.'}</p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        {notice && <div className="auth-message" role="status">{notice}</div>}

        <form className="auth-form" onSubmit={submit}>
          {mode === 'register' && <label>Parent / guardian name
            <input value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder="Enter your full name" autoComplete="name" maxLength={120} required={step === 'mobile'} disabled={step === 'otp'} />
          </label>}
          <label>Registered mobile number
            <input value={mobile} onChange={e => setMobile(e.target.value.replace(/\D/g, '').slice(0, 10))} placeholder="10-digit mobile number" inputMode="numeric" autoComplete="tel" required disabled={otpFlow && step === 'otp'} />
          </label>
          {mode === 'login' && loginMethod === 'password' && <label>Password
            <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="Enter your password" autoComplete="current-password" required />
          </label>}
          {mode === 'register' && step === 'mobile' && <>
            <label>Create password
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder="At least 8 characters" autoComplete="new-password" minLength={8} required />
            </label>
            <label>Confirm password
              <input type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)} placeholder="Enter the same password again" autoComplete="new-password" minLength={8} required />
            </label>
          </>}
          {otpFlow && step === 'otp' && <>
            {devCode && <div className="auth-dev-code"><span>Local development OTP</span><strong>{devCode}</strong></div>}
            <label>6-digit verification code
              <input value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))} placeholder="Enter OTP" inputMode="numeric" autoComplete="one-time-code" required />
            </label>
            {mode === 'reset' && <>
              <label>New password
                <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)} placeholder="At least 8 characters" autoComplete="new-password" minLength={8} required />
              </label>
              <label>Confirm new password
                <input type="password" value={confirmNewPassword} onChange={e => setConfirmNewPassword(e.target.value)} placeholder="Enter the same password again" autoComplete="new-password" minLength={8} required />
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
          {otpFlow && step === 'otp' && <button type="button" className="auth-secondary-action" onClick={() => { setStep('mobile'); setOtp(''); setDevCode(''); setError(''); setNotice(''); }} disabled={busy}>← Change mobile number</button>}
        </form>

        <div className="auth-mode-links">
          {mode === 'login' ? <>
            <button type="button" onClick={() => resetState('reset')}>Forgot password?</button>
            <button type="button" onClick={() => switchLoginMethod(loginMethod === 'password' ? 'otp' : 'password')}>
              {loginMethod === 'password' ? 'Sign in with OTP' : 'Sign in with password'}
            </button>
            <button type="button" onClick={() => resetState('register')}>Create parent account</button>
          </> : <button type="button" onClick={() => resetState('login')}>Back to parent sign in</button>}
        </div>
        <div className="auth-separator"><span>OR</span></div>
        <Link href="/login/student" className="auth-alt-link">Student login <span>→</span></Link>
        <small className="auth-note">OTP is required for registration and password recovery, and is also available as an alternative for parent sign-in.</small>
      </section>
    </main>
  );
}
