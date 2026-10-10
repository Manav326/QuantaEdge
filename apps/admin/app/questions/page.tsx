'use client';

import AdminSidebar from '../components/AdminSidebar';
import { useCallback, useEffect, useState } from 'react';

type QuestionRow = {
  question_id:number; lesson_id:number; question_type:string; prompt:string;
  explanation?:string; review_status:string; review_notes?:string|null; active:boolean;
  lesson_title:string; lesson_status:string; chapter_name:string; class_code:string;
  class_name:string; subject_code:string; subject_name:string; topic?:string|null; subtopic?:string|null;
  options?:string; history_count?:number; latest_history_reason?:string|null;
};

type HistoryEntry = {
  id:number; event_type:string; previous_status?:string|null; new_status?:string|null;
  actor_staff_id?:number|null; actor_role?:string|null; change_reason?:string|null;
  before_snapshot?:string|null; after_snapshot?:string|null; occurred_at:string;
};

async function api(url:string,init:RequestInit={}) {
  const headers=new Headers(init.headers||{});
  if(init.body)headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok)throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));
  return body;
}

function parse(value:unknown,fallback:any=null){
  if(value===null||value===undefined||value==='')return fallback;
  if(typeof value==='string'){try{return JSON.parse(value)}catch{return fallback}}
  return value;
}

const statuses=['ALL','REVIEW','REJECTED','DRAFT','APPROVED','PUBLISHED'];

