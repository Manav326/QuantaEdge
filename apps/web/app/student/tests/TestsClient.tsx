'use client';

import { translateUi } from '../../i18n/catalog';
import { UiText } from '../../i18n/useUiTranslation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';
import PremiumPagination from '../../components/PremiumPagination';
import AnswerFeedback from '../../components/AnswerFeedback';
import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { formatUserTimestamp } from '../../components/formatUserTimestamp';
import styles from './Tests.module.css';

type AssessmentCard = {
  assessment_id:number;test_id?:string|null;title:string;description?:string;duration_minutes:number;max_attempts:number;
  attempts_used:number;question_count:number;max_score:number;subject_code:string;subject_name:string;
  assessment_type?:string;is_generated?:boolean;chapter_name?:string|null;in_progress_attempt_id?:number|null;latest_result_attempt_id?:number|null;
};
type AttemptQuestion = {
  attemptQuestionId:number;answerId:number;question_type:string;prompt:string;explanation?:string;
  options:Array<{key:string;label:string}>;answer:any;answerStatus:string;maxMarks:number|string;
  isCorrect?:boolean|null;awardedMarks?:number|string|null;teacherFeedback?:string|null;translated?:boolean;
  correctAnswer?:{kind?:string;value?:any}|null;
};
type Attempt = {
  attempt_id:number;assessment_id:number;test_id?:string|null;attempt_number:number;status:string;started_at:string;deadline_at:string;
  submitted_at?:string|null;released_at?:string|null;assessment_title:string;subject_name:string;chapter_name?:string|null;
  questions:AttemptQuestion[];resultReleased:boolean;translation_complete?:boolean;final_score?:number|string|null;auto_score?:number|string|null;max_score:number|string;
};
type ResultAttempt = {attempt_id:number;attempt_number:number;status:string;submitted_at?:string|null;released_at?:string|null;final_score:number|string;max_score:number|string};
type Result = {attempt_id:number;assessment_id:number;test_id?:string|null;attempt_number:number;status:string;submitted_at:string;released_at:string;final_score:number|string;max_score:number|string;best_score?:number|string;attempt_count?:number;attempts?:ResultAttempt[];assessment_title:string;assessment_type?:string;subject_name:string;chapter_name?:string|null;question_count:number};
type CertificateEligibility = {
  attempt_id:number;eligible:boolean;eligibility_reason?:string|null;certificate_id?:number|null;
  certificate_number?:string|null;certificate_status?:string|null;pass_threshold_percent?:number|string;
  class_code?:string;subject_code?:string;subject_name?:string;
};

function certificateTrackName(code: string | undefined, name: string | undefined, language: string) {
  const normalized = String(code || '').trim().toLowerCase();
  if (['maths', 'math', 'mathematics'].includes(normalized)) return language === 'hi' ? 'गणित' : 'Mathematics';
  if (normalized === 'science') return language === 'hi' ? 'विज्ञान' : 'Science';
  return name || code || '';
}

type LeaderboardEntry = {
  rank:number;
  student_name:string;
  score:number|string;
  max_score:number|string;
  time_taken_seconds:number;
  is_current_student:boolean;
};
type LeaderboardData = {
  assessment_id:number;
  assessment_title:string;
  max_score:number|string;
  participant_count:number;
  items:LeaderboardEntry[];
  current_student:LeaderboardEntry|null;
  ranking_criteria?:string[];
};

async function api(url:string,init:RequestInit={}) {
  const headers=new Headers(init.headers||{});
  headers.set('X-QE-Workspace','STUDENT');
  if(init.body)headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok){if(response.status===401||response.status===403)throw Object.assign(new Error('Student sign-in is required.'),{auth:true});throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));}
  return body;
}
function answerText(value:any){if(value===null||value===undefined)return '';if(typeof value==='string')return value;try{return JSON.stringify(value)}catch{return String(value)}}
function fmtDate(value?:string|null){return formatUserTimestamp(value)}
function clock(seconds:number){const safe=Math.max(0,seconds);return String(Math.floor(safe/60)).padStart(2,'0')+':'+String(safe%60).padStart(2,'0')}

