'use client';

import Link from 'next/link';
import { useState } from 'react';
import AdminSidebar from '../../components/AdminSidebar';
import styles from './CoursePackageImport.module.css';

type Validation = {
  valid: boolean;
  schema?: string;
  errors?: string[];
  packageId?: string;
  packageVersion?: string;
  courseName?: string;
  courseTypeCode?: string;
  subjectCount?: number;
  chapterCount?: number;
  lessonCount?: number;
  blockCount?: number;
  releaseStatus?: string;
  studentDeliveryAllowed?: boolean;
};
type ImportResult = {
  created?: boolean;
  replayed?: boolean;
  courseId?: number | string;
  releaseId?: number | string;
  importSummary?: Record<string, unknown>;
  releaseStatus?: string;
  studentDeliveryAllowed?: boolean;
  message?: string;
};

const EXAMPLE = `{
  "schema": "quantaedge.course-package.v1",
  "package_id": "class6-maths-fractions",
  "package_version": "1.0.0",
  "course": {
    "code": "CLASS6_MATHS",
    "display_name": "Class 6 Mathematics",
    "course_type_code": "K12",
    "curriculum_version": "NCERT-2026",
    "class_code": "6"
  },
  "subjects": [
    {
      "code": "maths",
      "display_name": "Mathematics",
      "sort_order": 1,
      "chapters": [
        {
          "code": "fractions",
          "display_name": "Fractions",
          "sort_order": 1,
          "source": {
            "title": "Official source textbook",
            "edition": "2026",
            "pages": "18-24"
          },
          "lessons": [
            {
              "code": "fraction-basics",
              "title": "Understanding fractions",
              "summary": "Represent equal parts of a whole.",
              "estimated_minutes": 10,
              "sort_order": 1,
              "blocks": [
                {
                  "sequence_no": 1,
                  "block_type": "EXPLANATION",
                  "content": {
                    "heading": "Fractions",
                    "body": "Replace this illustrative text with reviewed source-aligned teaching content."
                  },
                  "active": true
                },
                {
                  "sequence_no": 2,
                  "block_type": "RECAP",
                  "content": {
                    "points": ["Add the verified key takeaway here."]
                  },
                  "active": true
                }
              ]
            }
          ]
        }
      ]
    }
  ],
  "editorial": {
    "notes": "Example template only. Replace every illustrative field before import.",
    "source_rights_status": "UNCONFIRMED"
  }
}`;

async function requestJson(path: string, payload: unknown) {
  const response = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.detail || body.message || body.error || `Request failed (${response.status}).`);
  }
  return body;
}

