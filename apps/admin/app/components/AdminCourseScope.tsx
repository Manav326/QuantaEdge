'use client';


import { apiFetch } from './apiClient';
import { useCallback, useEffect, useMemo, useState } from 'react';

export type AdminCourseSubject = {
  id?: number;
  subject_id?: number;
  subject_code: string;
  subject_name: string;
  curriculum_class_code?: string;
  curriculum_class_name?: string;
  course_subject_sort_order?: number;
};

export type AdminCourse = {
  id: number;
  course_id: number;
  code: string;
  display_name: string;
  curriculum_version?: string;
  curriculum_class_code?: string;
  release_id?: number;
  release_version?: number;
  release_state?: string;
  source_institution_id?: number;
  course_relationship?: 'SOURCED' | 'SHARED' | string;
  subjects: AdminCourseSubject[];
};

type AdminCourseScope = {
  courses: AdminCourse[];
  courseId: string;
  activeCourse: AdminCourse | null;
  subjects: AdminCourseSubject[];
  loadingCourses: boolean;
  courseError: string;
  selectCourse: (nextCourseId: string) => boolean;
  reloadCourses: () => Promise<void>;
};

/**
 * Loads only courses released/assigned to the authenticated admin's resolved institution.
 * Browser storage is merely a preferred selection: it is accepted only when the server's
 * current institution-scoped catalogue contains that exact course.
 */
export function useAdminCourseScope(storageKey = 'qe:admin:activeCourseId'): AdminCourseScope {
  const [courses, setCourses] = useState<AdminCourse[]>([]);
  const [courseId, setCourseId] = useState('');
  const [loadingCourses, setLoadingCourses] = useState(true);
  const [courseError, setCourseError] = useState('');

  const reloadCourses = useCallback(async () => {
    setLoadingCourses(true);
    setCourseError('');
    try {
      const response = await apiFetch('/api/v1/admin/content/programs', {
        credentials: 'same-origin',
        cache: 'no-store',
      });
      const raw = await response.text();
      let body: unknown;
      try { body = raw ? JSON.parse(raw) : []; } catch { body = []; }
      if (!response.ok) {
        const detail = body && typeof body === 'object'
          ? String((body as { detail?: unknown; message?: unknown }).detail
            || (body as { message?: unknown }).message || '')
          : '';
        throw new Error(detail || 'Could not load courses assigned to this institution.');
      }
      const rows = Array.isArray(body) ? body : [];
      const normalized: AdminCourse[] = rows.flatMap((row: any) => {
        const id = Number(row?.course_id || row?.id);
        if (!Number.isSafeInteger(id) || id <= 0) return [];
        const subjects: AdminCourseSubject[] = (Array.isArray(row.subjects) ? row.subjects : [])
          .flatMap((subject: any) => {
            const subjectCode = String(subject?.subject_code || subject?.code || '').trim();
            if (!subjectCode) return [];
            return [{
              id: Number(subject?.id || subject?.subject_id) || undefined,
              subject_id: Number(subject?.subject_id || subject?.id) || undefined,
              subject_code: subjectCode,
              subject_name: String(subject?.subject_name || subject?.display_name || subjectCode),
              curriculum_class_code: subject?.curriculum_class_code ? String(subject.curriculum_class_code) : undefined,
              curriculum_class_name: subject?.curriculum_class_name ? String(subject.curriculum_class_name) : undefined,
              course_subject_sort_order: Number(subject?.course_subject_sort_order) || undefined,
            }];
          });
        return [{
          id,
          course_id: id,
          code: String(row?.code || ''),
          display_name: String(row?.display_name || row?.code || 'Course'),
          curriculum_version: row?.curriculum_version ? String(row.curriculum_version) : undefined,
          curriculum_class_code: row?.curriculum_class_code ? String(row.curriculum_class_code) : undefined,
          release_id: Number(row?.release_id) || undefined,
          release_version: Number(row?.release_version) || undefined,
          release_state: row?.release_state ? String(row.release_state) : undefined,
          source_institution_id: Number(row?.source_institution_id) || undefined,
          course_relationship: row?.course_relationship === 'SOURCED' ? 'SOURCED' : 'SHARED',
          subjects,
        }];
      });
      setCourses(normalized);
      const requested = new URLSearchParams(window.location.search).get('courseId') || '';
      if (requested && !normalized.some(course => String(course.id) === requested)) {
        // An explicit deep-link is authoritative: never silently redirect it to
        // the first/local-storage course. Leave the picker empty so staff must choose.
        setCourseId('');
        try { window.localStorage.removeItem(storageKey); } catch { /* storage may be disabled */ }
        const url = new URL(window.location.href);
        url.searchParams.delete('courseId');
        window.history.replaceState({}, '', url.toString());
      } else {
        setCourseId(current => {
          let remembered = '';
          try { remembered = window.localStorage.getItem(storageKey) || ''; } catch { /* storage may be disabled */ }
          const candidate = [requested, current, remembered].find(value =>
            value && normalized.some(course => String(course.id) === value)
          );
          const next = candidate || String(normalized[0]?.id || '');
          try {
            if (next) window.localStorage.setItem(storageKey, next);
            else window.localStorage.removeItem(storageKey);
          } catch { /* server catalogue remains authoritative */ }
          return next;
        });
      }
    } catch (error) {
      setCourses([]);
      setCourseId('');
      setCourseError(error instanceof Error ? error.message : 'Could not load institution courses.');
    } finally {
      setLoadingCourses(false);
    }
  }, [storageKey]);

  useEffect(() => { void reloadCourses(); }, [reloadCourses]);

  const activeCourse = useMemo(
    () => courses.find(course => String(course.id) === courseId) || null,
    [courses, courseId],
  );
  const subjects = activeCourse?.subjects || [];

  const selectCourse = useCallback((nextCourseId: string) => {
    if (!courses.some(course => String(course.id) === nextCourseId)) return false;
    setCourseId(nextCourseId);
    try { window.localStorage.setItem(storageKey, nextCourseId); } catch { /* optional convenience only */ }
    const url = new URL(window.location.href);
    url.searchParams.set('courseId', nextCourseId);
    window.history.replaceState({}, '', url.toString());
    return true;
  }, [courses, storageKey]);

  return { courses, courseId, activeCourse, subjects, loadingCourses, courseError, selectCourse, reloadCourses };
}

