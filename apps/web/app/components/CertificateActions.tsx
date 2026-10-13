'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import styles from './CertificateActions.module.css';

type CertificateRecord = {
  id?: number;
  certificate_id?: number;
  certificateId?: number;
  certificate_number?: string;
  certificateNumber?: string;
  achievement_title?: string;
  achievementTitle?: string;
  status?: string;
  issued_at?: string;
  issuedAt?: string;
};

type Eligibility = {
  eligible?: boolean;
  canIssue?: boolean;
  reason?: string;
  scorePercent?: number | string | null;
  passThresholdPercent?: number | string;
  certificate?: CertificateRecord | null;
};

type Props = {
  attemptId?: number;
  assessmentType?: string;
  isGenerated?: boolean;
  status?: string;
  certificate?: CertificateRecord;
  language?: string;
  templateId?: string;
  compact?: boolean;
};

function certId(certificate?: CertificateRecord | null) {
  return Number(certificate?.id ?? certificate?.certificate_id ?? certificate?.certificateId ?? 0);
}
function certNumber(certificate?: CertificateRecord | null) {
  return String(certificate?.certificate_number ?? certificate?.certificateNumber ?? '');
}
function certStatus(certificate?: CertificateRecord | null) {
  return String(certificate?.status ?? 'ISSUED').toUpperCase();
}
function certTitle(certificate?: CertificateRecord | null) {
  return String(certificate?.achievement_title ?? certificate?.achievementTitle ?? 'QuantaEdge achievement');
}

export default function CertificateActions({
  attemptId,
  assessmentType,
  isGenerated = false,
  status,
  certificate,
  language = 'en',
  templateId = 'CLASSIC',
  compact = false,
}: Props) {
  const [eligibility, setEligibility] = useState<Eligibility | null>(null);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const canCheckAttempt = Boolean(
    attemptId && assessmentType === 'ADMIN_CREATED' && !isGenerated && status === 'RELEASED'
  );

  useEffect(() => {
    if (certificate) {
      setEligibility({ certificate, eligible: certStatus(certificate) === 'ISSUED', canIssue: false });
      return;
    }
    if (!canCheckAttempt || !attemptId) {
      setEligibility(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError('');
    fetch('/api/v1/students/me/certificates/assessment-attempts/' + attemptId, {
      cache: 'no-store',
      credentials: 'same-origin',
    })
      .then(async (response) => {
        const body = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(body.message || 'Could not check certificate eligibility.');
        return body as Eligibility;
      })
      .then((body) => { if (!cancelled) setEligibility(body); })
      .catch((failure: any) => {
        if (!cancelled) setError(failure?.message || 'Could not check certificate eligibility.');
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [attemptId, canCheckAttempt, certificate]);

  if (certificate && !certId(certificate)) return null;
  if (!certificate && !canCheckAttempt) return null;

  async function issue() {
    if (!attemptId || busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const response = await fetch('/api/v1/students/me/certificates/issue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({
          achievementType: 'ASSESSMENT_PASS',
          assessmentAttemptId: attemptId,
          templateId,
          language: language === 'hi' ? 'hi' : 'en',
        }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.message || 'Certificate could not be issued.');
      setEligibility({ eligible: true, canIssue: false, certificate: body });
      setNotice('Certificate issued successfully.');
    } catch (failure: any) {
      setError(failure?.message || 'Certificate could not be issued.');
    } finally {
      setBusy(false);
    }
  }

  const current = certificate ?? eligibility?.certificate ?? null;
  const id = certId(current);
  const number = certNumber(current);
  const currentStatus = current ? certStatus(current) : '';
  const isRevoked = currentStatus === 'REVOKED';
  const base = id > 0 ? '/api/v1/students/me/certificates/' + id + '/download' : '';
  const verifyUrl = number ? '/certificate/verify?code=' + encodeURIComponent(number) : '';
  const title = certTitle(current);

  return (
    <section className={styles.root + (compact ? ' ' + styles.compact : '')} aria-live="polite">
      <div className={styles.heading}>
        <span className={styles.mark} aria-hidden="true">QE</span>
        <div className={styles.copy}>
          <strong>{current ? title : 'Certificate'}</strong>
          <small>{current ? (number || 'Issued certificate') : 'Admin-created assessment certificate'}</small>
        </div>
        {current && <span className={isRevoked ? styles.revoked : styles.issued}>{isRevoked ? 'REVOKED' : 'ISSUED'}</span>}
      </div>

      {loading && <p className={styles.message}>Checking eligibility and issued certificate…</p>}
      {error && <p className={styles.error} role="alert">{error}</p>}
      {notice && <p className={styles.notice} role="status">{notice}</p>}

      {current && isRevoked && (
        <p className={styles.error}>This certificate has been revoked. It cannot be downloaded as valid proof.</p>
      )}

      {current && !isRevoked && id > 0 && (
        <div className={styles.actions}>
          <a href={base + '?format=pdf&disposition=inline'} target="_blank" rel="noopener noreferrer">View PDF</a>
          <a href={base + '?format=pdf'}>Download PDF</a>
          <a href={base + '?format=png'}>Download PNG</a>
          {verifyUrl && <Link href={verifyUrl} target="_blank">Verify certificate ↗</Link>}
        </div>
      )}

      {!current && !loading && eligibility?.canIssue && (
        <div className={styles.eligible}>
          <span>Score: {eligibility.scorePercent ?? '—'}% · Required: {eligibility.passThresholdPercent ?? 80}%</span>
          <button type="button" onClick={() => void issue()} disabled={busy}>
            {busy ? 'Issuing…' : 'Issue & view certificate'}
          </button>
        </div>
      )}

      {!current && !loading && eligibility && !eligibility.canIssue && (
        <p className={styles.message}>{eligibility.reason || 'This result is not currently eligible for a certificate.'}</p>
      )}
    </section>
  );
}
