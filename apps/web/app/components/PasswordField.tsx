'use client';

import { useState, type InputHTMLAttributes } from 'react';

type PasswordFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> & {
  showLabel?: string;
  hideLabel?: string;
};

/** Password field with an accessible visibility toggle; hidden by default. */
export default function PasswordField({
  showLabel = 'Show password',
  hideLabel = 'Hide password',
  className,
  ...inputProps
}: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);
  return (
    <span className="qe-password-field">
      <input {...inputProps} className={className} type={visible ? 'text' : 'password'} />
      <button
        className="qe-password-field__toggle"
        type="button"
        aria-label={visible ? hideLabel : showLabel}
        title={visible ? hideLabel : showLabel}
        aria-pressed={visible}
        onClick={() => setVisible(value => !value)}
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
          <circle cx="12" cy="12" r="3" />
          {visible ? <path d="M4 4 20 20" /> : null}
        </svg>
      </button>
    </span>
  );
}
