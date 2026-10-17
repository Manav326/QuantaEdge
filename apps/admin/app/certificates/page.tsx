'use client';

import { formatUserTimestamp } from '../components/formatUserTimestamp';

import AdminSidebar from '../components/AdminSidebar';
import { Fragment } from 'react';
import { useCallback, useContext, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './Certificates.module.css';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import { useUiTranslation } from '../i18n/useUiTranslation';

type CertificateRow = {
  id: number;
  certificate_number: string;
  student_id: number;
  recipient_name: string;
  achievement_type: string;
  class_code: string;
  subject_code?: string;
  subject_name?: string;
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
  const t = useUiTranslation();
  const { locale } = useContext(AdminWorkspaceContext);
  const [rows, setRows] = useState<CertificateRow[]>([]);
  const [status, setStatus] = useState('ALL');
  const [achievementType, setAchievementType] = useState('ALL');
  const [language, setLanguage] = useState('ALL');
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
        achievementType,
        language,
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
      if (!response.ok) throw new Error(body.message || t('admin.certificates.loadFailed'));
      setRows(Array.isArray(body.items) ? body.items : []);
      setTotal(Number(body.total || 0));
    } catch (e: any) {
      setError(e?.message || t('admin.certificates.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [router, status, achievementType, language, page, t]);

  useEffect(() => { void load(); }, [load]);

  async function revoke(certificate: CertificateRow) {
    const trimmed = reason.trim();
    if (trimmed.length < 5) {
      setError(t('admin.certificates.reasonTooShort'));
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
      if (!response.ok) throw new Error(body.message || t('admin.certificates.revokeFailed'));
      const returnedStatus = String(body.status || 'REVOKED');
      const translatedStatus = returnedStatus === 'ISSUED' ? t('admin.certificates.statusIssued') : returnedStatus === 'REVOKED' ? t('admin.certificates.statusRevoked') : returnedStatus;
      setNotice(t('admin.certificates.revocationSuccess', { certificateNumber: certificate.certificate_number, status: translatedStatus }));
      setRevoking(null);
      setReason('');
      await load();
    } catch (e: any) {
      setError(e?.message || t('admin.certificates.revokeFailed'));
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
      if (!response.ok) throw new Error(body.message || t('admin.certificates.auditLoadFailed'));
      setAuditRows(Array.isArray(body.items) ? body.items : []);
    } catch (e: any) {
      setError(e?.message || t('admin.certificates.auditLoadFailed'));
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
            <span className="admin-kicker">{t('admin.certificates.eyebrow')}</span>
            <h1>{t('admin.certificates.title')}</h1>
            <p>{t('admin.certificates.description')}</p>
          </div>
        </header>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {notice && <div className={styles.notice} role="status">{notice}</div>}

        <section className="admin-panel">
          <div className="panel-head">
            <div><span>{t('admin.certificates.register')}</span><h2>{t('admin.certificates.studentAchievementRecords')}</h2></div>
            <span className="count">{t('admin.certificates.recordCount', { count: total })}</span>
          </div>

          <div className={styles.toolbar}>
            <label>
              <span>{t('admin.certificates.status')}</span>
              <select value={status} onChange={(event) => { setStatus(event.target.value); setPage(0); setRevoking(null); }}>
                <option value="ALL">{t('admin.certificates.allCertificates')}</option>
                <option value="ISSUED">{t('admin.certificates.issuedAndValid')}</option>
                <option value="REVOKED">{t('admin.certificates.revokedFilter')}</option>
              </select>
            </label>
            <label>
              <span>{t('admin.certificates.achievementType')}</span>
              <select value={achievementType} onChange={(event) => { setAchievementType(event.target.value); setPage(0); setRevoking(null); }}>
                <option value="ALL">{t('admin.certificates.allAchievements')}</option>
                <option value="SUBJECT_COMPLETION">{t('admin.certificates.subjectCompletion')}</option>
                <option value="ASSESSMENT_PASS">{t('admin.certificates.assessmentPass')}</option>
              </select>
            </label>
            <label>
              <span>{t('admin.certificates.certificateLanguage')}</span>
              <select value={language} onChange={(event) => { setLanguage(event.target.value); setPage(0); setRevoking(null); }}>
                <option value="ALL">{t('admin.certificates.allLanguages')}</option>
                <option value="hi">{t('admin.certificates.hindi')}</option>
                <option value="en">{t('admin.certificates.english')}</option>
              </select>
            </label>
            <span className={styles.range}>{total ? t('admin.certificates.showingRange', { start: firstRecord, end: lastRecord, total }) : t('admin.certificates.noRecords')}</span>
          </div>

          {loading ? (
            <div className={styles.empty}>{t('admin.certificates.loadingRegister')}</div>
          ) : rows.length === 0 ? (
            <div className={styles.empty}><strong>{t('admin.certificates.noCertificatesFound')}</strong><p>{t('admin.certificates.issuedCertificatesEmpty')}</p></div>
          ) : (
            <div className={styles.tableWrap}>
              <table className={styles.table}>
                <thead><tr>
                  <th>{t('admin.certificates.recipientCertificateId')}</th><th>{t('admin.certificates.achievement')}</th><th>{t('admin.certificates.designLanguage')}</th><th>{t('admin.certificates.issued')}</th><th>{t('admin.certificates.status')}</th><th>{t('admin.certificates.adminAction')}</th>
                </tr></thead>
                <tbody>
                  {rows.map((row) => (
                    <Fragment key={row.id}>
                      <tr>
                      <td><strong>{row.recipient_name}</strong><small>{row.certificate_number}</small><small>{t('admin.certificates.classStudent', { classCode: row.class_code, studentId: row.student_id })}</small></td>
                      <td><strong>{row.achievement_title}</strong><small>{t('admin.certificates.classSubject', { classCode: row.class_code, subject: row.subject_name || row.subject_code || t('admin.certificates.subjectUnavailable') })}</small><small>{row.achievement_type === 'SUBJECT_COMPLETION' ? t('admin.certificates.subjectCompletion') : t('admin.certificates.assessmentPass')}</small></td>
                      <td>{row.template_id}<small>{row.language === 'hi' ? t('admin.certificates.hindi') : t('admin.certificates.english')}</small></td>
                      <td>{formatUserTimestamp(row.issued_at)}</td>
                      <td><span className={row.status === 'ISSUED' ? styles.valid : styles.revoked}>{row.status === 'ISSUED' ? t('admin.certificates.statusIssued') : row.status === 'REVOKED' ? t('admin.certificates.statusRevoked') : row.status}</span>
                        {row.revocation_reason && <small className={styles.reason}>{row.revocation_reason}</small>}</td>
                      <td>
                        <div className={styles.actionLinks}>
                          <a href={'/api/v1/admin/certificates/' + row.id + '/download?format=pdf'}>{row.status === 'ISSUED' ? t('admin.certificates.certificatePdf') : t('admin.certificates.auditCopyPdf')}</a>
                          <a href={'/api/v1/admin/certificates/' + row.id + '/download?format=png'}>{row.status === 'ISSUED' ? t('admin.certificates.certificatePng') : t('admin.certificates.auditCopyPng')}</a>
                          <button type="button" className={styles.auditButton}
                            onClick={() => void toggleAudit(row.id)}>{auditFor === row.id ? t('admin.certificates.hideAudit') : t('admin.certificates.viewAudit')}</button>
                          {row.status === 'ISSUED' ? (
                            <button type="button" className={styles.revokeButton}
                              onClick={() => { setRevoking(revoking === row.id ? null : row.id); setReason(''); setError(''); }}>
                              {revoking === row.id ? t('admin.certificates.cancel') : t('admin.certificates.revoke')}
                            </button>
                          ) : <span className={styles.muted}>{t('admin.certificates.noAction')}</span>}
                        </div>
                        {revoking === row.id && (
                          <div className={styles.revokeForm}>
                            <label htmlFor={'reason-' + row.id}>{t('admin.certificates.reasonAudited')}</label>
                            <textarea id={'reason-' + row.id} value={reason} maxLength={500}
                              onChange={(event) => setReason(event.target.value)}
                              placeholder={t('admin.certificates.reasonPlaceholder')} rows={3} />
                            <button type="button" disabled={busy || reason.trim().length < 5}
                              onClick={() => void revoke(row)}>{busy ? t('admin.certificates.saving') : t('admin.certificates.confirmRevocation')}</button>
                          </div>
                        )}
                      </td>
                      </tr>
                      {auditFor === row.id && (
                        <tr><td colSpan={6}>
                          <div className={styles.auditPanel}>
                            <strong>{t('admin.certificates.auditHistory', { certificateNumber: row.certificate_number })}</strong>
                            {auditLoading ? <p>{t('admin.certificates.loadingAuditEvents')}</p> : auditRows.length ? (
                              <ol>{auditRows.map((event: any) => (
                                <li key={event.id}>
                                  <span>{String(event.action || '').replaceAll('_', ' ')} · {event.actor_role} · {formatUserTimestamp(event.occurred_at)}</span>
                                  <small>{typeof event.detail === 'string' ? event.detail : JSON.stringify(event.detail || {})}</small>
                                </li>
                              ))}</ol>
                            ) : <p>{t('admin.certificates.noAuditEvents')}</p>}
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
            <button type="button" disabled={page === 0 || loading} onClick={() => setPage((value) => Math.max(0, value - 1))}>{t('admin.certificates.previous')}</button>
            <span>{t('admin.certificates.pageOf', { current: page + 1, total: Math.max(1, Math.ceil(total / PAGE_SIZE)) })}</span>
            <button type="button" disabled={loading || (page + 1) * PAGE_SIZE >= total} onClick={() => setPage((value) => value + 1)}>{t('admin.certificates.next')}</button>
          </div>
        </section>
      </section>
    </main>
  );
}
