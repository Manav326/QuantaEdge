'use client';

import AdminSidebar from '../components/AdminSidebar';
import { Fragment } from 'react';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './Certificates.module.css';

type CertificateRow = {
  id: number;
  certificate_number: string;
  student_id: number;
  recipient_name: string;
  achievement_type: string;
  class_code: string;
  subject_code?: string;
  achievement_title: string;
  template_id: string;
  language: string;
  status: string;
  issued_at: string;
  revoked_at?: string;
  revocation_reason?: string;
};

const PAGE_SIZE = 50;

export default function AdminCertificatesPage() {
  const router = useRouter();
  const [rows, setRows] = useState<CertificateRow[]>([]);
  const [status, setStatus] = useState('ALL');
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [revoking, setRevoking] = useState<number | null>(null);
  const [reason, setReason] = useState('');
  const [auditFor, setAuditFor] = useState<number | null>(null);
  const [auditRows, setAuditRows] = useState<any[]>([]);
  const [auditLoading, setAuditLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({
        status,
        limit: String(PAGE_SIZE),
        offset: String(page * PAGE_SIZE),
      });
      const response = await fetch('/api/v1/admin/certificates?' + params.toString(), {
        cache: 'no-store',
        credentials: 'same-origin',
      });
      if (response.status === 401 || response.status === 403) {
        router.replace('/login');
        return;
      }
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.message || 'Unable to load the certificate register.');
      setRows(Array.isArray(body.items) ? body.items : []);
      setTotal(Number(body.total || 0));
    } catch (e: any) {
      setError(e?.message || 'Unable to load the certificate register.');
    } finally {
      setLoading(false);
    }
  }, [router, status, page]);

  useEffect(() => { void load(); }, [load]);

  async function revoke(certificate: CertificateRow) {
    const trimmed = reason.trim();
    if (trimmed.length < 5) {
      setError('Enter a clear revocation reason of at least five characters.');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const response = await fetch('/api/v1/admin/certificates/' + certificate.id + '/revoke', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify({ reason: trimmed }),
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.message || 'Certificate revocation failed.');
      setNotice('Certificate ' + certificate.certificate_number + ' is now ' + String(body.status || 'REVOKED') + '.');
      setRevoking(null);
      setReason('');
      await load();
    } catch (e: any) {
      setError(e?.message || 'Certificate revocation failed.');
    } finally {
      setBusy(false);
    }
  }

  async function toggleAudit(certificateId: number) {
    if (auditFor === certificateId) {
      setAuditFor(null);
      setAuditRows([]);
      return;
    }
    setAuditFor(certificateId);
    setAuditRows([]);
    setAuditLoading(true);
    setError('');
    try {
      const response = await fetch('/api/v1/admin/certificates/' + certificateId + '/audit', {
        cache: 'no-store',
        credentials: 'same-origin',
      });
      if (response.status === 401 || response.status === 403) {
        router.replace('/login');
        return;
      }
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.message || 'Unable to load certificate audit history.');
      setAuditRows(Array.isArray(body.items) ? body.items : []);
    } catch (e: any) {
      setError(e?.message || 'Unable to load certificate audit history.');
    } finally {
      setAuditLoading(false);
    }
  }

  const firstRecord = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const lastRecord = Math.min(total, (page + 1) * PAGE_SIZE);

  return (
    <main className="admin-shell">
      <AdminSidebar active="certificates" />
      <section className="admin-main">
        <header className="admin-top">
          <div>
            <span className="admin-kicker">LEARNER OPERATIONS</span>
            <h1>Certificates</h1>
            <p>Issued learning achievements, file availability, validity and revocation audit.</p>
          </div>
        </header>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {notice && <div className={styles.notice} role="status">{notice}</div>}

        <section className="admin-panel">
          <div className="panel-head">
            <div><span>CERTIFICATE REGISTER</span><h2>Student achievement records</h2></div>
            <span className="count">{total} records</span>
          </div>

          <div className={styles.toolbar}>
            <label>
              <span>Status</span>
              <select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); setRevoking(null); }}>
                <option value="ALL">All certificates</option>
                <option value="ISSUED">Issued and valid</option>
                <option value="REVOKED">Revoked</option>
              </select>
            </label>
            <span className={styles.range}>{total ? ('Showing ' + firstRecord + '–' + lastRecord + ' of ' + total) : 'No records'}</span>
          </div>

          {loading ? (
            <div className={styles.empty}>Loading the certificate register…</div>
          ) : rows.length === 0 ? (
            <div className={styles.empty}><strong>No certificates found</strong><p>Issued certificates will appear here after a verified student achievement.</p></div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr>
                  <th>Recipient / certificate ID</th><th>Achievement</th><th>Design / language</th><th>Issued</th><th>Status</th><th>Admin action</th>
                </tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <Fragment key={row.id}>
                      <tr>
                      <td><strong>{row.recipient_name}</strong><small>{row.certificate_number}</small><small>Class {row.class_code} · Student #{row.student_id}</small></td>
                      <td><strong>{row.achievement_title}</strong><small>{row.achievement_type === 'SUBJECT_COMPLETION' ? 'Subject completion' : 'Assessment pass'}{row.subject_code ? ' · ' + row.subject_code : ''}</small></td>
                      <td>{row.template_id}<small>{row.language === 'hi' ? 'Hindi' : 'English'}</small></td>
                      <td>{new Date(row.issued_at).toLocaleDateString()}</td>
                      <td><span className={row.status === 'ISSUED' ? styles.valid : styles.revoked}>{row.status}</span>
                        {row.revocation_reason && <small className={styles.reason}>{row.revocation_reason}</small>}</td>
                      <td>
                        <div className={styles.actionLinks}>
                          <a href={'/api/v1/admin/certificates/' + row.id + '/download?format=pdf'}>Original PDF</a>
                          <a href={'/api/v1/admin/certificates/' + row.id + '/download?format=png'}>Original PNG</a>
                          <button type="button" className={styles.auditButton}
                            onClick={() => void toggleAudit(row.id)}>{auditFor === row.id ? 'Hide audit' : 'View audit'}</button>
                          {row.status === 'ISSUED' ? (
                            <button type="button" className={styles.revokeButton}
                              onClick={() => { setRevoking(revoking === row.id ? null : row.id); setReason(''); setError(''); }}>
                              {revoking === row.id ? 'Cancel' : 'Revoke'}
                            </button>
                          ) : <span className={styles.muted}>No action</span>}
                        </div>
                        {revoking === row.id && (
                          <div className={styles.revokeForm}>
                            <label htmlFor={'reason-' + row.id}>Reason (audited)</label>
                            <textarea id={'reason-' + row.id} value={reason} maxLength={500}
                              onChange={(event) => setReason(event.target.value)}
                              placeholder="Explain why this certificate should be revoked." rows={3} />
                            <button type="button" disabled={busy || reason.trim().length < 5}
                              onClick={() => void revoke(row)}>{busy ? 'Saving…' : 'Confirm revocation'}</button>
                          </div>
                        )}
                      </td>
                      </tr>
                      {auditFor === row.id && (
                        <tr><td colSpan={6}>
                          <div className={styles.auditPanel}>
                            <strong>Audit history · {row.certificate_number}</strong>
                            {auditLoading ? <p>Loading audit events…</p> : auditRows.length ? (
                              <ol>{auditRows.map((event: any) => (
                                <li key={event.id}>
                                  <span>{String(event.action || '').replaceAll('_', ' ')} · {event.actor_role} · {new Date(event.occurred_at).toLocaleString()}</span>
                                  <small>{typeof event.detail === 'string' ? event.detail : JSON.stringify(event.detail || {})}</small>
                                </li>
                              ))}</ol>
                            ) : <p>No audit events returned.</p>}
                          </div>
                        </td></tr>
                      )}
                    </Fragment>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className={styles.pagination}>
            <button type="button" disabled={page === 0 || loading} onClick={() => setPage((value) => Math.max(0, value - 1))}>← Previous</button>
            <span>Page {page + 1} of {Math.max(1, Math.ceil(total / PAGE_SIZE))}</span>
            <button type="button" disabled={loading || (page + 1) * PAGE_SIZE >= total} onClick={() => setPage((value) => value + 1)}>Next →</button>
          </div>
        </section>
      </section>
    </main>
  );
}
