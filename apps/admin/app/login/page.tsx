'use client';

import Link from 'next/link';
import {useRouter} from 'next/navigation';
import {useState} from 'react';

async function readApiJson(response: Response): Promise<any> {
  const body = await response.text();
  try {
    return body ? JSON.parse(body) : {};
  } catch {
    if (!response.ok) {
      throw new Error(`QuantaEdge server error (${response.status}). The API may be unavailable; please retry after the service is healthy.`);
    }
    throw new Error(`QuantaEdge returned an unexpected response (${response.status}). Please retry.`);
  }
}

export default function AdminLogin() {
  const router = useRouter();
  const [mobile, setMobile] = useState('');
  const [otp, setOtp] = useState('');
  const [step, setStep] = useState<1 | 2>(1);
  const [firstAccess, setFirstAccess] = useState(false);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [devCode, setDevCode] = useState('');

  async function requestOtp() {
    setError('');
    setBusy(true);
    try {
      const response = await fetch('/api/v1/auth/request-otp', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({mobile, purpose: 'STAFF_LOGIN'})
      });
      const body = await readApiJson(response);
      if (!response.ok) throw new Error(body.message || 'We could not send your sign-in code.');
      setFirstAccess(Boolean(body.firstAccess));
      setDevCode(body.devCode || '');
      setOtp('');
      setStep(2);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'We could not send your sign-in code.');
    } finally {
      setBusy(false);
    }
  }

  async function verify() {
    setError('');
    setBusy(true);
    try {
      const response = await fetch('/api/v1/auth/verify-otp', {
        method: 'POST',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({mobile, otp, purpose: 'STAFF_LOGIN', firstAccess})
      });
      const body = await readApiJson(response);
      if (!response.ok) throw new Error(body.message || 'The code could not be verified.');
      const meResponse = await fetch('/api/v1/auth/me', {cache: 'no-store'});
      const me = await readApiJson(meResponse);
      if (!meResponse.ok || !me.staffId) {
        throw new Error('This is not a staff session. Use learner sign-in on the learning site.');
      }

      const permissions: string[] = Array.isArray(me.permissions) ? me.permissions : [];
      if (me.role === 'ADMIN') router.replace('/');
      else if (permissions.includes('CONTENT_VIEW')) router.replace('/content');
      else if (permissions.includes('AUDIT_VIEW')) router.replace('/employees?view=audit');
      else throw new Error('Your staff account has no workspace permissions. Contact your administrator.');
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'The code could not be verified.');
    } finally {
      setBusy(false);
    }
  }

  function changeNumber() {
    setStep(1);
    setOtp('');
    setDevCode('');
    setError('');
  }

  const normalizedLength = mobile.replace(/\D/g, '').slice(-10).length;

  return (
    <main className="qe-login-shell">
      <aside className="qe-login-story">
        <Link href="/" className="qe-login-brand" aria-label="QuantaEdge staff console">
          <span className="qe-login-brand-mark">Q</span>
          <span><strong>QuantaEdge</strong><small>STAFF CONSOLE</small></span>
        </Link>

        <div className="qe-login-story-content">
          <span className="qe-login-eyebrow"><i /> WORKSPACE ACCESS</span>
          <h1>Great learning starts with <em>great people.</em></h1>
          <p>A secure workspace for the team building, reviewing and publishing trusted learning content.</p>
          <div className="qe-login-proof">
            <span className="qe-login-proof-icon">✓</span>
            <span><strong>Permission-based access</strong><small>Your role controls what you can see and change.</small></span>
          </div>
          <div className="qe-login-proof">
            <span className="qe-login-proof-icon">↗</span>
            <span><strong>Every important action is tracked</strong><small>Content and staff activity are recorded in the audit trail.</small></span>
          </div>
        </div>

        <div className="qe-login-story-footer">
          <span>QUANTAEDGE · LEARNING OPERATIONS</span>
          <span>Secure access for invited staff</span>
        </div>
        <div className="qe-login-decoration qe-login-decoration-one" />
        <div className="qe-login-decoration qe-login-decoration-two" />
      </aside>

      <section className="qe-login-panel">
        <div className="qe-login-mobile-brand">
          <span className="qe-login-brand-mark">Q</span><strong>QuantaEdge</strong>
        </div>
        <div className="qe-login-card">
          <div className="qe-login-card-top">
            <span className="qe-login-symbol">{step === 1 ? '↗' : firstAccess ? '✳' : '✓'}</span>
            <span className="qe-login-secure"><i /> SECURE STAFF ACCESS</span>
          </div>

          <div className="qe-login-heading">
            <span className="qe-login-overline">{step === 1 ? 'YOUR WORKSPACE AWAITS' : firstAccess ? 'FIRST-TIME ACCESS' : 'WELCOME BACK'}</span>
            <h2>{step === 1 ? 'Sign in to your team' : firstAccess ? 'Activate your invitation' : 'Verify it’s you'}</h2>
            <p>{step === 1
              ? 'Use the mobile number assigned to you by your QuantaEdge administrator.'
              : firstAccess
                ? 'Your administrator has created your staff account. Verify your assigned mobile to activate access.'
                : 'Enter the six-digit code sent to your assigned mobile number.'}</p>
          </div>

          {step === 1 ? (
            <form className="qe-login-form" onSubmit={event => {event.preventDefault(); if (!busy && normalizedLength === 10) void requestOtp();}}>
              <label htmlFor="staff-mobile">Staff mobile number</label>
              <div className="qe-login-input-wrap">
                <span className="qe-login-input-prefix">+91</span>
                <input
                  id="staff-mobile"
                  value={mobile}
                  onChange={event => setMobile(event.target.value.replace(/\D/g, '').slice(-10))}
                  inputMode="numeric"
                  autoComplete="tel-national"
                  placeholder="98765 43210"
                  maxLength={10}
                  required
                  aria-describedby="staff-mobile-help"
                />
              </div>
              <small id="staff-mobile-help" className="qe-login-field-help">Use the number registered by your administrator.</small>
              {error && <div className="qe-login-error" role="alert"><strong>We couldn’t continue</strong><span>{error}</span></div>}
              <button className="qe-login-primary" disabled={busy || normalizedLength !== 10} type="submit">
                {busy ? 'Checking account…' : 'Continue securely'} <span>→</span>
              </button>
              <div className="qe-login-activation-note">
                <span className="qe-login-note-icon">i</span>
                <p><strong>First time here?</strong> Staff accounts are created by an administrator. We’ll guide you through activation if this is your first sign-in.</p>
              </div>
            </form>
          ) : (
            <form className="qe-login-form" onSubmit={event => {event.preventDefault(); if (!busy && /^\d{6}$/.test(otp)) void verify();}}>
              <div className="qe-login-recipient">
                <span className="qe-login-recipient-icon">↗</span>
                <span><small>CODE REQUESTED FOR</small><strong>+91 {mobile.replace(/\D/g, '').slice(-10)}</strong></span>
                <button type="button" onClick={changeNumber} disabled={busy}>Change</button>
              </div>
              <label htmlFor="staff-otp">Six-digit verification code</label>
              <input
                className="qe-login-otp"
                id="staff-otp"
                value={otp}
                onChange={event => setOtp(event.target.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="000000"
                maxLength={6}
                autoFocus
                required
              />
              <small className="qe-login-field-help">{firstAccess ? 'Verify your assigned number to activate your invitation.' : 'Enter the code sent to your registered staff number.'}</small>
              {devCode && <div className="qe-login-dev-code"><span>Local development code</span><strong>{devCode}</strong><small>Displayed only when local demo OTP mode is enabled.</small></div>}
              {error && <div className="qe-login-error" role="alert"><strong>Verification didn’t complete</strong><span>{error}</span></div>}
              <button className="qe-login-primary" disabled={busy || !/^\d{6}$/.test(otp)} type="submit">
                {busy ? 'Verifying access…' : firstAccess ? 'Activate and open workspace' : 'Sign in to workspace'} <span>→</span>
              </button>
              <button className="qe-login-back" type="button" onClick={changeNumber} disabled={busy}>← Use a different mobile number</button>
            </form>
          )}

          <div className="qe-login-policy">
            <span>🔒</span><p>Access is tied to your staff identity and assigned role. Only an administrator can create staff accounts or change permissions.</p>
          </div>
        </div>

        <footer className="qe-login-panel-footer">
          <span>Need access? Contact your QuantaEdge administrator.</span>
          <span>mPay Learn · Staff operations</span>
        </footer>
      </section>
    </main>
  );
}
