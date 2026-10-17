'use client';

import { UiText, useUiTranslation } from '../i18n/useUiTranslation';
import Link from 'next/link';
import { FormEvent, useState } from 'react';
import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import { LanguageSwitcher } from '../components/LanguageProvider';

type Mode = 'login' | 'register' | 'reset';
type Step = 'mobile' | 'otp';
type LoginMethod = 'password' | 'otp';

async function readApi(response: Response, unexpectedResponse: (status: number) => string): Promise<any> {
  const raw = await response.text();
  if (!raw.trim()) return {};
  try { return JSON.parse(raw); }
  catch { throw new Error(unexpectedResponse(response.status)); }
}

export default function ParentLoginPage() {
  const t = useUiTranslation();
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
      const body = await readApi(response, status => t('parent.login.unexpectedResponse', { status }));
      if (!response.ok) {
        const message = String(body.message || '');
        if (message.toLowerCase().includes('password has not been set')) {
          throw new Error(t('parent.login.passwordNotSet'));
        }
        throw new Error(message || t('parent.login.invalidCredentials'));
      }
      if (body.role !== 'PARENT' || body.workspace !== 'PARENT' || Number(body.userId) <= 0 || Number(body.studentId) > 0) {
        throw new Error(t('parent.login.sessionNotConfirmed'));
      }
      window.location.replace('/parent');
    } catch (e: any) {
      setError(e?.message || t('parent.login.signInFailed'));
    } finally { setBusy(false); }
  }

  async function requestOtp() {
    setError(''); setNotice('');
    if (mode === 'register') {
      if (displayName.trim().length < 2) { setError(t('parent.login.enterName')); return; }
      if (password.length < 8) { setError(t('parent.login.passwordMinimum')); return; }
      if (password !== confirmPassword) { setError(t('parent.login.passwordMismatch')); return; }
    }
    setBusy(true);
    try {
      const purpose = mode === 'register' ? 'SIGNUP' : mode === 'reset' ? 'PASSWORD_RESET' : 'LOGIN';
      const response = await fetch('/api/v1/auth/request-otp', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mobile: mobile.trim(), purpose }),
      });
      const body = await readApi(response, status => t('parent.login.unexpectedResponse', { status }));
      if (!response.ok) throw new Error(body.message || t('parent.login.sendVerificationFailed'));
      setDevCode(body.devCode || ''); setOtp(''); setStep('otp');
      setNotice(mode === 'register'
        ? t('parent.login.otpSentSignup')
        : mode === 'reset'
          ? t('parent.login.otpSentReset')
          : t('parent.login.otpSentLogin'));
    } catch (e: any) {
      setError(e?.message || t('parent.login.sendVerificationFailed'));
    } finally { setBusy(false); }
  }

  async function verifyOtp() {
    setError(''); setNotice('');
    if (otp.trim().length !== 6) { setError(t('parent.login.invalidOtp')); return; }
    if (mode === 'reset') {
      if (newPassword.length < 8) { setError(t('parent.login.passwordMinimum')); return; }
      if (newPassword !== confirmNewPassword) { setError(t('parent.login.newPasswordMismatch')); return; }
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
      const body = await readApi(response, status => t('parent.login.unexpectedResponse', { status }));
      if (!response.ok) throw new Error(body.message || t('parent.login.verificationFailed'));
      if (body.role !== 'PARENT' || body.workspace !== 'PARENT' || Number(body.userId) <= 0 || Number(body.studentId) > 0) {
        throw new Error(t('parent.login.sessionNotConfirmed'));
      }
      window.location.replace(mode === 'register' ? '/parent/children' : '/parent');
    } catch (e: any) {
      setError(e?.message || t('parent.login.verificationFailed'));
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
      ? (busy ? t('parent.login.signingIn') : t('parent.login.signIn'))
      : busy
        ? (step === 'mobile' ? t('parent.login.sendingLoginOtp') : t('parent.login.verifying'))
        : step === 'mobile' ? t('parent.login.sendLoginOtp') : t('parent.login.verifyOtpAndSignIn')
    : mode === 'register'
      ? busy
        ? (step === 'mobile' ? t('parent.login.sendingOtp') : t('parent.login.creatingAccount'))
        : step === 'mobile' ? t('parent.login.verifyMobile') : t('parent.login.createAccount')
      : busy
        ? (step === 'mobile' ? t('parent.login.sendingOtp') : t('parent.login.resettingPassword'))
        : step === 'mobile' ? t('parent.login.sendResetOtp') : t('parent.login.resetPassword');

  return (
    <main className="auth-page">
      <div className="qe-language-auth-float"><LanguageSwitcher /></div>
      <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
      <section className="auth-card parent-auth-card">
        <Link href="/" className="auth-back-link"><UiText message="legacy.web.back.to.quantaedge.afca99d4" /></Link>
        <span className="eyebrow">{mode === 'login' ? <UiText message="legacy.web.parent.guardian.70a181bd" /> : mode === 'register' ? <UiText message="legacy.web.create.family.account.48f9b3ad" /> : <UiText message="legacy.web.account.recovery.1b09ce51" />}</span>
        <h1>{mode === 'login' ? <UiText message="legacy.web.welcome.back.ab75bbfd" /> : mode === 'register' ? <UiText message="legacy.web.your.family.learning.space.6fdbe315" /> : <UiText message="legacy.web.create.a.new.password.582ed665" />}</h1>
        <p>{mode === 'login'
          ? <UiText message="legacy.web.sign.in.with.your.registered.mobile.nu.2cd44b58" />
          : mode === 'register'
            ? <UiText message="legacy.web.verify.your.mobile.number.and.create.a.8707e1ec" />
            : <UiText message="legacy.web.verify.the.otp.sent.to.your.registered.573234e4" />}</p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        {notice && <div className="auth-message" role="status">{notice}</div>}

        <form className="auth-form" onSubmit={submit}>
          {mode === 'register' && <label><UiText message="legacy.web.parent.guardian.name.1ab0447b" />
            <input value={displayName} onChange={e => setDisplayName(e.target.value)} placeholder={t('parent.login.fullNamePlaceholder')} autoComplete="name" maxLength={120} required={step === 'mobile'} disabled={step === 'otp'} />
          </label>}
          <label><UiText message="legacy.web.registered.mobile.number.65c4e773" />
            <input value={mobile} onChange={e => setMobile(e.target.value.replace(/\D/g, '').slice(0, 10))} placeholder={t('parent.login.mobilePlaceholder')} inputMode="numeric" autoComplete="tel" required disabled={otpFlow && step === 'otp'} />
          </label>
          {mode === 'login' && loginMethod === 'password' && <label><UiText message="legacy.web.password.dc78b30b" />
            <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={t('parent.login.passwordPlaceholder')} autoComplete="current-password" required />
          </label>}
          {mode === 'register' && step === 'mobile' && <>
            <label><UiText message="legacy.web.create.password.b06eaef9" />
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} placeholder={t('parent.login.passwordMinimumPlaceholder')} autoComplete="new-password" minLength={8} required />
            </label>
            <label><UiText message="legacy.web.re.enter.password.541a6b9c" />
              <input type="password" value={confirmPassword} onChange={e => setConfirmPassword(e.target.value)} placeholder={t('parent.login.confirmPasswordPlaceholder')} autoComplete="new-password" minLength={8} required />
            </label>
          </>}
          {otpFlow && step === 'otp' && <>
            {devCode && <div className="auth-dev-code"><span><UiText message="legacy.web.local.development.otp.51756e69" /></span><strong>{devCode}</strong></div>}
            <label><UiText message="legacy.web.6.digit.otp.7a404f31" />
              <input value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))} placeholder={t('parent.login.otpPlaceholder')} inputMode="numeric" autoComplete="one-time-code" required />
            </label>
            {mode === 'reset' && <>
              <label><UiText message="legacy.web.new.password.32a2f216" />
                <input type="password" value={newPassword} onChange={e => setNewPassword(e.target.value)} placeholder={t('parent.login.passwordMinimumPlaceholder')} autoComplete="new-password" minLength={8} required />
              </label>
              <label><UiText message="legacy.web.re.enter.new.password.37b05353" />
                <input type="password" value={confirmNewPassword} onChange={e => setConfirmNewPassword(e.target.value)} placeholder={t('parent.login.confirmPasswordPlaceholder')} autoComplete="new-password" minLength={8} required />
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
          {otpFlow && step === 'otp' && <button type="button" className="auth-secondary-action" onClick={() => { setStep('mobile'); setOtp(''); setDevCode(''); setError(''); setNotice(''); }} disabled={busy}><UiText message="legacy.web.change.mobile.number.a4e4ee0c" /></button>}
        </form>

        <div className="auth-mode-links">
          {mode === 'login' ? <>
            <button type="button" onClick={() => resetState('reset')}><UiText message="legacy.web.forgot.password.0815b893" /></button>
            <button type="button" onClick={() => switchLoginMethod(loginMethod === 'password' ? 'otp' : 'password')}>
              {loginMethod === 'password' ? <UiText message="legacy.web.sign.in.with.otp.8d79c836" /> : <UiText message="legacy.web.sign.in.with.password.55ae7710" />}
            </button>
            <button type="button" onClick={() => resetState('register')}><UiText message="legacy.web.create.parent.account.ee2209e1" /></button>
          </> : <button type="button" onClick={() => resetState('login')}><UiText message="legacy.web.back.to.parent.sign.in.0cfae1e3" /></button>}
        </div>
        <div className="auth-separator"><span><UiText message="legacy.web.or.5dca2d83" /></span></div>
        <Link href="/login/student" className="auth-alt-link"><UiText message="legacy.web.student.login.9541c2d5" /> <span>→</span></Link>
        <small className="auth-note"><UiText message="legacy.web.otp.is.required.for.registration.and.p.2af964c6" /></small>
      </section>
    </main>
  );
}
