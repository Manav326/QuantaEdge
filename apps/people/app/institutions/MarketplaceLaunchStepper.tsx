'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { apiFetch } from '../components/apiClient';
import styles from './MarketplaceLaunchStepper.module.css';

type LaunchCheck = {
  code: string;
  label: string;
  passed: boolean;
  required: boolean;
  message: string;
};
type LaunchItem = {
  assignmentId: number;
  offerId?: number | null;
  institutionId: number;
  institutionName: string;
  courseId: number;
  courseName: string;
  listingTitle?: string;
  description?: string;
  offerStatus: string;
  marketplaceVisible?: boolean;
  pricePaise?: number | null;
  currency?: string;
  ready: boolean;
  checks: LaunchCheck[];
  blockingIssues: string[];
  message: string;
};
type AvailableRelease = {
  release_id: number;
  course_id: number;
  release_version: number;
  contentCurrent: boolean;
  course_code: string;
  course_name: string;
  source_institution_id: number;
  source_institution_code: string;
  source_institution_name: string;
};
type QualityReviewItem = {
  release_id: number;
  course_id: number;
  release_version: number;
  release_state: string;
  content_fingerprint: string;
  contentCurrent: boolean;
  submitted_at?: string | null;
  course_code: string;
  course_name: string;
  source_institution_id: number;
  source_institution_code: string;
  source_institution_name: string;
  active_subject_count: number;
  subjects_with_published_lessons: number;
  published_chapters: number;
  published_lessons: number;
  course_type_code: string;
  delivery_mode: string;
  assessment_only_ready: boolean;
  assessment_count: number;
  assessment_question_count: number;
};
type ReviewQuestion = { sequence_no: number; max_marks: number; question_snapshot: Record<string, any> };
type ReviewAssessment = {
  assessment_id: number;
  title: string;
  description?: string | null;
  assessment_type?: string | null;
  portable_assessment_type?: string | null;
  source_exam_cycle?: string | null;
  source_package_id?: string | null;
  source_package_version?: string | null;
  source_url?: string | null;
  status: string;
  duration_minutes: number;
  max_attempts: number;
  negative_marking_fraction: number;
  question_count: number;
  max_score: number;
  questionCount?: number;
  questions: ReviewQuestion[];
};
type LessonReviewQuestion = {
  id: number;
  question_type: string;
  prompt: string;
  explanation?: string | null;
  difficulty: string;
  active: boolean;
  options: Array<{ option_key: string; label: string; is_correct: boolean; sort_order: number }>;
  translations: Array<Record<string, any>>;
};
type LessonReviewBlock = {
  id: number;
  sequence_no: number;
  block_type: string;
  content: unknown;
  asset_id?: number | null;
  asset_url?: string | null;
  asset_title?: string | null;
  asset_alt?: string | null;
  asset_type?: string | null;
  asset_status?: string | null;
  translations: Array<Record<string, any>>;
};
type LessonReviewLesson = {
  id: number;
  code: string;
  title: string;
  summary?: string | null;
  estimated_minutes: number;
  status: string;
  sort_order: number;
  active: boolean;
  objective_code?: string | null;
  objective_title?: string | null;
  objective_description?: string | null;
  objectiveTranslations: Array<Record<string, any>>;
  translations: Array<Record<string, any>>;
  blocks: LessonReviewBlock[];
  questions: LessonReviewQuestion[];
};
type LessonReviewResource = {
  assignment_id: number;
  target_kind: string;
  assignment_status: string;
  resource_id: number;
  resource_type: string;
  title: string;
  description?: string | null;
  language: string;
  source_kind: string;
  external_url?: string | null;
  source_reference?: string | null;
  resource_status: string;
  version_no: number;
  content_asset_id?: number | null;
  sha256?: string | null;
  media_type?: string | null;
  file_size_bytes?: number | null;
  duration_seconds?: number | null;
};
type ReleaseReviewChapter = {
  id: number;
  code: string;
  display_name: string;
  description?: string | null;
  content_status: string;
  curriculum_source?: string | null;
  curriculum_source_url?: string | null;
  curriculum_source_edition?: string | null;
  curriculum_source_pages?: string | null;
  curriculum_source_verified: boolean;
  translations: Array<Record<string, any>>;
  lesson_count: number;
  lesson_block_count: number;
  lesson_question_count: number;
  resource_count: number;
  lessons?: LessonReviewLesson[];
  resources?: LessonReviewResource[];
};
type ReleaseReviewSubject = {
  id: number;
  code: string;
  display_name: string;
  translations: Array<Record<string, any>>;
  chapters: ReleaseReviewChapter[];
};
type ReleaseReviewContent = {
  release_id: number;
  course_id: number;
  release_version: number;
  release_state: string;
  course_code: string;
  course_name: string;
  course_type_code: string;
  delivery_mode: string;
  contentCurrent: boolean;
  content_fingerprint: string;
  currentFingerprint: string;
  assessmentOnlyReady: boolean;
  courseTranslations: Array<Record<string, any>>;
  assessmentCount: number;
  questionCount: number;
  lessonCount: number;
  lessonBlockCount: number;
  lessonQuestionCount: number;
  courseResourceCount: number;
  subjects: ReleaseReviewSubject[];
  assessments: ReviewAssessment[];
  readOnly: boolean;
  publicationAllowed: boolean;
};
type PackageRightsReview = {
  import_id: number;
  package_id: string;
  package_version: string;
  course_id: number;
  release_id: number;
  source_institution_id: number;
  source_institution_code: string;
  source_institution_name: string;
  course_name: string;
  source_rights_status: string;
  rights_evidence_url?: string | null;
  rights_reviewed_at?: string | null;
  rights_review_notes?: string | null;
};
type PackageRightsDraft = { status: string; evidenceUrl: string; notes: string };
type LaunchResponse = {
  institutionId: number;
  institutionName: string;
  items: LaunchItem[];
  total: number;
  readyCount: number;
  blockedCount: number;
  availableReleases?: AvailableRelease[];
  availableCount?: number;
  qualityReviewItems?: QualityReviewItem[];
  qualityReviewCount?: number;
  coursePackageImports?: PackageRightsReview[];
  coursePackageImportCount?: number;
};
type Draft = { title: string; description: string; priceRupees: string };

const GROUPS = [
  { key: 'course', number: '01', title: 'Course content, rights & quality', subtitle: 'The course is active, imported source rights are cleared where applicable, mapped subjects have published learning content, and HRMS quality review is complete.', codes: ['INSTITUTION_ACTIVE', 'COURSE_ACTIVE', 'CONTENT_QUALITY_REVIEW', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT', 'SOURCE_RIGHTS_CLEARANCE'] },
  { key: 'release', number: '02', title: 'Origin approval & assignment', subtitle: 'The source institution approves the reviewed release, then HRMS assigns that exact shareable release to this institution.', codes: ['ORIGIN_RELEASE_APPROVAL', 'ASSIGNMENT_AND_RELEASE'] },
  { key: 'offer', number: '03', title: 'Listing details', subtitle: 'Set the student-facing title, description, currency and explicit price.', codes: ['OFFER_TERMS'] },
  { key: 'payment', number: '04', title: 'Payment setup', subtitle: 'Paid courses require working Razorpay credentials and the signed course-payment webhook.', codes: ['RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK'] },
  { key: 'seller', number: '05', title: 'Seller & tax', subtitle: 'Approve the seller and tax treatment, GST registration where required, and e-invoice applicability.', codes: ['APPROVED_TAX_PROFILE', 'EINVOICE_REVIEW', 'TAX_CALCULATION', 'GST_REGISTRATION'] },
  { key: 'invoice', number: '06', title: 'Invoice & revenue share', subtitle: 'Publish an invoice template; confirm effective shares fit the after-tax pool, including approved discounts.', codes: ['PUBLISHED_INVOICE_TEMPLATE', 'REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION', 'DISCOUNT_POOL_COMPATIBILITY'] },
] as const;

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-QE-Workspace', 'HRMS');
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await apiFetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let data: any = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw }; }
  if (!response.ok) throw new Error(String(data.message || data.detail || data.error || 'Request failed (' + response.status + ')'));
  return data as T;
}

function priceText(paise?: number | null) {
  if (paise === null || paise === undefined) return 'Price not set';
  return new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' }).format(paise / 100);
}
function normalizeFingerprint(value: unknown): string {
  return String(value ?? '').trim().toLowerCase();
}
function statusText(status: string) {
  return status.replaceAll('_', ' ').toLowerCase().replace(/\b[a-z]/g, value => value.toUpperCase());
}
function initialDraft(item: LaunchItem): Draft {
  return {
    title: item.listingTitle || item.courseName || '',
    description: item.description || '',
    priceRupees: item.pricePaise === null || item.pricePaise === undefined ? '' : (item.pricePaise / 100).toFixed(2).replace(/\.00$/, ''),
  };
}

