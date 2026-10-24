'use client';


import { apiFetch } from '../../components/apiClient';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTenantBranding } from '../../components/TenantBrandingProvider';
import { UiText } from '../../i18n/useUiTranslation';
import StudentEmptyState from '../components/StudentEmptyState';
import styles from './courses.module.css';

type Subject = {
  subject_code: string;
  subject_name: string;
  chapters?: number;
  lessons?: number;
};
type Course = {
  id: number;
  course_id?: number;
  public_id?: string;
  code: string;
  display_name: string;
  curriculum_version?: string;
  course_type_code?: string;
  delivery_mode?: string;
  status?: string;
  is_enrolled?: boolean;
  enrolled?: boolean;
  paused?: boolean;
  subjects?: Subject[];
};
type Metric = { id: number; data?: any; error?: boolean };

async function responseJson(response: Response): Promise<any> {
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || body.detail || body.error || 'This request could not be completed.');
  return body;
}

export default function StudentCoursesPage() {
  const router = useRouter();
  const { brand } = useTenantBranding();
  const [courses, setCourses] = useState<Course[]>([]);
  const [progress, setProgress] = useState<Record<number, Metric>>({});
  const [activeCourseId, setActiveCourseId] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [busyCourseId, setBusyCourseId] = useState<number | null>(null);
  const [error, setError] = useState('');

  const loadCourses = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const rows = await responseJson(await apiFetch('/api/v1/students/me/programs', {
        credentials: 'same-origin', cache: 'no-store',
      }));
      const all = Array.isArray(rows) ? rows as Course[] : [];
      setCourses(all);
      const owned = all.filter(course => course.is_enrolled === true || course.enrolled === true || course.paused === true);
      const serverActive = owned.find(course => course.is_enrolled === true || course.enrolled === true);
      let savedFocus = 0;
      try { savedFocus = Number(window.localStorage.getItem('qe:student:activeCourseId') || 0); } catch {}
      const requestedFocus = typeof window !== 'undefined'
        ? Number(new URLSearchParams(window.location.search).get('focus') || 0) : 0;
      const requested = owned.find(course =>
        Number(course.id || course.course_id) === requestedFocus
      );
      const saved = owned.find(course => Number(course.id || course.course_id) === savedFocus);
      const selected = requested || saved || serverActive || owned[0] || null;
      const selectedId = selected ? Number(selected.id || selected.course_id) : null;
      setActiveCourseId(selectedId && Number.isSafeInteger(selectedId) ? selectedId : null);

      const results = await Promise.all(owned.map(async course => {
        const id = Number(course.id || course.course_id);
        if (!Number.isSafeInteger(id) || id <= 0) return null;
        if (course.course_type_code === 'ASSESSMENT' || course.delivery_mode === 'ASSESSMENT_ONLY') {
          return { id, data: { assessmentOnly: true } };
        }
        try {
          const data = await responseJson(await apiFetch('/api/v1/students/me?courseId=' + encodeURIComponent(String(id)), {
            credentials: 'same-origin', cache: 'no-store',
          }));
          return { id, data };
        } catch {
          return { id, error: true };
        }
      }));
      const next: Record<number, Metric> = {};
      results.forEach(result => { if (result) next[result.id] = result; });
      setProgress(next);

      if (selectedId) {
        try { window.localStorage.setItem('qe:student:activeCourseId', String(selectedId)); } catch { /* storage is optional */ }
      }
    } catch (e: any) {
      setError(e?.message || 'Courses could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadCourses(); }, [loadCourses]);

  async function activateCourse(courseId: number, navigate = true) {
    if (!Number.isSafeInteger(courseId) || courseId <= 0 || busyCourseId !== null) return;
    const exists = courses.some(course =>
      Number(course.id || course.course_id) === courseId &&
      (course.is_enrolled === true || course.enrolled === true || course.paused === true)
    );
    if (!exists) {
      setError('That course is not included in your account.');
      return;
    }
    setBusyCourseId(courseId);
    setError('');
    try {
      const selected = courses.find(course => Number(course.id || course.course_id) === courseId);
      if (!selected) throw new Error('That course is not included in your account.');
      // Focus changes independently of enrollment. Only reactivate a legacy paused row.
      if (!(selected.is_enrolled === true || selected.enrolled === true)) {
        await responseJson(await apiFetch('/api/v1/students/me/programs/id/' + encodeURIComponent(String(courseId)) + '/enroll', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json' }, body: '{}',
        }));
        setCourses(previous => previous.map(course => Number(course.id || course.course_id) === courseId
          ? { ...course, is_enrolled: true, enrolled: true, paused: false } : course));
      }
      setActiveCourseId(courseId);
      try { window.localStorage.setItem('qe:student:activeCourseId', String(courseId)); } catch { /* storage is optional */ }
      if (navigate) {
        const assessmentCourse = selected.course_type_code === 'ASSESSMENT' || selected.delivery_mode === 'ASSESSMENT_ONLY';
        const destination = assessmentCourse ? '/student/tests?courseId=' : '/student/learn?courseId=';
        router.push(destination + encodeURIComponent(String(selected.public_id || courseId)));
      }
    } catch (e: any) {
      setError(e?.message || 'The selected course could not be opened.');
    } finally {
      setBusyCourseId(null);
    }
  }

  const owned = courses.filter(course =>
    course.is_enrolled === true || course.enrolled === true || course.paused === true
  );

  return (
    <main className={styles.page}>
      <header className={styles.hero}>
        <div className={styles.eyebrow}>{brand?.displayName || "YOUR INSTITUTION"} · YOUR COURSES</div>
        <h1>All Courses</h1>
        <p>Your courses in one place. Select a course to focus your subjects, lessons and progress on that learning path.</p>
        <div className={styles.actions}>
          <Link className={styles.primaryLink} href="/student/marketplace">Explore Marketplace <span aria-hidden="true">↗</span></Link>
          <Link className={styles.secondaryLink} href="/student">Back to dashboard</Link>
        </div>
      </header>

      {error && <p className={styles.error} role="alert">{error}</p>}
      {loading && <div className={styles.empty} role="status">Loading your courses…</div>}
      {!loading && !error && owned.length === 0 && (
        <StudentEmptyState
          icon="courses"
          title={<UiText message="student.empty.courses.title" />}
          message={<UiText message="student.empty.courses.message" />}
          primaryActionHref="/student/marketplace"
          primaryActionLabel={<UiText message="student.empty.dashboard.action" />}
          secondaryActionHref="/student"
          secondaryActionLabel={<UiText message="web.navigation.student.today" />}
        />
      )}
      {!loading && owned.length > 0 && (
        <section className={styles.grid} aria-label="Your enrolled courses">
          {owned.map(course => {
            const id = Number(course.id || course.course_id);
            const active = id === activeCourseId;
            const assessmentCourse = course.course_type_code === 'ASSESSMENT' || course.delivery_mode === 'ASSESSMENT_ONLY';
            const metric = progress[id];
            const stats = metric?.data?.lessonStats || {};
            const total = Number(stats.total_lessons || stats.totalLessons || 0);
            const completed = Number(stats.completed_lessons || stats.completedLessons || 0);
            const percent = Math.max(0, Math.min(100, Number(stats.completion_percent || (total ? completed * 100 / total : 0))));
            return (
              <article className={styles.courseCard} key={id}>
                <div className={styles.cardHeader}>
                  <span className={styles.mark} aria-hidden="true">✦</span>
                  <span className={active ? styles.activeBadge : styles.enrolledBadge}>{active ? 'Learning now' : 'Enrolled'}</span>
                </div>
                <h2>{course.display_name}</h2>
                <p className={styles.meta}>{course.curriculum_version || course.code}</p>
                {assessmentCourse ? (
                  <p className={styles.meta}>Test packs, attempts and results are available in this course.</p>
                ) : total > 0 ? (
                  <div className={styles.progress}>
                    <div className={styles.progressLabels}><span>{completed} of {total} lessons</span><strong>{Math.round(percent)}%</strong></div>
                    <span className={styles.progressTrack}><span style={{ width: percent + '%' }} /></span>
                  </div>
                ) : <p className={styles.meta}>{metric?.error ? 'Progress is temporarily unavailable.' : 'Progress will appear as you learn.'}</p>}

                {assessmentCourse ? (
                  <div className={styles.subjectList}>
                    <div className={styles.subjectHeading}>Assessment content</div>
                    <Link href={'/student/tests?courseId=' + encodeURIComponent(String(course.public_id || id))} className={styles.subjectLink}>
                      <span><strong>Open tests</strong><small>Test catalogue, attempts, results and review</small></span>
                      <span aria-hidden="true">→</span>
                    </Link>
                  </div>
                ) : (
                  <div className={styles.subjectList}>
                    <div className={styles.subjectHeading}>Subjects</div>
                    {(course.subjects || []).length === 0 ? <p className={styles.meta}>Subjects will appear when available.</p> :
                      (course.subjects || []).map(subject => (
                        <Link key={subject.subject_code} href={'/student/learn?courseId=' + encodeURIComponent(String(course.public_id || id)) + '&subjectCode=' + encodeURIComponent(subject.subject_code)} className={styles.subjectLink}>
                          <span><strong>{subject.subject_name || subject.subject_code}</strong><small>{Number(subject.chapters || 0)} chapters · {Number(subject.lessons || 0)} lessons</small></span>
                          <span aria-hidden="true">→</span>
                        </Link>
                      ))}
                  </div>
                )}
                {!active && (
                  <button className={styles.secondaryButton} type="button" disabled={busyCourseId !== null}
                    onClick={() => void activateCourse(id)}>
                    {busyCourseId === id ? 'Switching course…' : 'Focus on this course'}
                  </button>
                )}
              </article>
            );
          })}
        </section>
      )}
    </main>
  );
}
