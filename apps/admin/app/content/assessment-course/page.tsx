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
  editorOpen?: boolean;
  questionEditorOpen?: boolean;
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
    const normalizedKey = courseKey.trim().toUpperCase();
    if (!/^[A-Z0-9][A-Z0-9_-]{1,37}$/.test(normalizedKey)
        || /^[A-Z]{3}[A-Z0-9]{5}$/.test(normalizedKey)) {
      setError('Enter a stable course key of 2–38 letters, digits, underscores or hyphens, not an 8-character public ID. Example: BPSC_TRE_CS_PYQ_SERIES. QuantaEdge generates the public ID automatically.');
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

  function finishPackageEdit(
    index: number,
    mutate: (pkg: Record<string, any>) => void,
    options: { openEditor?: boolean; openQuestionEditor?: boolean } = {}
  ) {
    setPackages(current => current.map((entry, currentIndex) => {
      if (currentIndex !== index) return entry;
      const pkg = clonePackage(entry.packageJson);
      mutate(pkg);
      return {
        ...entry,
        packageJson: pkg,
        validation: undefined,
        validatedSignature: undefined,
        importResult: undefined,
        editorOpen: entry.editorOpen || Boolean(options.openEditor),
        questionEditorOpen: entry.questionEditorOpen || Boolean(options.openQuestionEditor)
      };
    }));
    setHasValidation(false); setError(''); setNotice('');
  }

  function updatePackageField(index: number, area: 'root' | 'source' | 'configuration', key: string, value: string) {
    finishPackageEdit(index, pkg => {
      if (area === 'root') {
        pkg[key] = value;
      } else {
        const target = area === 'source' ? sourceOf(pkg) : configurationOf(pkg);
        const rootKey = area === 'source' ? 'source' : 'configuration';
        const parsed = area === 'configuration' && ['duration_minutes', 'max_attempts'].includes(key)
          ? (value.trim() ? Number(value) : value)
          : area === 'configuration' && ['marks_per_question', 'negative_marking_fraction', 'unanswered_mark'].includes(key)
            ? (value.trim() ? Number(value) : value)
            : value;
        pkg[rootKey] = { ...target, [key]: parsed };
      }
    });
  }

  function currentSections(pkg: Record<string, any>): Array<Record<string, any>> {
    return Array.isArray(pkg.sections) ? pkg.sections.filter((value: unknown) => value && typeof value === 'object') : [];
  }
  function currentQuestions(pkg: Record<string, any>): Array<Record<string, any>> {
    return Array.isArray(pkg.questions) ? pkg.questions.filter((value: unknown) => value && typeof value === 'object') : [];
  }
  function recountQuestionCounts(pkg: Record<string, any>) {
    const sections = currentSections(pkg);
    const questions = currentQuestions(pkg);
    pkg.sections = sections.map(section => ({
      ...section,
      question_count: questions.filter(question => String(question.section_code || '') === String(section.code || '')).length
    }));
  }

  function addSection(index: number) {
    finishPackageEdit(index, pkg => {
      const sections = currentSections(pkg);
      if (sections.length >= 20) return;
      const questions = currentQuestions(pkg);
      const firstQuestionCode = String(questions[0]?.section_code || '');
      const defaultCode = sections.length === 0 && /^[A-Z][A-Z0-9_]{1,49}$/.test(firstQuestionCode)
        ? firstQuestionCode
        : sections.length === 0 ? 'COMPUTER_SCIENCE' : 'SECTION_' + (sections.length + 1);
      const section = {
        code: defaultCode,
        name_i18n: { en: defaultCode.replaceAll('_', ' '), hi: '' },
        question_count: 0,
        result_policy: 'SCORED'
      };
      pkg.sections = [...sections, section];
      pkg.questions = questions.map(question => ({
        ...question,
        section_result_policy: question.section_code === defaultCode ? 'SCORED' : question.section_result_policy
      }));
      recountQuestionCounts(pkg);
    }, { openEditor: true });
  }

  function updateSectionField(index: number, sectionIndex: number, field: 'code' | 'result_policy' | 'name_en' | 'name_hi', value: string) {
    finishPackageEdit(index, pkg => {
      const sections = currentSections(pkg);
      const previous = sections[sectionIndex];
      if (!previous) return;
      const oldCode = String(previous.code || '');
      const nextCode = field === 'code' ? value.toUpperCase().replace(/[^A-Z0-9_]/g, '_') : oldCode;
      pkg.sections = sections.map((section, i) => {
        if (i !== sectionIndex) return section;
        if (field === 'name_en' || field === 'name_hi') {
          return { ...section, name_i18n: { ...(section.name_i18n || {}), [field === 'name_en' ? 'en' : 'hi']: value } };
        }
        if (field === 'result_policy') return { ...section, result_policy: value };
        return { ...section, code: nextCode };
      });
      if (field === 'code' && nextCode !== oldCode) {
        pkg.questions = currentQuestions(pkg).map(question =>
          question.section_code === oldCode
            ? { ...question, section_code: nextCode, section_result_policy: String(previous.result_policy || 'SCORED') }
            : question
        );
      }
      if (field === 'result_policy') {
        pkg.questions = currentQuestions(pkg).map(question =>
          question.section_code === oldCode ? { ...question, section_result_policy: value } : question
        );
      }
      recountQuestionCounts(pkg);
    }, { openEditor: true });
  }

  function addQuestion(index: number) {
    finishPackageEdit(index, pkg => {
      let sections = currentSections(pkg);
      if (!sections.length) {
        sections = [{
          code: 'COMPUTER_SCIENCE',
          name_i18n: { en: 'Computer Science', hi: 'कंप्यूटर विज्ञान' },
          question_count: 0,
          result_policy: 'SCORED'
        }];
        pkg.sections = sections;
      }
      const questions = currentQuestions(pkg);
      if (questions.length >= 250) return;
      const section = sections[0];
      let ordinal = questions.length + 1;
      while (questions.some(question => String(question.id || '') === 'question-' + ordinal)) ordinal++;
      pkg.questions = [...questions, {
        id: 'question-' + ordinal,
        source_question_number: 'Q-' + ordinal,
        question_type: 'MCQ',
        prompt: '',
        prompt_i18n: { en: '', hi: '' },
        options: [
          { key: 'A', text: '', text_i18n: { en: '', hi: '' } },
          { key: 'B', text: '', text_i18n: { en: '', hi: '' } }
        ],
        topic_tags: ['Unclassified'],
        difficulty: 'CORE',
        proposed_answer: {
          option_key: null,
          confidence: 'UNRESOLVED',
          explanation: 'Pending human review.',
          answer_source: 'UNRESOLVED',
          review_status: 'PENDING_HUMAN_REVIEW',
          explanation_i18n: { en: 'Pending human review.' },
          solution_mode: 'CONCISE'
        },
        section_code: section.code,
        section_result_policy: section.result_policy || 'SCORED',
        marks: 1
      }];
      recountQuestionCounts(pkg);
    }, { openEditor: true, openQuestionEditor: true });
  }

  function updateQuestionField(index: number, questionIndex: number, field: string, value: string) {
    finishPackageEdit(index, pkg => {
      const questions = currentQuestions(pkg);
      const question = questions[questionIndex];
      if (!question) return;
      const next = { ...question };
      if (field === 'prompt_en' || field === 'prompt_hi') {
        next.prompt_i18n = { ...(next.prompt_i18n || {}), [field === 'prompt_en' ? 'en' : 'hi']: value };
        next.prompt = String(next.prompt_i18n.en || next.prompt_i18n.hi || '');
      } else if (field === 'explanation') {
        next.proposed_answer = { ...(next.proposed_answer || {}), explanation: value };
      } else if (field === 'section_code') {
        const section = currentSections(pkg).find(item => item.code === value);
        next.section_code = value;
        next.section_result_policy = section?.result_policy || 'SCORED';
      } else if (field === 'marks') {
        next.marks = value.trim() ? Number(value) : value;
      } else {
        (next as Record<string, any>)[field] = value;
      }
      pkg.questions = questions.map((item, i) => i === questionIndex ? next : item);
      recountQuestionCounts(pkg);
    }, { openEditor: true, openQuestionEditor: true });
  }

  function updateQuestionOption(index: number, questionIndex: number, optionIndex: number, field: 'en' | 'hi', value: string) {
    finishPackageEdit(index, pkg => {
      const questions = currentQuestions(pkg);
      const question = questions[questionIndex];
      if (!question) return;
      const options = Array.isArray(question.options) ? question.options : [];
      const updatedOptions = options.map((option: Record<string, any>, i: number) => {
        if (i !== optionIndex) return option;
        return {
          ...option,
          ...(field === 'en' ? { text: value } : {}),
          text_i18n: { ...(option.text_i18n || {}), [field]: value }
        };
      });
      pkg.questions = questions.map((item, i) => i === questionIndex ? { ...item, options: updatedOptions } : item);
    }, { openEditor: true, openQuestionEditor: true });
  }

  function addQuestionOption(index: number, questionIndex: number) {
    finishPackageEdit(index, pkg => {
      const questions = currentQuestions(pkg);
      const question = questions[questionIndex];
      if (!question) return;
      const options = Array.isArray(question.options) ? question.options : [];
      if (options.length >= 5) return;
      const key = String.fromCharCode(65 + options.length);
      const updated = [...options, { key, text: '', text_i18n: { en: '', hi: '' } }];
      pkg.questions = questions.map((item, i) => i === questionIndex ? { ...item, options: updated } : item);
    }, { openEditor: true, openQuestionEditor: true });
  }

  function removeQuestionOption(index: number, questionIndex: number, optionIndex: number) {
    finishPackageEdit(index, pkg => {
      const questions = currentQuestions(pkg);
      const question = questions[questionIndex];
      if (!question) return;
      const options = Array.isArray(question.options) ? question.options : [];
      if (options.length <= 2) return;
      const updated = options.filter((_: unknown, i: number) => i !== optionIndex)
        .map((option: Record<string, any>, i: number) => ({ ...option, key: String.fromCharCode(65 + i) }));
      pkg.questions = questions.map((item, i) => i === questionIndex ? { ...item, options: updated } : item);
    }, { openEditor: true, openQuestionEditor: true });
  }

  function removeQuestion(index: number, questionIndex: number) {
    finishPackageEdit(index, pkg => {
      pkg.questions = currentQuestions(pkg).filter((_, i) => i !== questionIndex);
      recountQuestionCounts(pkg);
    }, { openEditor: true, openQuestionEditor: true });
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
    !Array.isArray(entry.packageJson.sections) || !entry.packageJson.sections.length || !Array.isArray(entry.packageJson.questions) || !entry.packageJson.questions.length;

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
            <p>Public course ID: <code>{course.publicId}</code> · Stable key: <code>{courseKey.toUpperCase()}</code></p>
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
                      {((!Array.isArray(pkg.sections) || !pkg.sections.length || !Array.isArray(pkg.questions) || !pkg.questions.length) || entry.editorOpen) && <section style={{ marginTop: 16, padding: 14, border: '1px solid var(--admin-border, #d8dce5)', borderRadius: 10 }}>
                        <h4>{missingQuestionContent(entry) ? 'Complete missing sections or questions' : 'Section and question editor'}</h4>
                        {!entry.questionEditorOpen && currentQuestions(pkg).length > 0 && <button className="button button-dark" type="button" onClick={() => setPackages(current => current.map((row, rowIndex) => rowIndex === index ? { ...row, editorOpen: true, questionEditorOpen: true, validation: undefined, validatedSignature: undefined } : row))}>Edit existing question rows</button>}
                        <p>Enter verified source content below. QuantaEdge will not guess a question, answer, translation, or exam rule. Every newly entered question remains pending human review.</p>
                        {currentSections(pkg).map((section, sectionIndex) => <div key={'section-' + sectionIndex} style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,180px),1fr))', gap: 10, margin: '10px 0', padding: 10, border: '1px solid var(--admin-border, #d8dce5)', borderRadius: 8 }}>
                          <div><label htmlFor={'sec-code-' + index + '-' + sectionIndex}>Section code</label><input id={'sec-code-' + index + '-' + sectionIndex} value={String(section.code || '')} onChange={event => updateSectionField(index, sectionIndex, 'code', event.target.value)} placeholder="COMPUTER_SCIENCE" /></div>
                          <div><label htmlFor={'sec-en-' + index + '-' + sectionIndex}>Section name (English)</label><input id={'sec-en-' + index + '-' + sectionIndex} value={String(section.name_i18n?.en || '')} onChange={event => updateSectionField(index, sectionIndex, 'name_en', event.target.value)} placeholder="Computer Science" /></div>
                          <div><label htmlFor={'sec-hi-' + index + '-' + sectionIndex}>Section name (Hindi, optional)</label><input id={'sec-hi-' + index + '-' + sectionIndex} value={String(section.name_i18n?.hi || '')} onChange={event => updateSectionField(index, sectionIndex, 'name_hi', event.target.value)} placeholder="कंप्यूटर विज्ञान" /></div>
                          <div><label htmlFor={'sec-policy-' + index + '-' + sectionIndex}>Scoring policy</label><select id={'sec-policy-' + index + '-' + sectionIndex} value={String(section.result_policy || 'SCORED')} onChange={event => updateSectionField(index, sectionIndex, 'result_policy', event.target.value)}><option value="SCORED">Scored</option><option value="QUALIFYING_ONLY">Qualifying only</option><option value="PRACTICE_ONLY">Practice only</option></select></div>
                          <p style={{ gridColumn: '1 / -1', margin: 0 }}>Questions assigned to this section: {Number(section.question_count || 0)}</p>
                        </div>)}
                        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', margin: '10px 0' }}>
                          <button className="button button-dark" type="button" disabled={currentSections(pkg).length >= 20} onClick={() => addSection(index)}>Add section</button>
                          <button className="button button-dark" type="button" disabled={currentQuestions(pkg).length >= 250} onClick={() => addQuestion(index)}>Add question</button>
                        </div>
                        {(entry.questionEditorOpen || currentQuestions(pkg).length === 0) && currentQuestions(pkg).map((question, questionIndex) => <div key={String(question.id || questionIndex) + '-' + questionIndex} style={{ marginTop: 12, padding: 12, border: '1px solid var(--admin-border, #d8dce5)', borderRadius: 8 }}>
                          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
                            <h4 style={{ margin: 0 }}>Question {questionIndex + 1}</h4>
                            <button className="button button-dark" type="button" onClick={() => removeQuestion(index, questionIndex)}>Remove question</button>
                          </div>
                          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit,minmax(min(100%,220px),1fr))', gap: 10, marginTop: 10 }}>
                            <div><label htmlFor={'q-id-' + index + '-' + questionIndex}>Stable question ID</label><input id={'q-id-' + index + '-' + questionIndex} value={String(question.id || '')} onChange={event => updateQuestionField(index, questionIndex, 'id', event.target.value.toLowerCase().replace(/[^a-z0-9._-]/g, '-'))} placeholder="question-1" /></div>
                            <div><label htmlFor={'q-source-number-' + index + '-' + questionIndex}>Source question number</label><input id={'q-source-number-' + index + '-' + questionIndex} value={String(question.source_question_number || '')} onChange={event => updateQuestionField(index, questionIndex, 'source_question_number', event.target.value)} placeholder="Q-1" /></div>
                            <div><label htmlFor={'q-type-' + index + '-' + questionIndex}>Question type</label><select id={'q-type-' + index + '-' + questionIndex} value={String(question.question_type || 'MCQ')} onChange={event => updateQuestionField(index, questionIndex, 'question_type', event.target.value)}>{['MCQ','TRUE_FALSE','INFERENCE','INPUT','NUMERICAL','SHORT_ANSWER','LONG_ANSWER','CASE_BASED','SOURCE_BASED','DIAGRAM','MAP','ASSERTION_REASON','MATCH','ORDER'].map(type => <option value={type} key={type}>{type.replaceAll('_', ' ')}</option>)}</select></div>
                            <div><label htmlFor={'q-section-' + index + '-' + questionIndex}>Section</label><select id={'q-section-' + index + '-' + questionIndex} value={String(question.section_code || '')} onChange={event => updateQuestionField(index, questionIndex, 'section_code', event.target.value)}>{currentSections(pkg).map(section => <option value={String(section.code)} key={String(section.code)}>{String(section.name_i18n?.en || section.code)}</option>)}</select></div>
                            <div><label htmlFor={'q-marks-' + index + '-' + questionIndex}>Marks</label><input id={'q-marks-' + index + '-' + questionIndex} type="number" min={0} max={1000} step="any" value={String(question.marks ?? 1)} onChange={event => updateQuestionField(index, questionIndex, 'marks', event.target.value)} /></div>
                            <div style={{ gridColumn: '1 / -1' }}><label htmlFor={'q-prompt-en-' + index + '-' + questionIndex}>Question text (English, if present)</label><textarea id={'q-prompt-en-' + index + '-' + questionIndex} value={String(question.prompt_i18n?.en || '')} onChange={event => updateQuestionField(index, questionIndex, 'prompt_en', event.target.value)} rows={3} placeholder="Enter the verified question exactly as it appears in the source." /></div>
                            <div style={{ gridColumn: '1 / -1' }}><label htmlFor={'q-prompt-hi-' + index + '-' + questionIndex}>Question text (Hindi, if present)</label><textarea id={'q-prompt-hi-' + index + '-' + questionIndex} value={String(question.prompt_i18n?.hi || '')} onChange={event => updateQuestionField(index, questionIndex, 'prompt_hi', event.target.value)} rows={3} placeholder="स्रोत में उपलब्ध हिंदी प्रश्न यहाँ लिखें।" /></div>
                          </div>
                          <h5 style={{ marginBottom: 8 }}>Options (for MCQ / true-false)</h5>
                          {(Array.isArray(question.options) ? question.options : []).map((option: Record<string, any>, optionIndex: number) => <div key={String(option.key || optionIndex)} style={{ display: 'grid', gridTemplateColumns: '32px repeat(2,minmax(0,1fr)) 82px', gap: 8, marginBottom: 8 }}>
                            <label>{String(option.key || String.fromCharCode(65 + optionIndex))}</label>
                            <input aria-label={'Option ' + String(option.key) + ' English text'} value={String(option.text_i18n?.en || option.text || '')} onChange={event => updateQuestionOption(index, questionIndex, optionIndex, 'en', event.target.value)} placeholder="Option text (English)" />
                            <input aria-label={'Option ' + String(option.key) + ' Hindi text'} value={String(option.text_i18n?.hi || '')} onChange={event => updateQuestionOption(index, questionIndex, optionIndex, 'hi', event.target.value)} placeholder="विकल्प (हिंदी)" />
                            <button className="button button-dark" type="button" disabled={(question.options || []).length <= 2} onClick={() => removeQuestionOption(index, questionIndex, optionIndex)}>Remove</button>
                          </div>)}
                          <button className="button button-dark" type="button" disabled={(question.options || []).length >= 5} onClick={() => addQuestionOption(index, questionIndex)}>Add option (up to 5)</button>
                          <div><label htmlFor={'q-explanation-' + index + '-' + questionIndex}>Proposed explanation (review required)</label><textarea id={'q-explanation-' + index + '-' + questionIndex} value={String(question.proposed_answer?.explanation || '')} onChange={event => updateQuestionField(index, questionIndex, 'explanation', event.target.value)} rows={2} placeholder="Add a concise explanation from a verified source; a reviewer must approve it." /></div>
                          <p>Answer key remains unresolved unless supplied in the package. Reviewers must approve the correct answer and explanation before publishing.</p>
                        </div>)}
                      </section>}
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
            <p><strong>Safety:</strong> validation writes no assessment content. Import writes draft packages and assessments only. Student delivery remains disabled until answer review, test publication, course-release review, HRMS approval and Marketplace publication are completed through the existing workflows.</p>
          </section>
        )}
      </section>
    </main>
  );
}
