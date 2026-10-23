'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import Link from 'next/link';
import { apiFetch } from './apiClient';
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
  const [includeChildren, setIncludeChildren] = useState(false);
  const [childrenConfirmationRequired, setChildrenConfirmationRequired] = useState(false);
  const [childrenCount, setChildrenCount] = useState(0);
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
      const response = await apiFetch('/api/v1/account/me/deletion-request', {
        credentials: 'same-origin', cache: 'no-store',
      });
      const body = await responseBody(response);
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

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setNotice('');
    if (confirmation.trim() !== 'DELETE') {
      setError(t('accountDeletion.confirmationMismatch'));
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
          includeLinkedChildren: includeChildren,
          source: 'WEB',
        }),
      });
      const body = await responseBody(response);
      if (!response.ok) throw new Error(body.message || body.detail || body.error || t('accountDeletion.error'));
      if (body.requiresLinkedChildrenConfirmation) {
        const count = Number(body.linkedChildrenCount || 0);
        setChildrenCount(count);
        setChildrenConfirmationRequired(true);
        setIncludeChildren(false);
        setError(t('accountDeletion.childrenRequired', { count }));
        return;
      }
      if (!body.accepted) throw new Error(t('accountDeletion.error'));
      setRequest({
        hasRequest: true,
        requestId: String(body.requestId || ''),
        status: String(body.status || 'REQUESTED'),
        linkedChildrenCount: Number(body.linkedChildrenCount || 0),
        includesLinkedChildren: Boolean(body.includesLinkedChildren),
        requestedAt: String(body.requestedAt || ''),
        updatedAt: String(body.requestedAt || ''),
      });
      setChildrenConfirmationRequired(false);
      setConfirmation('');
      setNotice(t('accountDeletion.requestAccepted'));
    } catch (e) {
      setError(e instanceof Error ? e.message : t('accountDeletion.error'));
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
            <form className="qe-account-deletion__form" onSubmit={submit}>
              {childrenConfirmationRequired && childrenCount > 0 && (
                <>
                  <p className="qe-account-deletion__warning">
                    {t('accountDeletion.childrenRequired', { count: childrenCount })}
                  </p>
                  <label className="qe-account-deletion__checkbox">
                    <input type="checkbox" checked={includeChildren} onChange={event => setIncludeChildren(event.target.checked)} />
                    <span>{t('accountDeletion.includeChildren', { count: childrenCount })}</span>
                  </label>
                  <p className="qe-account-deletion__retention">{t('accountDeletion.childTransferNote')}</p>
                </>
              )}
              <label className="qe-account-deletion__confirmation">
                {t('accountDeletion.confirmationLabel')}
                <input value={confirmation} onChange={event => setConfirmation(event.target.value.toUpperCase().slice(0, 20))}
                  placeholder={t('accountDeletion.confirmInput')} autoComplete="off" required />
              </label>
              <button type="submit" className="button button-danger button-small"
                disabled={busy !== '' || confirmation.trim() !== 'DELETE' || (childrenConfirmationRequired && childrenCount > 0 && !includeChildren)}>
                {busy === 'submit' ? t('accountDeletion.requesting') : t('accountDeletion.submitButton')}
              </button>
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
