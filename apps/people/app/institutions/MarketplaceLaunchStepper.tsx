'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { apiFetch } from '../components/apiClient';
import styles from './MarketplaceLaunchStepper.module.css';

function formatReviewSourceLocation(value:unknown):string {
  if(value===null||value===undefined||value==='')return '';
  if(typeof value==='string'||typeof value==='number')return String(value);
  if(Array.isArray(value))return value.map(formatReviewSourceLocation).filter(Boolean).join(' · ');
  if(typeof value==='object')return Object.entries(value as Record<string,unknown>)
    .filter(([,item])=>item!==null&&item!==undefined&&item!=='')
    .map(([key,item])=>key.replaceAll('_',' ')+': '+(typeof item==='object'?formatReviewSourceLocation(item):String(item)))
    .join(' · ');
  return String(value);
}
function safeReviewReference(value:unknown):string {
  try {
    const url=new URL(String(value||''));
    return url.protocol==='https:'||url.protocol==='http:'?url.href:'';
  } catch { return ''; }
}

function activateAccessibleReviewDialog(dialog:HTMLElement,onEscape:()=>void):()=>void {
  const previousOverflow=document.body.style.overflow;
  const previousFocus=document.activeElement instanceof HTMLElement?document.activeElement:null;
  document.body.style.overflow='hidden';
  const selector='a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])';
  const focusable=()=>Array.from(dialog.querySelectorAll<HTMLElement>(selector)).filter(el=>!el.hidden&&el.getAttribute('aria-hidden')!=='true');
  const frame=window.requestAnimationFrame(()=>{(focusable()[0]||dialog).focus();});
  const onKeyDown=(event:KeyboardEvent)=>{
    if(event.key==='Escape'){event.preventDefault();onEscape();return;}
    if(event.key!=='Tab')return;
    const items=focusable();
    if(!items.length){event.preventDefault();dialog.focus();return;}
    const first=items[0],last=items[items.length-1];
    if(event.shiftKey&&(document.activeElement===first||!dialog.contains(document.activeElement))){event.preventDefault();last.focus();}
    else if(!event.shiftKey&&(document.activeElement===last||!dialog.contains(document.activeElement))){event.preventDefault();first.focus();}
  };
  window.addEventListener('keydown',onKeyDown);
  return ()=>{
    window.cancelAnimationFrame(frame);
    window.removeEventListener('keydown',onKeyDown);
    document.body.style.overflow=previousOverflow;
    if(previousFocus?.isConnected)previousFocus.focus();
  };
}

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
  course_type_publish_ready: boolean;
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
  courseTypePublishReady: boolean;
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


