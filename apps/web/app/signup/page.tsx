'use client';

import { FormEvent, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import GoogleSignInButton from '../components/GoogleSignInButton';
import { LanguageSwitcher, useLocale } from '../components/LanguageProvider';

async function readJson(response: Response): Promise<any> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.detail || body.error || 'The request could not be completed.');
  return body;
}

export default function SignupPage() {
  const router = useRouter();
  const { locale } = useLocale();
  const [method, setMethod] = useState<'choice' | 'mobile'>('choice');
  const [name, setName] = useState('');
  const [mobile, setMobile] = useState('');
  const [otp, setOtp] = useState('');
  const [devCode, setDevCode] = useState('');
  const [otpRequested, setOtpRequested] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const english = locale === 'english';

  async function requestOtp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(''); setNotice('');
    if (name.trim().length < 2) { setError(english ? 'Enter your full name.' : 'अपना पूरा नाम दर्ज करें।'); return; }
    if (mobile.length !== 10) { setError(english ? 'Enter a valid 10-digit mobile number.' : 'मान्य 10 अंकों का मोबाइल नंबर दर्ज करें।'); return; }
    setBusy(true);
    try {
      const body = await readJson(await fetch('/api/v1/auth/account-signup/request-otp', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mobile }),
      }));
      setDevCode(String(body.devCode || ''));
      setOtp('');
      setOtpRequested(true);
      setNotice(english ? 'Enter the verification code sent to your mobile.' : 'मोबाइल पर आए सत्यापन कोड को दर्ज करें।');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not request the verification code.');
    } finally { setBusy(false); }
  }

  async function verifyOtp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(''); setNotice('');
    if (otp.length !== 6) { setError(english ? 'Enter the 6-digit OTP.' : '6 अंकों का OTP दर्ज करें।'); return; }
    setBusy(true);
    try {
      const body = await readJson(await fetch('/api/v1/auth/account-signup/verify-otp', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ mobile, otp, displayName: name.trim() }),
      }));
      if (body.workspace !== 'STUDENT' || Number(body.studentId) <= 0) {
        throw new Error(english ? 'The learner session could not be confirmed.' : 'Learner session की पुष्टि नहीं हो सकी।');
      }
      router.replace('/student/profile?welcome=1');
      router.refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Mobile verification failed.');
    } finally { setBusy(false); }
  }

  return (
    <main className="auth-page">
      <div className="qe-language-auth-float"><LanguageSwitcher /></div>
      <div className="auth-brand"><QuantaEdgeBrand variant="auth" /></div>
      <section className="auth-card parent-auth-card" aria-labelledby="signup-heading">
        <Link href="/" className="auth-back-link">{english ? 'Back to QuantaEdge' : 'QuantaEdge पर वापस जाएँ'}</Link>
        <span className="eyebrow">{english ? 'YOUR LEARNING STARTS HERE' : 'आपकी सीखने की शुरुआत'}</span>
        <h1 id="signup-heading">{english ? 'Create your account' : 'अपना खाता बनाएँ'}</h1>
        <p>{english ? 'Use a verified Gmail account, or verify your mobile number with a one-time code.' : 'सत्यापित Gmail खाते का उपयोग करें या OTP से मोबाइल नंबर सत्यापित करें।'}</p>
        {error && <div className="auth-message is-error" role="alert">{error}</div>}
        {notice && <div className="auth-message" role="status">{notice}</div>}
        <GoogleSignInButton locale={english ? 'en' : 'hi'} context="signup" />
        <div className="auth-separator"><span>{english ? 'OR SIGN UP WITH MOBILE' : 'या मोबाइल से साइन अप करें'}</span></div>
        {method === 'choice' && (
          <button type="button" className="button button-light full" onClick={() => { setMethod('mobile'); setError(''); setNotice(''); }}>
            {english ? 'Continue with mobile number' : 'मोबाइल नंबर से आगे बढ़ें'}
          </button>
        )}
        {method === 'mobile' && (
          <form className="auth-form" onSubmit={otpRequested ? verifyOtp : requestOtp}>
            {!otpRequested && <>
              <label>{english ? 'Full name' : 'पूरा नाम'}
                <input value={name} onChange={e => setName(e.target.value)} maxLength={120} autoComplete="name" required />
              </label>
              <label>{english ? 'Mobile number' : 'मोबाइल नंबर'}
                <input value={mobile} onChange={e => setMobile(e.target.value.replace(/\D/g, '').slice(0, 10))}
                  placeholder={english ? '10-digit mobile number' : '10 अंकों का मोबाइल नंबर'}
                  inputMode="numeric" autoComplete="tel-national" required />
              </label>
            </>}
            {otpRequested && <>
              <p className="auth-note">{english ? 'Verifying mobile: ' : 'सत्यापित किया जा रहा है: '}+91 {mobile}</p>
              {devCode && <div className="auth-dev-code"><span>{english ? 'Development OTP' : 'डेवलपमेंट OTP'}</span><strong>{devCode}</strong></div>}
              <label>{english ? '6-digit verification code' : '6 अंकों का सत्यापन कोड'}
                <input value={otp} onChange={e => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
                  inputMode="numeric" autoComplete="one-time-code" maxLength={6} required />
              </label>
            </>}
            <button className="button button-dark full" type="submit" disabled={busy || (!otpRequested && (name.trim().length < 2 || mobile.length !== 10)) || (otpRequested && otp.length !== 6)}>
              {busy
                ? (english ? 'Please wait…' : 'कृपया प्रतीक्षा करें…')
                : otpRequested
                  ? (english ? 'Verify and create account' : 'सत्यापित करें और खाता बनाएँ')
                  : (english ? 'Send verification code' : 'सत्यापन कोड भेजें')}
            </button>
            <button type="button" className="auth-secondary-action" disabled={busy} onClick={() => {
              setMethod('choice'); setOtpRequested(false); setOtp(''); setDevCode(''); setError(''); setNotice('');
            }}>{english ? 'Use another method' : 'दूसरा तरीका चुनें'}</button>
          </form>
        )}
        <p className="auth-note">{english
          ? 'After signup, complete your profile to set a username and password or request parent-account eligibility. Gmail is shown as verified only after Google verifies it; mobile is shown as verified only after OTP succeeds.'
          : 'साइन अप के बाद प्रोफ़ाइल में यूज़रनेम और पासवर्ड सेट करें। Google की पुष्टि के बाद ही Gmail और सफल OTP के बाद ही मोबाइल सत्यापित दिखेगा।'}</p>
        <div className="auth-mode-links"><span>{english ? 'Already have an account?' : 'पहले से खाता है?'}</span><Link href="/login">{english ? 'Sign in' : 'साइन इन करें'}</Link></div>
      </section>
    </main>
  );
}
