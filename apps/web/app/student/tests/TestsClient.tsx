'use client';

import { LocaleText } from '../../components/LanguageProvider';
import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './Tests.module.css';

type AssessmentCard = {
  assessment_id:number;title:string;description?:string;duration_minutes:number;max_attempts:number;
  attempts_used:number;question_count:number;max_score:number;subject_code:string;subject_name:string;
  chapter_name?:string|null;in_progress_attempt_id?:number|null;latest_result_attempt_id?:number|null;
};
type AttemptQuestion = {
  attemptQuestionId:number;answerId:number;question_type:string;prompt:string;explanation?:string;
  options:Array<{key:string;label:string}>;answer:any;answerStatus:string;maxMarks:number|string;
  isCorrect?:boolean|null;awardedMarks?:number|string|null;teacherFeedback?:string|null;
};
type Attempt = {
  attempt_id:number;assessment_id:number;attempt_number:number;status:string;started_at:string;deadline_at:string;
  submitted_at?:string|null;released_at?:string|null;assessment_title:string;subject_name:string;chapter_name?:string|null;
  questions:AttemptQuestion[];resultReleased:boolean;final_score?:number|string|null;auto_score?:number|string|null;max_score:number|string;
};
type Result = {attempt_id:number;assessment_id:number;attempt_number:number;status:string;submitted_at:string;released_at:string;final_score:number|string;max_score:number|string;assessment_title:string;subject_name:string;chapter_name?:string|null;question_count:number};

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

