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
  listingTitle?: string;
  description?: string;
  offerStatus: string;
  pricePaise?: number | null;
  currency?: string;
  ready: boolean;
  checks: LaunchCheck[];
  blockingIssues: string[];
  message: string;
};
type AvailableRelease = {
  release_id: number;
  course_id: number;
  release_version: number;
  contentCurrent: boolean;
  course_code: string;
  course_name: string;
  source_institution_id: number;
  source_institution_code: string;
  source_institution_name: string;
};
type QualityReviewItem = {
  release_id: number;
  course_id: number;
  release_version: number;
  release_state: string;
  content_fingerprint: string;
  contentCurrent: boolean;
  submitted_at?: string | null;
  course_code: string;
  course_name: string;
  source_institution_id: number;
  source_institution_code: string;
  source_institution_name: string;
  active_subject_count: number;
  subjects_with_published_lessons: number;
  published_chapters: number;
  published_lessons: number;
};
type PackageRightsReview = {
  import_id: number;
  package_id: string;
  package_version: string;
  course_id: number;
  release_id: number;
  source_institution_id: number;
  source_institution_code: string;
  source_institution_name: string;
  course_name: string;
  source_rights_status: string;
  rights_evidence_url?: string | null;
  rights_reviewed_at?: string | null;
  rights_review_notes?: string | null;
};
type PackageRightsDraft = { status: string; evidenceUrl: string; notes: string };
type LaunchResponse = {
  institutionId: number;
  institutionName: string;
  items: LaunchItem[];
  total: number;
  readyCount: number;
  blockedCount: number;
  availableReleases?: AvailableRelease[];
  availableCount?: number;
  qualityReviewItems?: QualityReviewItem[];
  qualityReviewCount?: number;
  coursePackageImports?: PackageRightsReview[];
  coursePackageImportCount?: number;
};
type Draft = { title: string; description: string; priceRupees: string };

