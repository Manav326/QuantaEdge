'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { apiFetch } from '../components/apiClient';
import styles from './MarketplaceLaunchStepper.module.css';

type LaunchCheck = {
  code: string;
  label: string;
  passed: boolean;
  required: boolean;
  message: string;
};
type LaunchItem = {
  assignmentId: number;
  offerId?: number | null;
  institutionId: number;
  institutionName: string;
  courseId: number;
  courseName: string;
  offerStatus: string;
  pricePaise?: number | null;
  currency?: string;
  ready: boolean;
  checks: LaunchCheck[];
  blockingIssues: string[];
  message: string;
};
type LaunchResponse = {
  institutionId: number;
  institutionName: string;
  items: LaunchItem[];
  total: number;
  readyCount: number;
  blockedCount: number;
};
type Draft = { title: string; description: string; priceRupees: string };

const GROUPS = [
  { key: 'course', number: '01', title: 'Course & release', subtitle: 'Course is active, quality-reviewed, shareable, and has published learning content.', codes: ['INSTITUTION_ACTIVE', 'COURSE_ACTIVE', 'ASSIGNMENT_AND_RELEASE', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT'] },
  { key: 'offer', number: '02', title: 'Listing details', subtitle: 'Set the student-facing title, description, currency and explicit price.', codes: ['OFFER_TERMS'] },
  { key: 'payment', number: '03', title: 'Payment setup', subtitle: 'Paid courses require working Razorpay credentials and the signed course-payment webhook.', codes: ['RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK'] },
  { key: 'seller', number: '04', title: 'Seller & tax', subtitle: 'Approve the seller and tax treatment, GST registration where required, and e-invoice applicability.', codes: ['APPROVED_TAX_PROFILE', 'EINVOICE_REVIEW', 'TAX_CALCULATION', 'GST_REGISTRATION'] },
  { key: 'invoice', number: '05', title: 'Invoice & revenue share', subtitle: 'Publish an invoice template and approve a valid revenue-share policy for the after-tax pool.', codes: ['PUBLISHED_INVOICE_TEMPLATE', 'REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION'] },
] as const;

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-QE-Workspace', 'HRMS');
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await apiFetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let data: any = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw }; }
  if (!response.ok) throw new Error(String(data.message || data.detail || data.error || 'Request failed (' + response.status + ')'));
  return data as T;
}

function priceText(paise?: number | null) {
  if (paise === null || paise === undefined) return 'Price not set';
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(paise / 100);
}
function statusText(status: string) {
  return status.replaceAll('_', ' ').toLowerCase().replace(/\b[a-z]/g, value => value.toUpperCase());
}
function initialDraft(item: LaunchItem): Draft {
  return {
    title: item.courseName || '',
    description: '',
    priceRupees: item.pricePaise === null || item.pricePaise === undefined ? '' : (item.pricePaise / 100).toFixed(2).replace(/\.00$/, ''),
  };
}

