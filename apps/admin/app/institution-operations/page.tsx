'use client';

import Link from 'next/link';
import { useCallback, useContext, useEffect, useState } from 'react';
import AdminSidebar from '../components/AdminSidebar';
import { apiFetch } from '../components/apiClient';
import { useUiTranslation } from '../i18n/useUiTranslation';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import styles from './InstitutionOperations.module.css';

type Overview = {
  institution_id?: number;
  institution_code?: string;
  institution_name?: string;
  active_learners?: number;
  all_memberships?: number;
  authorized_courses?: number;
  completed_lessons?: number;
  lessons_with_activity?: number;
  current_period_billable_events?: number;
  current_period_amount_paise?: number;
};
type Learner = {
  learner_reference?: string;
  display_name?: string;
  class_code?: string;
  board?: string;
  city?: string;
  state?: string;
  language?: string;
  membership_status?: string;
  joined_at?: string;
  completed_lessons?: number;
  lessons_with_activity?: number;
  average_lesson_progress_percent?: number;
};
type Course = {
  course_id?: number;
  code?: string;
  display_name?: string;
  entitlement_start?: string;
  entitlement_end?: string | null;
  published_lessons?: number;
  institution_learners?: number;
};
type Release = {
  release_id: number;
  course_id: number;
  course_code?: string;
  display_name?: string;
  release_version: number;
  release_state: string;
  quality_review_notes?: string | null;
  origin_rejection_reason?: string | null;
  submitted_at?: string | null;
  quality_reviewed_at?: string | null;
  origin_approved_at?: string | null;
  shareable_at?: string | null;
};
type RosterResponse = { learners?: Learner[]; total?: number; page?: number; pageSize?: number };

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await apiFetch(path, {
    ...init,
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { Accept: 'application/json', ...(init.body ? { 'Content-Type': 'application/json' } : {}), ...init.headers },
  });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(String(body.message || body.detail || body.error || 'Request failed (' + response.status + ')'));
  return body as T;
}

function count(value: unknown) {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n.toLocaleString('en-IN') : '0';
}
function money(value: unknown) {
  const amount = Number(value ?? 0) / 100;
  return Number.isFinite(amount) ? new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 }).format(amount) : '—';
}
function dateLabel(value?: string | null) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString('en-IN');
}
function stateLabel(value: string) {
  return String(value || 'UNKNOWN').replaceAll('_', ' ').toLowerCase().replace(/\b\w/g, letter => letter.toUpperCase());
}

