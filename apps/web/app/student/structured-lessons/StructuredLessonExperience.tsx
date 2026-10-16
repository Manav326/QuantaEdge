'use client';

import { useMemo, useState } from 'react';
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

const tx = (locale: LessonLocale, en: string, hi: string) => locale === 'hi' ? hi : en;

function normalizeAnswer(value: string, locale: LessonLocale): string {
  const normalized = value.normalize('NFC').trim().replace(/\s+/g, ' ');
  return locale === 'en' ? normalized.toLocaleLowerCase('en') : normalized;
}

function sameSet(left: string[], right: string[]): boolean {
  const a = Array.from(new Set(left)).sort();
  const b = Array.from(new Set(right)).sort();
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

function gradeAdminPreview(question: StructuredQuestion, response: QuestionResponse, locale: LessonLocale): QuestionResult {
  const fallback = readLocale(question.feedback_by_locale, locale) || tx(locale, 'Answer checked in administrator preview.', 'प्रशासक पूर्वावलोकन में उत्तर जाँचा गया।');
  if (question.type === 'short_answer') {
    return { status: 'pending_review', feedback: tx(locale, 'Short answers require a configured, validated grader or human review. No automatic score was invented.', 'लघु उत्तरीय प्रश्न के लिए सत्यापित मूल्यांकन या मानवीय समीक्षा चाहिए। स्वतः अंक नहीं दिए गए।') };
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
  return { status: 'pending_review', feedback: tx(locale, 'This response type needs an explicit grading adapter.', 'इस उत्तर प्रकार के लिए स्पष्ट मूल्यांकन अडैप्टर चाहिए।') };
}

export default function StructuredLessonExperience({
  lesson, locale, mode = 'student', onActivitySave, onQuestionSubmit,
  initialResponses, initialResults, initialSubmitted, initialActivityResponses,
}: Props) {
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
        result = gradeAdminPreview(current, activeResponse, locale);
      } else if (onQuestionSubmit) {
        result = await onQuestionSubmit(current, activeResponse);
      } else {
        setSubmitError(tx(locale,
          'This learner preview is not connected to the learning-service submit endpoint yet. No score was saved.',
          'यह विद्यार्थी पूर्वावलोकन अभी learning-service के उत्तर जमा करने वाले endpoint से जुड़ा नहीं है। कोई अंक सहेजे नहीं गए।'
        ));
        return;
      }
      setResults(previous => ({ ...previous, [current.id]: result }));
      setSubmitted(previous => ({ ...previous, [current.id]: result.status === 'correct' || result.status === 'pending_review' }));
    } catch (error) {
      setSubmitError(error instanceof Error ? error.message : tx(locale, 'Answer could not be submitted.', 'उत्तर जमा नहीं हो सका।'));
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
      <strong>{tx(locale, 'Lesson is not available for student rendering', 'यह पाठ विद्यार्थी के लिए अभी उपलब्ध नहीं है')}</strong>
      <p>{hasAnswerKeys
        ? tx(locale, 'The delivered lesson payload still contains author-only answer or grading keys. The server must sanitize it before student display.', 'विद्यार्थी वाले डेटा में अभी लेखक के सही उत्तर या मूल्यांकन कुंजी शामिल हैं। दिखाने से पहले सर्वर को इन्हें हटाना होगा।')
        : tx(locale, 'This lesson has not passed the publication gate yet.', 'इस पाठ को अभी प्रकाशन की स्वीकृति नहीं मिली है।')
      }</p>
    </section>;
  }

  return <div className="qe-structured-experience">
    <StructuredLessonContent lesson={lesson} locale={locale} mode={mode} onActivitySave={onActivitySave} initialActivityResponses={initialActivityResponses} />
    <section className="qe-structured-practice" aria-labelledby="qe-structured-practice-title">
      <header className="qe-structured-practice__header">
        <p className="qe-structured-eyebrow">{tx(locale, 'EXPLAIN → PRACTICE', 'समझें → अभ्यास करें')}</p>
        <h2 id="qe-structured-practice-title">{readLocale(lesson.practice.title_by_locale, locale) || tx(locale, 'Practise what you learned', 'आपने जो सीखा उसका अभ्यास करें')}</h2>
        <p>{tx(locale, 'One question at a time. Your response is stored by stable question ID, not by translated label.', 'एक समय में एक प्रश्न। उत्तर का संबंध स्थिर प्रश्न ID से है, अनुवादित विकल्प-लेबल से नहीं।')}</p>
        {mode === 'admin-preview' && unavailableVisualCount > 0 && <p className="qe-structured-muted" role="status">
          {unavailableVisualCount} {tx(locale, 'image-observation question(s) are blocked from student mode pending visual approval.', 'चित्र-अवलोकन प्रश्नों की दृश्य स्वीकृति लंबित है; वे विद्यार्थी मोड में अवरुद्ध हैं।')}
        </p>}
      </header>
      {!questions.length ? <div className="qe-structured-muted" role="status">
        {tx(locale, 'No approved practice questions are available for this lesson yet.', 'इस पाठ के लिए अभी स्वीकृत अभ्यास प्रश्न उपलब्ध नहीं हैं।')}
      </div> : <>
        <div className="qe-structured-progress" aria-label={tx(locale, 'Question progress', 'प्रश्न प्रगति')}>
          <span>{tx(locale, 'Question', 'प्रश्न')} {index + 1} / {questions.length}</span>
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
          {tx(locale, 'Try again', 'फिर प्रयास करें')}
        </button>}
        {activeResult && <div className="qe-structured-navigation">
          <button type="button" className="qe-structured-button-secondary" disabled={index === 0} onClick={() => { setIndex(value => Math.max(0, value - 1)); setSubmitError(''); }}>
            {tx(locale, 'Previous question', 'पिछला प्रश्न')}
          </button>
          <button type="button" disabled={index >= questions.length - 1} onClick={() => { setIndex(value => Math.min(questions.length - 1, value + 1)); setSubmitError(''); }}>
            {tx(locale, 'Next question', 'अगला प्रश्न')}
          </button>
        </div>}
      </>}
    </section>
  </div>;
}
