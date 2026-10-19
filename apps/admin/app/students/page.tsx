'use client';


import { apiFetch } from '../components/apiClient';
import Link from 'next/link';
import AdminSidebar from '../components/AdminSidebar';
import { useUiTranslation } from '../i18n/useUiTranslation';
import PremiumPagination from '../components/PremiumPagination';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { AdminCourseSelector, useAdminCourseScope } from '../components/AdminCourseScope';

type AdminStudentRow = {
  id: number;
  display_name: string;
  course_names?: string | null;
  board?: string | null;
  language?: string | null;
  active: boolean;
  completed_lessons: number;
};

export default function AdminStudents() {
  const router = useRouter();
  const t = useUiTranslation();
  const { courses, courseId, loadingCourses, courseError, selectCourse } = useAdminCourseScope();
  const [rows, setRows] = useState<AdminStudentRow[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [pageSize, setPageSize] = useState(10);

  useEffect(() => {
    if (loadingCourses) {
      setLoading(true);
      return;
    }
    if (!courseId) {
      setRows([]);
      setTotal(0);
      setError(courseError || 'Select an assigned course to view course-specific learner progress.');
      setLoading(false);
      return;
    }

    let active = true;
    setLoading(true);
    setError('');
    (async () => {
      const params = new URLSearchParams({
        courseId,
        limit: String(pageSize),
        offset: String(page * pageSize),
      });
      if (query.trim()) params.set('query', query.trim());
      const response = await apiFetch('/api/v1/admin/students?' + params.toString(), {
        cache: 'no-store',
        credentials: 'same-origin',
      });
      if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
          router.replace('/login');
          return;
        }
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || body.detail || t('admin.students.loadFailed'));
      }
      const body = await response.json();
      if (!active) return;
      setRows(Array.isArray(body.items) ? body.items : []);
      setTotal(Number(body.total ?? 0));
    })().catch((cause: unknown) => {
      if (active) setError(cause instanceof Error ? cause.message : t('admin.students.loadFailed'));
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [router, query, page, pageSize, t, courseId, loadingCourses, courseError]);

  return (
    <main className="admin-shell">
      <AdminSidebar active="students" />
      <section className="admin-main">
        <header className="admin-top">
          <div>
            <span className="admin-kicker">{t('admin.students.eyebrow')}</span>
            <h1>{t('admin.students.title')}</h1>
            <p>{t('admin.students.description')}</p>
          </div>
          <Link href="/" className="button button-dark">{t('admin.students.dashboard')}</Link>
        </header>

        {error && <div className="feedback" role="alert">{error}</div>}

        <div style={{ margin: '16px 0', display: 'flex', alignItems: 'end', gap: 12, flexWrap: 'wrap' }}>
          <AdminCourseSelector
            courses={courses}
            courseId={courseId}
            loading={loadingCourses}
            label={t('admin.contentStudio.course')}
            placeholder={t('admin.contentStudio.noCoursesAssigned')}
            onChange={value => {
              if (selectCourse(value)) {
                setPage(0);
                setRows([]);
                setError('');
              }
            }}
            disabled={Boolean(courseError)}
          />
          <span className="admin-live-label">Completed lessons for the selected course only</span>
        </div>

        <section className="admin-panel">
          <div className="panel-head">
            <div>
              <span>{t('admin.students.studentAccounts')}</span>
              <h2>{t('admin.students.productionProfiles')}</h2>
            </div>
            <span className="count">{t('admin.students.accountCount', { count: total })}</span>
          </div>
          <label style={{ display: 'grid', gap: 6, maxWidth: 440, marginBottom: 16 }}>
            <span>{t('admin.students.searchLabel')}</span>
            <input
              value={query}
              onChange={event => { setQuery(event.target.value); setPage(0); }}
              placeholder={t('admin.students.searchPlaceholder')}
              style={{ padding: '10px 12px', border: '1px solid var(--admin-border,#d8dce5)', borderRadius: 10 }}
            />
          </label>
          <div className="table">
            <div className="tr th">
              <span>{t('admin.students.name')}</span>
              <span>{t('admin.assessments.course')}</span>
              <span>{t('admin.students.boardLanguage')}</span>
              <span>{t('admin.students.status')}</span>
              <span>{t('admin.students.completed')}</span>
            </div>
            {!loading && rows.map(student => (
              <div className="tr" key={student.id}>
                <strong>{student.display_name}</strong>
                <span>{student.course_names || '—'}</span>
                <span>{student.board || '—'} · {student.language || '—'}</span>
                <span className={student.active ? 'published' : 'draft'}>
                  {student.active ? t('admin.students.active') : t('admin.students.suspended')}
                </span>
                <span>{student.completed_lessons}</span>
              </div>
            ))}
          </div>
          {loading && <div className="empty-state" role="status"><h2>Loading course progress…</h2></div>}
          {!loading && rows.length === 0 && (
            <div className="empty-state">
              <h2>{t('admin.students.emptyTitle')}</h2>
              <p>{t('admin.students.emptyDescription')}</p>
            </div>
          )}
          <PremiumPagination
            page={page}
            total={total}
            pageSize={pageSize}
            onPageChange={setPage}
            onPageSizeChange={size => { setPageSize(size); setPage(0); }}
            itemLabel={t('admin.students.itemLabel')}
          />
        </section>
      </section>
    </main>
  );
}
