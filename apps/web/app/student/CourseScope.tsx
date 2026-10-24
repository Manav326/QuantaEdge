'use client';


import { apiFetch } from '../components/apiClient';
import { useCallback, useEffect, useRef, useState } from 'react';
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
  course_type_code?: string;
  delivery_mode?: string;
  layout_schema_version?: string;
  is_enrolled: boolean;
  enrolled?: boolean;
  paused?: boolean;
  purchased?: boolean;
  has_access?: boolean;
  subjects?: EnrolledCourseSubject[];
};

export function useStudentCourseScope(requestedCourseId: string | number | null = null) {
  const [courses, setCourses] = useState<EnrolledCourse[]>([]);
  const [courseId, setCourseId] = useState<number | null>(null);
  const [loadingCourses, setLoadingCourses] = useState(true);
  const [courseError, setCourseError] = useState('');
  const [activatingCourseId, setActivatingCourseId] = useState<number | null>(null);
  const coursesRef = useRef<EnrolledCourse[]>([]);
  const courseIdRef = useRef<number | null>(null);
  // Public course IDs are stable eight-character alphanumeric identities. Numeric database
  // row IDs remain accepted for older links, but newly generated learning links use public_id.
  const requestedText = String(requestedCourseId ?? '').trim();
  const hasRequestedCourse = requestedText.length > 0;
  const matchesRequestedCourse = useCallback((course: EnrolledCourse) => {
    if (!hasRequestedCourse) return false;
    if (/^\d+$/.test(requestedText)) return Number(course.id || course.course_id) === Number(requestedText);
    return String(course.public_id || '').toUpperCase() === requestedText.toUpperCase()
      || String(course.code || '').toUpperCase() === requestedText.toUpperCase();
  }, [hasRequestedCourse, requestedText]);

  // Reuse the current catalogue when the URL changes only to preserve course/subject selection.
  coursesRef.current = courses;
  courseIdRef.current = courseId;

  useEffect(() => {
    let active = true;
    const cachedCourses = coursesRef.current;
    if (cachedCourses.length > 0) {
      setCourseError('');
      const saved = typeof window === 'undefined' ? 0 : Number(window.localStorage.getItem('qe:student:activeCourseId') || 0);
      const selected = hasRequestedCourse
        ? cachedCourses.find(matchesRequestedCourse) || null
        : cachedCourses.find(course => Number(course.id) === courseIdRef.current)
          || cachedCourses.find(course => Number(course.id) === saved)
          || cachedCourses.find(course => course.is_enrolled === true || course.enrolled === true)
          || cachedCourses[0]
          || null;
      if (hasRequestedCourse && !selected) {
        setCourseId(null);
        setCourseError('This course is not enrolled in your current institution account.');
        setLoadingCourses(false);
        return () => { active = false; };
      }
      const selectedId = selected ? Number(selected.id) : null;
      setCourseId(selectedId);
      if (selectedId !== null) {
        try { window.localStorage.setItem('qe:student:activeCourseId', String(selectedId)); } catch {}
      }
      setLoadingCourses(false);
      return () => { active = false; };
    }
    setLoadingCourses(true);
    setCourseError('');
    apiFetch('/api/v1/students/me/programs', {
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
      // Keep the stable numeric row ID for internal APIs and DB-bound operations. The
      // fixed-width public_id is the external identity used in learner-facing URLs.
      const normalized = rows.map(course => ({
        ...course,
        id: Number(course.id || course.course_id),
      }));
      // Focus is not enrollment. Only courses actually enrolled (or pre-migration paused)
      // for this authenticated institution are valid report/lesson destinations.
      const enrolled = normalized.filter(course =>
        (course.is_enrolled === true || course.paused === true) &&
        Number.isSafeInteger(Number(course.id)) && Number(course.id) > 0);
      if (!active) return;
      setCourses(enrolled);
      const saved = typeof window === 'undefined' ? 0 : Number(window.localStorage.getItem('qe:student:activeCourseId') || 0);
      const selected = hasRequestedCourse
        ? enrolled.find(matchesRequestedCourse) || null
        : enrolled.find(course => Number(course.id) === saved)
          || enrolled.find(course => course.is_enrolled === true || course.enrolled === true)
          || enrolled[0]
          || null;
      if (hasRequestedCourse && !selected) {
        setCourseId(null);
        setCourseError('This course is not enrolled in your current institution account.');
        return;
      }
      const selectedId = selected ? Number(selected.id) : null;
      if (selected && selectedId !== null && selected.is_enrolled !== true) {
        // Legacy paused enrollment rows need one transition to ACTIVE. The server no
        // longer pauses other courses, so this does not change the user's primary focus.
        const activateResponse = await apiFetch('/api/v1/students/me/programs/id/' + selectedId + '/enroll', {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'STUDENT' },
          body: '{}',
        });
        const activateRaw = await activateResponse.text();
        let activateBody: any = {};
        try { activateBody = activateRaw ? JSON.parse(activateRaw) : {}; } catch { activateBody = { message: activateRaw }; }
        if (activateResponse.status === 401 || activateResponse.status === 403) {
          throw Object.assign(new Error('Student sign-in is required.'), { auth: true });
        }
        if (!activateResponse.ok) throw new Error(activateBody.detail || activateBody.message || activateBody.error || 'The selected course could not be activated.');
        setCourses(previous => previous.map(course => Number(course.id) === selectedId
          ? { ...course, is_enrolled: true, enrolled: true, paused: false }
          : course));
      }
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
  }, [requestedCourseId, hasRequestedCourse, matchesRequestedCourse]);

  const selectCourse = useCallback(async (value: string | number) => {
    const candidate = Number(value);
    const course = courses.find(item => Number(item.id) === candidate);
    if (!Number.isSafeInteger(candidate) || candidate <= 0 || !course || activatingCourseId !== null) return;
    if (candidate === courseId) return;
    setActivatingCourseId(candidate);
    setCourseError('');
    try {
      // Switching the primary/focused course must never enrol the learner again or
      // pause another course. Only reactivate a pre-migration paused enrollment.
      if (course.is_enrolled !== true) {
        const response = await apiFetch('/api/v1/students/me/programs/id/' + candidate + '/enroll', {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'STUDENT' },
          body: '{}',
        });
        const raw = await response.text();
        let body: any = {};
        try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
        if (response.status === 401 || response.status === 403) {
          window.location.assign('/login/student');
          return;
        }
        if (!response.ok) throw new Error(body.detail || body.message || body.error || 'This course could not be activated.');
        setCourses(previous => previous.map(item => Number(item.id) === candidate
          ? { ...item, is_enrolled: true, paused: false }
          : item));
      }
      setCourseId(candidate);
      try { window.localStorage.setItem('qe:student:activeCourseId', String(candidate)); } catch {}
    } catch (error) {
      setCourseError(error instanceof Error ? error.message : 'This course could not be activated.');
    } finally {
      setActivatingCourseId(null);
    }
  }, [courses, courseId, activatingCourseId]);

  const activeCourse = courses.find(course => Number(course.id) === courseId) || null;
  return { courses, activeCourse, courseId, selectCourse, loadingCourses: loadingCourses || activatingCourseId !== null, courseError };
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
      <span>No owned course is available for learning resources.</span>
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