export default function InstitutionOperationsPage() {
  const t = useUiTranslation();
  const { permissions } = useContext(AdminWorkspaceContext);
  const canCreateCourse = permissions.includes('CONTENT_CREATE') && permissions.includes('CONTENT_EDIT');
  const canManageReleases = permissions.includes('INSTITUTION_RELEASE_MANAGE');
  const [view, setView] = useState<'overview' | 'roster' | 'courses'>('overview');
  const [focusCourseId, setFocusCourseId] = useState('');
  const [overview, setOverview] = useState<Overview | null>(null);
  const [learners, setLearners] = useState<Learner[]>([]);
  const [rosterTotal, setRosterTotal] = useState(0);
  const [rosterPage, setRosterPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [query, setQuery] = useState('');
  const [appliedQuery, setAppliedQuery] = useState('');
  const [courses, setCourses] = useState<Course[]>([]);
  const [releases, setReleases] = useState<Release[]>([]);
  const [loading, setLoading] = useState(true);
  const [rosterLoading, setRosterLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busyRelease, setBusyRelease] = useState<number | null>(null);

  const loadOverviewAndCourses = useCallback(async () => {
    const [summary, authorised, sourceReleases] = await Promise.all([
      request<Overview>('/api/v1/admin/institution-operations/overview'),
      request<Course[]>('/api/v1/admin/institution-operations/courses'),
      request<Release[]>('/api/v1/admin/institution-operations/course-releases'),
    ]);
    setOverview(summary);
    setCourses(Array.isArray(authorised) ? authorised : []);
    setReleases(Array.isArray(sourceReleases) ? sourceReleases : []);
  }, []);

  const loadRoster = useCallback(async () => {
    setRosterLoading(true);
    try {
      const params = new URLSearchParams({
        page: String(rosterPage), pageSize: String(pageSize), query: appliedQuery,
      });
      const result = await request<RosterResponse>('/api/v1/admin/institution-operations/roster?' + params.toString());
      setLearners(Array.isArray(result.learners) ? result.learners : []);
      setRosterTotal(Number(result.total || 0));
    } finally {
      setRosterLoading(false);
    }
  }, [rosterPage, pageSize, appliedQuery]);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      await Promise.all([loadOverviewAndCourses(), loadRoster()]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('admin.institutionOperations.refreshFailed'));
    } finally {
      setLoading(false);
    }
  }, [loadOverviewAndCourses, loadRoster, t]);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    if (params.get('view') === 'courses') setView('courses');
    const requestedCourse = params.get('courseId') || '';
    if (/^[0-9]+$/.test(requestedCourse)) setFocusCourseId(requestedCourse);
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  useEffect(() => {
    if (view !== 'courses' || !focusCourseId) return;
    const target = document.querySelector('[data-release-course-id="' + focusCourseId + '"]');
    if (target) target.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }, [view, focusCourseId, releases]);

  async function releaseAction(release: Release, action: 'submit' | 'approve') {
    setBusyRelease(release.release_id);
    setError('');
    setNotice('');
    try {
      if (action === 'submit') {
        const result = await request<{ releaseVersion?: number }>(
          '/api/v1/admin/institution-operations/courses/' + release.course_id + '/release-requests',
          { method: 'POST' },
        );
        setNotice(t('admin.institutionOperations.submitted') + ' ' + t('admin.institutionOperations.existingAccess') +
          (result.releaseVersion ? ' · Release v' + result.releaseVersion : ''));
      } else {
        await request('/api/v1/admin/institution-operations/course-releases/' + release.release_id + '/approve', { method: 'POST' });
        setNotice(t('admin.institutionOperations.approved') + ' ' + t('admin.institutionOperations.existingAccess'));
      }
      await loadOverviewAndCourses();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('admin.institutionOperations.actionFailed'));
    } finally {
      setBusyRelease(null);
    }
  }

  const latestReleases = releases.filter((release, index, all) =>
    all.findIndex(candidate => candidate.course_id === release.course_id) === index,
  );
  const maxPages = Math.max(1, Math.ceil(rosterTotal / pageSize));

  return (
    <main className="admin-shell">
      <AdminSidebar active="institution" />
      <section className="admin-main">
        <header className="admin-top">
          <div>
            <span className="admin-kicker">{t('admin.institutionOperations.eyebrow')}</span>
            <h1>{t('admin.institutionOperations.title')}</h1>
            <p>{overview?.institution_name ? overview.institution_name + ' · ' : ''}{t('admin.institutionOperations.description')}</p>
          </div>
          <div className={styles.headerActions}>
            <Link href="/content" className="button button-dark">{t('admin.institutionOperations.contentStudio')}</Link>
            <button type="button" className="admin-refresh" onClick={() => void refresh()} disabled={loading}>
              {loading ? '…' : '↻ ' + t('admin.institutionOperations.refresh')}
            </button>
          </div>
        </header>

        {error ? <div className="feedback" role="alert">{error}</div> : null}
        {notice ? <div className={styles.notice} role="status">{notice}</div> : null}

        <nav className={styles.tabs} aria-label={t('admin.institutionOperations.title')}>
          {(['overview', 'roster', 'courses'] as const).map(id => (
            <button key={id} type="button" className={view === id ? styles.activeTab : ''} onClick={() => setView(id)}>
              {t('admin.institutionOperations.' + id)}
            </button>
          ))}
        </nav>

        {view === 'overview' ? (
          <>
            <section className={styles.metrics} aria-label={t('admin.institutionOperations.overview')}>
              {[
                [t('admin.institutionOperations.activeLearners'), count(overview?.active_learners), '↗'],
                [t('admin.institutionOperations.allMemberships'), count(overview?.all_memberships), '◎'],
                [t('admin.institutionOperations.authorisedCourses'), count(overview?.authorized_courses), '▤'],
                [t('admin.institutionOperations.completedLessons'), count(overview?.completed_lessons), '✓'],
                [t('admin.institutionOperations.billableEvents'), count(overview?.current_period_billable_events), '₹'],
                [t('admin.institutionOperations.billableAmount'), t('admin.institutionOperations.currentPeriodAmount', { amount: money(overview?.current_period_amount_paise) }), '₹'],
              ].map(([label, value, icon]) => (
                <article className={styles.metric} key={label}>
                  <span aria-hidden="true">{icon}</span><small>{label}</small><strong>{value}</strong>
                </article>
              ))}
            </section>
            <section className={styles.panel}>
              <div className={styles.panelHeader}>
                <div><h2>{t('admin.institutionOperations.lessonsWithActivity')}</h2><p>{t('admin.institutionOperations.description')}</p></div>
                <strong className={styles.bigNumber}>{count(overview?.lessons_with_activity)}</strong>
              </div>
              <div className={styles.quickLinks}>
                <Link href="/content">{t('admin.institutionOperations.contentStudio')} <span>↗</span></Link>
                {canCreateCourse ? <Link href="/content/create">{t('admin.institutionOperations.createCourse')} <span>↗</span></Link> : null}
                <Link href="/content/import">{t('admin.institutionOperations.importCourse')} <span>↗</span></Link>
                <Link href="/notifications">{t('admin.institutionOperations.notifications')} <span>↗</span></Link>
              </div>
              <p className={styles.note}>{t('admin.institutionOperations.notificationsHelp')}</p>
            </section>
            <section className={styles.panel}>
              <div className={styles.panelHeader}><div><h2>{t('admin.institutionOperations.courseReleases')}</h2><p>{t('admin.institutionOperations.courseReleasesDescription')}</p></div><span className={styles.statusCount}>{t('admin.institutionOperations.coursesCount', { count: latestReleases.length })}</span></div>
              <ReleaseList releases={latestReleases.slice(0, 4)} loading={loading} busyRelease={busyRelease} onAction={releaseAction} t={t} canManageReleases={canManageReleases} focusCourseId={focusCourseId} compact />
              {latestReleases.length > 4 ? <button type="button" className={styles.textButton} onClick={() => setView('courses')}>{t('admin.institutionOperations.viewAllReleases')} →</button> : null}
            </section>
          </>
        ) : view === 'roster' ? (
          <section className={styles.panel}>
            <div className={styles.panelHeader}><div><h2>{t('admin.institutionOperations.rosterTitle')}</h2><p>{t('admin.institutionOperations.rosterDescription')}</p></div><span className={styles.statusCount}>{t('admin.institutionOperations.learnerCount', { count: count(rosterTotal) })}</span></div>
            <form className={styles.searchRow} onSubmit={event => { event.preventDefault(); setRosterPage(1); setAppliedQuery(query.trim()); }}>
              <input value={query} onChange={event => setQuery(event.target.value)} placeholder={t('admin.institutionOperations.searchLearners')} aria-label={t('admin.institutionOperations.searchLearners')} />
              <button className="button button-dark" type="submit">{t('admin.institutionOperations.searchButton')}</button>
            </form>
            <div className={styles.tableScroll}>
              <table className={styles.table}>
                <thead><tr><th>{t('admin.institutionOperations.learner')}</th><th>{t('admin.institutionOperations.class')}</th><th>{t('admin.institutionOperations.location')}</th><th>{t('admin.institutionOperations.membership')}</th><th>{t('admin.institutionOperations.progress')}</th></tr></thead>
                <tbody>
                  {learners.map((learner, index) => <tr key={learner.learner_reference || String(index)}>
                    <td><strong>{learner.display_name || '—'}</strong><small>{learner.learner_reference || ''}</small></td>
                    <td>{learner.class_code || '—'}<small>{learner.board || learner.language || ''}</small></td>
                    <td>{[learner.city, learner.state].filter(Boolean).join(', ') || '—'}</td>
                    <td><span className={learner.membership_status === 'ACTIVE' ? styles.activeStatus : styles.status}>{stateLabel(learner.membership_status || 'UNKNOWN')}</span><small>{t('admin.institutionOperations.joined')} {dateLabel(learner.joined_at)}</small></td>
                    <td><strong>{count(learner.average_lesson_progress_percent)}%</strong><small>{t('admin.institutionOperations.progressCounts', { completed: count(learner.completed_lessons), active: count(learner.lessons_with_activity) })}</small></td>
                  </tr>)}
                  {!rosterLoading && learners.length === 0 ? <tr><td colSpan={5} className={styles.empty}>{t('admin.institutionOperations.noLearners')}</td></tr> : null}
                </tbody>
              </table>
              {rosterLoading ? <p className={styles.loading}>{t('admin.institutionOperations.loadingRoster')}</p> : null}
            </div>
            <p className={styles.note}>{t('admin.institutionOperations.privacyNote')}</p>
            <div className={styles.pagination}>
              <label>{t('admin.institutionOperations.rowsPerPage')}
                <select value={pageSize} onChange={event => { setPageSize(Number(event.target.value)); setRosterPage(1); }}>
                  {[10, 20, 30, 50].map(size => <option key={size} value={size}>{size}</option>)}
                </select>
              </label>
              <span>{t('admin.institutionOperations.page', { page: rosterPage })} · {t('admin.institutionOperations.learnerCount', { count: count(rosterTotal) })}</span>
              <div>
                <button type="button" disabled={rosterPage <= 1} onClick={() => setRosterPage(page => Math.max(1, page - 1))}>{t('admin.institutionOperations.previous')}</button>
                <button type="button" disabled={rosterPage >= maxPages} onClick={() => setRosterPage(page => Math.min(maxPages, page + 1))}>{t('admin.institutionOperations.next')}</button>
              </div>
            </div>
          </section>
        ) : (
          <>
            <section className={styles.panel}>
              <div className={styles.panelHeader}><div><h2>{t('admin.institutionOperations.authorisedCourses')}</h2><p>{t('admin.institutionOperations.availableCoursesDescription')}</p></div><span className={styles.statusCount}>{t('admin.institutionOperations.coursesCount', { count: courses.length })}</span></div>
              {courses.length ? <div className={styles.courseGrid}>{courses.map(course => <article key={course.course_id || course.code}>
                <span className={styles.courseGlyph}>▤</span><h3>{course.display_name || course.code}</h3><small>{course.code}</small>
                <div className={styles.courseStats}><span>{t('admin.institutionOperations.publishedLessonsCount')}<strong>{count(course.published_lessons)}</strong></span><span>{t('admin.institutionOperations.institutionLearnersCount')}<strong>{count(course.institution_learners)}</strong></span></div>
                {course.entitlement_end ? <small>{t('admin.institutionOperations.accessUntil', { date: dateLabel(course.entitlement_end) })}</small> : null}
              </article>)}</div> : <p className={styles.empty}>{t('admin.institutionOperations.noCoursesAvailable')}</p>}
            </section>
            <section className={styles.panel}>
              <div className={styles.panelHeader}><div><h2>{t('admin.institutionOperations.courseReleases')}</h2><p>{t('admin.institutionOperations.courseReleasesDescription')}</p></div><span className={styles.statusCount}>{t('admin.institutionOperations.coursesCount', { count: latestReleases.length })}</span></div>
              <ReleaseList releases={latestReleases} loading={loading} busyRelease={busyRelease} onAction={releaseAction} t={t} canManageReleases={canManageReleases} focusCourseId={focusCourseId} />
            </section>
          </>
        )}
      </section>
    </main>
  );
}

