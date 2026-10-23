'use client';


import { apiFetch } from '../components/apiClient';
import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import AdminSidebar from '../components/AdminSidebar';
import { useUiTranslation } from '../i18n/useUiTranslation';

type AdultReview = { student_id:number; display_name:string; class_code?:string|null; status:string; reviewed_at?:string|null; expires_at?:string|null };
type ParentReview = { user_id:number; student_id:number; display_name:string; mobile_last_four?:string|null; status:string; reviewed_at?:string|null; expires_at?:string|null };
type DeletionRequest = {
  request_id:string; subject_user_public_id:string; subject_student_public_user_id?:string|null;
  request_source:string; status:string; includes_linked_children:boolean; linked_children_count:number;
  requested_at:string; updated_at?:string|null;
};
type DeletionDependency = {
  schema:string; table:string; constraint:string; target:string; sourceColumns:string[]; targetColumns:string[];
  deleteRule:string; classification:string; rowCount:number|null; countIssue?:string|null; manualReviewRequired:boolean;
};
type DeletionAssessment = {
  requestId:string; requestStatus:string; assessmentStatus:string; automaticFulfillmentAllowed:boolean;
  canMarkCompleted:boolean; erasureExecuted:boolean; classificationIsHeuristic:boolean; message:string;
  summary:Record<string,number|boolean>; dependencies:DeletionDependency[];
};

async function bodyOf(response:Response):Promise<any> {
  return await response.json().catch(()=>({}));
}

