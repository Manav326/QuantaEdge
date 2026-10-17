'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import AdminSidebar from '../components/AdminSidebar';
import { useUiTranslation } from '../i18n/useUiTranslation';

type AdultReview = { student_id:number; display_name:string; class_code?:string|null; status:string; reviewed_at?:string|null; expires_at?:string|null };
type ParentReview = { user_id:number; student_id:number; display_name:string; mobile_last_four?:string|null; status:string; reviewed_at?:string|null; expires_at?:string|null };

async function bodyOf(response:Response):Promise<any> {
  return await response.json().catch(()=>({}));
}

export default function AccountLifecyclePage() {
  const router=useRouter();
  const t=useUiTranslation();
  const [adultItems,setAdultItems]=useState<AdultReview[]>([]);
  const [parentItems,setParentItems]=useState<ParentReview[]>([]);
  const [references,setReferences]=useState<Record<string,string>>({});
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState<string|null>(null);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const load=useCallback(async()=>{
    setError('');
    try {
      const [adultResponse,parentResponse]=await Promise.all([
        fetch('/api/v1/admin/account-lifecycle/adult-verifications?status=PENDING',{cache:'no-store'}),
        fetch('/api/v1/admin/account-lifecycle/parent-eligibility?status=PENDING',{cache:'no-store'}),
      ]);
      if([adultResponse.status,parentResponse.status].some(status=>status===401||status===403)){
        router.replace('/login'); return;
      }
      const [adultBody,parentBody]=await Promise.all([bodyOf(adultResponse),bodyOf(parentResponse)]);
      if(!adultResponse.ok) throw new Error(adultBody.message||t('admin.lifecycle.loadAdultFailed'));
      if(!parentResponse.ok) throw new Error(parentBody.message||t('admin.lifecycle.loadParentFailed'));
      setAdultItems(Array.isArray(adultBody.items)?adultBody.items:[]);
      setParentItems(Array.isArray(parentBody.items)?parentBody.items:[]);
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
      const response=await fetch('/api/v1/admin/account-lifecycle/students/'+item.student_id+'/adult-verification',{
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
      const response=await fetch('/api/v1/admin/account-lifecycle/users/'+item.user_id+'/parent-eligibility',{
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
          </>
        )}
      </section>
    </main>
  );
}
