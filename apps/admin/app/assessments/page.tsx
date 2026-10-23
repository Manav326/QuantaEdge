'use client';

import { getUiLocaleLanguage } from '../i18n/catalog';
import AdminSidebar from '../components/AdminSidebar';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import PremiumPagination from '../components/PremiumPagination';
import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import { useUiTranslation } from '../i18n/useUiTranslation';
import styles from './Assessments.module.css';
import { AdminCourseSelector, useAdminCourseScope } from '../components/AdminCourseScope';

type CourseOption={id:number;course_id:number;code:string;display_name:string;curriculum_class_code:string};
type BankQuestion = { question_id:number; question_type:string; prompt:string; difficulty:string; marks?:number|null; lesson_title:string; chapter_id:number; chapter_name:string; chapter_code:string; subject_name:string; };
type Assessment = { assessment_id:number; test_id?:string|null; title:string; description?:string; duration_minutes:number; max_attempts:number; status:string; offline_enabled?:boolean; assessment_type?:string; is_generated?:boolean; generation_version?:number; class_code:string; subject_code:string; subject_name:string; chapter_id?:number|null; chapter_name?:string|null; question_count:number; max_score:number; attempt_count:number; review_count:number; assigned_grader_names?:string|null; };
type Staff = {staff_id:number;display_name:string;role:string;can_grade:boolean};
type ReviewQuestion = {attemptQuestionId:number;answerId:number;question_type:string;prompt:string;explanation?:string;options:Array<{key:string;label:string}>;teacherOptions?:Array<{key:string;label:string;isCorrect:boolean}>;answerKey?:any;answer:any;answerStatus:string;maxMarks:number|string;awardedMarks?:number|string|null;isCorrect?:boolean|null;teacherFeedback?:string|null;manualReviewRequired?:boolean};
type ReviewAttempt = {attempt_id:number;assessment_id:number;test_id?:string|null;assessment_title:string;student_name:string;class_code:string;subject_name:string;status:string;final_score?:number|null;auto_score?:number|null;max_score:number;questions:ReviewQuestion[];audit?:Array<{id:number;actor_name:string;actor_role:string;action:string;previous_status?:string;new_status?:string;note?:string;occurred_at:string}>};

async function api(url:string,init:RequestInit={}) {
  const headers=new Headers(init.headers||{});
  if(init.body && !(init.body instanceof FormData))headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok)throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));
  return body;
}

function fmtDate(value?:string|null,locale:string='hinglish'){if(!value)return '—';const date=new Date(value);return Number.isNaN(date.getTime())?'—':date.toLocaleString(getUiLocaleLanguage(locale)+'-IN');}
type Translator=(key:string,values?:Record<string,string|number>)=>string;
function answerText(value:any,noAnswerText:string){if(value===null||value===undefined||value==='')return noAnswerText;if(typeof value==='string'){try{return JSON.stringify(JSON.parse(value),null,2)}catch{return value}}try{return JSON.stringify(value,null,2)}catch{return String(value)}}
const STATUS_KEYS:Record<string,string>={DRAFT:'admin.assessments.statusDraft',PUBLISHED:'admin.assessments.statusPublished',ARCHIVED:'admin.assessments.statusArchived',AWAITING_REVIEW:'admin.assessments.statusAwaitingReview',GRADED:'admin.assessments.statusGraded',RELEASED:'admin.assessments.statusReleased',IN_PROGRESS:'admin.assessments.statusInProgress',ISSUED:'admin.assessments.statusIssued',REVOKED:'admin.assessments.statusRevoked'};
const AUDIT_ACTION_KEYS:Record<string,string>={ASSESSMENT_CREATED:'admin.assessments.auditActionCreated',ASSESSMENT_STATUS_CHANGED:'admin.assessments.auditActionStatusChanged',GRADER_ASSIGNED:'admin.assessments.auditActionGraderAssigned',GRADING_SAVED:'admin.assessments.auditActionGradingSaved',RESULT_RELEASED:'admin.assessments.auditActionResultReleased'};
function statusText(status:string,t:Translator){const key=STATUS_KEYS[status];return key?t(key):t('admin.assessments.statusUnknown',{status});}
function auditActionText(action:string,t:Translator){const key=AUDIT_ACTION_KEYS[action];return key?t(key):action.replaceAll('_',' ').toLowerCase();}
function referenceAnswerText(value:any,t:Translator){
  let parsed=value;
  if(typeof value==='string'){try{parsed=JSON.parse(value)}catch{return value}}
  if(parsed&&typeof parsed==='object'&&!Array.isArray(parsed)){
    if(parsed.kind==='TEXT')return String(parsed.value??'');
    if(parsed.kind==='NUMERIC')return parsed.tolerance!==undefined?t('admin.assessments.expectedNumericWithTolerance',{value:String(parsed.value??''),tolerance:String(parsed.tolerance)}):t('admin.assessments.expectedNumericAnswer',{value:String(parsed.value??'')});
    if(parsed.kind==='OPTION')return t('admin.assessments.correctOption',{value:String(parsed.value??'')});
  }
  if(typeof parsed==='string')return parsed;
  try{return JSON.stringify(parsed,null,2)}catch{return String(parsed)}
}