function readableLessonBlock(content: unknown): Array<{label:string;text:string}> {
  if (content === null || content === undefined) return [];
  if (typeof content === 'string' || typeof content === 'number' || typeof content === 'boolean') {
    return [{label:'Content',text:String(content)}];
  }
  if (Array.isArray(content)) {
    return content.map((value,index)=>({
      label:'Item '+(index+1),
      text:typeof value==='string'?value:JSON.stringify(value,null,2)
    }));
  }
  if (typeof content !== 'object') return [{label:'Content',text:String(content)}];
  const source=content as Record<string,unknown>;
  const preferred=['title','heading','body','text','prompt','explanation','hint','caption','formula','steps','items','content'];
  const fields:Array<{label:string;text:string}>=[];
  for(const key of preferred) {
    const value=source[key];
    if(value===null||value===undefined||value==='') continue;
    fields.push({
      label:key.replace(/_/g,' ').replace(/\b\w/g,letter=>letter.toUpperCase()),
      text:typeof value==='string'||typeof value==='number'||typeof value==='boolean'
        ?String(value):JSON.stringify(value,null,2)
    });
  }
  return fields.length?fields:[{label:'Structured content',text:JSON.stringify(content,null,2)}];
}

export default function MarketplaceLaunchStepper({
  institutionId,
  institutionName,
}: {
  institutionId: number;
  institutionName: string;
}) {
  const [data, setData] = useState<LaunchResponse | null>(null);
  const [drafts, setDrafts] = useState<Record<number, Draft>>({});
  const [reviewNotes, setReviewNotes] = useState<Record<number, string>>({});
  const [reviewContent, setReviewContent] = useState<Record<number, ReleaseReviewContent>>({});
  const [reviewContentLoadingId, setReviewContentLoadingId] = useState<number | null>(null);
  const [reviewContentErrors, setReviewContentErrors] = useState<Record<number, string>>({});
  const [reviewChapters, setReviewChapters] = useState<Record<number, ReleaseReviewChapter>>({});
  const [expandedReviewChapters, setExpandedReviewChapters] = useState<Record<number, number | null>>({});
  const [reviewChapterLoadingId, setReviewChapterLoadingId] = useState<number | null>(null);
  const [packageRightsDrafts, setPackageRightsDrafts] = useState<Record<number, PackageRightsDraft>>({});
  const [loading, setLoading] = useState(true);
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const result = await api<LaunchResponse>('/api/v1/hrms/platform/institutions/' + institutionId + '/marketplace-launches');
      const items = Array.isArray(result.items) ? result.items : [];
      setData({ ...result, items });
      setReviewNotes(previous => {
        const next = { ...previous };
        for (const release of result.qualityReviewItems || []) {
          if (next[release.release_id] === undefined) next[release.release_id] = '';
        }
        return next;
      });
      setPackageRightsDrafts(previous => {
        const next = { ...previous };
        for (const item of result.coursePackageImports || []) {
          if (next[item.import_id] === undefined) {
            const status = ['CLEARED_FOR_INTERNAL_USE','CLEARED_FOR_PUBLIC_REPOSITORY','DO_NOT_REDISTRIBUTE']
              .includes(item.source_rights_status) ? item.source_rights_status : '';
            next[item.import_id] = {
              status,
              evidenceUrl: item.rights_evidence_url || '',
              notes: item.rights_review_notes || '',
            };
          }
        }
        return next;
      });
      setDrafts(previous => {
        const next: Record<number, Draft> = {};
        for (const item of items) {
          next[item.assignmentId] = previous[item.assignmentId] || initialDraft(item);
        }
        return next;
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Marketplace launch readiness could not be loaded.');
      setData(null);
    } finally {
      setLoading(false);
    }
  }, [institutionId]);

  useEffect(() => { void load(); }, [load]);

  async function savePackageRights(item: PackageRightsReview) {
    const draft = packageRightsDrafts[item.import_id];
    if (!draft || !draft.status) {
      setError('Choose a package-rights decision before saving.');
      return;
    }
    if (draft.notes.trim().length < 5) {
      setError('Add reviewer notes of at least five characters.');
      return;
    }
    if (draft.status !== 'DO_NOT_REDISTRIBUTE' && !/^https:\/\//i.test(draft.evidenceUrl.trim())) {
      setError('A clearance decision requires an HTTPS rights-evidence URL.');
      return;
    }
    setBusyKey('rights-' + item.import_id);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId
        + '/course-package-imports/' + item.import_id + '/rights-review', {
        method: 'POST',
        body: JSON.stringify({
          status: draft.status,
          evidenceUrl: draft.evidenceUrl.trim(),
          notes: draft.notes.trim(),
        }),
      });
      setNotice('Package rights decision saved. All versions of this source-owned course now use this audited rights status.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Package rights review could not be saved.');
    } finally {
      setBusyKey(null);
    }
  }

  const counts = useMemo(() => {
    const items = data?.items || [];
    return {
      total: items.length,
      ready: items.filter(item => item.ready && item.offerStatus === 'APPROVED').length,
      published: items.filter(item => item.marketplaceVisible === true).length,
      publishedStatus: items.filter(item => item.offerStatus === 'PUBLISHED').length,
    };
  }, [data]);

  const updateDraft = (assignmentId: number, patch: Partial<Draft>) => {
    setDrafts(current => ({
      ...current,
      [assignmentId]: { ...(current[assignmentId] || { title: '', description: '', priceRupees: '' }), ...patch },
    }));
  };

  const toggleReviewContent = async (release: QualityReviewItem) => {
    const releaseId = release.release_id;
    if (reviewContent[releaseId]) {
      setReviewContent(previous => {
        const next = { ...previous };
        delete next[releaseId];
        return next;
      });
      return;
    }
    setReviewContentLoadingId(releaseId);
    setReviewContentErrors(previous => ({ ...previous, [releaseId]: '' }));
    try {
      const content = await api<ReleaseReviewContent>(
        '/api/v1/hrms/platform/institutions/' + institutionId + '/course-releases/' + releaseId + '/review-content'
      );
      setReviewContent(previous => ({ ...previous, [releaseId]: content }));
    } catch (e) {
      setReviewContentErrors(previous => ({
        ...previous,
        [releaseId]: e instanceof Error ? e.message : 'The exact submitted release content could not be loaded.'
      }));
    } finally {
      setReviewContentLoadingId(null);
    }
  };

  const toggleReviewChapter = async (release: QualityReviewItem, chapterId: number) => {
    if (expandedReviewChapters[release.release_id] === chapterId) {
      setExpandedReviewChapters(previous => ({ ...previous, [release.release_id]: null }));
      return;
    }
    const cached = reviewChapters[chapterId];
    if (cached) {
      setExpandedReviewChapters(previous => ({ ...previous, [release.release_id]: chapterId }));
      return;
    }
    setReviewChapterLoadingId(chapterId);
    setReviewContentErrors(previous => ({ ...previous, [release.release_id]: '' }));
    try {
      const detail = await api<ReleaseReviewContent>(
        '/api/v1/hrms/platform/institutions/' + institutionId + '/course-releases/'
          + release.release_id + '/review-content?chapterId=' + encodeURIComponent(String(chapterId))
      );
      const chapter = detail.subjects.flatMap(subject => subject.chapters || [])
        .find(item => item.id === chapterId);
      if (!chapter || !Array.isArray(chapter.lessons)) {
        throw new Error('The selected chapter content is not part of this submitted release.');
      }
      setReviewChapters(previous => ({ ...previous, [chapterId]: chapter }));
      setExpandedReviewChapters(previous => ({ ...previous, [release.release_id]: chapterId }));
      const exactReleaseCurrent = detail.contentCurrent === true
        && normalizeFingerprint(detail.content_fingerprint) === normalizeFingerprint(release.content_fingerprint);
      setReviewContent(previous => {
        const existing=previous[release.release_id];
        if(!existing) return previous;
        return {
          ...previous,
          [release.release_id]: {
            ...existing,
            contentCurrent:exactReleaseCurrent,
            currentFingerprint:String(detail.currentFingerprint||'')
          }
        };
      });
      if (!exactReleaseCurrent) {
        setReviewContentErrors(previous => ({
          ...previous,
          [release.release_id]: 'The source graph no longer matches the submitted fingerprint. This content is shown for diagnosis only; do not approve it.'
        }));
      }
    } catch (e) {
      setReviewContentErrors(previous => ({
        ...previous,
        [release.release_id]: e instanceof Error ? e.message : 'The chapter content could not be loaded.'
      }));
    } finally {
      setReviewChapterLoadingId(null);
    }
  };

  const reviewQuality = async (release: QualityReviewItem, approved: boolean) => {
    const notes = (reviewNotes[release.release_id] || '').trim();
    if (notes.length < 3 || notes.length > 1000) {
      setError('Add a review note between 3 and 1,000 characters before recording a quality decision.');
      return;
    }
    const assessmentOnly = release.delivery_mode === 'ASSESSMENT_ONLY' || release.course_type_code === 'ASSESSMENT';
    const preview = reviewContent[release.release_id];
    if (approved && (!preview || !preview.contentCurrent
      || normalizeFingerprint(preview.content_fingerprint) !== normalizeFingerprint(release.content_fingerprint))) {
      setError('Open and inspect the exact submitted release content before approving its quality.');
      return;
    }
    const releaseReady = assessmentOnly
      ? release.assessment_only_ready === true
      : release.active_subject_count > 0
        && release.subjects_with_published_lessons === release.active_subject_count
        && release.published_chapters > 0 && release.published_lessons > 0;
    if (approved && (!release.contentCurrent || !releaseReady)) {
      setError(assessmentOnly
        ? 'Do not approve this frozen release while any assessment or test pack is still Draft. Reject it for correction, then have the source institution review every question/answer, publish all assessments (which publishes complete packs), and submit a fresh release.'
        : 'This release is not ready for quality approval. Refresh the checklist and ensure every active subject has published lessons.');
      return;
    }
    setBusyKey('quality-' + release.release_id);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/course-releases/' + release.release_id + '/quality-review', {
        method: 'POST',
        body: JSON.stringify({
          approved,
          notes,
          ...(approved ? { reviewedContentFingerprint: String(preview?.content_fingerprint ?? '').trim().toLowerCase() } : {}),
        }),
      });
      setNotice(approved
        ? 'Content quality approved. The source institution must still complete its origin approval before the release can be assigned.'
        : 'Release returned for correction. The source institution must update the content and submit a fresh release.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The course release quality decision could not be saved.');
    } finally {
      setBusyKey(null);
    }
  };

  const assignRelease = async (release: AvailableRelease) => {
    if (!release.contentCurrent) {
      setError('This release no longer matches its reviewed content fingerprint. The source institution must submit a fresh release before it can be assigned.');
      return;
    }
    setBusyKey('assign-' + release.release_id);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId + '/course-releases/' + release.release_id + '/assignment', {
        method: 'PUT',
        body: JSON.stringify({ enabled: true }),
      });
      setNotice(release.course_name + ' assigned to this institution. Its launch checklist is now available below.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The approved course release could not be assigned.');
    } finally {
      setBusyKey(null);
    }
  };

  const saveDraft = async (item: LaunchItem) => {
    const draft = drafts[item.assignmentId] || initialDraft(item);
    const raw = draft.priceRupees.trim();
    if (!/^\d+(?:\.\d{1,2})?$/.test(raw)) {
      setError('Set an explicit INR price with up to two decimal places. Use 0 only if the listing is intentionally free.');
      return;
    }
    const pricePaise = Math.round(Number(raw) * 100);
    if (!Number.isSafeInteger(pricePaise) || pricePaise < 0) {
      setError('The course price is outside the supported range.');
      return;
    }
    if (draft.title.trim().length > 200 || draft.description.trim().length > 5000) {
      setError('The listing title must be 200 characters or fewer and the description 5,000 characters or fewer.');
      return;
    }
    setBusyKey('save-' + item.assignmentId);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId + '/marketplace-launches/' + item.assignmentId + '/offer', {
        method: 'POST',
        body: JSON.stringify({
          title: draft.title.trim(),
          description: draft.description.trim(),
          pricePaise,
          currency: 'INR',
        }),
      });
      setNotice(item.offerId ? 'Draft listing saved. Re-run the checklist before resubmitting.' : 'Draft listing created. Continue through the launch checklist.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The course listing draft could not be saved.');
    } finally {
      setBusyKey(null);
    }
  };

  const transition = async (item: LaunchItem, action: 'submit' | 'approve' | 'publish' | 'archive' | 'return-to-draft') => {
    if (!item.offerId) return;
    if (action === 'publish' && !window.confirm('Publish ' + item.courseName + ' to the student Marketplace for ' + institutionName + '?')) return;
    let body: string | undefined;
    if (action === 'return-to-draft') {
      const reason = window.prompt('Why is this offer being returned to draft?', 'Launch checks need correction');
      if (reason === null) return;
      if (reason.trim().length < 3) {
        setError('Enter a reason of at least 3 characters.');
        return;
      }
      body = JSON.stringify({ reason: reason.trim() });
    }
    if (action === 'archive' && !window.confirm('Remove ' + item.courseName + ' from the student Marketplace? Existing purchase history will be retained.')) return;
    setBusyKey(action + '-' + item.assignmentId);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/hrms/platform/institutions/' + institutionId + '/marketplace-launches/' + item.offerId + '/' + action, {
        method: 'POST',
        ...(body ? { body } : {}),
      });
      const messages: Record<string, string> = {
        submit: 'Listing submitted for review.',
        approve: 'Listing approved. It can now be published after the final readiness check.',
        publish: 'Course published to the Marketplace.',
        archive: 'Course listing archived. Existing order and entitlement history is preserved.',
        'return-to-draft': 'Listing returned to draft so the blockers can be corrected.',
      };
      setNotice(messages[action]);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The Marketplace status transition could not be completed.');
      await load();
    } finally {
      setBusyKey(null);
    }
  };

  function groupState(item: LaunchItem, group: typeof GROUPS[number]) {
    const map = new Map(item.checks.map(check => [check.code, check]));
    const free = item.pricePaise === 0;
    if (free && ['payment', 'seller', 'invoice'].includes(group.key)) {
      return { complete: true, optional: true, present: 0, total: 0, checks: [] as LaunchCheck[] };
    }
    const checks = group.codes.map(code => map.get(code)).filter((check): check is LaunchCheck => Boolean(check));
    if (checks.length === 0) return { complete: false, optional: false, present: 0, total: group.codes.length, checks: [] as LaunchCheck[] };
    const present = checks.filter(check => check.passed).length;
    const complete = checks.length === group.codes.length && present === checks.length;
    return { complete, optional: false, present, total: group.codes.length, checks };
  }

  function progress(item: LaunchItem) {
    const groups = GROUPS.map(group => groupState(item, group));
    const required = groups.filter(group => !group.optional);
    const complete = required.filter(group => group.complete).length;
    return { percent: required.length ? Math.round(complete * 100 / required.length) : 100, complete, total: required.length };
  }

  function nextBlocker(item: LaunchItem) {
    return item.checks.find(check => !check.passed);
  }

  type FinanceSetupTarget = 'entities' | 'tax' | 'studio' | 'distribution' | 'discounts';

  function hasFailedCheck(item: LaunchItem, codes: string[]) {
    return item.checks.some(check => !check.passed && codes.includes(check.code));
  }

  function shouldShowSellerSetupLink(item: LaunchItem) {
    return hasFailedCheck(item, ['APPROVED_TAX_PROFILE', 'EINVOICE_REVIEW', 'TAX_CALCULATION', 'GST_REGISTRATION']);
  }

  function sellerSetupTarget(item: LaunchItem): FinanceSetupTarget {
    return hasFailedCheck(item, ['TAX_CALCULATION']) ? 'tax' : 'entities';
  }

  function shouldShowPaymentSetupLink(item: LaunchItem) {
    return typeof item.pricePaise === 'number' && item.pricePaise > 0
      && hasFailedCheck(item, ['RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK']);
  }

  function educationAdminHref(path: string): string | null {
    const configured = process.env.NEXT_PUBLIC_EDUCATION_ADMIN_ORIGIN?.trim();
    if (configured) {
      try {
        const parsed = new URL(configured);
        const local = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1';
        if (parsed.protocol === 'https:' || (local && parsed.protocol === 'http:')) {
          return new URL(path, parsed.origin).toString();
        }
      } catch { /* A malformed Admin origin must not create a broken handoff link. */ }
    }
    if (typeof window !== 'undefined' && ['localhost', '127.0.0.1'].includes(window.location.hostname)) {
      return new URL(path, 'http://localhost:3001').toString();
    }
    // Production must explicitly configure the Education Admin origin. Never fall back to
    // the learner-facing application for an institutional release-approval task.
    return null;
  }

  function shouldShowInvoiceSetupLink(item: LaunchItem) {
    return item.checks.some(check => !check.passed && [
      'PUBLISHED_INVOICE_TEMPLATE', 'REVENUE_SHARE_POLICY',
      'SHARE_POOL_VALIDATION', 'DISCOUNT_POOL_COMPATIBILITY',
    ].includes(check.code));
  }

  function invoiceSetupTarget(item: LaunchItem): FinanceSetupTarget {
    if (item.checks.some(check => check.code === 'PUBLISHED_INVOICE_TEMPLATE' && !check.passed)) return 'studio';
    if (item.checks.some(check => !check.passed && ['REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION'].includes(check.code))) return 'distribution';
    return 'discounts';
  }

  function invoiceSetupLabel(item: LaunchItem) {
    const templateBlocked = item.checks.some(check => check.code === 'PUBLISHED_INVOICE_TEMPLATE' && !check.passed);
    const shareBlocked = item.checks.some(check => !check.passed && ['REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION'].includes(check.code));
    if (templateBlocked && shareBlocked) return 'Configure invoice template and revenue share';
    if (templateBlocked) return 'Configure invoice template';
    if (shareBlocked) return 'Configure revenue-share policy';
    return 'Review approved discounts';
  }

  function financeSetupHref(item: LaunchItem, target: FinanceSetupTarget) {
    const params = new URLSearchParams({
      tab: target,
      setup: 'marketplace',
      institutionId: String(item.institutionId),
      courseName: item.courseName,
    });
    if (item.offerId) params.set('offerId', String(item.offerId));
    return '/finance?' + params.toString();
  }

  function sourceReleaseHref(item: LaunchItem): string | null {
    const params = new URLSearchParams({ view: 'courses', courseId: String(item.courseId) });
    return educationAdminHref('/institution-operations?' + params.toString());
  }

  function blockerHref(item: LaunchItem, check: LaunchCheck): string | null {
    if (check.code === 'SOURCE_RIGHTS_CLEARANCE') return '#marketplace-source-rights';
    if (['RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK'].includes(check.code)) return '/institutions#hrms-integrations-heading';
    if (check.code === 'EINVOICE_REVIEW' && /requires e-invoicing|REQUIRED/i.test(check.message)) return null;
    if (['APPROVED_TAX_PROFILE', 'EINVOICE_REVIEW', 'GST_REGISTRATION'].includes(check.code)) return financeSetupHref(item, 'entities');
    if (check.code === 'TAX_CALCULATION') return financeSetupHref(item, 'tax');
    if (check.code === 'PUBLISHED_INVOICE_TEMPLATE') return financeSetupHref(item, 'studio');
    if (['REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION'].includes(check.code)) return financeSetupHref(item, 'distribution');
    if (check.code === 'DISCOUNT_POOL_COMPATIBILITY') return financeSetupHref(item, 'discounts');
    if (check.code === 'ORIGIN_RELEASE_APPROVAL' || check.code === 'ASSIGNMENT_AND_RELEASE') {
      return sourceReleaseHref(item);
    }
    return null;
  }

  return (
    <section className={styles.workspace} aria-labelledby="marketplace-launch-heading">
      <header className={styles.hero}>
        <div className={styles.heroIcon} aria-hidden="true"><span>↗</span></div>
        <div className={styles.heroCopy}>
          <p className={styles.eyebrow}>INSTITUTION · MARKETPLACE CONTROL</p>
          <h2 id="marketplace-launch-heading">Course launch checklist</h2>
          <p>One readiness path per course. The percentage reflects completed mandatory steps—not just saved fields. Publication is blocked until the server confirms every required check.</p>
        </div>
        <button type="button" className={styles.refresh} onClick={() => void load()} disabled={loading || busyKey !== null}>
          {loading ? 'Refreshing…' : '↻ Refresh'}
        </button>
      </header>

      {(error || notice) && (
        <div className={error ? styles.alertError : styles.alertNotice} role={error ? 'alert' : 'status'}>
          <span>{error || notice}</span>
          <button type="button" onClick={() => { setError(''); setNotice(''); }} aria-label="Dismiss message">×</button>
        </div>
      )}

      <div className={styles.summary}>
        <div className={styles.summaryCopy}>
          <span className={styles.summaryLabel}>Selected institution</span>
          <strong>{institutionName}</strong>
          <span className={styles.summaryHint}>Every listing below is scoped to this institution.</span>
        </div>
        <div className={styles.summaryStats}>
          <div><strong>{loading ? '—' : counts.total}</strong><span>Assigned courses</span></div>
          <div><strong>{loading ? '—' : counts.ready}</strong><span>Ready to publish</span></div>
          <div><strong>{loading ? '—' : counts.published}</strong><span>Currently published to learners</span></div>
        </div>
        {!loading && counts.publishedStatus > counts.published && (
          <p className={styles.summaryHint}>
            {counts.publishedStatus} offers still carry a PUBLISHED status, but {counts.publishedStatus - counts.published} are not currently discoverable by learners. Check the release-quality, origin-approval, current-fingerprint, and newer-version gates below; loading this checklist does not change any publication status.
          </p>
        )}
      </div>

      {!loading && data && (data.coursePackageImports || []).length > 0 && (
        <section className={styles.rightsPanel} id="marketplace-source-rights">
          <header className={styles.availableHeader}>
            <div>
              <span className={styles.summaryLabel}>HRMS source-rights gate</span>
              <h3>Imported packages requiring rights review</h3>
              <p>Record an evidence-backed decision for the source package. Clearance follows the source course identity, including future draft versions; it does not itself approve content quality or publish the course.</p>
            </div>
            <span className={styles.rightsCount}>{(data.coursePackageImports || []).length} packages</span>
          </header>
          <div className={styles.rightsList}>
            {(data.coursePackageImports || []).map(item => {
              const draft = packageRightsDrafts[item.import_id] || {
                status: '', evidenceUrl: item.rights_evidence_url || '', notes: item.rights_review_notes || '',
              };
              const busy = busyKey === 'rights-' + item.import_id;
              return (
                <article className={styles.rightsRow} key={item.import_id}>
                  <div className={styles.availableMark} aria-hidden="true">§</div>
                  <div className={styles.rightsCopy}>
                    <strong>{item.course_name}</strong>
                    <small>Package {item.package_id} · v{item.package_version} · Source: {item.source_institution_name} ({item.source_institution_code})</small>
                    <div className={styles.rightsStatusLine}>
                      <span>Current: {statusText(item.source_rights_status || 'UNCONFIRMED')}</span>
                      {item.rights_reviewed_at ? <span>Reviewed {new Date(item.rights_reviewed_at).toLocaleString('en-IN')}</span> : <span>Not reviewed by HRMS</span>}
                    </div>
                    <div className={styles.rightsFields}>
                      <label className={styles.reviewNote}>Rights decision
                        <select value={draft.status} disabled={busyKey !== null}
                          onChange={event => setPackageRightsDrafts(previous => ({
                            ...previous, [item.import_id]: { ...draft, status: event.target.value },
                          }))}>
                          <option value="">Choose a decision…</option>
                          <option value="CLEARED_FOR_INTERNAL_USE">Cleared for internal use</option>
                          <option value="CLEARED_FOR_PUBLIC_REPOSITORY">Cleared for public repository</option>
                          <option value="DO_NOT_REDISTRIBUTE">Do not redistribute</option>
                        </select>
                      </label>
                      <label className={styles.reviewNote}>Evidence URL {draft.status !== 'DO_NOT_REDISTRIBUTE' ? '(required for clearance)' : '(optional)'}
                        <input type="url" inputMode="url" maxLength={2000} value={draft.evidenceUrl}
                          disabled={busyKey !== null}
                          onChange={event => setPackageRightsDrafts(previous => ({
                            ...previous, [item.import_id]: { ...draft, evidenceUrl: event.target.value },
                          }))}
                          placeholder="https://…"/>
                      </label>
                    </div>
                    <label className={styles.reviewNote}>Reviewer notes (required)
                      <textarea rows={2} maxLength={1000} value={draft.notes} disabled={busyKey !== null}
                        onChange={event => setPackageRightsDrafts(previous => ({
                          ...previous, [item.import_id]: { ...draft, notes: event.target.value },
                        }))}
                        placeholder="Record the rights basis, license scope, and any restrictions."/>
                    </label>
                    <div className={styles.rightsActions}>
                      <button type="button" className={styles.primaryButton} disabled={busyKey !== null
                        || !draft.status || draft.notes.trim().length < 5
                        || (draft.status !== 'DO_NOT_REDISTRIBUTE' && !/^https:\/\//i.test(draft.evidenceUrl.trim()))}
                        onClick={() => void savePackageRights(item)}>
                        {busy ? 'Saving rights review…' : 'Save rights decision'}
                      </button>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      )}

      {!loading && data && (data.qualityReviewItems || []).length > 0 && (
        <section className={styles.qualityPanel}>
          <header className={styles.availableHeader}>
            <div><span className={styles.summaryLabel}>HRMS content gate</span><h3>Courses waiting for quality review</h3>
              <p>Review the source institution’s submitted release. This is separate from origin approval and Marketplace publication.</p></div>
            <span className={styles.qualityCount}>{data.qualityReviewItems?.length || 0} awaiting review</span>
          </header>
          <div className={styles.qualityList}>
            {(data.qualityReviewItems || []).map(release => {
              const assessmentOnly = release.delivery_mode === 'ASSESSMENT_ONLY' || release.course_type_code === 'ASSESSMENT';
              const qualityReady = release.contentCurrent && (assessmentOnly
                ? release.assessment_only_ready === true
                : release.active_subject_count > 0
                  && release.subjects_with_published_lessons === release.active_subject_count
                  && release.published_chapters > 0 && release.published_lessons > 0);
              const preview = reviewContent[release.release_id];
              const reviewNoteLength = (reviewNotes[release.release_id] || '').trim().length;
              const previewFingerprint = normalizeFingerprint(preview?.content_fingerprint);
              const submittedFingerprint = normalizeFingerprint(release.content_fingerprint);
              const exactPreviewReady = Boolean(preview && preview.contentCurrent
                && previewFingerprint && previewFingerprint === submittedFingerprint);
              const previewFingerprintMismatch = Boolean(preview
                && (!previewFingerprint || !submittedFingerprint || previewFingerprint !== submittedFingerprint));
              const previewLoadInProgress = reviewContentLoadingId !== null;
              // Always allow read-only inspection of the submitted snapshot, even when
              // quality/readiness or fingerprint gates fail. Only approval stays gated.
              const previewNeedsLoading = !preview && !previewLoadInProgress
                && reviewChapterLoadingId === null && busyKey === null;
              const qualityBlocker = !release.contentCurrent
                ? 'Approval is blocked because this release is not fingerprint-current. Return it for correction, have the source institution correct the content and submit a fresh release.'
                : !qualityReady
                  ? assessmentOnly
                    ? 'Approval is blocked because one or more assessments or test packs are still drafts or their question/answer review is incomplete. Return this release for correction, finish human review and publish every test/pack, then submit a fresh release.'
                    : 'Approval is blocked until every active mapped subject has published chapter and lesson content.'
                  : busyKey !== null
                    ? 'Another HRMS action is in progress. Wait for it to finish, then retry.'
                    : reviewChapterLoadingId !== null
                      ? 'Wait for the selected chapter review to finish loading.'
                      : previewLoadInProgress
                        ? reviewContentLoadingId === release.release_id
                          ? 'The exact submitted release is loading. Keep this panel open and wait for the preview to finish.'
                          : 'Another release preview is loading. Wait for it to finish before starting another preview.'
                        : !preview
                          ? 'Approval stays locked until you load and inspect the exact submitted release snapshot. Loading it does not approve or publish the course.'
                          : !preview.contentCurrent
                            ? 'The loaded release is not fingerprint-current. Do not approve it; return it for correction, have the source institution submit a fresh release, and review that new submission.'
                            : previewFingerprintMismatch
                              ? 'The loaded preview fingerprint differs from this review-queue entry. Do not approve. Refresh the review queue; if the mismatch persists, return the release for correction and submit a fresh release.'
                              : reviewNoteLength < 3
                                ? 'Enter a quality review note of at least 3 characters before approving.'
                                : '';
              return (
                <article className={styles.qualityRow} key={release.release_id}>
                  <div className={styles.availableMark} aria-hidden="true">✓</div>
                  <div className={styles.qualityCopy}>
                    <strong>{release.course_name} <span>v{release.release_version}</span></strong>
                    <small>Source: {release.source_institution_name} · {release.source_institution_code} · Release #{release.release_id}</small>
                    <div className={styles.qualityMetrics}>
                      {assessmentOnly ? <>
                        <span>ASSESSMENT_ONLY</span>
                        <span>{release.assessment_count} tests</span>
                        <span>{release.assessment_question_count} questions</span>
                        <span>{release.assessment_only_ready ? 'Assessment hierarchy complete' : 'Assessment hierarchy incomplete'}</span>
                      </> : <>
                        <span>{release.active_subject_count} active subjects</span>
                        <span>{release.subjects_with_published_lessons} with lessons</span>
                        <span>{release.published_chapters} chapters</span>
                        <span>{release.published_lessons} lessons</span>
                      </>}
                    </div>
                    {assessmentOnly && <p>Review the exact submitted test papers, question snapshots, source provenance, answer-review state and scoring rules below. This preview is read-only.</p>}
                    {!release.contentCurrent && <p className={styles.availableWarning}>Course content changed after submission. Ask the source institution to submit a fresh release.</p>}
                    <div className={styles.qualityActions}>
                      <button type="button" className={styles.secondaryButton}
                        disabled={reviewContentLoadingId !== null || reviewChapterLoadingId !== null || busyKey !== null}
                        onClick={() => void toggleReviewContent(release)}>
                        {reviewContentLoadingId === release.release_id ? 'Loading exact release…' : preview ? 'Hide submitted content' : 'Review submitted content'}
                      </button>
                    </div>
                    {reviewContentErrors[release.release_id] && <p className={styles.availableWarning}>{reviewContentErrors[release.release_id]}</p>}
                    {preview && (
                      <div style={{ marginTop: 14, padding: 14, border: '1px solid var(--border, #d7dce2)', borderRadius: 12 }}>
                        <div style={{ display: 'flex', gap: 10, flexWrap: 'wrap', justifyContent: 'space-between', marginBottom: 12 }}>
                          <strong>Exact release preview · read-only</strong>
                          <span style={{ fontSize: 12, color: 'var(--muted, #667085)' }}>
                            {preview.contentCurrent ? 'Fingerprint matches submission' : 'Fingerprint mismatch — do not approve'}
                          </span>
                        </div>
                        <p style={{ overflowWrap: 'anywhere', fontSize: 12 }}>Submitted fingerprint: {preview.content_fingerprint}</p>
                        {preview.delivery_mode === 'ASSESSMENT_ONLY' ? (
                          <>
                            <p>{preview.assessmentCount} assessments · {preview.questionCount} frozen question snapshots · student publication remains disabled during this review</p>
                            {(preview.assessments || []).map(assessment => (
                              <details key={assessment.assessment_id} style={{ borderTop: '1px solid var(--border, #d7dce2)', padding: '10px 0' }}>
                                <summary style={{ cursor: 'pointer', fontWeight: 600 }}>
                                  {assessment.title} · {assessment.questionCount ?? assessment.question_count} questions · {assessment.status}
                                </summary>
                                <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', margin: '8px 0' }}>
                                  <span>{assessment.source_exam_cycle || assessment.portable_assessment_type || assessment.assessment_type}</span>
                                  <span>{assessment.duration_minutes} min</span>
                                  <span>{assessment.max_attempts} attempt(s)</span>
                                  <span>Negative marking: {assessment.negative_marking_fraction}</span>
                                  <span>Max score: {assessment.max_score}</span>
                                </div>
                                {assessment.source_url && <p style={{ overflowWrap: 'anywhere', fontSize: 12 }}>Source: {assessment.source_url}</p>}
                                {(assessment.questions || []).map(question => {
                                  const snapshot = question.question_snapshot || {};
                                  const promptI18n = snapshot.prompt_i18n || {};
                                  const options = Array.isArray(snapshot.options) ? snapshot.options : [];
                                  const answer = snapshot.answer_payload || {};
                                  const tags = Array.isArray(snapshot.topic_tags) ? snapshot.topic_tags : [];
                                  return (
                                    <div key={question.sequence_no} style={{ padding: 10, marginTop: 8, border: '1px solid var(--border, #d7dce2)', borderRadius: 8 }}>
                                      <strong>Q{question.sequence_no} · {snapshot.question_type || 'Question'} · {question.max_marks} mark(s)</strong>
                                      {snapshot.prompt && <p style={{ whiteSpace: 'pre-wrap' }}>{snapshot.prompt}</p>}
                                      {promptI18n.en && promptI18n.en !== snapshot.prompt && <p style={{ whiteSpace: 'pre-wrap' }}><strong>EN:</strong> {promptI18n.en}</p>}
                                      {promptI18n.hi && <p style={{ whiteSpace: 'pre-wrap' }}><strong>HI:</strong> {promptI18n.hi}</p>}
                                      {options.length > 0 && <ol type="A">{options.map((option: any, optionIndex: number) => (
                                        <li key={option.key || option.option_key || optionIndex} style={{ marginBottom: 4 }}>
                                          {option.text || option.label || option.text_i18n?.en || option.text_i18n?.hi || ''}
                                          {option.text_i18n?.hi && option.text_i18n?.en && <span> / {option.text_i18n.hi}</span>}
                                        </li>
                                      ))}</ol>}
                                      <p style={{ fontSize: 12, color: 'var(--muted, #667085)' }}>
                                        Section: {snapshot.section_code || '—'} · Topic tags: {tags.join(', ') || 'not tagged'} · Difficulty: {snapshot.difficulty || 'CORE'}
                                      </p>
                                      <p style={{ fontSize: 12 }}>
                                        Answer review: {snapshot.question_review_status || 'PENDING_HUMAN_REVIEW'} · Answer key: {answer.kind || 'UNRESOLVED'} {answer.value === null || answer.value === undefined ? '(unresolved)' : String(answer.value)}
                                      </p>
                                      {snapshot.explanation && <p style={{ whiteSpace: 'pre-wrap' }}><strong>Explanation:</strong> {snapshot.explanation}</p>}
                                    </div>
                                  );
                                })}
                              </details>
                            ))}
                          </>
                        ) : (
                          <div>
                            <p>{preview.lessonCount} lessons · {preview.lessonBlockCount} teaching blocks · {preview.lessonQuestionCount} lesson questions · {preview.courseResourceCount} resources. Expand a chapter to load its lesson graph and source assets on demand.</p>
                            {(preview.courseTranslations || []).length > 0 && (
                              <div style={{margin:'10px 0',padding:10,border:'1px solid var(--border, #d7dce2)',borderRadius:8}}>
                                <strong>Course translations</strong>
                                {preview.courseTranslations.map((translation,ti)=>(
                                  <p key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti} style={{fontSize:12,margin:'5px 0'}}>
                                    {String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}: {translation.display_name}
                                  </p>
                                ))}
                              </div>
                            )}
                            {(preview.subjects || []).map(subject => (
                              <section key={subject.id} style={{ marginBottom: 16, borderTop: '1px solid var(--border, #d7dce2)', paddingTop: 12 }}>
                                <h4 style={{ margin: '0 0 8px' }}>{subject.display_name} <span style={{ fontSize: 12, fontWeight: 400 }}>({subject.code})</span></h4>
                                {(subject.translations || []).length > 0 && (
                                  <div style={{fontSize:12,margin:'4px 0 8px',color:'var(--muted, #667085)'}}>
                                    {(subject.translations || []).map((translation,ti)=>(
                                      <p key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti} style={{margin:'3px 0'}}>
                                        {String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}: {translation.display_name}
                                      </p>
                                    ))}
                                  </div>
                                )}
                                {(subject.chapters || []).map(chapter => {
                                  const chapterContent = reviewChapters[chapter.id];
                                  const expanded = expandedReviewChapters[release.release_id] === chapter.id;
                                  return (
                                    <div key={chapter.id} style={{ margin: '8px 0', padding: 12, border: '1px solid var(--border, #d7dce2)', borderRadius: 8 }}>
                                      <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', justifyContent: 'space-between', flexWrap: 'wrap' }}>
                                        <div>
                                          <strong>{chapter.display_name}</strong> <span style={{ fontSize: 12, color: 'var(--muted, #667085)' }}>· {chapter.content_status} · {chapter.lesson_count} lessons · {chapter.lesson_block_count} blocks · {chapter.lesson_question_count} questions · {chapter.resource_count} resources</span>
                                          {(chapter.translations || []).length > 0 && (
                                            <p style={{margin:'4px 0',fontSize:12,color:'var(--muted, #667085)'}}>
                                              {(chapter.translations || []).map((translation,ti)=>(
                                                <span key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti}>
                                                  {ti>0?' · ':''}{String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}: {translation.display_name}
                                                </span>
                                              ))}
                                            </p>
                                          )}
                                          <p style={{ margin: '4px 0', fontSize: 12, color: 'var(--muted, #667085)' }}>
                                            Source: {chapter.curriculum_source || 'not recorded'} · Verified: {chapter.curriculum_source_verified ? 'yes' : 'no'}
                                            {chapter.curriculum_source_pages ? ' · Pages: ' + chapter.curriculum_source_pages : ''}
                                          </p>
                                          {chapter.curriculum_source_url && <a href={chapter.curriculum_source_url} target="_blank" rel="noreferrer" style={{ fontSize: 12, overflowWrap: 'anywhere' }}>Open chapter source reference</a>}
                                        </div>
                                        <button type="button" className={styles.secondaryButton}
                                          disabled={reviewChapterLoadingId !== null || busyKey !== null}
                                          aria-expanded={expanded}
                                          onClick={() => void toggleReviewChapter(release, chapter.id)}>
                                          {reviewChapterLoadingId === chapter.id ? 'Loading chapter…' : expanded ? 'Hide chapter content' : chapterContent ? 'Show chapter content' : 'Review chapter content'}
                                        </button>
                                      </div>
                                      {expanded && chapterContent && (
                                        <div style={{ marginTop: 12 }}>
                                          {(chapterContent.lessons || []).length === 0 && <p>No lesson rows are attached to this chapter.</p>}
                                          {(chapterContent.lessons || []).map(lesson => (
                                            <details key={lesson.id} style={{ borderTop: '1px solid var(--border, #d7dce2)', padding: '10px 0' }}>
                                              <summary style={{ cursor: 'pointer', fontWeight: 600 }}>
                                                {lesson.sort_order}. {lesson.title} · {lesson.status} · {(lesson.blocks || []).length} blocks · {(lesson.questions || []).length} questions
                                              </summary>
                                              <div style={{ marginTop: 8 }}>
                                                <p style={{ whiteSpace: 'pre-wrap' }}>{lesson.summary || 'No lesson summary'}</p>
                                                <p style={{ fontSize: 12, color: 'var(--muted, #667085)' }}>
                                                  Code: {lesson.code} · Estimated time: {lesson.estimated_minutes} min · Active: {lesson.active ? 'yes' : 'no'}
                                                  {lesson.objective_title ? ' · Objective: ' + lesson.objective_title : ''}
                                                </p>
                                                {(lesson.objectiveTranslations || []).length > 0 && (
                                                  <div style={{margin:'8px 0',padding:8,border:'1px solid var(--border, #d7dce2)',borderRadius:6}}>
                                                    <strong>Learning objective translations</strong>
                                                    {(lesson.objectiveTranslations || []).map((translation,ti)=>(
                                                      <p key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti} style={{margin:'5px 0',fontSize:12,whiteSpace:'pre-wrap'}}>
                                                        {String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}: {translation.title || ''}{translation.description ? ' — '+translation.description : ''}
                                                      </p>
                                                    ))}
                                                  </div>
                                                )}
                                                {(lesson.translations || []).length > 0 && (
                                                  <div style={{ margin: '8px 0', padding: 8, background: 'var(--surface-muted, #f7f8fa)', borderRadius: 6 }}>
                                                    <strong>Lesson translations</strong>
                                                    {lesson.translations.map((translation, ti) => (
                                                      <p key={String(translation.language) + '-' + String(translation.version_no) + '-' + ti} style={{ margin: '6px 0', fontSize: 12, whiteSpace: 'pre-wrap' }}>
                                                        {String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}: {translation.title || ''}{translation.summary ? ' — ' + translation.summary : ''}
                                                      </p>
                                                    ))}
                                                  </div>
                                                )}
                                                <h5 style={{ margin: '12px 0 6px' }}>Teaching blocks</h5>
                                                {(lesson.blocks || []).map(block => (
                                                  <details key={block.id} style={{ margin: '6px 0', padding: 8, border: '1px solid var(--border, #d7dce2)', borderRadius: 6 }}>
                                                    <summary style={{ cursor: 'pointer' }}>Block {block.sequence_no} · {block.block_type}{block.asset_title ? ' · ' + block.asset_title : ''}</summary>
                                                    <div style={{padding:'8px 10px',borderRadius:6,background:'var(--surface-muted, #f7f8fa)'}}>
                                                      {readableLessonBlock(block.content).map((field,fieldIndex)=>(
                                                        <div key={field.label+'-'+fieldIndex} style={{marginBottom:fieldIndex===readableLessonBlock(block.content).length-1?0:8}}>
                                                          <strong style={{fontSize:12}}>{field.label}</strong>
                                                          <p style={{margin:'3px 0 0',whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontSize:13}}>{field.text}</p>
                                                        </div>
                                                      ))}
                                                    </div>
                                                    {block.asset_url && (
                                                      <div style={{marginTop:8}}>
                                                        {/^https:\/\//i.test(block.asset_url) && ['IMAGE','DIAGRAM'].includes(String(block.asset_type||'').toUpperCase()) && (
                                                          <img src={block.asset_url} alt={block.asset_alt||block.asset_title||'Course visual under review'}
                                                            loading="lazy" referrerPolicy="no-referrer"
                                                            style={{display:'block',maxWidth:'100%',maxHeight:420,objectFit:'contain',border:'1px solid var(--border, #d7dce2)',borderRadius:8,background:'#fff'}} />
                                                        )}
                                                        <p style={{fontSize:12}}>Asset: <a href={block.asset_url} target="_blank" rel="noreferrer">{block.asset_title || block.asset_url}</a> · {block.asset_type || 'file'} · {block.asset_status || 'unknown status'}{block.asset_alt ? ' · Alt: ' + block.asset_alt : ''}</p>
                                                      </div>
                                                    )}
                                                    <details style={{marginTop:8}}>
                                                      <summary style={{cursor:'pointer',fontSize:12}}>Inspect raw block JSON</summary>
                                                      <pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',fontSize:12,padding:8}}>{JSON.stringify(block.content,null,2)}</pre>
                                                    </details>
                                                    {(block.translations || []).map((translation, ti) => (
                                                      <div key={String(translation.language) + '-' + String(translation.version_no) + '-' + ti} style={{ marginTop: 8 }}>
                                                        <strong>{String(translation.language).toUpperCase()} block translation · {translation.translation_status} v{translation.version_no}</strong>
                                                        <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 12 }}>{JSON.stringify(translation.content, null, 2)}</pre>
                                                      </div>
                                                    ))}
                                                  </details>
                                                ))}
                                                <h5 style={{ margin: '12px 0 6px' }}>Lesson questions and answer keys</h5>
                                                {(lesson.questions || []).map(question => (
                                                  <div key={question.id} style={{ margin: '6px 0', padding: 8, border: '1px solid var(--border, #d7dce2)', borderRadius: 6 }}>
                                                    <strong>{question.question_type} · {question.difficulty} · active: {question.active ? 'yes' : 'no'}</strong>
                                                    <p style={{ whiteSpace: 'pre-wrap' }}>{question.prompt}</p>
                                                    {question.explanation && <p style={{ whiteSpace: 'pre-wrap' }}>Explanation: {question.explanation}</p>}
                                                    <ol type="A">{(question.options || []).map(option => (
                                                      <li key={option.option_key} style={{ marginBottom: 3 }}>
                                                        {option.label} {option.is_correct ? '✓ Answer key' : ''}
                                                      </li>
                                                    ))}</ol>
                                                    {(question.translations || []).map((translation, ti) => (
                                                      <details key={String(translation.language) + '-' + String(translation.version_no) + '-' + ti} style={{ marginTop: 6 }}>
                                                        <summary>{String(translation.language).toUpperCase()} question translation · {translation.translation_status} v{translation.version_no}</summary>
                                                        <p style={{ whiteSpace: 'pre-wrap' }}>{translation.prompt}</p>
                                                        {translation.explanation && <p style={{ whiteSpace: 'pre-wrap' }}>Explanation: {translation.explanation}</p>}
                                                        <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', fontSize: 12 }}>{JSON.stringify(translation.options || [], null, 2)}</pre>
                                                      </details>
                                                    ))}
                                                  </div>
                                                ))}
                                              </div>
                                            </details>
                                          ))}
                                          <h5 style={{ margin: '14px 0 6px' }}>Chapter resources</h5>
                                          {(chapterContent.resources || []).length === 0 && <p>No resources are assigned to this chapter.</p>}
                                          {(chapterContent.resources || []).map(resource => (
                                            <div key={resource.assignment_id} style={{ padding: 8, margin: '6px 0', border: '1px solid var(--border, #d7dce2)', borderRadius: 6 }}>
                                              <strong>{resource.title}</strong> · {resource.resource_type} · {resource.resource_status} · Assignment: {resource.assignment_status}
                                              <p style={{ fontSize: 12, margin: '4px 0' }}>{resource.description || ''} · {resource.language} · {resource.source_kind} · {resource.version_no ? 'v' + resource.version_no : ''}</p>
                                              {resource.external_url && <a href={resource.external_url} target="_blank" rel="noreferrer" style={{ fontSize: 12, overflowWrap: 'anywhere' }}>Open external resource</a>}
                                              {resource.source_reference && <p style={{ fontSize: 12 }}>Source reference: {resource.source_reference}</p>}
                                              {resource.sha256 && <p style={{ fontSize: 12 }}>SHA-256: {resource.sha256}</p>}
                                            </div>
                                          ))}
                                        </div>
                                      )}
                                    </div>
                                  );
                                })}
                              </section>
                            ))}
                          </div>
                        )}
                      </div>
                    )}
                    <label className={styles.reviewNote}>Quality review note
                      <textarea rows={2} maxLength={1000} value={reviewNotes[release.release_id] || ''}
                        onChange={event => setReviewNotes(previous => ({ ...previous, [release.release_id]: event.target.value }))}
                        placeholder={assessmentOnly ? 'Record question inventory, source fidelity, answer review, section scoring and release fingerprint checks…' : 'Record what was checked: syllabus coverage, published lessons, content integrity…'} />
                    </label>
                    {qualityBlocker ? (
                      <div id={'quality-blocker-' + release.release_id}
                        className={styles.actionHint + (!release.contentCurrent || !qualityReady || Boolean(preview && (!preview.contentCurrent || previewFingerprintMismatch)) ? ' ' + styles.actionHintWarning : '')}
                        role="note">
                        <span>{qualityBlocker}</span>
                        {previewNeedsLoading ? (
                          <button type="button" className={styles.secondaryButton}
                            disabled={reviewContentLoadingId !== null || reviewChapterLoadingId !== null || busyKey !== null}
                            onClick={() => void toggleReviewContent(release)}>
                            Load exact submitted content
                          </button>
                        ) : null}
                      </div>
                    ) : null}
                    <div className={styles.qualityActions}>
                      <button type="button" className={styles.primaryButton}
                        aria-describedby={qualityBlocker ? 'quality-blocker-' + release.release_id : undefined}
                        disabled={busyKey !== null || reviewChapterLoadingId !== null || !qualityReady || !exactPreviewReady || reviewNoteLength < 3}
                        onClick={() => void reviewQuality(release, true)}>
                        {busyKey === 'quality-' + release.release_id ? 'Saving decision…' : 'Approve quality'}
                      </button>
                      <button type="button" className={styles.secondaryButton} disabled={busyKey !== null || (reviewNotes[release.release_id] || '').trim().length < 3}
                        onClick={() => void reviewQuality(release, false)}>Return for correction</button>
                    </div>
                  </div>
                </article>
              );
            })}
          </div>
          <p className={styles.qualityFootnote}>Quality approval does not make the course purchasable by itself. The originating institution must complete release approval; HRMS can then assign the shareable release and continue through the listing checklist.</p>
        </section>
      )}

      {!loading && data && (data.availableReleases || []).length > 0 && (
        <section className={styles.availablePanel}>
          <header className={styles.availableHeader}>
            <div><span className={styles.summaryLabel}>HRMS release catalogue</span><h3>Available approved courses</h3>
              <p>Assign an origin-approved, shareable course release to this institution. Assignment does not publish or sell it.</p></div>
            <span className={styles.availableCount}>{data.availableReleases?.length || 0} available</span>
          </header>
          <div className={styles.availableList}>
            {(data.availableReleases || []).map(release => (
              <article className={styles.availableRow} key={release.release_id}>
                <div className={styles.availableMark} aria-hidden="true">↗</div>
                <div className={styles.availableCopy}>
                  <strong>{release.course_name}</strong>
                  <span>{release.course_code} · Release v{release.release_version}</span>
                  <small>Source: {release.source_institution_name} · {release.source_institution_code}</small>
                  {!release.contentCurrent && <small className={styles.availableWarning}>Reviewed content has changed. The source institution must resubmit this release.</small>}
                </div>
                <button type="button" className={styles.secondaryButton}
                  disabled={busyKey !== null || !release.contentCurrent}
                  onClick={() => void assignRelease(release)}>
                  {busyKey === 'assign-' + release.release_id ? 'Assigning…' : 'Assign to institution'}
                </button>
              </article>
            ))}
          </div>
        </section>
      )}

      {loading ? (
        <div className={styles.loadingList}><div /><div /><div /></div>
      ) : !data ? (
        <div className={styles.empty}><b>Readiness checklist unavailable</b><p>Retry the request. No publication action has been performed.</p><button type="button" onClick={() => void load()}>Try again</button></div>
      ) : !data.items.length && !(data.availableReleases || []).length ? (
        <div className={styles.empty}>
          <span className={styles.emptyIcon}>◎</span>
          <b>No assigned course releases yet</b>
          <p>There are no currently available shareable course releases to assign. The source institution must complete its release approval and HRMS content-quality review first.</p>
        </div>
      ) : !data.items.length ? (
        <div className={styles.empty}>
          <span className={styles.emptyIcon}>✓</span>
          <b>No course assigned yet</b>
          <p>Choose an approved course release above. It will then appear here with its own launch checklist and completion percentage.</p>
        </div>
      ) : (
        <div className={styles.courseList}>
          {data.items.map(item => {
            const draft = drafts[item.assignmentId] || initialDraft(item);
            const status = String(item.offerStatus || 'NOT_CONFIGURED').toUpperCase();
            const meter = progress(item);
            const blocker = nextBlocker(item);
            const editing = !item.offerId || ['DRAFT', 'REJECTED', 'ARCHIVED'].includes(status);
            const canSubmit = Boolean(item.offerId) && item.ready && ['DRAFT', 'REJECTED'].includes(status);
            const canApprove = Boolean(item.offerId) && item.ready && status === 'IN_REVIEW';
            const canPublish = Boolean(item.offerId) && item.ready && status === 'APPROVED';
            const canReturn = Boolean(item.offerId) && !item.ready && ['IN_REVIEW', 'APPROVED'].includes(status);
            return (
              <article className={styles.courseCard} key={item.assignmentId}>
                <header className={styles.courseHeader}>
                  <div className={styles.courseIdentity}>
                    <span className={styles.courseMark} aria-hidden="true">QE</span>
                    <div><h3>{item.courseName}</h3><p>Course #{item.courseId} · Assignment #{item.assignmentId}</p></div>
                  </div>
                  <div className={styles.statusCluster}>
                    <span className={styles.statusPill + ' ' + (status === 'PUBLISHED' ? styles.statusPublished : status === 'NOT_CONFIGURED' ? styles.statusDraft : styles.statusOther)}>{statusText(status)}</span>
                    {item.offerId ? <span className={styles.price}>{priceText(item.pricePaise)}</span> : <span className={styles.price}>Offer not created</span>}
                  </div>
                </header>

                <section className={styles.progressPanel}>
                  <div className={styles.progressTop}>
                    <div><span>Launch readiness</span><strong>{meter.percent}%</strong></div>
                    <span className={item.ready ? styles.readyTag : styles.blockedTag}>{item.ready ? 'Checks passed' : 'Action required'}</span>
                  </div>
                  <div className={styles.progressTrack} role="progressbar" aria-label={item.courseName + ' launch progress'} aria-valuemin={0} aria-valuemax={100} aria-valuenow={meter.percent}>
                    <span style={{ width: meter.percent + '%' }} />
                  </div>
                  <p>{meter.complete} of {meter.total} mandatory stages complete</p>
                </section>

                <div className={styles.stepper} aria-label="Course launch steps">
                  {GROUPS.map((group, index) => {
                    const state = groupState(item, group);
                    const isCurrent = !state.complete && !state.optional && !GROUPS.slice(0, index).every(previous => groupState(item, previous).complete || groupState(item, previous).optional);
                    return (
                      <section key={group.key} className={styles.step + (state.complete ? ' ' + styles.stepComplete : isCurrent ? ' ' + styles.stepCurrent : '')}>
                        <div className={styles.stepRail}>
                          <span className={styles.stepNumber}>{state.complete ? '✓' : group.number}</span>
                          {index < GROUPS.length - 1 && <span className={styles.stepConnector} />}
                        </div>
                        <div className={styles.stepBody}>
                          <div className={styles.stepHeading}>
                            <div><h4>{group.title}</h4><p>{group.subtitle}</p></div>
                            <span className={state.optional ? styles.stepOptional : state.complete ? styles.stepDone : styles.stepPending}>
                              {state.optional ? 'Not required' : state.complete ? 'Complete' : (state.present + '/' + state.total)}
                            </span>
                          </div>
                          {state.checks.length ? (
                            <div className={styles.checkList}>
                              {state.checks.map(check => (
                                <div className={styles.checkRow} key={check.code}>
                                  <span className={check.passed ? styles.checkPassed : styles.checkFailed}>{check.passed ? '✓' : '!'}</span>
                                  <div><strong>{check.label}</strong>{!check.passed && <p>{check.message}</p>}</div>
                                </div>
                              ))}
                            </div>
                          ) : state.optional ? (
                            <p className={styles.stepHint}>This is an intentionally free listing, so paid checkout, sale-invoice and revenue-share setup is not required.</p>
                          ) : item.offerId ? (
                            <p className={styles.stepHint}>The server has not returned the required checks for this stage. Refresh before trying to publish.</p>
                          ) : (
                            <p className={styles.stepHint}>This stage can be evaluated after the draft offer and price exist.</p>
                          )}
                          {group.key === 'offer' && editing && (
                            <div className={styles.offerForm}>
                              <label>Student-facing title<input value={draft.title} maxLength={200} onChange={event => updateDraft(item.assignmentId, { title: event.target.value })} /></label>
                              <label>Price in INR<input type="number" min="0" step="0.01" inputMode="decimal" placeholder="Enter price" value={draft.priceRupees} onChange={event => updateDraft(item.assignmentId, { priceRupees: event.target.value })} /><small>Set ₹0.00 only for a deliberately free course. Currency is locked to INR.</small></label>
                              <label className={styles.descriptionField}>Marketplace description<textarea rows={3} maxLength={5000} value={draft.description} onChange={event => updateDraft(item.assignmentId, { description: event.target.value })} placeholder="Describe the course for prospective learners." /></label>
                              <div className={styles.formActions}>
                                <button type="button" className={styles.primaryButton} disabled={busyKey !== null || !item.checks.filter(check => ['INSTITUTION_ACTIVE', 'COURSE_ACTIVE', 'CONTENT_QUALITY_REVIEW', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT', 'SOURCE_RIGHTS_CLEARANCE', 'ORIGIN_RELEASE_APPROVAL', 'ASSIGNMENT_AND_RELEASE'].includes(check.code)).every(check => check.passed)} onClick={() => void saveDraft(item)}>
                                  {busyKey === 'save-' + item.assignmentId ? 'Saving draft…' : item.offerId ? 'Save draft terms' : 'Create draft offer'}
                                </button>
                                <span>Saving a draft never publishes it.</span>
                              </div>
                            </div>
                          )}
                          {group.key === 'payment' && shouldShowPaymentSetupLink(item) && <a className={styles.actionLink} href="/institutions#hrms-integrations-heading">Open shared integration readiness <span aria-hidden="true">↗</span></a>}
                          {group.key === 'seller' && shouldShowSellerSetupLink(item) && <a className={styles.actionLink} href={financeSetupHref(item, sellerSetupTarget(item))}>Open seller &amp; tax setup <span aria-hidden="true">↗</span></a>}
                          {group.key === 'invoice' && shouldShowInvoiceSetupLink(item) && <a className={styles.actionLink} href={financeSetupHref(item, invoiceSetupTarget(item))}>{invoiceSetupLabel(item)} <span aria-hidden="true">↗</span></a>}
                          {group.key === 'course' && !item.ready && blocker && ['CONTENT_QUALITY_REVIEW', 'CONTENT_REVIEW_CURRENT', 'PUBLISHED_LEARNING_CONTENT'].includes(blocker.code) && <p className={styles.stepHint}>Resolve this in the course release / content-quality workflow, then refresh this checklist.</p>}
                          {group.key === 'release' && !item.ready && item.checks.some(check => !check.passed && ['ORIGIN_RELEASE_APPROVAL', 'ASSIGNMENT_AND_RELEASE'].includes(check.code)) && sourceReleaseHref(item) && <a className={styles.actionLink} href={sourceReleaseHref(item)!}>Open Institution in Admin <span aria-hidden="true">↗</span></a>}
                        </div>
                      </section>
                    );
                  })}
                </div>

                {blocker && !item.ready && (
                  <div className={styles.nextAction}>
                    <span className={styles.nextIcon} aria-hidden="true">→</span>
                    <div><strong>Next required action · {['RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK'].includes(blocker.code) ? 'HRMS platform operator' : ['APPROVED_TAX_PROFILE', 'EINVOICE_REVIEW', 'TAX_CALCULATION', 'GST_REGISTRATION', 'PUBLISHED_INVOICE_TEMPLATE', 'REVENUE_SHARE_POLICY', 'SHARE_POOL_VALIDATION', 'DISCOUNT_POOL_COMPATIBILITY'].includes(blocker.code) ? 'HRMS finance' : blocker.code === 'ORIGIN_RELEASE_APPROVAL' ? 'Source institution administrator' : blocker.code === 'SOURCE_RIGHTS_CLEARANCE' ? 'HRMS rights reviewer' : 'Course/content owner'}</strong><p>{blocker.label}: {blocker.message}</p>
                      {blocker.code === 'EINVOICE_REVIEW' && /requires e-invoicing|REQUIRED/i.test(blocker.message) ? <p className={styles.stepHint}>This is a platform integration limitation, not a missing form. Launch remains safely blocked until the supported IRP/IRN and signed-QR path is enabled; repeated Finance edits will not resolve it.</p> : null}
                    </div>
                    {blockerHref(item, blocker) && <a href={blockerHref(item, blocker)!}>{blocker.code === 'SOURCE_RIGHTS_CLEARANCE' ? 'Review source rights' : ['RAZORPAY_CREDENTIALS', 'RAZORPAY_WEBHOOK'].includes(blocker.code) ? 'Open integration readiness' : blocker.code === 'ORIGIN_RELEASE_APPROVAL' || blocker.code === 'ASSIGNMENT_AND_RELEASE' ? 'Open Institution in Admin' : 'Open required setup'} <span aria-hidden="true">↗</span></a>}
                    {!blockerHref(item, blocker) && (blocker.code === 'ORIGIN_RELEASE_APPROVAL' || blocker.code === 'ASSIGNMENT_AND_RELEASE')
                      ? <small>Open the Institution section in Admin. Set NEXT_PUBLIC_EDUCATION_ADMIN_ORIGIN to enable a direct course link.</small> : null}
                  </div>
                )}

                <footer className={styles.courseFooter}>
                  <div><span className={styles.footerLabel}>Listing workflow</span><p>{item.ready ? 'All configured checks pass. Continue with the status action below.' : 'The next step is highlighted above. The API rechecks readiness during each transition.'}</p></div>
                  <div className={styles.footerActions}>
                    {canSubmit && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'submit')}>{busyKey === 'submit-' + item.assignmentId ? 'Submitting…' : 'Submit for review'}</button>}
                    {canApprove && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'approve')}>{busyKey === 'approve-' + item.assignmentId ? 'Approving…' : 'Approve listing'}</button>}
                    {canPublish && <button type="button" className={styles.primaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'publish')}>{busyKey === 'publish-' + item.assignmentId ? 'Publishing…' : 'Publish to Marketplace'}</button>}
                    {canReturn && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'return-to-draft')}>Return to draft</button>}
                    {status === 'PUBLISHED' && <button type="button" className={styles.secondaryButton} disabled={busyKey !== null} onClick={() => void transition(item, 'archive')}>{busyKey === 'archive-' + item.assignmentId ? 'Archiving…' : 'Archive listing'}</button>}
                    {!canSubmit && !canApprove && !canPublish && status !== 'PUBLISHED' && !canReturn && <span className={styles.footerHint}>{status === 'IN_REVIEW' ? 'Awaiting a clean checklist or an explicit return to draft.' : status === 'APPROVED' ? 'Ready checks must pass before publication.' : 'Finish the required stages to unlock submission.'}</span>}
                  </div>
                </footer>
              </article>
            );
          })}
        </div>
      )}
      <p className={styles.disclaimer}>A completed checklist is not a legal opinion. HRMS must use the seller’s approved tax treatment and reviewed invoice setup. Payments remain blocked when e-invoicing is marked REQUIRED but the supported IRP/IRN path is not enabled.</p>
    </section>
  );
}
