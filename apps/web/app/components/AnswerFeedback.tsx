import type { ReactNode } from 'react';

export type AnswerOutcome = 'correct' | 'incorrect' | 'ungraded';

type AnswerFeedbackProps = {
  outcome: AnswerOutcome;
  title: ReactNode;
  message?: ReactNode;
  explanation?: ReactNode;
  explanationLabel?: ReactNode;
};

/** Shared accessible presentation: green = correct, red = incorrect, neutral = not graded. */
export default function AnswerFeedback({
  outcome,
  title,
  message,
  explanation,
  explanationLabel,
}: AnswerFeedbackProps) {
  const incorrect = outcome === 'incorrect';
  const icon = outcome === 'correct' ? '✓' : incorrect ? '!' : 'i';

  return (
    <div
      className={`qe-answer-feedback is-${outcome}`}
      role={incorrect ? 'alert' : 'status'}
      aria-live={incorrect ? 'assertive' : 'polite'}
    >
      <span className="qe-answer-feedback__icon" aria-hidden="true">{icon}</span>
      <div className="qe-answer-feedback__body">
        <strong>{title}</strong>
        {message && <p>{message}</p>}
        {explanation && (
          <div className="qe-answer-feedback__explanation">
            {explanationLabel && <span>{explanationLabel}</span>}
            {explanation}
          </div>
        )}
      </div>
    </div>
  );
}
