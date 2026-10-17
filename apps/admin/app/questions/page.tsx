'use client';

import AdminSidebar from '../components/AdminSidebar';
import PremiumPagination from '../components/PremiumPagination';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import { useUiTranslation } from '../i18n/useUiTranslation';
import { useCallback, useContext, useEffect, useRef, useState } from 'react';

type QuestionRow = {
  question_id:number; lesson_id:number; question_type:string; prompt:string;
  explanation?:string; review_status:string; review_notes?:string|null; active:boolean;
  difficulty?:string; source_kind?:string; source_title?:string|null; source_ref?:string|null;
  source_year?:number|null; board?:string|null; marks?:number|null; exam_format?:string|null;
  lesson_title:string; lesson_status:string; chapter_name:string; class_code:string;
  class_name:string; subject_code:string; subject_name:string; topic?:string|null; subtopic?:string|null;
  options?:string; history_count?:number; latest_history_reason?:string|null;
};

type HistoryEntry = {
  id:number; event_type:string; previous_status?:string|null; new_status?:string|null;
  actor_staff_id?:number|null; actor_role?:string|null; change_reason?:string|null;
  before_snapshot?:string|null; after_snapshot?:string|null; occurred_at:string;
};

async function api(url:string,init:RequestInit={},requestFailed?:(status:number)=>string) {
  const headers=new Headers(init.headers||{});
  if(init.body)headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok)throw new Error(body.detail||body.message||body.error||(requestFailed?requestFailed(response.status):'Request failed ('+response.status+')'));
  return body;
}

function parse(value:unknown,fallback:any=null){
  if(value===null||value===undefined||value==='')return fallback;
  if(typeof value==='string'){try{return JSON.parse(value)}catch{return fallback}}
  return value;
}

const statuses=['ALL','REVIEW','REJECTED','DRAFT','APPROVED','PUBLISHED'];
function countGlobal(summary:any,statusList:string[],active?:boolean){
  return (Array.isArray(summary?.byStatus)?summary.byStatus:[])
    .filter((row:any)=>statusList.includes(String(row.review_status||'DRAFT').toUpperCase())&&(active===undefined||row.active===active))
    .reduce((total:number,row:any)=>total+Number(row.count||0),0);
}

