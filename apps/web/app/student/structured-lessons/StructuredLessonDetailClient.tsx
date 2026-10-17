'use client';
import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
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
        if (!lessonResponse.ok) throw new Error(body.message || body.detail || body.error || translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.this.lesson.is.not.available.yet.09b6c21c'));
        const savedBody = savedResponse.ok ? await savedResponse.json() : {};
        if (!savedResponse.ok && savedResponse.status !== 404) throw new Error(savedBody.message || savedBody.detail || translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.saved.progress.could.not.be.loaded.72d606a2'));
        if (active) { setLesson(body as StructuredLesson); setSaved(savedBody || {}); }
      } catch (error) {
        if (active) setError(error instanceof Error ? error.message : translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.the.lesson.could.not.be.loaded.1765a64a'));
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
    if (!http.ok) throw new Error(body.message || body.detail || body.error || translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.the.response.was.not.saved.05e6bba1'));
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
    if (!http.ok) throw new Error(body.message || body.detail || body.error || translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.activity.response.was.not.saved.9ff3c1d7'));
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
    <header className="content-heading"><Link href="/student/structured-lessons" className="back-link">← {translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.all.structured.lessons.5f389f55')}</Link>
      {lesson && <p>{lesson.chapter.title_by_locale?.[learningLanguage] || ''}</p>}</header>
    {error ? <><p role="alert" className="error-message">{error}</p><Link href="/student/structured-lessons" className="button button-dark">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.go.back.f75fdd65')}</Link></> :
      busy || !lesson ? <p role="status" className="eyebrow">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.loading.lesson.9e7c2f6c')}</p> :
      <StructuredLessonExperience key={artifactId} lesson={lesson} locale={learningLanguage} mode="student"
        onQuestionSubmit={submitQuestion} onActivitySave={saveActivity} initialResponses={responses}
        initialResults={results} initialSubmitted={submitted} initialActivityResponses={saved.activities || {}} />}
  </section></div></main>;
}