export default function AssessmentsPage(){
  const {locale}=useContext(AdminWorkspaceContext);
  const t=useUiTranslation();
  const tRef=useRef(t);
  useEffect(()=>{tRef.current=t;},[t]);
  const { courses, courseId, activeCourse, loadingCourses, courseError, selectCourse } = useAdminCourseScope();
  const [classCode,setClassCode]=useState('');
  const [subjectCode,setSubjectCode]=useState('maths');
  const [subjectOptions,setSubjectOptions]=useState<Array<{code:string;name:string}>>([]);
  const [chapterId,setChapterId]=useState('');
  const [statusFilter,setStatusFilter]=useState('ALL');
  const [assessments,setAssessments]=useState<Assessment[]>([]);
  const [assessmentPage,setAssessmentPage]=useState(0);
  const [assessmentTotal,setAssessmentTotal]=useState(0);
  const [assessmentPageSize,setAssessmentPageSize]=useState(10);
  const [bank,setBank]=useState<BankQuestion[]>([]);
  const [bankPage,setBankPage]=useState(0);
  const [bankTotal,setBankTotal]=useState(0);
  const [bankPageSize,setBankPageSize]=useState(10);
  const [staff,setStaff]=useState<Staff[]>([]);
  const [chapters,setChapters]=useState<Array<{id:number;code:string;name:string}>>([]);
  const [selectedQuestionIds,setSelectedQuestionIds]=useState<number[]>([]);
  const [search,setSearch]=useState('');
  const [title,setTitle]=useState('');
  const [description,setDescription]=useState('');
  const [duration,setDuration]=useState('45');
  const [maxAttempts,setMaxAttempts]=useState('1');
  const [activeTab,setActiveTab]=useState<'author'|'review'>('author');
  const [queue,setQueue]=useState<any[]>([]);
  const [queuePage,setQueuePage]=useState(0);
  const [queueTotal,setQueueTotal]=useState(0);
  const [queuePageSize,setQueuePageSize]=useState(10);
  const [queueStatus,setQueueStatus]=useState('AWAITING_REVIEW');
  const [query,setQuery]=useState('');
  const [review,setReview]=useState<ReviewAttempt|null>(null);
  const [reviewCertificate,setReviewCertificate]=useState<any>(null);
  const [reviewCertificateLoading,setReviewCertificateLoading]=useState(false);
  const [reviewCertificateLookupFailed,setReviewCertificateLookupFailed]=useState(false);
  const [marks,setMarks]=useState<Record<number,string>>({});
  const [feedback,setFeedback]=useState<Record<number,string>>({});
  const [overallNote,setOverallNote]=useState('');
  const [graderByAssessment,setGraderByAssessment]=useState<Record<number,string>>({});
  const [loading,setLoading]=useState(true);
  const [bankLoading,setBankLoading]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [packageJsonText,setPackageJsonText]=useState('');
  const [packageObject,setPackageObject]=useState<any|null>(null);
  const [packageFileName,setPackageFileName]=useState('');
  const [packagePreview,setPackagePreview]=useState<any|null>(null);
  const [packageBusy,setPackageBusy]=useState(false);
  const [importedReviewId,setImportedReviewId]=useState<number|null>(null);
  const [importedReviewItems,setImportedReviewItems]=useState<Array<{sequenceNo:number;maxMarks:number|string;question:any}>>([]);
  const [importedAnswerEdits,setImportedAnswerEdits]=useState<Record<number,string>>({});
  const [importedExplanationEdits,setImportedExplanationEdits]=useState<Record<number,string>>({});

  const eligibleStaff=staff.filter(s=>s.can_grade);
  const filteredBank=bank;

  const loadAssessments=useCallback(async()=>{
    if(!courseId){setAssessments([]);setAssessmentTotal(0);setChapters([]);setLoading(courses.length===0);return;}
    setLoading(true);setError('');
    try{
      const params=new URLSearchParams({classCode,subjectCode,courseId,status:statusFilter,limit:String(assessmentPageSize),offset:String(assessmentPage*assessmentPageSize)});
      const [assessmentRows,staffRows,contentRows]=await Promise.all([
        api('/api/v1/admin/assessments?'+params.toString()),
        api('/api/v1/admin/assessment-staff'),
        api('/api/v1/admin/content?courseId='+encodeURIComponent(courseId))
      ]);
      setAssessments(Array.isArray(assessmentRows?.items)?assessmentRows.items:(Array.isArray(assessmentRows)?assessmentRows:[]));
      setAssessmentTotal(Number(assessmentRows?.total??(Array.isArray(assessmentRows)?assessmentRows.length:0)));
      setStaff(Array.isArray(staffRows)?staffRows:[]);
      const subjectMap=new Map<string,{code:string;name:string}>();
      const chapterMap=new Map<number,{id:number;code:string;name:string}>();
      (Array.isArray(contentRows)?contentRows:[]).forEach((row:any)=>{
        if(row.subject_code)subjectMap.set(String(row.subject_code),{code:String(row.subject_code),name:String(row.subject_name||row.subject_code)});
        const id=Number(row.chapter_id);
        if(id>0&&row.chapter_name)chapterMap.set(id,{id,code:String(row.chapter_code||''),name:String(row.chapter_name)});
      });
      const nextSubjects=[...subjectMap.values()];setSubjectOptions(nextSubjects);
      setChapters([...chapterMap.values()]);
      setChapterId(current=>current&&chapterMap.has(Number(current))?current:'');
      if(nextSubjects.length===0)setSubjectCode('');
       if(nextSubjects.length&&!nextSubjects.some(item=>item.code===subjectCode))setSubjectCode(nextSubjects[0].code);
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.loadFailed'));}
    finally{setLoading(false);}
  },[classCode,subjectCode,courseId,courses.length,statusFilter,assessmentPage,assessmentPageSize]);

  const loadBank=useCallback(async(nextPage=bankPage,nextPageSize=bankPageSize)=>{
    if(!courseId){setBank([]);setBankTotal(0);setBankLoading(false);return;}
    setBankLoading(true);setError('');
    try{
      const params=new URLSearchParams({classCode,subjectCode,courseId,limit:String(nextPageSize),offset:String(nextPage*nextPageSize)});
      if(chapterId)params.set('chapterId',chapterId);
      if(search.trim())params.set('query',search.trim());
      const data=await api('/api/v1/admin/assessment-question-bank?'+params.toString());
      setBank(Array.isArray(data?.items)?data.items:(Array.isArray(data)?data:[]));
      setBankTotal(Number(data?.total??(Array.isArray(data)?data.length:0)));
      setBankPage(nextPage);
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.bankLoadFailed'));}
    finally{setBankLoading(false);}
  },[classCode,subjectCode,courseId,chapterId,search,bankPage,bankPageSize]);

  const loadQueue=useCallback(async(nextPage=queuePage,nextPageSize=queuePageSize)=>{
    if(!courseId){setQueue([]);setQueueTotal(0);setLoading(courses.length===0);return;}
    setLoading(true);setError('');
    try{
      const params=new URLSearchParams({courseId,status:queueStatus,limit:String(nextPageSize),offset:String(nextPage*nextPageSize)});
      if(query.trim())params.set('query',query.trim());
      const data=await api('/api/v1/admin/assessment-reviews?'+params.toString());
      setQueue(Array.isArray(data?.items)?data.items:(Array.isArray(data)?data:[]));
      setQueueTotal(Number(data?.total??(Array.isArray(data)?data.length:0)));
      setQueuePage(nextPage);
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.queueLoadFailed'));}
    finally{setLoading(false);}
  },[courseId,courses.length,queueStatus,query,queuePage,queuePageSize]);

  useEffect(()=>{if(activeCourse?.curriculum_class_code)setClassCode(String(activeCourse.curriculum_class_code));else setClassCode('');},[activeCourse]);
  useEffect(()=>{void loadAssessments();},[loadAssessments]);
  useEffect(()=>{if(activeTab==='review')void loadQueue();},[activeTab,loadQueue]);

  async function readPackageFile(file?:File|null) {
    if(!file)return;
    setError('');setNotice('');setPackagePreview(null);setPackageObject(null);
    if(file.size>5_000_000) {
      setError('JSON package is larger than 5 MB. Keep media files separate and reference them as resources.');
      return;
    }
    try {
      const text=await file.text();
      const parsed=JSON.parse(text);
      if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))throw new Error('The JSON root must be an object.');
      setPackageJsonText(JSON.stringify(parsed,null,2));
      setPackageObject(parsed);
      setPackageFileName(file.name);
      setNotice('JSON loaded locally. Validate it against the selected source course before importing.');
    } catch(e) {
      setPackageJsonText('');
      setPackageObject(null);
      setPackageFileName(file.name);
      setError(e instanceof Error?'Invalid JSON: '+e.message:'The selected file is not valid JSON.');
    }
  }
  function parsePackageDraft() {
    try {
      const parsed=JSON.parse(packageJsonText);
      if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))throw new Error('The JSON root must be an object.');
      setPackageObject(parsed);
      return parsed;
    } catch(e) {
      setError(e instanceof Error?'Invalid JSON: '+e.message:'The package JSON could not be parsed.');
      setPackageObject(null);setPackagePreview(null);
      return null;
    }
  }
  async function validatePackageDraft() {
    if(!courseId){setError('Choose a course before validating an assessment package.');return;}
    const pkg=parsePackageDraft();if(!pkg)return;
    setPackageBusy(true);setError('');setNotice('');setPackagePreview(null);
    try {
      const target:any={courseId:Number(courseId),package:pkg};
      if(subjectOptions.some(item=>item.code===subjectCode))target.subjectCode=subjectCode;
      if(chapterId)target.chapterId=Number(chapterId);
      const preview=await api('/api/v1/admin/assessments/validate-package',{method:'POST',body:JSON.stringify(target)});
      setPackagePreview(preview);
      if(preview.valid)setNotice('Validation passed. Import creates a versioned draft pack and will not publish anything.');
      else setError('Package validation found '+preview.issues.length+' issue(s). Correct the JSON paths below, then validate again.');
    } catch(e) {
      setError(e instanceof Error?e.message:'The assessment package could not be validated.');
    } finally {setPackageBusy(false);}
  }
  async function loadImportedPackageQuestions(assessmentId:number) {
    if(!courseId)return;
    const data=await api('/api/v1/admin/assessments/'+assessmentId+'/package-questions?courseId='+encodeURIComponent(courseId));
    const items=Array.isArray(data?.items)?data.items:[];
    setImportedReviewId(assessmentId);setImportedReviewItems(items);
    const answerValues:Record<number,string>={},explanationValues:Record<number,string>={};
    items.forEach((item:any)=>{
      const sequence=Number(item.sequenceNo);
      answerValues[sequence]=JSON.stringify(item.question?.answer_payload||{kind:'UNRESOLVED',value:''},null,2);
      explanationValues[sequence]=String(item.question?.explanation||'');
    });
    setImportedAnswerEdits(answerValues);setImportedExplanationEdits(explanationValues);
  }
  async function importPackageDraft() {
    if(!courseId){setError('Choose a source course before importing.');return;}
    const pkg=packageObject||parsePackageDraft();if(!pkg)return;
    if(!packagePreview?.valid){setError('Validate this exact JSON package and source-course binding before importing.');return;}
    setPackageBusy(true);setError('');setNotice('');
    try {
      const target:any={courseId:Number(courseId),package:pkg};
      if(subjectOptions.some(item=>item.code===subjectCode))target.subjectCode=subjectCode;
      if(chapterId)target.chapterId=Number(chapterId);
      const result=await api('/api/v1/admin/assessments/import-package',{method:'POST',body:JSON.stringify(target)});
      const importedId=Number(result?.assessment_id||result?.assessmentId||0);
      if(!importedId)throw new Error('The server did not return the imported assessment ID.');
      setNotice((result?.idempotent?'This identical package version was already imported.':'Imported '+Number(result?.question_count||result?.questionCount||0)+' questions.')+
        ' The test remains a draft; answer keys and explanations require review before publication.');
      setPackagePreview(null);
      await loadAssessments();
      await loadImportedPackageQuestions(importedId);
    } catch(e) {
      setError(e instanceof Error?e.message:'The assessment package could not be imported.');
    } finally {setPackageBusy(false);}
  }
  async function saveImportedQuestionReview(sequenceNo:number,reviewStatus:'APPROVED'|'REJECTED'|'PENDING_HUMAN_REVIEW') {
    if(!courseId||!importedReviewId)return;
    let answerPayload:any;
    try {
      answerPayload=JSON.parse(importedAnswerEdits[sequenceNo]||'{}');
      if(!answerPayload||typeof answerPayload!=='object'||Array.isArray(answerPayload))throw new Error('Answer payload must be a JSON object.');
    } catch(e) {
      setError(e instanceof Error?'Question '+sequenceNo+': '+e.message:'Answer payload JSON is invalid.');
      return;
    }
    setPackageBusy(true);setError('');setNotice('');
    try {
      const payload={reviewStatus,answerPayload,explanation:importedExplanationEdits[sequenceNo]||''};
      const result=await api('/api/v1/admin/assessments/'+importedReviewId+'/package-questions/'+sequenceNo+'/review?courseId='+encodeURIComponent(courseId),{
        method:'PATCH',body:JSON.stringify(payload)
      });
      setNotice('Question '+sequenceNo+' marked '+reviewStatus+'. '+result.approvedQuestionCount+' of '+result.questionCount+' questions approved.');
      await loadImportedPackageQuestions(importedReviewId);
      await loadAssessments();
    } catch(e) {
      setError(e instanceof Error?e.message:'Question review could not be saved.');
    } finally {setPackageBusy(false);}
  }

  function toggleQuestion(id:number,checked:boolean){setSelectedQuestionIds(ids=>checked?[...ids,id]:ids.filter(x=>x!==id));}
  async function createAssessment(){
    if(!title.trim()){setError(tRef.current('admin.assessments.titleRequired'));return;}
    if(!selectedQuestionIds.length){setError(tRef.current('admin.assessments.questionRequired'));return;}
    setBusy(true);setError('');setNotice('');
    try{
      const row=await api('/api/v1/admin/assessments',{method:'POST',body:JSON.stringify({
        title:title.trim(),description:description.trim(),classCode,subjectCode,courseId:Number(courseId),
        chapterId:chapterId?Number(chapterId):null,
        durationMinutes:Number(duration),maxAttempts:Number(maxAttempts),questionIds:selectedQuestionIds
      })});
      setTitle('');setDescription('');setSelectedQuestionIds([]);
      setNotice(tRef.current('admin.assessments.draftCreated',{count:row.question_count}));
      await loadAssessments();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.createFailed'));}
    finally{setBusy(false);}
  }
  async function changeStatus(row:Assessment,status:'PUBLISHED'|'ARCHIVED'|'DRAFT'){
    setBusy(true);setError('');setNotice('');
    try{await api('/api/v1/admin/assessments/'+row.assessment_id+'/status?courseId='+encodeURIComponent(courseId),{method:'PATCH',body:JSON.stringify({status})});setNotice(tRef.current('admin.assessments.statusUpdated',{title:row.title,status:statusText(status,tRef.current)}));await loadAssessments();}
    catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.statusUpdateFailed'));}
    finally{setBusy(false);}
  }
  async function changeOfflinePolicy(row:Assessment,offlineEnabled:boolean){
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/admin/assessments/'+row.assessment_id+'/offline-policy?courseId='+encodeURIComponent(courseId),{
        method:'PATCH',body:JSON.stringify({offlineEnabled})
      });
      setNotice(tRef.current(offlineEnabled?'admin.assessments.offlineEnabledNotice':'admin.assessments.offlineDisabledNotice',{title:row.title}));
      await loadAssessments();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.offlinePolicyFailed'));}
    finally{setBusy(false);}
  }
  async function assignGrader(row:Assessment){
    const staffId=Number(graderByAssessment[row.assessment_id]||0);
    if(!staffId){setError(tRef.current('admin.assessments.graderRequired'));return;}
    setBusy(true);setError('');setNotice('');
    try{await api('/api/v1/admin/assessments/'+row.assessment_id+'/graders?courseId='+encodeURIComponent(courseId),{method:'POST',body:JSON.stringify({staffId})});setNotice(tRef.current('admin.assessments.graderAssigned',{title:row.title}));await loadAssessments();}
    catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.graderAssignmentFailed'));}
    finally{setBusy(false);}
  }
  async function loadReviewCertificate(attemptId:number){
    setReviewCertificateLoading(true);
    setReviewCertificateLookupFailed(false);
    try{
      const data=await api('/api/v1/admin/certificates/for-attempt/'+attemptId+'?courseId='+encodeURIComponent(String(courseId)));
      setReviewCertificate(data?.issued?data.certificate:null);
    }catch{
      setReviewCertificate(null);
      setReviewCertificateLookupFailed(true);
    }finally{setReviewCertificateLoading(false);}
  }

  async function openReview(attemptId:number){
    setBusy(true);setError('');setNotice('');setReviewCertificate(null);
    try{
      const data=await api('/api/v1/admin/assessment-attempts/'+attemptId+'?courseId='+encodeURIComponent(courseId));
      setReview(data);
      const nextMarks:Record<number,string>={},nextFeedback:Record<number,string>={};
      (data.questions||[]).forEach((q:ReviewQuestion)=>{if(q.manualReviewRequired){nextMarks[q.answerId]=q.awardedMarks===null||q.awardedMarks===undefined?'':String(q.awardedMarks);nextFeedback[q.answerId]=q.teacherFeedback||'';}});
      setMarks(nextMarks);setFeedback(nextFeedback);setOverallNote('');
      if(data.status==='RELEASED')await loadReviewCertificate(attemptId);
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.openAttemptFailed'));}
    finally{setBusy(false);}
  }
  async function saveGrading(){
    if(!review){return;}
    const pending=review.questions.filter(q=>q.manualReviewRequired);
    const answers=[];
    for(const q of pending){
      const value=marks[q.answerId];
      if(value===undefined||value.trim()===''){setError(tRef.current('admin.assessments.marksRequired'));return;}
      const number=Number(value);
      if(!Number.isFinite(number)||number<0||number>Number(q.maxMarks)){setError(tRef.current('admin.assessments.marksRange',{answerId:q.answerId,maxMarks:q.maxMarks}));return;}
      answers.push({answerId:q.answerId,marks:number,feedback:(feedback[q.answerId]||'').trim()});
    }
    setBusy(true);setError('');setNotice('');
    try{
      const data=await api('/api/v1/admin/assessment-attempts/'+review.attempt_id+'/grade?courseId='+encodeURIComponent(courseId),{
        method:'POST',body:JSON.stringify({answers,overallNote:overallNote.trim(),releaseResult:true})
      });
      setReview(data);
      const nextMarks:Record<number,string>={},nextFeedback:Record<number,string>={};
      (data.questions||[]).forEach((q:ReviewQuestion)=>{if(q.manualReviewRequired){nextMarks[q.answerId]=q.awardedMarks===null||q.awardedMarks===undefined?'':String(q.awardedMarks);nextFeedback[q.answerId]=q.teacherFeedback||'';}});
      setMarks(nextMarks);setFeedback(nextFeedback);
      setNotice(tRef.current(data.status==='RELEASED'?'admin.assessments.allGradedReleased':'admin.assessments.savedPending'));
      if(data.status==='RELEASED')await loadReviewCertificate(data.attempt_id);
      await loadQueue();await loadAssessments();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.assessments.gradingSaveFailed'));}
    finally{setBusy(false);}
  }

  return <main className="admin-shell">
    <AdminSidebar active="assessments" variant="content"/>
    <section className={'admin-main '+styles.page}>
      <header className="admin-top"><div><span className="admin-kicker">{t('admin.assessments.eyebrow')}</span><h1>{t('admin.assessments.title')}</h1><p>{t('admin.assessments.description')}</p></div><button type="button" className="admin-refresh" onClick={()=>activeTab==='author'?void loadAssessments():void loadQueue()}>↻</button></header>
      <section className={styles.hero}>
        <div className={styles.heroIcon}>✓</div><div><span>{t('admin.assessments.heroEyebrow')}</span><h2>{t('admin.assessments.heroTitle')}</h2><p>{t('admin.assessments.heroDescription')}</p></div>
        <div className={styles.heroStats}><strong>{assessmentTotal}</strong><small>{t('admin.assessments.testsInFilter')}</small><strong>{assessments.reduce((sum,a)=>sum+Number(a.review_count||0),0)}</strong><small>{t('admin.assessments.attemptsAwaiting')}</small></div>
      </section>
      {error&&<div className={styles.error} role="alert">{error}</div>}
      {notice&&<div className={styles.success} role="status">{notice}</div>}
      <div className={styles.tabs} role="group" aria-label={t('admin.assessments.workspaceSections')}><button type="button" aria-pressed={activeTab==='author'} className={activeTab==='author'?styles.activeTab:''} onClick={()=>setActiveTab('author')}>{t('admin.assessments.buildPublishTests')}</button><button type="button" aria-pressed={activeTab==='review'} className={activeTab==='review'?styles.activeTab:''} onClick={()=>setActiveTab('review')}>{t('admin.assessments.teacherReviewQueueTab')} <span>{queue.filter(row=>row.status==='AWAITING_REVIEW').length}</span></button></div>

      {activeTab==='author'?<>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>PORTABLE AUTHORING</span><h2>Import test questions from JSON</h2><p>Load a structured test package, validate the selected source course, preview every issue, and import atomically as a draft. Publishing remains a separate review action.</p></div><b>JSON</b></div>
          <div className={styles.fields}>
            <label className={styles.wide}>JSON file (maximum 5 MB)
              <input type="file" accept=".json,application/json" onChange={event=>void readPackageFile(event.currentTarget.files?.[0]||null)}/>
            </label>
            {packageFileName?<p className={styles.helpNote}>Loaded file: {packageFileName}</p>:null}
            <label className={styles.wide}>Portable assessment JSON
              <textarea rows={12} spellCheck={false} value={packageJsonText} onChange={event=>{setPackageJsonText(event.target.value);setPackageObject(null);setPackagePreview(null);}} placeholder='Paste a quantaedge.portable-assessment-package.v1 object here…'/>
            </label>
          </div>
          <div className={styles.submitBar}>
            <p>{courseId?'Bound to selected course: '+(activeCourse?.display_name||courseId)+'. JSON cannot override the tenant or course identity.':'Select a course above before validating or importing.'}</p>
            <button type="button" className={styles.secondaryButton} disabled={packageBusy||!courseId||!packageJsonText.trim()} onClick={()=>void validatePackageDraft()}>{packageBusy?'Validating…':'Validate & preview'}</button>
            <button type="button" className={styles.primaryButton} disabled={packageBusy||!courseId||!packagePreview?.valid} onClick={()=>void importPackageDraft()}>{packageBusy?'Importing…':'Import draft test'}</button>
          </div>
          {packagePreview&&<div className={styles.bank}>
            <div className={styles.bankHeader}><div><b>{packagePreview.valid?'Preview is valid':'Preview needs corrections'}</b><small>{packagePreview.title} · {packagePreview.questionCount} questions · {packagePreview.sectionCount} sections</small></div><span className={styles.statusDRAFT}>{packagePreview.valid?'DRAFT ONLY':'FIX ISSUES'}</span></div>
            <div className={styles.resultStats}>
              <div><small>Assessment type</small><b>{packagePreview.assessmentType||'—'}</b></div>
              <div><small>Duration</small><b>{packagePreview.durationMinutes} minutes</b></div>
              <div><small>Attempts</small><b>{packagePreview.maxAttempts}</b></div>
            </div>
            {Array.isArray(packagePreview.issues)&&packagePreview.issues.length>0
              ?<ol className={styles.helpNote}>{packagePreview.issues.map((issue:string,index:number)=><li key={index}>{issue}</li>)}</ol>
              :<p className={styles.helpNote}>Structural checks passed. The server allocates database IDs and saves immutable question snapshots. Answers remain under human review.</p>}
          </div>}
          {importedReviewId&&<div className={styles.bank}>
            <div className={styles.panelHeading}><div><span>IMPORTED DRAFT #{importedReviewId}</span><h3>Review questions and answer keys</h3><p>Check each answer and explanation. The test cannot publish while questions remain unresolved or unapproved.</p></div><button type="button" className={styles.secondaryButton} disabled={packageBusy} onClick={()=>void loadImportedPackageQuestions(importedReviewId)}>Refresh</button></div>
            <p className={styles.helpNote}>{importedReviewItems.filter(item=>item.question?.question_review_status==='APPROVED').length} of {importedReviewItems.length} approved. Keep this test in Draft until every question has been reviewed.</p>
            <div className={styles.testList}>{importedReviewItems.map(item=>{
              const seq=Number(item.sequenceNo),question=item.question||{},options=Array.isArray(question.options)?question.options:[];
              return <article className={styles.testCard} key={seq}>
                <div className={styles.testBody}>
                  <div className={styles.testTitle}><h4>Question {seq}</h4><span className={styles.statusDRAFT}>{String(question.question_review_status||'PENDING_HUMAN_REVIEW')}</span></div>
                  <p>{String(question.prompt||'Prompt not supplied')}</p>
                  {question.prompt_i18n&&<details><summary>Localized prompt</summary><pre>{JSON.stringify(question.prompt_i18n,null,2)}</pre></details>}
                  {options.length>0&&<ul className={styles.helpNote}>{options.map((option:any,index:number)=><li key={String(option.key||option.option_key||index)}>{String(option.key||option.option_key||'?')}. {String(option.label||option.text||'')}</li>)}</ul>}
                  <label className={styles.wide}>Answer payload JSON
                    <textarea rows={3} spellCheck={false} value={importedAnswerEdits[seq]??JSON.stringify(question.answer_payload||{kind:'UNRESOLVED',value:''},null,2)} onChange={event=>setImportedAnswerEdits(current=>({...current,[seq]:event.target.value}))} placeholder='{"kind":"OPTION","value":"B"} or {"kind":"NUMERIC","value":12,"tolerance":0.1}'/>
                  </label>
                  <label className={styles.wide}>Explanation
                    <textarea rows={3} value={importedExplanationEdits[seq]??String(question.explanation||'')} onChange={event=>setImportedExplanationEdits(current=>({...current,[seq]:event.target.value}))} placeholder="Explain why this answer is correct."/>
                  </label>
                  <div className={styles.testActions}>
                    <button type="button" className={styles.secondaryButton} disabled={packageBusy} onClick={()=>void saveImportedQuestionReview(seq,'PENDING_HUMAN_REVIEW')}>Save changes</button>
                    <button type="button" className={styles.secondaryButton} disabled={packageBusy} onClick={()=>void saveImportedQuestionReview(seq,'REJECTED')}>Reject question</button>
                    <button type="button" className={styles.primaryButton} disabled={packageBusy} onClick={()=>void saveImportedQuestionReview(seq,'APPROVED')}>Approve answer</button>
                  </div>
                </div>
              </article>;
            })}</div>
          </div>}
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>{t('admin.assessments.step01')}</span><h2>{t('admin.assessments.chooseCurriculum')}</h2><p>{t('admin.assessments.chooseCurriculumDescription')}</p></div><b>01</b></div>
          <div className={styles.fields}>
            <AdminCourseSelector courses={courses} courseId={courseId} loading={loadingCourses} label={t('admin.assessments.course')} placeholder={loadingCourses?t('admin.assessments.chooseCurriculum'):t('admin.assessments.noCoursesAssigned')} onChange={value=>{if(selectCourse(value)){setChapterId('');setBank([]);setBankTotal(0);setBankPage(0);setSelectedQuestionIds([]);setAssessmentPage(0);setQueuePage(0);setReview(null);}}} disabled={Boolean(courseError)} />
            <label>{t('admin.assessments.subject')}<select value={subjectCode} onChange={e=>{setSubjectCode(e.target.value);setChapterId('');setBank([]);setBankTotal(0);setBankPage(0);setSelectedQuestionIds([]);setAssessmentPage(0);}} disabled={!subjectOptions.length}>{subjectOptions.map(subject=><option key={subject.code} value={subject.code}>{subject.name}</option>)}</select></label>
            <label>{t('admin.assessments.chapterFilterOptional')}<select value={chapterId} onChange={e=>{setChapterId(e.target.value);setBank([]);setBankTotal(0);setBankPage(0);setSelectedQuestionIds([]);}}><option value="">{t('admin.assessments.anyPublishedChapter')}</option>{chapters.map(ch=><option key={ch.id} value={ch.id}>{ch.code} — {ch.name}</option>)}</select></label>
            <button type="button" className={styles.secondaryButton} onClick={()=>void loadBank()} disabled={bankLoading}>{bankLoading?t('admin.assessments.loading'):t('admin.assessments.loadSearchQuestionBank')}</button>
          </div>
          {bank.length>0&&<div className={styles.bank}>
            <div className={styles.bankHeader}><div><b>{t('admin.assessments.approvedQuestionBank')}</b><small>{t('admin.assessments.matchingSelected',{matching:bankTotal,selected:selectedQuestionIds.length})}</small></div><input aria-label={t('admin.assessments.searchApprovedQuestions')} value={search} onChange={e=>{setSearch(e.target.value);setBankPage(0)}} placeholder={t('admin.assessments.searchQuestionChapter')}/></div>
            <div className={styles.bankList}>{filteredBank.map(q=><label className={styles.bankRow} key={q.question_id}><input type="checkbox" checked={selectedQuestionIds.includes(q.question_id)} onChange={e=>toggleQuestion(q.question_id,e.target.checked)}/><span><b>{q.prompt}</b><small>{q.chapter_name} · {q.lesson_title} · {q.question_type} · {q.marks||1} mark(s)</small></span><code>#{q.question_id}</code></label>)}</div>
            <PremiumPagination page={bankPage} total={bankTotal} pageSize={bankPageSize}
              onPageChange={nextPage=>void loadBank(nextPage)} onPageSizeChange={size=>{setBankPageSize(size);void loadBank(0,size);}}
              itemLabel="approved questions" loading={bankLoading} />
            <p className={styles.helpNote}>{t('admin.assessments.answerKeyServerNote')}</p>
          </div>}
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>{t('admin.assessments.step02')}</span><h2>{t('admin.assessments.testDetails')}</h2><p>{t('admin.assessments.testDetailsDescription')}</p></div><b>02</b></div>
          <div className={styles.fields}>
            <label className={styles.wide}>{t('admin.assessments.testTitle')}<input value={title} onChange={e=>setTitle(e.target.value)} placeholder={t('admin.assessments.testTitlePlaceholder')}/></label>
            <label className={styles.wide}>{t('admin.assessments.instructionsDescription')}<textarea value={description} onChange={e=>setDescription(e.target.value)} rows={3} placeholder={t('admin.assessments.instructionsPlaceholder')}/></label>
            <label>{t('admin.assessments.durationMinutes')}<input type="number" min="1" max="240" value={duration} onChange={e=>setDuration(e.target.value)}/></label>
            <label>{t('admin.assessments.attemptsAllowed')}<input type="number" min="1" max="10" value={maxAttempts} onChange={e=>setMaxAttempts(e.target.value)}/></label>
          </div>
          <div className={styles.submitBar}><p>{t('admin.assessments.selectedQuestionsSummary',{count:selectedQuestionIds.length})}</p><button className={styles.primaryButton} type="button" onClick={()=>void createAssessment()} disabled={busy||!courseId||!title.trim()||selectedQuestionIds.length===0}>{busy?'Saving…':t('admin.assessments.createDraftTest')}</button></div>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>{t('admin.assessments.governance')}</span><h2>{t('admin.assessments.testCatalogue')}</h2><p>{t('admin.assessments.catalogueDescription')}</p></div><select className={styles.filter} value={statusFilter} onChange={e=>{setStatusFilter(e.target.value);setAssessmentPage(0)}}><option value="ALL">{t('admin.assessments.allStatuses')}</option><option value="DRAFT">{t('admin.assessments.draftsOnly')}</option><option value="PUBLISHED">{t('admin.assessments.publishedOnly')}</option><option value="ARCHIVED">{t('admin.assessments.archivedOnly')}</option></select></div>
          <div className={styles.fields+' '+styles.compactFields}><AdminCourseSelector courses={courses} courseId={courseId} loading={loadingCourses} label={t('admin.assessments.course')} placeholder={t('admin.assessments.noCoursesAssigned')} onChange={value=>{if(selectCourse(value)){setAssessmentPage(0);setQueuePage(0);setReview(null);}}} disabled={Boolean(courseError)} /><label>{t('admin.assessments.subject')}<select value={subjectCode} onChange={e=>{setSubjectCode(e.target.value);setAssessmentPage(0)}} disabled={!subjectOptions.length}>{subjectOptions.map(subject=><option key={subject.code} value={subject.code}>{subject.name}</option>)}</select></label></div>
          {loading?<p className={styles.empty}>{t('admin.assessments.loadingTests')}</p>:assessments.length===0?<div className={styles.empty}><b>{t('admin.assessments.noAssessmentsFilter')}</b><span>{t('admin.assessments.createDraftHint')}</span></div>:<div className={styles.testList}>{assessments.map(row=><article className={styles.testCard} key={row.assessment_id}>
            <div className={styles.testIcon}>✓</div><div className={styles.testBody}><div className={styles.testTitle}><h3>{row.title}</h3>{row.test_id&&<small>{t('admin.assessments.testId',{testId:row.test_id})}</small>}<span className={styles['status'+row.status]}>{statusText(row.status,t)}</span></div><p>{activeCourse?.display_name || t('admin.assessments.course')} · {row.subject_name}{row.chapter_name?' · '+row.chapter_name:''}</p><small>{row.is_generated?(row.assessment_type==='TOPIC_END'?t('admin.assessments.systemTopicEnd'):t('admin.assessments.systemChapterEnd')):t('admin.assessments.adminCreatedTest')}{row.is_generated?' · '+t('admin.assessments.version',{version:row.generation_version||1}):''}</small><small>{t('admin.assessments.questionMarksDurationAttempts',{questions:row.question_count,marks:row.max_score,minutes:row.duration_minutes,attempts:row.max_attempts})}</small><small>{t('admin.assessments.submittedStartedAwaiting',{submitted:row.attempt_count,awaiting:row.review_count})}</small><small>{t('admin.assessments.delivery')} {row.offline_enabled?t('admin.assessments.protectedOfflineEnabled'):t('admin.assessments.onlineOnly')}{row.offline_enabled?' · '+t('admin.assessments.encryptedLocalAnswersNote'):''}</small>
              <div className={styles.inlineAssign}><select aria-label={t('admin.assessments.assignGraderAria',{title:row.title})} value={graderByAssessment[row.assessment_id]||''} onChange={e=>setGraderByAssessment(m=>({...m,[row.assessment_id]:e.target.value}))}><option value="">{t('admin.assessments.assignPermittedStaffOption')}</option>{eligibleStaff.map(s=><option key={s.staff_id} value={s.staff_id}>{s.display_name} · {s.role}</option>)}</select><button type="button" disabled={busy||!eligibleStaff.length||!graderByAssessment[row.assessment_id]} onClick={()=>void assignGrader(row)}>{t('admin.assessments.assignChecker')}</button></div>
              {row.assigned_grader_names? <small>{t('admin.assessments.assignedNames',{names:row.assigned_grader_names})}</small>:null}
            </div><div className={styles.testActions}>{!row.is_generated&&row.status!=='ARCHIVED'&&<button className={styles.secondaryButton} type="button" disabled={busy} title={t('admin.assessments.offlineProtectionTooltip')} onClick={()=>void changeOfflinePolicy(row,!row.offline_enabled)}>{row.offline_enabled?t('admin.assessments.disableOffline'):t('admin.assessments.allowOffline')}</button>}{row.status==='DRAFT'&&<button className={styles.primaryButton} type="button" disabled={busy} onClick={()=>void changeStatus(row,'PUBLISHED')}>{t('admin.assessments.publishAction')}</button>}{row.status==='PUBLISHED'&&!row.is_generated&&<button className={styles.secondaryButton} type="button" disabled={busy} onClick={()=>void changeStatus(row,'ARCHIVED')}>{t('admin.assessments.archiveAction')}</button>}{row.status==='ARCHIVED'&&<button className={styles.secondaryButton} type="button" disabled={busy} onClick={()=>void changeStatus(row,'DRAFT')}>{t('admin.assessments.restoreDraft')}</button>}</div>
          </article>)}</div>}
          <PremiumPagination page={assessmentPage} total={assessmentTotal} pageSize={assessmentPageSize}
            onPageChange={setAssessmentPage} onPageSizeChange={size=>{setAssessmentPageSize(size);setAssessmentPage(0);}}
            itemLabel={t('admin.assessments.assessmentsItemLabel')} loading={loading} />
        </section>
      </>:<section className={styles.panel}>
        <div className={styles.panelHeading}><div><span>{t('admin.assessments.teacherReviewQueue')}</span><h2>{t('admin.assessments.submittedAwaitingDecision')}</h2><p>{t('admin.assessments.reviewQueueDescription')}</p></div><select className={styles.filter} value={queueStatus} onChange={e=>{setQueueStatus(e.target.value);setQueuePage(0)}}><option value="AWAITING_REVIEW">{t('admin.assessments.awaitingReview')}</option><option value="GRADED">{t('admin.assessments.gradedNotReleased')}</option><option value="RELEASED">{t('admin.assessments.releasedResults')}</option><option value="ALL">{t('admin.assessments.allStates')}</option></select></div>
        <div className={styles.queueSearch}><input aria-label={t('admin.assessments.searchTestStudentChapter')} value={query} onChange={e=>{setQuery(e.target.value);setQueuePage(0)}} placeholder={t('admin.assessments.searchTestStudentChapter')}/><button type="button" className={styles.secondaryButton} onClick={()=>void loadQueue()}>{t('admin.assessments.searchQueueButton')}</button></div>
        {loading?<p className={styles.empty}>{t('admin.assessments.loadingGradingQueue')}</p>:queue.length===0?<div className={styles.empty}><b>{t('admin.assessments.noSubmittedTests')}</b><span>{t('admin.assessments.newAttemptsQueueHint')}</span></div>:<div className={styles.testList}>{queue.map(row=><article className={styles.testCard} key={row.attempt_id}><div className={styles.testIcon}>✎</div><div className={styles.testBody}><div className={styles.testTitle}><h3>{row.assessment_title}</h3><span className={styles['status'+row.status]}>{statusText(row.status,t)}</span></div><p>{row.student_name} · {activeCourse?.display_name || t('admin.assessments.course')} · {row.subject_name}{row.chapter_name?' · '+row.chapter_name:''}</p><small>{t('admin.assessments.submittedAttemptDetails',{date:fmtDate(row.submitted_at,locale),attempt:row.attempt_number,questions:row.question_count,pending:row.pending_answers})}</small><small>{t('admin.assessments.resultLabel')} {row.status==='RELEASED'?row.final_score+' / '+row.max_score:t('admin.assessments.resultNotYetReleased')}</small></div><div className={styles.testActions}><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void openReview(row.attempt_id)}>{row.status==='AWAITING_REVIEW'?t('admin.assessments.reviewTest'):t('admin.assessments.openRecord')}</button></div></article>)}</div>}
        <PremiumPagination page={queuePage} total={queueTotal} pageSize={queuePageSize}
          onPageChange={setQueuePage} onPageSizeChange={size=>{setQueuePageSize(size);setQueuePage(0);}}
          itemLabel="submitted tests" loading={loading} />
      </section>}

      {review&&<div className={styles.modalBackdrop} role="presentation" onMouseDown={e=>{if(e.target===e.currentTarget)setReview(null);}}>
        <section className={styles.reviewModal} role="dialog" aria-modal="true" aria-labelledby="review-title">
          <div className={styles.modalHeader}><div><span>{t('admin.assessments.testLevelReview')}</span><h2 id="review-title">{review.assessment_title}</h2>{review.test_id&&<small>{t('admin.assessments.testId',{testId:review.test_id})}</small>}<p>{review.student_name} · {activeCourse?.display_name || t('admin.assessments.course')} · {statusText(review.status,t)}</p></div><button type="button" aria-label={t('admin.assessments.closeReview')} onClick={()=>setReview(null)}>×</button></div>
          <div className={styles.resultStats}><div><small>{t('admin.assessments.testStatus')}</small><b>{review.status}</b></div><div><small>{t('admin.assessments.maximumMarks')}</small><b>{review.max_score}</b></div><div><small>{t('admin.assessments.currentResult')}</small><b>{review.status==='RELEASED'?String(review.final_score):t('admin.assessments.notReleased')}</b></div></div>
          {review.status==='RELEASED'&&<section className={styles.certificatePanel} aria-live="polite">
            <div><span className={styles.certificateEyebrow}>{t('admin.assessments.certificateRecord')}</span><h3>{t('admin.assessments.certificateForResult')}</h3>
              <p>{t('admin.assessments.certificateEligibility')}</p></div>
            {reviewCertificateLoading?<small>{t('admin.assessments.checkingCertificateRecord')}</small>:reviewCertificate?(
              <div className={styles.certificateRecordActions}>
                <span className={reviewCertificate.status==='ISSUED'?styles.certificateValid:styles.certificateRevoked}>
                  {reviewCertificate.status==='ISSUED'?t('admin.assessments.statusIssuedValid'):t('admin.assessments.statusRevokedAudit')}
                </span>
                <small>{reviewCertificate.certificate_number}</small>
                <a href={'/api/v1/admin/certificates/'+reviewCertificate.id+'/download?courseId='+encodeURIComponent(courseId)+'&format=pdf'}>
                  {reviewCertificate.status==='ISSUED'?t('admin.assessments.viewDownloadPdf'):t('admin.assessments.auditCopyPdf')}
                </a>
                <a href={'/api/v1/admin/certificates/'+reviewCertificate.id+'/download?courseId='+encodeURIComponent(courseId)+'&format=png'}>
                  {reviewCertificate.status==='ISSUED'?t('admin.assessments.downloadPng'):t('admin.assessments.auditCopyPng')}
                </a>
                <button type="button" className={styles.secondaryButton}
                  onClick={()=>void loadReviewCertificate(review.attempt_id)}>{t('admin.assessments.refresh')}</button>
              </div>
            ):<div className={styles.certificateRecordActions}>
              <small>{reviewCertificateLookupFailed
                ? t('admin.assessments.certificateLookupFailed')
                : t('admin.assessments.noCertificateIssued')}</small>
              <button type="button" className={styles.secondaryButton}
                onClick={()=>void loadReviewCertificate(review.attempt_id)}>{t('admin.assessments.refreshCertificateStatus')}</button>
              <a href="/admin/certificates">{t('admin.assessments.openCertificateRegister')}</a>
            </div>}
          </section>}
          <div className={styles.reviewQuestions}>{review.questions.map((q,index)=><article key={q.attemptQuestionId} className={styles.reviewQuestion}><div className={styles.questionHeading}><span>{t('admin.assessments.questionNumberType',{index:index+1,type:q.question_type})}</span><b>{t('admin.assessments.marksCount',{count:q.maxMarks})}</b></div><h3>{q.prompt}</h3>{(q.teacherOptions?.length?q.teacherOptions:q.options)?.length>0&&<ul>{(q.teacherOptions?.length?q.teacherOptions:q.options).map(o=><li key={o.key}>{o.label}{'isCorrect' in o&&o.isCorrect?' · '+t('admin.assessments.correctAnswer'):''}</li>)}</ul>}{q.answerKey!==undefined&&q.answerKey!==null&&q.answerKey!==''&&<div className={styles.teacherReference}><small>{t('admin.assessments.referenceAnswerRubric')}</small><pre>{referenceAnswerText(q.answerKey,t)}</pre></div>}<div className={styles.studentAnswer}><small>{t('admin.assessments.studentSavedAnswer')}</small><p>{answerText(q.answer,t('admin.assessments.noAnswerSubmitted'))}</p></div>
            {q.manualReviewRequired&&<div className={styles.gradeFields}><label>{t('admin.assessments.marksAwardedRange',{maxMarks:q.maxMarks})}<input type="number" min="0" max={Number(q.maxMarks)} step="0.25" value={marks[q.answerId]??''} onChange={e=>setMarks(m=>({...m,[q.answerId]:e.target.value}))}/></label><label>{t('admin.assessments.feedbackVisible')}<textarea rows={2} maxLength={2000} value={feedback[q.answerId]??''} onChange={e=>setFeedback(m=>({...m,[q.answerId]:e.target.value}))} placeholder={t('admin.assessments.improvePlaceholder')}/></label></div>}
            {q.answerStatus==='AUTO_GRADED'&&<div className={[styles.autoGrade,q.isCorrect===true?styles.autoGradeCorrect:q.isCorrect===false?styles.autoGradeIncorrect:styles.autoGradeNeutral].join(' ')}>{t('admin.assessments.autoGradedMarks',{awarded:q.awardedMarks??0,maxMarks:q.maxMarks})}{q.isCorrect===true?' · '+t('admin.assessments.correct'):q.isCorrect===false?' · '+t('admin.assessments.incorrect'):''}</div>}{q.answerStatus==='GRADED'&&!q.manualReviewRequired&&<div className={[styles.autoGrade,q.isCorrect===true?styles.autoGradeCorrect:q.isCorrect===false?styles.autoGradeIncorrect:styles.autoGradeNeutral].join(' ')}>{t('admin.assessments.teacherCheckedMarks',{awarded:q.awardedMarks??0,maxMarks:q.maxMarks})}{q.isCorrect===true?' · '+t('admin.assessments.correct'):q.isCorrect===false?' · '+t('admin.assessments.incorrect'):''}</div>}
            {q.teacherFeedback&&!q.manualReviewRequired&&<p className={styles.feedbackText}>{q.teacherFeedback}</p>}
          </article>)}</div>
          <label className={styles.overallNote}>{t('admin.assessments.overallNoteOptional')}<textarea rows={2} maxLength={1200} value={overallNote} onChange={e=>setOverallNote(e.target.value)} placeholder={t('admin.assessments.auditNotePlaceholder')}/></label>
          {review.audit?.length?<section className={styles.auditBox}><div><span>{t('admin.assessments.auditTrail')}</span><h3>{t('admin.assessments.whoCheckedTest')}</h3><p>{t('admin.assessments.testLevelAuditOnly')}</p></div><div className={styles.auditList}>{review.audit.map(item=><article key={item.id}><span>{auditActionText(item.action,t)}</span><b>{item.actor_name} · {item.actor_role}</b><small>{fmtDate(item.occurred_at,locale)} · {item.previous_status||'—'} → {item.new_status||'—'}</small>{item.note&&<p>{item.note}</p>}</article>)}</div></section>:null}
          <div className={styles.modalFooter}><button type="button" className={styles.secondaryButton} onClick={()=>setReview(null)}>{t('admin.assessments.close')}</button>{review.status!=='RELEASED'&&review.status!=='IN_PROGRESS'&&<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void saveGrading()}>{busy?t('admin.assessments.saving'):t('admin.assessments.saveGradingRelease')}</button>}</div>
        </section>
      </div>}
    </section>
  </main>;
}