export default function QuestionReviewPage(){
  const t=useUiTranslation();
  const tRef=useRef(t);
  const { locale }=useContext(AdminWorkspaceContext);
  useEffect(()=>{tRef.current=t;},[t]);
  const requestFailed=(statusCode:number)=>tRef.current('admin.questions.requestFailed',{status:statusCode});
  const statusLabel=(value:string)=>{
    switch(String(value||'').toUpperCase()){
      case 'ALL':return t('admin.questions.statusAll');
      case 'REVIEW':return t('admin.questions.statusReview');
      case 'REJECTED':return t('admin.questions.statusRejected');
      case 'DRAFT':return t('admin.questions.statusDraft');
      case 'APPROVED':return t('admin.questions.statusApproved');
      case 'PUBLISHED':return t('admin.questions.statusPublished');
      case 'INACTIVE':return t('admin.questions.statusInactive');
      default:return value||'—';
    }
  };
  const [rows,setRows]=useState<QuestionRow[]>([]);
  const [reviewSummary,setReviewSummary]=useState<any>(null);
  const [totalMatching,setTotalMatching]=useState(0);
  const [page,setPage]=useState(0);
  const [pageSize,setPageSize]=useState(10);
  const [status,setStatus]=useState('ALL');
  const [query,setQuery]=useState('');
  const [loading,setLoading]=useState(true);
  const [busyId,setBusyId]=useState<number|null>(null);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [historyFor,setHistoryFor]=useState<number|null>(null);
  const [history,setHistory]=useState<HistoryEntry[]>([]);
  const [historyPage,setHistoryPage]=useState(0);
  const [historyTotal,setHistoryTotal]=useState(0);
  const [historyPageSize,setHistoryPageSize]=useState(10);
  const [historyLoading,setHistoryLoading]=useState(false);
  const [canManage,setCanManage]=useState(false);
  const [permissionLoading,setPermissionLoading]=useState(true);

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const params=new URLSearchParams({status,limit:String(pageSize),offset:String(page*pageSize)});
      if(query.trim())params.set('query',query.trim());
      const [data,summary]=await Promise.all([
        api('/api/v1/admin/content/questions/review-queue?'+params.toString(),{},requestFailed),
        api('/api/v1/admin/content/questions/review-summary',{},requestFailed)
      ]);
      setRows(Array.isArray(data.items)?data.items:[]);
      setTotalMatching(Number(data.total||0));
      setReviewSummary(summary);
    }catch(e:any){setError(e.message||tRef.current('admin.questions.queueLoadFailed'));}
    finally{setLoading(false);}
  },[status,query,page,pageSize]);

  useEffect(()=>{void load();},[load]);

  useEffect(()=>{
    let active=true;
    api('/api/v1/auth/me',{},requestFailed)
      .then(me=>{
        if(!active)return;
        const role=String(me.role||'').toUpperCase();
        const permissions=Array.isArray(me.permissions)?me.permissions:[];
        setCanManage(role==='ADMIN'||permissions.includes('CONTENT_REVIEW'));
      })
      .catch(()=>{if(active)setCanManage(false);})
      .finally(()=>{if(active)setPermissionLoading(false);});
    return()=>{active=false;};
  },[]);

  async function act(row:QuestionRow,nextStatus:'APPROVED'|'REJECTED'|'DRAFT'|'REVIEW'){
    if(!canManage){
      setError(t('admin.questions.permissionDenied'));
      return;
    }
    if(!['REVIEW','PUBLISHED'].includes(String(row.lesson_status||'').toUpperCase())){
      setError(t('admin.questions.lessonNotReady'));
      return;
    }
    let notes:string|undefined;
    if(nextStatus==='REJECTED'){
      const value=window.prompt(t('admin.questions.rejectPrompt'),row.review_notes||'');
      if(value===null)return;
      notes=value.trim();
      if(!notes){setError(t('admin.questions.reasonRequired'));return;}
    }else if(nextStatus==='DRAFT'){
      const value=window.prompt(t('admin.questions.returnDraftPrompt'),row.review_notes||'');
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
      setNotice(t('admin.questions.reviewNotice',{questionId:row.question_id,status:statusLabel(nextStatus)}));
      await load();
      if(historyFor===row.question_id)await showHistory(row.question_id);
    }catch(e:any){setError(e.message||t('admin.questions.reviewSaveFailed'));}
    finally{setBusyId(null);}
  }

  async function showHistory(questionId:number,nextPage=0,nextPageSize=historyPageSize){
    setHistoryFor(questionId);setHistoryPage(nextPage);setHistoryLoading(true);setError('');
    try{
      const params=new URLSearchParams({limit:String(nextPageSize),offset:String(nextPage*nextPageSize)});
      const data=await api('/api/v1/admin/content/questions/'+questionId+'/history?'+params.toString(),{},requestFailed);
      setHistory(Array.isArray(data.history)?data.history:[]);
      setHistoryTotal(Number(data.total??(Array.isArray(data.history)?data.history.length:0)));
    }catch(e:any){setError(e.message||t('admin.questions.historyLoadFailed'));setHistory([]);setHistoryTotal(0);}
    finally{setHistoryLoading(false);}
  }

  const totalQuestions=Number(reviewSummary?.totalQuestions||0);
  const activeQuestions=Number(reviewSummary?.activeQuestions||0);
  const inactiveQuestions=Number(reviewSummary?.inactiveQuestions||0);
  const summary={
    review:countGlobal(reviewSummary,['REVIEW'],true),
    rejected:countGlobal(reviewSummary,['REJECTED'],true),
    draft:countGlobal(reviewSummary,['DRAFT'],true),
    approved:countGlobal(reviewSummary,['APPROVED','PUBLISHED'],true)
  };

  return <main className="admin-shell">
    <AdminSidebar active="questions" variant="content"/>
    <section className="admin-main">
      <header className="admin-top">
        <div><span className="admin-kicker">{t('admin.questions.eyebrow')}</span><h1>{t('admin.questions.title')}</h1><p>{t('admin.questions.description')}</p></div>
        <button type="button" className="admin-refresh" onClick={()=>void load()} title={t('admin.questions.refreshTitle')}>↻</button>
      </header>

      <div className="admin-grid stats admin-primary-stats qe-review-counts">
        <article className="admin-kpi"><span>{t('admin.questions.metricAwaiting')}</span><strong>{summary.review}</strong><small>{t('admin.questions.metricAwaitingDescription')}</small></article>
        <article className="admin-kpi"><span>{t('admin.questions.metricDrafts')}</span><strong>{summary.draft}</strong><small>{t('admin.questions.metricDraftsDescription')}</small></article>
        <article className="admin-kpi"><span>{t('admin.questions.metricRejected')}</span><strong>{summary.rejected}</strong><small>{t('admin.questions.metricRejectedDescription')}</small></article>
        <article className="admin-kpi"><span>{t('admin.questions.metricApprovedPublished')}</span><strong>{summary.approved}</strong><small>{t('admin.questions.metricApprovedPublishedDescription')}</small></article>
        <article className="admin-kpi"><span>{t('admin.questions.metricInactive')}</span><strong>{inactiveQuestions}</strong><small>{t('admin.questions.metricInactiveDescription')}</small></article>
      </div>

      <section className="admin-panel" style={{marginTop:20}}>
        <div className="admin-section-title"><div><span className="admin-kicker">{t('admin.questions.reviewQueue')}</span><h2>{t('admin.questions.allQuestions')}</h2><p style={{fontSize:12,color:"#6f7b90",marginTop:5}}>{t('admin.questions.totalSummary',{total:totalQuestions.toLocaleString(locale==='english'?'en-IN':'hi-IN'),active:activeQuestions.toLocaleString(locale==='english'?'en-IN':'hi-IN'),inactive:inactiveQuestions.toLocaleString(locale==='english'?'en-IN':'hi-IN')})}</p></div><span className="admin-live-label">{t('admin.questions.matchingCount',{count:totalMatching.toLocaleString(locale==='english'?'en-IN':'hi-IN')})}</span></div>
        <div style={{display:'flex',gap:12,flexWrap:'wrap',alignItems:'center',margin:'16px 0'}}>
          <label style={{display:'flex',flexDirection:'column',gap:5,fontSize:13,fontWeight:600}}>{t('admin.questions.filterStatus')}
            <select value={status} onChange={e=>{setPage(0);setStatus(e.target.value)}} style={{minWidth:180,padding:'10px 12px',borderRadius:10,border:'1px solid #d8dce5',background:'#fff'}}>
              {statuses.map(x=><option key={x} value={x}>{statusLabel(x)}</option>)}
            </select>
          </label>
          <label style={{display:'flex',flexDirection:'column',gap:5,fontSize:13,fontWeight:600,flex:'1 1 260px'}}>{t('admin.questions.searchLabel')}
            <input value={query} onChange={e=>{setPage(0);setQuery(e.target.value)}} placeholder={t('admin.questions.searchPlaceholder')} style={{padding:'10px 12px',borderRadius:10,border:'1px solid #d8dce5',minWidth:220}}/>
          </label>
          <button type="button" className="button button-dark" onClick={()=>void load()} disabled={loading}>{loading?t('admin.questions.loadingShort'):t('admin.questions.applyFilters')}</button>
        </div>
        <PremiumPagination page={page} total={totalMatching} pageSize={pageSize} onPageChange={setPage}
          onPageSizeChange={size=>{setPageSize(size);setPage(0);}} itemLabel={t('admin.questions.questionItemLabel')} loading={loading} />
        {error&&<div className="admin-empty-state" role="alert" style={{marginBottom:12}}><p>{error}</p></div>}
        {notice&&<p role="status" style={{padding:'12px 14px',background:'#eef8f2',borderRadius:10,color:'#22543d'}}>{notice}</p>}
        {loading?<p className="admin-loading" role="status" aria-live="polite">{t('admin.questions.loadingHistoryAndStatuses')}</p>:rows.length===0?<div className="admin-empty-state"><h3>{t('admin.questions.noQuestionsMatch')}</h3><p>{t('admin.questions.noQuestionsHint')}</p></div>:
          <div style={{display:'grid',gap:14}}>
            {rows.map(row=>{
              const options=parse(row.options,[]) as {key:string;label:string;is_correct?:boolean}[];
              const rejected=row.review_status==='REJECTED';
              const canReview=['REVIEW','PUBLISHED'].includes(String(row.lesson_status||'').toUpperCase())&&canManage&&!permissionLoading;
              return <article key={row.question_id} style={{border:'1px solid #e1e5ed',borderRadius:14,padding:16,background:'#fff'}}>
                <div style={{display:'flex',gap:10,justifyContent:'space-between',alignItems:'flex-start',flexWrap:'wrap'}}>
                  <div><div style={{display:'flex',gap:8,alignItems:'center',flexWrap:'wrap'}}><strong>{t('admin.questions.questionNumber',{questionId:row.question_id})}</strong><span className="admin-live-label">{statusLabel(row.review_status)}</span>{!row.active&&<span className="admin-live-label">{t('admin.questions.statusInactive')}</span>}</div>
                    <p style={{margin:'7px 0 3px',fontSize:13,color:'#647084'}}>{row.class_name} · {row.subject_name} · {row.chapter_name} · {row.lesson_title}</p>
                    <p style={{margin:'4px 0',fontSize:13,color:'#647084'}}>{t('admin.questions.microTopicStatus')}: <b>{statusLabel(row.lesson_status)}</b>{row.topic?' · '+t('admin.questions.topicLabel')+': '+row.topic:''}{row.subtopic?' / '+row.subtopic:''}</p>
                    <p style={{margin:'4px 0',fontSize:13,color:'#647084'}}>{t('admin.questions.questionTypeLabel')}: {row.question_type} · {t('admin.questions.difficultyLabel')}: {row.difficulty||'—'}{row.marks?' · '+t('admin.questions.marksLabel',{marks:row.marks}):''}{row.exam_format?' · '+row.exam_format:''}</p>
                    {(row.source_title||row.source_ref||row.board||row.source_year)&&<p style={{margin:'4px 0',fontSize:13,color:'#647084'}}>{t('admin.questions.sourceLabel')}:  {[row.source_title,row.board,row.source_year,row.source_ref].filter(Boolean).join(' · ')}</p>}
                  </div>
                  <button type="button" className="text-link" onClick={()=>void showHistory(row.question_id)}>{historyFor===row.question_id?t('admin.questions.historySelected'):t('admin.questions.viewFullHistory')} · {row.history_count||0}</button>
                </div>
                <h3 style={{fontSize:17,lineHeight:1.5,margin:'12px 0'}}>{row.prompt}</h3>
                {options.length>0&&<ol type="A" style={{paddingLeft:24,margin:'8px 0',display:'grid',gap:5}}>{options.map((o,i)=><li key={o.key||i}>{o.label}{o.is_correct&&<span style={{marginLeft:8,fontSize:12,fontWeight:700,color:'#147848'}}>{t('admin.questions.correctAnswer')}</span>}</li>)}</ol>}
                {row.explanation&&<p style={{fontSize:14,color:'#4b5565'}}><b>{t('admin.questions.explanation')}</b> {row.explanation}</p>}
                <div style={{marginTop:12,padding:'11px 13px',borderRadius:10,background:rejected?'#fff3ef':'#f5f7fa',color:rejected?'#893b28':'#475467'}}>
                  <b>{rejected?t('admin.questions.rejectionReason'):t('admin.questions.latestReviewerNote')}</b>
                  <p style={{margin:'5px 0 0'}}>{row.review_notes||row.latest_history_reason||(rejected?t('admin.questions.legacyRejectionNoReason'):t('admin.questions.noReviewerNote'))}</p>
                </div>
                {!canManage&&!permissionLoading&&<p style={{fontSize:13,color:'#7a4b10',margin:'12px 0 0'}}>{t('admin.questions.viewOnlyTitle')} {t('admin.questions.viewOnlyDetails')}</p>}
                {canManage&&!['REVIEW','PUBLISHED'].includes(String(row.lesson_status||'').toUpperCase())&&<p style={{fontSize:13,color:'#7a4b10',margin:'12px 0 0'}}>{t('admin.questions.reviewLockedTitle')} {t('admin.questions.reviewLockedDetails')} {t('admin.questions.open')} <a href="/content" style={{textDecoration:'underline',fontWeight:600}}>{t('admin.questions.contentStudio')}</a> {t('admin.questions.submitTopic')}</p>}
                <div style={{display:'flex',gap:8,flexWrap:'wrap',marginTop:14}}>
                  <button type="button" className="button button-dark" disabled={!canReview||busyId===row.question_id||row.review_status!=='REVIEW'} onClick={()=>void act(row,'APPROVED')}>{busyId===row.question_id?t('admin.questions.saving'):t('admin.questions.approve')}</button>
                  <button type="button" className="button" disabled={!canReview||busyId===row.question_id||row.review_status!=='REVIEW'} onClick={()=>void act(row,'REJECTED')}>{t('admin.questions.rejectReturn')}</button>
                  <button type="button" className="button" disabled={!canReview||busyId===row.question_id||row.review_status==='DRAFT'} onClick={()=>void act(row,'DRAFT')}>{t('admin.questions.returnToDraft')}</button>
                  <button type="button" className="button" disabled={!canReview||busyId===row.question_id||row.review_status==='REVIEW'} onClick={()=>void act(row,'REVIEW')}>{t('admin.questions.needsAnotherReview')}</button>
                </div>
              </article>;
            })}
          </div>
        }
      </section>

      {historyFor!==null&&<section className="admin-panel" style={{marginTop:20}}>
        <div className="admin-section-title"><div><span className="admin-kicker">{t('admin.questions.changeLog')}</span><h2>{t('admin.questions.fullHistoryTitle',{questionId:historyFor})}</h2></div><button type="button" className="text-link" onClick={()=>{setHistoryFor(null);setHistory([]);setHistoryPage(0);setHistoryTotal(0)}}>{t('admin.questions.close')}</button></div>
        {historyLoading?<p role="status" aria-live="polite">{t('admin.questions.loadingHistory')}</p>:history.length===0?<p>{t('admin.questions.noHistoryRows')}</p>:
          <div style={{display:'grid',gap:12,marginTop:14}}>
            {history.map(entry=><article key={entry.id} style={{border:'1px solid #e1e5ed',borderRadius:12,padding:14}}>
              <div style={{display:'flex',gap:10,justifyContent:'space-between',flexWrap:'wrap'}}><strong>{entry.event_type.replaceAll('_',' ')}</strong><span style={{fontSize:13,color:'#647084'}}>{entry.occurred_at?new Date(entry.occurred_at).toLocaleString(locale==='english'?'en-IN':'hi-IN'):''}</span></div>
              <p style={{margin:'8px 0',fontSize:14}}><b>{entry.previous_status||'—'}</b> → <b>{entry.new_status||'—'}</b></p>
              <p style={{margin:'4px 0',fontSize:13,color:'#647084'}}>{t('admin.questions.actor')} {entry.actor_role||t('admin.questions.systemHistoricalBaseline')}{entry.actor_staff_id?' · '+t('admin.questions.staffId',{staffId:entry.actor_staff_id}):''}</p>
              <p style={{margin:'6px 0'}}>{entry.change_reason||t('admin.questions.noReasonRecorded')}</p>
              <details style={{marginTop:8}}><summary style={{cursor:'pointer',fontWeight:600}}>{t('admin.questions.snapshotTitle')}</summary>
                <div style={{display:'grid',gridTemplateColumns:'repeat(auto-fit,minmax(240px,1fr))',gap:10,marginTop:10}}>
                  <div><b>{t('admin.questions.before')}</b><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',maxHeight:360,overflow:'auto',fontSize:11,background:'#f6f7f9',padding:10,borderRadius:8}}>{JSON.stringify(parse(entry.before_snapshot,entry.before_snapshot||t('admin.questions.noEarlierSnapshot')),null,2)}</pre></div>
                  <div><b>{t('admin.questions.after')}</b><pre style={{whiteSpace:'pre-wrap',overflowWrap:'anywhere',maxHeight:360,overflow:'auto',fontSize:11,background:'#f6f7f9',padding:10,borderRadius:8}}>{JSON.stringify(parse(entry.after_snapshot,entry.after_snapshot||t('admin.questions.noSnapshotRecorded')),null,2)}</pre></div>
                </div>
              </details>
            </article>)}
          </div>}
        <PremiumPagination page={historyPage} total={historyTotal} pageSize={historyPageSize}
          onPageChange={nextPage=>{if(historyFor!==null)void showHistory(historyFor,nextPage);}}
          onPageSizeChange={size=>{setHistoryPageSize(size);if(historyFor!==null)void showHistory(historyFor,0,size);}}
          itemLabel={t('admin.questions.historyItemLabel')} loading={historyLoading} />
      </section>}
    </section>
  </main>;
}