export default function CoursePackageImportPage() {
  const [source, setSource] = useState('');
  const [validation, setValidation] = useState<Validation | null>(null);
  const [validatedSource, setValidatedSource] = useState('');
  const [result, setResult] = useState<ImportResult | null>(null);
  const [busy, setBusy] = useState<'validate' | 'import' | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  function updateSource(next: string) {
    setSource(next);
    setValidation(null);
    setValidatedSource('');
    setResult(null);
    setError('');
    setNotice('');
  }

  function parseSource(): Record<string, unknown> {
    const parsed: unknown = JSON.parse(source);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('The package root must be a JSON object.');
    }
    return parsed as Record<string, unknown>;
  }

  async function validate() {
    setBusy('validate');
    setError('');
    setNotice('');
    setResult(null);
    try {
      const checked = await requestJson('/api/v1/admin/content/course-packages/validate', parseSource()) as Validation;
      setValidation(checked);
      setValidatedSource(checked.valid ? source : '');
      if (checked.valid) {
        setNotice('Package structure is valid. This only validates the draft; it does not publish or grant student access.');
      } else {
        setError('Correct the validation findings before importing.');
      }
    } catch (e) {
      setValidation(null);
      setValidatedSource('');
      setError(e instanceof Error ? e.message : 'The package could not be validated.');
    } finally {
      setBusy(null);
    }
  }

  async function importDraft() {
    if (!validation?.valid || validatedSource !== source) {
      setError('Validate the exact current package before importing.');
      return;
    }
    setBusy('import');
    setError('');
    setNotice('');
    try {
      const imported = await requestJson('/api/v1/admin/content/course-packages/import', parseSource()) as ImportResult;
      setResult(imported);
      setNotice(imported.replayed
        ? 'This package version was already imported. The original course and release were returned.'
        : 'The complete course hierarchy was imported atomically as a draft.');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The package import failed; no partial course should be committed.');
    } finally {
      setBusy(null);
    }
  }

  const mayImport = Boolean(validation?.valid && validatedSource === source && !busy);

  return (
    <main className={`admin-shell ${styles.workspace}`}>
      <AdminSidebar active="content" variant="content" />
      <section className={`admin-main ${styles.main}`}>
        <header className={styles.header}>
          <div>
            <span className={styles.kicker}>COURSE STUDIO · JSON AUTHORING</span>
            <h1>Import a complete K12 course package</h1>
            <p>Validate the nested subjects, chapters, lessons and teaching blocks before creating a source-owned draft. The server generates every database ID.</p>
          </div>
          <Link href="/content" className={styles.secondary}>Back to Content Studio</Link>
        </header>

        <section className={styles.boundary} aria-label="Import safety rules">
          <strong>Draft-only import</strong>
          <span>Import never publishes lessons or opens student access. HRMS release review and Marketplace publication remain separate required steps.</span>
          <span>Version 1 supports K12 hierarchy only. Competitive and standalone assessment packages need their own type-specific import workflow.</span>
        </section>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {notice && <div className={styles.notice} role="status">{notice}</div>}

        <section className={styles.panel}>
          <div className={styles.panelHead}>
            <div>
              <h2>Package JSON</h2>
              <p>Keep package IDs stable. If content changes, increment <code>package_version</code>; the server rejects changed content under an already-used package ID/version.</p>
            </div>
            <button type="button" className={styles.secondary} disabled={Boolean(busy)} onClick={() => updateSource(EXAMPLE)}>Load example</button>
          </div>
          <label className={styles.field}>
            <span>Course package (JSON)</span>
            <textarea
              value={source}
              onChange={e => updateSource(e.target.value)}
              spellCheck={false}
              autoCapitalize="off"
              autoCorrect="off"
              placeholder="Paste a package that follows schemas/course-package.schema.json. Load the example to view the full nested shape."
              rows={24}
              aria-describedby="package-help"
            />
            <small id="package-help">Use UTF-8 JSON. Unknown fields, duplicate codes/sequences, unsupported block types, empty chapters/lessons, and incomplete teaching blocks are rejected.</small>
          </label>
          <div className={styles.actions}>
            <button type="button" className={styles.primary} onClick={() => void validate()} disabled={Boolean(busy) || !source.trim()}>
              {busy === 'validate' ? 'Validating…' : 'Validate package'}
            </button>
            <button type="button" className={styles.primary} onClick={() => void importDraft()} disabled={!mayImport}>
              {busy === 'import' ? 'Importing…' : 'Import draft'}
            </button>
          </div>
        </section>

        {validation && (
          <section className={styles.panel} aria-live="polite">
            <div className={styles.panelHead}>
              <div>
                <h2>{validation.valid ? 'Validation summary' : 'Validation findings'}</h2>
                <p>{validation.valid ? 'The schema-shaped hierarchy has passed server-side checks.' : 'No database import has been attempted.'}</p>
              </div>
              <span className={validation.valid ? styles.okPill : styles.failPill}>{validation.valid ? 'VALID' : 'INVALID'}</span>
            </div>
            {validation.valid ? (
              <div className={styles.summaryGrid}>
                <div><small>Package</small><strong>{validation.packageId} · v{validation.packageVersion}</strong></div>
                <div><small>Course</small><strong>{validation.courseName}</strong></div>
                <div><small>Hierarchy</small><strong>{validation.subjectCount} subjects / {validation.chapterCount} chapters</strong></div>
                <div><small>Teaching content</small><strong>{validation.lessonCount} lessons / {validation.blockCount} blocks</strong></div>
                <div><small>Resulting state</small><strong>Draft only; student delivery disabled</strong></div>
              </div>
            ) : (
              <ul className={styles.errors}>{(validation.errors || []).map((item, index) => <li key={`${index}-${item}`}>{item}</li>)}</ul>
            )}
          </section>
        )}

        {result && (
          <section className={styles.panel} aria-live="polite">
            <div className={styles.panelHead}>
              <div>
                <h2>{result.replayed ? 'Existing import returned' : 'Draft import completed'}</h2>
                <p>Course ID {String(result.courseId)} · release ID {String(result.releaseId)}</p>
              </div>
              <span className={styles.okPill}>{result.releaseStatus || 'DRAFT'}</span>
            </div>
            <div className={styles.summaryGrid}>
              {Object.entries(result.importSummary || {}).map(([key, value]) => (
                <div key={key}><small>{key.replaceAll(/([A-Z])/g, ' $1')}</small><strong>{String(value)}</strong></div>
              ))}
              <div><small>Student delivery</small><strong>{result.studentDeliveryAllowed ? 'Enabled' : 'Disabled'}</strong></div>
            </div>
          </section>
        )}

        <section className={styles.reference}>
          <h2>Schema and workflow reference</h2>
          <p>See <code>schemas/course-package.schema.json</code> for the machine-readable contract and <code>schemas/course-package.schema.md</code> for field definitions, constraints and an annotated example.</p>
        </section>
      </section>
    </main>
  );
}
