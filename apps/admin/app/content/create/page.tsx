'use client';

import Link from 'next/link';
import { useCallback, useContext, useEffect, useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import AdminSidebar from '../../components/AdminSidebar';
import { apiFetch } from '../../components/apiClient';
import { AdminWorkspaceContext } from '../../components/AdminWorkspaceContext';
import { useUiTranslation } from '../../i18n/useUiTranslation';
import styles from './CreateCourse.module.css';

type CurriculumClass = { code: string; display_name: string };
type SubjectDraft = { code: string; displayName: string; sortOrder: number };
type CreateResult = { courseId?: number; releaseId?: number; releaseVersion?: number; releaseStatus?: string; displayName?: string };

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await apiFetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(String(body.message || body.detail || body.error || 'Request failed (' + response.status + ')'));
  return body as T;
}

export default function CreateCoursePage() {
  const t = useUiTranslation();
  const router = useRouter();
  const { permissions } = useContext(AdminWorkspaceContext);
  const canCreateCourse = permissions.includes('CONTENT_CREATE') && permissions.includes('CONTENT_EDIT');
  const [classes, setClasses] = useState<CurriculumClass[]>([]);
  const [classLoading, setClassLoading] = useState(true);
  const [classError, setClassError] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [code, setCode] = useState('');
  const [classCode, setClassCode] = useState('');
  const [curriculumVersion, setCurriculumVersion] = useState('CURRENT');
  const [subjects, setSubjects] = useState<SubjectDraft[]>([{ code: '', displayName: '', sortOrder: 1 }]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const loadClasses = useCallback(async () => {
    if (!canCreateCourse) { setClassLoading(false); return; }
    setClassLoading(true);
    setClassError('');
    try {
      const rows = await request<CurriculumClass[]>('/api/v1/admin/content/class-options');
      const normalized = Array.isArray(rows) ? rows.filter(row => row && row.code && row.display_name) : [];
      setClasses(normalized);
      setClassCode(current => current && normalized.some(item => item.code === current)
        ? current
        : (normalized.find(item => item.code === '6') || normalized[0])?.code || '');
      if (!normalized.length) setClassError(t('admin.institutionOperations.noClasses'));
    } catch (cause) {
      setClassError(cause instanceof Error ? cause.message : t('admin.institutionOperations.loadClassesFailed'));
    } finally {
      setClassLoading(false);
    }
  }, [canCreateCourse, t]);

  useEffect(() => { void loadClasses(); }, [loadClasses]);

  function updateSubject(index: number, patch: Partial<SubjectDraft>) {
    setSubjects(current => current.map((subject, i) => i === index ? { ...subject, ...patch } : subject));
  }
  function addSubject() {
    setSubjects(current => [...current, { code: '', displayName: '', sortOrder: current.length + 1 }]);
  }
  function removeSubject(index: number) {
    setSubjects(current => current.filter((_, i) => i !== index).map((subject, i) => ({ ...subject, sortOrder: i + 1 })));
  }

  async function createCourse(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canCreateCourse) { setError(t('admin.institutionOperations.courseCreatePermission')); return; }
    const normalizedSubjects = subjects.map((subject, index) => ({
      code: subject.code.trim().toLowerCase().replace(/[^a-z0-9_-]/g, ''),
      displayName: subject.displayName.trim(),
      sortOrder: index + 1,
    }));
    if (!normalizedSubjects.length || normalizedSubjects.some(subject => subject.code.length < 2 || !subject.displayName)) {
      setError(t('admin.institutionOperations.subjectsRequired'));
      return;
    }
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const result = await request<CreateResult>('/api/v1/admin/content/courses', {
        method: 'POST',
        body: JSON.stringify({
          courseTypeCode: 'K12',
          code: code.trim().toUpperCase(),
          displayName: displayName.trim(),
          curriculumVersion: curriculumVersion.trim() || 'CURRENT',
          classCode,
          subjects: normalizedSubjects,
        }),
      });
      if (!result.courseId) throw new Error(t('admin.institutionOperations.creationFailed'));
      setNotice(t('admin.institutionOperations.draftCreated'));
      router.push('/content?courseId=' + encodeURIComponent(String(result.courseId)));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('admin.institutionOperations.creationFailed'));
    } finally {
      setSaving(false);
    }
  }

  return (
    <main className="admin-shell">
      <AdminSidebar active="content" variant="content" />
      <section className="admin-main">
        <header className="admin-top">
          <div>
            <span className="admin-kicker">{t('admin.institutionOperations.eyebrow')}</span>
            <h1>{t('admin.institutionOperations.createCourseTitle')}</h1>
            <p>{t('admin.institutionOperations.createCourseIntro')}</p>
          </div>
          <Link href="/content" className="button button-dark">{t('admin.institutionOperations.cancel')}</Link>
        </header>

        {error ? <div className="feedback" role="alert">{error}</div> : null}
        {notice ? <div className={styles.notice} role="status">{notice}</div> : null}
        {!canCreateCourse ? (
          <section className={styles.panel}>
            <h2>{t('admin.institutionOperations.courseCreatePermission')}</h2>
            <p>{t('admin.institutionOperations.viewOnlyHint')}</p>
            <Link href="/content" className="button button-dark">{t('admin.institutionOperations.openContent')}</Link>
          </section>
        ) : (
          <form className={styles.panel} onSubmit={createCourse}>
            <div className={styles.sectionHeading}><h2>{t('admin.institutionOperations.courseDetails')}</h2><span>{t('admin.institutionOperations.courseTypeK12')}</span></div>
            <p className={styles.helper}>{t('admin.institutionOperations.courseCodeHint')}</p>
            <div className={styles.formGrid}>
              <label>
                <span>{t('admin.institutionOperations.courseName')} *</span>
                <input required minLength={2} maxLength={200} value={displayName} onChange={event => setDisplayName(event.target.value)} autoComplete="off" />
              </label>
              <label>
                <span>{t('admin.institutionOperations.courseCode')} *</span>
                <input required minLength={2} maxLength={38} value={code} onChange={event => setCode(event.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, '').slice(0, 38))} autoComplete="off" />
              </label>
              <label>
                <span>{t('admin.institutionOperations.class')} *</span>
                <select required value={classCode} onChange={event => setClassCode(event.target.value)} disabled={classLoading || classes.length === 0}>
                  <option value="">{classLoading ? t('admin.institutionOperations.loading') : t('admin.institutionOperations.class')}</option>
                  {classes.map(item => <option key={item.code} value={item.code}>{item.display_name} · {item.code}</option>)}
                </select>
              </label>
              <label>
                <span>{t('admin.institutionOperations.curriculumVersion')}</span>
                <input maxLength={120} value={curriculumVersion} onChange={event => setCurriculumVersion(event.target.value)} />
              </label>
            </div>
            {classError ? <div className="feedback" role="alert">{classError}<button type="button" onClick={() => void loadClasses()}>↻ {t('admin.institutionOperations.refresh')}</button></div> : null}

            <div className={styles.sectionHeading}><h2>{t('admin.institutionOperations.subjectTracks')}</h2><button type="button" className={styles.secondaryButton} onClick={addSubject}>{t('admin.institutionOperations.addSubject')} +</button></div>
            <div className={styles.subjectList}>
              {subjects.map((subject, index) => <div className={styles.subjectRow} key={index}>
                <span className={styles.subjectIndex}>{index + 1}</span>
                <label><span>{t('admin.institutionOperations.subjectCode')} *</span><input required minLength={2} maxLength={38} value={subject.code} onChange={event => updateSubject(index, { code: event.target.value.toLowerCase().replace(/[^a-z0-9_-]/g, '').slice(0, 38) })} /></label>
                <label><span>{t('admin.institutionOperations.subjectName')} *</span><input required minLength={2} maxLength={160} value={subject.displayName} onChange={event => updateSubject(index, { displayName: event.target.value })} /></label>
                {subjects.length > 1 ? <button type="button" className={styles.removeButton} onClick={() => removeSubject(index)}>{t('admin.institutionOperations.removeSubject')}</button> : null}
              </div>)}
            </div>

            <div className={styles.workflowNote}>
              <strong>{t('admin.institutionOperations.courseDraftNotice')}</strong>
              <p>{t('admin.institutionOperations.onlyK12Supported')}</p>
            </div>
            <footer className={styles.formFooter}>
              <Link href="/content" className={styles.secondaryButton}>{t('admin.institutionOperations.cancel')}</Link>
              <button className="button button-dark" type="submit" disabled={saving || classLoading || !classCode || classes.length === 0}>
                {saving ? t('admin.institutionOperations.creatingCourse') : t('admin.institutionOperations.createCourseButton')}
              </button>
            </footer>
          </form>
        )}
      </section>
    </main>
  );
}
