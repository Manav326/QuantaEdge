'use client';

import { apiFetch } from '../../app/components/apiClient';
import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';
import { useStudentCourseScope } from '../CourseScope';
import InstitutionCourseSubjectScope from '../InstitutionCourseSubjectScope';

type LessonItem = {
  artifactId: string; subjectCode: string; chapterNumber: number;
  chapterTitle: string; topicId: string; topicNumber: number; title: string; estimatedMinutes: number;
};
export default function StructuredLessonsClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const requestedCourseText = searchParams.get('courseId') || '';
  const requestedCourseId = requestedCourseText || null;
  const { courses, activeCourse, courseId, selectCourse, loadingCourses, courseError } = useStudentCourseScope(requestedCourseId);
  const requestedSubjectCode = searchParams.get('subjectCode') || 'ALL';
  const [subjectCode, setSubjectCode] = useState(requestedSubjectCode);
  const { locale, learningLanguage } = useLocale();
  const [items, setItems] = useState<LessonItem[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const loadCatalogue = useCallback(async () => {
    setBusy(true); setError('');
    if (courseId === null) {
      setItems([]);
      setBusy(false);
      if (!loadingCourses) setError(courseError || (locale === 'english' ? 'Select an enrolled course to view its approved lessons.' : 'स्वीकृत पाठ देखने के लिए नामांकित कोर्स चुनें।'));
      return;
    }
    try {
      const query = new URLSearchParams({ language: learningLanguage, courseId: String(activeCourse?.public_id || courseId) });
      if (subjectCode !== 'ALL') query.set('subjectCode', subjectCode);
      const response = await apiFetch('/api/v1/learning/structured-lessons?' + query.toString(),
        { credentials: 'same-origin', cache: 'no-store' });
      if (response.status === 401 || response.status === 403) { router.replace('/login/student'); return; }
      const body = await response.json();
      if (!response.ok) throw new Error(body.message || body.detail || body.error || translateUi(locale, 'legacy.web.structured.lessons.could.not.be.loaded.607c9597'));
      setItems(Array.isArray(body) ? body : []);
    } catch (error) {
      setError(error instanceof Error ? error.message : translateUi(locale, 'legacy.web.structured.lessons.could.not.be.loaded.607c9597'));
    } finally { setBusy(false); }
  }, [learningLanguage, locale, router, courseId, activeCourse?.public_id, subjectCode, loadingCourses, courseError]);
  useEffect(() => { void loadCatalogue(); }, [loadCatalogue]);
  useEffect(() => {
    if (subjectCode === 'ALL') return;
    const available = (activeCourse?.subjects || []).some(subject => String(subject.subject_code || subject.code || '') === subjectCode);
    if (!available) setSubjectCode('ALL');
  }, [courseId, activeCourse, subjectCode]);
  async function changeCourse(value: string) {
    await selectCourse(value);
    const params = new URLSearchParams(searchParams.toString());
    params.set('courseId', courses.find(course => Number(course.id) === Number(value))?.public_id || value);
    params.delete('subjectCode');
    window.history.replaceState(null, '', '/student/structured-lessons?' + params.toString());
    setSubjectCode('ALL');
  }
  function changeSubject(value: string) {
    setSubjectCode(value);
    const params = new URLSearchParams(searchParams.toString());
    if (courseId !== null) params.set('courseId', String(activeCourse?.public_id || courseId));
    if (value === 'ALL') params.delete('subjectCode'); else params.set('subjectCode', value);
    window.history.replaceState(null, '', '/student/structured-lessons?' + params.toString());
  }
  return <main className="app-shell"><div className="app-layout"><section className="app-content">
    <header className="content-heading qe-lesson-list-header"><div>
      <span className="eyebrow"><UiText message="legacy.web.bilingual.lesson.format.349a3c45" /></span>
      <h1><UiText message="web.navigation.student.structuredLessons" /></h1>
      <p><UiText message="legacy.web.explain.visuals.activities.and.one.que.c789dbec" /></p>
    </div>
    <div className="qe-course-page-actions"><InstitutionCourseSubjectScope courses={courses} activeCourse={activeCourse} courseId={courseId} onCourseChange={value => void changeCourse(value)} subjectCode={subjectCode} onSubjectChange={changeSubject} loading={loadingCourses} language={locale} courseLabel={locale === 'english' ? 'Course' : 'कोर्स'} subjectLabel={locale === 'english' ? 'Subject' : 'विषय'} allSubjectsLabel={locale === 'english' ? 'All subjects' : 'सभी विषय'} /><button type="button" className="qe-icon-action" onClick={() => void loadCatalogue()} disabled={busy} aria-label={locale === 'english' ? 'Reload lessons' : 'पाठ फिर से लोड करें'} title={locale === 'english' ? 'Reload lessons' : 'पाठ फिर से लोड करें'}><svg aria-hidden="true" viewBox="0 0 24 24" focusable="false"><path d="M20 7v5h-5M4 17v-5h5"/><path d="M5.6 9A7 7 0 0 1 17.2 6L20 12M4 12l2.8 6A7 7 0 0 0 18.4 15"/></svg></button></div></header>
    {courseError && !loadingCourses && <p role="alert" className="error-message">{courseError}</p>}{error && <p role="alert" className="error-message">{error}</p>}
    {busy ? <p role="status" className="eyebrow">{translateUi(locale, 'legacy.web.finding.published.lessons.9f844f1e')}</p> :
      items.length === 0 ? <section className="auth-card" role="status">
        <h2>{translateUi(locale, 'legacy.web.no.approved.structured.lessons.are.ava.a3717bf5')}</h2>
        <p>{translateUi(locale, 'legacy.web.lessons.appear.here.only.after.content.88a83834')}</p>
        <Link href={'/student/courses' + (courseId !== null ? '?focus=' + encodeURIComponent(String(courseId)) : '')} className="button button-dark">{locale === 'english' ? 'Review your course' : 'अपना कोर्स देखें'}</Link>
      </section> :
      <div className="task-list">{items.map(item => <article className="task-card" key={item.artifactId}>
        <div><span className="qe-course-subject-context">{activeCourse?.display_name || (locale === 'english' ? 'Selected course' : 'चुना गया कोर्स')} · {(activeCourse?.subjects || []).find(subject => String(subject.subject_code || subject.code || '') === item.subjectCode)?.subject_name || item.subjectCode}</span>
          <h2>{item.title}</h2><p>{translateUi(locale, 'legacy.web.chapter.0a3bff81')} {item.chapterNumber}: {item.chapterTitle} · {translateUi(locale, 'legacy.web.topic.1a9696fe')} {item.topicNumber}</p>
          {item.estimatedMinutes > 0 && <small>{item.estimatedMinutes} {translateUi(locale, 'legacy.web.min.72242533')}</small>}</div>
        <Link href={'/student/structured-lessons/' + encodeURIComponent(item.artifactId) + '?courseId=' + encodeURIComponent(String(activeCourse?.public_id || courseId || '')) + '&subjectCode=' + encodeURIComponent(item.subjectCode)} className="button button-dark">{translateUi(locale, 'legacy.web.open.lesson.bca8f87b')}</Link>
      </article>)}</div>}
  </section></div></main>;
}
