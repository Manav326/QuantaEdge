'use client';

import Link from 'next/link';
import { useContext, useState, type ChangeEvent, type FormEvent } from 'react';
import AdminSidebar from '../../components/AdminSidebar';
import { apiFetch } from '../../components/apiClient';
import { AdminWorkspaceContext } from '../../components/AdminWorkspaceContext';

type CourseResult = {
  courseId?: number;
  publicId?: string;
  courseCode?: string;
  courseIdentityId?: number;
  releaseId?: number;
  releaseStatus?: string;
  displayName?: string;
  curriculumVersion?: string;
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
type PackageEntry = {
  fileName: string;
  packageJson: Record<string, any>;
  parseError?: string;
  validation?: PackageValidation;
  validatedSignature?: string;
  importResult?: ImportResult;
};

const EXAMPLE_PACKAGE = {
  schema: 'quantaedge.portable-assessment-package.v1',
  package_id: 'example-assessment-practice-set',
  package_version: '1.0.0',
  title: 'EXAMPLE ONLY — Assessment Practice Set',
  description: 'Replace this example with verified source content before importing.',
  assessment_type: 'PRACTICE_SET',
  source: {
    issuer: 'Example issuer — replace before use',
    exam_name: 'Example assessment — not official exam content',
    cycle: 'Practice Set 1',
    source_url: 'https://example.org/official-source.pdf',
    source_status: 'DISCOVERED',
    repository_distribution_rights: 'UNCONFIRMED',
    available_languages: ['en', 'hi']
  },
  configuration: {
    duration_minutes: 60,
    marks_per_question: 1,
    negative_marking_fraction: 0,
    unanswered_mark: 0,
    max_attempts: 1
  },
  sections: [
    { code: 'COMPUTER_SCIENCE', name_i18n: { en: 'Computer Science', hi: 'कंप्यूटर विज्ञान' }, question_count: 1, result_policy: 'SCORED' }
  ],
  questions: [
    {
      id: 'example-question-1',
      source_question_number: 'EXAMPLE-1',
      question_type: 'MCQ',
      prompt: 'EXAMPLE ONLY — replace with a verified source question.',
      prompt_i18n: { en: 'EXAMPLE ONLY — replace with a verified source question.', hi: 'केवल उदाहरण — सत्यापित स्रोत के प्रश्न से बदलें।' },
      options: [
        { key: 'A', text: 'Example option A', text_i18n: { en: 'Example option A', hi: 'उदाहरण विकल्प A' } },
        { key: 'B', text: 'Example option B', text_i18n: { en: 'Example option B', hi: 'उदाहरण विकल्प B' } }
      ],
      topic_tags: ['Example topic'],
      difficulty: 'CORE',
      proposed_answer: {
        option_key: null,
        confidence: 'UNRESOLVED',
        explanation: 'Pending human review.',
        answer_source: 'UNRESOLVED',
        review_status: 'PENDING_HUMAN_REVIEW',
        explanation_i18n: { en: 'Pending human review.', hi: 'मानवीय समीक्षा लंबित है।' },
        solution_mode: 'CONCISE'
      },
      section_code: 'COMPUTER_SCIENCE',
      section_result_policy: 'SCORED',
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

function clonePackage(pkg: Record<string, any>): Record<string, any> {
  return JSON.parse(JSON.stringify(pkg));
}
function packagePayload(entry: PackageEntry): Record<string, any> {
  const pkg = clonePackage(entry.packageJson);
  pkg.schema = pkg.schema || 'quantaedge.portable-assessment-package.v1';
  pkg.editorial = {
    ...(pkg.editorial && typeof pkg.editorial === 'object' ? pkg.editorial : {}),
    status: 'DRAFT',
    student_delivery_allowed: false,
    review_status: 'PENDING_HUMAN_REVIEW'
  };
  return pkg;
}
function sourceOf(pkg: Record<string, any>): Record<string, any> {
  return pkg.source && typeof pkg.source === 'object' ? pkg.source : {};
}
function configurationOf(pkg: Record<string, any>): Record<string, any> {
  return pkg.configuration && typeof pkg.configuration === 'object' ? pkg.configuration : {};
}

export default function AssessmentCourseStudioPage() {
  const { permissions } = useContext(AdminWorkspaceContext);
  const canCreate = permissions.includes('CONTENT_CREATE') && permissions.includes('CONTENT_EDIT');
  const [course, setCourse] = useState<CourseResult | null>(null);
  const [creating, setCreating] = useState(false);
  const [courseKey, setCourseKey] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [curriculumVersion, setCurriculumVersion] = useState('');
  const [packages, setPackages] = useState<PackageEntry[]>([]);
  const [validating, setValidating] = useState(false);
  const [importing, setImporting] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [hasValidation, setHasValidation] = useState(false);

  async function createCourse(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!canCreate) { setError('Your staff account needs CONTENT_CREATE and CONTENT_EDIT permission.'); return; }
    if (!/^[A-Z0-9][A-Z0-9_-]{1,37}$/.test(courseKey.trim().toUpperCase())) {
      setError('Course code must be 2–38 letters, digits, underscores or hyphens. Example: BPSC_TRE_CS_PYQ_SERIES.');
      return;
    }
    setCreating(true); setError(''); setNotice('');
    try {
      const created = await requestJson<CourseResult>('/api/v1/admin/content/courses', {
        code: courseKey.trim().toUpperCase(),
        displayName: displayName.trim(),
        courseTypeCode: 'ASSESSMENT',
        curriculumVersion: curriculumVersion.trim(),
        streamCode: 'ASSESSMENT'
      });
      if (!created.courseId || !created.publicId) {
        throw new Error('The API did not return the course identity and generated public ID. Stop here and have the platform team investigate; do not retry with another course code.');
      }
      setCourse(created);
      setNotice('Draft course created. Add your assessment package files, validate every package, then import them as drafts.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Assessment course could not be created.');
    } finally { setCreating(false); }
  }

  async function loadPackageFiles(event: ChangeEvent<HTMLInputElement>) {
    const files = Array.from(event.target.files || []);
    setError(''); setNotice(''); setHasValidation(false);
    if (!files.length) return;
    const entries: PackageEntry[] = await Promise.all(files.map(async file => {
      try {
        const raw = await file.text();
        const parsed: unknown = JSON.parse(raw);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
          return { fileName: file.name, packageJson: {}, parseError: 'The file root must be a JSON object.' };
        }
        return { fileName: file.name, packageJson: parsed as Record<string, any> };
      } catch (cause) {
        return { fileName: file.name, packageJson: {}, parseError: cause instanceof Error ? cause.message : 'This is not valid JSON.' };
      }
    }));
    setPackages(entries);
    if (entries.some(entry => entry.parseError)) {
      setError('One or more files could not be read as a portable assessment JSON package. See each file below.');
    } else {
      setNotice('Package files loaded. Review any missing fields shown in the forms, then validate all packages before importing.');
    }
  }

  function updatePackageField(index: number, area: 'root' | 'source' | 'configuration', key: string, value: string) {
    setPackages(current => current.map((entry, currentIndex) => {
      if (currentIndex !== index) return entry;
      const pkg = clonePackage(entry.packageJson);
      if (area === 'root') {
        pkg[key] = value;
      } else {
        const target = area === 'source' ? sourceOf(pkg) : configurationOf(pkg);
        const rootKey = area === 'source' ? 'source' : 'configuration';
        pkg[rootKey] = { ...target, [key]: value };
      }
      return { ...entry, packageJson: pkg, validation: undefined, validatedSignature: undefined, importResult: undefined };
    }));
    setHasValidation(false); setError(''); setNotice('');
  }

  function loadExamplePackage() {
    setPackages([{ fileName: 'EXAMPLE-ONLY-assessment-package.json', packageJson: clonePackage(EXAMPLE_PACKAGE) }]);
    setHasValidation(false); setError('');
    setNotice('Example loaded. Its question, answers and rights are placeholders; replace them with verified content before importing.');
  }

  async function validatePackages() {
    if (!course?.courseId) { setError('Create or choose the course before package validation.'); return; }
    if (!packages.length) { setError('Choose at least one portable assessment JSON package.'); return; }
    if (packages.some(entry => entry.parseError)) { setError('Fix the invalid JSON file(s) before validating.'); return; }
    setValidating(true); setError(''); setNotice(''); setHasValidation(false);
    const checked: PackageEntry[] = [];
    try {
      for (const entry of packages) {
        const payload = packagePayload(entry);
        const signature = JSON.stringify(payload);
        const result = await requestJson<PackageValidation>('/api/v1/admin/assessments/validate-package', {
          courseId: course.courseId,
          package: payload
        });
        checked.push({ ...entry, validation: result, validatedSignature: result.valid ? signature : undefined });
      }
      setPackages(checked);
      setHasValidation(checked.every(entry => entry.validation?.valid && entry.validatedSignature === JSON.stringify(packagePayload(entry))));
      const passed = checked.filter(entry => entry.validation?.valid).length;
      if (passed === checked.length) setNotice('All ' + passed + ' package(s) passed server-side validation. Import will create draft tests and versioned test packs only.');
      else setError(passed + ' of ' + checked.length + ' packages passed. Correct the fields and content listed on each package, then validate again.');
    } catch (cause) {
      setPackages(checked.length ? checked : packages);
      setError(cause instanceof Error ? cause.message : 'Package validation failed.');
    } finally { setValidating(false); }
  }

  async function importPackages() {
    if (!course?.courseId || !packages.length || !hasValidation
        || !packages.every(entry => entry.validation?.valid && entry.validatedSignature === JSON.stringify(packagePayload(entry)))) {
      setError('Validate all the exact current packages before importing.');
      return;
    }
    setImporting(true); setError(''); setNotice('');
    let completed = 0;
    const results = [...packages];
    try {
      for (let index = 0; index < results.length; index++) {
        const entry = results[index];
        if (entry.importResult) { completed++; continue; }
        const result = await requestJson<ImportResult>('/api/v1/admin/assessments/import-package', {
          courseId: course.courseId,
          package: packagePayload(entry)
        });
        results[index] = { ...entry, importResult: result };
        setPackages([...results]);
        completed++;
      }
      setNotice('Imported or verified all ' + completed + ' package(s). Every test and test pack remains in DRAFT pending review; nothing was published.');
    } catch (cause) {
      setPackages([...results]);
      setError('Import stopped after ' + completed + ' package(s). Completed packages remain draft. ' +
        'Correct the issue and rerun validation; identical package IDs, versions and content are safe to retry. ' +
        (cause instanceof Error ? cause.message : 'Package import failed.'));
    } finally { setImporting(false); }
  }

  const allPackagesValid = packages.length > 0 && packages.every(entry =>
    entry.validation?.valid && entry.validatedSignature === JSON.stringify(packagePayload(entry)));
  const missingQuestionContent = (entry: PackageEntry) =>
    !Array.isArray(entry.packageJson.sections) || !Array.isArray(entry.packageJson.questions);

  return (
    <main className="admin-shell">
      <AdminSidebar active="content" variant="content" />
      <section className="admin-main">
        <header className="admin-top">
          <div>
            <span className="admin-kicker">COURSE STUDIO · ASSESSMENT</span>
            <h1>Assessment course importer</h1>
            <p>Create a standalone assessment-only course, upload prepared package files, review missing information, validate everything, and import as drafts.</p>
          </div>
          <Link href="/content" className="button button-dark">Back to Content Studio</Link>
        </header>

        {error && <div className="feedback" role="alert">{error}</div>}
        {notice && <div className="feedback" role="status">{notice}</div>}
        {!canCreate && <section className="panel"><h2>View only</h2><p>Your staff account needs CONTENT_CREATE and CONTENT_EDIT permission.</p></section>}

        {canCreate && !course && (
          <form className="panel" onSubmit={createCourse}>
            <h2>1. Course details</h2>
            <p>Use a meaningful stable key. QuantaEdge generates the public course ID automatically; do not invent or type an 8-character public ID.</p>
            <label htmlFor="assessment-course-key">Stable course key / course code</label>
            <input id="assessment-course-key" value={courseKey} onChange={event => setCourseKey(event.target.value.toUpperCase())}
              required minLength={2} maxLength={38} pattern="[A-Z0-9][A-Z0-9_-]{1,37}"
              placeholder="BPSC_TRE_CS_PYQ_SERIES" autoComplete="off" />
            <p className="admin-muted">2–38 uppercase letters, digits, underscores or hyphens. Example: <code>BPSC_TRE_CS_PYQ_SERIES</code>. This is a stable logical key, not the generated public ID.</p>
            <label htmlFor="assessment-course-name">Course name</label>
            <input id="assessment-course-name" value={displayName} onChange={event => setDisplayName(event.target.value)}
              required minLength={2} maxLength={200} placeholder="BPSC TRE Computer Science — Previous-Year Practice Series" />
            <label htmlFor="assessment-course-version">Curriculum/content version</label>
            <input id="assessment-course-version" value={curriculumVersion} onChange={event => setCurriculumVersion(event.target.value)}
              required maxLength={120} placeholder="BPSC-TRE-CS-PYQ-SERIES" />
            <p>Course type: <strong>ASSESSMENT</strong> · Delivery: <strong>ASSESSMENT_ONLY</strong> · Source institution: determined by your authenticated HRMS assignment.</p>
            <button className="button button-primary" type="submit" disabled={creating}>
              {creating ? 'Creating draft course…' : 'Create draft course'}
            </button>
          </form>
        )}

        {course?.courseId && (
          <section className="panel">
            <h2>1. Draft course created</h2>
            <p><strong>{course.displayName || displayName}</strong></p>
            <p>Public course ID: <code>{course.publicId}</code> · Stable key: <code>{course.courseCode || courseKey.toUpperCase()}</code></p>
            <p>Internal database ID: {course.courseId} · Release: {course.releaseId ?? '—'} · State: {course.releaseStatus || 'DRAFT'}</p>
            <p>The public ID is generated by the registered QuantaEdge identity allocator. The course is not published and no learner access is enabled.</p>
          </section>
        )}

        {canCreate && course?.courseId && (
          <section className="panel">
            <h2>2. Add assessment package files</h2>
            <p>Select one or more prepared <code>quantaedge.portable-assessment-package.v1</code> JSON files. Each file becomes one versioned test pack containing one assessment and its ordered question snapshots.</p>
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', alignItems: 'center', marginBottom: 12 }}>
              <label htmlFor="assessment-package-files">Assessment package JSON files</label>
              <input id="assessment-package-files" type="file" accept=".json,application/json" multiple onChange={event => void loadPackageFiles(event)} />
              <button className="button button-dark" type="button" onClick={loadExamplePackage}>Load example</button>
              <a className="button button-dark" href="https://github.com/Manav326/QuantaEdge/blob/main/schemas/portable-assessment-package.schema.json" target="_blank" rel="noreferrer">Package format reference</a>
            </div>
            {packages.map((entry, index) => {
              const pkg = entry.packageJson;
              const source = sourceOf(pkg);
              const config = configurationOf(pkg);
              const validation = entry.validation;
              return (
                <section className="panel" key={entry.fileName + '-' + index} style={{ marginTop: 14, border: '1px solid var(--admin-border, #d8dce5)' }}>
                  <h3>{index + 1}. {entry.fileName}</h3>
                  {entry.parseError && <div className="feedback" role="alert">Could not read this file: {entry.parseError}</div>}
                  {!entry.parseError && (
                    <>
                      <p>Complete the fields that are missing from the file. The question and section content must come from your verified source; QuantaEdge will not invent questions or answers.</p>
                      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,240px),1fr))', gap: 12 }}>
                        <div>
                          <label htmlFor={'package-id-' + index}>Package ID</label>
                          <input id={'package-id-' + index} value={String(pkg.package_id || '')} onChange={event => updatePackageField(index, 'root', 'package_id', event.target.value.toLowerCase())} placeholder="tre-computer-science-paper-1" />
                        </div>
                        <div>
                          <label htmlFor={'package-version-' + index}>Package version</label>
                          <input id={'package-version-' + index} value={String(pkg.package_version || '')} onChange={event => updatePackageField(index, 'root', 'package_version', event.target.value)} placeholder="1.0.0" />
                        </div>
                        <div>
                          <label htmlFor={'package-title-' + index}>Test title</label>
                          <input id={'package-title-' + index} value={String(pkg.title || '')} onChange={event => updatePackageField(index, 'root', 'title', event.target.value)} placeholder="BPSC TRE Computer Science — Paper 1" />
                        </div>
                        <div>
                          <label htmlFor={'package-type-' + index}>Test type</label>
                          <select id={'package-type-' + index} value={String(pkg.assessment_type || 'PRACTICE_SET')} onChange={event => updatePackageField(index, 'root', 'assessment_type', event.target.value)}>
                            <option value="PREVIOUS_YEAR_PAPER">Previous-year paper</option>
                            <option value="MOCK_EXAM">Mock exam</option>
                            <option value="DIAGNOSTIC">Diagnostic</option>
                            <option value="PRACTICE_SET">Practice set</option>
                          </select>
                        </div>
                        <div>
                          <label htmlFor={'issuer-' + index}>Source issuer</label>
                          <input id={'issuer-' + index} value={String(source.issuer || '')} onChange={event => updatePackageField(index, 'source', 'issuer', event.target.value)} placeholder="BPSC" />
                        </div>
                        <div>
                          <label htmlFor={'exam-name-' + index}>Exam/source name</label>
                          <input id={'exam-name-' + index} value={String(source.exam_name || '')} onChange={event => updatePackageField(index, 'source', 'exam_name', event.target.value)} placeholder="TRE Computer Science" />
                        </div>
                        <div>
                          <label htmlFor={'cycle-' + index}>Exam cycle / label</label>
                          <input id={'cycle-' + index} value={String(source.cycle || '')} onChange={event => updatePackageField(index, 'source', 'cycle', event.target.value)} placeholder="TRE 1.0 or Practice Set 1" />
                        </div>
                        <div>
                          <label htmlFor={'source-url-' + index}>Source URL</label>
                          <input id={'source-url-' + index} type="url" value={String(source.source_url || '')} onChange={event => updatePackageField(index, 'source', 'source_url', event.target.value)} placeholder="https://example.org/official-paper.pdf" />
                        </div>
                        <div>
                          <label htmlFor={'rights-' + index}>Source distribution rights</label>
                          <select id={'rights-' + index} value={String(source.repository_distribution_rights || 'UNCONFIRMED')} onChange={event => updatePackageField(index, 'source', 'repository_distribution_rights', event.target.value)}>
                            <option value="UNCONFIRMED">Not yet confirmed</option>
                            <option value="CLEARED_FOR_INTERNAL_USE">Cleared for internal use</option>
                            <option value="CLEARED_FOR_PUBLIC_REPOSITORY">Cleared for public repository</option>
                            <option value="DO_NOT_REDISTRIBUTE">Do not redistribute</option>
                          </select>
                        </div>
                        <div>
                          <label htmlFor={'duration-' + index}>Duration (minutes)</label>
                          <input id={'duration-' + index} type="number" min={1} max={240} value={String(config.duration_minutes ?? '')} onChange={event => updatePackageField(index, 'configuration', 'duration_minutes', event.target.value)} placeholder="120" />
                        </div>
                        <div>
                          <label htmlFor={'marks-' + index}>Marks per question</label>
                          <input id={'marks-' + index} type="number" min={0} max={1000} step="any" value={String(config.marks_per_question ?? '')} onChange={event => updatePackageField(index, 'configuration', 'marks_per_question', event.target.value)} placeholder="1" />
                        </div>
                        <div>
                          <label htmlFor={'negative-' + index}>Negative-marking fraction (0–1)</label>
                          <input id={'negative-' + index} type="number" min={0} max={1} step="any" value={String(config.negative_marking_fraction ?? '')} onChange={event => updatePackageField(index, 'configuration', 'negative_marking_fraction', event.target.value)} placeholder="0.25" />
                        </div>
                        <div>
                          <label htmlFor={'unanswered-' + index}>Unanswered mark</label>
                          <input id={'unanswered-' + index} type="number" step="any" value={String(config.unanswered_mark ?? '')} onChange={event => updatePackageField(index, 'configuration', 'unanswered_mark', event.target.value)} placeholder="0" />
                        </div>
                        <div>
                          <label htmlFor={'attempts-' + index}>Maximum attempts</label>
                          <input id={'attempts-' + index} type="number" min={1} max={10} value={String(config.max_attempts ?? '')} onChange={event => updatePackageField(index, 'configuration', 'max_attempts', event.target.value)} placeholder="1" />
                        </div>
                      </div>
                      {missingQuestionContent(entry) && <div className="feedback" role="alert" style={{ marginTop: 12 }}>
                        This file is missing sections or questions. Those are source content and cannot be inferred. Upload a complete package; the reference format includes a section code such as <code>COMPUTER_SCIENCE</code> and a matching <code>questions</code> list.
                      </div>}
                      {validation && (
                        <div style={{ marginTop: 14 }}>
                          <h4>{validation.valid ? 'Validation passed' : 'Validation needs attention'}</h4>
                          <p>{validation.questionCount ?? 0} questions · {validation.sectionCount ?? 0} sections · {validation.durationMinutes ?? 0} minutes · {validation.maxAttempts ?? 1} maximum attempt(s) · Status: {validation.willCreateAs || 'DRAFT'}</p>
                          {validation.issues?.length ? <ul>{validation.issues.map((issue, issueIndex) => <li key={issueIndex}>{issue}</li>)}</ul> : null}
                        </div>
                      )}
                      {entry.importResult && (
                        <div style={{ marginTop: 14 }}>
                          <h4>{entry.importResult.idempotent ? 'Already imported — verified unchanged' : 'Imported as draft'}</h4>
                          <p>Test pack ID {entry.importResult.packId ?? '—'} · Assessment ID {entry.importResult.assessmentId ?? '—'} · Questions {entry.importResult.questionCount ?? '—'}</p>
                          <p>{entry.importResult.message || 'Question and answer review remains pending.'}</p>
                        </div>
                      )}
                    </>
                  )}
                </section>
              );
            })}
            <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap', marginTop: 16 }}>
              <button className="button button-dark" type="button" disabled={validating || importing || !packages.length} onClick={() => void validatePackages()}>
                {validating ? 'Validating packages…' : 'Validate all packages'}
              </button>
              <button className="button button-primary" type="button" disabled={importing || validating || !allPackagesValid} onClick={() => void importPackages()}>
                {importing ? 'Importing packages…' : 'Import all as drafts'}
              </button>
            </div>
            <p><strong>Safety:</strong> validation writes no assessment content. Import writes draft packages and assessments only. Answer review, test publication, course-release review, HRMS approval and Marketplace publication remain separate controls.</p>
          </section>
        )}
      </section>
    </main>
  );
}