export default function QuestionReviewPage(){
  const [rows,setRows]=useState<QuestionRow[]>([]);
  const [status,setStatus]=useState('ALL');
  const [query,setQuery]=useState('');
  const [loading,setLoading]=useState(true);
  const [busyId,setBusyId]=useState<number|null>(null);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [historyFor,setHistoryFor]=useState<number|null>(null);
  const [history,setHistory]=useState<HistoryEntry[]>([]);
  const [historyLoading,setHistoryLoading]=useState(false);

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const params=new URLSearchParams({status,limit:'200'});
      if(query.trim())params.set('query',query.trim());
      const data=await api('/api/v1/admin/content/questions/review-queue?'+params.toString());
      setRows(Array.isArray(data)?data:[]);
    }catch(e:any){setError(e.message||'Question queue could not be loaded.');}
    finally{setLoading(false);}
  },[status,query]);

  useEffect(()=>{void load();},[load]);

  async function act(row:QuestionRow,nextStatus:'APPROVED'|'REJECTED'|'DRAFT'|'REVIEW'){
    if(row.lesson_status!=='REVIEW'){
      setError('Submit this micro-topic for review in Content Studio before changing question review status.');
      return;
    }
    let notes:string|undefined;
    if(nextStatus==='REJECTED'){
      const value=window.prompt('Explain clearly what the author must correct before resubmission:',row.review_notes||'');
      if(value===null)return;
      notes=value.trim();
      if(!notes){setError('A rejection reason is required.');return;}
    }else if(nextStatus==='DRAFT'){
      const value=window.prompt('Reason for returning this question to draft:',row.review_notes||'');
      if(value===null)return;
      notes=value.trim()||'Returned to draft for correction.';
    }else if(nextStatus==='REVIEW'){
      notes='Reviewer requested another review.';
    }
    setBusyId(row.question_id);setError('');setNotice('');
    try{
      await api('/api/v1/admin/content/lessons/'+row.lesson_id+'/questions/'+row.question_id+'/review',{
        method:'POST',body:JSON.stringify({status:nextStatus,reviewNotes:notes})
      });
      setNotice('Question #'+row.question_id+' changed to '+nextStatus+'. The event and question snapshot were saved to its history.');
      await load();
      if(historyFor===row.question_id)await showHistory(row.question_id);
    }catch(e:any){setError(e.message||'Question review could not be saved.');}
    finally{setBusyId(null);}
  }

  async function showHistory(questionId:number){
    setHistoryFor(questionId);setHistoryLoading(true);setError('');
    try{
      const data=await api('/api/v1/admin/content/questions/'+questionId+'/history');
      setHistory(Array.isArray(data.history)?data.history:[]);
    }catch(e:any){setError(e.message||'Question history could not be loaded.');setHistory([]);}
    finally{setHistoryLoading(false);}
  }

  const summary={
    review:rows.filter(r=>r.review_status==='REVIEW').length,
    rejected:rows.filter(r=>r.review_status==='REJECTED').length,
    draft:rows.filter(r=>r.review_status==='DRAFT').length,
    approved:rows.filter(r=>['APPROVED','PUBLISHED'].includes(r.review_status)).length
  };

  return <main className="admin-shell">
    <AdminSidebar active="questions" variant="content"/>
    <section className="admin-main">
      <header className="admin-top">
        <div><span className="admin-kicker">QUANTAEDGE LEARNING / CONTENT GOVERNANCE</span><h1>Question review & history</h1><p>A dedicated parking area for draft, rejected, pending, approved and published questions.</p></div>
        <button type="button" className="admin-refresh" onClick={()=>void load()} title="Refresh questions">↻</button>
      </header>

      <div className="admin-grid stats admin-primary-stats">
        <article className="admin-kpi"><span>Needs review</span><strong>{summary.review}</strong><small>Submitted for a decision</small></article>
        <article className="admin-kpi"><span>Rejected / returned</span><strong>{summary.rejected}</strong><small>Keep the feedback visible</small></article>
        <article className="admin-kpi"><span>Draft</span><strong>{summary.draft}</strong><small>Not ready for student use</small></article>
        <article className="admin-kpi"><span>Approved / published</span><strong>{summary.approved}</strong><small>Only approved content is eligible for practice</small></article>
      </div>

      <section className="admin-panel" style={{marginTop:20}}>
        <div className="admin-section-title"><div><span className="admin-kicker">REVIEW QUEUE</span><h2>All questions</h2></div><span className="admin-live-label">{rows.length} shown</span></div>
        <div style={{display:'flex',gap:12,flexWrap:'wrap',alignItems:'center',margin:'16px 0'}}>
          <label style={{display:'flex',flexDirection:'column',gap:5,fontSize:13,fontWeight:600}}>Status
            <select value={status} onChange={e=>setStatus(e.target.value)} style={{minWidth:180,padding:'10px 12px',borderRadius:10,border:'1px solid #d8dce5',background:'#fff'}}>
              {statuses.map(x=><option key={x} value={x}>{x==='ALL'?'All statuses':x}</option>)}
            </select>
          </label>
          <label style={{display:'flex',flexDirection:'column',gap:5,fontSize:13,fontWeight:600,flex:'1 1 260px'}}>Search question, topic, chapter or micro-topic
            <input value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search question text…" style={{padding:'10px 12px',borderRadius:10,border:'1px solid #d8dce5',minWidth:220}}/>
          </label>
          <button type="button" className="button button-dark" onClick={()=>void load()} disabled={loading}>{loading?'Loading…':'Apply filters'}</button>
        </div>
        {error&&<div className="admin-empty-state" role="alert" style={{marginBottom:12}}><p>{error}</p></div>}
        {notice&&<p role="status" style={{padding:'12px 14px',background:'#eef8f2',borderRadius:10,color:'#22543d'}}>{notice}</p>}
        {loading?<p className="admin-loading">Loading question history and current statuses…</p>:rows.length===0?<div className="admin-empty-state"><h3>No questions match this filter</h3><p>Try another status or search term. New questions appear after saving them in Content Studio.</p></div>:
          <div style={{display:'grid',gap:14}}>
            {rows.map(row=>{
              const options=parse(row.options,[]) as {key:string;label:string}[];
              const rejected=row.review_status==='REJECTED';
              const canReview=row.lesson_status==='REVIEW';
              return <article key={row.question_id} style={{border:'1px solid #e1e5ed',borderRadius:14,padding:16,background:'#fff'}}>
                <div style={{display:'flex',gap:10,justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap'}}>
                  <div><div style={{display:'flex',gap:8,alignItems:'center',flexWrap:'wrap'}}><strong>Question #{row.question_id}</strong><span className="admin-live-label">{row.review_status}</span>{!row.active&&<span className="admin-live-label">INACTIVE</span>}</div>
                    <p style={{margin:'7px 0 3px',fontSize:13,color:'#647084'}}>{row.class_name} · {row.subject_name} · {row.chapter_name} · {row.lesson_title}</p>
                    <p style={{margin:'4px 0',fontSize:13,color:'#647084'}}>Micro-topic status: <b>{row.lesson_status}</b>{row.topic?' · Topic: '+row.topic:''}{row.subtopic?' / '+row.subtopic:''}</p>
                  </div>
                  <button type="button" className="text-link" onClick={()=>void showHistory(row.question_id)}>{historyFor===row.question_id?'History selected':'View full history'} · {row.history_count||0}</button>
                </div>
                <h3 style={{fontSize:17,lineHeight:1.5,margin:'12px 0'}}>{row.prompt}</h3>
                {options.length>0&&<ol type="A" style={{paddingLeft:24,margin:'8px 0',display:'grid',gap:5}}>{options.map((o,i)=><li key={o.key||i}>{o.label}</li>)}</ol>}
                {row.explanation&&<p style={{fontSize:14,color:'#4b5565'}}><b>Explanation:</b> {row.explanation}</p>}
                <div style={{marginTop:12,padding:'11px 13px',borderRadius:10,background:rejected?'#fff3ef':'#f5f7fa',color:rejected?'#893b28':'#475467'}}>
                  <b>{rejected?'Reason for rejection / return':'Latest reviewer note'}</b>
                  <p style={{margin:'5px 0 0'}}>{row.review_notes||row.latest_history_reason||(rejected?'This is a legacy rejection with no saved reason. The earlier reason cannot be reconstructed; review it and add a clear note.':'No reviewer note saved yet.')}</p>
                </div>
                {!canReview&&<p style={{fontSize:13,color:'#7a4b10',margin:'12px 0 0'}}>Review actions are locked until this micro-topic is submitted for review. The question remains parked here and is not shown to students unless approved and published. Open <a href="/content" style={{textDecoration:'underline',fontWeight:600}}>Content Studio</a> to revise or submit its micro-topic.</p>}
                <div style={{display:'flex',gap:8,flexWrap:'wrap',marginTop:14}}>
                  <button type="button" className="button button-dark" disabled={!canReview||busyId===row.question_id||row.review_status==='APPROVED'||row.review_status==='PUBLISHED'} onClick={()=>void act(row,'APPROVED')}>{busyId===row.question_id?'Saving…':'Approve'}</button>
                  <button type="button" className="button" disabled={!canReview||busyId===row.question_id||row.review_status==='REJECTED'} onClick={()=>void act(row,'REJECTED')}>Reject / return with reason</button>
                  <button type="button" className="button" disabled={!canReview||busyId===row.question_id||row.review_status==='DRAFT'} onClick={()=>void act(row,'DRAFT')}>Return to draft</button>
                  <button type="button" className="button" disabled={!canReview||busyId===row.question_id||row.review_status==='REVIEW'} onClick={()=>void act(row,'REVIEW')}>Needs another review</button>
                </div>
              </article>;
            })}
          </div>
        }
      </section>

      {historyFor!==null&&<section className="admin-panel" style={{marginTop:20}}>
        <div className="admin-section-title"><div><span className="admin-kicker">QUESTION CHANGE LOG</span><h2>Question #{historyFor} — full history</h2></div><button type="button" className="text-link" onClick={()=>{setHistoryFor(null);setHistory([])}}>Close</button></div>
        {historyLoading?<p>Loading history…</p>:history.length===0?<p>No history rows were found.</p>:
          <div style={{display:'grid',gap:12,marginTop:14}}>
            {history.map(entry=><article key={entry.id} style={{border:'1px solid #e1e5ed',borderRadius:12,padding:14}}>
              <div style={{display:'flex',gap:10,justifyContent:'space-between',flexWrap:'wrap'}}><strong>{entry.event_type.replaceAll('_',' ')}</strong><span style={{fontSize:13,color:'#647084'}}>{entry.occurred_at?new Date(entry.occurred_at).toLocaleString():''}</span></div>
              <p style={{margin:'8px 0',fontSize:14}}><b>{entry.previous_status||'—'}</b> → <b>{entry.new_status||'—'}</b></p>
              <p style={{margin:'4px 0',fontSize:13,color:'#647084'}}>Actor: {entry.actor_role||'system / historical baseline'}{entry.actor_staff_id?' · Staff ID '+entry.actor_staff_id:''}</p>
              <p style={{margin:'6px 0'}}>{entry.change_reason||'No reason was recorded for this older event.'}</p>
              <details style={{marginTop:8}}><summary style={{cursor:'pointer',fontWeight:600}}>Before / after question snapshot</summary>
                <div style={{display:'grid',gridTemplateColumns:'repeat(auto-fit,minmax(240px,1fr))',gap:10,marginTop:10}}>
                  <div><b>Before</b><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',maxHeight:360,overflow:'auto',fontSize:11,background:'#f6f7f9',padding:10,borderRadius:8}}>{JSON.stringify(parse(entry.before_snapshot,entry.before_snapshot||'No earlier snapshot'),null,2)}</pre></div>
                  <div><b>After</b><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',maxHeight:360,overflow:'auto',fontSize:11,background:'#f6f7f9',padding:10,borderRadius:8}}>{JSON.stringify(parse(entry.after_snapshot,entry.after_snapshot||'No snapshot recorded'),null,2)}</pre></div>
                </div>
              </details>
            </article>)}
          </div>}
      </section>}
    </section>
  </main>;
}
