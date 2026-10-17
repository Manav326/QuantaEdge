'use client';

import Link from 'next/link';
import {useRouter} from 'next/navigation';
import {useContext, useState} from 'react';
import { AdminWorkspaceContext, type AdminLocale } from '../components/AdminWorkspaceContext';
import { useUiTranslation } from '../i18n/useUiTranslation';
import { UI_LOCALE_OPTIONS } from '../i18n/catalog';
import QuantaEdgeBrand from '../components/QuantaEdgeBrand';

async function readApiJson(response: Response, t: (key: string, values?: Record<string, string | number>) => string): Promise<any> {
  const body = await response.text();
  try {
    return body ? JSON.parse(body) : {};
  } catch {
    if (!response.ok) {
      throw new Error(t('admin.login.serverUnavailable', { status: response.status }));
    }
    throw new Error(t('admin.login.unexpectedResponse', { status: response.status }));
  }
}

export default function AdminLogin() {
  const router = useRouter();
  const workspace = useContext(AdminWorkspaceContext);
  const t = useUiTranslation();
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
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({mobile, purpose: 'STAFF_LOGIN'})
      });
      const body = await readApiJson(response, t);
      if (!response.ok) throw new Error(body.message || body.detail || body.error || t('admin.login.couldNotSendCode'));
      setFirstAccess(Boolean(body.firstAccess));
      setDevCode(body.devCode || '');
      setOtp('');
      setStep(2);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t('admin.login.couldNotSendCode'));
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
        credentials: 'same-origin',
        cache: 'no-store',
        headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({mobile, otp, purpose: 'STAFF_LOGIN', firstAccess})
      });
      const body = await readApiJson(response, t);
      if (!response.ok) throw new Error(body.message || body.detail || body.error || t('admin.login.couldNotVerifyCode'));

      // The successful verification response already includes the authenticated staff
      // identity and sets both HttpOnly cookies. Avoid an unnecessary immediate /me
      // round-trip for administrators; the protected workspace boundary validates the
      // cookie normally after navigation.
      if (Number(body.staffId) > 0 && body.role === 'ADMIN') {
        router.replace('/');
        router.refresh();
        return;
      }

      if (!(Number(body.staffId) > 0)) {
        throw new Error(t('admin.login.otpNoSession'));
      }
      const meResponse = await fetch('/api/v1/auth/me', {cache: 'no-store', credentials: 'same-origin'});
      const me = await readApiJson(meResponse, t);
      if (!meResponse.ok || !me.staffId) {
        throw new Error(me.message || me.detail || t('admin.login.sessionNotConfirmed'));
      }

      const permissions: string[] = Array.isArray(me.permissions) ? me.permissions : [];
      if (me.role === 'ADMIN') router.replace('/');
      else if (permissions.includes('CONTENT_VIEW')) router.replace('/content');
      else if (permissions.includes('AUDIT_VIEW')) router.replace('/employees?view=audit');
      else throw new Error(t('admin.login.noWorkspacePermissions'));
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : t('admin.login.couldNotVerifyCode'));
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
        <div className="qe-admin-login-brand">
          <QuantaEdgeBrand href="/" variant="auth" className="qe-admin-login-brand-lockup" />
          <small className="qe-login-brand-context">{t('admin.login.staffConsole')}</small>
        </div>

        <div className="qe-login-story-content">
          <span className="qe-login-eyebrow"><i /> {t('admin.login.workspaceAccess')}</span>
          <h1>{t('admin.login.heroHeadlineBefore')} <em>{t('admin.login.heroHeadlineEmphasis')}</em></h1>
          <p>{t('admin.login.heroDescription')}</p>
          <div className="qe-login-proof">
            <span className="qe-login-proof-icon">✓</span>
            <span><strong>{t('admin.login.permissionBasedAccess')}</strong><small>{t('admin.login.permissionDescription')}</small></span>
          </div>
          <div className="qe-login-proof">
            <span className="qe-login-proof-icon">↗</span>
            <span><strong>{t('admin.login.actionTracked')}</strong><small>{t('admin.login.auditDescription')}</small></span>
          </div>
        </div>

        <div className="qe-login-story-footer">
          <span>{t('admin.login.footerOperations')}</span>
          <span>{t('admin.login.invitedStaff')}</span>
        </div>
        <div className="qe-login-decoration qe-login-decoration-one" />
        <div className="qe-login-decoration qe-login-decoration-two" />
      </aside>

      <section className="qe-login-panel">
        <div className="qe-login-mobile-brand">
          <QuantaEdgeBrand href="/" variant="compact" className="qe-admin-login-brand-lockup" />
        </div>
        <div className="qe-login-card">
          <label className="qe-admin-language-switcher qe-admin-login-language-switcher">
          <span aria-hidden="true">文</span>
          <select value={workspace.locale} onChange={event => workspace.setLocale(event.target.value as AdminLocale)} aria-label={t('admin.language.choose')}>
            {UI_LOCALE_OPTIONS.map(option => <option key={option.id} value={option.id}>{option.nativeLabel}</option>)}
          </select>
        </label>
        <div className="qe-login-card-top">
            <span className="qe-login-symbol">{step === 1 ? '↗' : firstAccess ? '✳' : '✓'}</span>
            <span className="qe-login-secure"><i /> {t('admin.login.secureStaffAccess')}</span>
          </div>

          <div className="qe-login-heading">
            <span className="qe-login-overline">{step === 1 ? t('admin.login.workspaceAwaits') : firstAccess ? t('admin.login.firstTimeAccess') : t('admin.login.welcomeBack')}</span>
            <h2>{step === 1 ? t('admin.login.signInTeam') : firstAccess ? t('admin.login.activateInvitation') : t('admin.login.verifyIdentity')}</h2>
            <p>{step === 1
              ? t('admin.login.mobileStepInstruction')
              : firstAccess
                ? t('admin.login.firstAccessInstruction')
                : t('admin.login.otpStepInstruction')}</p>
          </div>

          {step === 1 ? (
            <form className="qe-login-form" onSubmit={event => {event.preventDefault(); if (!busy && normalizedLength === 10) void requestOtp();}}>
              <label htmlFor="staff-mobile">{t('admin.login.mobileLabel')}</label>
              <div className="qe-login-input-wrap">
                <span className="qe-login-input-prefix">+91</span>
                <input
                  id="staff-mobile"
                  value={mobile}
                  onChange={event => setMobile(event.target.value.replace(/\D/g, '').slice(-10))}
                  inputMode="numeric"
                  autoComplete="tel-national"
                  placeholder={t("admin.login.mobilePlaceholder")}
                  maxLength={10}
                  required
                  aria-describedby="staff-mobile-help"
                />
              </div>
              <small id="staff-mobile-help" className="qe-login-field-help">{t("admin.login.mobileHelp")}</small>
              {error && <div className="qe-login-error" role="alert"><strong>{t("admin.login.errorContinue")}</strong><span>{error}</span></div>}
              <button className="qe-login-primary" disabled={busy || normalizedLength !== 10} type="submit">
                {busy ? t('admin.login.checkingAccount') : t('admin.login.continueSecurely')} <span>→</span>
              </button>
              <div className="qe-login-activation-note">
                <span className="qe-login-note-icon">i</span>
                <p><strong>{t('admin.login.firstTimeHere')}</strong> {t('admin.login.activationNote')}</p>
              </div>
            </form>
          ) : (
            <form className="qe-login-form" onSubmit={event => {event.preventDefault(); if (!busy && /^\d{6}$/.test(otp)) void verify();}}>
              <div className="qe-login-recipient">
                <span className="qe-login-recipient-icon">↗</span>
                <span><small>{t('admin.login.codeRequestedFor')}</small><strong>+91 {mobile.replace(/\D/g, '').slice(-10)}</strong></span>
                <button type="button" onClick={changeNumber} disabled={busy}>{t('admin.login.change')}</button>
              </div>
              <label htmlFor="staff-otp">{t("admin.login.otpLabel")}</label>
              <input
                className="qe-login-otp"
                id="staff-otp"
                value={otp}
                onChange={event => setOtp(event.target.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder={t("admin.login.otpPlaceholder")}
                maxLength={6}
                autoFocus
                required
              />
              <small className="qe-login-field-help">{firstAccess ? t('admin.login.firstAccessOtpHelp') : t('admin.login.registeredOtpHelp')}</small>
              {devCode && <div className="qe-login-dev-code"><span>{t("admin.login.localDevCode")}</span><strong>{devCode}</strong><small>{t("admin.login.devCodeNote")}</small></div>}
              {error && <div className="qe-login-error" role="alert"><strong>{t("admin.login.verificationIncomplete")}</strong><span>{error}</span></div>}
              <button className="qe-login-primary" disabled={busy || !/^\d{6}$/.test(otp)} type="submit">
                {busy ? t('admin.login.verifyingAccess') : firstAccess ? t('admin.login.activateAndOpen') : t('admin.login.signInWorkspace')} <span>→</span>
              </button>
              <button className="qe-login-back" type="button" onClick={changeNumber} disabled={busy}>{t('admin.login.useDifferentMobile')}</button>
            </form>
          )}

          <div className="qe-login-policy">
            <span>🔒</span><p>{t('admin.login.accessPolicy')}</p>
          </div>
        </div>

        <footer className="qe-login-panel-footer">
          <span>{t('admin.login.needAccess')}</span>
          <span>{t('admin.login.staffOperations')}</span>
        </footer>
      </section>
    </main>
  );
}
