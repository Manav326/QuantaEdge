'use client';

import AdminSidebar from '../components/AdminSidebar';
import { useCallback, useEffect, useMemo, useState } from 'react';
import styles from './Assessments.module.css';

type BankQuestion = { question_id:number; question_type:string; prompt:string; difficulty:string; marks?:number|null; lesson_title:string; chapter_id:number; chapter_name:string; chapter_code:string; subject_name:string; };
type Assessment = { assessment_id:number; title:string; description?:string; duration_minutes:number; max_attempts:number; status:string; class_code:string; subject_code:string; subject_name:string; chapter_id?:number|null; chapter_name?:string|null; question_count:number; max_score:number; attempt_count:number; review_count:number; assigned_grader_names?:string|null; };
type Staff = {staff_id:number;display_name:string;role:string;can_grade:boolean};
type ReviewQuestion = {attemptQuestionId:number;answerId:number;question_type:string;prompt:string;explanation?:string;options:Array<{key:string;label:string}>;answer:any;answerStatus:string;maxMarks:number|string;awardedMarks?:number|string|null;isCorrect?:boolean|null;teacherFeedback?:string|null;manualReviewRequired?:boolean};
type ReviewAttempt = {attempt_id:number;assessment_id:number;assessment_title:string;student_name:string;class_code:string;subject_name:string;status:string;final_score?:number|null;auto_score?:number|null;max_score:number;questions:ReviewQuestion[];audit?:Array<{id:number;actor_name:string;actor_role:string;action:string;previous_status?:string;new_status?:string;note?:string;occurred_at:string}>};

async function api(url:string,init:RequestInit={}) {
  const headers=new Headers(init.headers||{});
  if(init.body && !(init.body instanceof FormData))headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok)throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));
  return body;
}

function fmtDate(value?:string|null){if(!value)return '—';const date=new Date(value);return Number.isNaN(date.getTime())?'—':date.toLocaleString();}
function answerText(value:any){if(value===null||value===undefined||value==='')return 'No answer submitted';if(typeof value==='string')return value;try{return JSON.stringify(value,null,2)}catch{return String(value)}}

