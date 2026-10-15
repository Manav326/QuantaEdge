'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import AdminSidebar from '../components/AdminSidebar';

type AdultReview = { student_id:number; display_name:string; class_code?:string|null; status:string; reviewed_at?:string|null; expires_at?:string|null };
type ParentReview = { user_id:number; student_id:number; display_name:string; mobile_last_four?:string|null; status:string; reviewed_at?:string|null; expires_at?:string|null };

async function bodyOf(response:Response):Promise<any> {
  return await response.json().catch(()=>({}));
}

export default function AccountLifecyclePage() {
  const router=useRouter();
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
      if(!adultResponse.ok) throw new Error(adultBody.message||'Unable to load adult-verification requests.');
      if(!parentResponse.ok) throw new Error(parentBody.message||'Unable to load parent-eligibility requests.');
      setAdultItems(Array.isArray(adultBody.items)?adultBody.items:[]);
      setParentItems(Array.isArray(parentBody.items)?parentBody.items:[]);
    } catch(loadError) {
      setError(loadError instanceof Error?loadError.message:'Unable to load account-lifecycle queues.');
    } finally { setLoading(false); }
  },[router]);

  useEffect(()=>{void load();},[load]);

  async function reviewAdult(item:AdultReview,approved:boolean) {
    const key='adult:'+item.student_id;
    const reference=(references[key]||'').trim();
    if(reference.length<4){setError('Enter the external verification reference used for the review.');return;}
    setBusy(key);setError('');setNotice('');
    try {
      const response=await fetch('/api/v1/admin/account-lifecycle/students/'+item.student_id+'/adult-verification',{
        method:'PATCH',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({adultOver18Confirmed:approved,verificationReference:reference}),
      });
      const body=await bodyOf(response);
      if(!response.ok) throw new Error(body.message||'Unable to save adult verification.');
      setReferences(current=>({...current,[key]:''}));
      setNotice('Student '+item.student_id+': adult verification '+body.status.toLowerCase()+'.');
      await load();
    } catch(actionError) {
      setError(actionError instanceof Error?actionError.message:'Unable to save adult verification.');
    } finally { setBusy(null); }
  }

  async function reviewParent(item:ParentReview,eligible:boolean) {
    const key='parent:'+item.user_id;
    const reference=(references[key]||'').trim();
    if(reference.length<4){setError('Enter the external parent-eligibility verification reference.');return;}
    setBusy(key);setError('');setNotice('');
    try {
      const response=await fetch('/api/v1/admin/account-lifecycle/users/'+item.user_id+'/parent-eligibility',{
        method:'PATCH',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({eligible,verificationReference:reference}),
      });
      const body=await bodyOf(response);
      if(!response.ok) throw new Error(body.message||'Unable to save parent eligibility.');
      setReferences(current=>({...current,[key]:''}));
      setNotice('Account '+item.user_id+': parent eligibility '+body.status.toLowerCase()+'.');
      await load();
    } catch(actionError) {
      setError(actionError instanceof Error?actionError.message:'Unable to save parent eligibility.');
    } finally { setBusy(null); }
  }

  return (
    <main className="admin-shell">
      <AdminSidebar active="lifecycle" />
      <section className="admin-main">
        <header className="admin-top">
          <div><span className="admin-kicker">IDENTITY & ELIGIBILITY</span><h1>Account lifecycle reviews</h1><p>Review over-18 verification and parent eligibility separately before a role transition.</p></div>
          <Link href="/" className="button button-dark">← Dashboard</Link>
        </header>
        {error&&<div className="feedback" role="alert">{error}</div>}
        {notice&&<div className="feedback success" role="status">{notice}</div>}
        {loading?<div className="admin-panel">Loading verification queues…</div>:(
          <>
            <section className="admin-panel">
              <div className="panel-head"><div><span>QUEUE 01</span><h2>Adult verification · strictly over 18</h2></div><span className="count">{adultItems.length} pending</span></div>
              <p>Verify the original evidence through the approved process. QuantaEdge stores only a hash of the reference, not a date of birth or identity document.</p>
              {!adultItems.length?<p>No pending adult-verification requests.</p>:adultItems.map(item=>{
                const key='adult:'+item.student_id;
                return <article key={item.student_id} style={{display:'grid',gap:10,padding:'16px 0',borderTop:'1px solid var(--admin-border,#e1e5ec)'}}>
                  <div><strong>{item.display_name}</strong><small style={{display:'block',color:'var(--admin-muted,#687386)'}}>Student ID {item.student_id} · {item.class_code||'Course-linked learner'}</small></div>
                  <label style={{display:'grid',gap:5,maxWidth:520}}>External verification reference<input value={references[key]||''} onChange={e=>setReferences(v=>({...v,[key]:e.target.value}))} placeholder="Reference from the approved review process" style={{padding:'10px 12px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:9}} /></label>
                  <div style={{display:'flex',gap:8,flexWrap:'wrap'}}><button className="button button-dark" disabled={busy===key} onClick={()=>void reviewAdult(item,true)}>Confirm age strictly over 18</button><button className="button" disabled={busy===key} onClick={()=>void reviewAdult(item,false)}>Reject</button></div>
                </article>;
              })}
            </section>
            <section className="admin-panel">
              <div className="panel-head"><div><span>QUEUE 02</span><h2>Parent eligibility</h2></div><span className="count">{parentItems.length} pending</span></div>
              <p>Approve only after the separate guardian/parent eligibility checks are complete. Adult verification alone is not sufficient.</p>
              {!parentItems.length?<p>No pending parent-eligibility requests.</p>:parentItems.map(item=>{
                const key='parent:'+item.user_id;
                return <article key={item.user_id} style={{display:'grid',gap:10,padding:'16px 0',borderTop:'1px solid var(--admin-border,#e1e5ec)'}}>
                  <div><strong>{item.display_name}</strong><small style={{display:'block',color:'var(--admin-muted,#687386)'}}>Account ID {item.user_id} · Learner ID {item.student_id} · Mobile ending {item.mobile_last_four||'••••'}</small></div>
                  <label style={{display:'grid',gap:5,maxWidth:520}}>External eligibility reference<input value={references[key]||''} onChange={e=>setReferences(v=>({...v,[key]:e.target.value}))} placeholder="Reference from the approved review process" style={{padding:'10px 12px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:9}} /></label>
                  <div style={{display:'flex',gap:8,flexWrap:'wrap'}}><button className="button button-dark" disabled={busy===key} onClick={()=>void reviewParent(item,true)}>Approve parent eligibility</button><button className="button" disabled={busy===key} onClick={()=>void reviewParent(item,false)}>Reject</button></div>
                </article>;
              })}
            </section>
          </>
        )}
      </section>
    </main>
  );
}