function ReleaseList({ releases, loading, busyRelease, onAction, t, canManageReleases, focusCourseId, compact = false }: {
  releases: Release[];
  loading: boolean;
  busyRelease: number | null;
  onAction: (release: Release, action: 'submit' | 'approve') => void;
  t: (key: string, values?: Record<string, string | number>) => string;
  canManageReleases: boolean;
  focusCourseId: string;
  compact?: boolean;
}) {
  if (loading && releases.length === 0) return <p className={styles.loading}>{t('admin.institutionOperations.loadingReleases')}</p>;
  if (!releases.length) return <p className={styles.empty}>{t('admin.institutionOperations.noReleases')}</p>;
  return <div className={styles.releaseGrid}>
    {releases.map(release => {
      const state = String(release.release_state || 'UNKNOWN').toUpperCase();
      const canSubmit = ['DRAFT', 'REJECTED', 'WITHDRAWN', 'LEGACY_MIGRATED'].includes(state);
      const canApprove = state === 'QUALITY_APPROVED';
      return <article className={styles.releaseCard + (focusCourseId === String(release.course_id) ? ' ' + styles.focused : '')} data-release-course-id={release.course_id} key={release.course_id + ':' + release.release_id}>
        <div className={styles.releaseTitle}>
          <div><h3>{release.display_name || release.course_code || 'Course'}</h3><p>{release.course_code || ''} · v{release.release_version}</p></div>
          <span className={state === 'SHAREABLE' ? styles.activeStatus : styles.status}>{stateLabel(state)}</span>
        </div>
        <ol className={styles.steps}>
          <li className={['IN_QUALITY_REVIEW', 'QUALITY_APPROVED', 'SHAREABLE'].includes(state) ? styles.completedStep : styles.currentStep}>{t('admin.institutionOperations.contentReadyStep')}</li>
          <li className={['QUALITY_APPROVED', 'SHAREABLE'].includes(state) ? styles.completedStep : state === 'IN_QUALITY_REVIEW' ? styles.currentStep : ''}>{t('admin.institutionOperations.qualityReviewStep')}</li>
          <li className={state === 'SHAREABLE' ? styles.completedStep : state === 'QUALITY_APPROVED' ? styles.currentStep : ''}>{t('admin.institutionOperations.sourceApprovalStep')}</li>
          <li className={state === 'SHAREABLE' ? styles.completedStep : ''}>{t('admin.institutionOperations.marketplaceStep')}</li>
        </ol>
        {state === 'IN_QUALITY_REVIEW' ? <p className={styles.note}>{t('admin.institutionOperations.awaitingReview')}</p>
          : state === 'QUALITY_APPROVED' ? <p className={styles.note}>{t('admin.institutionOperations.qualityApproved')}</p>
          : state === 'SHAREABLE' ? <p className={styles.note}>{t('admin.institutionOperations.shareable')}</p>
          : state === 'REJECTED' ? <p className={styles.note}>{t('admin.institutionOperations.returned')}</p>
          : <p className={styles.note}>{t('admin.institutionOperations.draft')}</p>}
        {release.quality_review_notes ? <p className={styles.reviewNote}>{t('admin.institutionOperations.hrmsReview', { notes: release.quality_review_notes })}</p> : null}
        {release.origin_rejection_reason ? <p className={styles.reviewNote}>{t('admin.institutionOperations.previousDecision', { reason: release.origin_rejection_reason })}</p> : null}
        <div className={styles.releaseMeta}>
          {release.submitted_at ? <span>{t('admin.institutionOperations.submittedAt', { date: dateLabel(release.submitted_at) })}</span> : null}
          {release.quality_reviewed_at ? <span>{t('admin.institutionOperations.reviewedAt', { date: dateLabel(release.quality_reviewed_at) })}</span> : null}
          {release.shareable_at ? <span>{t('admin.institutionOperations.approvedAt', { date: dateLabel(release.shareable_at) })}</span> : null}
        </div>
        <div className={styles.releaseActions}>
          {canManageReleases && canSubmit ? <button type="button" disabled={busyRelease !== null} onClick={() => onAction(release, 'submit')}>
            {busyRelease === release.release_id ? t('admin.institutionOperations.submitting') : state === 'DRAFT' ? t('admin.institutionOperations.submitForReview') : t('admin.institutionOperations.submitNewVersion')}
          </button> : null}
          {canManageReleases && canApprove ? <button type="button" disabled={busyRelease !== null} onClick={() => onAction(release, 'approve')}>
            {busyRelease === release.release_id ? t('admin.institutionOperations.approving') : t('admin.institutionOperations.approveForSharing')}
          </button> : null}
        </div>
        {compact && state === 'SHAREABLE' ? <small>{t('admin.institutionOperations.shareable')}</small> : null}
      </article>;
    })}
  </div>;
}
