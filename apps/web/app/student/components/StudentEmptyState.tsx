import Link from 'next/link';
import type { ReactNode } from 'react';
import styles from './StudentEmptyState.module.css';

export type StudentEmptyStateIcon =
  | 'courses' | 'learn' | 'classes' | 'tests' | 'certificates'
  | 'book' | 'progress' | 'marketplace' | 'practice' | 'diagnostic';

type StudentEmptyStateProps = {
  icon: StudentEmptyStateIcon;
  title: ReactNode;
  message: ReactNode;
  primaryActionHref?: string;
  primaryActionLabel?: ReactNode;
  secondaryActionHref?: string;
  secondaryActionLabel?: ReactNode;
  onRetry?: () => void;
  retryLabel?: ReactNode;
  retryDisabled?: boolean;
};

function EmptyIcon({ icon }: { icon: StudentEmptyStateIcon }) {
  switch (icon) {
    case 'courses':
      return <><rect x="4" y="4" width="7" height="7" rx="1.5" /><rect x="13" y="4" width="7" height="7" rx="1.5" /><rect x="4" y="13" width="7" height="7" rx="1.5" /><rect x="13" y="13" width="7" height="7" rx="1.5" /></>;
    case 'learn':
      return <><path d="M3.5 5.5A2.5 2.5 0 0 1 6 3h5v17H6a2.5 2.5 0 0 0-2.5 1.5z" /><path d="M20.5 5.5A2.5 2.5 0 0 0 18 3h-5v17h5a2.5 2.5 0 0 1 2.5 1.5z" /><path d="M7 7h1.5M7 10h1.5M16 7h1.5M16 10h1.5" /></>;
    case 'classes':
      return <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M7 3v4M17 3v4M3 10h18" /><path d="m10 14 5 3-5 3z" /></>;
    case 'tests':
      return <><rect x="5" y="3.5" width="14" height="17" rx="2" /><path d="M9 8h6M9 12h6M9 16h2" /><path d="m7.5 8 .5.5 1-1" /></>;
    case 'certificates':
      return <><circle cx="12" cy="9" r="6" /><path d="m8 14-1 7 5-3 5 3-1-7" /><path d="m9.5 9 1.5 1.5 3-3" /></>;
    case 'book':
      return <><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v17H6.5A2.5 2.5 0 0 0 4 22z" /><path d="M4 5.5V22M8 7h8M8 11h8M8 15h5" /></>;
    case 'progress':
      return <><path d="M4 20V11M10 20V5M16 20v-7M22 20H2" /><path d="m3 8 5-3 5 4 7-6" /></>;
    case 'marketplace':
      return <><path d="M4 9h16l-1.5-5h-13z" /><path d="M5 9v11h14V9" /><path d="M9 20v-6h6v6" /><path d="M3 9a2 2 0 0 0 4 0 2 2 0 0 0 4 0 2 2 0 0 0 4 0 2 2 0 0 0 4 0" /></>;
    case 'practice':
      return <><circle cx="12" cy="12" r="9" /><path d="m8 12 2.5 2.5L16.5 9" /></>;
    case 'diagnostic':
      return <><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3 2" /><path d="M8 2.8 6 5M16 2.8 18 5" /></>;
  }
}

export default function StudentEmptyState({
  icon, title, message, primaryActionHref, primaryActionLabel,
  secondaryActionHref, secondaryActionLabel, onRetry, retryLabel,
  retryDisabled = false,
}: StudentEmptyStateProps) {
  return (
    <section className={styles.card} role="status" aria-live="polite">
      <div className={styles.icon} aria-hidden="true">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <EmptyIcon icon={icon} />
        </svg>
      </div>
      <div className={styles.copy}>
        <h2>{title}</h2>
        <p>{message}</p>
      </div>
      {(primaryActionHref || onRetry || secondaryActionHref) && (
        <div className={styles.actions}>
          {primaryActionHref && primaryActionLabel && <Link className={styles.primaryAction} href={primaryActionHref}>{primaryActionLabel}</Link>}
          {onRetry && retryLabel && <button className={styles.primaryAction} type="button" onClick={onRetry} disabled={retryDisabled}>{retryLabel}</button>}
          {secondaryActionHref && secondaryActionLabel && <Link className={styles.secondaryAction} href={secondaryActionHref}>{secondaryActionLabel}</Link>}
        </div>
      )}
    </section>
  );
}