const GROUPS = [
  { key: 'course', number: '01', title: 'Course content, rights & quality', subtitle: 'The course is active, imported source rights are cleared where applicable, mapped subjects have published learning content, and HRMS quality review is complete.', codes: ['INSTITUTION_ACTIVE', 'COURSE_ACTIVE', 'CONTENT_QUALITY_REVIEW', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT', 'SOURCE_RIGHTS_CLEARANCE'] },
  { key: 'release', number: '02', title: 'Origin approval & assignment', subtitle: 'The source institution approves the reviewed release, then HRMS assigns that exact shareable release to this institution.', codes: ['ORIGIN_RELEASE_APPROVAL', 'ASSIGNMENT_AND_RELEASE'] },
  { key: 'offer', number: '03', title: 'Listing details', subtitle: 'Set the student-facing title, description, currency and explicit price.', codes: ['OFFER_TERMS'] },
  { key: 'payment', number: '04', title: 'Payment setup', subtitle: 'Paid courses require working Razorpay credentials and the signed course-payment webhook.', codes: ['RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK'] },
  { key: 'seller', number: '05', title: 'Seller & tax', subtitle: 'Approve the seller and tax treatment, GST registration where required, and e-invoice applicability.', codes: ['APPROVED_TAX_PROFILE', 'EINVOICE_REVIEW', 'TAX_CALCULATION', 'GST_REGISTRATION'] },
  { key: 'invoice', number: '06', title: 'Invoice & revenue share', subtitle: 'Publish an invoice template; confirm effective shares fit the after-tax pool, including approved discounts.', codes: ['PUBLISHED_INVOICE_TEMPLATE', 'REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION', 'DISCOUNT_POOL_COMPATIBILITY'] },
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
    title: item.listingTitle || item.courseName || '',
    description: item.description || '',
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
  const [reviewNotes, setReviewNotes] = useState<Record<number, string>>({});
  const [packageRightsDrafts, setPackageRightsDrafts] = useState<Record<number, PackageRightsDraft>>({});
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
      setReviewNotes(previous => {
        const next = { ...previous };
        for (const release of result.qualityReviewItems || []) {
          if (next[release.release_id] === undefined) next[release.release_id] = '';
        }
        return next;
      });
      setPackageRightsDrafts(previous => {
        const next = { ...previous };
        for (const item of result.coursePackageImports || []) {
          if (next[item.import_id] === undefined) {
            const status = ['CLEARED_FOR_INTERNAL_USE','CLEARED_FOR_PUBLIC_REPOSITORY','DO_NOT_REDISTRIBUTE']
              .includes(item.source_rights_status) ? item.source_rights_status : '';
            next[item.import_id] = {
              status,
              evidenceUrl: item.rights_evidence_url || '',
              notes: item.rights_review_notes || '',
            };
          }
        }
        return next;
      });
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

  async function savePackageRights(item: PackageRightsReview) {
    const draft = packageRightsDrafts[item.import_id];
    if (!draft || !draft.status) {
      setError('Choose a package-rights decision before saving.');
      return;
    }
    if (draft.notes.trim().length < 5) {
      setError('Add reviewer notes of at least five characters.');
      return;
    }
    if (draft.status !== 'DO_NOT_REDISTRIBUTE' && !/^https:\/\//i.test(draft.evidenceUrl.trim())) {
      setError('A clearance decision requires an HTTPS rights-evidence URL.');
      return;
    }
    setBusyKey('rights-' + item.import_id);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId
        + '/course-package-imports/' + item.import_id + '/rights-review', {
        method: 'POST',
        body: JSON.stringify({
          status: draft.status,
          evidenceUrl: draft.evidenceUrl.trim(),
          notes: draft.notes.trim(),
        }),
      });
      setNotice('Package rights decision saved. All versions of this source-owned course now use this audited rights status.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Package rights review could not be saved.');
    } finally {
      setBusyKey(null);
    }
  }

  const counts = useMemo(() => {
    const items = data?.items || [];
    return {
      total: items.length,
      ready: items.filter(item => item.ready && item.offerStatus === 'APPROVED').length,
      published: items.filter(item => item.offerStatus === 'PUBLISHED').length,
    };
  }, [data]);

  const updateDraft = (assignmentId: number, patch: Partial<Draft>) => {
    setDrafts(current => ({
      ...current,
      [assignmentId]: { ...(current[assignmentId] || { title: '', description: '', priceRupees: '' }), ...patch },
    }));
  };

  const reviewQuality = async (release: QualityReviewItem, approved: boolean) => {
    const notes = (reviewNotes[release.release_id] || '').trim();
    if (notes.length < 3 || notes.length > 1000) {
      setError('Add a review note between 3 and 1,000 characters before recording a quality decision.');
      return;
    }
    if (approved && (!release.contentCurrent || release.active_subject_count < 1
      || release.subjects_with_published_lessons !== release.active_subject_count
      || release.published_chapters < 1 || release.published_lessons < 1)) {
      setError('This release is not ready for quality approval. Refresh the checklist and ensure every active subject has published lessons.');
      return;
    }
    setBusyKey('quality-' + release.release_id);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/course-releases/' + release.release_id + '/quality-review', {
        method: 'POST',
        body: JSON.stringify({ approved, notes }),
      });
      setNotice(approved
        ? 'Content quality approved. The source institution must still complete its origin approval before the release can be assigned.'
        : 'Release returned for correction. The source institution must update the content and submit a fresh release.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The course release quality decision could not be saved.');
    } finally {
      setBusyKey(null);
    }
  };

  const assignRelease = async (release: AvailableRelease) => {
    if (!release.contentCurrent) {
      setError('This release no longer matches its reviewed content fingerprint. The source institution must submit a fresh release before it can be assigned.');
      return;
    }
    setBusyKey('assign-' + release.release_id);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId + '/course-releases/' + release.release_id + '/assignment', {
        method: 'PUT',
        body: JSON.stringify({ enabled: true }),
      });
      setNotice(release.course_name + ' assigned to this institution. Its launch checklist is now available below.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The approved course release could not be assigned.');
    } finally {
      setBusyKey(null);
    }
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
      'DISCOUNT_POOL_COMPATIBILITY',
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

      {!loading && data && (data.coursePackageImports || []).length > 0 && (
        <section className={styles.rightsPanel}>
          <header className={styles.availableHeader}>
            <div>
              <span className={styles.summaryLabel}>HRMS source-rights gate</span>
              <h3>Imported packages requiring rights review</h3>
              <p>Record an evidence-backed decision for the source package. Clearance follows the source course identity, including future draft versions; it does not itself approve content quality or publish the course.</p>
            </div>
            <span className={styles.rightsCount}>{data.coursePackageImports.length} packages</span>
          </header>
          <div className={styles.rightsList}>
            {(data.coursePackageImports || []).map(item => {
              const draft = packageRightsDrafts[item.import_id] || {
                status: '', evidenceUrl: item.rights_evidence_url || '', notes: item.rights_review_notes || '',
              };
              const busy = busyKey === 'rights-' + item.import_id;
              return (
                <article className={styles.rightsRow} key={item.import_id}>
                  <div className={styles.availableMark} aria-hidden="true">§</div>
                  <div className={styles.rightsCopy}>
                    <strong>{item.course_name}</strong>
                    <small>Package {item.package_id} · v{item.package_version} · Source: {item.source_institution_name} ({item.source_institution_code})</small>
                    <div className={styles.rightsStatusLine}>
                      <span>Current: {statusText(item.source_rights_status || 'UNCONFIRMED')}</span>
                      {item.rights_reviewed_at ? <span>Reviewed {new Date(item.rights_reviewed_at).toLocaleString('en-IN')}</span> : <span>Not reviewed by HRMS</span>}
                    </div>
                    <div className={styles.rightsFields}>
                      <label className={styles.reviewNote}>Rights decision
                        <select value={draft.status} disabled={busyKey !== null}
                          onChange={event => setPackageRightsDrafts(previous => ({
                            ...previous, [item.import_id]: { ...draft, status: event.target.value },
                          }))}>
                          <option value="">Choose a decision…</option>
                          <option value="CLEARED_FOR_INTERNAL_USE">Cleared for internal use</option>
                          <option value="CLEARED_FOR_PUBLIC_REPOSITORY">Cleared for public repository</option>
                          <option value="DO_NOT_REDISTRIBUTE">Do not redistribute</option>
                        </select>
                      </label>
                      <label className={styles.reviewNote}>Evidence URL {draft.status !== 'DO_NOT_REDISTRIBUTE' ? '(required for clearance)' : '(optional)'}
                        <input type="url" inputMode="url" maxLength={2000} value={draft.evidenceUrl}
                          disabled={busyKey !== null}
                          onChange={event => setPackageRightsDrafts(previous => ({
                            ...previous, [item.import_id]: { ...draft, evidenceUrl: event.target.value },
                          }))}
                          placeholder="https://…"/>
                      </label>
                    </div>
                    <label className={styles.reviewNote}>Reviewer notes (required)
                      <textarea rows={2} maxLength={1000} value={draft.notes} disabled={busyKey !== null}
                        onChange={event => setPackageRightsDrafts(previous => ({
                          ...previous, [item.import_id]: { ...draft, notes: event.target.value },
                        }))}
                        placeholder="Record the rights basis, license scope, and any restrictions."/>
                    </label>
                    <div className={styles.rightsActions}>
                      <button type="button" className={styles.primaryButton} disabled={busyKey !== null
                        || !draft.status || draft.notes.trim().length < 5
                        || (draft.status !== 'DO_NOT_REDISTRIBUTE' && !/^https:\/\//i.test(draft.evidenceUrl.trim()))}
                        onClick={() => void savePackageRights(item)}>
                        {busy ? 'Saving rights review…' : 'Save rights decision'}
                      </button>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      )}

      {!loading && data && (data.qualityReviewItems || []).length > 0 && (
        <section className={styles.qualityPanel}>
          <header className={styles.availableHeader}>
            <div><span className={styles.summaryLabel}>HRMS content gate</span><h3>Courses waiting for quality review</h3>
              <p>Review the source institution’s submitted release. This is separate from origin approval and Marketplace publication.</p></div>
            <span className={styles.qualityCount}>{data.qualityReviewItems?.length || 0} awaiting review</span>
          </header>
          <div className={styles.qualityList}>
            {(data.qualityReviewItems || []).map(release => {
              const qualityReady = release.contentCurrent && release.active_subject_count > 0
                && release.subjects_with_published_lessons === release.active_subject_count
                && release.published_chapters > 0 && release.published_lessons > 0;
              return (
                <article className={styles.qualityRow} key={release.release_id}>
                  <div className={styles.availableMark} aria-hidden="true">✓</div>
                  <div className={styles.qualityCopy}>
                    <strong>{release.course_name} <span>v{release.release_version}</span></strong>
                    <small>Source: {release.source_institution_name} · {release.source_institution_code} · Release #{release.release_id}</small>
                    <div className={styles.qualityMetrics}>
                      <span>{release.active_subject_count} active subjects</span>
                      <span>{release.subjects_with_published_lessons} with lessons</span>
                      <span>{release.published_chapters} chapters</span>
                      <span>{release.published_lessons} lessons</span>
                    </div>
                    {!release.contentCurrent && <p className={styles.availableWarning}>Course content changed after submission. Ask the source institution to submit a fresh release.</p>}
                    <label className={styles.reviewNote}>Quality review note
                      <textarea rows={2} maxLength={1000} value={reviewNotes[release.release_id] || ''}
                        onChange={event => setReviewNotes(previous => ({ ...previous, [release.release_id]: event.target.value }))}
                        placeholder="Record what was checked: syllabus coverage, published lessons, content integrity…" />
                    </label>
                    <div className={styles.qualityActions}>
                      <button type="button" className={styles.primaryButton} disabled={busyKey !== null || !qualityReady || (reviewNotes[release.release_id] || '').trim().length < 3}
                        onClick={() => void reviewQuality(release, true)}>
                        {busyKey === 'quality-' + release.release_id ? 'Saving decision…' : 'Approve quality'}
                      </button>
                      <button type="button" className={styles.secondaryButton} disabled={busyKey !== null || (reviewNotes[release.release_id] || '').trim().length < 3}
                        onClick={() => void reviewQuality(release, false)}>Return for correction</button>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
          <p className={styles.qualityFootnote}>Quality approval does not make the course purchasable by itself. The originating institution must complete release approval; HRMS can then assign the shareable release and continue through the listing checklist.</p>
        </section>
      )}

      {!loading && data && (data.availableReleases || []).length > 0 && (
        <section className={styles.availablePanel}>
          <header className={styles.availableHeader}>
            <div><span className={styles.summaryLabel}>HRMS release catalogue</span><h3>Available approved courses</h3>
              <p>Assign an origin-approved, shareable course release to this institution. Assignment does not publish or sell it.</p></div>
            <span className={styles.availableCount}>{data.availableReleases?.length || 0} available</span>
          </header>
          <div className={styles.availableList}>
            {(data.availableReleases || []).map(release => (
              <article className={styles.availableRow} key={release.release_id}>
                <div className={styles.availableMark} aria-hidden="true">↗</div>
                <div className={styles.availableCopy}>
                  <strong>{release.course_name}</strong>
                  <span>{release.course_code} · Release v{release.release_version}</span>
                  <small>Source: {release.source_institution_name} · {release.source_institution_code}</small>
                  {!release.contentCurrent && <small className={styles.availableWarning}>Reviewed content has changed. The source institution must resubmit this release.</small>}
                </div>
                <button type="button" className={styles.secondaryButton}
                  disabled={busyKey !== null || !release.contentCurrent}
                  onClick={() => void assignRelease(release)}>
                  {busyKey === 'assign-' + release.release_id ? 'Assigning…' : 'Assign to institution'}
                </button>
              </article>
            ))}
          </div>
        </section>
      )}

      {loading ? (
        <div className={styles.loadingList}><div /><div /><div /></div>
      ) : !data ? (
        <div className={styles.empty}><b>Readiness checklist unavailable</b><p>Retry the request. No publication action has been performed.</p><button type="button" onClick={() => void load()}>Try again</button></div>
      ) : !data.items.length && !(data.availableReleases || []).length ? (
        <div className={styles.empty}>
          <span className={styles.emptyIcon}>◎</span>
          <b>No assigned course releases yet</b>
          <p>There are no currently available shareable course releases to assign. The source institution must complete its release approval and HRMS content-quality review first.</p>
        </div>
      ) : !data.items.length ? (
        <div className={styles.empty}>
          <span className={styles.emptyIcon}>✓</span>
          <b>No course assigned yet</b>
          <p>Choose an approved course release above. It will then appear here with its own launch checklist and completion percentage.</p>
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
                                <button type="button" className={styles.primaryButton} disabled={busyKey !== null || !item.checks.filter(check => ['INSTITUTION_ACTIVE', 'COURSE_ACTIVE', 'CONTENT_QUALITY_REVIEW', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT', 'ORIGIN_RELEASE_APPROVAL', 'ASSIGNMENT_AND_RELEASE'].includes(check.code)).every(check => check.passed)} onClick={() => void saveDraft(item)}>
                                  {busyKey === 'save-' + item.assignmentId ? 'Saving draft…' : item.offerId ? 'Save draft terms' : 'Create draft offer'}
                                </button>
                                <span>Saving a draft never publishes it.</span>
                              </div>
                            </div>
                          )}
                          {group.key === 'seller' && shouldShowFinanceLink(item) && <a className={styles.actionLink} href="/finance">Open HRMS Finance & Billing <span aria-hidden="true">↗</span></a>}
                          {group.key === 'invoice' && shouldShowFinanceLink(item) && <a className={styles.actionLink} href="/finance">Review invoice template and revenue-share policy <span aria-hidden="true">↗</span></a>}
                          {group.key === 'course' && !item.ready && blocker && ['CONTENT_QUALITY_REVIEW', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT'].includes(blocker.code) && <p className={styles.stepHint}>Resolve this in the course release / content-quality workflow, then refresh this checklist.</p>}
                          {group.key === 'release' && !item.ready && item.checks.some(check => !check.passed && ['ORIGIN_RELEASE_APPROVAL', 'ASSIGNMENT_AND_RELEASE'].includes(check.code)) && <p className={styles.stepHint}>After HRMS quality approval, the source institution admin must approve the release in the separate Institution portal. HRMS can assign it to this institution only after it becomes SHAREABLE.</p>}
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
