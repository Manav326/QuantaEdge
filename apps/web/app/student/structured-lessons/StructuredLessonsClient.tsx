'use client';

import { apiFetch } from '../../components/apiClient';
import { UiText } from '../../i18n/useUiTranslation';
import { useLocale } from '../../components/LanguageProvider';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useStudentCourseScope } from '../CourseScope';
import StudentEmptyState from '../components/StudentEmptyState';

/**
 * The legacy navigation label now means Resume Lesson.
 * Resume uses the same database lesson screen and ordered lesson_block records as Learn → Lessons and Topics.
 * This page deliberately does not load/render the separate structured-artifact catalogue.
 */
export default function StructuredLessonsClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const requestedCourseId = searchParams.get('courseId') || null;
  const requestedSubject = searchParams.get('subjectCode') || '';
  const { courses, activeCourse, courseId, loadingCourses, courseError } = useStudentCourseScope(requestedCourseId);
  const { locale, learningLanguage } = useLocale();
  const [error, setError] = useState('');

  useEffect(() => {
    if (loadingCourses) return;
    if (courseId === null) {
      setError(courseError || (locale === 'english'
        ? 'Choose an enrolled course before resuming a lesson.'
        : 'पाठ फिर से शुरू करने के लिए नामांकित कोर्स चुनें।'));
      return;
    }

    let cancelled = false;
    async function resume() {
      setError('');
      const publicCourseId = String(activeCourse?.public_id || courseId);
      let lessonId = 0;

      // Record written by the canonical Learn screen after a lesson is opened.
      try {
        const saved = JSON.parse(localStorage.getItem(
          'qe:student:last-opened-lesson:course-' + publicCourseId
        ) || '{}');
        if (String(saved.courseId || '') === publicCourseId) {
          const savedLessonId = Number(saved.lessonId);
          if (Number.isSafeInteger(savedLessonId) && savedLessonId > 0) lessonId = savedLessonId;
        }
      } catch {
        // An invalid local bookmark must never prevent a learner from opening the catalogue.
      }

      if (!lessonId) {
        // Backward compatibility: older builds stored one lesson per subject but did not
        // record which subject was opened most recently. Prefer the requested subject when
        // supplied; otherwise choose the first published lesson in this course.
        const query = new URLSearchParams({
          language: learningLanguage,
          courseId: String(courseId),
        });
        if (requestedSubject && requestedSubject !== 'ALL') query.set('subjectCode', requestedSubject);
        const response = await apiFetch('/api/v1/learning/lessons?' + query.toString(), {
          cache: 'no-store',
          credentials: 'same-origin',
        });
        if (response.status === 401 || response.status === 403) {
          router.replace('/login/student');
          return;
        }
        const body = await response.json().catch(() => []);
        if (!response.ok) throw new Error(body?.message || body?.detail || 'Could not find a lesson to resume.');
        const rows = Array.isArray(body) ? body : [];
        const first = rows[0];
        lessonId = Number(first?.id || 0);
      }

      if (!Number.isSafeInteger(lessonId) || lessonId <= 0) {
        throw new Error(locale === 'english'
          ? 'There are no published lessons available in this course yet.'
          : 'इस कोर्स में अभी कोई प्रकाशित पाठ उपलब्ध नहीं है।');
      }

      if (cancelled) return;
      // Keep subjectCode=ALL in the return URL so Back opens Lessons and Topics in its
      // course-wide mode. Learn itself resolves the lesson's actual subject for prev/next.
      const target = new URLSearchParams({
        courseId: publicCourseId,
        subjectCode: 'ALL',
        lessonId: String(lessonId),
      });
      router.replace('/student/learn?' + target.toString());
    }

    void resume().catch((cause: unknown) => {
      if (!cancelled) setError(cause instanceof Error ? cause.message : 'Could not resume the last lesson.');
    });
    return () => { cancelled = true; };
  }, [activeCourse?.public_id, courseId, courseError, learningLanguage, loadingCourses, locale, requestedSubject, router]);

  // Match the other learner screens: a successful empty enrollment list gets a clear
  // marketplace path, while real API errors stay visible as errors.
  const noEnrolledCourses = !loadingCourses && courses.length === 0 &&
    (!courseError || courseError === 'This course is not enrolled in your current institution account.');
  if (noEnrolledCourses) return <main className="app-shell"><div className="app-layout"><section className="app-content">
    <header className="content-heading"><h1><UiText message="student.empty.learn.title" /></h1></header>
    <StudentEmptyState icon="learn"
      title={<UiText message="student.empty.learn.title" />}
      message={<UiText message="student.empty.learn.message" />}
      primaryActionHref="/student/marketplace"
      primaryActionLabel={<UiText message="student.empty.dashboard.action" />}
      secondaryActionHref="/student/courses"
      secondaryActionLabel={<UiText message="web.navigation.student.allCourses" />} />
  </section></div></main>;

  if (error) {
    return <main className="lesson-page"><section className="lesson-wrap">
      <div className="auth-card" role="alert">
        <h1>{locale === 'english' ? 'Resume lesson' : 'पाठ फिर से शुरू करें'}</h1>
        <p>{error}</p>
        <Link href={'/student/learn' + (courseId !== null ? '?courseId=' + encodeURIComponent(String(activeCourse?.public_id || courseId)) : '')}
          className="button button-dark">
          {locale === 'english' ? 'Open Lessons and Topics' : 'पाठ और विषय खोलें'}
        </Link>
        <Link href="/student/courses" className="text-link">
          <UiText message="web.navigation.student.allCourses" />
        </Link>
      </div>
    </section></main>;
  }

  return <main className="lesson-page"><section className="lesson-wrap">
    <div className="eyebrow">{locale === 'english' ? 'Resume lesson' : 'पाठ फिर से शुरू करें'}</div>
    <h1>{locale === 'english' ? 'Opening your last lesson…' : 'आपका पिछला पाठ खोला जा रहा है…'}</h1>
    <p className="lesson-intro">{locale === 'english'
      ? 'Your saved lesson opens in the same lesson view used by Lessons and Topics.'
      : 'आपका सुरक्षित पाठ उसी दृश्य में खुलेगा जो पाठ और विषय में उपयोग होता है।'}</p>
  </section></main>;
}
