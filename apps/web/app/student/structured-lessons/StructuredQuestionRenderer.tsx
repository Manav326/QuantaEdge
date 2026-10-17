'use client';

import { useMemo } from 'react';
import { useLocale } from '../../components/LanguageProvider';
import { translateUi, type MessageKey } from '../../i18n/catalog';
import type { LessonLocale, StructuredLesson, StructuredQuestion } from './contract';
import { findQuestionVisual, isQuestionVisualApproved } from './contract';
import { StructuredDiagramView } from './StructuredLessonContent';
import StructuredImageLightbox from './StructuredImageLightbox';

export type QuestionResponse = string | boolean | string[] | Record<string, string>;
export type QuestionResult = { status: 'correct' | 'incorrect' | 'partially_correct' | 'pending_review'; score?: number; maxScore?: number; feedback?: string; feedbackByLocale?: Partial<Record<LessonLocale, string>>; autoGraded?: boolean; correctAnswer?: unknown };
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

const labels = (locale: unknown) => ({
  selectOne: translateUi(locale, 'legacy.web.choose.one.answer.367999bc'),
  selectMany: translateUi(locale, 'legacy.web.select.all.that.apply.d4c9340b'),
  trueLabel: translateUi(locale, 'legacy.web.true.518b6548'),
  falseLabel: translateUi(locale, 'legacy.web.false.3b047c99'),
  matchPlaceholder: translateUi(locale, 'legacy.web.choose.a.match.9c22c9b4'),
  answerPlaceholder: translateUi(locale, 'legacy.web.write.your.answer.a8dac402'),
  submit: translateUi(locale, 'legacy.web.submit.answer.10965fb7'),
  correct: translateUi(locale, 'legacy.web.correct.079f9a56'),
  incorrect: translateUi(locale, 'legacy.web.not.correct.yet.38443af3'),
  partial: translateUi(locale, 'legacy.web.partly.correct.b2b468f7'),
  pending: translateUi(locale, 'legacy.web.pending.review.f9989033'),
  submitting: translateUi(locale, 'legacy.web.submitting.answer.8ab447ff'),
  visualNotApproved: translateUi(locale, 'legacy.web.the.visual.for.this.question.has.not.b.f56c5b2d'),
  scenario: translateUi(locale, 'legacy.web.scenario.81774341'),
  approvedVisual: translateUi(locale, 'legacy.web.approved.visual.e8f0de71'),
  visualReviewPending: translateUi(locale, 'legacy.web.visual.review.pending.bbde27cf'),
  chooseTrueFalse: translateUi(locale, 'legacy.web.choose.true.or.false.73c071ab'),
  answerGuide: translateUi(locale, 'legacy.web.answer.guide.5960084f'),
  correctOptionIds: translateUi(locale, 'legacy.web.correct.option.ids.review.only.e304f315'),
  correctAnswerReview: translateUi(locale, 'legacy.web.correct.answer.review.only.af6c2a42'),
  responseSavedReview: translateUi(locale, 'legacy.web.your.response.has.been.saved.for.revie.29ae2032'),
});

function typeLabel(type: string, uiLocale: unknown): string {
  const names: Record<string, MessageKey> = {
    multiple_choice: 'legacy.web.multiple.choice.649280af',
    multiple_select: 'legacy.web.select.all.that.apply.d4c9340b',
    true_false: 'legacy.web.true.or.false.43e5661e',
    matching: 'legacy.web.matching.9edd43bf',
    fill_blank: 'legacy.web.fill.in.the.blank.3f05ce89',
    image_observation: 'legacy.web.image.observation.a3688e63',
    short_answer: 'legacy.web.short.answer.b3e52c23',
    scenario_based: 'legacy.web.scenario.question.ed58e553',
  };
  return translateUi(uiLocale, names[type] || 'legacy.web.question.a06d34df');
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
  const { locale: uiLocale } = useLocale();
  const ui = labels(uiLocale);
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
      <p>{ui.visualNotApproved}</p>
    </section>;
  }

  return <section className="qe-structured-question" aria-labelledby={'question-' + question.id}>
    <div className="qe-structured-question__header">
      <span className="qe-structured-question__type">{typeLabel(question.type, uiLocale)}</span>
      {question.difficulty && <span className="qe-structured-question__difficulty">{question.difficulty}</span>}
    </div>
    {question.type === 'scenario_based' && question.scenario_by_locale && <div className="qe-structured-scenario">
      <strong>{ui.scenario}</strong>
      <p>{text(question.scenario_by_locale, locale)}</p>
    </div>}
    {question.type === 'image_observation' && mode === 'admin-preview' && <div className="qe-structured-muted">
      {visualApproved ? ui.approvedVisual : ui.visualReviewPending}
    </div>}
    {question.type === 'image_observation' && (visualApproved || mode === 'admin-preview') && visualBlock?.type === 'image' && typeof (visualBlock.asset as any)?.url === 'string' &&
      <StructuredImageLightbox url={String((visualBlock.asset as any).url)} alt={text((visualBlock.asset as any).alt_by_locale, locale)} caption={text((visualBlock.asset as any).caption_by_locale, locale) || text(visualBlock.caption_by_locale, locale)} locale={locale} />}
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
      <legend>{ui.chooseTrueFalse}</legend>
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
      <summary>{ui.answerGuide}</summary>
      <p>{text(question.answer_guide_by_locale[locale]?.sample_answer, locale)}</p>
      {Array.isArray(question.answer_guide_by_locale[locale]?.key_points) &&
        <ul>{question.answer_guide_by_locale[locale]!.key_points!.map((point, i) => <li key={i}>{point}</li>)}</ul>}
    </details>}

    {mode === 'admin-preview' && question.correct_answer_ids && <details>
      <summary>{ui.correctOptionIds}</summary>
      <code>{question.correct_answer_ids.join(', ')}</code>
    </details>}
    {mode === 'admin-preview' && question.type === 'true_false' && <details>
      <summary>{ui.correctAnswerReview}</summary>
      <strong>{question.correct_answer ? ui.trueLabel : ui.falseLabel}</strong>
    </details>}

    {question.type !== 'short_answer' && result &&
      <div className={'qe-structured-feedback qe-structured-feedback--' + result.status} role="status">
        <strong>{statusLabel}</strong>
        <p>{result.feedbackByLocale?.[locale] || result.feedback || text(question.feedback_by_locale, locale)}</p>
        {typeof result.score === 'number' && typeof result.maxScore === 'number' && <small>{result.score} / {result.maxScore}</small>}
      </div>}
    {question.type === 'short_answer' && result && <div className="qe-structured-feedback qe-structured-feedback--pending_review" role="status">
      <strong>{statusLabel || ui.pending}</strong><p>{result.feedbackByLocale?.[locale] || result.feedback || ui.responseSavedReview}</p>
    </div>}

    {!submitted && <button type="button" className="qe-structured-submit" disabled={!canSubmit || disabled || submitting} onClick={onSubmit}>
      {submitting ? ui.submitting : ui.submit}
    </button>}
  </section>;
}
