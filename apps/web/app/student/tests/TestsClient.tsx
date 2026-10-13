'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';
import PremiumPagination from '../../components/PremiumPagination';
import AnswerFeedback from '../../components/AnswerFeedback';
import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './Tests.module.css';

type AssessmentCard = {
  assessment_id:number;title:string;description?:string;duration_minutes:number;max_attempts:number;
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
  attempt_id:number;assessment_id:number;attempt_number:number;status:string;started_at:string;deadline_at:string;
  submitted_at?:string|null;released_at?:string|null;assessment_title:string;subject_name:string;chapter_name?:string|null;
  questions:AttemptQuestion[];resultReleased:boolean;translation_complete?:boolean;final_score?:number|string|null;auto_score?:number|string|null;max_score:number|string;
};
type ResultAttempt = {attempt_id:number;attempt_number:number;status:string;submitted_at?:string|null;released_at?:string|null;final_score:number|string;max_score:number|string};
type Result = {attempt_id:number;assessment_id:number;attempt_number:number;status:string;submitted_at:string;released_at:string;final_score:number|string;max_score:number|string;best_score?:number|string;attempt_count?:number;attempts?:ResultAttempt[];assessment_title:string;assessment_type?:string;subject_name:string;chapter_name?:string|null;question_count:number};
type CertificateEligibility = {
  attempt_id:number;eligible:boolean;eligibility_reason?:string|null;certificate_id?:number|null;
  certificate_number?:string|null;certificate_status?:string|null;pass_threshold_percent?:number|string;
};

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
  if(init.body)headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok){if(response.status===401||response.status===403)throw Object.assign(new Error('Student sign-in is required.'),{auth:true});throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));}
  return body;
}
function answerText(value:any){if(value===null||value===undefined)return '';if(typeof value==='string')return value;try{return JSON.stringify(value)}catch{return String(value)}}
function fmtDate(value?:string|null){if(!value)return '—';const date=new Date(value);return Number.isNaN(date.getTime())?'—':date.toLocaleString()}
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
      const hint=review&&chosen&&correct?'Your answer · Correct answer':review&&chosen&&question.isCorrect===false?'Your recorded answer · Incorrect':review&&chosen&&question.isCorrect===true?'Your recorded answer · Correct':review&&chosen?'Your recorded answer':review&&correct?'Correct answer':'';
      return <label key={option.key} className={[styles.option,!review&&chosen?styles.optionSelected:'',tone].filter(Boolean).join(' ')}>
        <input type="radio" name={'question-'+question.attemptQuestionId} value={key} checked={chosen} disabled={disabled||review} onChange={()=>onAnswer?.(option.key)}/>
        <span className={styles.optionKey}>{option.key}</span>
        <span className={styles.optionText}>{option.label}{hint&&<small className={wrongChosen?styles.optionHintIncorrect:correct||question.isCorrect===true?styles.optionHintCorrect:styles.optionHintNeutral}>{hint}</small>}</span>
        {review&&(chosen||correct)&&<span className={styles.optionOutcome} aria-label={wrongChosen?'Incorrect answer':correct?'Correct answer':question.isCorrect===true?'Correct':'Recorded answer'}>{wrongChosen?'!':correct||question.isCorrect===true?'✓':'i'}</span>}
      </label>;
    })}</div>}
    {!hasOptions&&<label className={[styles.answerField,review?answerTone:''].filter(Boolean).join(' ')}>
      <span>{question.question_type==='NUMERICAL'?'Your numerical answer':'Your answer'}</span>
      {question.question_type==='INPUT'||question.question_type==='NUMERICAL'
        ?<input value={answerText(response)} disabled={disabled||review} type={question.question_type==='NUMERICAL'?'number':'text'} onChange={event=>onAnswer?.(event.target.value)} placeholder="Type your answer here…"/>
        :<textarea value={answerText(response)} disabled={disabled||review} rows={6} onChange={event=>onAnswer?.(event.target.value)} placeholder={['MATCH','ORDER','CASE_BASED','ASSERTION_REASON'].includes(question.question_type)?'Write your answer or reasoning clearly…':'Enter your answer here…'}/>}
      {!review&&<small>{['INPUT','SHORT_ANSWER','LONG_ANSWER','CASE_BASED','SOURCE_BASED','DIAGRAM','MAP','ASSERTION_REASON','MATCH','ORDER'].includes(question.question_type)?'This response is sent for staff checking. Your final result is released after all required answers are reviewed.':''}</small>}
    </label>}
    {review&&correctText&&(!hasOptions||rightKeys.size===0)&&<div className={styles.correctAnswerBox}><strong>Correct answer</strong><span>{correctText}</span></div>}
  </>;
}