function answerValues(value:any):string[]{
  if(value===null||value===undefined||value==='')return [];
  if(Array.isArray(value))return value.flatMap(answerValues);
  if(typeof value==='object'){
    if(Object.prototype.hasOwnProperty.call(value,'kind')&&Object.prototype.hasOwnProperty.call(value,'value'))return answerValues(value.value);
    return Object.values(value).flatMap(answerValues);
  }
  const text=String(value).trim();
  if(!text)return [];
  if((text.startsWith('[')&&text.endsWith(']'))||(text.startsWith('{')&&text.endsWith('}'))){
    try{const parsed=JSON.parse(text);if(parsed!==value)return answerValues(parsed);}catch{}
  }
  return [text];
}
function correctOptionKeys(question:AttemptQuestion):string[]{
  if(!question.correctAnswer?.value||!question.options?.length)return [];
  const values=new Set(answerValues(question.correctAnswer.value));
  return question.options.filter(option=>values.has(String(option.key))).map(option=>String(option.key));
}
function correctAnswerText(question:AttemptQuestion):string{
  const payload=question.correctAnswer;
  if(!payload||payload.value===null||payload.value===undefined)return '';
  const kind=String(payload.kind||'').toUpperCase();
  if(kind==='OPTION'||kind==='MULTI_OPTION'){
    const values=answerValues(payload.value);
    return values.map(value=>{
      const option=question.options?.find(item=>String(item.key)===value);
      return option?String(option.key)+'. '+option.label:value;
    }).join(', ');
  }
  return answerText(payload.value);
}
type QuestionContentProps={
  question:AttemptQuestion;response:any;review?:boolean;disabled?:boolean;onAnswer?:(value:any)=>void;
};
function QuestionContent({question,response,review=false,disabled=false,onAnswer}:QuestionContentProps){
  const {locale}=useLocale();
  const t=(english:string,hindi:string)=>locale==='english'?english:hindi;
  const selectedKeys=new Set(answerValues(response));
  const rightKeys=new Set(correctOptionKeys(question));
  const hasOptions=Array.isArray(question.options)&&question.options.length>0;
  const answerTone=review?(question.isCorrect===true?styles.answerFieldCorrect:question.isCorrect===false?styles.answerFieldIncorrect:styles.answerFieldNeutral):'';
  const correctText=review?correctAnswerText(question):'';
  return <>
    <h2>{question.prompt}</h2>
    {hasOptions&&<div className={styles.options}>{question.options.map(option=>{
      const key=String(option.key);
      const chosen=selectedKeys.has(key);
      const correct=rightKeys.has(key);
      const wrongChosen=review&&chosen&&question.isCorrect===false;
      const tone=wrongChosen?styles.optionIncorrect:review&&(correct||(chosen&&question.isCorrect===true))?styles.optionCorrect:review&&chosen?styles.optionNeutral:'';
      const hint=review&&chosen&&correct?translateUi(locale, 'legacy.web.your.answer.correct.answer.e22f5a2c')
        :review&&chosen&&question.isCorrect===false?translateUi(locale, 'legacy.web.your.recorded.answer.incorrect.f3522066')
        :review&&chosen&&question.isCorrect===true?translateUi(locale, 'legacy.web.your.recorded.answer.correct.d15f6d71')
        :review&&chosen?translateUi(locale, 'legacy.web.your.recorded.answer.a7e68292')
        :review&&correct?translateUi(locale, 'legacy.web.correct.answer.2a6f5308'):'';
      return <label key={option.key} className={[styles.option,!review&&chosen?styles.optionSelected:'',tone].filter(Boolean).join(' ')}>
        <input type="radio" name={'question-'+question.attemptQuestionId} value={key} checked={chosen} disabled={disabled||review} onChange={()=>onAnswer?.(option.key)}/>
        <span className={styles.optionKey}>{option.key}</span>
        <span className={styles.optionText}>{option.label}{hint&&<small className={wrongChosen?styles.optionHintIncorrect:correct||question.isCorrect===true?styles.optionHintCorrect:styles.optionHintNeutral}>{hint}</small>}</span>
        {review&&(chosen||correct)&&<span className={styles.optionOutcome}
          aria-label={wrongChosen?translateUi(locale, 'legacy.web.incorrect.answer.e6b805c5'):correct?translateUi(locale, 'legacy.web.correct.answer.2a6f5308'):question.isCorrect===true?translateUi(locale, 'legacy.web.correct.d01db0a2'):translateUi(locale, 'legacy.web.recorded.answer.02081560')}>
          {wrongChosen?'!':correct||question.isCorrect===true?'✓':'i'}
        </span>}
      </label>;
    })}</div>}
    {!hasOptions&&<label className={[styles.answerField,review?answerTone:''].filter(Boolean).join(' ')}>
      <span>{question.question_type==='NUMERICAL'?translateUi(locale, 'legacy.web.your.numerical.answer.27c93cf9'):translateUi(locale, 'legacy.web.your.answer.d70be76f')}</span>
      {question.question_type==='INPUT'||question.question_type==='NUMERICAL'
        ?<input value={answerText(response)} disabled={disabled||review} type={question.question_type==='NUMERICAL'?'number':'text'} onChange={event=>onAnswer?.(event.target.value)}
          placeholder={translateUi(locale, 'legacy.web.type.your.answer.here.a5f3d012')}/>
        :<textarea value={answerText(response)} disabled={disabled||review} rows={6} onChange={event=>onAnswer?.(event.target.value)}
          placeholder={['MATCH','ORDER','CASE_BASED','ASSERTION_REASON'].includes(question.question_type)
            ?translateUi(locale, 'legacy.web.write.your.answer.or.reasoning.clearly.f91bcdfd')
            :translateUi(locale, 'legacy.web.enter.your.answer.here.d508c830')}/>}
      {!review&&<small>{['INPUT','SHORT_ANSWER','LONG_ANSWER','CASE_BASED','SOURCE_BASED','DIAGRAM','MAP','ASSERTION_REASON','MATCH','ORDER'].includes(question.question_type)
        ?translateUi(locale, 'legacy.web.this.response.is.sent.for.staff.checki.78ad3ada')
        :''}</small>}
    </label>}
    {review&&correctText&&(!hasOptions||rightKeys.size===0)&&<div className={styles.correctAnswerBox}>
      <strong>{translateUi(locale, 'legacy.web.correct.answer.2a6f5308')}</strong><span>{correctText}</span>
    </div>}
  </>;
}
export default function TestsClient(){
  const router=useRouter();
  const searchParams=useSearchParams();
  const rawCourseId=searchParams.get('courseId')||'';
  const courseId=/^\d+$/.test(rawCourseId)&&Number(rawCourseId)>0?Number(rawCourseId):null;
  const {locale, learningLanguage, certificateLanguage}=useLocale();
  const language=learningLanguage;
  const uiLanguage=locale==='english'?'en':'hi';
  const t=(english:string,hindi:string)=>locale==='english'?english:hindi;
  const [tests,setTests]=useState<AssessmentCard[]>([]);
  const [testsPage,setTestsPage]=useState(0);
  const [testsTotal,setTestsTotal]=useState(0);
  const [testsPageSize,setTestsPageSize]=useState(10);
  const [results,setResults]=useState<Result[]>([]);
  const [resultsPage,setResultsPage]=useState(0);
  const [resultsTotal,setResultsTotal]=useState(0);
  const [resultsPageSize,setResultsPageSize]=useState(10);
  const [certificateEligibility,setCertificateEligibility]=useState<Record<number,CertificateEligibility>>({});
  const [issuingCertificateAttemptId,setIssuingCertificateAttemptId]=useState<number|null>(null);
  const [leaderboardTest,setLeaderboardTest]=useState<{assessment_id:number;title:string}|null>(null);
  const [leaderboardData,setLeaderboardData]=useState<LeaderboardData|null>(null);
  const [leaderboardLoading,setLeaderboardLoading]=useState(false);
  const [leaderboardError,setLeaderboardError]=useState('');
  const [tab,setTab]=useState<'tests'|'results'>('tests');
  const [attempt,setAttempt]=useState<Attempt|null>(null);
  const [responses,setResponses]=useState<Record<number,any>>({});
  const [index,setIndex]=useState(0);
  const [secondsLeft,setSecondsLeft]=useState<number|null>(null);
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState(false);
  const [saving,setSaving]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [subjectFilter,setSubjectFilter]=useState('ALL');
  const [dirtyAnswers,setDirtyAnswers]=useState<Record<number,any>>({});
  const [showSubmit,setShowSubmit]=useState(false);
  const leaderboardRequestRef=useRef(0);
  const attemptRef=useRef<Attempt|null>(null);
  const dirtyRef=useRef<Record<number,any>>({});
  const saveQueueRef=useRef<Promise<void>>(Promise.resolve());
  const submitFnRef=useRef<(automatic?:boolean)=>Promise<void>>(async()=>{});
  const autoSubmitted=useRef(false);

  const loadOverview=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const catalogParams=new URLSearchParams({language,limit:String(testsPageSize),offset:String(testsPage*testsPageSize)});
      if(subjectFilter!=='ALL')catalogParams.set('subjectCode',subjectFilter);
      if(courseId!==null)catalogParams.set('courseId',String(courseId));
      const resultsParams=new URLSearchParams({language,limit:String(resultsPageSize),offset:String(resultsPage*resultsPageSize)});
      if(courseId!==null)resultsParams.set('courseId',String(courseId));
      const [catalog,studentResults]=await Promise.all([
        api('/api/v1/learning/assessments?'+catalogParams.toString()),
        api('/api/v1/learning/assessment-results?'+resultsParams.toString())
      ]);
      const catalogItems=Array.isArray(catalog?.items)?catalog.items:(Array.isArray(catalog)?catalog:[]);
      const resultItems:Result[]=Array.isArray(studentResults?.items)?studentResults.items:(Array.isArray(studentResults)?studentResults:[]);
      setTests(catalogItems);
      setTestsTotal(Number(catalog?.total ?? catalogItems.length));
      setResults(resultItems);
      setResultsTotal(Number(studentResults?.total??(Array.isArray(studentResults)?studentResults.length:0)));
      const attemptIds=[...new Set(resultItems.flatMap(result=>{
        const history=Array.isArray(result.attempts)&&result.attempts.length?result.attempts:[result];
        return history.map(item=>Number(item.attempt_id)).filter(id=>Number.isSafeInteger(id)&&id>0);
      }))].slice(0,100);
      const eligibilityMap:Record<number,CertificateEligibility>={};
      if(attemptIds.length){
        try{
          const eligibility=await api('/api/v1/students/me/certificates/assessment-eligibility?attemptIds='+attemptIds.join(','));
          for(const item of Array.isArray(eligibility?.items)?eligibility.items:[]){
            const id=Number(item.attempt_id);
            if(Number.isSafeInteger(id)&&id>0)eligibilityMap[id]=item as CertificateEligibility;
          }
        }catch{
          // Certificate status is supplemental to saved assessment results; keep results visible
          // when this optional endpoint is temporarily unavailable.
        }
      }
      setCertificateEligibility(eligibilityMap);
    }catch(e:any){if(e?.auth){router.replace('/login/student');return;}setError(e instanceof Error?e.message:'Could not load your tests and results.');}
    finally{setLoading(false);}
  },[router,language,resultsPage,resultsPageSize,subjectFilter,testsPage,testsPageSize,courseId]);
  useEffect(()=>{void loadOverview();},[loadOverview]);
  useEffect(()=>{attemptRef.current=attempt;},[attempt]);

  const assessmentTypeLabel=(kind?:string)=>kind==='TOPIC_END'?translateUi(locale, 'legacy.web.topic.end.test.8a9ba225'):kind==='CHAPTER_END'?translateUi(locale, 'legacy.web.chapter.end.test.fede453c'):translateUi(locale, 'legacy.web.admin.created.test.7c1bcdfd');
  const certificateEligibilityMessage=(reason?:string|null)=>{
    switch(reason){
      case 'ADMIN_CREATED_TEST_ONLY':return '';
      case 'RESULT_NOT_RELEASED':return translateUi(locale, 'legacy.web.certificate.unlocks.after.the.final.re.70095f6c');
      case 'SUBJECT_TRACK_NOT_ACTIVE':return translateUi(locale, 'legacy.web.an.active.enrollment.in.this.subject.a.36b70c5c');
      case 'PUBLISHED_LESSONS_NOT_AVAILABLE':return translateUi(locale, 'legacy.web.a.released.passing.admin.created.test..7df706dd');
      case 'SUBJECT_COMPLETION_REQUIRED':return translateUi(locale, 'legacy.web.test.certificates.do.not.require.compl.c6d71776');
      case 'PASS_THRESHOLD_NOT_MET':{
        const threshold=String(Object.values(certificateEligibility).find(item=>item.pass_threshold_percent)?.pass_threshold_percent||80);
        return t('The result is below the '+threshold+'% certificate threshold.','परिणाम प्रमाणपत्र की '+threshold+'% सीमा से कम है।');
      }
      case 'INVALID_SCORE':return translateUi(locale, 'legacy.web.this.result.has.no.valid.maximum.score.2ff9b3c6');
      case 'CERTIFICATE_REVOKED':return translateUi(locale, 'legacy.web.this.certificate.has.been.revoked.cont.334c43c0');
      default:return translateUi(locale, 'legacy.web.certificate.eligibility.is.unavailable.28d87202');
    }
  };

  async function issueCertificateForAttempt(attemptId:number){
    if(!Number.isSafeInteger(attemptId)||attemptId<1)return;
    setIssuingCertificateAttemptId(attemptId);setError('');setNotice('');
    try{
      const response=await fetch('/api/v1/students/me/certificates/issue',{
        method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',
        body:JSON.stringify({achievementType:'ASSESSMENT_PASS',assessmentAttemptId:attemptId,templateId:'CLASSIC',language:certificateLanguage,...(courseId!==null?{courseId}:{})})
      });
      const body=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(body.detail||body.message||body.error||'Certificate could not be issued.');
      setNotice(translateUi(locale, 'legacy.web.certificate.issued.successfully.view.o.48684d00'));
      await loadOverview();
      await refreshCertificateEligibilityForAttempts([attemptId]);
    }catch(e:any){
      setError(e?.message||'Certificate could not be issued.');
    }finally{
      setIssuingCertificateAttemptId(null);
    }
  }

  const filteredTests=tests.filter(t=>subjectFilter==='ALL'||t.subject_code===subjectFilter);
  const current=attempt?.questions?.[index];
  const currentResponse=current?responses[current.attemptQuestionId]??'':'';
  const released=attempt?.status==='RELEASED';
  const submitted=Boolean(attempt&&attempt.status!=='IN_PROGRESS');
  const answeredCount=attempt?.questions?.filter(q=>!isEmpty(responses[q.attemptQuestionId]??q.answer)).length||0;
  const resultScore=Number(attempt?.final_score??attempt?.auto_score??0);
  const resultMax=Number(attempt?.max_score??0);
  const resultPercent=resultMax>0?Math.round(resultScore/resultMax*100):0;
  const correctCount=attempt?.questions?.filter(q=>q.isCorrect===true).length||0;
  const incorrectCount=attempt?.questions?.filter(q=>q.isCorrect===false).length||0;
  const unansweredCount=attempt?.questions?.filter(q=>isEmpty(q.answer)).length||0;

  function isEmpty(value:any):boolean{
    if(value===null||value===undefined||value==='')return true;
    if(Array.isArray(value))return value.length===0;
    if(typeof value==='object')return Object.keys(value).length===0;
    return false;
  }
  function updateResponse(question:AttemptQuestion,value:any){
    setResponses(old=>({...old,[question.attemptQuestionId]:value}));
    const next={...dirtyRef.current,[question.attemptQuestionId]:value};
    dirtyRef.current=next;
    setDirtyAnswers(next);
    setNotice('');
  }

  const saveAnswersBatch=useCallback(async(entries:Array<[string|number,any]>)=>{
    const currentAttempt=attemptRef.current;
    if(!currentAttempt||currentAttempt.status!=='IN_PROGRESS'||entries.length===0)return;
    setSaving(true);
    const run=saveQueueRef.current.then(async()=>{
      await api('/api/v1/learning/assessment-attempts/'+currentAttempt.attempt_id+'/answers',{
        method:'PUT',
        body:JSON.stringify({answers:entries.map(([attemptQuestionId,answer])=>({attemptQuestionId:Number(attemptQuestionId),answer}))})
      });
    });
    saveQueueRef.current=run.catch(()=>{});
    try{await run;}finally{setSaving(false);}
  },[]);

  useEffect(()=>{
    if(!Object.keys(dirtyAnswers).length||!attempt||attempt.status!=='IN_PROGRESS')return;
    const snapshot=Object.entries(dirtyAnswers);
    const timer=window.setTimeout(async()=>{
      try{
        await saveAnswersBatch(snapshot);
        const remaining={...dirtyRef.current};
        for(const [key,value] of snapshot){
          const questionId=Number(key);
          if(Object.prototype.hasOwnProperty.call(remaining,questionId)&&JSON.stringify(remaining[questionId])===JSON.stringify(value))delete remaining[questionId];
        }
        dirtyRef.current=remaining;
        setDirtyAnswers(remaining);
      }catch{
        setNotice('Could not save the latest edit. It will be retried when you submit the test.');
      }
    },550);
    return()=>window.clearTimeout(timer);
  },[dirtyAnswers,attempt?.attempt_id,attempt?.status,saveAnswersBatch]);

  const refreshCertificateEligibilityForAttempts=useCallback(async(attemptIds:number[])=>{
    const ids=[...new Set(attemptIds.filter(id=>Number.isSafeInteger(id)&&id>0))].slice(0,100);
    if(!ids.length)return;
    try{
      const response=await api('/api/v1/students/me/certificates/assessment-eligibility?attemptIds='+ids.join(','));
      const updates:Record<number,CertificateEligibility>={};
      for(const item of Array.isArray(response?.items)?response.items:[]){
        const id=Number(item.attempt_id);
        if(Number.isSafeInteger(id)&&id>0)updates[id]=item as CertificateEligibility;
      }
      setCertificateEligibility(current=>({...current,...updates}));
    }catch{
      // A temporary eligibility lookup failure must not hide the released test result.
    }
  },[]);

  async function openTest(testId:number){
    setBusy(true);setError('');setNotice('');setAttempt(null);setShowSubmit(false);autoSubmitted.current=false;
    try{
      const data=await api('/api/v1/learning/assessments/'+testId+'/attempts?language='+language,{method:'POST'});
      const nextResponses:Record<number,any>={};
      (data.questions||[]).forEach((q:AttemptQuestion)=>{nextResponses[q.attemptQuestionId]=q.answer??'';});
      setAttempt(data);attemptRef.current=data;setResponses(nextResponses);dirtyRef.current={};setDirtyAnswers({});setIndex(0);setTab('tests');
      if(data.status==='IN_PROGRESS')setNotice('Your attempt is saved. Answers save automatically; you can return and resume until the deadline.');
    }catch(e:any){if(e?.auth){router.replace('/login/student');return;}setError(e instanceof Error?e.message:'Could not start this test.');}
    finally{setBusy(false);}
  }

  async function openResult(attemptId:number){
    setBusy(true);setError('');setNotice('');
    try{
      const data=await api('/api/v1/learning/assessment-attempts/'+attemptId);
      const nextResponses:Record<number,any>={};
      (data.questions||[]).forEach((q:AttemptQuestion)=>{nextResponses[q.attemptQuestionId]=q.answer??'';});
      setAttempt(data);attemptRef.current=data;setResponses(nextResponses);dirtyRef.current={};setDirtyAnswers({});setIndex(0);setTab('results');
      if(data.status==='RELEASED')await refreshCertificateEligibilityForAttempts([attemptId]);
    }catch(e:any){if(e?.auth){router.replace('/login/student');return;}setError(e instanceof Error?e.message:'Could not open this result.');}
    finally{setBusy(false);}
  }

  async function openLeaderboard(test:{assessment_id:number;title:string}){
    const requestId=++leaderboardRequestRef.current;
    setLeaderboardTest(test);setLeaderboardData(null);setLeaderboardError('');setLeaderboardLoading(true);
    try{
      const data=await api('/api/v1/learning/assessments/'+test.assessment_id+'/leaderboard?language='+encodeURIComponent(language));
      if(leaderboardRequestRef.current===requestId)setLeaderboardData(data as LeaderboardData);
    }catch(e:any){
      if(leaderboardRequestRef.current!==requestId)return;
      if(e?.auth){setLeaderboardTest(null);router.replace('/login/student');return;}
      setLeaderboardError(e instanceof Error?e.message:'Could not load this test leaderboard.');
    }finally{if(leaderboardRequestRef.current===requestId)setLeaderboardLoading(false);}
  }

  function closeLeaderboard(){
    leaderboardRequestRef.current++;
    setLeaderboardTest(null);setLeaderboardData(null);setLeaderboardError('');setLeaderboardLoading(false);
  }

  useEffect(()=>{
    if(!leaderboardTest)return;
    const previousOverflow=document.body.style.overflow;
    document.body.style.overflow='hidden';
    const onKeyDown=(event:KeyboardEvent)=>{
      if(event.key==='Escape'){
        leaderboardRequestRef.current++;
        setLeaderboardTest(null);setLeaderboardData(null);setLeaderboardError('');setLeaderboardLoading(false);
      }
    };
    window.addEventListener('keydown',onKeyDown);
    return ()=>{
      window.removeEventListener('keydown',onKeyDown);
      document.body.style.overflow=previousOverflow;
    };
  },[leaderboardTest]);

  function formatMarks(value:number|string){
    const number=Number(value);
    return Number.isFinite(number)?number.toLocaleString(undefined,{maximumFractionDigits:2}):String(value);
  }

  function studentInitials(name:string){
    return name.trim().split(/\s+/).filter(Boolean).slice(0,2).map(part=>part.charAt(0)).join('').toUpperCase()||'S';
  }

  const flushSavedAnswers=useCallback(async()=>{
    const currentAttempt=attemptRef.current;
    if(!currentAttempt||currentAttempt.status!=='IN_PROGRESS')return;
    const entries=Object.entries(dirtyRef.current);
    if(entries.length)await saveAnswersBatch(entries);
    dirtyRef.current={};setDirtyAnswers({});
  },[saveAnswersBatch]);

  const submitTest=useCallback(async(automatic=false)=>{
    const currentAttempt=attemptRef.current;
    if(!currentAttempt||currentAttempt.status!=='IN_PROGRESS'||autoSubmitted.current)return;
    autoSubmitted.current=true;setBusy(true);setError('');setNotice('');
    try{
      try{await flushSavedAnswers();}
      catch(e){
        if(!automatic)throw e;
        setNotice('Time expired. The test is being finalized with the answers already saved.');
      }
      const data=await api('/api/v1/learning/assessment-attempts/'+currentAttempt.attempt_id+'/submit',{method:'POST'});
      setAttempt(data);attemptRef.current=data;setShowSubmit(false);
      setTab(data.status==='RELEASED'?'results':'tests');
      setNotice(data.status==='RELEASED'?'Your test was submitted and the result has been saved.': 'Your test was submitted. Fill-in, short-answer and long-answer responses are waiting for an assigned staff reviewer. Your final score and feedback will appear after all required answers are checked.');
      await loadOverview();
      if(data.status==='RELEASED')await refreshCertificateEligibilityForAttempts([Number(data.attempt_id||currentAttempt.attempt_id)]);
    }catch(e:any){
      autoSubmitted.current=false;
      setError(e instanceof Error?e.message:'Could not submit your test. Saved answers are still preserved.');
    }finally{setBusy(false);}
  },[loadOverview,flushSavedAnswers,refreshCertificateEligibilityForAttempts]);
  useEffect(()=>{submitFnRef.current=submitTest;},[submitTest]);

  useEffect(()=>{
    if(!attempt||attempt.status!=='IN_PROGRESS'||!attempt.deadline_at){setSecondsLeft(null);return;}
    const update=()=>{
      const remaining=Math.max(0,Math.ceil((new Date(attempt.deadline_at).getTime()-Date.now())/1000));
      setSecondsLeft(remaining);
      if(remaining===0&&!autoSubmitted.current)void submitFnRef.current(true);
    };
    update();const timer=window.setInterval(update,1000);
    return()=>window.clearInterval(timer);
  },[attempt?.attempt_id,attempt?.deadline_at,attempt?.status]);

  function closeAttempt(){
    setAttempt(null);setResponses({});dirtyRef.current={};setDirtyAnswers({});setIndex(0);setShowSubmit(false);setError('');setNotice('');autoSubmitted.current=false;
    void loadOverview();
  }

  return <main className={styles.shell}>
    <section className={styles.wrap}>
      {!attempt?<>
        {error&&<div className={styles.error} role="alert">{error}</div>}
        <div className={styles.tabs}><button type="button" className={tab==='tests'?styles.activeTab:''} onClick={()=>setTab('tests')}>{translateUi(locale, 'legacy.web.available.tests.83298a6c')} <span>{testsTotal}</span></button><button type="button" className={tab==='results'?styles.activeTab:''} onClick={()=>setTab('results')}>{translateUi(locale, 'legacy.web.my.results.f7fbefd3')} <span>{resultsTotal}</span></button></div>
        {tab==='tests'?<>
          <div className={styles.toolbar}><div><span className={styles.eyebrow}>{translateUi(locale, 'legacy.web.your.assessments.c34efa9f')}</span><h2>{translateUi(locale, 'legacy.web.tests.available.to.you.6ba5d74e')}</h2><p>{courseId!==null?translateUi(locale, 'legacy.web.tests.are.shown.for.the.selected.cours.48e44b0e'):translateUi(locale, 'legacy.web.tests.are.shown.only.for.your.enrolled.57cf4f75')}</p></div><label>{translateUi(locale, 'legacy.web.subject.0af41287')}<select value={subjectFilter} onChange={e=>{setSubjectFilter(e.target.value);setTestsPage(0)}}><option value="ALL">{translateUi(locale, 'legacy.web.all.subjects.7895d918')}</option><option value="maths">{translateUi(locale, 'legacy.web.mathematics.4cf6be1a')}</option><option value="science">{translateUi(locale, 'legacy.web.science.f85a10aa')}</option></select></label></div>
          {loading?<div className={styles.empty}>{translateUi(locale, 'legacy.web.loading.your.tests.ff94a584')}</div>:filteredTests.length===0?<div className={styles.empty}><span>✓</span><b>{translateUi(locale, 'legacy.web.no.published.tests.yet.04ce394c')}</b><small>{translateUi(locale, 'legacy.web.your.teacher.s.published.tests.will.ap.450888e9')}</small></div>:<div className={styles.testGrid}>{filteredTests.map(test=>{
            const started=Boolean(test.in_progress_attempt_id);
            const used=Number(test.attempts_used||0);
            const resultAvailable=Boolean(test.latest_result_attempt_id);
            const exhausted=!started&&used>=Number(test.max_attempts);
            return <article className={styles.testCard} key={test.assessment_id}><div className={styles.cardTop}><span className={styles.testSymbol}>✓</span><span className={styles.subjectChip}>{test.subject_code==='maths'?translateUi(locale, 'legacy.web.mathematics.4cf6be1a'):translateUi(locale, 'legacy.web.science.f85a10aa')}</span><span className={styles.subjectChip}>{assessmentTypeLabel(test.assessment_type)}</span></div><h3>{test.title}</h3>{test.test_id&&<small>{translateUi(locale, 'legacy.web.test.id.f22e0b35')}: {test.test_id}</small>}<p>{test.description||test.chapter_name||test.subject_name}</p><div className={styles.cardMeta}><span>▤ {test.question_count} {translateUi(locale, 'legacy.web.questions.fa34dc08')}</span><span>◷ {test.duration_minutes} {translateUi(locale, 'legacy.web.min.72242533')}</span><span>★ {test.max_score} {translateUi(locale, 'legacy.web.marks.57368a8d')}</span></div><div className={styles.attemptTrack}><span>{translateUi(locale, 'legacy.web.attempts.used.1eef4b49')}</span><b>{used} / {test.max_attempts}</b></div><div className={styles.attemptBar}><i style={{width:Math.min(100,used/Math.max(1,test.max_attempts)*100)+'%'}}/></div><div className={styles.cardActions}><button type="button" className={styles.primaryButton} disabled={busy||exhausted} onClick={()=>void openTest(test.assessment_id)}>{busy?translateUi(locale, 'legacy.web.opening.c30c8984'):started?translateUi(locale, 'legacy.web.resume.test.f20ec881'):exhausted?translateUi(locale, 'legacy.web.attempts.used.6cc262d7'):translateUi(locale, 'legacy.web.start.test.84f53ec2')}</button>{!started&&<button type="button" className={styles.leaderboardButton} disabled={leaderboardLoading} onClick={()=>void openLeaderboard(test)}><span aria-hidden="true">♛</span> {translateUi(locale, 'legacy.web.top.20.leaderboard.9f98bf6d')}</button>}{!started&&resultAvailable&&<button type="button" className={styles.resultLink} disabled={busy} onClick={()=>void openResult(Number(test.latest_result_attempt_id))}>{translateUi(locale, 'legacy.web.latest.result.e927be6a')}</button>}</div>{exhausted&&!resultAvailable&&<small className={styles.muted}>{translateUi(locale, 'legacy.web.you.have.used.all.allowed.attempts.afaa7d9e')}</small>}</article>;
          })}</div>}
          <PremiumPagination page={testsPage} total={testsTotal} pageSize={testsPageSize}
            onPageChange={setTestsPage} onPageSizeChange={size=>{setTestsPageSize(size);setTestsPage(0);}}
            itemLabel={translateUi(locale, 'legacy.web.available.tests.0852a38c')} loading={loading} />
        </>:<>
          <div className={styles.toolbar}><div><span className={styles.eyebrow}>{translateUi(locale, 'legacy.web.saved.results.804cf2f3')}</span><h2>{translateUi(locale, 'legacy.web.your.test.history.6548bc4c')}</h2><p>{translateUi(locale, 'legacy.web.only.released.results.appear.here.pend.8cf88a8d')}</p></div><button className={styles.secondaryButton} type="button" onClick={()=>void loadOverview()}>{translateUi(locale, 'legacy.web.refresh.944128d0')}</button></div>
          {loading?<div className={styles.empty}>{translateUi(locale, 'legacy.web.loading.saved.results.0ba7f16c')}</div>:results.length===0?<div className={styles.empty}><span>▤</span><b>{translateUi(locale, 'legacy.web.no.released.results.yet.c64adf39')}</b><small>{translateUi(locale, 'legacy.web.when.you.finish.a.test.your.score.and..9b05ae07')}</small></div>:<div className={styles.resultList}>{results.map(result=>{
             const latestAttempts=Array.isArray(result.attempts)&&result.attempts.length?result.attempts.slice(0,3):[{attempt_id:result.attempt_id,attempt_number:result.attempt_number,status:result.status,submitted_at:result.submitted_at,released_at:result.released_at,final_score:result.final_score,max_score:result.max_score}];
             const bestScore=Number(result.best_score??Math.max(...latestAttempts.map(item=>Number(item.final_score??0))));
             const bestPercent=Number(result.max_score)>0?Math.round(bestScore/Number(result.max_score)*100):0;
             const attemptsInHistory=Number(result.attempt_count??latestAttempts.length);
             return <article key={result.assessment_id} className={styles.resultCard}>
               <span className={styles.resultIcon}>✓</span>
               <div className={styles.resultSummary}>
                 <h3>{result.assessment_title}</h3>{result.test_id&&<small>{translateUi(locale, 'legacy.web.test.id.f22e0b35')}: {result.test_id}</small>}
                 <p>{assessmentTypeLabel(result.assessment_type)} · {result.subject_name}{result.chapter_name?' · '+result.chapter_name:''}</p>
                 <small>{attemptsInHistory} {translateUi(locale, attemptsInHistory === 1 ? 'legacy.web.released.attempt.a2ac0123' : 'web.count.releasedAttemptsPlural')} · {result.question_count} {translateUi(locale, 'legacy.web.questions.fa34dc08')} · {translateUi(locale, 'legacy.web.latest.397aaf9b')} {fmtDate(result.released_at)}</small>
               </div>
               <div className={styles.score} aria-label={'Best score among latest attempts: '+bestScore+' out of '+result.max_score}>
                 <b>{bestScore} <small>/ {result.max_score}</small></b><span>{bestPercent}%</span><small>Best of last {Math.min(3,latestAttempts.length)}</small>
               </div>
               <div className={styles.resultAttemptPicker}>
                 <span>{translateUi(locale, 'legacy.web.open.attempt.feedback.b3629ab0')} <small>· {translateUi(locale, 'legacy.web.newest.first.8fc54e8c')}</small></span>
                 <div>{latestAttempts.map((item,attemptIndex)=>{
                   const positionLabel=attemptIndex===0?'Latest':attemptIndex===1?'Previous':'Earlier';
                   const scoreLabel=Number(item.final_score??0)+' / '+Number(item.max_score??result.max_score);
                   const attemptId=Number(item.attempt_id);
                   const certificate=certificateEligibility[attemptId];
                   const certificateId=Number(certificate?.certificate_id||0);
                   return <div key={item.attempt_id} className={styles.resultAttemptItem}>
                     <button type="button" className={styles.resultAttemptButton} disabled={busy} onClick={()=>void openResult(attemptId)} aria-label={t('Open '+positionLabel.toLowerCase()+' attempt #'+item.attempt_number+', score '+scoreLabel,'प्रयास #'+item.attempt_number+' खोलें, अंक '+scoreLabel)} title={positionLabel+' · '+translateUi(locale, 'legacy.web.attempt.b10a594c')+' #'+item.attempt_number+' · '+scoreLabel}>
                       <b>{attemptIndex+1}</b><span>{positionLabel}</span>
                     </button>
                     {certificate?.certificate_status==='ISSUED'&&certificateId>0?(
                       <div className={styles.certificateActionLinks}>
                         <Link href={'/student/certificates?certificateId='+certificateId}>{translateUi(locale, 'legacy.web.view.certificate.07663aac')}</Link>
                         
                         
                         <a href={'/certificate/verify?code='+encodeURIComponent(certificate?.certificate_number||'')} target="_blank" rel="noreferrer">{translateUi(locale, 'legacy.web.verify.status.8146b8bf')}</a>
                       </div>
                     ):certificate?.certificate_status==='REVOKED'?(
                       <div className={styles.certificateActionLinks}>
                         <small className={styles.certificateEligibilityHint}>{translateUi(locale, 'legacy.web.certificate.revoked.29f7fafd')}</small>
                         <a href={'/certificate/verify?code='+encodeURIComponent(certificate?.certificate_number||'')} target="_blank" rel="noreferrer">{translateUi(locale, 'legacy.web.verify.status.8146b8bf')}</a>
                       </div>
                     ):certificate?.eligible?(
                       <button type="button" className={styles.certificateIssueButton}
                         disabled={issuingCertificateAttemptId!==null}
                         onClick={()=>void issueCertificateForAttempt(attemptId)}>
                         {issuingCertificateAttemptId===attemptId?translateUi(locale, 'legacy.web.issuing.1efee91b'):translateUi(locale, 'legacy.web.issue.certificate.2e69c02c')}
                       </button>
                     ):(
                       <small className={styles.certificateEligibilityHint}>{certificateEligibilityMessage(certificate?.eligibility_reason)}</small>
                     )}
                   </div>;
                 })}
                   <button type="button" className={styles.leaderboardButton} disabled={leaderboardLoading}
                     onClick={()=>void openLeaderboard({assessment_id:result.assessment_id,title:result.assessment_title})}>
                     <span aria-hidden="true">♛</span> {translateUi(locale, 'legacy.web.leaderboard.5be07c6e')}
                   </button>
                 </div>
               </div>
             </article>;
           })}</div>}
          <PremiumPagination page={resultsPage} total={resultsTotal} pageSize={resultsPageSize}
            onPageChange={setResultsPage} onPageSizeChange={size=>{setResultsPageSize(size);setResultsPage(0);}}
            itemLabel={translateUi(locale, 'legacy.web.released.results.cd3a88a6')} loading={loading} />
        </>}
      </>:<>
        <div className={styles.attemptHeader}><button className={styles.backButton} type="button" onClick={closeAttempt}>← {translateUi(locale, 'legacy.web.tests.results.f949f4ee')}</button><span className={styles.attemptState}>{attempt.status.replaceAll('_',' ')}</span></div>
        <div className={styles.attemptTitle}><div><span className={styles.eyebrow}>{attempt.subject_name}{attempt.chapter_name?' · '+attempt.chapter_name:''}</span><h1>{attempt.assessment_title}</h1>{attempt.test_id&&<small>{translateUi(locale, 'legacy.web.test.id.f22e0b35')}: {attempt.test_id}</small>}<p>{translateUi(locale, 'legacy.web.attempt.b10a594c')} {attempt.attempt_number} · {attempt.questions.length} {translateUi(locale, 'legacy.web.questions.fa34dc08')} · {attempt.max_score} {translateUi(locale, 'legacy.web.marks.57368a8d')}</p></div>{attempt.status==='IN_PROGRESS'&&<div className={styles.timerBox}><small>{translateUi(locale, 'legacy.web.time.remaining.6d99bc57')}</small><b className={secondsLeft!==null&&secondsLeft<300?styles.timeWarning:''}>{secondsLeft===null?'--:--':clock(secondsLeft)}</b><span>{saving?translateUi(locale, 'legacy.web.saving.answer.3950845d'):translateUi(locale, 'legacy.web.answers.auto.save.0f25a7cb')}</span></div>}</div>
        {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.notice} role="status">{notice}</div>}
        {attempt.translation_complete===false&&<div className={styles.languageNotice} role="status">
          <b><UiText message="legacy.web.language.coverage.047432da" /></b>
          <span><UiText message="legacy.web.some.test.questions.do.not.yet.have.a..4b8d7d84" /></span>
        </div>}
        {released?<section className={styles.releasedResult} aria-live="polite">
          <div className={styles.resultHero}>
            <span className={styles.resultHeroIcon}>✓</span>
            <div><span className={styles.eyebrow}>{translateUi(locale, 'legacy.web.result.ready.a835d9c8')}</span><h2>{translateUi(locale, 'legacy.web.test.submitted.successfully.70e5c363')}</h2><p>{translateUi(locale, 'legacy.web.your.test.is.closed.review.one.questio.abbd2276')}</p></div>
          </div>
          <div className={styles.resultScoreGrid}>
            <article className={styles.resultScorePrimary}>
              <small>{translateUi(locale, 'legacy.web.your.score.67516f44')}</small>
              <strong>{resultScore} <span>/ {resultMax}</span></strong>
              <div className={styles.resultScoreProgress}><i style={{width:Math.min(100,Math.max(0,resultPercent))+'%'}}/></div>
              <b>{resultPercent}%</b><p>{translateUi(locale, 'legacy.web.overall.score.bddc06dc')}</p>
            </article>
            <article className={styles.resultMetric}><strong>{correctCount}</strong><span>{t(translateUi(locale, 'legacy.web.correct.d01db0a2'),'सही')}</span></article>
            <article className={styles.resultMetric}><strong>{incorrectCount}</strong><span>{translateUi(locale, 'legacy.web.incorrect.c7d63751')}</span></article>
            <article className={styles.resultMetric}><strong>{unansweredCount}</strong><span>{translateUi(locale, 'legacy.web.not.answered.f674e469')}</span></article>
          </div>
          {released&&(()=>{
            const cert=certificateEligibility[Number(attempt?.attempt_id)];
            const certificateId=Number(cert?.certificate_id||0);
            return <section className={styles.certificateResultPanel} aria-live="polite">
              <div><span className={styles.eyebrow}>{translateUi(locale, 'legacy.web.certificate.ea04cb7f')}</span><h3>{translateUi(locale, 'legacy.web.certificate.for.this.result.3136e16b')}</h3>
                <p>{translateUi(locale, 'legacy.web.test.certificates.require.an.admin.cre.07390e4d')}</p>
                {cert?.subject_name && <small>{uiLanguage === 'hi' ? 'प्रमाणपत्र ट्रैक:' : 'Certificate track:'} {uiLanguage === 'hi' ? 'कक्षा ' : 'Class '}{cert.class_code || ''} · {certificateTrackName(cert.subject_code, cert.subject_name, uiLanguage)}</small>}
              </div>
              {cert?.certificate_status==='ISSUED'&&certificateId>0?(
                <div className={styles.certificateActionLinks}>
                  <Link href={'/student/certificates?certificateId='+certificateId}>{translateUi(locale, 'legacy.web.view.certificate.07663aac')}</Link>
                  
                  
                  <a href={'/certificate/verify?code='+encodeURIComponent(cert?.certificate_number||'')} target="_blank" rel="noreferrer">{translateUi(locale, 'legacy.web.verify.status.8146b8bf')}</a>
                </div>
              ):cert?.certificate_status==='REVOKED'?(
                <div className={styles.certificateActionLinks}>
                  <small className={styles.certificateEligibilityHint}>{translateUi(locale, 'legacy.web.this.certificate.has.been.revoked.6ccdb9f1')}</small>
                  <a href={'/certificate/verify?code='+encodeURIComponent(cert?.certificate_number||'')} target="_blank" rel="noreferrer">{translateUi(locale, 'legacy.web.verify.status.8146b8bf')}</a>
                </div>
              ):cert?.eligible?(
                <button type="button" className={styles.certificateIssueButton}
                  disabled={issuingCertificateAttemptId!==null}
                  onClick={()=>void issueCertificateForAttempt(Number(attempt?.attempt_id))}>
                  {issuingCertificateAttemptId===Number(attempt?.attempt_id)?translateUi(locale, 'legacy.web.issuing.1efee91b'):translateUi(locale, 'legacy.web.issue.certificate.2e69c02c')}
                </button>
              ):(
                cert?.eligibility_reason!=='ADMIN_CREATED_TEST_ONLY'
                  ? <small className={styles.certificateEligibilityHint}>{certificateEligibilityMessage(cert?.eligibility_reason)}</small>
                  : null
              )}
            </section>;
          })()}
          <section className={styles.resultDetails}>
            <div className={styles.resultDetailsHeading}><h3>{translateUi(locale, 'legacy.web.question.wise.result.4d0055f4')}</h3><p>{translateUi(locale, 'legacy.web.marks.and.outcomes.are.shown.for.revie.78f6eeaf')}</p></div>
            <div className={styles.resultReviewList}>
              {current&&(()=>{
                const question=current;
                const questionIndex=index;
                const outcome=question.isCorrect===true?translateUi(locale, 'legacy.web.correct.d01db0a2'):question.isCorrect===false?translateUi(locale, 'legacy.web.incorrect.c7d63751'):isEmpty(question.answer)?translateUi(locale, 'legacy.web.not.answered.f674e469'):translateUi(locale, 'legacy.web.reviewed.6c5656b3');
                const outcomeClass=question.isCorrect===true?styles.reviewCorrect:question.isCorrect===false?styles.reviewIncorrect:styles.reviewNeutral;
                return <article className={styles.resultReviewItem} key={question.attemptQuestionId}>
                  <section className={styles.questionPanel}>
                    <div className={styles.questionPanelTop}>
                      <span>{translateUi(locale, 'legacy.web.question.92d71f93')}{questionIndex+1} {translateUi(locale, 'legacy.web.of.b4f09a56')}{attempt.questions.length}</span>
                      <b className={outcomeClass}>{outcome} · {Number(question.awardedMarks??0)} / {Number(question.maxMarks??0)} {translateUi(locale, 'legacy.web.marks.57368a8d')}</b>
                    </div>
                    <div className={styles.questionProgress}><i style={{width:((questionIndex+1)/attempt.questions.length*100)+'%'}}/></div>
                    <QuestionContent question={question} response={question.answer} review disabled/>
                    <AnswerFeedback
                      outcome={question.isCorrect===true?'correct':question.isCorrect===false?'incorrect':'ungraded'}
                      title={question.isCorrect===true?'✓ '+translateUi(locale, 'legacy.web.correct.d01db0a2'):question.isCorrect===false?'✕ '+translateUi(locale, 'legacy.web.incorrect.c7d63751'):isEmpty(question.answer)?translateUi(locale, 'legacy.web.not.answered.f674e469'):translateUi(locale, 'legacy.web.answer.reviewed.2ac87d1a')}
                      message={<>{translateUi(locale, 'legacy.web.marks.9797bead')}: {question.awardedMarks??0} / {question.maxMarks}{question.teacherFeedback?<> · {question.teacherFeedback}</>:null}</>}
                      explanation={question.explanation||undefined}
                      explanationLabel={translateUi(locale, 'legacy.web.explanation.f09ea03b')}
                    />
                  </section>
                </article>;
              })()}
            </div>
            {attempt.questions.length>0&&<nav className={styles.resultQuestionNav} aria-label={translateUi(locale, 'legacy.web.question.review.navigation.2c58cc47')}>
              <button type="button" className={styles.secondaryButton} disabled={index===0}
                onClick={()=>setIndex(i=>Math.max(0,i-1))}>← {translateUi(locale, 'legacy.web.previous.9320332c')}</button>
              <span>{translateUi(locale, 'legacy.web.question.85099273')}{Math.min(index+1,attempt.questions.length)} {translateUi(locale, 'legacy.web.of.ffc7a9ee')} {attempt.questions.length}</span>
              <button type="button" className={styles.primaryButton} disabled={index>=attempt.questions.length-1}
                onClick={()=>setIndex(i=>Math.min(attempt.questions.length-1,i+1))}>{translateUi(locale, 'legacy.web.next.7c68200e')} →</button>
            </nav>}
          </section>
          <div className={styles.resultActions}><button className={styles.primaryButton} type="button" onClick={closeAttempt}>{translateUi(locale, 'legacy.web.back.to.results.fb465e0f')}</button></div>
        </section>
        :(attempt.status==='AWAITING_REVIEW'||attempt.status==='GRADED')?<div className={styles.pendingResult}><span>◷</span><h2>{translateUi(locale, 'legacy.web.test.submitted.successfully.70e5c363')}</h2><p>{translateUi(locale, 'legacy.web.your.answers.are.saved.fill.in.short.a.319393e7')}</p><small>Submitted: {fmtDate(attempt.submitted_at)}</small><button className={styles.primaryButton} type="button" onClick={closeAttempt}>{translateUi(locale, 'legacy.web.back.to.tests.and.results.29805d19')}</button></div>
        :attempt.status==='ABANDONED'?<div className={styles.pendingResult}><h2>{translateUi(locale, 'legacy.web.this.attempt.is.closed.056ad0ef')}</h2><button className={styles.primaryButton} type="button" onClick={closeAttempt}>{translateUi(locale, 'legacy.web.back.to.tests.0d539e53')}</button></div>
        :attempt.status!=='IN_PROGRESS'||!current?<div className={styles.pendingResult}><h2>{translateUi(locale, 'legacy.web.this.attempt.is.closed.3b00ff63')}</h2><p>{translateUi(locale, 'legacy.web.you.cannot.return.to.the.question.navi.83bf6290')}</p><button className={styles.primaryButton} type="button" onClick={closeAttempt}>{translateUi(locale, 'legacy.web.back.to.tests.and.results.29805d19')}</button></div>
        :<div className={styles.examLayout}>
          <aside className={styles.questionRail}><div><b>{translateUi(locale, 'legacy.web.questions.b5a33268')}</b><small>{answeredCount}/{attempt.questions.length} {translateUi(locale, 'legacy.web.answered.f3b8dd02')}</small></div><div className={styles.questionNumbers}>{attempt.questions.map((q,i)=><button type="button" key={q.attemptQuestionId} className={[i===index?styles.currentNumber:'',!isEmpty(responses[q.attemptQuestionId]??q.answer)?styles.answeredNumber:''].filter(Boolean).join(' ')} onClick={()=>setIndex(i)} aria-label={translateUi(locale, 'legacy.web.question.85099273')+(i+1)}>{i+1}</button>)}</div><div className={styles.railLegend}><span><i className={styles.legendCurrent}/>{translateUi(locale, 'legacy.web.current.e19e8a40')}</span><span><i className={styles.legendAnswered}/>{translateUi(locale, 'legacy.web.answered.5531e37e')}</span></div></aside>
          <section className={styles.questionPanel}><div className={styles.questionPanelTop}><span>{translateUi(locale, 'legacy.web.question.3b02187f')} {index+1} {translateUi(locale, 'legacy.web.of.205487ae')} {attempt.questions.length}</span><b>{current.maxMarks} {translateUi(locale, 'legacy.web.mark.s.a8fb7e84')}</b></div><div className={styles.questionProgress}><i style={{width:((index+1)/attempt.questions.length*100)+'%'}}/></div><QuestionContent question={current} response={currentResponse} disabled={submitted} onAnswer={value=>updateResponse(current,value)}/>
            {released&&<AnswerFeedback
              outcome={current.isCorrect===true?'correct':current.isCorrect===false?'incorrect':'ungraded'}
              title={current.isCorrect===true?'✓ Correct':current.isCorrect===false?'✕ Incorrect':'Teacher checked'}
              message={<>Marks: {current.awardedMarks??0} / {current.maxMarks}{current.teacherFeedback?<> · {current.teacherFeedback}</>:null}</>}
              explanation={current.explanation||undefined}
              explanationLabel={translateUi(locale, 'legacy.web.explanation.f09ea03b')}
            />}
            <div className={styles.questionFooter}><span>{saving?translateUi(locale, 'legacy.web.saving.0f10a0df'):Object.keys(dirtyAnswers).length?translateUi(locale, 'legacy.web.save.pending.0d2fabc1'):translateUi(locale, 'legacy.web.saved.answers.are.preserved.689797b2')}</span><div><button type="button" className={styles.secondaryButton} disabled={index===0} onClick={()=>setIndex(i=>Math.max(0,i-1))}>← {translateUi(locale, 'legacy.web.previous.9320332c')}</button>{index<attempt.questions.length-1?<button type="button" className={styles.primaryButton} onClick={()=>setIndex(i=>Math.min(attempt.questions.length-1,i+1))}>{translateUi(locale, 'legacy.web.next.question.e339ba3c')}</button>:attempt.status==='IN_PROGRESS'?<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>setShowSubmit(true)}>{translateUi(locale, 'legacy.web.review.submit.e856cb00')}</button>:null}</div></div>
          </section>
        </div>}
        {attempt.status==='IN_PROGRESS'&&showSubmit&&<div className={styles.confirmBackdrop}><section className={styles.confirmModal}><span className={styles.eyebrow}>{translateUi(locale, 'legacy.web.final.check.eb2b8cf0')}</span><h2>{translateUi(locale, 'legacy.web.submit.this.test.2468974e')}</h2><p>You have answered {answeredCount} of {attempt.questions.length} questions. Unanswered questions will be saved as blank and marked accordingly. You cannot change answers after submission.</p><div><button type="button" className={styles.secondaryButton} onClick={()=>setShowSubmit(false)}>{translateUi(locale, 'legacy.web.keep.working.2fe8b3fa')}</button><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void submitTest(false)}>{busy?translateUi(locale, 'legacy.web.submitting.ab455b91'):translateUi(locale, 'legacy.web.submit.test.7f5c3b03')}</button></div></section></div>}
      </>}
    </section>

    {leaderboardTest&&<div className={styles.leaderboardBackdrop} onClick={closeLeaderboard}>
      <section className={styles.leaderboardModal} role="dialog" aria-modal="true" aria-labelledby="qe-leaderboard-title" onClick={event=>event.stopPropagation()}>
        <header className={styles.leaderboardHeader}>
          <div className={styles.leaderboardHeading}>
            <span className={styles.leaderboardEyebrow}>QUANTAEDGE · STUDENT RANKINGS</span>
            <h2 id="qe-leaderboard-title">{leaderboardData?.assessment_title||leaderboardTest.title}</h2>
            <p>{translateUi(locale, 'legacy.web.top.20.performers.higher.marks.rank.fi.31b5b0df')}</p>
          </div>
          <button type="button" className={styles.leaderboardClose} onClick={closeLeaderboard} aria-label={translateUi(locale, 'legacy.web.close.leaderboard.bc21fd7c')}>×</button>
        </header>
        {leaderboardError&&<div className={styles.leaderboardInlineError} role="alert"><span>{leaderboardError}</span><button type="button" className={styles.secondaryButton} onClick={()=>void openLeaderboard(leaderboardTest)}>{translateUi(locale, 'legacy.web.try.again.271e37a8')}</button></div>}
        {leaderboardLoading?<div className={styles.leaderboardLoading}><span className={styles.leaderboardSpinner}/><b>{translateUi(locale, 'legacy.web.preparing.the.leaderboard.079f41ea')}</b><small>{translateUi(locale, 'legacy.web.only.completed.tests.with.released.sco.bd353c93')}</small></div>
        :leaderboardData&&<>
          <div className={styles.leaderboardMetrics}>
            <div><span>{translateUi(locale, 'legacy.web.participants.206706e1')}</span><b>{leaderboardData.participant_count}</b></div>
            <div><span>{translateUi(locale, 'legacy.web.ranking.rule.4aea94a2')}</span><b>{translateUi(locale, 'legacy.web.marks.first.22616169')} <i>·</i> {translateUi(locale, 'legacy.web.time.second.2f076da4')}</b></div>
            <div><span>{translateUi(locale, 'legacy.web.displaying.887ee655')}</span><b>{Math.min(20,leaderboardData.items.length)} {translateUi(locale, 'legacy.web.of.ffc7a9ee')} {leaderboardData.participant_count}</b></div>
          </div>
          {leaderboardData.items.length===0?<div className={styles.leaderboardEmpty}><span>♛</span><h3>{translateUi(locale, 'legacy.web.the.race.starts.here.3bb5aef5')}</h3><p>{translateUi(locale, 'legacy.web.no.released.scores.are.available.for.t.827f6137')}</p></div>
          :<>
            {leaderboardData.items.length>=3&&<div className={styles.leaderboardPodium} aria-label={translateUi(locale, 'legacy.web.top.three.students.ccadc756')}>
              {[2,1,3].map(rank=>{
                const entry=leaderboardData.items.find(item=>item.rank===rank);
                if(!entry)return null;
                const podiumClass=rank===1?styles.leaderboardFirst:rank===2?styles.leaderboardSecond:styles.leaderboardThird;
                return <article key={entry.rank} className={[styles.leaderboardPodiumCard,podiumClass,entry.is_current_student?styles.leaderboardMine:''].filter(Boolean).join(' ')}>
                  <span className={styles.leaderboardMedal}>{rank===1?'♛':rank===2?'Ⅱ':'Ⅲ'}</span>
                  <span className={styles.leaderboardAvatar}>{studentInitials(entry.student_name)}</span>
                  <b className={styles.leaderboardStudentName}>{entry.student_name}</b>
                  <strong className={styles.leaderboardPodiumScore}>{formatMarks(entry.score)}<small> / {formatMarks(entry.max_score)}</small></strong>
                  <span className={styles.leaderboardTime}><i aria-hidden="true">◷</i> {clock(Math.floor(Number(entry.time_taken_seconds)||0))}</span>
                  {entry.is_current_student&&<span className={styles.leaderboardYou}>{translateUi(locale, 'legacy.web.you.5104625c')}</span>}
                </article>;
              })}
            </div>}
            {(leaderboardData.items.length<3||leaderboardData.items.some(item=>item.rank>3))&&<div className={styles.leaderboardTableWrap}>
              <table className={styles.leaderboardTable}>
                <thead><tr><th scope="col">{translateUi(locale, 'legacy.web.rank.6d8e5bba')}</th><th scope="col">{translateUi(locale, 'legacy.web.student.bd93d3aa')}</th><th scope="col">{translateUi(locale, 'legacy.web.marks.9797bead')}</th><th scope="col">{translateUi(locale, 'legacy.web.time.taken.056e615b')}</th></tr></thead>
                <tbody>{leaderboardData.items.filter(item=>leaderboardData.items.length<3||item.rank>3).map(entry=><tr key={entry.rank} className={entry.is_current_student?styles.leaderboardTableMine:''}>
                  <td><span className={styles.leaderboardRankNumber}>{String(entry.rank).padStart(2,'0')}</span></td>
                  <td><div className={styles.leaderboardStudentCell}><span className={styles.leaderboardTableAvatar}>{studentInitials(entry.student_name)}</span><b>{entry.student_name}</b>{entry.is_current_student&&<span className={styles.leaderboardYou}>{translateUi(locale, 'legacy.web.you.5104625c')}</span>}</div></td>
                  <td><strong className={styles.leaderboardTableScore}>{formatMarks(entry.score)} <small>/ {formatMarks(entry.max_score)}</small></strong></td>
                  <td><span className={styles.leaderboardTime}><i aria-hidden="true">◷</i> {clock(Math.floor(Number(entry.time_taken_seconds)||0))}</span></td>
                </tr>)}</tbody>
              </table>
            </div>}
            {leaderboardData.current_student&&leaderboardData.current_student.rank>20&&<div className={styles.leaderboardMyStanding}>
              <div><span>{translateUi(locale, 'legacy.web.your.current.position.a3b4573d')}</span><b>#{leaderboardData.current_student.rank} <small>{translateUi(locale, 'legacy.web.outside.the.top.20.f065ad68')}</small></b></div>
              <strong>{formatMarks(leaderboardData.current_student.score)} <small>/ {formatMarks(leaderboardData.current_student.max_score)} {translateUi(locale, 'legacy.web.marks.57368a8d')}</small></strong>
              <span className={styles.leaderboardTime}><i aria-hidden="true">◷</i> {clock(Math.floor(Number(leaderboardData.current_student.time_taken_seconds)||0))}</span>
            </div>}
          </>}
          <footer className={styles.leaderboardFooter}><span>{translateUi(locale, 'legacy.web.rankings.use.the.best.released.attempt.c4aab20c')}</span><button type="button" className={styles.secondaryButton} onClick={closeLeaderboard}>{translateUi(locale, 'legacy.web.close.leaderboard.bc21fd7c')}</button></footer>
        </>}
      </section>
    </div>}

  </main>;
}
