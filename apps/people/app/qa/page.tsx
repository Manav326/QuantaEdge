'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { PeopleEmptyState, PeopleFeedback, PeoplePageHeader, PeopleStatusBadge } from '../components/PeopleUI';
import { useUiTranslation } from '../i18n/UiLocaleProvider';
import { notifyUser } from '../components/GlobalNotificationHost';
import styles from './QaCenter.module.css';

type JsonMap = Record<string, unknown>;
type Gate = {
  featureEnabled?: boolean;
  environmentApproved?: boolean;
  environmentLabel?: string | null;
  currentDatabase?: string | null;
  databaseApproved?: boolean;
  productionProfileDetected?: boolean;
  writeAllowed?: boolean;
  reason?: string;
};
type Pack = {
  packKey: string;
  version: number;
  title: string;
  summary: string;
  personas?: string[];
  coverageLimit?: string;
};
type CheckResult = {
  check_key?: string;
  status?: string;
  expected?: string;
  actual?: string;
  details?: unknown;
  executed_at?: string;
};
type Run = {
  run_id?: string;
  runId?: string;
  pack_key?: string;
  packKey?: string;
  pack_version?: number;
  packVersion?: number;
  state?: string;
  planned_resource_count?: number;
  created_resource_count?: number;
  cleaned_resource_count?: number;
  created_at?: string;
  updated_at?: string;
  installed_at?: string | null;
  uninstalled_at?: string | null;
  error_code?: string | null;
  error_summary?: string | null;
  resources?: JsonMap[];
  events?: JsonMap[];
  protected_baseline?: unknown;
  cleanup_report?: unknown;
  checks?: CheckResult[];
  fixtureSummary?: Record<string, unknown>;
  verification?: { overallStatus?: string; passedChecks?: number; failedChecks?: number };
  oneTimeSyntheticCredentials?: unknown;
  credentialsIncluded?: boolean;
  uninstallPlan?: JsonMap;
  [key: string]: unknown;
};

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-QE-Workspace', 'HRMS');
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let data: unknown = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw }; }
  if (!response.ok) {
    const value = data && typeof data === 'object' ? data as Record<string, unknown> : {};
    throw new Error(String(value.message || value.detail || value.error || 'Request failed (' + response.status + ')'));
  }
  return data as T;
}

function runId(run: Run): string {
  return String(run.run_id ?? run.runId ?? '');
}

function pretty(value: unknown): string {
  if (value == null) return '—';
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
  }
  try { return JSON.stringify(value, null, 2); } catch { return String(value); }
}

function formatTime(value?: unknown): string {
  if (!value) return '—';
  const date = new Date(String(value));
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString();
}

function newIdempotencyKey(): string {
  return 'qa-ui-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10);
}