export default function TestsClient(){
  const router=useRouter();
  const {locale}=useLocale();
  const language=locale==='english'?'en':'hi';
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
      const [catalog,studentResults]=await Promise.all([
        api('/api/v1/learning/assessments?'+catalogParams.toString()),
        api('/api/v1/learning/assessment-results?language='+language+'&limit='+resultsPageSize+'&offset='+(resultsPage*resultsPageSize))
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
        }catch(e:any){
          // Certificate status is supplemental to saved assessment results; keep results visible
          // when this optional endpoint is temporarily unavailable.
        }
      }
      setCertificateEligibility(eligibilityMap);
    }catch(e:any){if(e?.auth){router.replace('/login/student');return;}setError(e instanceof Error?e.message:'Could not load your tests and results.');}
    finally{setLoading(false);}
  },[router,language,resultsPage,resultsPageSize,subjectFilter,testsPage,testsPageSize]);
  useEffect(()=>{void loadOverview();},[loadOverview]);
  useEffect(()=>{attemptRef.current=attempt;},[attempt]);

  const assessmentTypeLabel=(kind?:string)=>kind==='TOPIC_END'?'Topic-end test':kind==='CHAPTER_END'?'Chapter-end test':'Admin-created test';
  const certificateEligibilityMessage=(reason?:string|null)=>{
    switch(reason){
      case 'ADMIN_CREATED_TEST_ONLY':return 'Only admin-created tests qualify; generated topic/chapter tests and practice are excluded.';
      case 'RESULT_NOT_RELEASED':return 'Certificate unlocks after the final result is released.';
      case 'SUBJECT_TRACK_NOT_ACTIVE':return 'An active enrollment in this subject and current class is required.';
      case 'PUBLISHED_LESSONS_NOT_AVAILABLE':return 'Certificates unlock after this subject has published lessons.';
      case 'SUBJECT_COMPLETION_REQUIRED':return 'Complete all published lessons in this subject to unlock the test certificate.';
      case 'PASS_THRESHOLD_NOT_MET':return 'The result is below the '+String(Object.values(certificateEligibility).find(item=>item.pass_threshold_percent)?.pass_threshold_percent||80)+'% certificate threshold.';
      case 'INVALID_SCORE':return 'This result has no valid maximum score.';
      case 'CERTIFICATE_REVOKED':return 'This certificate has been revoked; contact an administrator.';
      default:return 'Certificate eligibility is unavailable. Refresh results to check again.';
    }
  };

  async function issueCertificateForAttempt(attemptId:number){
    if(!Number.isSafeInteger(attemptId)||attemptId<1)return;
    setIssuingCertificateAttemptId(attemptId);setError('');setNotice('');
    try{
      const response=await fetch('/api/v1/students/me/certificates/issue',{
        method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',
        body:JSON.stringify({achievementType:'ASSESSMENT_PASS',assessmentAttemptId:attemptId,templateId:'CLASSIC',language})
      });
      const body=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(body.message||'Certificate could not be issued.');
      setNotice('Certificate issued successfully. View or download it from this result.');
      await loadOverview();
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

  async function refreshCertificateEligibilityForAttempts(attemptIds:number[]){
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
  }

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
  },[loadOverview,flushSavedAnswers]);
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
        <div className={styles.tabs}><button type="button" className={tab==='tests'?styles.activeTab:''} onClick={()=>setTab('tests')}>Available tests <span>{testsTotal}</span></button><button type="button" className={tab==='results'?styles.activeTab:''} onClick={()=>setTab('results')}>My results <span>{resultsTotal}</span></button></div>
        {tab==='tests'?<>
          <div className={styles.toolbar}><div><span className={styles.eyebrow}>YOUR ASSESSMENTS</span><h2>Tests available to you</h2><p>Tests are shown only for your enrolled class and subjects.</p></div><label>Subject<select value={subjectFilter} onChange={e=>{setSubjectFilter(e.target.value);setTestsPage(0)}}><option value="ALL">All subjects</option><option value="maths">Mathematics</option><option value="science">Science</option></select></label></div>
          {loading?<div className={styles.empty}>Loading your tests…</div>:filteredTests.length===0?<div className={styles.empty}><span>✓</span><b>No published tests yet</b><small>Your teacher’s published tests will appear here.</small></div>:<div className={styles.testGrid}>{filteredTests.map(test=>{
            const started=Boolean(test.in_progress_attempt_id);
            const used=Number(test.attempts_used||0);
            const resultAvailable=Boolean(test.latest_result_attempt_id);
            const exhausted=!started&&used>=Number(test.max_attempts);
            return <article className={styles.testCard} key={test.assessment_id}><div className={styles.cardTop}><span className={styles.testSymbol}>✓</span><span className={styles.subjectChip}>{test.subject_code==='maths'?'Mathematics':'Science'}</span><span className={styles.subjectChip}>{assessmentTypeLabel(test.assessment_type)}</span></div><h3>{test.title}</h3><p>{test.description||test.chapter_name||test.subject_name}</p><div className={styles.cardMeta}><span>▤ {test.question_count} questions</span><span>◷ {test.duration_minutes} min</span><span>★ {test.max_score} marks</span></div><div className={styles.attemptTrack}><span>Attempts used</span><b>{used} / {test.max_attempts}</b></div><div className={styles.attemptBar}><i style={{width:Math.min(100,used/Math.max(1,test.max_attempts)*100)+'%'}}/></div><div className={styles.cardActions}><button type="button" className={styles.primaryButton} disabled={busy||exhausted} onClick={()=>void openTest(test.assessment_id)}>{busy?'Opening…':started?'Resume attempt':exhausted?'Attempts used':'Start test'}</button><button type="button" className={styles.leaderboardButton} disabled={leaderboardLoading} onClick={()=>void openLeaderboard(test)}><span aria-hidden="true">♛</span> Top 20 leaderboard</button>{resultAvailable&&<button type="button" className={styles.resultLink} disabled={busy} onClick={()=>void openResult(Number(test.latest_result_attempt_id))}>Latest result ↗</button>}</div>{exhausted&&!resultAvailable&&<small className={styles.muted}>You have used all allowed attempts.</small>}</article>;
          })}</div>}
          <PremiumPagination page={testsPage} total={testsTotal} pageSize={testsPageSize}
            onPageChange={setTestsPage} onPageSizeChange={size=>{setTestsPageSize(size);setTestsPage(0);}}
            itemLabel="available tests" loading={loading} />
        </>:<>
          <div className={styles.toolbar}><div><span className={styles.eyebrow}>SAVED RESULTS</span><h2>Your test history</h2><p>Only released results appear here. Pending teacher checks are not shown as final scores.</p></div><button className={styles.secondaryButton} type="button" onClick={()=>void loadOverview()}>Refresh</button></div>
          {loading?<div className={styles.empty}>Loading saved results…</div>:results.length===0?<div className={styles.empty}><span>▤</span><b>No released results yet</b><small>When you finish a test, your score and teacher feedback will be saved here after any required review.</small></div>:<div className={styles.resultList}>{results.map(result=>{
             const latestAttempts=Array.isArray(result.attempts)&&result.attempts.length?result.attempts.slice(0,3):[{attempt_id:result.attempt_id,attempt_number:result.attempt_number,status:result.status,submitted_at:result.submitted_at,released_at:result.released_at,final_score:result.final_score,max_score:result.max_score}];
             const bestScore=Number(result.best_score??Math.max(...latestAttempts.map(item=>Number(item.final_score??0))));
             const bestPercent=Number(result.max_score)>0?Math.round(bestScore/Number(result.max_score)*100):0;
             const attemptsInHistory=Number(result.attempt_count??latestAttempts.length);
             return <article key={result.assessment_id} className={styles.resultCard}>
               <span className={styles.resultIcon}>✓</span>
               <div className={styles.resultSummary}>
                 <h3>{result.assessment_title}</h3>
                 <p>{result.assessment_type==='TOPIC_END'?'Topic-end test':result.assessment_type==='CHAPTER_END'?'Chapter-end test':'Admin-created test'} · {result.subject_name}{result.chapter_name?' · '+result.chapter_name:''}</p>
                 <small>{attemptsInHistory} released attempt{attemptsInHistory===1?'':'s'} · {result.question_count} questions · Latest: {fmtDate(result.released_at)}</small>
               </div>
               <div className={styles.score} aria-label={'Best score among latest attempts: '+bestScore+' out of '+result.max_score}>
                 <b>{bestScore} <small>/ {result.max_score}</small></b><span>{bestPercent}%</span><small>Best of last {Math.min(3,latestAttempts.length)}</small>
               </div>
               <div className={styles.resultAttemptPicker}>
                 <span>Open attempt feedback <small>· newest first</small></span>
                 <div>{latestAttempts.map((item,attemptIndex)=>{
                   const positionLabel=attemptIndex===0?'Latest':attemptIndex===1?'Previous':'Earlier';
                   const scoreLabel=Number(item.final_score??0)+' / '+Number(item.max_score??result.max_score);
                   const attemptId=Number(item.attempt_id);
                   const certificate=certificateEligibility[attemptId];
                   const certificateId=Number(certificate?.certificate_id||0);
                   return <div key={item.attempt_id} className={styles.resultAttemptItem}>
                     <button type="button" className={styles.resultAttemptButton} disabled={busy} onClick={()=>void openResult(attemptId)} aria-label={'Open '+positionLabel.toLowerCase()+' attempt #'+item.attempt_number+', score '+scoreLabel} title={positionLabel+' · Attempt #'+item.attempt_number+' · '+scoreLabel}>
                       <b>{attemptIndex+1}</b><span>{positionLabel}</span>
                     </button>
                     {certificate?.certificate_status==='ISSUED'&&certificateId>0?(
                       <div className={styles.certificateActionLinks}>
                         <a href={'/api/v1/students/me/certificates/'+certificateId+'/view?format=pdf'} target="_blank" rel="noreferrer">View certificate ↗</a>
                         <a href={'/api/v1/students/me/certificates/'+certificateId+'/download?format=pdf'}>PDF</a>
                         <a href={'/api/v1/students/me/certificates/'+certificateId+'/download?format=png'}>PNG</a>
                       </div>
                     ):certificate?.certificate_status==='REVOKED'?(
                       <small className={styles.certificateEligibilityHint}>Certificate revoked</small>
                     ):certificate?.eligible?(
                       <button type="button" className={styles.certificateIssueButton}
                         disabled={issuingCertificateAttemptId!==null}
                         onClick={()=>void issueCertificateForAttempt(attemptId)}>
                         {issuingCertificateAttemptId===attemptId?'Issuing…':'Issue certificate'}
                       </button>
                     ):(
                       <small className={styles.certificateEligibilityHint}>{certificateEligibilityMessage(certificate?.eligibility_reason)}</small>
                     )}
                   </div>;
                 })}
                   <button type="button" className={styles.leaderboardButton} disabled={leaderboardLoading}
                     onClick={()=>void openLeaderboard({assessment_id:result.assessment_id,title:result.assessment_title})}>
                     <span aria-hidden="true">♛</span> Leaderboard
                   </button>
                 </div>
               </div>
             </article>;
           })}</div>}
          <PremiumPagination page={resultsPage} total={resultsTotal} pageSize={resultsPageSize}
            onPageChange={setResultsPage} onPageSizeChange={size=>{setResultsPageSize(size);setResultsPage(0);}}
            itemLabel="released results" loading={loading} />
        </>}
      </>:<>
        <div className={styles.attemptHeader}><button className={styles.backButton} type="button" onClick={closeAttempt}>← Tests & results</button><span className={styles.attemptState}>{attempt.status.replaceAll('_',' ')}</span></div>
        <div className={styles.attemptTitle}><div><span className={styles.eyebrow}>{attempt.subject_name}{attempt.chapter_name?' · '+attempt.chapter_name:''}</span><h1>{attempt.assessment_title}</h1><p>Attempt {attempt.attempt_number} · {attempt.questions.length} questions · {attempt.max_score} marks</p></div>{attempt.status==='IN_PROGRESS'&&<div className={styles.timerBox}><small>TIME REMAINING</small><b className={secondsLeft!==null&&secondsLeft<300?styles.timeWarning:''}>{secondsLeft===null?'--:--':clock(secondsLeft)}</b><span>{saving?'Saving answer…':'Answers auto-save'}</span></div>}</div>
        {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.notice} role="status">{notice}</div>}
        {attempt.translation_complete===false&&<div className={styles.languageNotice} role="status">
          <b><LocaleText hinglish="भाषा कवरेज" english="Language coverage" /></b>
          <span><LocaleText
            hinglish="कुछ test प्रश्नों की चुनी हुई भाषा में अलग, समीक्षा-स्वीकृत प्रति उपलब्ध नहीं है। जहाँ ज़रूरी हो, मूल पाठ रखा गया है; अंक और answer keys नहीं बदले गए।"
            english="Some test questions do not yet have a separately reviewed version in the selected language. Original text is retained where needed; marks and answer keys are unchanged."
          /></span>
        </div>}
        {released?<section className={styles.releasedResult} aria-live="polite">
          <div className={styles.resultHero}>
            <span className={styles.resultHeroIcon}>✓</span>
            <div><span className={styles.eyebrow}>RESULT READY</span><h2>Test submitted successfully</h2><p>Your test is closed. Here is your result; the active question navigator is no longer available.</p></div>
          </div>
          <div className={styles.resultScoreGrid}>
            <article className={styles.resultScorePrimary}>
              <small>YOUR SCORE</small>
              <strong>{resultScore} <span>/ {resultMax}</span></strong>
              <div className={styles.resultScoreProgress}><i style={{width:Math.min(100,Math.max(0,resultPercent))+'%'}}/></div>
              <b>{resultPercent}%</b><p>Overall score</p>
            </article>
            <article className={styles.resultMetric}><strong>{correctCount}</strong><span>Correct</span></article>
            <article className={styles.resultMetric}><strong>{incorrectCount}</strong><span>Incorrect</span></article>
            <article className={styles.resultMetric}><strong>{unansweredCount}</strong><span>Not answered</span></article>
          </div>
          {released&&(()=>{
            const cert=certificateEligibility[Number(attempt?.attempt_id)];
            const certificateId=Number(cert?.certificate_id||0);
            return <section className={styles.certificateResultPanel} aria-live="polite">
              <div><span className={styles.eyebrow}>CERTIFICATE</span><h3>Certificate for this result</h3>
                <p>Test certificates require an admin-created test, a released qualifying score, and completion of all published lessons in this subject.</p></div>
              {cert?.certificate_status==='ISSUED'&&certificateId>0?(
                <div className={styles.certificateActionLinks}>
                  <a href={'/api/v1/students/me/certificates/'+certificateId+'/view?format=pdf'} target="_blank" rel="noreferrer">View certificate ↗</a>
                  <a href={'/api/v1/students/me/certificates/'+certificateId+'/download?format=pdf'}>Download PDF</a>
                  <a href={'/api/v1/students/me/certificates/'+certificateId+'/download?format=png'}>Download PNG</a>
                </div>
              ):cert?.certificate_status==='REVOKED'?(
                <small className={styles.certificateEligibilityHint}>This certificate has been revoked.</small>
              ):cert?.eligible?(
                <button type="button" className={styles.certificateIssueButton}
                  disabled={issuingCertificateAttemptId!==null}
                  onClick={()=>void issueCertificateForAttempt(Number(attempt?.attempt_id))}>
                  {issuingCertificateAttemptId===Number(attempt?.attempt_id)?'Issuing…':'Issue certificate'}
                </button>
              ):(
                <small className={styles.certificateEligibilityHint}>{certificateEligibilityMessage(cert?.eligibility_reason)}</small>
              )}
            </section>;
          })()}
          <section className={styles.resultDetails}>
            <div className={styles.resultDetailsHeading}><h3>Question-wise result</h3><p>Marks and outcomes are shown for review. Submitted questions cannot be reopened or changed.</p></div>
            <div className={styles.resultReviewList}>
              {attempt.questions.map((question,questionIndex)=>{
                const outcome=question.isCorrect===true?'Correct':question.isCorrect===false?'Incorrect':isEmpty(question.answer)?'Not answered':'Reviewed';
                const outcomeClass=question.isCorrect===true?styles.reviewCorrect:question.isCorrect===false?styles.reviewIncorrect:styles.reviewNeutral;
                return <article className={styles.resultReviewItem} key={question.attemptQuestionId}>
                  <section className={styles.questionPanel}>
                    <div className={styles.questionPanelTop}>
                      <span>QUESTION {questionIndex+1} OF {attempt.questions.length}</span>
                      <b className={outcomeClass}>{outcome} · {Number(question.awardedMarks??0)} / {Number(question.maxMarks??0)} marks</b>
                    </div>
                    <div className={styles.questionProgress}><i style={{width:((questionIndex+1)/attempt.questions.length*100)+'%'}}/></div>
                    <QuestionContent question={question} response={question.answer} review disabled/>
                    <AnswerFeedback
                      outcome={question.isCorrect===true?'correct':question.isCorrect===false?'incorrect':'ungraded'}
                      title={question.isCorrect===true?'✓ Correct':question.isCorrect===false?'✕ Incorrect':isEmpty(question.answer)?'Not answered':'Answer reviewed'}
                      message={<>Marks: {question.awardedMarks??0} / {question.maxMarks}{question.teacherFeedback?<> · {question.teacherFeedback}</>:null}</>}
                      explanation={question.explanation||undefined}
                      explanationLabel="Explanation"
                    />
                  </section>
                </article>;
              })}
            </div>
          </section>
          <div className={styles.resultActions}><button className={styles.primaryButton} type="button" onClick={closeAttempt}>Back to results</button></div>
        </section>
        :(attempt.status==='AWAITING_REVIEW'||attempt.status==='GRADED')?<div className={styles.pendingResult}><span>◷</span><h2>Test submitted successfully</h2><p>Your answers are saved. Fill-in, short-answer and long-answer responses are sent to an assigned staff reviewer. Your final score and feedback will appear here after all required checking is complete.</p><small>Submitted: {fmtDate(attempt.submitted_at)}</small><button className={styles.primaryButton} type="button" onClick={closeAttempt}>Back to tests and results</button></div>
        :attempt.status==='ABANDONED'?<div className={styles.pendingResult}><h2>This attempt is closed</h2><button className={styles.primaryButton} type="button" onClick={closeAttempt}>Back to tests</button></div>
        :attempt.status!=='IN_PROGRESS'||!current?<div className={styles.pendingResult}><h2>This attempt is closed.</h2><p>You cannot return to the question navigator after submission.</p><button className={styles.primaryButton} type="button" onClick={closeAttempt}>Back to tests and results</button></div>
        :<div className={styles.examLayout}>
          <aside className={styles.questionRail}><div><b>Questions</b><small>{answeredCount}/{attempt.questions.length} answered</small></div><div className={styles.questionNumbers}>{attempt.questions.map((q,i)=><button type="button" key={q.attemptQuestionId} className={[i===index?styles.currentNumber:'',!isEmpty(responses[q.attemptQuestionId]??q.answer)?styles.answeredNumber:''].filter(Boolean).join(' ')} onClick={()=>setIndex(i)} aria-label={'Question '+(i+1)}>{i+1}</button>)}</div><div className={styles.railLegend}><span><i className={styles.legendCurrent}/>Current</span><span><i className={styles.legendAnswered}/>Answered</span></div></aside>
          <section className={styles.questionPanel}><div className={styles.questionPanelTop}><span>QUESTION {index+1} OF {attempt.questions.length}</span><b>{current.maxMarks} mark(s)</b></div><div className={styles.questionProgress}><i style={{width:((index+1)/attempt.questions.length*100)+'%'}}/></div><QuestionContent question={current} response={currentResponse} disabled={submitted} onAnswer={value=>updateResponse(current,value)}/>
            {released&&<AnswerFeedback
              outcome={current.isCorrect===true?'correct':current.isCorrect===false?'incorrect':'ungraded'}
              title={current.isCorrect===true?'✓ Correct':current.isCorrect===false?'✕ Incorrect':'Teacher checked'}
              message={<>Marks: {current.awardedMarks??0} / {current.maxMarks}{current.teacherFeedback?<> · {current.teacherFeedback}</>:null}</>}
              explanation={current.explanation||undefined}
              explanationLabel="Explanation"
            />}
            <div className={styles.questionFooter}><span>{saving?'Saving…':Object.keys(dirtyAnswers).length?'Save pending':'✓ Saved answers are preserved'}</span><div><button type="button" className={styles.secondaryButton} disabled={index===0} onClick={()=>setIndex(i=>Math.max(0,i-1))}>← Previous</button>{index<attempt.questions.length-1?<button type="button" className={styles.primaryButton} onClick={()=>setIndex(i=>Math.min(attempt.questions.length-1,i+1))}>Next question →</button>:attempt.status==='IN_PROGRESS'?<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>setShowSubmit(true)}>Review & submit</button>:null}</div></div>
          </section>
        </div>}
        {attempt.status==='IN_PROGRESS'&&showSubmit&&<div className={styles.confirmBackdrop}><section className={styles.confirmModal}><span className={styles.eyebrow}>FINAL CHECK</span><h2>Submit this test?</h2><p>You have answered {answeredCount} of {attempt.questions.length} questions. Unanswered questions will be saved as blank and marked accordingly. You cannot change answers after submission.</p><div><button type="button" className={styles.secondaryButton} onClick={()=>setShowSubmit(false)}>Keep working</button><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void submitTest(false)}>{busy?'Submitting…':'Submit test'}</button></div></section></div>}
      </>}
    </section>

    {leaderboardTest&&<div className={styles.leaderboardBackdrop} onClick={closeLeaderboard}>
      <section className={styles.leaderboardModal} role="dialog" aria-modal="true" aria-labelledby="qe-leaderboard-title" onClick={event=>event.stopPropagation()}>
        <header className={styles.leaderboardHeader}>
          <div className={styles.leaderboardHeading}>
            <span className={styles.leaderboardEyebrow}>QUANTAEDGE · STUDENT RANKINGS</span>
            <h2 id="qe-leaderboard-title">{leaderboardData?.assessment_title||leaderboardTest.title}</h2>
            <p>Top 20 performers · Higher marks rank first; faster completion breaks a marks tie.</p>
          </div>
          <button type="button" className={styles.leaderboardClose} onClick={closeLeaderboard} aria-label="Close leaderboard">×</button>
        </header>
        {leaderboardError&&<div className={styles.leaderboardInlineError} role="alert"><span>{leaderboardError}</span><button type="button" className={styles.secondaryButton} onClick={()=>void openLeaderboard(leaderboardTest)}>Try again</button></div>}
        {leaderboardLoading?<div className={styles.leaderboardLoading}><span className={styles.leaderboardSpinner}/><b>Preparing the leaderboard</b><small>Only completed tests with released scores are ranked.</small></div>
        :leaderboardData&&<>
          <div className={styles.leaderboardMetrics}>
            <div><span>PARTICIPANTS</span><b>{leaderboardData.participant_count}</b></div>
            <div><span>RANKING RULE</span><b>Marks first <i>·</i> time second</b></div>
            <div><span>DISPLAYING</span><b>{Math.min(20,leaderboardData.items.length)} of {leaderboardData.participant_count}</b></div>
          </div>
          {leaderboardData.items.length===0?<div className={styles.leaderboardEmpty}><span>♛</span><h3>The race starts here</h3><p>No released scores are available for this test yet. The leaderboard will appear as results are finalised.</p></div>
          :<>
            {leaderboardData.items.length>=3&&<div className={styles.leaderboardPodium} aria-label="Top three students">
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
                  {entry.is_current_student&&<span className={styles.leaderboardYou}>YOU</span>}
                </article>;
              })}
            </div>}
            {(leaderboardData.items.length<3||leaderboardData.items.some(item=>item.rank>3))&&<div className={styles.leaderboardTableWrap}>
              <table className={styles.leaderboardTable}>
                <thead><tr><th scope="col">Rank</th><th scope="col">Student</th><th scope="col">Marks</th><th scope="col">Time taken</th></tr></thead>
                <tbody>{leaderboardData.items.filter(item=>leaderboardData.items.length<3||item.rank>3).map(entry=><tr key={entry.rank} className={entry.is_current_student?styles.leaderboardTableMine:''}>
                  <td><span className={styles.leaderboardRankNumber}>{String(entry.rank).padStart(2,'0')}</span></td>
                  <td><div className={styles.leaderboardStudentCell}><span className={styles.leaderboardTableAvatar}>{studentInitials(entry.student_name)}</span><b>{entry.student_name}</b>{entry.is_current_student&&<span className={styles.leaderboardYou}>YOU</span>}</div></td>
                  <td><strong className={styles.leaderboardTableScore}>{formatMarks(entry.score)} <small>/ {formatMarks(entry.max_score)}</small></strong></td>
                  <td><span className={styles.leaderboardTime}><i aria-hidden="true">◷</i> {clock(Math.floor(Number(entry.time_taken_seconds)||0))}</span></td>
                </tr>)}</tbody>
              </table>
            </div>}
            {leaderboardData.current_student&&leaderboardData.current_student.rank>20&&<div className={styles.leaderboardMyStanding}>
              <div><span>YOUR CURRENT POSITION</span><b>#{leaderboardData.current_student.rank} <small>outside the Top 20</small></b></div>
              <strong>{formatMarks(leaderboardData.current_student.score)} <small>/ {formatMarks(leaderboardData.current_student.max_score)} marks</small></strong>
              <span className={styles.leaderboardTime}><i aria-hidden="true">◷</i> {clock(Math.floor(Number(leaderboardData.current_student.time_taken_seconds)||0))}</span>
            </div>}
          </>}
          <footer className={styles.leaderboardFooter}><span>Rankings use the best released attempt per student.</span><button type="button" className={styles.secondaryButton} onClick={closeLeaderboard}>Close leaderboard</button></footer>
        </>}
      </section>
    </div>}

  </main>;
}
