'use client';

import { apiFetch } from '../components/apiClient';
import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import styles from './CourseAccess.module.css';

type Course = {
  course_identity_id: number; course_identity_public_id?: string; stable_course_key?: string; display_name: string; course_type_code?: string;
  source_institution_code?: string; source_institution_name?: string; current_course_code?: string;
  policy_mode?: 'CALENDAR_DURATION' | 'FIXED_DAYS' | null; calendar_years?: number | null;
  calendar_months?: number | null; calendar_days?: number | null; fixed_days?: number | null;
  policy_version?: number | null; policy_updated_at?: string | null; policy_change_note?: string | null;
  assigned_institution_count?: number | string; active_enrollment_count?: number | string;
};
type Enrollment = {
  enrollment_id: number; institution_id: number; institution_code?: string; institution_name?: string;
  student_id: number; student_public_id?: string; student_name?: string; enrollment_status: string;
  enrolled_at?: string | null; course_expires_at?: string | null; course_access_blocked: boolean;
  effective_access: boolean; access_status: string; course_expiry_policy_version_snapshot?: number | null;
};
type EventRow = {
  id: number; event_type: string; policy_version?: number; enrollment_id?: number;
  institution_name?: string; student_name?: string; previous_expiry?: string | null; new_expiry?: string | null;
  previous_blocked?: boolean; new_blocked?: boolean; notes: string; actor_staff_id?: number | null; created_at: string;
};
const ENROLMENT_PAGE_SIZE = 50;

type ActionKind = 'BLOCK' | 'UNBLOCK' | 'EXTEND';
type ActiveAction = { kind: ActionKind; row: Enrollment };

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
function fmt(value?: string | null) {
  if (!value) return 'No expiry set';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'Asia/Kolkata' }).format(date) + ' IST';
}
function istDateTimeInput(date: Date) {
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23',
  }).formatToParts(date);
  const value = (type: string) => parts.find(part => part.type === type)?.value || '';
  return value('year') + '-' + value('month') + '-' + value('day') + 'T' + value('hour') + ':' + value('minute');
}
function parseIstDateTime(value: string) {
  // datetime-local has no offset; the HRMS form interprets it explicitly as India Standard Time.
  return new Date(value + ':00+05:30');
}
function expired(value?: string | null) { return Boolean(value && new Date(value).getTime() <= Date.now()); }
function duration(course: Course) {
  if (!course.policy_mode) return 'Not configured — new enrolments do not yet expire';
  if (course.policy_mode === 'FIXED_DAYS') return String(course.fixed_days) + ' days';
  const parts: string[] = [];
  if (course.calendar_years) parts.push(String(course.calendar_years) + ' year' + (course.calendar_years === 1 ? '' : 's'));
  if (course.calendar_months) parts.push(String(course.calendar_months) + ' month' + (course.calendar_months === 1 ? '' : 's'));
  if (course.calendar_days) parts.push(String(course.calendar_days) + ' day' + (course.calendar_days === 1 ? '' : 's'));
  return parts.join(', ');
}

