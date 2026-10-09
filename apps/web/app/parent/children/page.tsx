'use client';

import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type Child = {
  id:number;
  display_name:string;
  class_code:string;
  board:string;
  language:string;
  track_codes?:string;
  login_username?:string|null;
};
type Account = { parentName:string; activeChildren:number; maxChildren:number; remainingSlots:number };
type Draft = {displayName:string;classCode:string;language:string;username:string;password:string;trackCodes:string[]};
type CredentialDraft = {username:string;password:string};

const emptyDraft:Draft={displayName:'',classCode:'7',language:'hi',username:'',password:'',trackCodes:['maths','science']};

async function readJson(response:Response):Promise<any>{
  const raw=await response.text();
  try{return raw?JSON.parse(raw):{};}catch{
    throw new Error(`Server returned an unexpected response (HTTP ${response.status}).`);
  }
}

export default function ParentChildrenPage(){
  const router=useRouter();
  const [account,setAccount]=useState<Account|null>(null);
  const [children,setChildren]=useState<Child[]>([]);
  const [draft,setDraft]=useState<Draft>(emptyDraft);
  const [trackDrafts,setTrackDrafts]=useState<Record<number,string[]>>({});
  const [credentialDrafts,setCredentialDrafts]=useState<Record<number,CredentialDraft>>({});
  const [consent,setConsent]=useState(false);
  const [busy,setBusy]=useState(false);
  const [busyTrack,setBusyTrack]=useState<number|null>(null);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  async function load(){
    setError('');
    const meResponse=await fetch('/api/v1/auth/me');
    if(!meResponse.ok){router.replace('/login');return;}
    let me=await readJson(meResponse);
    if(me.role!=='PARENT'&&me.role!=='ADMIN'){router.replace('/login');return;}
    const [accountResponse,childrenResponse]=await Promise.all([
      fetch('/api/v1/guardians/account'),fetch('/api/v1/guardians/children')
    ]);
    const accountBody=await readJson(accountResponse);
    const childrenBody=await readJson(childrenResponse);
    if(!accountResponse.ok)throw new Error(accountBody.message||'Unable to load parent account.');
    if(!childrenResponse.ok)throw new Error(childrenBody.message||'Unable to load child profiles.');
    setAccount(accountBody);
    setChildren(childrenBody);
    setTrackDrafts(Object.fromEntries(childrenBody.map((child:Child)=>[
      child.id,(child.track_codes||'').split(',').filter(Boolean)
    ])));
    setCredentialDrafts(current=>Object.fromEntries(childrenBody.map((child:Child)=>[
      child.id,{username:child.login_username||current[child.id]?.username||'',password:''}
    ])));
  }

  useEffect(()=>{load().catch((e:any)=>setError(e.message||'Unable to load profiles'));},[router]);

  function toggleTrack(studentId:number,code:string,checked:boolean){
    setTrackDrafts(current=>{
      const next=new Set(current[studentId]||[]);
      checked?next.add(code):next.delete(code);
      return {...current,[studentId]:[...next]};
    });
  }

  async function createChild(){
    setError('');setNotice('');setBusy(true);
    try{
      const response=await fetch('/api/v1/guardians/children',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({...draft,consentAccepted:consent})
      });
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to create the student profile.');
      setDraft(emptyDraft);setConsent(false);
      await load();
      setNotice('Student profile ban gaya hai aur login details save ho gayi hain. Ab bachcha aapke banaye username aur password se sign in kar sakta hai.');
    }catch(e:any){setError(e.message||'Unable to create student profile.');}
    finally{setBusy(false);}
  }

  async function saveCredentials(child:Child){
    setError('');setNotice('');
    const draft=credentialDrafts[child.id]||{username:'',password:''};
    try{
      const response=await fetch('/api/v1/guardians/children/'+child.id+'/credentials',{
        method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(draft)
      });
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to save student login details.');
      await load();
      setNotice('Login details save ho gayi hain for '+child.display_name+'. Username aur password bachche ke saath safely share karein.');
    }catch(e:any){setError(e.message||'Unable to save student login details.');}
  }

  async function saveTracks(child:Child){
    setError('');setNotice('');setBusyTrack(child.id);
    try{
      const trackCodes=trackDrafts[child.id]||[];
      const response=await fetch(`/api/v1/guardians/children/${child.id}/tracks`,{
        method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({trackCodes})
      });
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to update tracks.');
      await load();
      setNotice(`Learning tracks saved for ${child.display_name}.`);
    }catch(e:any){setError(e.message||'Unable to update tracks.');}
    finally{setBusyTrack(null);}
  }

  async function archiveChild(child:Child){
    if(!window.confirm(`Archive ${child.display_name}'s profile? Their learning history is retained, but this parent link will be revoked.`))return;
    setError('');setNotice('');
    try{
      const response=await fetch(`/api/v1/guardians/children/${child.id}`,{method:'DELETE'});
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to archive the profile.');
      await load();
      setNotice(`${child.display_name}'s profile was archived. Historical learning records were retained.`);
    }catch(e:any){setError(e.message||'Unable to archive profile.');}
  }

  const trackOptions=[{code:'maths',label:'गणित · Maths'},{code:'science',label:'विज्ञान · Science'}];

  return <main className="parent-app">
    <header className="parent-header">
      <QuantaEdgeBrand variant="compact" />
      <span>Parent account</span>
      <Link href="/parent" className="text-link">← Learning report</Link>
    </header>
    <section className="parent-dashboard parent-manage">
      <div className="parent-manage-hero">
        <div><span className="eyebrow">FAMILY LEARNING</span><h1>Bachchon ke profiles</h1>
          <p>Apne account limit tak profiles banayein, har bachche ke subjects choose karein, aur unka learning access alag rakhein.</p></div>
        <div className="parent-capacity"><span>Active profiles</span><strong>{account?account.activeChildren:'—'} <small>/ {account?account.maxChildren:'—'}</small></strong><span>{account?.remainingSlots??'—'} profile slots remaining</span></div>
      </div>

      {error&&<div className="feedback" role="alert">{error}</div>}
      {notice&&<div className="parent-notice" role="status">{notice}</div>}

      <section className="parent-manage-panel">
        <div className="parent-manage-panel-head"><div><span className="eyebrow">STUDENT PROFILES</span><h2>Aapke bachche</h2></div><span className="count">{children.length} active</span></div>
        {!children.length?<div className="parent-empty"><strong>Abhi koi active child profile nahi hai</strong><p>Neeche pehla profile banayein aur bachche ke liye subjects choose karein.</p></div>:<div className="parent-child-list">
          {children.map(child=><article className="parent-child-card" key={child.id}>
            <div className="parent-child-overview">
              <div className="parent-child-avatar">{child.display_name.trim().slice(0,1)||'S'}</div>
              <div className="parent-child-name"><h3>{child.display_name}</h3><p>Class {child.class_code} · {child.board} · Hindi</p>
                <div className="track-pill-row">{(trackDrafts[child.id]||[]).map(code=><span className="track-pill" key={code}>{code==='maths'?'गणित':'विज्ञान'}</span>)}</div>
              </div>
              <button type="button" className="parent-archive-button" onClick={()=>archiveChild(child)}>Profile archive karein</button>
            </div>
            <div className="parent-child-credentials">
              <div className="parent-credentials-heading"><strong>Student login details</strong><small>Step 1: yahan username aur password set karein. Step 2: yeh details bachche ke saath share karein. Student Login par aapka registered mobile number, username aur password use hoga.</small></div>
              <div className="parent-credentials-status" role="status">
                <strong className={child.login_username?'is-ready':'is-pending'}>{child.login_username?'✓ Student login ready hai':'Student login abhi set nahi hua hai'}</strong>
                <p>{child.login_username?'Is bachche ka login ready hai; ab sign in kar sakta hai.':'Bachcha abhi sign in nahi kar sakta. Neeche username aur password banayein, phir “Login details save karein” select karein.'}</p>
              </div>
              {child.login_username&&<p className="parent-credentials-current">Current username: <strong>{child.login_username}</strong></p>}
              <div className="parent-child-form parent-credentials-form">
                <label>Student username<input value={credentialDrafts[child.id]?.username||''} onChange={e=>setCredentialDrafts(current=>({...current,[child.id]:{username:e.target.value,password:current[child.id]?.password||''}}))} placeholder="e.g. aarav07" autoComplete="off" /></label>
                <label>Password set / reset karein<input type="password" value={credentialDrafts[child.id]?.password||''} onChange={e=>setCredentialDrafts(current=>({...current,[child.id]:{username:current[child.id]?.username||'',password:e.target.value}}))} placeholder="Kam se kam 8 characters" autoComplete="new-password" /></label>
              </div>
              <button type="button" className="button button-light button-small" disabled={(credentialDrafts[child.id]?.username||'').trim().length<3||(credentialDrafts[child.id]?.password||'').length<8} onClick={()=>void saveCredentials(child)}>Login details save karein</button>
            </div>
            <div className="parent-track-editor">
              <div><strong>Learning access</strong><small>Only selected subjects appear in the student's learning area.</small></div>
              <div className="parent-track-options">{trackOptions.map(option=><label key={option.code}>
                <input type="checkbox" checked={(trackDrafts[child.id]||[]).includes(option.code)}
                  onChange={e=>toggleTrack(child.id,option.code,e.target.checked)}/>
                <span>{option.label}</span>
              </label>)}</div>
              <button className="button button-dark button-small" disabled={busyTrack===child.id||(trackDrafts[child.id]||[]).length===0}
                onClick={()=>saveTracks(child)}>{busyTrack===child.id?'Saving…':'Save subjects'}</button>
            </div>
          </article>)}
        </div>}
      </section>

      <section className="parent-manage-panel">
        <div className="parent-manage-panel-head"><div><span className="eyebrow">ADD A CHILD</span><h2>Naya student profile</h2><p>Bachche ka profile aur login ek saath banayein. Form complete karke username aur password set karne ke baad hi bachcha sign in kar paayega.</p></div></div>
        {(account?.remainingSlots??0)<=0
          ? <div className="parent-empty"><strong>Aapke account ki profile limit poori ho gayi hai.</strong><p>Profile limit badhane ke liye support se contact karein. Existing profiles aur learning history available rahengi.</p></div>
          : <div className="parent-child-form">
            <label>Bachche ka naam<input value={draft.displayName} onChange={e=>setDraft({...draft,displayName:e.target.value})} placeholder="Bachche ka naam daalein" autoComplete="off"/></label>
            <label>Class<select value={draft.classCode} onChange={e=>setDraft({...draft,classCode:e.target.value})}><option value="6">Class 6</option><option value="7">Class 7</option><option value="8">Class 8</option></select></label>
            <label>Student username<input value={draft.username} onChange={e=>setDraft({...draft,username:e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g,'').slice(0,32)})} placeholder="e.g. aarav07" autoComplete="off"/></label>
            <label>Student password<input type="password" value={draft.password} onChange={e=>setDraft({...draft,password:e.target.value})} placeholder="Kam se kam 8 characters" autoComplete="new-password"/></label>
            <fieldset className="parent-track-fieldset"><legend>Subjects choose karein</legend>{trackOptions.map(option=><label key={option.code}><input type="checkbox" checked={draft.trackCodes.includes(option.code)} onChange={e=>setDraft({...draft,trackCodes:e.target.checked?[...draft.trackCodes,option.code]:draft.trackCodes.filter(code=>code!==option.code)})}/><span>{option.label}</span></label>)}</fieldset>
            <label className="consent-row"><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/><span>Main bachche ka authorised guardian hoon aur profile banane aur learning records rakhne ki permission deta/deti hoon.</span></label>
            <button className="button button-dark" disabled={busy||draft.displayName.trim().length<2||draft.username.trim().length<3||draft.password.length<8||draft.trackCodes.length===0||!consent} onClick={createChild}>{busy?'Profile ban raha hai…':'Child profile banayein →'}</button>
          </div>}
      </section>
    </section>
  </main>;
}