export default function AccountLifecyclePage() {
  const router=useRouter();
  const t=useUiTranslation();
  const [adultItems,setAdultItems]=useState<AdultReview[]>([]);
  const [parentItems,setParentItems]=useState<ParentReview[]>([]);
  const [deletionItems,setDeletionItems]=useState<DeletionRequest[]>([]);
  const [deletionAssessments,setDeletionAssessments]=useState<Record<string,DeletionAssessment>>({});
  const [references,setReferences]=useState<Record<string,string>>({});
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState<string|null>(null);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const load=useCallback(async()=>{
    setError('');
    try {
      const deletionStatuses=['REQUESTED','UNDER_REVIEW','AWAITING_LINKED_CHILD_DECISION'];
      const [adultResponse,parentResponse,...deletionResponses]=await Promise.all([
        apiFetch('/api/v1/admin/account-lifecycle/adult-verifications?status=PENDING',{cache:'no-store'}),
        apiFetch('/api/v1/admin/account-lifecycle/parent-eligibility?status=PENDING',{cache:'no-store'}),
        ...deletionStatuses.map(status=>apiFetch('/api/v1/admin/account-deletion-requests?status='+status,{cache:'no-store'})),
      ]);
      const allResponses=[adultResponse,parentResponse,...deletionResponses];
      if(allResponses.some(response=>response.status===401||response.status===403)){
        router.replace('/login'); return;
      }
      const [adultBody,parentBody,...deletionBodies]=await Promise.all(allResponses.map(bodyOf));
      if(!adultResponse.ok) throw new Error(adultBody.message||t('admin.lifecycle.loadAdultFailed'));
      if(!parentResponse.ok) throw new Error(parentBody.message||t('admin.lifecycle.loadParentFailed'));
      const failedDeletionResponse=deletionResponses.find((response)=>!response.ok);
      if(failedDeletionResponse) {
        const failedBody=deletionBodies[deletionResponses.indexOf(failedDeletionResponse)]||{};
        throw new Error(failedBody.message||t('admin.lifecycle.deletionLoadFailed'));
      }
      setAdultItems(Array.isArray(adultBody.items)?adultBody.items:[]);
      setParentItems(Array.isArray(parentBody.items)?parentBody.items:[]);
      setDeletionItems(deletionBodies.flatMap((body:any)=>Array.isArray(body.items)?body.items:[]));
    } catch(loadError) {
      setError(loadError instanceof Error?loadError.message:t('admin.lifecycle.loadQueuesFailed'));
    } finally { setLoading(false); }
  },[router,t]);

  useEffect(()=>{void load();},[load]);

  async function reviewAdult(item:AdultReview,approved:boolean) {
    const key='adult:'+item.student_id;
    const reference=(references[key]||'').trim();
    if(reference.length<4){setError(t('admin.lifecycle.needAdultReference'));return;}
    setBusy(key);setError('');setNotice('');
    try {
      const response=await apiFetch('/api/v1/admin/account-lifecycle/students/'+item.student_id+'/adult-verification',{
        method:'PATCH',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({adultOver18Confirmed:approved,verificationReference:reference}),
      });
      const body=await bodyOf(response);
      if(!response.ok) throw new Error(body.message||t('admin.lifecycle.saveAdultFailed'));
      setReferences(current=>({...current,[key]:''}));
      setNotice(approved?t('admin.lifecycle.adultApprovedNotice',{studentId:item.student_id}):t('admin.lifecycle.adultRejectedNotice',{studentId:item.student_id}));
      await load();
    } catch(actionError) {
      setError(actionError instanceof Error?actionError.message:t('admin.lifecycle.saveAdultFailed'));
    } finally { setBusy(null); }
  }

  async function reviewParent(item:ParentReview,eligible:boolean) {
    const key='parent:'+item.user_id;
    const reference=(references[key]||'').trim();
    if(reference.length<4){setError(t('admin.lifecycle.needParentReference'));return;}
    setBusy(key);setError('');setNotice('');
    try {
      const response=await apiFetch('/api/v1/admin/account-lifecycle/users/'+item.user_id+'/parent-eligibility',{
        method:'PATCH',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({eligible,verificationReference:reference}),
      });
      const body=await bodyOf(response);
      if(!response.ok) throw new Error(body.message||t('admin.lifecycle.saveParentFailed'));
      setReferences(current=>({...current,[key]:''}));
      setNotice(eligible?t('admin.lifecycle.parentApprovedNotice',{userId:item.user_id}):t('admin.lifecycle.parentRejectedNotice',{userId:item.user_id}));
      await load();
    } catch(actionError) {
      setError(actionError instanceof Error?actionError.message:t('admin.lifecycle.saveParentFailed'));
    } finally { setBusy(null); }
  }

  async function startDeletionReview(item:DeletionRequest) {
    const key='deletion-review:'+item.request_id;
    setBusy(key);setError('');setNotice('');
    try {
      const response=await apiFetch('/api/v1/admin/account-deletion-requests/'+encodeURIComponent(item.request_id)+'/start-review',{
        method:'POST',
      });
      const body=await bodyOf(response);
      if(!response.ok) throw new Error(body.message||t('admin.lifecycle.deletionLoadFailed'));
      setNotice(t('admin.lifecycle.deletionStatus')+': '+String(body.status||'UNDER_REVIEW'));
      await load();
    } catch(actionError) {
      setError(actionError instanceof Error?actionError.message:t('admin.lifecycle.deletionLoadFailed'));
    } finally { setBusy(null); }
  }

  async function assessDeletion(item:DeletionRequest) {
    const key='deletion-assessment:'+item.request_id;
    setBusy(key);setError('');setNotice('');
    try {
      const response=await apiFetch('/api/v1/admin/account-deletion-requests/'+encodeURIComponent(item.request_id)+'/assessment',{cache:'no-store'});
      const body=await bodyOf(response);
      if(!response.ok) throw new Error(body.message||t('admin.lifecycle.deletionRunFailed'));
      setDeletionAssessments(current=>({...current,[item.request_id]:body as DeletionAssessment}));
      setNotice(t('admin.lifecycle.deletionAssessmentTitle'));
    } catch(actionError) {
      setError(actionError instanceof Error?actionError.message:t('admin.lifecycle.deletionRunFailed'));
    } finally { setBusy(null); }
  }

  return (
    <main className="admin-shell">
      <AdminSidebar active="lifecycle" />
      <section className="admin-main">
        <header className="admin-top">
          <div><span className="admin-kicker">{t('admin.lifecycle.eyebrow')}</span><h1>{t('admin.lifecycle.title')}</h1><p>{t('admin.lifecycle.description')}</p></div>
          <Link href="/" className="button button-dark">{t('admin.lifecycle.dashboard')}</Link>
        </header>
        {error&&<div className="feedback" role="alert">{error}</div>}
        {notice&&<div className="feedback success" role="status">{notice}</div>}
        {loading?<div className="admin-panel">{t('admin.lifecycle.loading')}</div>:(
          <>
            <section className="admin-panel">
              <div className="panel-head"><div><span>{t('admin.lifecycle.adultQueue')}</span><h2>{t('admin.lifecycle.adultTitle')}</h2></div><span className="count">{t('admin.lifecycle.pendingCount',{count:adultItems.length})}</span></div>
              <p>{t('admin.lifecycle.adultEvidence')}</p>
              {!adultItems.length?<p>{t('admin.lifecycle.noAdultPending')}</p>:adultItems.map(item=>{
                const key='adult:'+item.student_id;
                return <article key={item.student_id} style={{display:'grid',gap:10,padding:'16px 0',borderTop:'1px solid var(--admin-border,#e1e5ec)'}}>
                  <div><strong>{item.display_name}</strong><small style={{display:'block',color:'var(--admin-muted,#687386)'}}>{t('admin.lifecycle.studentId',{id:item.student_id})} · {item.class_code||t('admin.lifecycle.courseLinkedLearner')}</small></div>
                  <label style={{display:'grid',gap:5,maxWidth:520}}>{t('admin.lifecycle.externalVerificationReference')}<input value={references[key]||''} onChange={e=>setReferences(v=>({...v,[key]:e.target.value}))} placeholder={t("admin.lifecycle.referencePlaceholder")} style={{padding:'10px 12px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:9}} /></label>
                  <div style={{display:'flex',gap:8,flexWrap:'wrap'}}><button className="button button-dark" disabled={busy===key} onClick={()=>void reviewAdult(item,true)}>{t('admin.lifecycle.confirmAge')}</button><button className="button" disabled={busy===key} onClick={()=>void reviewAdult(item,false)}>{t('admin.lifecycle.reject')}</button></div>
                </article>;
              })}
            </section>
            <section className="admin-panel">
              <div className="panel-head"><div><span>{t('admin.lifecycle.parentQueue')}</span><h2>{t('admin.lifecycle.parentTitle')}</h2></div><span className="count">{t('admin.lifecycle.pendingCount',{count:parentItems.length})}</span></div>
              <p>{t('admin.lifecycle.parentEligibilityRule')}</p>
              {!parentItems.length?<p>{t('admin.lifecycle.noParentPending')}</p>:parentItems.map(item=>{
                const key='parent:'+item.user_id;
                return <article key={item.user_id} style={{display:'grid',gap:10,padding:'16px 0',borderTop:'1px solid var(--admin-border,#e1e5ec)'}}>
                  <div><strong>{item.display_name}</strong><small style={{display:'block',color:'var(--admin-muted,#687386)'}}>{t('admin.lifecycle.accountAndLearner',{userId:item.user_id,studentId:item.student_id,lastFour:item.mobile_last_four||'••••'})}</small></div>
                  <label style={{display:'grid',gap:5,maxWidth:520}}>{t('admin.lifecycle.externalEligibilityReference')}<input value={references[key]||''} onChange={e=>setReferences(v=>({...v,[key]:e.target.value}))} placeholder={t("admin.lifecycle.referencePlaceholder")} style={{padding:'10px 12px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:9}} /></label>
                  <div style={{display:'flex',gap:8,flexWrap:'wrap'}}><button className="button button-dark" disabled={busy===key} onClick={()=>void reviewParent(item,true)}>{t('admin.lifecycle.approveParent')}</button><button className="button" disabled={busy===key} onClick={()=>void reviewParent(item,false)}>{t('admin.lifecycle.reject')}</button></div>
                </article>;
              })}
            </section>
            <section className="admin-panel">
              <div className="panel-head">
                <div><span>{t('admin.lifecycle.deletionQueue')}</span><h2>{t('admin.lifecycle.deletionTitle')}</h2></div>
                <span className="count">{deletionItems.length}</span>
              </div>
              <p>{t('admin.lifecycle.deletionDescription')}</p>
              <p>{t('admin.lifecycle.deletionNoErasure')}</p>
              {!deletionItems.length?<p>{t('admin.lifecycle.deletionNoRequests')}</p>:deletionItems.map(item=>{
                const reviewKey='deletion-review:'+item.request_id;
                const assessKey='deletion-assessment:'+item.request_id;
                const assessment=deletionAssessments[item.request_id];
                const summary=assessment?.summary||{};
                return <article key={item.request_id} style={{display:'grid',gap:12,padding:'16px 0',borderTop:'1px solid var(--admin-border,#e1e5ec)'}}>
                  <div>
                    <strong>{t('admin.lifecycle.deletionSubject',{reference:item.subject_user_public_id})}</strong>
                    <small style={{display:'block',color:'var(--admin-muted,#687386)'}}>
                      {t('admin.lifecycle.deletionStatus')}: {item.status} · {t('admin.lifecycle.deletionSource')}: {item.request_source} · {t('admin.lifecycle.deletionChildren',{count:Number(item.linked_children_count||0)})}
                    </small>
                  </div>
                  <div style={{display:'flex',gap:8,flexWrap:'wrap'}}>
                    {item.status==='REQUESTED'&&<button className="button button-dark" disabled={busy!==null} onClick={()=>void startDeletionReview(item)}>{busy===reviewKey?t('admin.lifecycle.loading'):t('admin.lifecycle.deletionStartReview')}</button>}
                    <button className="button" disabled={busy!==null} onClick={()=>void assessDeletion(item)}>{busy===assessKey?t('admin.lifecycle.loading'):t('admin.lifecycle.deletionAssess')}</button>
                  </div>
                  {assessment&&<div style={{display:'grid',gap:10,padding:14,border:'1px solid var(--admin-border,#d8dce5)',borderRadius:10}}>
                    <h3 style={{margin:0}}>{t('admin.lifecycle.deletionAssessmentTitle')}</h3>
                    <p style={{margin:0}}>{t('admin.lifecycle.deletionAssessmentBlocked')}</p>
                    <p style={{margin:0}}>{assessment.message}</p>
                    <div style={{display:'grid',gridTemplateColumns:'repeat(auto-fit,minmax(140px,1fr))',gap:10}}>
                      <div><small>{t('admin.lifecycle.deletionReferencesInspected')}</small><strong style={{display:'block'}}>{String(summary.foreignKeyRelationshipsInspected??0)}</strong></div>
                      <div><small>{t('admin.lifecycle.deletionAffectedRelationships')}</small><strong style={{display:'block'}}>{String(summary.affectedRelationships??0)}</strong></div>
                      <div><small>{t('admin.lifecycle.deletionRelatedRows')}</small><strong style={{display:'block'}}>{String(summary.relatedRowReferences??0)}</strong></div>
                    </div>
                    {summary.childSetChangedSinceRequest===true&&<p role="alert">{t('admin.lifecycle.deletionChildSetChanged')}</p>}
                    <div style={{overflowX:'auto'}}>
                      <table style={{width:'100%',borderCollapse:'collapse',fontSize:13}}>
                        <thead><tr>
                          <th style={{textAlign:'left',padding:8}}>{t('admin.lifecycle.deletionClassification')}</th>
                          <th style={{textAlign:'left',padding:8}}>Table</th>
                          <th style={{textAlign:'left',padding:8}}>{t('admin.lifecycle.deletionRule')}</th>
                          <th style={{textAlign:'right',padding:8}}>{t('admin.lifecycle.deletionRows',{count:0}).replace('{count}','#')}</th>
                        </tr></thead>
                        <tbody>{assessment.dependencies.map((dependency,index)=><tr key={dependency.schema+'.'+dependency.table+'.'+dependency.constraint+'-'+index} style={{borderTop:'1px solid var(--admin-border,#e1e5ec)'}}>
                          <td style={{padding:8,verticalAlign:'top'}}>{dependency.classification==='UNKNOWN'?t('admin.lifecycle.deletionUnknown'):dependency.classification}</td>
                          <td style={{padding:8,verticalAlign:'top'}}>{dependency.schema+'.'+dependency.table}<small style={{display:'block',color:'var(--admin-muted,#687386)'}}>{dependency.constraint}</small></td>
                          <td style={{padding:8,verticalAlign:'top'}}>{dependency.deleteRule}</td>
                          <td style={{padding:8,textAlign:'right',verticalAlign:'top'}}>{dependency.rowCount===null?t('admin.lifecycle.deletionUnknown'):dependency.rowCount}</td>
                        </tr>)}</tbody>
                      </table>
                    </div>
                    {assessment.dependencies.length===0&&<p>{t('admin.lifecycle.deletionRows',{count:0})}</p>}
                  </div>}
                </article>;
              })}
            </section>
          </>
        )}
      </section>
    </main>
  );
}
