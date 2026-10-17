'use client';

import { useMemo, useState } from 'react';
import { useLocale } from '../../components/LanguageProvider';
import { translateUi } from '../../i18n/catalog';
import type { LessonLocale, StructuredLesson, StructuredQuestion } from './contract';
import { isQuestionVisualApproved, readLocale } from './contract';
import StructuredLessonContent from './StructuredLessonContent';
import StructuredQuestionRenderer, { type QuestionResponse, type QuestionResult } from './StructuredQuestionRenderer';
import './structured-lessons.css';

type Props = {
  lesson: StructuredLesson;
  locale: LessonLocale;
  mode?: 'student' | 'admin-preview';
  onActivitySave?: (blockId: string, value: string) => void;
  onQuestionSubmit?: (question: StructuredQuestion, response: QuestionResponse) => Promise<QuestionResult> | QuestionResult;
  initialResponses?: Record<string, QuestionResponse>;
  initialResults?: Record<string, QuestionResult>;
  initialSubmitted?: Record<string, boolean>;
  initialActivityResponses?: Record<string, Record<string, string>>;
};

function normalizeAnswer(value: string, locale: LessonLocale): string {
  const normalized = value.normalize('NFC').trim().replace(/\s+/g, ' ');
  return locale === 'en' ? normalized.toLocaleLowerCase('en') : normalized;
}