export default function CourseAccessPage() {
  const [courses, setCourses] = useState<Course[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [courseSearch, setCourseSearch] = useState('');
  const [enrollmentSearch, setEnrollmentSearch] = useState('');
  const [appliedSearch, setAppliedSearch] = useState('');
  const [enrollments, setEnrollments] = useState<Enrollment[]>([]);
  const [enrollmentTotal, setEnrollmentTotal] = useState(0);
  const [policyEvents, setPolicyEvents] = useState<EventRow[]>([]);
  const [accessEvents, setAccessEvents] = useState<EventRow[]>([]);
  const [mode, setMode] = useState<'CALENDAR_DURATION' | 'FIXED_DAYS'>('CALENDAR_DURATION');
  const [years, setYears] = useState('1');
  const [months, setMonths] = useState('0');
  const [days, setDays] = useState('0');
  const [fixedDays, setFixedDays] = useState('180');
  const [policyNotes, setPolicyNotes] = useState('');
  const [action, setAction] = useState<ActiveAction | null>(null);
  const [actionNotes, setActionNotes] = useState('');
  const [actionExpiry, setActionExpiry] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const selected = useMemo(() => courses.find(c => String(c.course_identity_id) === selectedId), [courses, selectedId]);
  const visibleCourses = useMemo(() => {
    const q = courseSearch.trim().toLowerCase();
    if (!q) return courses;
    return courses.filter(c => [c.display_name, c.stable_course_key, c.current_course_code, c.source_institution_name,
      c.source_institution_code, c.course_type_code].some(v => String(v || '').toLowerCase().includes(q)));
  }, [courses, courseSearch]);

  const loadEnrollments = useCallback(async (courseId: string, query: string) => {
    if (!courseId) return;
    const params = new URLSearchParams({ limit: '100', offset: '0' });
    if (query.trim()) params.set('q', query.trim());
    const result = await api<{ items: Enrollment[]; total: number }>(
      '/api/v1/hrms/platform/course-access/courses/' + encodeURIComponent(courseId) + '/enrollments?' + params.toString());
    setEnrollments(result.items || []);
    setEnrollmentTotal(Number(result.total || 0));
  }, []);
  const loadAudit = useCallback(async (courseId: string) => {
    if (!courseId) return;
    const result = await api<{ policyEvents: EventRow[]; enrollmentEvents: EventRow[] }>(
      '/api/v1/hrms/platform/course-access/courses/' + encodeURIComponent(courseId) + '/audit?limit=80');
    setPolicyEvents(result.policyEvents || []);
    setAccessEvents(result.enrollmentEvents || []);
  }, []);

  useEffect(() => {
    let live = true;
    setLoading(true);
    api<Course[]>('/api/v1/hrms/platform/course-access/courses').then(rows => {
      if (!live) return;
      const items = Array.isArray(rows) ? rows : [];
      setCourses(items);
      const requestedIdentity = new URLSearchParams(window.location.search).get('courseIdentityPublicId');
      const requestedCourse = requestedIdentity
        ? items.find(c => c.course_identity_public_id === requestedIdentity)
        : undefined;
      setSelectedId(current => current && items.some(c => String(c.course_identity_id) === current)
        ? current : (requestedCourse ? String(requestedCourse.course_identity_id)
          : (items[0] ? String(items[0].course_identity_id) : '')));
    }).catch(e => {
      if (live) setError(e instanceof Error ? e.message : 'Courses could not be loaded.');
    }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, []);

  useEffect(() => {
    if (!selected) return;
    setMode(selected.policy_mode || 'CALENDAR_DURATION');
    setYears(String(selected.calendar_years ?? 0));
    setMonths(String(selected.calendar_months ?? 0));
    setDays(String(selected.calendar_days ?? 0));
    setFixedDays(String(selected.fixed_days ?? 180));
    setPolicyNotes('');
    setAction(null);
  }, [selectedId, selected]);

  useEffect(() => {
    if (!selectedId) { setEnrollments([]); setPolicyEvents([]); setAccessEvents([]); return; }
    let live = true;
    Promise.all([loadEnrollments(selectedId, appliedSearch), loadAudit(selectedId)]).catch(e => {
      if (live) setError(e instanceof Error ? e.message : 'Course access details could not be loaded.');
    });
    return () => { live = false; };
  }, [selectedId, appliedSearch, loadEnrollments, loadAudit]);

  async function savePolicy(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!selected) return;
    setSaving(true); setError(''); setNotice('');
    try {
      const saved = await api<{
        policyVersion: number; mode: 'CALENDAR_DURATION' | 'FIXED_DAYS';
        years: number | null; months: number | null; days: number | null; fixedDays: number | null; changeNote: string;
      }>('/api/v1/hrms/platform/course-access/courses/' + selected.course_identity_id + '/policy', {
        method: 'PUT',
        body: JSON.stringify({ mode, years: Number(years), months: Number(months), days: Number(days),
          fixedDays: Number(fixedDays), notes: policyNotes.trim() }),
      });
      setCourses(current => current.map(c => c.course_identity_id !== selected.course_identity_id ? c : {
        ...c, policy_mode: saved.mode, calendar_years: saved.years, calendar_months: saved.months,
        calendar_days: saved.days, fixed_days: saved.fixedDays, policy_version: saved.policyVersion,
        policy_change_note: saved.changeNote,
      }));
      setPolicyNotes('');
      setNotice('Shared course policy saved (version ' + saved.policyVersion + '). Existing enrolment expiry dates were not changed.');
      await loadAudit(selectedId);
    } catch (e) { setError(e instanceof Error ? e.message : 'The expiry policy could not be saved.'); }
    finally { setSaving(false); }
  }

  async function submitAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!action) return;
    setSaving(true); setError(''); setNotice('');
    try {
      let endpoint = '';
      const body: Record<string, unknown> = { notes: actionNotes.trim() };
      if (action.kind === 'BLOCK') endpoint = '/api/v1/hrms/platform/course-access/enrollments/' + action.row.enrollment_id + '/block';
      if (action.kind === 'UNBLOCK') endpoint = '/api/v1/hrms/platform/course-access/enrollments/' + action.row.enrollment_id + '/unblock';
      if (action.kind === 'EXTEND') {
        if (!actionExpiry) throw new Error('Choose the new expiry date and time.');
        const date = parseIstDateTime(actionExpiry);
        if (Number.isNaN(date.getTime())) throw new Error('Choose a valid expiry date and time.');
        body.expiresAt = date.toISOString();
        endpoint = '/api/v1/hrms/platform/course-access/enrollments/' + action.row.enrollment_id + '/extend';
      }
      await api(endpoint, { method: 'POST', body: JSON.stringify(body) });
      const message: Record<ActionKind, string> = {
        BLOCK: 'Course access blocked for this enrolment.',
        UNBLOCK: 'Manual block removed. Expiry and other access rules still apply.',
        EXTEND: 'Expiry updated for this enrolment only. Any manual block remains in effect.',
      };
      setNotice(message[action.kind]);
      setAction(null); setActionNotes(''); setActionExpiry('');
      await Promise.all([loadEnrollments(selectedId, appliedSearch), loadAudit(selectedId)]);
    } catch (e) { setError(e instanceof Error ? e.message : 'The access change could not be saved.'); }
    finally { setSaving(false); }
  }

  function openAction(kind: ActionKind, row: Enrollment) {
    setError(''); setNotice(''); setActionNotes(''); setActionExpiry(''); setAction({ kind, row });
  }

  const combinedAudit = [
    ...policyEvents.map(event => ({ ...event, category: 'POLICY' })),
    ...accessEvents.map(event => ({ ...event, category: 'ACCESS' })),
  ].sort((a, b) => new Date(b.created_at).getTime() - new Date(a.created_at).getTime()).slice(0, 15);

  return <main className={styles.page}>
    <header className={styles.header}>
      <div>
        <span className={styles.eyebrow}>HRMS · COURSE CONTROL</span>
        <h1>Course expiry and learner access</h1>
        <p>Configure one shared expiry policy per logical course, then manage each learner's access across institutions.</p>
      </div>
      <button className={styles.secondary} type="button" disabled={loading} onClick={() => {
        setLoading(true); setError('');
        api<Course[]>('/api/v1/hrms/platform/course-access/courses').then(rows => setCourses(Array.isArray(rows) ? rows : []))
          .catch(e => setError(e instanceof Error ? e.message : 'Refresh failed.')).finally(() => setLoading(false));
      }}>Refresh</button>
    </header>
    {error && <div className={styles.error} role="alert">{error}</div>}
    {notice && <div className={styles.notice} role="status">{notice}</div>}
    <section className={styles.sharedNotice}>
      <strong>One policy for every institution sharing the course</strong>
      <p>The policy follows the stable logical course identity, not the institution or a particular content version. No institution-specific override is provided.</p>
      <small>Policy changes affect future enrolments and newly reactivated enrolments. Existing expiry dates remain unchanged unless HRMS extends or sets them individually.</small>
    </section>

    {loading ? <div className={styles.loading}>Loading courses and learner access…</div> : courses.length === 0 ? (
      <section className={styles.empty}><h2>No registered courses found</h2><p>Active logical course identities appear here after course registration.</p></section>
    ) : <div className={styles.layout}>
      <aside className={styles.coursePanel}>
        <div className={styles.panelHeader}><div><h2>Courses</h2><span>{courses.length} logical course identities</span></div></div>
        <input className={styles.search} value={courseSearch} onChange={e => setCourseSearch(e.target.value)}
          placeholder="Search course, code or source" aria-label="Search courses" />
        <div className={styles.courseList}>
          {visibleCourses.map(course => <button type="button" key={course.course_identity_id}
            className={styles.courseRow + (String(course.course_identity_id) === selectedId ? ' ' + styles.courseRowSelected : '')}
            onClick={() => {
              setSelectedId(String(course.course_identity_id));
              setEnrollmentOffset(0);
              setEnrollmentSearch('');
              setAppliedSearch('');
            }}>
            <span className={styles.courseIcon} aria-hidden="true">◈</span>
            <span className={styles.courseCopy}>
              <strong>{course.display_name}</strong>
              <small>{course.stable_course_key || course.current_course_code || 'Course'} · {course.source_institution_name || course.source_institution_code || 'Source not listed'}</small>
              <small>{Number(course.assigned_institution_count || 0)} institutions · {Number(course.active_enrollment_count || 0)} active enrolments</small>
              <span className={styles.status + (course.policy_mode ? ' ' + styles.statusActive : ' ' + styles.statusWarning)}>
                {course.policy_mode ? 'Policy v' + course.policy_version : 'Policy not set'}
              </span>
            </span>
          </button>)}
          {visibleCourses.length === 0 && <p className={styles.emptyText}>No courses match this search.</p>}
        </div>
      </aside>

      {selected && <div className={styles.detailColumn}>
        <section className={styles.panel}>
          <div className={styles.titleRow}>
            <div><span className={styles.eyebrow}>SHARED COURSE POLICY</span><h2>{selected.display_name}</h2>
              <p>{selected.stable_course_key || selected.current_course_code || 'Logical course'} · Source: {selected.source_institution_name || selected.source_institution_code || 'Not listed'}</p></div>
            <span className={styles.status + (selected.policy_mode ? ' ' + styles.statusActive : ' ' + styles.statusWarning)}>{selected.policy_mode ? 'Configured' : 'Not configured'}</span>
          </div>
          <div className={styles.policySummary}>
            <div><span>Current shared duration</span><strong>{duration(selected)}</strong></div>
            <div><span>Institutions using course</span><strong>{Number(selected.assigned_institution_count || 0)}</strong></div>
            <div><span>Active enrolments</span><strong>{Number(selected.active_enrollment_count || 0)}</strong></div>
          </div>
          <form onSubmit={savePolicy} className={styles.form}>
            <h3>{selected.policy_mode ? 'Update expiry policy' : 'Configure expiry policy'}</h3>
            <label className={styles.full}>Expiry format
              <select value={mode} onChange={e => setMode(e.target.value as typeof mode)}>
                <option value="CALENDAR_DURATION">Calendar duration — years, months and days</option>
                <option value="FIXED_DAYS">Fixed duration — number of days</option>
              </select>
              <small>The selected duration is shared by all institutions using this logical course.</small>
            </label>
            {mode === 'CALENDAR_DURATION' ? <div className={styles.durationFields}>
              <label>Years<input type="number" min="0" max="100" required value={years} onChange={e => setYears(e.target.value)} /></label>
              <label>Months<input type="number" min="0" max="100" required value={months} onChange={e => setMonths(e.target.value)} /></label>
              <label>Days<input type="number" min="0" max="100" required value={days} onChange={e => setDays(e.target.value)} /></label>
            </div> : <label className={styles.full}>Number of days
              <input type="number" min="1" max="36500" required value={fixedDays} onChange={e => setFixedDays(e.target.value)} />
              <small>Access expires once this duration has elapsed from the enrolment timestamp.</small>
            </label>}
            <label className={styles.full}>Written change note
              <textarea rows={2} minLength={3} maxLength={1000} required value={policyNotes} onChange={e => setPolicyNotes(e.target.value)}
                placeholder="Why is this shared course expiry policy being configured or changed?" />
            </label>
            <div className={styles.formActions}><button className={styles.primary} type="submit" disabled={saving}>
              {saving ? 'Saving…' : selected.policy_mode ? 'Save policy update' : 'Save shared policy'}</button></div>
          </form>
          <p className={styles.caveat}>Until a policy is configured, new enrolments for this course do not receive an automatic expiry. Legacy enrolments are not silently changed.</p>
        </section>

        <section className={styles.panel}>
          <div className={styles.titleRow}>
            <div><h2>Individual learner access</h2><p>All institutions sharing this course are included. Each action changes only the selected enrolment.</p></div>
            <span className={styles.count}>{enrollmentTotal} enrolments</span>
          </div>
          <form className={styles.searchForm} onSubmit={e => { e.preventDefault(); setAppliedSearch(enrollmentSearch.trim()); }}>
            <input className={styles.search} value={enrollmentSearch} onChange={e => setEnrollmentSearch(e.target.value)}
              placeholder="Search learner name, ID or institution" aria-label="Search learner enrolments" />
            <button className={styles.secondary} type="submit">Search</button>
          </form>
          {action && <form onSubmit={submitAction} className={styles.actionPanel}>
            <div className={styles.titleRow}>
              <div><span className={styles.eyebrow}>INDIVIDUAL ENROLMENT CHANGE</span>
                <h3>{action.kind === 'BLOCK' ? 'Block course access' : action.kind === 'UNBLOCK' ? 'Remove manual block' : 'Set or extend expiry'}</h3>
                <p>{action.row.student_name || 'Student ' + action.row.student_id} · {action.row.institution_name || action.row.institution_code || 'Institution'}</p></div>
              <button type="button" className={styles.quiet} onClick={() => setAction(null)} disabled={saving}>Cancel</button>
            </div>
            {action.kind === 'EXTEND' && <label className={styles.full}>New expiry date and time
              <input type="datetime-local" required min={istDateTimeInput(new Date())} value={actionExpiry} onChange={e => setActionExpiry(e.target.value)} />
              <small>Enter the expiry date and time in India Standard Time (IST). The new expiry must be in the future and later than the current expiry.</small>
            </label>}
            <label className={styles.full}>Written reason
              <textarea rows={3} minLength={3} maxLength={1000} required value={actionNotes} onChange={e => setActionNotes(e.target.value)}
                placeholder="Record the reason for this individual learner access change." />
            </label>
            <div className={styles.formActions}>
              <button className={action.kind === 'BLOCK' ? styles.danger : styles.primary} type="submit" disabled={saving}>
                {saving ? 'Saving…' : action.kind === 'BLOCK' ? 'Confirm block' : action.kind === 'UNBLOCK' ? 'Confirm unblock' : 'Save expiry'}</button>
              <button className={styles.secondary} type="button" onClick={() => setAction(null)} disabled={saving}>Cancel</button>
            </div>
          </form>}

          <div className={styles.tableWrap}><table>
            <thead><tr><th>Learner / institution</th><th>Enrolled</th><th>Expiry (IST)</th><th>Access</th><th>Actions</th></tr></thead>
            <tbody>{enrollments.map(row => {
              const isExpired = expired(row.course_expires_at);
              const ended = row.enrollment_status !== 'ACTIVE';
              return <tr key={row.enrollment_id}>
                <td><strong>{row.student_name || 'Student ' + row.student_id}</strong>
                  <small>{row.student_public_id || 'Student ID ' + row.student_id}</small>
                  <small>{row.institution_name || row.institution_code || 'Institution ' + row.institution_id}</small></td>
                <td>{fmt(row.enrolled_at)}</td>
                <td>{fmt(row.course_expires_at)}<small>{row.course_expiry_policy_version_snapshot ? 'Policy v' + row.course_expiry_policy_version_snapshot : 'Legacy or HRMS-set expiry'}</small></td>
                <td><span className={styles.status + (row.access_status === 'ACTIVE' ? ' ' + styles.statusActive :
                  (row.access_status === 'BLOCKED' || row.access_status === 'EXPIRED') ? ' ' + styles.statusWarning : ' ' + styles.statusNeutral)}>
                  {row.access_status || row.enrollment_status}</span></td>
                <td><div className={styles.rowActions}>
                  {row.course_access_blocked
                    ? <button type="button" className={styles.secondary} disabled={saving || ended || isExpired}
                        title={isExpired ? 'Extend expiry before unblocking.' : ended ? 'This enrolment is not active.' : 'Remove manual block.'}
                        onClick={() => openAction('UNBLOCK', row)}>Unblock</button>
                    : <button type="button" className={styles.danger} disabled={saving || ended}
                        title={ended ? 'This enrolment is not active.' : 'Block this learner.'} onClick={() => openAction('BLOCK', row)}>Block</button>}
                  <button type="button" className={styles.secondary} disabled={saving || ended}
                    onClick={() => openAction('EXTEND', row)}>{row.course_expires_at ? 'Extend' : 'Set expiry'}</button>
                </div>{isExpired && row.course_access_blocked && <small>Expired; extend before unblocking.</small>}</td>
              </tr>;
            })}
            {enrollments.length === 0 && <tr><td className={styles.emptyCell} colSpan={5}>No matching enrolments were found.</td></tr>}</tbody>
          </table></div>
          <p className={styles.caveat}>Blocking does not delete progress, saved answers, assessment results, purchases or existing certificates. Unblocking alone never renews expired access.</p>
        </section>

        <section className={styles.panel}>
          <div className={styles.titleRow}><div><h2>Access audit history</h2><p>Policy changes and user-wise actions with written reasons.</p></div>
            <span className={styles.count}>{combinedAudit.length} recent events</span></div>
          <div className={styles.auditList}>{combinedAudit.map(event => <article key={String(event.id) + event.event_type + String(event.enrollment_id || '')}>
            <div className={styles.auditHeading}><strong>{String(event.event_type || '').replaceAll('_', ' ')}</strong><time>{fmt(event.created_at)}</time></div>
            <p>{event.category === 'POLICY' ? 'Shared course policy' + (event.policy_version ? ' v' + event.policy_version : '') :
              (event.student_name || 'Enrollment ' + (event.enrollment_id || '')) + ' · ' + (event.institution_name || 'Institution')}</p>
            <blockquote>{event.notes}</blockquote>
            {(event.previous_expiry || event.new_expiry) && <small>Expiry: {fmt(event.previous_expiry)} → {fmt(event.new_expiry)}</small>}
            {event.previous_blocked !== undefined && <small>Manual block: {event.previous_blocked ? 'Yes' : 'No'} → {event.new_blocked ? 'Yes' : 'No'}</small>}
            <small>Recorded by {event.actor_staff_id ? 'HRMS staff #' + event.actor_staff_id : 'system / retained audit actor'}</small>
          </article>)}
            {combinedAudit.length === 0 && <p className={styles.emptyText}>No access-policy events recorded yet.</p>}
          </div>
        </section>
      </div>}
    </div>}
    <footer className={styles.footer}><span>Global course policy · no institution-specific override</span>
      <span>Expiry enforcement runs on the API; this screen is not the security boundary.</span></footer>
  </main>;
}
