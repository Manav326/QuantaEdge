'use client';

import Link from 'next/link';
import { useContext, useState, type FormEvent } from 'react';
import { useRouter } from 'next/navigation';
import AdminSidebar from '../../components/AdminSidebar';
import { apiFetch } from '../../components/apiClient';
import { AdminWorkspaceContext } from '../../components/AdminWorkspaceContext';

type CourseResult = {
  courseId?: number;
  releaseId?: number;
  assessmentChapterId?: number;
  courseTypeCode?: string;
  releaseStatus?: string;
  displayName?: string;
  message?: string;
};
type PackageValidation = {
  valid?: boolean;
  issues?: string[];
  questionCount?: number;
  sectionCount?: number;
  title?: string;
  assessmentType?: string;
  durationMinutes?: number;
  maxAttempts?: number;
  willCreateAs?: string;
  publishesAutomatically?: boolean;
};
type ImportResult = {
  assessmentId?: number;
  packId?: number;
  questionCount?: number;
  imported?: boolean;
  idempotent?: boolean;
  message?: string;
};

const EXAMPLE_PACKAGE = {
  schema: 'quantaedge.portable-assessment-package.v1',
  package_id: 'tre4-bpsc-tre-1-0-computer-science',
  package_version: '1.0.0',
  title: 'BPSC TRE 1.0 — Computer Science Previous-Year Practice Test',
  description: 'Draft import template. Replace with the reviewed package content.',
  assessment_type: 'PREVIOUS_YEAR_PAPER',
  subject: { name: 'Computer Science', domain_code: 'computer-science', language: 'mixed' },
  source: {
    issuer: 'BPSC',
    exam_name: 'BPSC TRE',
    cycle: 'TRE 1.0',
    source_url: 'https://example.invalid/source',
    source_status: 'VERIFIED_SOURCE_FILE',
    repository_distribution_rights: 'UNCONFIRMED',
    available_languages: ['en', 'hi']
  },
  configuration: {
    duration_minutes: 120,
    marks_per_question: 1,
    negative_marking_fraction: 0.25,
    unanswered_mark: 0,
    max_attempts: 1
  },
  sections: [
    { code: 'COMPUTER_SCIENCE', name_i18n: { en: 'Computer Science', hi: 'कंप्यूटर विज्ञान' }, question_count: 1, result_policy: 'SCORED' }
  ],
  questions: [
    {
      id: 'replace-this-question',
      source_question_number: '41',
      source_location: { pdf_page: 1 },
      question_type: 'MCQ',
      prompt: 'Replace with the verified source question.',
      prompt_i18n: { en: 'Replace with the verified source question.' },
      options: [
        { key: 'A', text: 'Option A', text_i18n: { en: 'Option A' } },
        { key: 'B', text: 'Option B', text_i18n: { en: 'Option B' } }
      ],
      topic_tags: [],
      proposed_answer: { status: 'UNRESOLVED', answer_source: 'UNRESOLVED', confidence: 'UNRESOLVED' },
      section_code: 'COMPUTER_SCIENCE',
      marks: 1
    }
  ],
  editorial: { status: 'DRAFT', student_delivery_allowed: false, review_status: 'PENDING_HUMAN_REVIEW' }
};

async function requestJson<T>(path: string, payload: unknown): Promise<T> {
  const response = await apiFetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(String(body.message || body.detail || body.error || 'Request failed (' + response.status + ').'));
  return body as T;
}