export default function TestsClient(){
  const router=useRouter();
  const [tests,setTests]=useState<AssessmentCard[]>([]);
  const [results,setResults]=useState<Result[]>([]);
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
  const attemptRef=useRef<Attempt|null>(null);
  const dirtyRef=useRef<Record<number,any>>({});
  const saveQueueRef=useRef<Promise<void>>(Promise.resolve());
  const submitFnRef=useRef<(automatic?:boolean)=>Promise<void>>(async()=>{});
  const autoSubmitted=useRef(false);

  const loadOverview=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const [catalog,studentResults]=await Promise.all([
        api('/api/v1/learning/assessments'),
        api('/api/v1/learning/assessment-results')
      ]);
      setTests(Array.isArray(catalog)?catalog:[]);
      setResults(Array.isArray(studentResults)?studentResults:[]);
    }catch(e:any){if(e?.auth){router.replace('/login/student');return;}setError(e instanceof Error?e.message:'Could not load your tests and results.');}
    finally{setLoading(false);}
  },[router]);
  useEffect(()=>{void loadOverview();},[loadOverview]);
  useEffect(()=>{attemptRef.current=attempt;},[attempt]);

  const filteredTests=tests.filter(t=>subjectFilter==='ALL'||t.subject_code===subjectFilter);
  const current=attempt?.questions?.[index];
  const currentResponse=current?responses[current.attemptQuestionId]??'':'';
  const released=attempt?.status==='RELEASED';
  const submitted=Boolean(attempt&&attempt.status!=='IN_PROGRESS');
  const answeredCount=attempt?.questions?.filter(q=>!isEmpty(responses[q.attemptQuestionId]??q.answer)).length||0;

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

  async function openTest(testId:number){
    setBusy(true);setError('');setNotice('');setAttempt(null);setShowSubmit(false);autoSubmitted.current=false;
    try{
      const data=await api('/api/v1/learning/assessments/'+testId+'/attempts',{method:'POST'});
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
    }catch(e:any){if(e?.auth){router.replace('/login/student');return;}setError(e instanceof Error?e.message:'Could not open this result.');}
    finally{setBusy(false);}
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
      setNotice(data.status==='RELEASED'?'Your test was submitted and the result has been saved.': 'Your test was submitted. Written answers are waiting for teacher review; your result will appear here after release.');
      await loadOverview();
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
    <header className={styles.header}><Link href="/student" className={styles.brand}><span>Q</span><b>QuantaEdge<small>STUDENT LEARNING</small></b></Link><nav><Link href="/student/learn"><LocaleText hinglish="पढ़ाई" english="Learning"/></Link><Link href="/student/textbooks"><LocaleText hinglish="किताबें" english="Textbooks"/></Link><Link href="/student">Home</Link></nav></header>
    <section className={styles.wrap}>
      {!attempt?<>
        <div className={styles.hero}><div><span className={styles.eyebrow}>TESTS · RESULTS · PROGRESS</span><h1><LocaleText hinglish="अपनी तैयारी जाँचें। अपनी प्रगति सुरक्षित रखें।" english="Test your learning. Keep every result." /></h1><p><LocaleText hinglish="हर test attempt save होता है। अगर किसी answer को teacher की जाँच चाहिए, result approve होने तक सुरक्षित रहेगा।" english="Every test attempt is saved. Answers that need teacher review stay safely recorded until the result is released." /></p></div><div className={styles.heroArt} aria-hidden="true"><span>✓</span><i/><b/><small>YOUR<br/>PROGRESS</small></div></div>
        {error&&<div className={styles.error} role="alert">{error}</div>}
        <div className={styles.tabs}><button type="button" className={tab==='tests'?styles.activeTab:''} onClick={()=>setTab('tests')}>Available tests <span>{tests.length}</span></button><button type="button" className={tab==='results'?styles.activeTab:''} onClick={()=>setTab('results')}>My results <span>{results.length}</span></button></div>
        {tab==='tests'?<>
          <div className={styles.toolbar}><div><span className={styles.eyebrow}>YOUR ASSESSMENTS</span><h2>Tests available to you</h2><p>Tests are shown only for your enrolled class and subjects.</p></div><label>Subject<select value={subjectFilter} onChange={e=>setSubjectFilter(e.target.value)}><option value="ALL">All subjects</option><option value="maths">Mathematics</option><option value="science">Science</option></select></label></div>
          {loading?<div className={styles.empty}>Loading your tests…</div>:filteredTests.length===0?<div className={styles.empty}><span>✓</span><b>No published tests yet</b><small>Your teacher’s published tests will appear here.</small></div>:<div className={styles.testGrid}>{filteredTests.map(test=>{
            const started=Boolean(test.in_progress_attempt_id);
            const used=Number(test.attempts_used||0);
            const resultAvailable=Boolean(test.latest_result_attempt_id);
            const exhausted=!started&&used>=Number(test.max_attempts);
            return <article className={styles.testCard} key={test.assessment_id}><div className={styles.cardTop}><span className={styles.testSymbol}>✓</span><span className={styles.subjectChip}>{test.subject_code==='maths'?'Mathematics':'Science'}</span></div><h3>{test.title}</h3><p>{test.description||test.chapter_name||test.subject_name}</p><div className={styles.cardMeta}><span>▤ {test.question_count} questions</span><span>◷ {test.duration_minutes} min</span><span>★ {test.max_score} marks</span></div><div className={styles.attemptTrack}><span>Attempts used</span><b>{used} / {test.max_attempts}</b></div><div className={styles.attemptBar}><i style={{width:Math.min(100,used/Math.max(1,test.max_attempts)*100)+'%'}}/></div><div className={styles.cardActions}><button type="button" className={styles.primaryButton} disabled={busy||exhausted} onClick={()=>void openTest(test.assessment_id)}>{busy?'Opening…':started?'Resume attempt':exhausted?'Attempts used':'Start test'}</button>{resultAvailable&&<button type="button" className={styles.resultLink} disabled={busy} onClick={()=>void openResult(Number(test.latest_result_attempt_id))}>Latest result ↗</button>}</div>{exhausted&&!resultAvailable&&<small className={styles.muted}>You have used all allowed attempts.</small>}</article>;
          })}</div>}
        </>:<>
          <div className={styles.toolbar}><div><span className={styles.eyebrow}>SAVED RESULTS</span><h2>Your test history</h2><p>Only released results appear here. Pending teacher checks are not shown as final scores.</p></div><button className={styles.secondaryButton} type="button" onClick={()=>void loadOverview()}>Refresh</button></div>
          {loading?<div className={styles.empty}>Loading saved results…</div>:results.length===0?<div className={styles.empty}><span>▤</span><b>No released results yet</b><small>When you finish a test, your score and teacher feedback will be saved here after any required review.</small></div>:<div className={styles.resultList}>{results.map(result=><article key={result.attempt_id} className={styles.resultCard}><span className={styles.resultIcon}>✓</span><div><h3>{result.assessment_title}</h3><p>{result.subject_name}{result.chapter_name?' · '+result.chapter_name:''} · Attempt {result.attempt_number}</p><small>Released {fmtDate(result.released_at)} · {result.question_count} questions</small></div><div className={styles.score}><b>{result.final_score} <small>/ {result.max_score}</small></b><span>{Number(result.max_score)>0?Math.round(Number(result.final_score)/Number(result.max_score)*100):0}%</span></div><button type="button" className={styles.resultOpen} disabled={busy} onClick={()=>void openResult(result.attempt_id)}>View feedback →</button></article>)}</div>}
        </>}
      </>:<>
        <div className={styles.attemptHeader}><button className={styles.backButton} type="button" onClick={closeAttempt}>← Tests & results</button><span className={styles.attemptState}>{attempt.status.replaceAll('_',' ')}</span></div>
        <div className={styles.attemptTitle}><div><span className={styles.eyebrow}>{attempt.subject_name}{attempt.chapter_name?' · '+attempt.chapter_name:''}</span><h1>{attempt.assessment_title}</h1><p>Attempt {attempt.attempt_number} · {attempt.questions.length} questions · {attempt.max_score} marks</p></div>{attempt.status==='IN_PROGRESS'&&<div className={styles.timerBox}><small>TIME REMAINING</small><b className={secondsLeft!==null&&secondsLeft<300?styles.timeWarning:''}>{secondsLeft===null?'--:--':clock(secondsLeft)}</b><span>{saving?'Saving answer…':'Answers auto-save'}</span></div>}</div>
        {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.notice} role="status">{notice}</div>}
        {attempt.status==='AWAITING_REVIEW'||attempt.status==='GRADED'?<div className={styles.pendingResult}><span>◷</span><h2>Test submitted successfully</h2><p>Your answers are preserved. Some answers need a teacher to check them, so your final score and feedback will appear after the result is released.</p><small>Submitted: {fmtDate(attempt.submitted_at)}</small><button className={styles.primaryButton} type="button" onClick={closeAttempt}>Back to tests and results</button></div>
        :attempt.status==='ABANDONED'?<div className={styles.pendingResult}><h2>This attempt is closed</h2><button className={styles.primaryButton} type="button" onClick={closeAttempt}>Back to tests</button></div>
        :!current?<div className={styles.pendingResult}><h2>No questions are available in this attempt.</h2><button className={styles.primaryButton} type="button" onClick={closeAttempt}>Back to tests</button></div>
        :<div className={styles.examLayout}>
          <aside className={styles.questionRail}><div><b>Questions</b><small>{answeredCount}/{attempt.questions.length} answered</small></div><div className={styles.questionNumbers}>{attempt.questions.map((q,i)=><button type="button" key={q.attemptQuestionId} className={[i===index?styles.currentNumber:'',!isEmpty(responses[q.attemptQuestionId]??q.answer)?styles.answeredNumber:''].filter(Boolean).join(' ')} onClick={()=>setIndex(i)} aria-label={'Question '+(i+1)}>{i+1}</button>)}</div><div className={styles.railLegend}><span><i className={styles.legendCurrent}/>Current</span><span><i className={styles.legendAnswered}/>Answered</span></div></aside>
          <section className={styles.questionPanel}><div className={styles.questionPanelTop}><span>QUESTION {index+1} OF {attempt.questions.length}</span><b>{current.maxMarks} mark(s)</b></div><div className={styles.questionProgress}><i style={{width:((index+1)/attempt.questions.length*100)+'%'}}/></div><h2>{current.prompt}</h2>{current.options?.length>0&&<div className={styles.options}>{current.options.map(o=><label key={o.key} className={[styles.option,answerText(currentResponse)===o.key?styles.optionSelected:''].join(' ')}><input type="radio" name={'question-'+current.attemptQuestionId} value={o.key} checked={answerText(currentResponse)===o.key} disabled={submitted} onChange={()=>updateResponse(current,o.key)}/><span className={styles.optionKey}>{o.key}</span><span>{o.label}</span></label>)}</div>}
            {(!current.options||current.options.length===0)&&<label className={styles.answerField}><span>{current.question_type==='NUMERICAL'?'Your numerical answer':'Your answer'}</span>{current.question_type==='INPUT'||current.question_type==='NUMERICAL'?<input value={answerText(currentResponse)} disabled={submitted} type={current.question_type==='NUMERICAL'?'number':'text'} onChange={e=>updateResponse(current,e.target.value)} placeholder="Type your answer here…"/>:<textarea value={answerText(currentResponse)} disabled={submitted} rows={6} onChange={e=>updateResponse(current,e.target.value)} placeholder={['MATCH','ORDER','CASE_BASED','ASSERTION_REASON'].includes(current.question_type)?'Write your answer or reasoning clearly…':'Enter your answer here…'}/>}<small>{['SHORT_ANSWER','LONG_ANSWER','CASE_BASED','SOURCE_BASED','DIAGRAM','MAP','ASSERTION_REASON','MATCH','ORDER'].includes(current.question_type)?'This answer will be saved and checked by your teacher.':''}</small></label>}
            {released&&<div className={styles.answerFeedback}><b>{current.isCorrect===true?'✓ Correct':current.isCorrect===false?'Review this answer':'Teacher checked'}</b><span>Marks: {current.awardedMarks??0} / {current.maxMarks}</span>{current.teacherFeedback&&<p>{current.teacherFeedback}</p>}{current.explanation&&<p>{current.explanation}</p>}</div>}
            <div className={styles.questionFooter}><span>{saving?'Saving…':Object.keys(dirtyAnswers).length?'Save pending':'✓ Saved answers are preserved'}</span><div><button type="button" className={styles.secondaryButton} disabled={index===0} onClick={()=>setIndex(i=>Math.max(0,i-1))}>← Previous</button>{index<attempt.questions.length-1?<button type="button" className={styles.primaryButton} onClick={()=>setIndex(i=>Math.min(attempt.questions.length-1,i+1))}>Next question →</button>:attempt.status==='IN_PROGRESS'?<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>setShowSubmit(true)}>Review & submit</button>:null}</div></div>
          </section>
        </div>}
        {attempt.status==='IN_PROGRESS'&&showSubmit&&<div className={styles.confirmBackdrop}><section className={styles.confirmModal}><span className={styles.eyebrow}>FINAL CHECK</span><h2>Submit this test?</h2><p>You have answered {answeredCount} of {attempt.questions.length} questions. Unanswered questions will be saved as blank and marked accordingly. You cannot change answers after submission.</p><div><button type="button" className={styles.secondaryButton} onClick={()=>setShowSubmit(false)}>Keep working</button><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void submitTest(false)}>{busy?'Submitting…':'Submit test'}</button></div></section></div>}
      </>}
    </section>
  </main>;
}
