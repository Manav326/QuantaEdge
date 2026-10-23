'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { apiFetch } from './apiClient';
import GoogleSignInButton from './GoogleSignInButton';
import { useLocale } from './LanguageProvider';
import { translateUi } from '../i18n/catalog';

type DeletionRequest = {
  hasRequest: boolean;
  requestId?: string;
  status?: string;
  linkedChildrenCount?: number;
  includesLinkedChildren?: boolean;
  requestedAt?: string;
  updatedAt?: string;
};

async function responseBody(response: Response): Promise<any> {
  return response.json().catch(() => ({}));
}

export default function AccountDeletionPanel({ compact = false }: { compact?: boolean }) {
  const { locale } = useLocale();
  const [request, setRequest] = useState<DeletionRequest | null>(null);
  const [signedIn, setSignedIn] = useState<boolean | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const [childrenCount, setChildrenCount] = useState(0);
  const [reasonCategory, setReasonCategory] = useState('');
  const [reasonDetails, setReasonDetails] = useState('');
  const [mobileVerified, setMobileVerified] = useState(false);
  const [gmailVerified, setGmailVerified] = useState(false);
  const [mobileLastFour, setMobileLastFour] = useState('');
  const [otp, setOtp] = useState('');
  const [otpRequested, setOtpRequested] = useState(false);
  const [verificationId, setVerificationId] = useState('');
  const [manualIdentityReview, setManualIdentityReview] = useState(false);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const t = useCallback((key: string, values?: Record<string, string | number>) =>
    translateUi(locale, key, values), [locale]);

  const load = useCallback(async () => {
    setBusy('load');
    setError('');
    try {
      const [response, profileResponse] = await Promise.all([
        apiFetch('/api/v1/account/me/deletion-request', {
          credentials: 'same-origin', cache: 'no-store',
        }),
        apiFetch('/api/v1/account/me/profile', {
          credentials: 'same-origin', cache: 'no-store',
        }),
      ]);
      const body = await responseBody(response);
      const profile = await responseBody(profileResponse);
      if (profileResponse.ok) {
        setMobileVerified(Boolean(profile.mobileVerified));
        setGmailVerified(Boolean(profile.gmailVerified));
        const mobile = String(profile.mobile || '');
        setMobileLastFour(mobile.replace(/\\D/g, '').slice(-4));
      } else {
        setMobileVerified(false);
        setGmailVerified(false);
        setMobileLastFour('');
      }
      if (!response.ok) {
        const message = String(body.message || body.detail || body.error || '');
        if (response.status === 401 || message === 'Authentication required') {
          setSignedIn(false);
          setRequest(null);
          return;
        }
        setSignedIn(true);
        throw new Error(message || t('accountDeletion.error'));
      }
      setSignedIn(true);
      setRequest(body);
      setChildrenCount(Number(body.linkedChildrenCount || 0));
    } catch (e) {
      setError(e instanceof Error ? e.message : t('accountDeletion.error'));
    } finally {
      setBusy('');
    }
  }, [t]);

  useEffect(() => { void load(); }, [load]);

  async function requestOtp() {
    setBusy('otp-request');
    setError('');
    setNotice('');
    try {
      const response = await apiFetch('/api/v1/account/me/deletion-request/otp', {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
      });
      const body = await responseBody(response);
      if (!response.ok) throw new Error(body.message || body.detail || body.error || t('accountDeletion.verifyIdentityFailed'));
      setOtpRequested(true);
      setNotice(t('accountDeletion.otpSent', { lastFour: String(body.mobileLastFour || mobileLastFour) }));
      if (body.devCode) setOtp(String(body.devCode));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t('accountDeletion.verifyIdentityFailed'));
    } finally {
      setBusy('');
    }
  }

  async function verifyOtp() {
    if (!/^[0-9]{6}$/.test(otp.trim())) {
      setError(t('accountDeletion.otpSixDigits'));
      return;
    }
    setBusy('otp-verify');
    setError('');
    setNotice('');
    try {
      const response = await apiFetch('/api/v1/account/me/deletion-request/verify-otp', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ otp: otp.trim() }),
      });
      const body = await responseBody(response);
      if (!response.ok || !body.verificationId) {
        throw new Error(body.message || body.detail || body.error || t('accountDeletion.verifyIdentityFailed'));
      }
      setVerificationId(String(body.verificationId));
      setOtp('');
      setNotice(t('accountDeletion.identityVerified'));
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t('accountDeletion.verifyIdentityFailed'));
    } finally {
      setBusy('');
    }
  }

  const onGoogleVerified = useCallback((result: Record<string, unknown>) => {
    const id = String(result.verificationId || '').trim();
    if (!id) {
      setError(t('accountDeletion.verifyIdentityFailed'));
      return;
    }
    setVerificationId(id);
    setOtp('');
    setNotice(t('accountDeletion.identityVerified'));
    setError('');
  }, [t]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setNotice('');
    if (confirmation.trim() !== 'DELETE') {
      setError(t('accountDeletion.confirmationMismatch'));
      return;
    }
    if (!verificationId && !manualIdentityReview) {
      setError(t('accountDeletion.verifyIdentityFirst'));
      return;
    }
    setBusy('submit');
    try {
      const response = await apiFetch('/api/v1/account/me/deletion-request', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          confirmation: confirmation.trim(),
          reasonCategory,
          reasonDetails,
          ...(verificationId ? { verificationId } : {}),
          manualIdentityReview,
          source: 'WEB',
        }),
      });
      const body = await responseBody(response);
      if (!response.ok) throw new Error(body.message || body.detail || body.error || t('accountDeletion.error'));
      if (body.requiresChildResolution) {
        setChildrenCount(Number(body.linkedChildrenCount || 0));
        setRequest(current => ({
          ...(current || {}),
          hasRequest: false,
          status: 'NONE',
          linkedChildren: Array.isArray(body.linkedChildren) ? body.linkedChildren : current?.linkedChildren || [],
          linkedChildrenCount: Number(body.linkedChildrenCount || 0),
          requiresChildResolution: true,
        }));
        setNotice(t('accountDeletion.parentBlocked'));
        return;
      }
      if (!body.accepted) {
        if (body.requiresIndependentAccountConversion) {
          setNotice(t('accountDeletion.childIndependentConversionRequired'));
          return;
        }
        throw new Error(t('accountDeletion.error'));
      }
      setRequest({
        hasRequest: true,
        requestId: String(body.requestId || ''),
        status: String(body.status || 'REQUESTED'),
        linkedChildrenCount: Number(body.linkedChildrenCount || 0),
        includesLinkedChildren: false,
        reasonCategory: String(body.reasonCategory || reasonCategory),
        reasonDetails: String(body.reasonDetails || reasonDetails),
        linkedChildren: [],
        requestedAt: String(body.requestedAt || ''),
        updatedAt: String(body.requestedAt || ''),
      });
      setConfirmation('');
      setReasonCategory('');
      setReasonDetails('');
      setVerificationId('');
      setManualIdentityReview(false);
      setOtpRequested(false);
      setNotice(body.identityVerificationMethod === 'MANUAL_REVIEW_REQUIRED'
        ? t('accountDeletion.manualReviewNotice')
        : t('accountDeletion.requestAccepted'));
    } catch (e) {
      setError(e instanceof Error ? e.message : t('accountDeletion.error'));
    } finally {
      setBusy('');
    }
  }

  async function requestChildDeletion(child: LinkedChild) {
    setError('');
    setNotice('');
    if (confirmation.trim() !== 'DELETE') {
      setError(t('accountDeletion.confirmationMismatch'));
      return;
    }
    if (!reasonCategory || (reasonCategory === 'OTHER' && reasonDetails.trim().length < 5)) {
      setError(t('accountDeletion.reasonRequired'));
      return;
    }
    if (!verificationId && !manualIdentityReview) {
      setError(t('accountDeletion.verifyIdentityFirst'));
      return;
    }
    setBusy('child-request:' + child.publicUserId);
    try {
      const response = await apiFetch('/api/v1/account/me/deletion-request/children/' + encodeURIComponent(child.publicUserId), {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          confirmation: 'DELETE',
          reasonCategory,
          reasonDetails,
          ...(verificationId ? { verificationId } : {}),
          manualIdentityReview,
          source: 'WEB',
        }),
      });
      const body = await responseBody(response);
      if (!response.ok) throw new Error(body.message || body.detail || body.error || t('accountDeletion.childRequestFailed'));
      if (body.requiresIndependentAccountConversion) {
        setNotice(t('accountDeletion.childIndependentConversionRequired'));
        return;
      }
      if (body.requiresAdultVerificationReview) {
        setNotice(t('accountDeletion.childAgeReviewRequired'));
        await load();
        return;
      }
      if (!body.accepted) throw new Error(t('accountDeletion.childRequestFailed'));
      setVerificationId('');
      setManualIdentityReview(false);
      setOtpRequested(false);
      setOtp('');
      setConfirmation('');
      setNotice(body.alreadyPending
        ? t('accountDeletion.childRequestAlreadyPending')
        : t('accountDeletion.childRequestAccepted'));
      await load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : t('accountDeletion.childRequestFailed'));
    } finally {
      setBusy('');
    }
  }

  async function cancel() {
    setBusy('cancel');
    setError('');
    setNotice('');
    try {
      const response = await apiFetch('/api/v1/account/me/deletion-request', {
        method: 'DELETE', credentials: 'same-origin', cache: 'no-store',
      });
      const body = await responseBody(response);
      if (!response.ok) throw new Error(body.message || body.detail || body.error || t('accountDeletion.error'));
      setConfirmCancel(false);
      setVerificationId('');
      setManualIdentityReview(false);
      setOtpRequested(false);
      setNotice(t('accountDeletion.cancelledNotice'));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : t('accountDeletion.error'));
    } finally {
      setBusy('');
    }
  }

  const status = String(request?.status || 'NONE');
  const canStart = !request?.hasRequest || status === 'NONE' || status === 'CANCELLED' || status === 'REJECTED';
  const statusMessageKey: Record<string, string> = {
    REQUESTED: 'accountDeletion.statusRequested',
    UNDER_REVIEW: 'accountDeletion.statusUnderReview',
    AWAITING_LINKED_CHILD_DECISION: 'accountDeletion.statusChildrenDecision',
    CANCELLED: 'accountDeletion.statusCancelled',
    COMPLETED: 'accountDeletion.statusCompleted',
    REJECTED: 'accountDeletion.statusNeedsSupport',
  };

  return (
    <section className={compact ? 'qe-account-deletion qe-account-deletion--compact' : 'qe-account-deletion'} aria-labelledby="qe-account-deletion-title">
      <h2 id="qe-account-deletion-title">{t('accountDeletion.title')}</h2>
      <p>{t('accountDeletion.intro')}</p>
      <p className="qe-account-deletion__retention">{t('accountDeletion.retention')}</p>

      {signedIn === false && (
        <div className="qe-account-deletion__signed-out">
          <p>{t('accountDeletion.notSignedIn')}</p>
          <Link className="button button-dark button-small" href="/login?returnTo=%2Faccount-deletion">
            {t('accountDeletion.signIn')}
          </Link>
        </div>
      )}

      {signedIn === true && request && (
        <>
          {request.hasRequest && status !== 'CANCELLED' && status !== 'REJECTED' && (
            <div className="qe-account-deletion__status" role="status">
              <strong>{t('accountDeletion.statusLabel')}: {t(statusMessageKey[status] || 'accountDeletion.statusRequested')}</strong>
              <p>{t('accountDeletion.statusExplanation')}</p>
              {Number(request.linkedChildrenCount || 0) > 0 && (
                <p>{t('accountDeletion.childrenIncludedStatus', {
                  count: Number(request.linkedChildrenCount || 0),
                  mode: request.includesLinkedChildren ? t('accountDeletion.childrenIncluded') : t('accountDeletion.childrenNotIncluded'),
                })}</p>
              )}
              {status !== 'COMPLETED' && (
                <>
                  {!confirmCancel ? (
                    <button type="button" className="button button-light button-small" onClick={() => setConfirmCancel(true)} disabled={busy !== ''}>
                      {t('accountDeletion.cancelButton')}
                    </button>
                  ) : (
                    <div className="qe-account-deletion__confirm-cancel">
                      <p>{t('accountDeletion.cancelConfirm')}</p>
                      <button type="button" className="button button-light button-small" onClick={() => setConfirmCancel(false)} disabled={busy !== ''}>
                        {t('accountDeletion.keepRequest')}
                      </button>
                      <button type="button" className="button button-danger button-small" onClick={() => void cancel()} disabled={busy !== ''}>
                        {busy === 'cancel' ? t('accountDeletion.cancelling') : t('accountDeletion.confirmCancel')}
                      </button>
                    </div>
                  )}
                </>
              )}
            </div>
          )}

          {(status === 'CANCELLED' || status === 'REJECTED') && (
            <p className="qe-account-deletion__status" role="status">
              {t(statusMessageKey[status] || 'accountDeletion.statusCancelled')}
            </p>
          )}

          {canStart && (
            <section className="qe-account-deletion__verification" aria-labelledby="qe-account-deletion-verification-title">
              <h3 id="qe-account-deletion-verification-title">{t('accountDeletion.verifyIdentityTitle')}</h3>
              <p>{t('accountDeletion.verifyIdentityDescription')}</p>
              {mobileVerified && (
                <div className="qe-account-deletion__verify-method">
                  <p>{t('accountDeletion.verifiedMobile', { lastFour: mobileLastFour })}</p>
                  <button type="button" className="button button-light button-small"
                    onClick={() => void requestOtp()} disabled={busy !== '' || Boolean(verificationId)}>
                    {busy === 'otp-request' ? t('accountDeletion.otpSending') : t('accountDeletion.sendOtp')}
                  </button>
                  {otpRequested && !verificationId && (
                    <div className="qe-account-deletion__otp-entry">
                      <label className="qe-account-deletion__confirmation">
                        {t('accountDeletion.otpLabel')}
                        <input value={otp} onChange={event => setOtp(event.target.value.replace(/\\D/g, '').slice(0, 6))}
                          inputMode="numeric" autoComplete="one-time-code" maxLength={6} />
                      </label>
                      <button type="button" className="button button-dark button-small"
                        onClick={() => void verifyOtp()} disabled={busy !== '' || otp.length !== 6}>
                        {busy === 'otp-verify' ? t('accountDeletion.otpVerifying') : t('accountDeletion.verifyOtp')}
                      </button>
                    </div>
                  )}
                </div>
              )}
              {gmailVerified && !verificationId && (
                <div className="qe-account-deletion__verify-method">
                  <p>{t('accountDeletion.verifiedGoogle')}</p>
                  <GoogleSignInButton locale={locale} context="use"
                    endpoint="/api/v1/account/me/deletion-request/verify-google"
                    onAuthenticated={onGoogleVerified} />
                </div>
              )}
              {!mobileVerified && !gmailVerified && (
                <p className="qe-account-deletion__retention">{t('accountDeletion.noVerifiedMethod')}</p>
              )}
              {verificationId && !manualIdentityReview && (
                <p className="qe-account-deletion__notice" role="status">{t('accountDeletion.identityVerified')}</p>
              )}
              <label className="qe-account-deletion__checkbox">
                <input type="checkbox" checked={manualIdentityReview}
                  onChange={event => {
                    const checked = event.target.checked;
                    setManualIdentityReview(checked);
                    if (checked) setVerificationId('');
                  }} disabled={busy !== ''} />
                <span>{t('accountDeletion.manualReviewLabel')}</span>
              </label>
              {manualIdentityReview && (
                <p className="qe-account-deletion__retention" role="status">{t('accountDeletion.manualReviewNotice')}</p>
              )}
            </section>
          )}

          {canStart && (
            <form className="qe-account-deletion__form" onSubmit={submit}>
              <h3>{t('accountDeletion.reasonQuestionTitle')}</h3>
              <p>{t('accountDeletion.reasonQuestionDescription')}</p>
              <label className="qe-account-deletion__confirmation">
                {t('accountDeletion.reasonLabel')}
                <select value={reasonCategory} onChange={event => setReasonCategory(event.target.value)} required>
                  <option value="">{t('accountDeletion.chooseReason')}</option>
                  <option value="NO_LONGER_NEEDED">{t('accountDeletion.reason.noLongerNeeded')}</option>
                  <option value="PRIVACY_CONCERN">{t('accountDeletion.reason.privacyConcern')}</option>
                  <option value="SECURITY_CONCERN">{t('accountDeletion.reason.securityConcern')}</option>
                  <option value="DUPLICATE_ACCOUNT">{t('accountDeletion.reason.duplicateAccount')}</option>
                  <option value="MOVING_TO_ANOTHER_SERVICE">{t('accountDeletion.reason.movingService')}</option>
                  <option value="DATA_CONCERN">{t('accountDeletion.reason.dataConcern')}</option>
                  <option value="OTHER">{t('accountDeletion.reason.other')}</option>
                </select>
              </label>
              <label className="qe-account-deletion__confirmation">
                {t('accountDeletion.reasonDetailsLabel')}
                <textarea value={reasonDetails} onChange={event => setReasonDetails(event.target.value.slice(0, 1500))}
                  maxLength={1500} rows={3} placeholder={t('accountDeletion.reasonDetailsPlaceholder')} />
              </label>

              {childrenCount > 0 && (
                <div className="qe-account-deletion__warning" role="region" aria-labelledby="qe-account-deletion-children-title">
                  <h3 id="qe-account-deletion-children-title">{t('accountDeletion.childResolutionTitle')}</h3>
                  <p>{t('accountDeletion.parentBlocked')}</p>
                  <p className="qe-account-deletion__retention">{t('accountDeletion.childDeletionNotCompleted')}</p>
                  <div className="qe-account-deletion__children">
                    {(request?.linkedChildren || []).map(child => (
                      <article className="qe-account-deletion__child" key={child.publicUserId}>
                        <strong>{child.displayName || child.publicUserId}</strong>
                        <small>{t('accountDeletion.childReference', { reference: child.publicUserId })}</small>
                        {child.adultVerified ? (
                          <p>{t('accountDeletion.childAdultVerified')}</p>
                        ) : (
                          <p>{t('accountDeletion.childAdultVerificationPending')}</p>
                        )}
                        {child.deletionRequestStatus ? (
                          <p role="status">{t('accountDeletion.childRequestStatus', { status: child.deletionRequestStatus })}</p>
                        ) : child.adultVerificationStatus === 'REJECTED' ? (
                          <button type="button" className="button button-light button-small"
                            disabled={busy !== '' || !reasonCategory || confirmation.trim() !== 'DELETE' ||
                              (!verificationId && !manualIdentityReview) || (reasonCategory === 'OTHER' && reasonDetails.trim().length < 5)}
                            onClick={() => void requestChildDeletion(child)}>
                            {busy === 'child-request:' + child.publicUserId
                              ? t('accountDeletion.requesting')
                              : t('accountDeletion.childRequestButton')}
                          </button>
                        ) : (
                          <p>{t('accountDeletion.childAgeReviewRequired')}</p>
                        )}
                      </article>
                    ))}
                  </div>
                </div>
              )}

              <label className="qe-account-deletion__confirmation">
                {t('accountDeletion.confirmationLabel')}
                <input value={confirmation} onChange={event => setConfirmation(event.target.value.toUpperCase().slice(0, 20))}
                  placeholder={t('accountDeletion.confirmInput')} autoComplete="off" required />
              </label>
              {childrenCount === 0 && (
                <button type="submit" className="button button-danger button-small"
                  disabled={busy !== '' || !verificationId && !manualIdentityReview || confirmation.trim() !== 'DELETE' ||
                    !reasonCategory || (reasonCategory === 'OTHER' && reasonDetails.trim().length < 5)}>
                  {busy === 'submit' ? t('accountDeletion.requesting') : t('accountDeletion.submitButton')}
                </button>
              )}
            </form>
          )}
        </>
      )}

      {signedIn === null && busy === 'load' && <p aria-live="polite">{t('accountDeletion.loading')}</p>}
      {notice && <p className="qe-account-deletion__notice" role="status">{notice}</p>}
      {error && <p className="qe-account-deletion__error" role="alert">{error}</p>}
      <p className="qe-account-deletion__external-note">{t('accountDeletion.externalNote')}</p>
    </section>
  );
}
