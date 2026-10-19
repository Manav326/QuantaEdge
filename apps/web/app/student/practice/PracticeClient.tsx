'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';
import { useStudentCourseScope } from '../CourseScope';
import InstitutionCourseSubjectScope from '../InstitutionCourseSubjectScope';

type PracticeTopic = {
  artifactId: string;
  subjectCode: string;
  chapterNumber: number;
  chapterTitle: string;
  topicId: string;
  topicNumber: number;
  title: string;
  estimatedMinutes: number;
};

export default function PracticeClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const rawCourseId = searchParams.get('courseId') || '';
  const requestedCourseId = /^\d+$/.test(rawCourseId) && Number(rawCourseId) > 0 ? Number(rawCourseId) : null;
  const { courses, activeCourse, courseId, selectCourse, loadingCourses, courseError } = useStudentCourseScope(requestedCourseId);
  const { locale, learningLanguage } = useLocale();
  const requestedSubject = searchParams.get('subjectCode') || 'ALL';
  const [subjectCode, setSubjectCode] = useState(requestedSubject);
  const [topics, setTopics] = useState<PracticeTopic[]>([]);
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const tx = (english: string, hindi: string) => locale === 'english' ? english : hindi;

  const loadTopics = useCallback(async () => {
    setBusy(true);
    setError('');
    if (courseId === null) {
      setTopics([]);
      setBusy(false);
      if (!loadingCourses) setError(courseError || tx('Choose an enrolled course to view its practice topics.', 'अभ्यास के विषय देखने के लिए नामांकित कोर्स चुनें।'));
      return;
    }
    try {
      const query = new URLSearchParams({ courseId: String(courseId), language: learningLanguage });
      if (subjectCode !== 'ALL') query.set('subjectCode', subjectCode);
      const response = await fetch('/api/v1/learning/structured-lessons?' + query.toString(), {
        credentials: 'same-origin',
        cache: 'no-store',
      });
      if (response.status === 401 || response.status === 403) {
        router.replace('/login/student');
        return;
      }
      const body = await response.json();
      if (!response.ok) throw new Error(body.message || body.detail || body.error || tx('Practice topics could not be loaded.', 'अभ्यास के विषय लोड नहीं हो सके।'));
      setTopics(Array.isArray(body) ? body : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : tx('Practice topics could not be loaded.', 'अभ्यास के विषय लोड नहीं हो सके।'));
      setTopics([]);
    } finally {
      setBusy(false);
    }
  }, [courseId, loadingCourses, courseError, learningLanguage, locale, router, subjectCode]);

  useEffect(() => { void loadTopics(); }, [loadTopics]);
  useEffect(() => {
    if (subjectCode === 'ALL') return;
    const found = (activeCourse?.subjects || []).some(subject => String(subject.subject_code || subject.code || '') === subjectCode);
    if (!found) setSubjectCode('ALL');
  }, [courseId, activeCourse, subjectCode]);

  async function changeCourse(value: string) {
    await selectCourse(value);
    const params = new URLSearchParams();
    params.set('courseId', value);
    window.history.replaceState(null, '', '/student/practice?' + params.toString());
    setSubjectCode('ALL');
  }

  function changeSubject(value: string) {
    setSubjectCode(value);
    const params = new URLSearchParams();
    if (courseId !== null) params.set('courseId', String(courseId));
    if (value !== 'ALL') params.set('subjectCode', value);
    window.history.replaceState(null, '', '/student/practice' + (params.size ? '?' + params.toString() : ''));
  }

  const subjectName = (code: string) => {
    const subject = (activeCourse?.subjects || []).find(item => String(item.subject_code || item.code || '') === code);
    return String(subject?.subject_name || subject?.display_name || code);
  };

  return <main className="app-shell"><div className="app-layout"><section className="app-content">
    <header className="content-heading">
      <div>
        <span className="eyebrow">{tx('Course-based practice', 'कोर्स के अनुसार अभ्यास')}</span>
        <h1>{tx('Practice', 'अभ्यास')}</h1>
        <p>{tx('Choose an institution course first, then a subject. Practice is available inside each approved structured lesson.', 'पहले संस्थान का कोर्स चुनें, फिर विषय। अभ्यास प्रत्येक स्वीकृत structured lesson के अंदर उपलब्ध है।')}</p>
      </div>
      <div className="qe-course-page-actions">
        <InstitutionCourseSubjectScope courses={courses} activeCourse={activeCourse} courseId={courseId}
          onCourseChange={value => void changeCourse(value)} subjectCode={subjectCode} onSubjectChange={changeSubject}
          loading={loadingCourses} language={locale} courseLabel={tx('Course', 'कोर्स')} subjectLabel={tx('Subject', 'विषय')}
          allSubjectsLabel={tx('All subjects', 'सभी विषय')} />
      </div>
    </header>
    {courseError && !loadingCourses && <p role="alert" className="error-message">{courseError}</p>}
    {error && <p role="alert" className="error-message">{error}</p>}
    {busy ? <p role="status" className="eyebrow">{tx('Loading approved practice topics…', 'स्वीकृत अभ्यास विषय लोड हो रहे हैं…')}</p> :
      topics.length === 0 ? <section className="auth-card" role="status">
        <h2>{tx('No approved practice lessons are available yet.', 'अभी कोई स्वीकृत अभ्यास पाठ उपलब्ध नहीं है।')}</h2>
        <p>{tx('Only lessons published for the selected institution and course appear here. Draft lessons are not shown.', 'यहाँ केवल चुने गए संस्थान और कोर्स के प्रकाशित पाठ दिखते हैं। ड्राफ्ट पाठ नहीं दिखाए जाते।')}</p>
        <Link href={'/student/courses' + (courseId !== null ? '?focus=' + encodeURIComponent(String(courseId)) : '')} className="button button-dark">{tx('Review course', 'कोर्स देखें')}</Link>
      </section> :
      <div className="task-list">{topics.map(item => <article className="task-card" key={item.artifactId}>
        <div>
          <span className="eyebrow">{activeCourse?.display_name || tx('Selected course', 'चुना गया कोर्स')} · {subjectName(item.subjectCode)}</span>
          <h2>{item.title}</h2>
          <p>{tx('Chapter', 'अध्याय')} {item.chapterNumber}: {item.chapterTitle} · {tx('Topic', 'विषय')} {item.topicNumber}</p>
          {item.estimatedMinutes > 0 && <small>{item.estimatedMinutes} {tx('min', 'मिनट')}</small>}
        </div>
        <Link href={'/student/structured-lessons/' + encodeURIComponent(item.artifactId) + '?courseId=' + encodeURIComponent(String(courseId || '')) + '&subjectCode=' + encodeURIComponent(item.subjectCode)} className="button button-dark">{tx('Open lesson and practice', 'पाठ और अभ्यास खोलें')}</Link>
      </article>)}</div>}
  </section></div></main>;
}
