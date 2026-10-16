'use client';

import { useMemo } from 'react';
import type { LessonLocale, StructuredLesson, StructuredQuestion } from './contract';
import { findQuestionVisual, isQuestionVisualApproved } from './contract';
import { StructuredDiagramView } from './StructuredLessonContent';

export type QuestionResponse = string | boolean | string[] | Record<string, string>;
export type QuestionResult = { status: 'correct' | 'incorrect' | 'partially_correct' | 'pending_review'; score?: number; maxScore?: number; feedback?: string; correctAnswer?: unknown };
type Props = {
  question: StructuredQuestion;
  lesson?: StructuredLesson;
  locale: LessonLocale;
  mode?: 'student' | 'admin-preview';
  response: QuestionResponse | null;
  result?: QuestionResult | null;
  submitted?: boolean;
  submitting?: boolean;
  disabled?: boolean;
  onResponseChange: (value: QuestionResponse) => void;
  onSubmit: () => void;
};

const labels = (locale: LessonLocale) => ({
  selectOne: locale === 'hi' ? 'एक उत्तर चुनें' : 'Choose one answer',
  selectMany: locale === 'hi' ? 'सभी सही विकल्प चुनें' : 'Select all that apply',
  trueLabel: locale === 'hi' ? 'सही' : 'True',
  falseLabel: locale === 'hi' ? 'गलत' : 'False',
  matchPlaceholder: locale === 'hi' ? 'जोड़ी चुनें' : 'Choose a match',
  answerPlaceholder: locale === 'hi' ? 'अपना उत्तर लिखें' : 'Write your answer',
  submit: locale === 'hi' ? 'उत्तर जमा करें' : 'Submit answer',
  correct: locale === 'hi' ? 'सही उत्तर' : 'Correct',
  incorrect: locale === 'hi' ? 'उत्तर सही नहीं है' : 'Not correct yet',
  partial: locale === 'hi' ? 'आंशिक रूप से सही' : 'Partly correct',
  pending: locale === 'hi' ? 'जाँच लंबित है' : 'Pending review',
  submitting: locale === 'hi' ? 'उत्तर भेजा जा रहा है…' : 'Submitting answer…',
});

function typeLabel(type: string, locale: LessonLocale): string {
  const names: Record<string, { en: string; hi: string }> = {
    multiple_choice: { en: 'Multiple choice', hi: 'बहुविकल्पीय प्रश्न' },
    multiple_select: { en: 'Select all that apply', hi: 'सभी सही विकल्प चुनें' },
    true_false: { en: 'True or false', hi: 'सही या गलत' },
    matching: { en: 'Matching', hi: 'मिलान करें' },
    fill_blank: { en: 'Fill in the blank', hi: 'रिक्त स्थान भरें' },
    image_observation: { en: 'Image observation', hi: 'चित्र का अवलोकन' },
    short_answer: { en: 'Short answer', hi: 'लघु उत्तरीय प्रश्न' },
    scenario_based: { en: 'Scenario question', hi: 'परिस्थिति-आधारित प्रश्न' },
  };
  return names[type]?.[locale] || (locale === 'hi' ? 'प्रश्न' : 'Question');
}

function text(value: unknown, locale: LessonLocale): string {
  if (value && typeof value === 'object') {
    const obj = value as Record<string, unknown>;
    const selected = obj[locale];
    return typeof selected === 'string' ? selected : '';
  }
  return typeof value === 'string' ? value : '';
}

