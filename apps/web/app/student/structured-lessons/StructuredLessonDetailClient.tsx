'use client';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale } from '../../components/LanguageProvider';
import StructuredLessonExperience from './StructuredLessonExperience';
import type { QuestionResult, QuestionResponse } from './StructuredQuestionRenderer';
import type { StructuredLesson, StructuredQuestion } from './contract';

type SavedQuestion = { response: QuestionResponse; status: QuestionResult['status']; score?: number; maxScore?: number; feedback?: string; feedbackByLocale?: Partial<Record<'hi' | 'en', string>> };
type SavedResponses = { questions?: Record<string, SavedQuestion>; activities?: Record<string, Record<string, string>> };
export default function StructuredLessonDetailClient({ artifactId }: { artifactId: string }) {
  const router = useRouter();
  const { locale, learningLanguage } = useLocale();
  const [lesson, setLesson] = useState<StructuredLesson | null>(null);
  const [saved, setSaved] = useState<SavedResponses>({});
  const [busy, setBusy] = useState(true);
  const [error, setError] = useState('');
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  useEffect(() => {
    let active = true;
    setBusy(true); setError('');
    (async () => {
      try {
        const base = '/api/v1/learning/structured-lessons/' + encodeURIComponent(artifactId);
        const [lessonResponse, savedResponse] = await Promise.all([
          fetch(base + '?language=' + learningLanguage, { credentials: 'same-origin', cache: 'no-store' }),
          fetch(base + '/responses?language=' + learningLanguage, { credentials: 'same-origin', cache: 'no-store' }),
        ]);
        if ([lessonResponse, savedResponse].some(r => r.status === 401 || r.status === 403)) { router.replace('/login/student'); return; }
        const body = await lessonResponse.json();
        if (!lessonResponse.ok) throw new Error(body.message || body.detail || body.error || tx('यह lesson अभी उपलब्ध नहीं है।', 'This lesson is not available yet.'));
        const savedBody = savedResponse.ok ? await savedResponse.json() : {};
        if (!savedResponse.ok && savedResponse.status !== 404) throw new Error(savedBody.message || savedBody.detail || tx('Saved progress load नहीं हुआ।', 'Saved progress could not be loaded.'));
        if (active) { setLesson(body as StructuredLesson); setSaved(savedBody || {}); }
      } catch (error) {
        if (active) setError(error instanceof Error ? error.message : tx('Lesson load नहीं हो पाया।', 'The lesson could not be loaded.'));
      } finally { if (active) setBusy(false); }
    })();
    return () => { active = false; };
  }, [artifactId, learningLanguage, locale, router]);
  async function submitQuestion(question: StructuredQuestion, response: QuestionResponse): Promise<QuestionResult> {
    const http = await fetch('/api/v1/learning/structured-lessons/' + encodeURIComponent(artifactId) +
      '/questions/' + encodeURIComponent(question.id) + '/responses?language=' + learningLanguage, {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ response }),
      });
    const body = await http.json();
    if (!http.ok) throw new Error(body.message || body.detail || body.error || tx('उत्तर सहेजा नहीं गया।', 'The response was not saved.'));
    return { status: body.status, score: body.score, maxScore: body.maxScore, autoGraded: body.autoGraded,
      feedback: body.feedback, feedbackByLocale: body.feedbackByLocale } as QuestionResult;
  }
  async function saveActivity(blockId: string, value: string): Promise<void> {
    const http = await fetch('/api/v1/learning/structured-lessons/' + encodeURIComponent(artifactId) +
      '/activities/' + encodeURIComponent(blockId) + '/responses', {
        method: 'POST', credentials: 'same-origin', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ response: value }),
      });
    const body = await http.json();
    if (!http.ok) throw new Error(body.message || body.detail || body.error || tx('Activity response सहेजा नहीं गया।', 'Activity response was not saved.'));
  }
  const responses: Record<string, QuestionResponse> = {};
  const results: Record<string, QuestionResult> = {};
  const submitted: Record<string, boolean> = {};
  Object.entries(saved.questions || {}).forEach(([id, value]) => {
    responses[id] = value.response;
    results[id] = { status: value.status, score: value.score, maxScore: value.maxScore, feedback: value.feedback, feedbackByLocale: value.feedbackByLocale };
    submitted[id] = value.status === 'correct' || value.status === 'pending_review';
  });
  return <main className="app-shell"><div className="app-layout"><section className="app-content">
    <header className="content-heading"><Link href="/student/structured-lessons" className="back-link">← {tx('सभी structured lessons', 'All structured lessons')}</Link>
      {lesson && <p>{lesson.chapter.title_by_locale?.[learningLanguage] || ''}</p>}</header>
    {error ? <><p role="alert" className="error-message">{error}</p><Link href="/student/structured-lessons" className="button button-dark">{tx('वापस जाएँ', 'Go back')}</Link></> :
      busy || !lesson ? <p role="status" className="eyebrow">{tx('Lesson खुल रहा है…', 'Loading lesson…')}</p> :
      <StructuredLessonExperience key={artifactId} lesson={lesson} locale={learningLanguage} mode="student"
        onQuestionSubmit={submitQuestion} onActivitySave={saveActivity} initialResponses={responses}
        initialResults={results} initialSubmitted={submitted} initialActivityResponses={saved.activities || {}} />}
  </section></div></main>;
}
