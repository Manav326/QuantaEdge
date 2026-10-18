'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
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
  status?: string;
  is_enrolled?: boolean;
  enrolled?: boolean;
  paused?: boolean;
  has_access?: boolean;
  offer_available?: boolean;
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
      const rows = await responseJson(await fetch('/api/v1/students/me/programs', {
        credentials: 'same-origin', cache: 'no-store',
      }));
      const all = Array.isArray(rows) ? rows as Course[] : [];
      setCourses(all);
      const owned = all.filter(course => course.is_enrolled === true || course.enrolled === true || course.paused === true || course.has_access === true);
      const serverActive = owned.find(course => course.is_enrolled === true || course.enrolled === true);
      const requestedFocus = typeof window !== 'undefined'
        ? Number(new URLSearchParams(window.location.search).get('focus') || 0) : 0;
      const requested = owned.find(course =>
        Number(course.id || course.course_id) === requestedFocus &&
        (course.is_enrolled === true || course.enrolled === true)
      );
      const selected = requested || serverActive || owned[0] || null;
      const selectedId = selected ? Number(selected.id || selected.course_id) : null;
      setActiveCourseId(selectedId && Number.isSafeInteger(selectedId) ? selectedId : null);

      const results = await Promise.all(owned.filter(course => course.is_enrolled === true || course.enrolled === true || course.paused === true).map(async course => {
        const id = Number(course.id || course.course_id);
        if (!Number.isSafeInteger(id) || id <= 0) return null;
        try {
          const data = await responseJson(await fetch('/api/v1/students/me?courseId=' + encodeURIComponent(String(id)), {
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
      (course.is_enrolled === true || course.enrolled === true || course.paused === true || course.has_access === true)
    );
    if (!exists) {
      setError('That course is not included in your account.');
      return;
    }
    setBusyCourseId(courseId);
    setError('');
    try {
      await responseJson(await fetch('/api/v1/students/me/programs/id/' + encodeURIComponent(String(courseId)) + '/enroll', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' }, body: '{}',
      }));
      setActiveCourseId(courseId);
      try { window.localStorage.setItem('qe:student:activeCourseId', String(courseId)); } catch { /* storage is optional */ }
      await loadCourses();
      if (navigate) router.push('/student');
    } catch (e: any) {
      setError(e?.message || 'The selected course could not be opened.');
    } finally {
      setBusyCourseId(null);
    }
  }

  return (
    <main className={styles.page}>
      <header className={styles.hero}>
        <div className={styles.eyebrow}>YOUR LEARNING SPACE</div>
        <h1>All Courses</h1>
        <p>Your courses in one place. Select a course to focus your subjects, lessons and progress on that learning path.</p>
        <div className={styles.actions}>
          <Link className={styles.primaryLink} href="/student/marketplace">Explore Marketplace <span aria-hidden="true">↗</span></Link>
          <Link className={styles.secondaryLink} href="/student">Back to dashboard</Link>
        </div>
      </header>

      {error && <p className={styles.error} role="alert">{error}</p>}
      {loading && <div className={styles.empty} role="status">Loading your courses…</div>}
      {!loading && !error && courses.length === 0 && (
        <div className={styles.empty}>
          <h2>No courses are available yet</h2>
          <p>Active courses will appear here when they are added to the course catalogue.</p>
          <Link className={styles.primaryLink} href="/student/marketplace">Explore Marketplace</Link>
        </div>
      )}
      {!loading && courses.length > 0 && (
        <section className={styles.grid} aria-label="All available courses">
          {courses.map(course => {
            const id = Number(course.id || course.course_id);
            const active = id === activeCourseId && (course.is_enrolled === true || course.enrolled === true);
            const hasAccess = course.has_access === true || course.is_enrolled === true || course.enrolled === true || course.paused === true;
            const metric = progress[id];
            const stats = metric?.data?.lessonStats || {};
            const total = Number(stats.total_lessons || stats.totalLessons || 0);
            const completed = Number(stats.completed_lessons || stats.completedLessons || 0);
            const percent = Math.max(0, Math.min(100, Number(stats.completion_percent || (total ? completed * 100 / total : 0))));
            return (
              <article className={styles.courseCard} key={id}>
                <div className={styles.cardHeader}>
                  <span className={styles.mark} aria-hidden="true">✦</span>
                  <span className={active ? styles.activeBadge : hasAccess ? styles.enrolledBadge : course.offer_available ? styles.availableBadge : styles.lockedBadge}>
                    {active ? 'Learning now' : hasAccess ? 'In your courses' : course.offer_available ? 'Available in Marketplace' : 'Coming soon'}
                  </span>
                </div>
                <h2>{course.display_name}</h2>
                <p className={styles.meta}>{course.curriculum_version || course.code}</p>
                {active && total > 0 ? (
                  <div className={styles.progress}>
                    <div className={styles.progressLabels}><span>{completed} of {total} lessons</span><strong>{Math.round(percent)}%</strong></div>
                    <span className={styles.progressTrack}><span style={{ width: percent + '%' }} /></span>
                  </div>
                ) : <p className={styles.meta}>{hasAccess ? (metric?.error ? 'Progress is temporarily unavailable.' : 'Progress will appear as you learn.') : 'Preview the course here. Enrolment or purchase is required to open lessons.'}</p>}

                <div className={styles.subjectList}>
                  <div className={styles.subjectHeading}>Subjects</div>
                  {(course.subjects || []).length === 0 ? <p className={styles.meta}>Subjects will appear when available.</p> :
                    (course.subjects || []).map(subject => active ? (
                      <Link key={subject.subject_code} href={'/student/learn?courseId=' + encodeURIComponent(String(id)) + '&subjectCode=' + encodeURIComponent(subject.subject_code)} className={styles.subjectLink}>
                        <span><strong>{subject.subject_name || subject.subject_code}</strong><small>{Number(subject.chapters || 0)} chapters · {Number(subject.lessons || 0)} lessons</small></span>
                        <span aria-hidden="true">→</span>
                      </Link>
                    ) : (
                      <div key={subject.subject_code} className={styles.subjectLink} aria-disabled="true">
                        <span><strong>{subject.subject_name || subject.subject_code}</strong><small>{Number(subject.chapters || 0)} chapters · {Number(subject.lessons || 0)} lessons</small></span>
                        <span aria-hidden="true">🔒</span>
                      </div>
                    ))}
                </div>
                {!active && hasAccess && (
                  <button className={styles.secondaryButton} type="button" disabled={busyCourseId !== null} onClick={() => void activateCourse(id)}>
                    {busyCourseId === id ? 'Opening course…' : 'Open / focus on this course'}
                  </button>
                )}
                {!hasAccess && course.offer_available && (
                  <Link className={styles.primaryLink} href={'/student/marketplace?courseId=' + encodeURIComponent(String(id))}>View course offer</Link>
                )}
                {!hasAccess && !course.offer_available && (
                  <button className={styles.secondaryButton} type="button" disabled>Not yet available to join</button>
                )}
              </article>
            );
          })}
        </section>
      )}
    </main>
  );
}