function sameSet(left: string[], right: string[]): boolean {
  const a = Array.from(new Set(left)).sort();
  const b = Array.from(new Set(right)).sort();
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function gradeAdminPreview(question: StructuredQuestion, response: QuestionResponse, locale: LessonLocale, uiLocale: unknown): QuestionResult {
  const fallback = readLocale(question.feedback_by_locale, locale) || translateUi(uiLocale, 'legacy.web.answer.checked.in.administrator.previe.022c5449');
  if (question.type === 'short_answer') {
    return { status: 'pending_review', feedback: translateUi(uiLocale, 'legacy.web.short.answers.require.a.configured.val.aafc7444') };
  }
  if (question.type === 'multiple_choice' || question.type === 'scenario_based' || question.type === 'image_observation') {
    const correct = typeof response === 'string' && Array.isArray(question.correct_answer_ids) && question.correct_answer_ids.includes(response);
    return { status: correct ? 'correct' : 'incorrect', score: correct ? 1 : 0, maxScore: 1, feedback: fallback, correctAnswer: question.correct_answer_ids };
  }
  if (question.type === 'multiple_select') {
    const correct = Array.isArray(response) && Array.isArray(question.correct_answer_ids) && sameSet(response, question.correct_answer_ids);
    return { status: correct ? 'correct' : 'incorrect', score: correct ? 1 : 0, maxScore: 1, feedback: fallback, correctAnswer: question.correct_answer_ids };
  }
  if (question.type === 'true_false') {
    const correct = typeof response === 'boolean' && response === question.correct_answer;
    return { status: correct ? 'correct' : 'incorrect', score: correct ? 1 : 0, maxScore: 1, feedback: fallback, correctAnswer: question.correct_answer };
  }
  if (question.type === 'fill_blank') {
    const answers = question.accepted_answers?.[locale] || [];
    const correct = typeof response === 'string' && answers.some(answer => normalizeAnswer(answer, locale) === normalizeAnswer(response, locale));
    return { status: correct ? 'correct' : 'incorrect', score: correct ? 1 : 0, maxScore: 1, feedback: fallback, correctAnswer: answers };
  }
  if (question.type === 'matching') {
    const pairs = question.correct_pairs || [];
    const selection = response && typeof response === 'object' && !Array.isArray(response) ? response as Record<string, string> : {};
    const correctCount = pairs.filter(pair => selection[pair.left_id] === pair.right_id).length;
    const maxScore = pairs.length;
    const correct = maxScore > 0 && correctCount === maxScore;
    return {
      status: correct ? 'correct' : correctCount > 0 ? 'partially_correct' : 'incorrect',
      score: correctCount, maxScore, feedback: fallback, correctAnswer: pairs,
    };
  }
  return { status: 'pending_review', feedback: translateUi(uiLocale, 'legacy.web.this.response.type.needs.an.explicit.g.d76f3e48') };
}

export default function StructuredLessonExperience({
  lesson, locale, mode = 'student', onActivitySave, onQuestionSubmit,
  initialResponses, initialResults, initialSubmitted, initialActivityResponses,
}: Props) {
  const { locale: uiLocale } = useLocale();
  const [index, setIndex] = useState(0);
  const [responses, setResponses] = useState<Record<string, QuestionResponse>>(() => initialResponses || {});
  const [results, setResults] = useState<Record<string, QuestionResult>>(() => initialResults || {});
  const [submitted, setSubmitted] = useState<Record<string, boolean>>(() => initialSubmitted || {});
  const [submitError, setSubmitError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const allQuestions = lesson.practice.questions;
  const hasAnswerKeys = allQuestions.some(question =>
    Object.prototype.hasOwnProperty.call(question, 'correct_answer_ids') ||
    Object.prototype.hasOwnProperty.call(question, 'correct_answer') ||
    Object.prototype.hasOwnProperty.call(question, 'correct_pairs') ||
    Object.prototype.hasOwnProperty.call(question, 'accepted_answers') ||
    Object.prototype.hasOwnProperty.call(question, 'answer_guide_by_locale') ||
    Object.prototype.hasOwnProperty.call(question, 'grading')
  );
  const publishedForStudents = lesson.workflow?.publication_allowed === true && String(lesson.workflow?.status || '').toUpperCase() === 'PUBLISHED';
  const blockedByPublicationGate = mode === 'student' && (!publishedForStudents || hasAnswerKeys);
  const unavailableVisualCount = allQuestions.filter(question =>
    question.type === 'image_observation' && !isQuestionVisualApproved(lesson, question)
  ).length;
  const questions = useMemo(
    () => mode === 'student' ? allQuestions.filter(question =>
      question.type !== 'image_observation' || isQuestionVisualApproved(lesson, question)
    ) : allQuestions,
    [allQuestions, lesson, mode]
  );
  const current = questions[index] || null;
  const activeResponse = current ? responses[current.id] ?? null : null;
  const activeResult = current ? results[current.id] ?? null : null;
  const isLocked = current ? submitted[current.id] === true : false;

  async function submitCurrent() {
    if (!current || activeResponse === null || isSubmitting) return;
    setIsSubmitting(true);
    setSubmitError('');
    try {
      let result: QuestionResult;
      if (mode === 'admin-preview') {
        result = gradeAdminPreview(current, activeResponse, locale, uiLocale);
      } else if (onQuestionSubmit) {
        result = await onQuestionSubmit(current, activeResponse);
      } else {
        setSubmitError(translateUi(uiLocale, 'legacy.web.this.learner.preview.is.not.connected..3ff4cc37'));
        return;
      }
      setResults(previous => ({ ...previous, [current.id]: result }));
      setSubmitted(previous => ({ ...previous, [current.id]: result.status === 'correct' || result.status === 'pending_review' }));
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : translateUi(uiLocale, 'legacy.web.answer.could.not.be.submitted.75ade80d'));
    } finally {
      setIsSubmitting(false);
    }
  }

  function updateResponse(value: QuestionResponse) {
    if (!current || isLocked || isSubmitting) return;
    setResponses(previous => ({ ...previous, [current.id]: value }));
    setResults(previous => { const next = { ...previous }; delete next[current.id]; return next; });
    setSubmitted(previous => ({ ...previous, [current.id]: false }));
    setSubmitError('');
  }

  function tryAgain() {
    if (!current) return;
    setResponses(previous => { const next = { ...previous }; delete next[current.id]; return next; });
    setResults(previous => { const next = { ...previous }; delete next[current.id]; return next; });
    setSubmitted(previous => ({ ...previous, [current.id]: false }));
    setSubmitError('');
  }

  if (blockedByPublicationGate) {
    return <section className="qe-structured-muted" role="status">
      <strong>{translateUi(uiLocale, 'legacy.web.lesson.is.not.available.for.student.re.6a09b450')}</strong>
      <p>{hasAnswerKeys
        ? translateUi(uiLocale, 'legacy.web.the.delivered.lesson.payload.still.con.2c56341c')
        : translateUi(uiLocale, 'legacy.web.this.lesson.has.not.passed.the.publica.7d20d1ee')
      }</p>
    </section>;
  }

  return <div className="qe-structured-experience">
    <StructuredLessonContent lesson={lesson} locale={locale} mode={mode} onActivitySave={onActivitySave} initialActivityResponses={initialActivityResponses} />
    <section className="qe-structured-practice" aria-labelledby="qe-structured-practice-title">
      <header className="qe-structured-practice__header">
        <p className="qe-structured-eyebrow">{translateUi(uiLocale, 'legacy.web.explain.practice.be1cc225')}</p>
        <h2 id="qe-structured-practice-title">{readLocale(lesson.practice.title_by_locale, locale) || translateUi(uiLocale, 'legacy.web.practise.what.you.learned.658c2550')}</h2>
        <p>{translateUi(uiLocale, 'legacy.web.one.question.at.a.time.your.response.i.79d204de')}</p>
        {mode === 'admin-preview' && unavailableVisualCount > 0 && <p className="qe-structured-muted" role="status">
          {unavailableVisualCount} {translateUi(uiLocale, 'legacy.web.image.observation.question.s.are.block.3a41534a')}
        </p>}
      </header>
      {!questions.length ? <div className="qe-structured-muted" role="status">
        {translateUi(uiLocale, 'legacy.web.no.approved.practice.questions.are.ava.0edf6991')}
      </div> : <>
        <div className="qe-structured-progress" aria-label={translateUi(uiLocale, 'legacy.web.question.progress.ed856d19')}>
          <span>{translateUi(uiLocale, 'legacy.web.question.a06d34df')} {index + 1} / {questions.length}</span>
          <progress value={index + 1} max={questions.length} />
        </div>
        <StructuredQuestionRenderer
          key={current!.id}
          question={current!}
          lesson={lesson}
          locale={locale}
          mode={mode}
          response={activeResponse}
          result={activeResult}
          submitted={isLocked}
          submitting={isSubmitting}
          onResponseChange={updateResponse}
          onSubmit={() => void submitCurrent()}
        />
        {submitError && <p className="qe-structured-error" role="alert">{submitError}</p>}
        {activeResult?.status === 'incorrect' && <button type="button" className="qe-structured-button-secondary" onClick={tryAgain}>
          {translateUi(uiLocale, 'legacy.web.try.again.271e37a8')}
        </button>}
        {activeResult && <div className="qe-structured-navigation">
          <button type="button" className="qe-structured-button-secondary" disabled={index === 0} onClick={() => { setIndex(value => Math.max(0, value - 1)); setSubmitError(''); }}>
            {translateUi(uiLocale, 'legacy.web.previous.question.1bce3b9a')}
          </button>
          <button type="button" disabled={index >= questions.length - 1} onClick={() => { setIndex(value => Math.min(questions.length - 1, value + 1)); setSubmitError(''); }}>
            {translateUi(uiLocale, 'legacy.web.next.question.6f256664')}
          </button>
        </div>}
      </>}
    </section>
  </div>;
}
