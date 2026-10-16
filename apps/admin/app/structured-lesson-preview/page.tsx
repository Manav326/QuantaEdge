'use client';

import { useState, type ChangeEvent } from 'react';
import AdminSidebar from '../components/AdminSidebar';
import { validateStructuredLesson, type LessonLocale, type StructuredLesson, type ValidationResult } from './contract';
import StructuredLessonExperience from './StructuredLessonExperience';
import './preview.css';

export default function StructuredLessonPreviewPage() {
  const [raw, setRaw] = useState('');
  const [fileName, setFileName] = useState('');
  const [validation, setValidation] = useState<ValidationResult | null>(null);
  const [locale, setLocale] = useState<LessonLocale>('en');
  const [activityResponses, setActivityResponses] = useState<Record<string, string>>({});
  const [showRaw, setShowRaw] = useState(false);

  function inspect(value = raw) {
    try {
      const parsed: unknown = JSON.parse(value);
      setValidation(validateStructuredLesson(parsed));
    } catch (error) {
      setValidation({
        ok: false,
        lesson: null,
        issues: [{
          path: '$',
          code: 'INVALID_JSON',
          message: error instanceof Error ? error.message : 'Invalid JSON document.',
        }],
      });
    }
  }

  async function handleFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.currentTarget.files?.[0];
    if (!file) return;
    const value = await file.text();
    setRaw(value);
    setFileName(file.name);
    inspect(value);
  }

  const lesson: StructuredLesson | null = validation?.ok ? validation.lesson : null;
  const declaredWorkflowStatus = String(lesson?.workflow?.status || 'UNKNOWN').toUpperCase();
  const publicationFlagSet = lesson?.workflow?.publication_allowed === true;

  return <main className="admin-shell qe-structured-preview-shell">
    <AdminSidebar active="content" variant="content" persistent />
    <section className="admin-main qe-structured-preview-main">
      <header className="admin-top qe-structured-preview-top">
        <div>
          <span className="admin-kicker">QUANTAEDGE / STRUCTURED CONTENT</span>
          <h1>Structured lesson preview</h1>
          <p>Load an authored lesson JSON to validate it and preview the Explain and Practice UI. This page does not publish or save an artifact.</p>
        </div>
        <a className="qe-secondary-button" href="/content">Back to Content Studio</a>
      </header>

      <section className="qe-structured-import-panel">
        <div className="qe-structured-import-heading">
          <div>
            <h2>Load lesson artifact</h2>
            <p>Choose a .json file or paste the full artifact. Draft visuals stay blocked in student mode.</p>
          </div>
          {fileName && <span className="qe-structured-file-name">{fileName}</span>}
        </div>
        <label className="qe-structured-upload">
          <span>Choose JSON file</span>
          <input type="file" accept="application/json,.json" onChange={event => void handleFile(event)} />
        </label>
        <label className="qe-structured-field">
          <span>Lesson JSON</span>
          <textarea value={raw} onChange={event => { setRaw(event.target.value); setValidation(null); }} rows={8} spellCheck={false} placeholder="Paste a complete structured lesson JSON artifact here…" />
        </label>
        <div className="qe-structured-actions">
          <button type="button" onClick={() => inspect()} disabled={!raw.trim()}>Validate and preview</button>
          <button type="button" className="qe-structured-button-secondary" onClick={() => { setRaw(''); setFileName(''); setValidation(null); setActivityResponses({}); }}>Clear</button>
        </div>
      </section>

      {validation && !validation.ok && <section className="qe-structured-validation qe-structured-validation--error" role="alert">
        <h2>Validation failed</h2>
        <p>{validation.issues.length} issue(s) must be corrected before preview can be trusted.</p>
        <ul>{validation.issues.map((issue, index) => <li key={issue.path + issue.code + index}>
          <code>{issue.path}</code> <strong>{issue.code}</strong> — {issue.message}
        </li>)}</ul>
      </section>}

      {lesson && validation?.ok && <section className="qe-structured-preview-result">
        <div className="qe-structured-validation qe-structured-validation--success">
          <div>
            <h2>Artifact contract passed</h2>
            <p>Structural validation only. Source completeness, science review, Hindi semantic parity, visual approval and publishability remain separate review steps.</p>
          </div>
          <span className="qe-structured-status-pill">Workflow: {declaredWorkflowStatus} · {publicationFlagSet ? 'publication flag set' : 'not publishable'}</span>
        </div>
        <div className="qe-structured-preview-toolbar">
          <div>
            <strong>{lesson.topic.title_by_locale[locale]}</strong>
            <small>{lesson.artifact_id} · {lesson.practice.questions.length} question(s)</small>
          </div>
          <div className="qe-structured-language-switch" aria-label="Preview language">
            <button type="button" aria-pressed={locale === 'en'} className={locale === 'en' ? 'active' : ''} onClick={() => setLocale('en')}>English</button>
            <button type="button" aria-pressed={locale === 'hi'} className={locale === 'hi' ? 'active' : ''} onClick={() => setLocale('hi')}>हिन्दी</button>
            <button type="button" className="qe-structured-button-secondary" onClick={() => setShowRaw(value => !value)}>{showRaw ? 'Hide JSON' : 'Show JSON'}</button>
          </div>
        </div>
        {showRaw && <pre className="qe-structured-raw-json">{JSON.stringify(lesson, null, 2)}</pre>}
        <StructuredLessonExperience
          key={lesson.artifact_id}
          lesson={lesson}
          locale={locale}
          mode="admin-preview"
          onActivitySave={(blockId, value) => setActivityResponses(previous => ({ ...previous, [blockId]: value }))}
        />
        {Object.keys(activityResponses).length > 0 && <details className="qe-structured-activity-saves">
          <summary>Activity responses saved in this preview session ({Object.keys(activityResponses).length})</summary>
          <pre>{JSON.stringify(activityResponses, null, 2)}</pre>
        </details>}
      </section>}
    </section>
  </main>;
}