type AdminCourseSelectorProps = {
  courses: AdminCourse[];
  courseId: string;
  loading?: boolean;
  label: string;
  placeholder: string;
  onChange: (courseId: string) => void;
  disabled?: boolean;
};

export function AdminCourseSelector({
  courses, courseId, loading = false, label, placeholder, onChange, disabled = false,
}: AdminCourseSelectorProps) {
  return (
    <label style={{ display: 'grid', gap: 5, minWidth: 220, maxWidth: 520, fontSize: 13, fontWeight: 600 }}>
      <span>{label}</span>
      <select
        value={courses.some(course => String(course.id) === courseId) ? courseId : ''}
        onChange={event => onChange(event.target.value)}
        disabled={loading || disabled || courses.length === 0}
        style={{ minWidth: 220, padding: '10px 12px', border: '1px solid var(--admin-border,#d8dce5)', borderRadius: 10, background: 'var(--admin-surface,#fff)', color: 'var(--admin-text,#1b2430)' }}
      >
        <option value="">{loading ? 'Loading courses…' : placeholder}</option>
        {courses.map(course => (
          <option key={course.id} value={String(course.id)}>
            {course.display_name}{course.curriculum_version ? ' · ' + course.curriculum_version : ''} · {course.course_relationship === 'SOURCED' ? 'Sourced' : 'Shared (read-only)'}
          </option>
        ))}
      </select>
      {(() => {
        const active = courses.find(course => String(course.id) === courseId);
        if (!active || loading) return null;
        return active.course_relationship === 'SOURCED'
          ? <small role="status">Sourced by this institution · content editing is available according to your permissions.</small>
          : <small role="status">Shared from another institution · view-only here. The source institution owns content changes; HRMS controls Marketplace availability.</small>;
      })()}
      {!loading && courses.length > 0 && !courseId &&
        <small role="status">The requested course is not assigned here. Select an assigned course.</small>}
    </label>
  );
}