export default function AssessmentsPage(){
  const [classCode,setClassCode]=useState('6');
  const [subjectCode,setSubjectCode]=useState('maths');
  const [chapterId,setChapterId]=useState('');
  const [statusFilter,setStatusFilter]=useState('ALL');
  const [assessments,setAssessments]=useState<Assessment[]>([]);
  const [bank,setBank]=useState<BankQuestion[]>([]);
  const [staff,setStaff]=useState<Staff[]>([]);
  const [selectedQuestionIds,setSelectedQuestionIds]=useState<number[]>([]);
  const [search,setSearch]=useState('');
  const [title,setTitle]=useState('');
  const [description,setDescription]=useState('');
  const [duration,setDuration]=useState('45');
  const [maxAttempts,setMaxAttempts]=useState('1');
  const [activeTab,setActiveTab]=useState<'author'|'review'>('author');
  const [queue,setQueue]=useState<any[]>([]);
  const [queueStatus,setQueueStatus]=useState('AWAITING_REVIEW');
  const [query,setQuery]=useState('');
  const [review,setReview]=useState<ReviewAttempt|null>(null);
  const [marks,setMarks]=useState<Record<number,string>>({});
  const [feedback,setFeedback]=useState<Record<number,string>>({});
  const [overallNote,setOverallNote]=useState('');
  const [graderByAssessment,setGraderByAssessment]=useState<Record<number,string>>({});
  const [loading,setLoading]=useState(true);
  const [bankLoading,setBankLoading]=useState(false);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const chapters=useMemo(()=>{const m=new Map<number,{id:number;code:string;name:string}>();bank.forEach(q=>m.set(q.chapter_id,{id:q.chapter_id,code:q.chapter_code,name:q.chapter_name}));return [...m.values()]},[bank]);
  const eligibleStaff=staff.filter(s=>s.can_grade);
  const filteredBank=bank.filter(q=>!search.trim()||q.prompt.toLowerCase().includes(search.toLowerCase())||q.lesson_title.toLowerCase().includes(search.toLowerCase())||q.chapter_name.toLowerCase().includes(search.toLowerCase()));

  const loadAssessments=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const params=new URLSearchParams({classCode,subjectCode,status:statusFilter});
      const [assessmentRows,staffRows]=await Promise.all([
        api('/api/v1/admin/assessments?'+params.toString()),
        api('/api/v1/admin/assessment-staff')
      ]);
      setAssessments(Array.isArray(assessmentRows)?assessmentRows:[]);
      setStaff(Array.isArray(staffRows)?staffRows:[]);
    }catch(e){setError(e instanceof Error?e.message:'Could not load tests and staff permissions.');}
    finally{setLoading(false);}
  },[classCode,subjectCode,statusFilter]);

  const loadBank=useCallback(async()=>{
    setBankLoading(true);setError('');
    try{
      const params=new URLSearchParams({classCode,subjectCode});
      if(chapterId)params.set('chapterId',chapterId);
      const rows=await api('/api/v1/admin/assessment-question-bank?'+params.toString());
      setBank(Array.isArray(rows)?rows:[]);
      setSelectedQuestionIds([]);
    }catch(e){setError(e instanceof Error?e.message:'Question bank could not be loaded.');}
    finally{setBankLoading(false);}
  },[classCode,subjectCode,chapterId]);

  const loadQueue=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const params=new URLSearchParams({status:queueStatus});
      if(query.trim())params.set('query',query.trim());
      const rows=await api('/api/v1/admin/assessment-reviews?'+params.toString());
      setQueue(Array.isArray(rows)?rows:[]);
    }catch(e){setError(e instanceof Error?e.message:'Assessment review queue could not be loaded.');}
    finally{setLoading(false);}
  },[queueStatus,query]);

  useEffect(()=>{void loadAssessments();},[loadAssessments]);
  useEffect(()=>{if(activeTab==='review')void loadQueue();},[activeTab,loadQueue]);

  function toggleQuestion(id:number,checked:boolean){setSelectedQuestionIds(ids=>checked?[...ids,id]:ids.filter(x=>x!==id));}
  async function createAssessment(){
    if(!title.trim()){setError('Enter a title for this test.');return;}
    if(!selectedQuestionIds.length){setError('Choose at least one approved question.');return;}
    setBusy(true);setError('');setNotice('');
    try{
      const row=await api('/api/v1/admin/assessments',{method:'POST',body:JSON.stringify({
        title:title.trim(),description:description.trim(),classCode,subjectCode,
        chapterId:chapterId?Number(chapterId):null,
        durationMinutes:Number(duration),maxAttempts:Number(maxAttempts),questionIds:selectedQuestionIds
      })});
      setTitle('');setDescription('');setSelectedQuestionIds([]);
      setNotice('Draft test created with '+row.question_count+' frozen question snapshots. Publish it when ready.');
      await loadAssessments();
    }catch(e){setError(e instanceof Error?e.message:'Could not create the test.');}
    finally{setBusy(false);}
  }
  async function changeStatus(row:Assessment,status:'PUBLISHED'|'ARCHIVED'|'DRAFT'){
    setBusy(true);setError('');setNotice('');
    try{await api('/api/v1/admin/assessments/'+row.assessment_id+'/status',{method:'PATCH',body:JSON.stringify({status})});setNotice('“'+row.title+'” is now '+status.toLowerCase()+'.');await loadAssessments();}
    catch(e){setError(e instanceof Error?e.message:'Test status update failed.');}
    finally{setBusy(false);}
  }
  async function assignGrader(row:Assessment){
    const staffId=Number(graderByAssessment[row.assessment_id]||0);
    if(!staffId){setError('Choose a staff member with the assessment grading permission first.');return;}
    setBusy(true);setError('');setNotice('');
    try{await api('/api/v1/admin/assessments/'+row.assessment_id+'/graders',{method:'POST',body:JSON.stringify({staffId})});setNotice('Grader assigned to “'+row.title+'”.');await loadAssessments();}
    catch(e){setError(e instanceof Error?e.message:'Grader assignment failed.');}
    finally{setBusy(false);}
  }
  async function openReview(attemptId:number){
    setBusy(true);setError('');setNotice('');
    try{
      const data=await api('/api/v1/admin/assessment-attempts/'+attemptId);
      setReview(data);
      const nextMarks:Record<number,string>={},nextFeedback:Record<number,string>={};
      (data.questions||[]).forEach((q:ReviewQuestion)=>{if(q.manualReviewRequired){nextMarks[q.answerId]=q.awardedMarks===null||q.awardedMarks===undefined?'':String(q.awardedMarks);nextFeedback[q.answerId]=q.teacherFeedback||'';}});
      setMarks(nextMarks);setFeedback(nextFeedback);setOverallNote('');
    }catch(e){setError(e instanceof Error?e.message:'Could not open this submitted test.');}
    finally{setBusy(false);}
  }
  async function saveGrading(){
    if(!review){return;}
    const pending=review.questions.filter(q=>q.manualReviewRequired);
    const answers=[];
    for(const q of pending){
      const value=marks[q.answerId];
      if(value===undefined||value.trim()===''){setError('Enter marks for every answer awaiting review. Use 0 when no marks are earned.');return;}
      const number=Number(value);
      if(!Number.isFinite(number)||number<0||number>Number(q.maxMarks)){setError('Marks for answer #'+q.answerId+' must be between 0 and '+q.maxMarks+'.');return;}
      answers.push({answerId:q.answerId,marks:number,feedback:(feedback[q.answerId]||'').trim()});
    }
    setBusy(true);setError('');setNotice('');
    try{
      const data=await api('/api/v1/admin/assessment-attempts/'+review.attempt_id+'/grade',{
        method:'POST',body:JSON.stringify({answers,overallNote:overallNote.trim(),releaseResult:true})
      });
      setReview(data);
      const nextMarks:Record<number,string>={},nextFeedback:Record<number,string>={};
      (data.questions||[]).forEach((q:ReviewQuestion)=>{if(q.manualReviewRequired){nextMarks[q.answerId]=q.awardedMarks===null||q.awardedMarks===undefined?'':String(q.awardedMarks);nextFeedback[q.answerId]=q.teacherFeedback||'';}});
      setMarks(nextMarks);setFeedback(nextFeedback);
      setNotice(data.status==='RELEASED'?'All answers are graded and the result is released to the student.':'Saved grading. This test still has answers awaiting review.');
      await loadQueue();await loadAssessments();
    }catch(e){setError(e instanceof Error?e.message:'Could not save grading.');}
    finally{setBusy(false);}
  }

  return <main className="admin-shell">
    <AdminSidebar active="assessments" variant="content"/>
    <section className={'admin-main '+styles.page}>
      <header className="admin-top"><div><span className="admin-kicker">QUANTAEDGE LEARNING / ASSESSMENT OPERATIONS</span><h1>Tests & grading</h1><p>Create published tests from approved questions, preserve every attempt, and release checked results.</p></div><button type="button" className="admin-refresh" onClick={()=>activeTab==='author'?void loadAssessments():void loadQueue()}>↻</button></header>
      <section className={styles.hero}>
        <div className={styles.heroIcon}>✓</div><div><span>RESULTS THAT LAST</span><h2>One test attempt, one preserved record.</h2><p>Student answers and question snapshots are saved against the attempt. Manual answers stay private until an authorized grader checks and releases the result. Audit entries are test-level, never per-question.</p></div>
        <div className={styles.heroStats}><strong>{assessments.length}</strong><small>Tests in current filter</small><strong>{assessments.reduce((sum,a)=>sum+Number(a.review_count||0),0)}</strong><small>Attempts awaiting review</small></div>
      </section>
      {error&&<div className={styles.error} role="alert">{error}</div>}
      {notice&&<div className={styles.success} role="status">{notice}</div>}
      <div className={styles.tabs}><button className={activeTab==='author'?styles.activeTab:''} onClick={()=>setActiveTab('author')}>Build & publish tests</button><button className={activeTab==='review'?styles.activeTab:''} onClick={()=>setActiveTab('review')}>Teacher review queue <span>{queue.filter(row=>row.status==='AWAITING_REVIEW').length}</span></button></div>

      {activeTab==='author'?<>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>STEP 01</span><h2>Choose the curriculum</h2><p>Only approved questions from published lessons can be added. A question snapshot is frozen when the test is created.</p></div><b>01</b></div>
          <div className={styles.fields}>
            <label>Class<select value={classCode} onChange={e=>{setClassCode(e.target.value);setChapterId('');setBank([]);setSelectedQuestionIds([]);}}><option value="6">Class 6</option><option value="7">Class 7</option><option value="8">Class 8</option></select></label>
            <label>Subject<select value={subjectCode} onChange={e=>{setSubjectCode(e.target.value);setChapterId('');setBank([]);setSelectedQuestionIds([]);}}><option value="maths">Mathematics</option><option value="science">Science</option></select></label>
            <label>Chapter filter (optional)<select value={chapterId} onChange={e=>{setChapterId(e.target.value);setBank([]);setSelectedQuestionIds([]);}}><option value="">Any published chapter</option>{chapters.map(ch=><option key={ch.id} value={ch.id}>{ch.code} — {ch.name}</option>)}</select></label>
            <button type="button" className={styles.secondaryButton} onClick={()=>void loadBank()} disabled={bankLoading}>{bankLoading?'Loading…':'Load approved question bank'}</button>
          </div>
          {bank.length>0&&<div className={styles.bank}>
            <div className={styles.bankHeader}><div><b>Approved question bank</b><small>{filteredBank.length} question(s) · {selectedQuestionIds.length} selected</small></div><input aria-label="Search approved questions" value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search question or chapter…"/></div>
            <div className={styles.bankList}>{filteredBank.map(q=><label className={styles.bankRow} key={q.question_id}><input type="checkbox" checked={selectedQuestionIds.includes(q.question_id)} onChange={e=>toggleQuestion(q.question_id,e.target.checked)}/><span><b>{q.prompt}</b><small>{q.chapter_name} · {q.lesson_title} · {q.question_type} · {q.marks||1} mark(s)</small></span><code>#{q.question_id}</code></label>)}</div>
            <p className={styles.helpNote}>Correct answers remain server-side and are not sent to the student before the result is released.</p>
          </div>}
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>STEP 02</span><h2>Test details</h2><p>Submitted attempts retain the test settings and the question versions used at that time.</p></div><b>02</b></div>
          <div className={styles.fields}>
            <label className={styles.wide}>Test title<input value={title} onChange={e=>setTitle(e.target.value)} placeholder="e.g. Chapter 3 — Understanding Fractions"/></label>
            <label className={styles.wide}>Instructions / description<textarea value={description} onChange={e=>setDescription(e.target.value)} rows={3} placeholder="Explain the test rules and what students should submit."/></label>
            <label>Duration (minutes)<input type="number" min="1" max="240" value={duration} onChange={e=>setDuration(e.target.value)}/></label>
            <label>Attempts allowed<input type="number" min="1" max="10" value={maxAttempts} onChange={e=>setMaxAttempts(e.target.value)}/></label>
          </div>
          <div className={styles.submitBar}><p>{selectedQuestionIds.length} question(s) selected. Manual-answer types will be held for teacher grading.</p><button className={styles.primaryButton} type="button" onClick={()=>void createAssessment()} disabled={busy||!title.trim()||selectedQuestionIds.length===0}>{busy?'Saving…':'Create draft test'}</button></div>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>ASSESSMENT GOVERNANCE</span><h2>Test catalogue</h2><p>Publish a draft to make it available only to students enrolled in the matching class and subject.</p></div><select className={styles.filter} value={statusFilter} onChange={e=>setStatusFilter(e.target.value)}><option value="ALL">All statuses</option><option value="DRAFT">Drafts</option><option value="PUBLISHED">Published</option><option value="ARCHIVED">Archived</option></select></div>
          <div className={styles.fields+' '+styles.compactFields}><label>Class<select value={classCode} onChange={e=>setClassCode(e.target.value)}><option value="6">Class 6</option><option value="7">Class 7</option><option value="8">Class 8</option></select></label><label>Subject<select value={subjectCode} onChange={e=>setSubjectCode(e.target.value)}><option value="maths">Mathematics</option><option value="science">Science</option></select></label></div>
          {loading?<p className={styles.empty}>Loading tests…</p>:assessments.length===0?<div className={styles.empty}><b>No assessments in this filter</b><span>Create a draft from approved questions above.</span></div>:<div className={styles.testList}>{assessments.map(row=><article className={styles.testCard} key={row.assessment_id}>
            <div className={styles.testIcon}>✓</div><div className={styles.testBody}><div className={styles.testTitle}><h3>{row.title}</h3><span className={styles['status'+row.status]}>{row.status}</span></div><p>Class {row.class_code} · {row.subject_name}{row.chapter_name?' · '+row.chapter_name:''}</p><small>{row.question_count} questions · {row.max_score} marks · {row.duration_minutes} min · {row.max_attempts} attempt(s)</small><small>{row.attempt_count} submitted/started · {row.review_count} awaiting review</small>
              <div className={styles.inlineAssign}><select aria-label={'Assign grader for '+row.title} value={graderByAssessment[row.assessment_id]||''} onChange={e=>setGraderByAssessment(m=>({...m,[row.assessment_id]:e.target.value}))}><option value="">Assign staff reviewer…</option>{eligibleStaff.map(s=><option key={s.staff_id} value={s.staff_id}>{s.display_name} · {s.role}</option>)}</select><button type="button" disabled={busy||!eligibleStaff.length||!graderByAssessment[row.assessment_id]} onClick={()=>void assignGrader(row)}>Assign grader</button></div>
              {row.assigned_grader_names? <small>Assigned: {row.assigned_grader_names}</small>:null}
            </div><div className={styles.testActions}>{row.status==='DRAFT'&&<button className={styles.primaryButton} type="button" disabled={busy} onClick={()=>void changeStatus(row,'PUBLISHED')}>Publish</button>}{row.status==='PUBLISHED'&&<button className={styles.secondaryButton} type="button" disabled={busy} onClick={()=>void changeStatus(row,'ARCHIVED')}>Archive</button>}{row.status==='ARCHIVED'&&<button className={styles.secondaryButton} type="button" disabled={busy} onClick={()=>void changeStatus(row,'DRAFT')}>Restore draft</button>}</div>
          </article>)}</div>}
        </section>
      </>:<section className={styles.panel}>
        <div className={styles.panelHeading}><div><span>TEACHER REVIEW QUEUE</span><h2>Submitted tests awaiting a decision</h2><p>Assigned graders see only tests assigned to them. Administrators can review any test. This audit is test-level only.</p></div><select className={styles.filter} value={queueStatus} onChange={e=>setQueueStatus(e.target.value)}><option value="AWAITING_REVIEW">Awaiting review</option><option value="GRADED">Graded · not released</option><option value="RELEASED">Released results</option><option value="ALL">All states</option></select></div>
        <div className={styles.queueSearch}><input value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search test, student or chapter…"/><button type="button" className={styles.secondaryButton} onClick={()=>void loadQueue()}>Search</button></div>
        {loading?<p className={styles.empty}>Loading the grading queue…</p>:queue.length===0?<div className={styles.empty}><b>No submitted tests to show</b><span>New attempts requiring teacher review appear here automatically.</span></div>:<div className={styles.testList}>{queue.map(row=><article className={styles.testCard} key={row.attempt_id}><div className={styles.testIcon}>✎</div><div className={styles.testBody}><div className={styles.testTitle}><h3>{row.assessment_title}</h3><span className={styles['status'+row.status]}>{row.status}</span></div><p>{row.student_name} · Class {row.class_code} · {row.subject_name}{row.chapter_name?' · '+row.chapter_name:''}</p><small>Submitted {fmtDate(row.submitted_at)} · Attempt {row.attempt_number} · {row.question_count} questions · {row.pending_answers} awaiting manual check</small><small>Result {row.status==='RELEASED'?row.final_score+' / '+row.max_score:'not yet released'}</small></div><div className={styles.testActions}><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void openReview(row.attempt_id)}>{row.status==='AWAITING_REVIEW'?'Review test':'Open record'}</button></div></article>)}</div>}
      </section>}

      {review&&<div className={styles.modalBackdrop} role="presentation" onMouseDown={e=>{if(e.target===e.currentTarget)setReview(null);}}>
        <section className={styles.reviewModal} role="dialog" aria-modal="true" aria-labelledby="review-title">
          <div className={styles.modalHeader}><div><span>TEST-LEVEL REVIEW</span><h2 id="review-title">{review.assessment_title}</h2><p>{review.student_name} · Class {review.class_code} · {review.status}</p></div><button type="button" aria-label="Close review" onClick={()=>setReview(null)}>×</button></div>
          <div className={styles.resultStats}><div><small>Test status</small><b>{review.status}</b></div><div><small>Maximum marks</small><b>{review.max_score}</b></div><div><small>Current result</small><b>{review.status==='RELEASED'?String(review.final_score):'Not released'}</b></div></div>
          <div className={styles.reviewQuestions}>{review.questions.map((q,index)=><article key={q.attemptQuestionId} className={styles.reviewQuestion}><div className={styles.questionHeading}><span>QUESTION {index+1} · {q.question_type}</span><b>{q.maxMarks} marks</b></div><h3>{q.prompt}</h3>{q.options?.length>0&&<ul>{q.options.map(o=><li key={o.key}>{o.label}</li>)}</ul>}<div className={styles.studentAnswer}><small>STUDENT'S SAVED ANSWER</small><p>{answerText(q.answer)}</p></div>
            {q.manualReviewRequired&&<div className={styles.gradeFields}><label>Marks awarded (0–{q.maxMarks})<input type="number" min="0" max={Number(q.maxMarks)} step="0.25" value={marks[q.answerId]??''} onChange={e=>setMarks(m=>({...m,[q.answerId]:e.target.value}))}/></label><label>Feedback visible to student<textarea rows={2} maxLength={2000} value={feedback[q.answerId]??''} onChange={e=>setFeedback(m=>({...m,[q.answerId]:e.target.value}))} placeholder="Explain how the student can improve."/></label></div>}
            {!q.manualReviewRequired&&<div className={styles.autoGrade}>Auto-graded · {q.awardedMarks??0} / {q.maxMarks} marks{q.isCorrect===true?' · Correct':q.isCorrect===false?' · Incorrect':''}</div>}
            {q.teacherFeedback&&!q.manualReviewRequired&&<p className={styles.feedbackText}>{q.teacherFeedback}</p>}
          </article>)}</div>
          <label className={styles.overallNote}>Overall test review note (optional)<textarea rows={2} maxLength={1200} value={overallNote} onChange={e=>setOverallNote(e.target.value)} placeholder="Test-level note for the audit trail. Do not put question-by-question details here."/></label>
          {review.audit?.length?<section className={styles.auditBox}><div><span>AUDIT TRAIL</span><h3>Who checked this test</h3><p>Test-level events only; individual question decisions are not written to the audit log.</p></div><div className={styles.auditList}>{review.audit.map(item=><article key={item.id}><span>{item.action.replaceAll('_',' ')}</span><b>{item.actor_name} · {item.actor_role}</b><small>{fmtDate(item.occurred_at)} · {item.previous_status||'—'} → {item.new_status||'—'}</small>{item.note&&<p>{item.note}</p>}</article>)}</div></section>:null}
          <div className={styles.modalFooter}><button type="button" className={styles.secondaryButton} onClick={()=>setReview(null)}>Close</button>{review.status!=='RELEASED'&&review.status!=='IN_PROGRESS'&&<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void saveGrading()}>{busy?'Saving…':'Save grading & release when complete'}</button>}</div>
        </section>
      </div>}
    </section>
  </main>;
}
