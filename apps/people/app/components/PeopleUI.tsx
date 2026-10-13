import type { ButtonHTMLAttributes, ReactNode } from 'react';

export type PeopleStatusTone = 'success' | 'warning' | 'danger' | 'info' | 'neutral';
export type PeopleButtonVariant = 'primary' | 'secondary' | 'danger';

export function PeoplePageHeader({
  eyebrow,
  title,
  description,
  actions,
}: {
  eyebrow: string;
  title: string;
  description: string;
  actions?: ReactNode;
}) {
  return (
    <header className="qe-people-page-header">
      <div className="qe-people-page-header-copy">
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        <p className="sub">{description}</p>
      </div>
      {actions ? <div className="qe-people-page-actions">{actions}</div> : null}
    </header>
  );
}

export function PeopleMetricCard({
  label,
  value,
  help,
}: {
  label: string;
  value: ReactNode;
  help?: ReactNode;
}) {
  return (
    <section className="card qe-people-metric-card">
      <div className="label">{label}</div>
      <div className="qe-people-metric-value">{value}</div>
      {help ? <div className="qe-people-metric-help">{help}</div> : null}
    </section>
  );
}

function inferredTone(status: string): PeopleStatusTone {
  const value = status.trim().toLowerCase().replaceAll('_', ' ');
  if (/\b(active|approved|complete|completed|published|paid|present|verified|issued|accepted|created|effective|success)\b/.test(value)) return 'success';
  if (/\b(rejected|failed|failure|cancelled|canceled|inactive|separated|absent|denied|error|overdue|unpaid)\b/.test(value)) return 'danger';
  if (/\b(pending|draft|review|planned|half day|awaiting|queued|processing|on leave|probation)\b/.test(value)) return 'warning';
  if (/\b(remote|scheduled|live|upcoming|in progress|submitted)\b/.test(value)) return 'info';
  return 'neutral';
}

export function PeopleStatusBadge({
  status,
  tone,
}: {
  status: string;
  tone?: PeopleStatusTone;
}) {
  const selectedTone = tone || inferredTone(status);
  return (
    <span className={'qe-people-status qe-people-status--' + selectedTone}>
      {status.replaceAll('_', ' ')}
    </span>
  );
}

export function PeopleFeedback({
  tone,
  children,
  role,
}: {
  tone: PeopleStatusTone;
  children: ReactNode;
  role?: 'status' | 'alert';
}) {
  return (
    <div className={'qe-people-feedback qe-people-feedback--' + tone} role={role || (tone === 'danger' ? 'alert' : 'status')}>
      {children}
    </div>
  );
}

export function PeopleButton({
  variant = 'primary',
  className = '',
  children,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { variant?: PeopleButtonVariant }) {
  return (
    <button {...props} className={['qe-people-button', 'qe-people-button--' + variant, className].filter(Boolean).join(' ')}>
      {children}
    </button>
  );
}

export function PeopleEmptyState({
  title,
  description,
  actions,
}: {
  title: string;
  description?: string;
  actions?: ReactNode;
}) {
  return (
    <div className="qe-people-empty-state">
      <strong>{title}</strong>
      {description ? <p>{description}</p> : null}
      {actions}
    </div>
  );
}