function hrmsLocalizedText(value: unknown, language: 'en'|'hi'): string {
  if (typeof value === 'string') return value;
  if (!value || typeof value !== 'object' || Array.isArray(value)) return '';
  const variants=value as Record<string, unknown>;
  const candidate=variants[language] ?? variants[language === 'en' ? 'english' : 'hindi'] ?? variants[language.toUpperCase()];
  if (typeof candidate === 'string') return candidate;
  if (candidate && typeof candidate === 'object' && !Array.isArray(candidate)) {
    const row=candidate as Record<string, unknown>;
    return String(row.text ?? row.label ?? row.prompt ?? '');
  }
  return '';
}
function hrmsReviewPrompt(snapshot: Record<string, any>, language: 'en'|'hi'): string {
  return hrmsLocalizedText(snapshot.prompt_i18n,language) || (language === 'en' ? String(snapshot.prompt || '') : '');
}
function hrmsReviewOption(option: Record<string, any>, language: 'en'|'hi'): string {
  const translated=hrmsLocalizedText(option.text_i18n ?? option.label_i18n ?? option.option_i18n,language);
  if (translated) return translated;
  return language === 'en' ? String(option.text ?? option.label ?? '') : '';
}
function hrmsReviewAnswerValue(value: unknown): string {
  if (value === null || value === undefined) return 'Unresolved';
  if (typeof value === 'string') return value;
  try { return JSON.stringify(value); } catch { return String(value); }
}
function hrmsQuestionReviewStatus(snapshot: Record<string, any>): 'APPROVED'|'REJECTED'|'PENDING_HUMAN_REVIEW' {
  const question=String(snapshot.question_review_status || 'PENDING_HUMAN_REVIEW').toUpperCase();
  const answer=String(snapshot.answer_review_status || 'PENDING_HUMAN_REVIEW').toUpperCase();
  if (question === 'REJECTED' || answer === 'REJECTED') return 'REJECTED';
  if (question === 'APPROVED' && answer === 'APPROVED') return 'APPROVED';
  return 'PENDING_HUMAN_REVIEW';
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
  const [assessmentReviewTarget,setAssessmentReviewTarget] = useState<{releaseId:number;assessmentId:number;sequenceNo:number}|null>(null);
  const assessmentReviewDialogRef=useRef<HTMLElement|null>(null);
  const k12ReviewDialogRef=useRef<HTMLElement|null>(null);
  const [assessmentReviewSearch,setAssessmentReviewSearch] = useState('');
  const [assessmentReviewStatusFilter,setAssessmentReviewStatusFilter] = useState<'ALL'|'PENDING'|'APPROVED'|'REJECTED'>('ALL');
  const [k12ReviewTarget,setK12ReviewTarget] = useState<{releaseId:number;chapterId:number;lessonId:number|null}|null>(null);
  const [k12ReviewSearch,setK12ReviewSearch] = useState('');
  const [reviewContentLoadingId, setReviewContentLoadingId] = useState<number | null>(null);
  const [reviewContentErrors, setReviewContentErrors] = useState<Record<number, string>>({});
  const [reviewChapters, setReviewChapters] = useState<Record<string, ReleaseReviewChapter>>({});
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

  const assessmentReviewOpen = assessmentReviewTarget !== null;
  const assessmentReviewContent = assessmentReviewTarget ? reviewContent[assessmentReviewTarget.releaseId] : undefined;
  const assessmentReviewAssessment = assessmentReviewContent?.assessments?.find(item=>item.assessment_id===assessmentReviewTarget?.assessmentId);
  const assessmentReviewQuestions = assessmentReviewAssessment?.questions || [];
  const assessmentReviewSearchText = assessmentReviewSearch.trim().toLocaleLowerCase();
  const filteredAssessmentReviewQuestions = assessmentReviewQuestions.filter(question=>{
    const snapshot=question.question_snapshot||{};
    const status=hrmsQuestionReviewStatus(snapshot);
    if(assessmentReviewStatusFilter!=='ALL'&&status!==(assessmentReviewStatusFilter==='PENDING'?'PENDING_HUMAN_REVIEW':assessmentReviewStatusFilter))return false;
    if(!assessmentReviewSearchText)return true;
    return [question.sequence_no,snapshot.prompt,snapshot.prompt_i18n?.en,snapshot.prompt_i18n?.hi]
      .some(value=>String(value??'').toLocaleLowerCase().includes(assessmentReviewSearchText));
  });
  const activeAssessmentReviewQuestion=filteredAssessmentReviewQuestions.find(question=>question.sequence_no===assessmentReviewTarget?.sequenceNo)
    ||filteredAssessmentReviewQuestions[0];
  useEffect(()=>{
    if(!assessmentReviewTarget||!filteredAssessmentReviewQuestions.length)return;
    if(!filteredAssessmentReviewQuestions.some(question=>question.sequence_no===assessmentReviewTarget.sequenceNo)){
      setAssessmentReviewTarget(current=>current?{...current,sequenceNo:filteredAssessmentReviewQuestions[0].sequence_no}:current);
    }
  },[assessmentReviewTarget?.releaseId,assessmentReviewTarget?.assessmentId,assessmentReviewTarget?.sequenceNo,assessmentReviewSearch,assessmentReviewStatusFilter,assessmentReviewQuestions]);
  const activeAssessmentReviewSnapshot=activeAssessmentReviewQuestion?.question_snapshot||{};
  const activeAssessmentReviewAnswer=activeAssessmentReviewSnapshot.answer_payload||{};
  const activeAssessmentReviewAnswerKeys=activeAssessmentReviewAnswer.kind==='OPTION'
    ?[String(activeAssessmentReviewAnswer.value??'')]
    :activeAssessmentReviewAnswer.kind==='MULTI_OPTION'&&Array.isArray(activeAssessmentReviewAnswer.value)
      ?activeAssessmentReviewAnswer.value.map((value:unknown)=>String(value))
      :[];
  const activeAssessmentReviewStatus=hrmsQuestionReviewStatus(activeAssessmentReviewSnapshot);
  const approvedAssessmentQuestions=assessmentReviewQuestions.filter(question=>hrmsQuestionReviewStatus(question.question_snapshot||{})==='APPROVED').length;
  const rejectedAssessmentQuestions=assessmentReviewQuestions.filter(question=>hrmsQuestionReviewStatus(question.question_snapshot||{})==='REJECTED').length;
  const reviewReleaseQueueItem=assessmentReviewTarget
    ?(data?.qualityReviewItems||[]).find(release=>release.release_id===assessmentReviewTarget.releaseId)
    :undefined;
  const closeAssessmentReview=()=>setAssessmentReviewTarget(null);
  const openAssessmentReview=(releaseId:number,assessmentId:number,sequenceNo:number)=>{
    setAssessmentReviewSearch('');
    setAssessmentReviewStatusFilter('ALL');
    setAssessmentReviewTarget({releaseId,assessmentId,sequenceNo});
  };

  useEffect(()=>{
    if(!assessmentReviewOpen)return;
    const dialog=assessmentReviewDialogRef.current;
    if(!dialog)return;
    return activateAccessibleReviewDialog(dialog,closeAssessmentReview);
  },[assessmentReviewOpen]);

  const k12ReviewOpen = k12ReviewTarget !== null;
  const k12ReviewRelease = k12ReviewTarget ? (data?.qualityReviewItems||[]).find(item=>item.release_id===k12ReviewTarget.releaseId) : undefined;
  const k12ReviewPreview = k12ReviewTarget ? reviewContent[k12ReviewTarget.releaseId] : undefined;
  const k12ReviewChapter = k12ReviewTarget ? reviewChapters[k12ReviewTarget.releaseId+':'+k12ReviewTarget.chapterId] : undefined;
  const k12ReviewLesson = k12ReviewChapter?.lessons?.find(lesson=>lesson.id===k12ReviewTarget?.lessonId);
  const k12ReviewSearchValue = k12ReviewSearch.trim().toLocaleLowerCase();
  const k12ReviewSubjects = (k12ReviewPreview?.subjects||[]).map(subject=>({
    ...subject,
    chapters:(subject.chapters||[]).filter(chapter=>!k12ReviewSearchValue
      || [chapter.display_name,chapter.code,subject.display_name,subject.code].some(value=>String(value||'').toLocaleLowerCase().includes(k12ReviewSearchValue)))
  })).filter(subject=>subject.chapters.length>0);
  const closeK12Review = () => setK12ReviewTarget(null);
  const openK12Review = (releaseId:number,chapterId:number) => {
    setK12ReviewSearch('');
    setK12ReviewTarget({releaseId,chapterId,lessonId:null});
    const release=(data?.qualityReviewItems||[]).find(item=>item.release_id===releaseId);
    if(release&&!reviewChapters[releaseId+':'+chapterId])void toggleReviewChapter(release,chapterId);
  };
  const selectK12ReviewChapter = (releaseId:number,chapterId:number) => {
    setK12ReviewTarget({releaseId,chapterId,lessonId:null});
    const release=(data?.qualityReviewItems||[]).find(item=>item.release_id===releaseId);
    if(release&&!reviewChapters[releaseId+':'+chapterId])void toggleReviewChapter(release,chapterId);
  };
  useEffect(()=>{
    if(!k12ReviewOpen)return;
    const dialog=k12ReviewDialogRef.current;
    if(!dialog)return;
    return activateAccessibleReviewDialog(dialog,closeK12Review);
  },[k12ReviewOpen]);

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
      setAssessmentReviewTarget(current=>current?.releaseId===releaseId?null:current);
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
    const cacheKey=release.release_id+':'+chapterId;
    const cached = reviewChapters[cacheKey];
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
      setReviewChapters(previous => ({ ...previous, [cacheKey]: chapter }));
      setExpandedReviewChapters(previous => ({ ...previous, [release.release_id]: chapterId }));
      const exactReleaseCurrent = detail.contentCurrent === true
        && detail.content_fingerprint === release.content_fingerprint;
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
    const assessmentOnly = release.course_type_code === 'ASSESSMENT' && release.delivery_mode === 'ASSESSMENT_ONLY';
    const k12Learning = release.course_type_code === 'K12' && release.delivery_mode === 'LEARNING_CONTENT';
    const preview = reviewContent[release.release_id];
    if (approved && (!preview || !preview.contentCurrent
      || preview.content_fingerprint !== release.content_fingerprint
      || preview.courseTypePublishReady !== true)) {
      setError('Open and inspect the exact submitted release content and confirm the canonical course-type readiness gate before approving its quality.');
      return;
    }
    const releaseReady = release.course_type_publish_ready === true;
    if (approved && (!release.contentCurrent || !releaseReady)) {
      setError(assessmentOnly
        ? 'Do not approve this frozen release while any assessment or test pack is still Draft. Reject it for correction, then have the source institution review every question/answer, publish all assessments (which publishes complete packs), and submit a fresh release.'
        : k12Learning
          ? 'This release is not ready for quality approval. Refresh the checklist and ensure every active subject has published lessons.'
          : 'This registered course type and delivery mode are not supported for quality approval. The source institution must correct the course/version configuration; the content remains blocked.');
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
          ...(approved ? { reviewedContentFingerprint: preview?.content_fingerprint } : {}),
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
              const assessmentOnly = release.course_type_code === 'ASSESSMENT' && release.delivery_mode === 'ASSESSMENT_ONLY';
              const k12Learning = release.course_type_code === 'K12' && release.delivery_mode === 'LEARNING_CONTENT';
              const qualityReady = release.contentCurrent && release.course_type_publish_ready === true;
              const preview = reviewContent[release.release_id];
              const reviewNoteLength = (reviewNotes[release.release_id] || '').trim().length;
              const previewFingerprintMismatch = Boolean(preview
                && preview.content_fingerprint !== release.content_fingerprint);
              const previewLoadInProgress = reviewContentLoadingId !== null;
              const previewNeedsLoading = !preview
                && !previewLoadInProgress && reviewChapterLoadingId === null && busyKey === null;
              const qualityBlocker = !release.contentCurrent
                ? 'Approval is blocked because this release is not fingerprint-current. Return it for correction, have the source institution correct the content and submit a fresh release.'
                : !qualityReady
                  ? assessmentOnly
                    ? 'Approval is blocked because one or more assessments or test packs are still drafts or their question/answer review is incomplete. Return this release for correction, finish human review and publish every test/pack, then submit a fresh release.'
                    : k12Learning
                      ? 'Approval is blocked until every active mapped subject has published chapter and lesson content.'
                      : 'Approval is blocked because the registered course type or delivery mode is inactive, mismatched or unsupported.'
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
                              : preview.courseTypePublishReady !== true
                                ? 'The exact release preview is not ready under the active course-type publication gate. Do not approve; return it for correction and submit a release with a supported course type and delivery mode.'
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
                      </> : k12Learning ? <>
                        <span>K12 · LEARNING_CONTENT</span>
                        <span>{release.active_subject_count} active subjects</span>
                        <span>{release.subjects_with_published_lessons} with lessons</span>
                        <span>{release.published_chapters} chapters</span>
                        <span>{release.published_lessons} lessons</span>
                      </> : <>
                        <span>Unsupported: {release.course_type_code || 'unknown course type'}</span>
                        <span>Delivery: {release.delivery_mode || 'unknown'}</span>
                        <span>Release stays blocked</span>
                      </>}
                    </div>
                    {assessmentOnly && <p>Review the exact submitted test papers, question snapshots, source provenance, question-review and answer-review states, and scoring rules below. This preview is read-only.</p>}
                    {!assessmentOnly && !k12Learning && <p className={styles.availableWarning}>This course type/delivery-mode combination is not supported for release review. No quality approval can be recorded until the source configuration is corrected.</p>}
                    {!release.contentCurrent && <p className={styles.availableWarning}>Course content changed after submission. Ask the source institution to submit a fresh release.</p>}
                    <div className={styles.qualityActions}>
                      <button type="button" className={preview ? styles.secondaryButton : styles.primaryButton}
                        disabled={reviewContentLoadingId !== null || reviewChapterLoadingId !== null || busyKey !== null}
                        aria-label={preview ? 'Hide submitted content' : 'Review submitted content before approving'}
                        aria-describedby={qualityBlocker ? 'quality-blocker-' + release.release_id : undefined}
                        onClick={() => void toggleReviewContent(release)}>
                        {reviewContentLoadingId === release.release_id ? 'Loading exact release…' : preview ? 'Hide submitted content' : 'Load exact submitted content'}
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
                        {preview.course_type_code === 'ASSESSMENT' && preview.delivery_mode === 'ASSESSMENT_ONLY' ? (
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
                                {(() => {
                                  const questions=assessment.questions||[];
                                  const approved=questions.filter(question=>hrmsQuestionReviewStatus(question.question_snapshot||{})==='APPROVED').length;
                                  const rejected=questions.filter(question=>hrmsQuestionReviewStatus(question.question_snapshot||{})==='REJECTED').length;
                                  const firstPending=questions.find(question=>hrmsQuestionReviewStatus(question.question_snapshot||{})==='PENDING_HUMAN_REVIEW');
                                  const firstSequence=Number(firstPending?.sequence_no||questions[0]?.sequence_no||1);
                                  return <div className={styles.assessmentReviewLaunch}>
                                    <div>
                                      <strong>{questions.length} frozen question snapshots</strong>
                                      <p>{approved} fully approved · {rejected} rejected · {questions.length-approved-rejected} pending human review</p>
                                      <small>Open a dedicated read-only workspace to compare both languages, verify the key, explanation and review statuses, and inspect each question before the HRMS quality decision.</small>
                                    </div>
                                    <button type="button" className={styles.primaryButton}
                                      disabled={!questions.length}
                                      onClick={()=>openAssessmentReview(release.release_id,assessment.assessment_id,firstSequence)}>
                                      Open full-screen bilingual review
                                    </button>
                                  </div>;
                                })()}
                              </details>
                            ))}
                          </>
                        ) : preview.course_type_code === 'K12' && preview.delivery_mode === 'LEARNING_CONTENT' ? (
                          <div className={styles.k12ReviewOverview}>
                            <div className={styles.k12ReviewSummary}>
                              <div><strong>{preview.subjects?.length||0}</strong><span>Subjects</span></div>
                              <div><strong>{preview.subjects?.reduce((sum,subject)=>sum+(subject.chapters||[]).length,0)||0}</strong><span>Chapters</span></div>
                              <div><strong>{preview.lessonCount}</strong><span>Lessons</span></div>
                              <div><strong>{preview.lessonBlockCount}</strong><span>Teaching blocks</span></div>
                              <div><strong>{preview.lessonQuestionCount}</strong><span>Lesson questions</span></div>
                              <div><strong>{preview.courseResourceCount}</strong><span>Resources</span></div>
                            </div>
                            <p className={styles.k12ReviewIntro}>Inspect the submitted release by subject, chapter and lesson in a dedicated read-only workspace. Bilingual variants, teaching blocks, lesson questions, answer keys and resources are grouped inside each chapter instead of repeating the entire structure inline.</p>
                            {(preview.courseTranslations||[]).length>0&&<details className={styles.k12TranslationSummary}>
                              <summary>Course language variants · {preview.courseTranslations.length}</summary>
                              <div>{preview.courseTranslations.map((translation,ti)=><p key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti}><b>{String(translation.language).toUpperCase()}</b> · {translation.translation_status} v{translation.version_no} · {translation.display_name}</p>)}</div>
                            </details>}
                            {(preview.subjects||[]).map(subject=>(
                              <section className={styles.k12SubjectDirectory} key={subject.id}>
                                <header><div><h4>{subject.display_name}</h4><span>{subject.code} · {subject.chapters?.length||0} chapters</span></div>
                                  {(subject.translations||[]).length>0&&<details><summary>Translations ({subject.translations.length})</summary>{subject.translations.map((translation,ti)=><p key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti}>{String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}: {translation.display_name}</p>)}</details>}
                                </header>
                                <div className={styles.k12ChapterDirectory}>
                                  {(subject.chapters||[]).map(chapter=>(
                                    <article className={styles.k12ChapterRow} key={chapter.id}>
                                      <div className={styles.k12ChapterCopy}>
                                        <strong>{chapter.display_name}</strong>
                                        <span>{chapter.content_status} · {chapter.lesson_count} lessons · {chapter.lesson_block_count} blocks · {chapter.lesson_question_count} questions · {chapter.resource_count} resources</span>
                                        <small>Source: {chapter.curriculum_source||'not recorded'} · Source verified: {chapter.curriculum_source_verified?'Yes':'No'}{chapter.curriculum_source_pages?' · Pages: '+chapter.curriculum_source_pages:''}</small>
                                        {(chapter.translations||[]).length>0&&<small>{chapter.translations.map((translation,ti)=>(ti?' · ':'')+String(translation.language).toUpperCase()+' v'+translation.version_no+': '+translation.display_name).join('')}</small>}
                                        {chapter.curriculum_source_url&&<a href={chapter.curriculum_source_url} target="_blank" rel="noreferrer">Open source reference ↗</a>}
                                      </div>
                                      <button type="button" className={styles.secondaryButton} disabled={reviewChapterLoadingId!==null||busyKey!==null} onClick={()=>openK12Review(release.release_id,chapter.id)}>Open chapter review</button>
                                    </article>
                                  ))}
                                </div>
                              </section>
                            ))}
                          </div>

                        ) : (
                          <p className={styles.availableWarning}>This course type and delivery mode do not have a supported read-only review view. Quality approval is blocked until the source configuration is corrected.</p>
                        )}
                      </div>
                    )}
                    <label className={styles.reviewNote}>Quality review note
                      <textarea rows={2} maxLength={1000} value={reviewNotes[release.release_id] || ''}
                        onChange={event => setReviewNotes(previous => ({ ...previous, [release.release_id]: event.target.value }))}
                        placeholder={assessmentOnly ? 'Record question inventory, source fidelity, answer-key review, section scoring and release fingerprint checks…' : k12Learning ? 'Record what was checked: syllabus coverage, published lessons, content integrity…' : 'Explain the course-type/delivery-mode mismatch and required correction…'} />
                    </label>
                    {qualityBlocker ? (
                      <div id={'quality-blocker-' + release.release_id}
                        className={styles.actionHint + (!release.contentCurrent || !qualityReady || Boolean(preview && (!preview.contentCurrent || previewFingerprintMismatch)) ? ' ' + styles.actionHintWarning : '')}
                        role="note">
                        <span>{qualityBlocker}</span>
                        {previewNeedsLoading ? <span>Use the “Load exact submitted content” button above to inspect this release before deciding.</span> : null}
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



      {assessmentReviewOpen&&assessmentReviewTarget&&<div className={styles.hrmsAssessmentReviewBackdrop} role="presentation" onMouseDown={event=>{if(event.target===event.currentTarget)closeAssessmentReview();}}>
        <section ref={assessmentReviewDialogRef} tabIndex={-1} className={styles.hrmsAssessmentReviewWorkspace} role="dialog" aria-modal="true" aria-labelledby="hrms-assessment-review-title">
          <aside className={styles.hrmsAssessmentReviewSidebar}>
            <header className={styles.hrmsAssessmentSidebarHeader}>
              <span>HRMS · RELEASE QUALITY REVIEW</span>
              <h2>Question navigator</h2>
              <p>{assessmentReviewAssessment?.title||'Assessment'} · Release #{assessmentReviewTarget.releaseId}</p>
              <div className={styles.hrmsAssessmentProgress} role="progressbar" aria-label="Questions fully approved in submitted snapshot" aria-valuemin={0} aria-valuemax={assessmentReviewQuestions.length} aria-valuenow={approvedAssessmentQuestions}>
                <span style={{width:(assessmentReviewQuestions.length?100*approvedAssessmentQuestions/assessmentReviewQuestions.length:0)+'%'}}/>
              </div>
              <div className={styles.hrmsAssessmentProgressMeta}><b>{approvedAssessmentQuestions}/{assessmentReviewQuestions.length} fully approved</b><span>{assessmentReviewQuestions.length-approvedAssessmentQuestions-rejectedAssessmentQuestions} pending</span></div>
            </header>
            <div className={styles.hrmsAssessmentNavigatorFilters}>
              <input aria-label="Search bilingual question text" value={assessmentReviewSearch} onChange={event=>setAssessmentReviewSearch(event.target.value)} placeholder="Search question or number"/>
              <select aria-label="Filter assessment questions by review status" value={assessmentReviewStatusFilter} onChange={event=>setAssessmentReviewStatusFilter(event.target.value as 'ALL'|'PENDING'|'APPROVED'|'REJECTED')}>
                <option value="ALL">All statuses</option><option value="PENDING">Pending review</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option>
              </select>
            </div>
            <nav className={styles.hrmsAssessmentQuestionNav} aria-label="Submitted assessment questions">
              {filteredAssessmentReviewQuestions.map(question=>{
                const status=hrmsQuestionReviewStatus(question.question_snapshot||{});
                return <button type="button" key={question.sequence_no}
                  className={[styles.hrmsAssessmentQuestionNavItem,activeAssessmentReviewQuestion?.sequence_no===question.sequence_no?styles.hrmsAssessmentQuestionNavItemActive:''].join(' ')}
                  aria-current={activeAssessmentReviewQuestion?.sequence_no===question.sequence_no?'true':undefined}
                  onClick={()=>setAssessmentReviewTarget(current=>current?{...current,sequenceNo:question.sequence_no}:current)}>
                  <span>{question.sequence_no}</span><b>Q{question.sequence_no}</b><small>{status==='APPROVED'?'Approved':status==='REJECTED'?'Rejected':'Pending'}</small>
                </button>;
              })}
              {!filteredAssessmentReviewQuestions.length&&<p className={styles.hrmsAssessmentNavigatorEmpty}>No questions match the current filters.</p>}
            </nav>
          </aside>
          <div className={styles.hrmsAssessmentReviewMain}>
            <header className={styles.hrmsAssessmentReviewHeader}>
              <div>
                <span>FROZEN SUBMITTED SNAPSHOT · READ ONLY</span>
                <h1 id="hrms-assessment-review-title">{assessmentReviewContent?.course_name||reviewReleaseQueueItem?.course_name||'Course'} · {assessmentReviewAssessment?.title||'Assessment'}</h1>
                <p>Release v{assessmentReviewContent?.release_version??reviewReleaseQueueItem?.release_version} · Release #{assessmentReviewTarget.releaseId} · {assessmentReviewAssessment?.status||'Unknown status'} · {assessmentReviewAssessment?.questionCount??assessmentReviewAssessment?.question_count??assessmentReviewQuestions.length} questions</p>
              </div>
              <button type="button" className={styles.hrmsAssessmentReviewClose} aria-label="Close bilingual question review" onClick={closeAssessmentReview}>×</button>
            </header>
            <div className={styles.hrmsAssessmentFingerprintBar}>
              <div><strong>Release fingerprint</strong><code>{assessmentReviewContent?.content_fingerprint||'Not available'}</code></div>
              <span className={assessmentReviewContent?.contentCurrent===true&&assessmentReviewContent?.content_fingerprint===reviewReleaseQueueItem?.content_fingerprint?styles.hrmsAssessmentFingerprintCurrent:styles.hrmsAssessmentFingerprintStale}>
                {assessmentReviewContent?.contentCurrent===true&&assessmentReviewContent?.content_fingerprint===reviewReleaseQueueItem?.content_fingerprint?'Matches the queued submission':'Fingerprint mismatch — do not approve'}
              </span>
            </div>
            {!activeAssessmentReviewQuestion?<div className={styles.hrmsAssessmentReviewEmpty}>No questions match the current filter. Change the filter or close this review.</div>:<>
              <div className={styles.hrmsAssessmentReviewContent}>
                <div className={styles.hrmsAssessmentQuestionTitle}>
                  <div><span>QUESTION {activeAssessmentReviewQuestion.sequence_no} OF {assessmentReviewQuestions.length}</span><h2>Question {activeAssessmentReviewQuestion.sequence_no}</h2>
                    <p>{String(activeAssessmentReviewSnapshot.question_type||'Question')} · {String(activeAssessmentReviewSnapshot.section_code||'Section not specified')} · {activeAssessmentReviewQuestion.max_marks} mark(s)</p>
                  </div>
                  <span className={[styles.hrmsAssessmentStatusPill,activeAssessmentReviewStatus==='APPROVED'?styles.hrmsAssessmentStatusApproved:activeAssessmentReviewStatus==='REJECTED'?styles.hrmsAssessmentStatusRejected:styles.hrmsAssessmentStatusPending].join(' ')}>
                    {activeAssessmentReviewStatus==='APPROVED'?'QUESTION + ANSWER APPROVED':activeAssessmentReviewStatus==='REJECTED'?'QUESTION OR ANSWER REJECTED':'PENDING HUMAN REVIEW'}
                  </span>
                </div>
                <div className={styles.hrmsAssessmentBilingualGrid}>
                  <section className={styles.hrmsAssessmentLanguagePanel} aria-labelledby="hrms-review-english-heading">
                    <header className={styles.hrmsAssessmentLanguageHeader}><div><span>EN</span><h3 id="hrms-review-english-heading">English</h3></div><small>English source variant</small></header>
                    {hrmsLocalizedText(activeAssessmentReviewSnapshot.stimulus_i18n,'en')&&<div className={styles.hrmsAssessmentStimulus}><b>Shared question stimulus</b><p>{hrmsLocalizedText(activeAssessmentReviewSnapshot.stimulus_i18n,'en')}</p></div>}
                    <div className={styles.hrmsAssessmentPrompt}>{hrmsReviewPrompt(activeAssessmentReviewSnapshot,'en')||<em>English prompt is missing in this submitted snapshot.</em>}</div>
                    <h4>Answer options</h4>
                    {Array.isArray(activeAssessmentReviewSnapshot.options)&&activeAssessmentReviewSnapshot.options.length>0?<ol className={styles.hrmsAssessmentOptions}>{activeAssessmentReviewSnapshot.options.map((option:Record<string,any>,index:number)=>{
                      const key=String(option.key||option.option_key||String.fromCharCode(65+index));
                      const chosen=activeAssessmentReviewAnswerKeys.includes(key);
                      return <li key={key} className={chosen?styles.hrmsAssessmentOptionSelected:''}><span>{key}</span><p>{hrmsReviewOption(option,'en')||'English option text is missing.'}</p>{chosen&&<b>Answer key</b>}</li>;
                    })}</ol>:<p className={styles.hrmsAssessmentMissingLanguage}>No structured options are present in this snapshot.</p>}
                    {hrmsLocalizedText(activeAssessmentReviewSnapshot.explanation_i18n,'en')||activeAssessmentReviewSnapshot.explanation?<section className={styles.hrmsAssessmentExplanation}><b>English explanation</b><p>{hrmsLocalizedText(activeAssessmentReviewSnapshot.explanation_i18n,'en')||String(activeAssessmentReviewSnapshot.explanation||'')}</p></section>:<section className={styles.hrmsAssessmentExplanationMissing}>English explanation is missing.</section>}
                  </section>
                  <section className={styles.hrmsAssessmentLanguagePanel} aria-labelledby="hrms-review-hindi-heading" lang="hi">
                    <header className={styles.hrmsAssessmentLanguageHeader}><div><span>हि</span><h3 id="hrms-review-hindi-heading">हिंदी</h3></div><small>हिंदी संस्करण</small></header>
                    {hrmsLocalizedText(activeAssessmentReviewSnapshot.stimulus_i18n,'hi')&&<div className={styles.hrmsAssessmentStimulus}><b>साझा प्रश्न सामग्री</b><p>{hrmsLocalizedText(activeAssessmentReviewSnapshot.stimulus_i18n,'hi')}</p></div>}
                    <div className={styles.hrmsAssessmentPrompt}>{hrmsReviewPrompt(activeAssessmentReviewSnapshot,'hi')||<em>इस सबमिट किए गए संस्करण में हिंदी प्रश्न उपलब्ध नहीं है।</em>}</div>
                    <h4>उत्तर विकल्प</h4>
                    {Array.isArray(activeAssessmentReviewSnapshot.options)&&activeAssessmentReviewSnapshot.options.length>0?<ol className={styles.hrmsAssessmentOptions}>{activeAssessmentReviewSnapshot.options.map((option:Record<string,any>,index:number)=>{
                      const key=String(option.key||option.option_key||String.fromCharCode(65+index));
                      const chosen=activeAssessmentReviewAnswerKeys.includes(key);
                      return <li key={key} className={chosen?styles.hrmsAssessmentOptionSelected:''}><span>{key}</span><p>{hrmsReviewOption(option,'hi')||<em>हिंदी विकल्प उपलब्ध नहीं है।</em>}</p>{chosen&&<b>उत्तर कुंजी</b>}</li>;
                    })}</ol>:<p className={styles.hrmsAssessmentMissingLanguage}>इस स्नैपशॉट में संरचित विकल्प उपलब्ध नहीं हैं।</p>}
                    {hrmsLocalizedText(activeAssessmentReviewSnapshot.explanation_i18n,'hi')?<section className={styles.hrmsAssessmentExplanation}><b>हिंदी में व्याख्या</b><p>{hrmsLocalizedText(activeAssessmentReviewSnapshot.explanation_i18n,'hi')}</p></section>:<section className={styles.hrmsAssessmentExplanationMissing}>हिंदी व्याख्या उपलब्ध नहीं है।</section>}
                  </section>
                </div>
                <section className={styles.hrmsAssessmentAnswerSummary}>
                  <div><span>ANSWER KEY · ANSWER_PAYLOAD</span><b>{String(activeAssessmentReviewAnswer.kind||'UNRESOLVED')} · {hrmsReviewAnswerValue(activeAssessmentReviewAnswer.value)}</b></div>
                  <div><span>REVIEW SNAPSHOT STATES</span><b>Question: {String(activeAssessmentReviewSnapshot.question_review_status||'PENDING_HUMAN_REVIEW')} · Answer: {String(activeAssessmentReviewSnapshot.answer_review_status||'PENDING_HUMAN_REVIEW')}</b></div>
                  <p>This is the immutable release snapshot currently under HRMS review. HRMS inspection does not edit or approve individual answers; return the release for source correction if an answer or translation is wrong.</p>
                </section>
                <section className={styles.hrmsAssessmentAnswerSummary} aria-label="Question source provenance">
                  <div><span>QUESTION SOURCE</span><b>{String(activeAssessmentReviewSnapshot.source_title||'Source not recorded')}</b></div>
                  <div><span>ISSUER / BOARD</span><b>{String(activeAssessmentReviewSnapshot.board||'Not recorded')}</b></div>
                  <div><span>SOURCE YEAR</span><b>{String(activeAssessmentReviewSnapshot.source_year||'Not recorded')}</b></div>
                  <div><span>SOURCE LOCATION / PAGE</span><b>{formatReviewSourceLocation(activeAssessmentReviewSnapshot.source_location)||'Not recorded'}</b></div>
                  <div><span>SOURCE PACKAGE</span><b>{String(activeAssessmentReviewSnapshot.source_id||'Not recorded')}</b></div>
                  {activeAssessmentReviewSnapshot.source_package_sha256&&<div><span>SOURCE PACKAGE SHA-256</span><b>{String(activeAssessmentReviewSnapshot.source_package_sha256)}</b></div>}
                  {safeReviewReference(activeAssessmentReviewSnapshot.source_ref)&&<div><span>SOURCE REFERENCE</span><b><a href={safeReviewReference(activeAssessmentReviewSnapshot.source_ref)} target="_blank" rel="noreferrer">{String(activeAssessmentReviewSnapshot.source_ref)}</a></b></div>}
                  {activeAssessmentReviewSnapshot.source_note&&<div><span>IMPORT NOTE</span><b>{String(activeAssessmentReviewSnapshot.source_note)}</b></div>}
                  {(activeAssessmentReviewSnapshot.last_rejection_note||(activeAssessmentReviewStatus==='REJECTED'&&activeAssessmentReviewSnapshot.human_review_note))&&<div><span>SOURCE REVIEW FEEDBACK</span><b>{String(activeAssessmentReviewSnapshot.last_rejection_note||activeAssessmentReviewSnapshot.human_review_note)}</b></div>}
                </section>
              </div>
              <footer className={styles.hrmsAssessmentReviewFooter}>
                <div className={styles.hrmsAssessmentQuestionPaging}>
                  <button type="button" className={styles.secondaryButton}
                    disabled={!filteredAssessmentReviewQuestions.length||filteredAssessmentReviewQuestions.findIndex(question=>question.sequence_no===activeAssessmentReviewQuestion.sequence_no)<=0}
                    onClick={()=>{
                      const index=filteredAssessmentReviewQuestions.findIndex(question=>question.sequence_no===activeAssessmentReviewQuestion.sequence_no);
                      if(index>0)setAssessmentReviewTarget(current=>current?{...current,sequenceNo:filteredAssessmentReviewQuestions[index-1].sequence_no}:current);
                    }}>← Previous question</button>
                  <span>Question {Math.max(0,filteredAssessmentReviewQuestions.findIndex(question=>question.sequence_no===activeAssessmentReviewQuestion.sequence_no)+1)} / {filteredAssessmentReviewQuestions.length}</span>
                  <button type="button" className={styles.secondaryButton}
                    disabled={!filteredAssessmentReviewQuestions.length||filteredAssessmentReviewQuestions.findIndex(question=>question.sequence_no===activeAssessmentReviewQuestion.sequence_no)<0||filteredAssessmentReviewQuestions.findIndex(question=>question.sequence_no===activeAssessmentReviewQuestion.sequence_no)>=filteredAssessmentReviewQuestions.length-1}
                    onClick={()=>{
                      const index=filteredAssessmentReviewQuestions.findIndex(question=>question.sequence_no===activeAssessmentReviewQuestion.sequence_no);
                      if(index>=0&&index<filteredAssessmentReviewQuestions.length-1)setAssessmentReviewTarget(current=>current?{...current,sequenceNo:filteredAssessmentReviewQuestions[index+1].sequence_no}:current);
                    }}>Next question →</button>
                </div>
                <div className={styles.hrmsAssessmentQuestionPaging}>
                  <span>HRMS quality decision remains on the release review page.</span>
                  <button type="button" className={styles.primaryButton} onClick={closeAssessmentReview}>Return to quality review</button>
                </div>
              </footer>
            </>}
          </div>
        </section>
      </div>}


      {k12ReviewOpen&&k12ReviewTarget&&k12ReviewPreview&&k12ReviewRelease&&<div className={styles.k12ReviewBackdrop} role="presentation" onMouseDown={event=>{if(event.target===event.currentTarget)closeK12Review();}}>
        <section ref={k12ReviewDialogRef} tabIndex={-1} className={styles.k12ReviewWorkspace} role="dialog" aria-modal="true" aria-labelledby="k12-review-title">
          <aside className={styles.k12ReviewSidebar}>
            <header className={styles.k12ReviewSidebarHeader}>
              <span>HRMS · K12 RELEASE REVIEW</span><h2>Chapter navigator</h2>
              <p>{k12ReviewPreview.course_name} · Release #{k12ReviewTarget.releaseId}</p>
              <small>{k12ReviewPreview.lessonCount} lessons · {k12ReviewPreview.lessonBlockCount} blocks · {k12ReviewPreview.lessonQuestionCount} questions</small>
            </header>
            <div className={styles.k12ReviewSidebarTools}><input aria-label="Search subjects and chapters" value={k12ReviewSearch} onChange={event=>setK12ReviewSearch(event.target.value)} placeholder="Find subject or chapter"/></div>
            <nav className={styles.k12ReviewChapterNav} aria-label="Course subjects and chapters">
              {k12ReviewSubjects.map(subject=><section key={subject.id}><h3>{subject.display_name} <small>{subject.chapters.length}</small></h3>
                {subject.chapters.map(chapter=><button key={chapter.id} type="button" className={[styles.k12ReviewChapterNavItem,k12ReviewTarget.chapterId===chapter.id?styles.k12ReviewChapterNavItemActive:''].join(' ')} aria-current={k12ReviewTarget.chapterId===chapter.id?'true':undefined} onClick={()=>selectK12ReviewChapter(k12ReviewTarget.releaseId,chapter.id)}>
                  <b>{chapter.display_name}</b><small>{chapter.lesson_count} lessons · {chapter.lesson_question_count} questions</small>
                </button>)}
              </section>)}
              {!k12ReviewSubjects.length&&<p className={styles.k12ReviewEmptyNote}>No chapters match your search.</p>}
            </nav>
          </aside>
          <div className={styles.k12ReviewMain}>
            <header className={styles.k12ReviewHeader}>
              <div><span>FROZEN SUBMITTED SNAPSHOT · READ ONLY</span><h1 id="k12-review-title">{k12ReviewChapter?.display_name||'Loading chapter content…'}</h1>
                <p>{k12ReviewPreview.course_name} · Release v{k12ReviewPreview.release_version} · {k12ReviewChapter?.lesson_count??'—'} lessons · {k12ReviewChapter?.resource_count??'—'} resources</p>
              </div>
              <button type="button" className={styles.k12ReviewClose} aria-label="Close K12 content review" onClick={closeK12Review}>×</button>
            </header>
            <div className={styles.k12ReviewFingerprint}>
              <div><strong>Submitted release fingerprint</strong><code>{k12ReviewPreview.content_fingerprint}</code></div>
              <span className={k12ReviewPreview.contentCurrent&&k12ReviewPreview.content_fingerprint===k12ReviewRelease.content_fingerprint?styles.k12ReviewFingerprintCurrent:styles.k12ReviewFingerprintStale}>{k12ReviewPreview.contentCurrent&&k12ReviewPreview.content_fingerprint===k12ReviewRelease.content_fingerprint?'Matches submitted release':'Fingerprint mismatch — do not approve'}</span>
            </div>
            {reviewContentErrors[k12ReviewTarget.releaseId]&&<div className={styles.k12ReviewError} role="alert">{reviewContentErrors[k12ReviewTarget.releaseId]}</div>}
            {!k12ReviewChapter?<div className={styles.k12ReviewLoading} role="status">{reviewChapterLoadingId!==null?'Loading the exact submitted chapter…':'Chapter details have not loaded yet. Select the chapter again or refresh the release preview.'}</div>:<>
              <div className={styles.k12ReviewContent}>
                <section className={styles.k12ReviewProvenance}>
                  <div><span>SUBMITTED SOURCE</span><strong>{k12ReviewChapter.curriculum_source||'No source recorded'}</strong>
                    <p>Source verification: {k12ReviewChapter.curriculum_source_verified?'Verified':'Not verified'}{k12ReviewChapter.curriculum_source_pages?' · Pages: '+k12ReviewChapter.curriculum_source_pages:''}</p>
                    {k12ReviewChapter.curriculum_source_url&&<a href={k12ReviewChapter.curriculum_source_url} target="_blank" rel="noreferrer">Open source reference ↗</a>}
                  </div>
                  <div><span>CHAPTER STATUS</span><strong>{k12ReviewChapter.content_status}</strong><p>{k12ReviewChapter.lessons?.length||0} lessons · {k12ReviewChapter.lesson_block_count} teaching blocks · {k12ReviewChapter.lesson_question_count} questions · {k12ReviewChapter.resources?.length||0} resources</p></div>
                </section>
                {(k12ReviewChapter.translations||[]).length>0&&<details className={styles.k12ReviewTranslations}><summary>Chapter language variants · {k12ReviewChapter.translations.length}</summary><div>{k12ReviewChapter.translations.map((translation,ti)=><p key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti}><b>{String(translation.language).toUpperCase()}</b> · {translation.translation_status} v{translation.version_no}: {translation.display_name}</p>)}</div></details>}
                <div className={styles.k12ReviewLessonLayout}>
                  <nav className={styles.k12ReviewLessonNav} aria-label="Lessons in selected chapter">
                    <header><strong>Lessons</strong><span>{k12ReviewChapter.lessons?.length||0}</span></header>
                    {(k12ReviewChapter.lessons||[]).map(lesson=><button type="button" key={lesson.id} className={[styles.k12ReviewLessonNavItem,k12ReviewTarget.lessonId===lesson.id?styles.k12ReviewLessonNavItemActive:''].join(' ')} aria-current={k12ReviewTarget.lessonId===lesson.id?'true':undefined} onClick={()=>setK12ReviewTarget(current=>current?{...current,lessonId:lesson.id}:current)}>
                      <b>{lesson.sort_order}. {lesson.title}</b><small>{lesson.status} · {lesson.blocks?.length||0} blocks · {lesson.questions?.length||0} questions</small>
                    </button>)}
                  </nav>
                  <section className={styles.k12ReviewLessonDetail}>
                    {!k12ReviewLesson?<div className={styles.k12ReviewLessonWelcome}><h3>Select a lesson to inspect</h3><p>Review language variants, learning objectives, teaching blocks, questions, answer keys and translations one lesson at a time.</p></div>:<>
                      <header className={styles.k12ReviewLessonHeader}><div><span>LESSON {k12ReviewLesson.sort_order} · {k12ReviewLesson.code}</span><h2>{k12ReviewLesson.title}</h2></div><span className={k12ReviewLesson.active&&k12ReviewLesson.status==='PUBLISHED'?styles.k12ReviewFingerprintCurrent:styles.k12ReviewFingerprintStale}>{k12ReviewLesson.status} · {k12ReviewLesson.active?'Active':'Inactive'}</span></header>
                      <div className={styles.k12ReviewLessonMeta}>Estimated time: {k12ReviewLesson.estimated_minutes} min{k12ReviewLesson.objective_title?' · Objective: '+k12ReviewLesson.objective_title:''}{k12ReviewLesson.objective_code?' · '+k12ReviewLesson.objective_code:''}</div>
                      <div className={styles.k12ReviewBilingualGrid}>
                        <section lang="en"><header><b>EN</b><strong>English</strong></header><p>{(k12ReviewLesson.translations||[]).find(translation=>String(translation.language).toLowerCase()==='en')?.summary||k12ReviewLesson.summary||'No English summary supplied.'}</p>
                          {(k12ReviewLesson.objectiveTranslations||[]).filter(translation=>String(translation.language).toLowerCase()==='en').map((translation,ti)=><p key={ti}><b>Objective:</b> {translation.title||''}{translation.description?' — '+translation.description:''}</p>)}
                          {(k12ReviewLesson.translations||[]).filter(translation=>String(translation.language).toLowerCase()==='en').map((translation,ti)=><details key={String(translation.language)+'-'+ti}><summary>Title / summary variant · v{translation.version_no}</summary><p>{translation.title}</p><p>{translation.summary}</p></details>)}
                        </section>
                        <section lang="hi"><header><b>हि</b><strong>हिंदी</strong></header><p>{(k12ReviewLesson.translations||[]).find(translation=>String(translation.language).toLowerCase()==='hi')?.summary||'हिंदी सारांश उपलब्ध नहीं है।'}</p>
                          {(k12ReviewLesson.objectiveTranslations||[]).filter(translation=>String(translation.language).toLowerCase()==='hi').map((translation,ti)=><p key={ti}><b>सीखने का उद्देश्य:</b> {translation.title||''}{translation.description?' — '+translation.description:''}</p>)}
                          {(k12ReviewLesson.translations||[]).filter(translation=>String(translation.language).toLowerCase()==='hi').map((translation,ti)=><details key={String(translation.language)+'-'+ti}><summary>शीर्षक / सारांश · v{translation.version_no}</summary><p>{translation.title}</p><p>{translation.summary}</p></details>)}
                        </section>
                      </div>
                      <section className={styles.k12ReviewDetailSection}><header><h3>Teaching blocks</h3><span>{k12ReviewLesson.blocks?.length||0}</span></header>
                        {(k12ReviewLesson.blocks||[]).map(block=><details key={block.id} className={styles.k12ReviewBlock}><summary>Block {block.sequence_no} · {block.block_type}{block.asset_title?' · '+block.asset_title:''}</summary>
                          <div className={styles.k12ReviewBlockContent}>{readableLessonBlock(block.content).map((field,fieldIndex)=><div key={field.label+'-'+fieldIndex}><b>{field.label}</b><p>{field.text}</p></div>)}</div>
                          {block.asset_url&&<div className={styles.k12ReviewAsset}>{/^https:\/\//i.test(block.asset_url)&&['IMAGE','DIAGRAM'].includes(String(block.asset_type||'').toUpperCase())&&<img src={block.asset_url} alt={block.asset_alt||block.asset_title||'Submitted lesson visual'} loading="lazy" referrerPolicy="no-referrer"/>}<p>Asset: <a href={block.asset_url} target="_blank" rel="noreferrer">{block.asset_title||block.asset_url}</a> · {block.asset_type||'file'} · {block.asset_status||'unknown status'}{block.asset_alt?' · Alt: '+block.asset_alt:''}</p></div>}
                          {(block.translations||[]).length>0&&<details className={styles.k12ReviewBlockTranslation}><summary>Block translations · {block.translations.length}</summary>{block.translations.map((translation,ti)=><div key={String(translation.language)+'-'+String(translation.version_no)+'-'+ti}><b>{String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}</b><pre>{JSON.stringify(translation.content,null,2)}</pre></div>)}</details>}
                        </details>)}
                      </section>
                      <section className={styles.k12ReviewDetailSection}><header><h3>Lesson questions and answer keys</h3><span>{k12ReviewLesson.questions?.length||0}</span></header>
                        {(k12ReviewLesson.questions||[]).map(question=><article key={question.id} className={styles.k12ReviewQuestion}>
                          <div><b>{question.question_type} · {question.difficulty}</b><span>{question.active?'Active':'Inactive'}</span></div><p>{question.prompt}</p>{question.explanation&&<p><b>Explanation:</b> {question.explanation}</p>}
                          <ol type="A">{(question.options||[]).map(option=><li key={option.option_key}>{option.label}{option.is_correct?' · ✓ Answer key':''}</li>)}</ol>
                          {(question.translations||[]).length>0&&<details><summary>Question translations · {question.translations.length}</summary>{question.translations.map((translation,ti)=><section key={String(translation.language)+'-'+ti} lang={String(translation.language).toLowerCase()==='hi'?'hi':undefined}><b>{String(translation.language).toUpperCase()} · {translation.translation_status} v{translation.version_no}</b><p>{translation.prompt}</p>{translation.explanation&&<p>Explanation: {translation.explanation}</p>}<pre>{JSON.stringify(translation.options||[],null,2)}</pre></section>)}</details>}
                        </article>)}
                      </section>
                    </>}
                  </section>
                </div>
                <section className={styles.k12ReviewDetailSection}><header><h3>Chapter resources</h3><span>{k12ReviewChapter.resources?.length||0}</span></header>
                  {(k12ReviewChapter.resources||[]).length===0?<p className={styles.k12ReviewNoResources}>No resources are assigned to this chapter.</p>:(k12ReviewChapter.resources||[]).map(resource=><article className={styles.k12ReviewResource} key={resource.assignment_id}>
                    <div><b>{resource.title}</b><span>{resource.resource_type} · {resource.resource_status} · Assignment {resource.assignment_status}</span></div><p>{resource.description||''} · {resource.language} · {resource.source_kind} · {resource.version_no?'v'+resource.version_no:''}</p>
                    {resource.external_url&&<a href={resource.external_url} target="_blank" rel="noreferrer">Open external resource ↗</a>}{resource.source_reference&&<p>Source reference: {resource.source_reference}</p>}{resource.sha256&&<code>SHA-256: {resource.sha256}</code>}
                  </article>)}
                </section>
              </div>
              <footer className={styles.k12ReviewFooter}><span>Read-only inspection of the frozen release · Quality approval is recorded separately below.</span><button type="button" className={styles.primaryButton} onClick={closeK12Review}>Return to quality review</button></footer>
            </>}
          </div>
        </section>
      </div>}

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
