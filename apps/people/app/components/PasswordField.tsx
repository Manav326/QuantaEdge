'use client';

import { useState, type InputHTMLAttributes } from 'react';

/** People Operations password control; hidden by default, with an accessible eye toggle. */
export default function PasswordField({
  className,
  style,
  ...inputProps
}: Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>) {
  const [visible, setVisible] = useState(false);
  const label = visible ? 'Hide password' : 'Show password';
  return (
    <span style={{ position: 'relative', display: 'block', width: '100%', minWidth: 0 }}>
      <input
        {...inputProps}
        className={className}
        style={{ ...style, width: '100%', paddingRight: '44px' }}
        type={visible ? 'text' : 'password'}
      />
      <button
        type="button"
        title={label}
        aria-label={label}
        aria-pressed={visible}
        onClick={() => setVisible(current => !current)}
        style={{
          position: 'absolute', top: '50%', right: '7px', transform: 'translateY(-50%)',
          display: 'grid', placeItems: 'center', width: '32px', height: '32px', padding: 0,
          border: '1px solid transparent', borderRadius: '8px', background: 'transparent',
          color: 'var(--muted, #65748b)', cursor: 'pointer',
        }}
      >
        <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor"
          strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12Z" />
          <circle cx="12" cy="12" r="3" />
          {visible ? <path d="M4 4 20 20" /> : null}
        </svg>
      </button>
    </span>
  );
}