export default function StructuredQuestionRenderer({
  question, lesson, locale, mode = 'student', response, result, submitted = false, submitting = false, disabled = false,
  onResponseChange, onSubmit,
}: Props) {
  const ui = labels(locale);
  const options = Array.isArray(question.options) ? question.options : [];
  const selected = Array.isArray(response) ? response : [];
  const mapResponse = response && typeof response === 'object' && !Array.isArray(response) ? response as Record<string, string> : {};
  const visualBlock = useMemo(() => lesson ? findQuestionVisual(lesson, question) : null, [lesson, question]);
  const visualApproved = Boolean(lesson && isQuestionVisualApproved(lesson, question));
  const matchingRows = question.type === 'matching' ? (question.left_items || []).length : 0;
  const canSubmit = response !== null && response !== '' && !(Array.isArray(response) && response.length === 0) &&
    !(typeof response === 'object' && !Array.isArray(response) && (Object.keys(response).length === 0 || (question.type === 'matching' && (Object.keys(mapResponse).length < matchingRows || Object.values(mapResponse).some(value => !value)))));
  const statusLabel = result?.status === 'correct' ? ui.correct :
    result?.status === 'incorrect' ? ui.incorrect :
    result?.status === 'partially_correct' ? ui.partial : result?.status === 'pending_review' ? ui.pending : '';

  if (question.type === 'image_observation' && !visualApproved && mode === 'student') {
    return <section className="qe-structured-question qe-structured-question--blocked" role="status">
      <h3>{text(question.prompt_by_locale, locale)}</h3>
      <p>{locale === 'hi' ? 'इस प्रश्न का चित्र अभी समीक्षा के लिए तैयार नहीं है।' : 'The visual for this question has not been approved yet.'}</p>
    </section>;
  }

  return <section className="qe-structured-question" aria-labelledby={'question-' + question.id}>
    <div className="qe-structured-question__header">
      <span className="qe-structured-question__type">{typeLabel(question.type, locale)}</span>
      {question.difficulty && <span className="qe-structured-question__difficulty">{question.difficulty}</span>}
    </div>
    {question.type === 'scenario_based' && question.scenario_by_locale && <div className="qe-structured-scenario">
      <strong>{locale === 'hi' ? 'स्थिति' : 'Scenario'}</strong>
      <p>{text(question.scenario_by_locale, locale)}</p>
    </div>}
    {question.type === 'image_observation' && mode === 'admin-preview' && <div className="qe-structured-muted">
      {visualApproved ? (locale === 'hi' ? 'स्वीकृत दृश्य' : 'Approved visual') : (locale === 'hi' ? 'दृश्य समीक्षा लंबित' : 'Visual review pending')}
    </div>}
    {question.type === 'image_observation' && (visualApproved || mode === 'admin-preview') && visualBlock?.type === 'image' && typeof (visualBlock.asset as any)?.url === 'string' &&
      <img className="qe-structured-question__image" src={String((visualBlock.asset as any).url)} alt={text((visualBlock.asset as any).alt_by_locale, locale)} />}
    {question.type === 'image_observation' && (visualApproved || mode === 'admin-preview') && visualBlock?.type === 'diagram' &&
      <StructuredDiagramView block={visualBlock} locale={locale} />}
    <h2 id={'question-' + question.id}>{text(question.prompt_by_locale, locale)}</h2>

    {(question.type === 'multiple_choice' || question.type === 'scenario_based' || question.type === 'image_observation') &&
      <fieldset className="qe-structured-choice-group" disabled={disabled || submitted}>
        <legend>{ui.selectOne}</legend>
        {options.map(option => <label className="qe-structured-choice" key={option.id}>
          <input type="radio" name={'answer-' + question.id} value={option.id}
            checked={response === option.id} onChange={() => onResponseChange(option.id)} />
          <span>{text(option.text_by_locale, locale)}</span>
        </label>)}
      </fieldset>}

    {question.type === 'multiple_select' && <fieldset className="qe-structured-choice-group" disabled={disabled || submitted}>
      <legend>{ui.selectMany}</legend>
      {options.map(option => <label className="qe-structured-choice" key={option.id}>
        <input type="checkbox" value={option.id} checked={selected.includes(option.id)}
          onChange={event => onResponseChange(event.target.checked ? Array.from(new Set([...selected, option.id])) : selected.filter(id => id !== option.id))} />
        <span>{text(option.text_by_locale, locale)}</span>
      </label>)}
    </fieldset>}

    {question.type === 'true_false' && <fieldset className="qe-structured-choice-group" disabled={disabled || submitted}>
      <legend>{locale === 'hi' ? 'सही या गलत चुनें' : 'Choose true or false'}</legend>
      {[true, false].map(value => <label className="qe-structured-choice" key={String(value)}>
        <input type="radio" name={'answer-' + question.id} checked={response === value} onChange={() => onResponseChange(value)} />
        <span>{value ? ui.trueLabel : ui.falseLabel}</span>
      </label>)}
    </fieldset>}

    {question.type === 'matching' && <div className="qe-structured-matching">
      {(question.left_items || []).map(left => <label className="qe-structured-match-row" key={left.id}>
        <span>{text(left.label_by_locale, locale)}</span>
        <select value={mapResponse[left.id] || ''} disabled={disabled || submitted}
          aria-label={text(left.label_by_locale, locale)}
          onChange={event => onResponseChange({ ...mapResponse, [left.id]: event.target.value })}>
          <option value="">{ui.matchPlaceholder}</option>
          {(question.right_items || []).map(right => <option key={right.id} value={right.id}>{text(right.label_by_locale, locale)}</option>)}
        </select>
      </label>)}
    </div>}

    {question.type === 'fill_blank' && <label className="qe-structured-field">
      <span>{ui.answerPlaceholder}</span>
      <input type="text" value={typeof response === 'string' ? response : ''} disabled={disabled || submitted}
        autoComplete="off" onChange={event => onResponseChange(event.target.value)} />
    </label>}

    {question.type === 'short_answer' && <label className="qe-structured-field">
      <span>{ui.answerPlaceholder}</span>
      <textarea rows={4} value={typeof response === 'string' ? response : ''} disabled={disabled || submitted}
        onChange={event => onResponseChange(event.target.value)} />
    </label>}

    {mode === 'admin-preview' && question.type === 'short_answer' && question.answer_guide_by_locale && <details>
      <summary>{locale === 'hi' ? 'मूल्यांकन मार्गदर्शिका' : 'Answer guide'}</summary>
      <p>{text(question.answer_guide_by_locale[locale]?.sample_answer, locale)}</p>
      {Array.isArray(question.answer_guide_by_locale[locale]?.key_points) &&
        <ul>{question.answer_guide_by_locale[locale]!.key_points!.map((point, i) => <li key={i}>{point}</li>)}</ul>}
    </details>}

    {mode === 'admin-preview' && question.correct_answer_ids && <details>
      <summary>{locale === 'hi' ? 'सही विकल्प IDs (समीक्षा हेतु)' : 'Correct option IDs (review only)'}</summary>
      <code>{question.correct_answer_ids.join(', ')}</code>
    </details>}
    {mode === 'admin-preview' && question.type === 'true_false' && <details>
      <summary>{locale === 'hi' ? 'सही उत्तर (समीक्षा हेतु)' : 'Correct answer (review only)'}</summary>
      <strong>{question.correct_answer ? ui.trueLabel : ui.falseLabel}</strong>
    </details>}

    {question.type !== 'short_answer' && question.feedback_by_locale && result &&
      <div className={'qe-structured-feedback qe-structured-feedback--' + result.status} role="status">
        <strong>{statusLabel}</strong>
        <p>{result.feedback || text(question.feedback_by_locale, locale)}</p>
        {typeof result.score === 'number' && typeof result.maxScore === 'number' && <small>{result.score} / {result.maxScore}</small>}
      </div>}
    {question.type === 'short_answer' && result && <div className="qe-structured-feedback qe-structured-feedback--pending_review" role="status">
      <strong>{statusLabel || ui.pending}</strong><p>{result.feedback || (locale === 'hi' ? 'आपका उत्तर जाँच के लिए सहेजा गया है।' : 'Your response has been saved for review.')}</p>
    </div>}

    {!submitted && <button type="button" className="qe-structured-submit" disabled={!canSubmit || disabled || submitting} onClick={onSubmit}>
      {submitting ? ui.submitting : ui.submit}
    </button>}
  </section>;
}
