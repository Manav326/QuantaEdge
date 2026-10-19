'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';

export type EnrolledCourseSubject = {
  subject_code?: string;
  subject_name?: string;
  code?: string;
  display_name?: string;
  lessons?: number;
  chapters?: number;
};

export type EnrolledCourse = {
  id: number;
  course_id?: number;
  public_id?: string;
  code?: string;
  display_name: string;
  curriculum_version?: string;
  is_enrolled: boolean;
  paused?: boolean;
  purchased?: boolean;
  subjects?: EnrolledCourseSubject[];
};

export function useStudentCourseScope(requestedCourseId: number | null = null) {
  const [courses, setCourses] = useState<EnrolledCourse[]>([]);
  const [courseId, setCourseId] = useState<number | null>(null);
  const [loadingCourses, setLoadingCourses] = useState(true);
  const [courseError, setCourseError] = useState('');

  useEffect(() => {
    let active = true;
    setLoadingCourses(true);
    setCourseError('');
    fetch('/api/v1/students/me/programs', {
      credentials: 'same-origin',
      cache: 'no-store',
      headers: { 'X-QE-Workspace': 'STUDENT' },
    }).then(async response => {
      const raw = await response.text();
      let body: any = {};
      try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
      if (response.status === 401 || response.status === 403) {
        throw Object.assign(new Error('Student sign-in is required.'), { auth: true });
      }
      if (!response.ok) throw new Error(body.detail || body.message || body.error || 'Your courses could not be loaded.');
      const rows: EnrolledCourse[] = Array.isArray(body) ? body : [];
      // A catalogue/marketplace offer is not an entitlement. Only an active enrollment
      // can establish the selected learning context for resources.
      const enrolled = rows.filter(course => course.is_enrolled === true && course.paused !== true && Number.isSafeInteger(Number(course.id)) && Number(course.id) > 0);
      if (!active) return;
      setCourses(enrolled);
      const saved = typeof window === 'undefined' ? 0 : Number(window.localStorage.getItem('qe:student:activeCourseId') || 0);
      const requested = Number(requestedCourseId || 0);
      const selected = enrolled.find(course => Number(course.id) === requested)
        || enrolled.find(course => Number(course.id) === saved)
        || enrolled[0]
        || null;
      const selectedId = selected ? Number(selected.id) : null;
      setCourseId(selectedId);
      if (selectedId !== null) {
        try { window.localStorage.setItem('qe:student:activeCourseId', String(selectedId)); } catch {}
      }
    }).catch(error => {
      if (!active) return;
      if ((error as any)?.auth) {
        window.location.assign('/login/student');
        return;
      }
      setCourses([]);
      setCourseId(null);
      setCourseError(error instanceof Error ? error.message : 'Your courses could not be loaded.');
    }).finally(() => { if (active) setLoadingCourses(false); });
    return () => { active = false; };
  }, [requestedCourseId]);

  const selectCourse = useCallback((value: string | number) => {
    const candidate = Number(value);
    if (!Number.isSafeInteger(candidate) || candidate <= 0 ||
        !courses.some(course => Number(course.id) === candidate)) return;
    setCourseId(candidate);
    try { window.localStorage.setItem('qe:student:activeCourseId', String(candidate)); } catch {}
  }, [courses]);

  const activeCourse = courses.find(course => Number(course.id) === courseId) || null;
  return { courses, activeCourse, courseId, selectCourse, loadingCourses, courseError };
}

export function PrimaryCoursePicker({
  courses, courseId, onChange, loading = false, label = 'Primary course',
}: {
  courses: EnrolledCourse[];
  courseId: number | null;
  onChange: (value: string) => void;
  loading?: boolean;
  label?: string;
}) {
  if (loading) return <div className="qe-primary-course-scope" role="status">{label}: loading…</div>;
  if (!courses.length) return (
    <div className="qe-primary-course-scope" role="status">
      <span>No active course is available for learning resources.</span>
      <Link href="/student/courses">Review All Courses</Link>
      <Link href="/student/marketplace">Browse Marketplace</Link>
    </div>
  );
  return (
    <label className="qe-primary-course-scope">
      <span>{label}</span>
      <select value={courseId === null ? '' : String(courseId)} onChange={event => onChange(event.target.value)}>
        {courses.map(course => (
          <option key={course.id} value={String(course.id)}>
            {course.display_name || course.code || 'Course'}{course.public_id ? ' · ' + course.public_id : ''}
          </option>
        ))}
      </select>
    </label>
  );
}