export default function AssessmentCourseStudioPage() {
  const router = useRouter();
  const { permissions } = useContext(AdminWorkspaceContext);
  const canCreate = permissions.includes('CONTENT_CREATE') && permissions.includes('CONTENT_EDIT');
  const [course, setCourse] = useState<CourseResult | null>(null);
  const [creating, setCreating] = useState(false);
  const [displayName, setDisplayName] = useState('TRE-4 · Computer Science');
  const [code, setCode] = useState('TRE4');
  const [packageText, setPackageText] = useState('');
  const [validation, setValidation] = useState<PackageValidation | null>(null);
  const [validatedText, setValidatedText] = useState('');
  const [validating, setValidating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [lastImport, setLastImport] = useState<ImportResult | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  async function createCourse(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canCreate) { setError('Your staff account needs CONTENT_CREATE and CONTENT_EDIT permission.'); return; }
    setCreating(true); setError(''); setNotice('');
    try {
      const created = await requestJson<CourseResult>('/api/v1/admin/content/courses', {
        code: code.trim().toUpperCase(),
        displayName: displayName.trim(),
        courseTypeCode: 'ASSESSMENT',
        curriculumVersion: 'BPSC-TRE-4',
        streamCode: 'BPSC_TRE',
        classCode: 'TRE4',
        subjects: [{ code: 'computer-science', displayName: 'Computer Science', sortOrder: 1 }]
      });
      if (!created.courseId || !created.assessmentChapterId) {
        throw new Error('The API did not return the course and Prev years chapter bindings. No package should be imported until this is corrected.');
      }
      setCourse(created);
      setNotice('Assessment course created as a draft. Next, import the three TRE paper packages. This does not publish the tests or list the course.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Assessment course could not be created.');
    } finally { setCreating(false); }
  }

  function updatePackageText(value: string) {
    setPackageText(value); setValidation(null); setValidatedText(''); setLastImport(null); setError(''); setNotice('');
  }

  function parsePackage(): Record<string, unknown> {
    const parsed: unknown = JSON.parse(packageText);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('The assessment package root must be a JSON object.');
    return parsed as Record<string, unknown>;
  }

  async function validatePackage() {
    if (!course?.courseId || !course.assessmentChapterId) { setError('Create or select the assessment course first.'); return; }
    setValidating(true); setError(''); setNotice(''); setValidation(null); setValidatedText(''); setLastImport(null);
    try {
      const result = await requestJson<PackageValidation>('/api/v1/admin/assessments/validate-package', {
        courseId: course.courseId,
        subjectCode: 'computer-science',
        chapterId: course.assessmentChapterId,
        package: parsePackage()
      });
      setValidation(result);
      if (result.valid) {
        setValidatedText(packageText);
        setNotice('Package validation passed. Import creates a draft assessment only; question answers still require human review.');
      } else {
        setError('Package validation found issues. Correct them before importing.');
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Assessment package validation failed.');
    } finally { setValidating(false); }
  }

  async function importPackage() {
    if (!course?.courseId || !course.assessmentChapterId || !validation?.valid || validatedText !== packageText) {
      setError('Validate the exact current package before importing it.'); return;
    }
    setImporting(true); setError(''); setNotice(''); setLastImport(null);
    try {
      const result = await requestJson<ImportResult>('/api/v1/admin/assessments/import-package', {
        courseId: course.courseId,
        subjectCode: 'computer-science',
        chapterId: course.assessmentChapterId,
        package: parsePackage()
      });
      setLastImport(result);
      setNotice(result.idempotent ? 'This exact package version was already imported; existing records were unchanged.' : 'Assessment imported as a draft. It is not visible to students.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Assessment package import failed.');
    } finally { setImporting(false); }
  }

  return (
    <main className="admin-shell">
      <AdminSidebar active="content" variant="content" />
      <section className="admin-main">
        <header className="admin-top">
          <div>
            <span className="admin-kicker">COURSE STUDIO · ASSESSMENT</span>
            <h1>Assessment course studio</h1>
            <p>Create a test-first course, then import each portable assessment package into its versioned test pack. No school lesson hierarchy is fabricated.</p>
          </div>
          <Link href="/content" className="button button-dark">Back to Content Studio</Link>
        </header>

        {error && <div className="feedback" role="alert">{error}</div>}
        {notice && <div className="feedback" role="status">{notice}</div>}
        {!canCreate && <section className="panel"><h2>View only</h2><p>Your staff account needs CONTENT_CREATE and CONTENT_EDIT permission.</p></section>}

        {canCreate && !course && (
          <form className="panel" onSubmit={createCourse}>
            <h2>1. Create assessment course</h2>
            <p>The current launch-ready assessment profile is TRE-4. The TRE4 class is an internal compatibility scope named “Competitive Exam Preparation”, not a school grade.</p>
            <label htmlFor="assessment-course-code">Course code</label>
            <input id="assessment-course-code" value={code} onChange={e => setCode(e.target.value)} required minLength={2} maxLength={38} />
            <label htmlFor="assessment-course-name">Course name</label>
            <input id="assessment-course-name" value={displayName} onChange={e => setDisplayName(e.target.value)} required minLength={2} maxLength={200} />
            <p>Course type: <strong>ASSESSMENT</strong> · Version: <strong>BPSC-TRE-4</strong> · Subject: <strong>Computer Science</strong> · Chapter: <strong>Prev years</strong></p>
            <button className="button button-primary" type="submit" disabled={creating}>{creating ? 'Creating draft…' : 'Create assessment course'}</button>
          </form>
        )}

        {course?.courseId && (
          <section className="panel">
            <h2>1. Course draft created</h2>
            <p><strong>{course.displayName || displayName}</strong> · Course ID {course.courseId} · Release {course.releaseId ?? '—'} · {course.releaseStatus || 'DRAFT'}</p>
            <p>Delivery mode is assessment-only. The learner experience uses the existing test catalogue, attempt, result and course-scoped analytics APIs.</p>
            <button className="button button-dark" type="button" onClick={() => router.push('/content?courseId=' + encodeURIComponent(String(course.courseId)))}>Open course in Content Studio</button>
          </section>
        )}

        {canCreate && course?.courseId && (
          <section className="panel">
            <h2>2. Import an assessment package</h2>
            <p>Import one package per test (for example TRE 1.0, TRE 2.0 and TRE 3.0). Use the portable assessment schema; course, institution, subject and chapter binding are supplied by this authenticated workspace, not by package JSON.</p>
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: 12 }}>
              <button className="button button-dark" type="button" onClick={() => updatePackageText(JSON.stringify(EXAMPLE_PACKAGE, null, 2))}>Load schema example</button>
              <a className="button button-dark" href="/schemas/portable-assessment-package.schema.json" target="_blank" rel="noreferrer">Open package schema</a>
            </div>
            <label htmlFor="assessment-package-json">Portable assessment package JSON</label>
            <textarea id="assessment-package-json" value={packageText} onChange={e => updatePackageText(e.target.value)} rows={18} spellCheck={false} placeholder="Paste the reviewed portable assessment package JSON here." style={{ width: '100%', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: 13 }} />
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 12 }}>
              <button className="button button-dark" type="button" disabled={validating || importing || !packageText.trim()} onClick={() => void validatePackage()}>{validating ? 'Validating…' : 'Validate package'}</button>
              <button className="button button-primary" type="button" disabled={importing || validating || !validation?.valid || validatedText !== packageText} onClick={() => void importPackage()}>{importing ? 'Importing…' : 'Import as draft'}</button>
            </div>
            {validation && <div style={{ marginTop: 16, padding: 16, border: '1px solid var(--border, #d7dce2)', borderRadius: 12 }}>
              <h3>Validation summary</h3>
              <p>{validation.valid ? 'Valid package structure' : 'Package needs corrections'} · {validation.questionCount ?? 0} questions · {validation.sectionCount ?? 0} sections · {validation.durationMinutes ?? 0} minutes · {validation.maxAttempts ?? 1} attempt(s)</p>
              {validation.issues?.length ? <ul>{validation.issues.map((issue, index) => <li key={index}>{issue}</li>)}</ul> : null}
              <p>Will create: {validation.willCreateAs || 'DRAFT'} · Automatic publication: No</p>
            </div>}
            {lastImport && <div style={{ marginTop: 16, padding: 16, border: '1px solid var(--border, #d7dce2)', borderRadius: 12 }}>
              <h3>Import result</h3>
              <p>Assessment ID {lastImport.assessmentId ?? '—'} · Test pack ID {lastImport.packId ?? '—'} · Questions {lastImport.questionCount ?? validation?.questionCount ?? '—'}</p>
              <p>{lastImport.message || 'Imported as draft; complete human answer review and release approvals before student delivery.'}</p>
            </div>}
            <p><strong>Release safety:</strong> imported tests stay draft and learner-hidden. HRMS release review, source institution approval, assignment and Marketplace publication remain separate gates. Unresolved answers are not approved by this import.</p>
          </section>
        )}
      </section>
    </main>
  );
}
