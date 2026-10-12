'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import styles from './Verify.module.css';

export default function CertificateVerifyPage() {
  const [code, setCode] = useState('');
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const query = new URLSearchParams(window.location.search);
    const initial = query.get('code') || '';
    setCode(initial);
    if (initial.trim()) void verify(initial);
  }, []);

  async function verify(value = code) {
    const normalized = value.trim().toUpperCase();
    if (!normalized) { setError('Enter the certificate ID printed on the document.'); return; }
    setBusy(true); setError(''); setData(null);
    try {
      const response = await fetch('/api/v1/certificates/verify/' + encodeURIComponent(normalized), { cache: 'no-store' });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(response.status === 404 ? 'No certificate record matches this ID.' : (body.message || 'Verification could not be completed.'));
      setData(body);
    } catch (e: any) {
      setError(e?.message || 'Verification could not be completed.');
    } finally { setBusy(false); }
  }

  return (
    <main className="app-shell">
      <section className={styles.page}>
        <Link href="/" className={styles.brand}>QuantaEdge <span>Certificate verification</span></Link>
        <div className={styles.card}>
          <span className={styles.kicker}>AUTHENTICITY CHECK</span>
          <h1>Verify a certificate</h1>
          <p>Enter the certificate ID shown on a QuantaEdge PDF or PNG. Only the certificate's visible details are displayed.</p>
          <form className={styles.form} onSubmit={(event) => { event.preventDefault(); void verify(); }}>
            <label htmlFor="certificate-code">Certificate ID</label>
            <input id="certificate-code" value={code} onChange={(event) => setCode(event.target.value)}
              placeholder="QE-2026-ABCDEF1234567890" autoComplete="off" maxLength={40} />
            <button className="button button-dark" disabled={busy}>{busy ? 'Checking…' : 'Verify certificate'}</button>
          </form>
          {error && <div className={styles.error} role="alert">{error}</div>}
          {data && <section className={styles.result} aria-live="polite">
            <div className={data.verified ? styles.statusValid : styles.statusRevoked}>
              <span aria-hidden="true">{data.verified ? '✓' : '!'}</span>
              <strong>{data.verified ? 'Valid certificate' : 'Certificate revoked'}</strong>
            </div>
            <dl>
              <div><dt>Certificate ID</dt><dd>{data.certificateNumber}</dd></div>
              <div><dt>Recipient</dt><dd>{data.recipientName}</dd></div>
              <div><dt>Achievement</dt><dd>{data.achievementTitle}</dd></div>
              <div><dt>Class</dt><dd>{data.classCode ? 'Class ' + data.classCode : '—'}</dd></div>
              <div><dt>Issue date</dt><dd>{data.issuedAt ? new Date(data.issuedAt).toLocaleDateString() : '—'}</dd></div>
            </dl>
            <p className={styles.privacy}>This check confirms the current certificate status; it does not disclose the student's profile or assessment history.</p>
          </section>}
        </div>
      </section>
    </main>
  );
}
