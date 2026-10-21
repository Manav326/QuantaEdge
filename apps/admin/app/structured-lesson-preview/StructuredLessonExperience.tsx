'use client';

import { useMemo, useState } from 'react';
import { useUiTranslation } from '../i18n/useUiTranslation';
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

function gradeAdminPreview(question: StructuredQuestion, response: QuestionResponse, locale: LessonLocale, t: (key: string) => string): QuestionResult {
  const fallback = readLocale(question.feedback_by_locale, locale) || t('admin.structuredPreview.answerChecked');
  if (question.type === 'short_answer') {
    return { status: 'pending_review', feedback: t('admin.structuredPreview.shortAnswerNeedsReview') };
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
  return { status: 'pending_review', feedback: t('admin.structuredPreview.gradingAdapterNeeded') };
}

export default function StructuredLessonExperience({
  lesson, locale, mode = 'student', onActivitySave, onQuestionSubmit,
}: Props) {
  const t = useUiTranslation();
  const [index, setIndex] = useState(0);
  const [responses, setResponses] = useState<Record<string, QuestionResponse>>({});
  const [results, setResults] = useState<Record<string, QuestionResult>>({});
  const [submitted, setSubmitted] = useState<Record<string, boolean>>({});
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
        result = gradeAdminPreview(current, activeResponse, locale, t);
      } else if (onQuestionSubmit) {
        result = await onQuestionSubmit(current, activeResponse);
      } else {
        setSubmitError(t('admin.structuredPreview.submitEndpointUnavailable'));
        return;
      }
      setResults(previous => ({ ...previous, [current.id]: result }));
      setSubmitted(previous => ({ ...previous, [current.id]: result.status === 'correct' || result.status === 'pending_review' }));
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : t('admin.structuredPreview.answerSubmitFailed'));
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
      <strong>{t('admin.structuredPreview.lessonUnavailable')}</strong>
      <p>{hasAnswerKeys
        ? t('admin.structuredPreview.payloadNotSanitized')
        : t('admin.structuredPreview.publicationGatePending')
      }</p>
    </section>;
  }

  return <div className="qe-structured-experience">
    <StructuredLessonContent lesson={lesson} locale={locale} mode={mode} onActivitySave={onActivitySave} />
    <section className="qe-structured-practice" aria-labelledby="qe-structured-practice-title">
      <header className="qe-structured-practice__header">
        <p className="qe-structured-eyebrow">{t('admin.structuredPreview.explainPracticeEyebrow')}</p>
        <h2 id="qe-structured-practice-title">{readLocale(lesson.practice.title_by_locale, locale) || t('admin.structuredPreview.practiceTitle')}</h2>
        <p>{t('admin.structuredPreview.practiceInstructions')}</p>
        {mode === 'admin-preview' && unavailableVisualCount > 0 && <p className="qe-structured-muted" role="status">
          {unavailableVisualCount} {t('admin.structuredPreview.visualQuestionsPending',{ count: unavailableVisualCount })}
        </p>}
      </header>
      {!questions.length ? <div className="qe-structured-muted" role="status">
        {t('admin.structuredPreview.noApprovedQuestions')}
      </div> : <>
        <div className="qe-structured-progress" aria-label={t('admin.structuredPreview.questionProgress')}>
          <span>{t('admin.structuredPreview.question')} {index + 1} / {questions.length}</span>
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
          {t('admin.structuredPreview.tryAgain')}
        </button>}
        {activeResult && <div className="qe-structured-navigation">
          <button type="button" className="qe-structured-button-secondary" disabled={index === 0} onClick={() => { setIndex(value => Math.max(0, value - 1)); setSubmitError(''); }}>
            {t('admin.structuredPreview.previousQuestion')}
          </button>
          <button type="button" disabled={index >= questions.length - 1} onClick={() => { setIndex(value => Math.min(questions.length - 1, value + 1)); setSubmitError(''); }}>
            {t('admin.structuredPreview.nextQuestion')}
          </button>
        </div>}
      </>}
    </section>
  </div>;
}