export default function QaCenterPage() {
  const { t } = useUiTranslation();
  const [gate, setGate] = useState<Gate | null>(null);
  const [packs, setPacks] = useState<Pack[]>([]);
  const [runs, setRuns] = useState<Run[]>([]);
  const [selectedRun, setSelectedRun] = useState<Run | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);
  const [installResult, setInstallResult] = useState<Run | null>(null);
  const [uninstallPlan, setUninstallPlan] = useState<JsonMap | null>(null);
  const [confirmRunId, setConfirmRunId] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const refresh = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [status, packResult, runResult] = await Promise.all([
        api<Gate>('/api/v1/hrms/platform/qa/status'),
        api<{ items?: Pack[] }>('/api/v1/hrms/platform/qa/packs'),
        api<{ items?: Run[] }>('/api/v1/hrms/platform/qa/runs'),
      ]);
      setGate(status);
      setPacks(Array.isArray(packResult.items) ? packResult.items : []);
      setRuns(Array.isArray(runResult.items) ? runResult.items : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('people.qa.loadError'));
    } finally {
      setLoading(false);
    }
  }, [t]);

  useEffect(() => { void refresh(); }, [refresh]);

  const activeRunId = selectedRun ? runId(selectedRun) : '';
  const sortedRuns = useMemo(() => [...runs].sort((a, b) =>
    String(b.created_at ?? '').localeCompare(String(a.created_at ?? ''))), [runs]);

  const inspectRun = useCallback(async (id: string) => {
    if (!id) return;
    setError('');
    setNotice('');
    setUninstallPlan(null);
    setConfirmRunId('');
    try {
      setSelectedRun(await api<Run>('/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(id)));
      setInstallResult(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('people.qa.actionError'));
    }
  }, [t]);

  async function install(pack: Pack) {
    if (!gate?.writeAllowed || busy || !idempotencyKey.trim()) return;
    setBusy(true);
    setError('');
    setNotice('');
    setSelectedRun(null);
    setInstallResult(null);
    setUninstallPlan(null);
    try {
      const result = await api<Run>('/api/v1/hrms/platform/qa/runs/install', {
        method: 'POST',
        body: JSON.stringify({ packKey: pack.packKey, idempotencyKey: idempotencyKey.trim() }),
      });
      setSelectedRun(result);
      if (result.state === 'INSTALLED') {
        setInstallResult(result);
        setNotice('');notifyUser(t('people.qa.installSuccess'),'success');
        setIdempotencyKey(newIdempotencyKey());
      } else {
        setError(String(result.error_summary ?? result.error_code ?? t('people.qa.installBlocked')));
      }
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : t('people.qa.actionError'));
    } finally {
      setBusy(false);
    }
  }

  async function verifyRun() {
    if (!activeRunId || busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await api<Run>(
        '/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(activeRunId) + '/verify',
        { method: 'POST', body: JSON.stringify({}) },
      );
      setSelectedRun(result);
      const verification = result.verification;
      if (verification?.overallStatus === 'PASS') {
        setNotice('');notifyUser(String(verification.passedChecks ?? 0) + ' checks passed. ' + t('people.qa.overallPass'),'success');
      } else {
        setError(String(verification?.failedChecks ?? 0) + ' ' + t('people.qa.checksFailed'));
      }
      await refresh();
      const detail = await api<Run>('/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(activeRunId));
      setSelectedRun({ ...detail, verification });
    } catch (e) {
      setError(e instanceof Error ? e.message : t('people.qa.actionError'));
    } finally {
      setBusy(false);
    }
  }

  async function previewUninstall() {
    if (!activeRunId || busy) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await api<JsonMap>(
        '/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(activeRunId) + '/uninstall-plan',
      );
      setUninstallPlan(result);
      setConfirmRunId('');
    } catch (e) {
      setError(e instanceof Error ? e.message : t('people.qa.actionError'));
    } finally {
      setBusy(false);
    }
  }

  async function uninstall() {
    if (!activeRunId || busy) return;
    if (confirmRunId.trim().toLowerCase() !== activeRunId.toLowerCase()) {
      setError(t('people.qa.emptyRunId'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await api<Run>(
        '/api/v1/hrms/platform/qa/runs/' + encodeURIComponent(activeRunId) + '/uninstall',
        { method: 'POST', body: JSON.stringify({ confirmRunId: confirmRunId.trim() }) },
      );
      setSelectedRun(result);
      setUninstallPlan(null);
      setConfirmRunId('');
      if (result.state === 'UNINSTALLED') {
        setNotice('');notifyUser(t('people.qa.baselinePassed'),'success');
      } else {
        setError(String(result.error_summary ?? result.error_code ?? t('people.qa.cleanupBlocked')));
      }
      await refresh();
    } catch (e) {
      setError(e instanceof Error ? e.message : t('people.qa.actionError'));
    } finally {
      setBusy(false);
    }
  }

  const credentialPayload = installResult?.credentialsIncluded
    ? installResult.oneTimeSyntheticCredentials : null;
  const currentPack = selectedRun ? String(selectedRun.pack_key ?? selectedRun.packKey ?? '') : '';
  const resourceRows = Array.isArray(selectedRun?.resources) ? selectedRun.resources : [];
  const canUninstall = Boolean(activeRunId && selectedRun?.state !== 'UNINSTALLED');

  return (
    <main className={styles.page}>
      <PeoplePageHeader
        eyebrow={t('people.brand.operations')}
        title={t('people.qa.title')}
        description={t('people.qa.subtitle')}
        actions={<button className="secondary" type="button" onClick={() => void refresh()} disabled={loading || busy}>{t('people.qa.refresh')}</button>}
      />

      {error && <PeopleFeedback tone="danger" role="alert">{error}</PeopleFeedback>}
      {notice && <PeopleFeedback tone="success" role="status">{notice}</PeopleFeedback>}

      <section className="panel" aria-labelledby="qa-gate-heading">
        <div className={styles.sectionHeading}>
          <div>
            <h2 id="qa-gate-heading">{t('people.qa.gateTitle')}</h2>
            <p className="small">{gate?.reason ?? t('people.qa.loading')}</p>
          </div>
          <PeopleStatusBadge status={gate?.writeAllowed ? 'WRITE_ALLOWED' : 'BLOCKED'} tone={gate?.writeAllowed ? 'success' : 'danger'} />
        </div>
        <dl className={styles.gateGrid}>
          <div><dt>{t('people.qa.gateEnabled')}</dt><dd>{gate?.featureEnabled ? 'YES' : 'NO'}</dd></div>
          <div><dt>{t('people.qa.gateApproved')}</dt><dd>{gate?.environmentApproved ? 'YES' : 'NO'}</dd></div>
          <div><dt>{t('people.qa.gateDatabase')}</dt><dd>{gate?.currentDatabase || '—'}</dd></div>
          <div><dt>{t('people.qa.gateLabel')}</dt><dd>{gate?.environmentLabel || '—'}</dd></div>
        </dl>
        {!gate?.writeAllowed && <p className={styles.guardNote}>{t('people.qa.installDisabled')}</p>}
      </section>

      <section className="panel" aria-labelledby="qa-packs-heading">
        <div className={styles.sectionHeading}>
          <div>
            <h2 id="qa-packs-heading">{t('people.qa.packsTitle')}</h2>
            <p className="small">{t('people.qa.packScope')}</p>
          </div>
          <span className="pill">{packs.length}</span>
        </div>
        {packs.map(pack => (
          <article className={styles.packCard} key={pack.packKey + ':' + pack.version}>
            <div className={styles.packHeading}>
              <div>
                <h3>{pack.title || t('people.qa.packName')}</h3>
                <p>{pack.summary || t('people.qa.packDescription')}</p>
              </div>
              <span className="pill">v{pack.version}</span>
            </div>
            {pack.personas?.length ? <p className="small">{pack.personas.join(' · ')}</p> : null}
            <p className={styles.packKey}>{pack.packKey}</p>
            <div className={styles.installRow}>
              <label className="field">
                {t('people.qa.idempotency')}
                <input className="input" value={idempotencyKey} onChange={event => setIdempotencyKey(event.target.value)} maxLength={120} spellCheck={false} />
                <span className="small">{t('people.qa.idempotencyHelp')}</span>
              </label>
              <button className="primary" type="button" disabled={!gate?.writeAllowed || busy || !idempotencyKey.trim()} onClick={() => void install(pack)}>
                {busy ? t('people.qa.installing') : t('people.qa.install')}
              </button>
            </div>
          </article>
        ))}
      </section>

      <section className="panel" aria-labelledby="qa-runs-heading">
        <div className={styles.sectionHeading}>
          <h2 id="qa-runs-heading">{t('people.qa.runHistory')}</h2>
          <span className="pill">{sortedRuns.length}</span>
        </div>
        {loading ? <p className="empty">{t('people.qa.loading')}</p> : sortedRuns.length === 0 ? (
          <PeopleEmptyState title={t('people.qa.noRuns')} />
        ) : (
          <div className={styles.runList}>
            {sortedRuns.map(run => {
              const id = runId(run);
              return (
                <article className={styles.runRow} key={id}>
                  <div className={styles.runMeta}>
                    <strong>{run.pack_key || run.packKey || t('people.qa.pack')}</strong>
                    <code>{id}</code>
                    <span className="small">{formatTime(run.created_at)}</span>
                  </div>
                  <div className={styles.runActions}>
                    <PeopleStatusBadge status={String(run.state || 'UNKNOWN')} />
                    <button type="button" className="secondary" onClick={() => void inspectRun(id)} disabled={busy}>{t('people.qa.view')}</button>
                  </div>
                </article>
              );
            })}
          </div>
        )}
      </section>

      {selectedRun && (
        <section className="panel" aria-labelledby="qa-run-detail-heading">
          <div className={styles.sectionHeading}>
            <div>
              <h2 id="qa-run-detail-heading">{t('people.qa.runId')}</h2>
              <code className={styles.fullRunId}>{activeRunId}</code>
              <p className="small">{currentPack}</p>
            </div>
            <PeopleStatusBadge status={String(selectedRun.state || 'UNKNOWN')} />
          </div>
          {selectedRun.error_summary ? <PeopleFeedback tone="danger" role="alert">{String(selectedRun.error_summary)}</PeopleFeedback> : null}
          <dl className={styles.gateGrid}>
            <div><dt>{t('people.qa.resources')}</dt><dd>{String(selectedRun.created_resource_count ?? 0)} / {String(selectedRun.planned_resource_count ?? 0)}</dd></div>
            <div><dt>{t('people.qa.created')}</dt><dd>{formatTime(selectedRun.created_at)}</dd></div>
            <div><dt>{t('people.qa.updated')}</dt><dd>{formatTime(selectedRun.updated_at)}</dd></div>
            <div><dt>{t('people.qa.remaining')}</dt><dd>{String((selectedRun.cleanup_report as Record<string, unknown> | undefined)?.resourcesRemaining ?? '—')}</dd></div>
          </dl>
          <div className={styles.detailActions}>
            <button className="secondary" type="button" onClick={() => void inspectRun(activeRunId)} disabled={busy}>{t('people.qa.refresh')}</button>
            <button className="primary" type="button" onClick={() => void verifyRun()} disabled={busy || selectedRun.state !== 'INSTALLED' || !gate?.writeAllowed}>{t('people.qa.runChecks')}</button>
            <button data-testid="qa-preview-uninstall" className="secondary" type="button" onClick={() => void previewUninstall()} disabled={busy || !canUninstall || !gate?.writeAllowed}>{t('people.qa.uninstallPlan')}</button>
          </div>
          {selectedRun.fixtureSummary && Object.keys(selectedRun.fixtureSummary).length > 0 ? (
            <details className={styles.details}>
              <summary>{t('people.qa.fixtureSummary')}</summary>
              <pre className={styles.json}>{pretty(selectedRun.fixtureSummary)}</pre>
            </details>
          ) : null}
          {selectedRun.checks && selectedRun.checks.length > 0 ? (
            <details className={styles.details} open>
              <summary>{t('people.qa.checks')} ({selectedRun.checks.length})</summary>
              <div className={styles.checkList}>
                {selectedRun.checks.map((check, index) => (
                  <div className={styles.checkRow} key={String(check.check_key ?? index)}>
                    <div className={styles.checkHead}>
                      <strong>{String(check.check_key ?? 'check')}</strong>
                      <PeopleStatusBadge status={String(check.status ?? 'UNKNOWN')} />
                    </div>
                    <p><span>{t('people.qa.expected')}: </span>{String(check.expected ?? '—')}</p>
                    <p><span>{t('people.qa.actual')}: </span>{String(check.actual ?? '—')}</p>
                    <span className="small">{formatTime(check.executed_at)}</span>
                  </div>
                ))}
              </div>
            </details>
          ) : null}
          <details className={styles.details}>
            <summary>{t('people.qa.manifest')} ({resourceRows.length})</summary>
            {resourceRows.length === 0 ? <p className="small">—</p> : (
              <div className={styles.manifestList}>
                {resourceRows.map((resource, index) => (
                  <div key={String(resource.resource_key ?? index)} className={styles.manifestRow}>
                    <strong>{String(resource.resource_key ?? resource.resource_kind ?? 'resource')}</strong>
                    <span>{String(resource.resource_kind ?? '')} · {String(resource.resource_id ?? '')}</span>
                    <PeopleStatusBadge status={String(resource.state ?? 'UNKNOWN')} />
                  </div>
                ))}
              </div>
            )}
          </details>
          {selectedRun.cleanup_report ? (
            <details className={styles.details}>
              <summary>{t('people.qa.cleanupSummary')}</summary>
              <pre className={styles.json}>{pretty(selectedRun.cleanup_report)}</pre>
              {(selectedRun.cleanup_report as Record<string, unknown>)?.baselineCheckPassed === true
                ? <PeopleFeedback tone="success">{t('people.qa.baselinePassed')}</PeopleFeedback>
                : <PeopleFeedback tone="danger">{t('people.qa.baselineFailed')}</PeopleFeedback>}
            </details>
          ) : null}
        </section>
      )}

      {uninstallPlan && selectedRun && (
        <section className={styles.confirmPanel} aria-labelledby="qa-uninstall-heading">
          <div className={styles.sectionHeading}>
            <h2 id="qa-uninstall-heading" data-testid="qa-uninstall-heading">{t('people.qa.uninstallPlan')}</h2>
            <PeopleStatusBadge status="PREVIEW_ONLY" tone="warning" />
          </div>
          <p>{t('people.qa.previewWarning')}</p>
          <p className="small">{String(uninstallPlan.resourceCount ?? 0)} {t('people.qa.resources')}</p>
          <div className={styles.planList}>
            {Array.isArray(uninstallPlan.resources) && uninstallPlan.resources.map((item, index) => {
              const resource = item as Record<string, unknown>;
              return <div key={String(resource.resource_key ?? index)}>
                <strong>{String(resource.resource_key ?? resource.resource_kind ?? 'resource')}</strong>
                <span>{String(resource.resource_kind ?? '')} · {String(resource.resource_id ?? '')} · {String(resource.state ?? '')}</span>
              </div>;
            })}
          </div>
          <label className="field">
            {t('people.qa.confirmLabel')}
            <input className="input" value={confirmRunId} onChange={event => setConfirmRunId(event.target.value)} autoComplete="off" spellCheck={false} />
            <span className="small">{t('people.qa.confirmHelp')}</span>
          </label>
          <div className={styles.detailActions}>
            <button className="secondary" type="button" disabled={busy} onClick={() => { setUninstallPlan(null); setConfirmRunId(''); }}>{t('people.qa.cancel')}</button>
            <button data-testid="qa-confirm-uninstall" className="dangerButton" type="button" disabled={busy || confirmRunId.trim().toLowerCase() !== activeRunId.toLowerCase() || !gate?.writeAllowed} onClick={() => void uninstall()}>{t('people.qa.confirmUninstall')}</button>
          </div>
        </section>
      )}

      {credentialPayload != null && (
        <section className={styles.credentials} aria-labelledby="qa-credentials-heading">
          <h2 id="qa-credentials-heading">{t('people.qa.credentials')}</h2>
          <PeopleFeedback tone="warning">{t('people.qa.credentialsWarning')}</PeopleFeedback>
          <pre className={styles.json}>{pretty(credentialPayload)}</pre>
          <p className="small">This panel exists only for the current page session; reopening the run will not restore plaintext credentials.</p>
        </section>
      )}
    </main>
  );
}