export default function MarketplaceLaunchStepper({
  institutionId,
  institutionName,
}: {
  institutionId: number;
  institutionName: string;
}) {
  const [data, setData] = useState<LaunchResponse | null>(null);
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [loading, setLoading] = useState(true);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await api<LaunchResponse>('/api/v1/hrms/platform/institutions/' + institutionId + '/marketplace-launches');
      const items = Array.isArray(result.items) ? result.items : [];
      setData({ ...result, items });
      setDrafts(previous => {
        const next: Record<number, Draft> = {};
        for (const item of items) {
          next[item.assignmentId] = previous[item.assignmentId] || initialDraft(item);
        }
        return next;
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Marketplace launch readiness could not be loaded.');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [institutionId]);

  useEffect(() => { void load(); }, [load]);

  const counts = useMemo(() => {
    const items = data?.items || [];
    return {
      total: items.length,
      ready: items.filter(item => item.ready).length,
      published: items.filter(item => item.offerStatus === 'PUBLISHED').length,
    };
  }, [data]);

  const updateDraft = (assignmentId: number, patch: Partial<Draft>) => {
    setDrafts(current => ({
      ...current,
      [assignmentId]: { ...(current[assignmentId] || { title: '', description: '', priceRupees: '' }), ...patch },
    }));
  };

  const saveDraft = async (item: LaunchItem) => {
    const draft = drafts[item.assignmentId] || initialDraft(item);
    const raw = draft.priceRupees.trim();
    if (!/^\d+(?:\.\d{1,2})?$/.test(raw)) {
      setError('Set an explicit INR price with up to two decimal places. Use 0 only if the listing is intentionally free.');
      return;
    }
    const pricePaise = Math.round(Number(raw) * 100);
    if (!Number.isSafeInteger(pricePaise) || pricePaise < 0) {
      setError('The course price is outside the supported range.');
      return;
    }
    if (draft.title.trim().length > 200 || draft.description.trim().length > 5000) {
      setError('The listing title must be 200 characters or fewer and the description 5,000 characters or fewer.');
      return;
    }
    setBusyKey('save-' + item.assignmentId);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId + '/marketplace-launches/' + item.assignmentId + '/offer', {
        method: 'POST',
        body: JSON.stringify({
          title: draft.title.trim(),
          description: draft.description.trim(),
          pricePaise,
          currency: 'INR',
        }),
      });
      setNotice(item.offerId ? 'Draft listing saved. Re-run the checklist before resubmitting.' : 'Draft listing created. Continue through the launch checklist.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The course listing draft could not be saved.');
    } finally {
      setBusyKey(null);
    }
  };

  const transition = async (item: LaunchItem, action: 'submit' | 'approve' | 'publish' | 'archive' | 'return-to-draft') => {
    if (!item.offerId) return;
    if (action === 'publish' && !window.confirm('Publish ' + item.courseName + ' to the student Marketplace for ' + institutionName + '?')) return;
    let body: string | undefined;
    if (action === 'return-to-draft') {
      const reason = window.prompt('Why is this offer being returned to draft?', 'Launch checks need correction');
      if (reason === null) return;
      if (reason.trim().length < 3) {
        setError('Enter a reason of at least 3 characters.');
        return;
      }
      body = JSON.stringify({ reason: reason.trim() });
    }
    if (action === 'archive' && !window.confirm('Remove ' + item.courseName + ' from the student Marketplace? Existing purchase history will be retained.')) return;
    setBusyKey(action + '-' + item.assignmentId);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId + '/marketplace-launches/' + item.offerId + '/' + action, {
        method: 'POST',
        ...(body ? { body } : {}),
      });
      const messages: Record<string, string> = {
        submit: 'Listing submitted for review.',
        approve: 'Listing approved. It can now be published after the final readiness check.',
        publish: 'Course published to the Marketplace.',
        archive: 'Course listing archived. Existing order and entitlement history is preserved.',
        'return-to-draft': 'Listing returned to draft so the blockers can be corrected.',
      };
      setNotice(messages[action]);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The Marketplace status transition could not be completed.');
      await load();
    } finally {
      setBusyKey(null);
    }
  };

  function groupState(item: LaunchItem, group: typeof GROUPS[number]) {
    const map = new Map(item.checks.map(check => [check.code, check]));
    const free = item.pricePaise === 0;
    if (free && ['payment', 'seller', 'invoice'].includes(group.key)) {
      return { complete: true, optional: true, present: 0, total: 0, checks: [] as LaunchCheck[] };
    }
    const checks = group.codes.map(code => map.get(code)).filter((check): check is LaunchCheck => Boolean(check));
    if (checks.length === 0) return { complete: false, optional: false, present: 0, total: group.codes.length, checks: [] as LaunchCheck[] };
    const present = checks.filter(check => check.passed).length;
    const complete = checks.length === group.codes.length && present === checks.length;
    return { complete, optional: false, present, total: group.codes.length, checks };
  }

  function progress(item: LaunchItem) {
    const groups = GROUPS.map(group => groupState(item, group));
    const required = groups.filter(group => !group.optional);
    const complete = required.filter(group => group.complete).length;
    return { percent: required.length ? Math.round(complete * 100 / required.length) : 100, complete, total: required.length };
  }

  function nextBlocker(item: LaunchItem) {
    return item.checks.find(check => !check.passed);
  }

  function shouldShowFinanceLink(item: LaunchItem) {
    return item.checks.some(check => !check.passed && [
      'RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK', 'APPROVED_TAX_PROFILE',
      'EINVOICE_REVIEW', 'TAX_CALCULATION', 'GST_REGISTRATION',
      'PUBLISHED_INVOICE_TEMPLATE', 'REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION',
    ].includes(check.code));
  }

  return (
    <section className={styles.workspace} aria-labelledby="marketplace-launch-heading">
      <header className={styles.hero}>
        <div className={styles.heroIcon} aria-hidden="true"><span>↗</span></div>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>INSTITUTION · MARKETPLACE CONTROL</p>
          <h2 id="marketplace-launch-heading">Course launch checklist</h2>
          <p>One readiness path per course. The percentage reflects completed mandatory steps—not just saved fields. Publication is blocked until the server confirms every required check.</p>
        </div>
        <button type="button" className={styles.refresh} onClick={() => void load()} disabled={loading || busyKey !== null}>
          {loading ? 'Refreshing…' : '↻ Refresh'}
        </button>
      </header>

      {(error || notice) && (
        <div className={error ? styles.alertError : styles.alertNotice} role={error ? 'alert' : 'status'}>
          <span>{error || notice}</span>
          <button type="button" onClick={() => { setError(''); setNotice(''); }} aria-label="Dismiss message">×</button>
        </div>
      )}

      <div className={styles.summary}>
        <div className={styles.summaryCopy}>
          <span className={styles.summaryLabel}>Selected institution</span>
          <strong>{institutionName}</strong>
          <span className={styles.summaryHint}>Every listing below is scoped to this institution.</span>
        </div>
        <div className={styles.summaryStats}>
          <div><strong>{loading ? '—' : counts.total}</strong><span>Assigned courses</span></div>
          <div><strong>{loading ? '—' : counts.ready}</strong><span>Ready to publish</span></div>
          <div><strong>{loading ? '—' : counts.published}</strong><span>Currently published</span></div>
        </div>
      </div>

      {loading ? (
        <div className={styles.loadingList}><div /><div /><div /></div>
      ) : !data ? (
        <div className={styles.empty}><b>Readiness checklist unavailable</b><p>Retry the request. No publication action has been performed.</p><button type="button" onClick={() => void load()}>Try again</button></div>
      ) : !data.items.length ? (
        <div className={styles.empty}>
          <span className={styles.emptyIcon}>◎</span>
          <b>No assigned course releases yet</b>
          <p>Assign a platform-approved, shareable course release to this institution first. Once assigned, its listing readiness appears here.</p>
        </div>
      ) : (
        <div className={styles.courseList}>
          {data.items.map(item => {
            const draft = drafts[item.assignmentId] || initialDraft(item);
            const status = String(item.offerStatus || 'NOT_CONFIGURED').toUpperCase();
            const meter = progress(item);
            const blocker = nextBlocker(item);
            const editing = !item.offerId || ['DRAFT', 'REJECTED', 'ARCHIVED'].includes(status);
            const canSubmit = Boolean(item.offerId) && item.ready && ['DRAFT', 'REJECTED'].includes(status);
            const canApprove = Boolean(item.offerId) && item.ready && status === 'IN_REVIEW';
            const canPublish = Boolean(item.offerId) && item.ready && status === 'APPROVED';
            const canReturn = Boolean(item.offerId) && !item.ready && ['IN_REVIEW', 'APPROVED'].includes(status);
            return (
              <article className={styles.courseCard} key={item.assignmentId}>
                <header className={styles.courseHeader}>
                  <div className={styles.courseIdentity}>
                    <span className={styles.courseMark} aria-hidden="true">QE</span>
                    <div><h3>{item.courseName}</h3><p>Course #{item.courseId} · Assignment #{item.assignmentId}</p></div>
                  </div>
                  <div className={styles.statusCluster}>
                    <span className={styles.statusPill + ' ' + (status === 'PUBLISHED' ? styles.statusPublished : status === 'NOT_CONFIGURED' ? styles.statusDraft : styles.statusOther)}>{statusText(status)}</span>
                    {item.offerId ? <span className={styles.price}>{priceText(item.pricePaise)}</span> : <span className={styles.price}>Offer not created</span>}
                  </div>
                </header>

                <section className={styles.progressPanel}>
                  <div className={styles.progressTop}>
                    <div><span>Launch readiness</span><strong>{meter.percent}%</strong></div>
                    <span className={item.ready ? styles.readyTag : styles.blockedTag}>{item.ready ? 'Checks passed' : 'Action required'}</span>
                  </div>
                  <div className={styles.progressTrack} role="progressbar" aria-label={item.courseName + ' launch progress'} aria-valuemin={0} aria-valuemax={100} aria-valuenow={meter.percent}>
                    <span style={{ width: meter.percent + '%' }} />
                  </div>
                  <p>{meter.complete} of {meter.total} mandatory stages complete</p>
                </section>

                <div className={styles.stepper} aria-label="Course launch steps">
                  {GROUPS.map((group, index) => {
                    const state = groupState(item, group);
                    const isCurrent = !state.complete && !state.optional && !GROUPS.slice(0, index).every(previous => groupState(item, previous).complete || groupState(item, previous).optional);
                    return (
                      <section key={group.key} className={styles.step + (state.complete ? ' ' + styles.stepComplete : isCurrent ? ' ' + styles.stepCurrent : '')}>
                        <div className={styles.stepRail}>
                          <span className={styles.stepNumber}>{state.complete ? '✓' : group.number}</span>
                          {index < GROUPS.length - 1 && <span className={styles.stepConnector} />}
                        </div>
                        <div className={styles.stepBody}>
                          <div className={styles.stepHeading}>
                            <div><h4>{group.title}</h4><p>{group.subtitle}</p></div>
                            <span className={state.optional ? styles.stepOptional : state.complete ? styles.stepDone : styles.stepPending}>
                              {state.optional ? 'Not required' : state.complete ? 'Complete' : (state.present + '/' + state.total)}
                            </span>
                          </div>
                          {state.checks.length ? (
                            <div className={styles.checkList}>
                              {state.checks.map(check => (
                                <div className={styles.checkRow} key={check.code}>
                                  <span className={check.passed ? styles.checkPassed : styles.checkFailed}>{check.passed ? '✓' : '!'}</span>
                                  <div><strong>{check.label}</strong>{!check.passed && <p>{check.message}</p>}</div>
                                </div>
                              ))}
                            </div>
                          ) : state.optional ? (
                            <p className={styles.stepHint}>This is an intentionally free listing, so paid checkout, sale-invoice and revenue-share setup is not required.</p>
                          ) : item.offerId ? (
                            <p className={styles.stepHint}>The server has not returned the required checks for this stage. Refresh before trying to publish.</p>
                          ) : (
                            <p className={styles.stepHint}>This stage can be evaluated after the draft offer and price exist.</p>
                          )}
                          {group.key === 'offer' && editing && (
                            <div className={styles.offerForm}>
                              <label>Student-facing title<input value={draft.title} maxLength={200} onChange={event => updateDraft(item.assignmentId, { title: event.target.value })} /></label>
                              <label>Price in INR<input type="number" min="0" step="0.01" inputMode="decimal" placeholder="Enter price" value={draft.priceRupees} onChange={event => updateDraft(item.assignmentId, { priceRupees: event.target.value })} /><small>Set ₹0.00 only for a deliberately free course. Currency is locked to INR.</small></label>
                              <label className={styles.descriptionField}>Marketplace description<textarea rows={3} maxLength={5000} value={draft.description} onChange={event => updateDraft(item.assignmentId, { description: event.target.value })} placeholder="Describe the course for prospective learners." /></label>
                              <div className={styles.formActions}>
                                <button type="button" className={styles.primaryButton} disabled={busyKey !== null || !item.checks.filter(check => ['INSTITUTION_ACTIVE', 'COURSE_ACTIVE', 'ASSIGNMENT_AND_RELEASE', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT'].includes(check.code)).every(check => check.passed)} onClick={() => void saveDraft(item)}>
                                  {busyKey === 'save-' + item.assignmentId ? 'Saving draft…' : item.offerId ? 'Save draft terms' : 'Create draft offer'}
                                </button>
                                <span>Saving a draft never publishes it.</span>
                              </div>
                            </div>
                          )}
                          {group.key === 'seller' && shouldShowFinanceLink(item) && <a className={styles.actionLink} href="/finance">Open HRMS Finance & Billing <span aria-hidden="true">↗</span></a>}
                          {group.key === 'invoice' && shouldShowFinanceLink(item) && <a className={styles.actionLink} href="/finance">Review invoice template and revenue-share policy <span aria-hidden="true">↗</span></a>}
                          {group.key === 'course' && !item.ready && blocker && ['CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT', 'ASSIGNMENT_AND_RELEASE'].includes(blocker.code) && <p className={styles.stepHint}>Resolve this in the course release / content-quality workflow, then refresh this checklist.</p>}
                        </div>
                      </section>
                    );
                  })}
                </div>

                {blocker && !item.ready && (
                  <div className={styles.nextAction}>
                    <span className={styles.nextIcon} aria-hidden="true">→</span>
                    <div><strong>Next required action</strong><p>{blocker.label}: {blocker.message}</p></div>
                    {shouldShowFinanceLink(item) && <a href="/finance">Go to Finance</a>}
                  </div>
                )}

                <footer className={styles.courseFooter}>
                  <div><span className={styles.footerLabel}>Listing workflow</span><p>{item.ready ? 'All configured checks pass. Continue with the status action below.' : 'The next step is highlighted above. The API rechecks readiness during each transition.'}</p></div>
                  <div className={styles.footerActions}>
                    {canSubmit && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'submit')}>{busyKey === 'submit-' + item.assignmentId ? 'Submitting…' : 'Submit for review'}</button>}
                    {canApprove && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'approve')}>{busyKey === 'approve-' + item.assignmentId ? 'Approving…' : 'Approve listing'}</button>}
                    {canPublish && <button type="button" className={styles.primaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'publish')}>{busyKey === 'publish-' + item.assignmentId ? 'Publishing…' : 'Publish to Marketplace'}</button>}
                    {canReturn && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'return-to-draft')}>Return to draft</button>}
                    {status === 'PUBLISHED' && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'archive')}>{busyKey === 'archive-' + item.assignmentId ? 'Archiving…' : 'Archive listing'}</button>}
                    {!canSubmit && !canApprove && !canPublish && status !== 'PUBLISHED' && !canReturn && <span className={styles.footerHint}>{status === 'IN_REVIEW' ? 'Awaiting a clean checklist or an explicit return to draft.' : status === 'APPROVED' ? 'Ready checks must pass before publication.' : 'Finish the required stages to unlock submission.'}</span>}
                  </div>
                </footer>
              </article>
            );
          })}
        </div>
      )}
      <p className={styles.disclaimer}>A completed checklist is not a legal opinion. HRMS must use the seller’s approved tax treatment and reviewed invoice setup. Payments remain blocked when e-invoicing is marked REQUIRED but the supported IRP/IRN path is not enabled.</p>
    </section>
  );
}
