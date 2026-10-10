'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';

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
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
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
      setNotice(tx('Student profile बन गया है और login details save हो गई हैं। अब बच्चा आपके बनाए username और password से sign in कर सकता है।','Student profile and login details are saved. Your child can now sign in with the username and password you created.'));
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
      setNotice(tx('Login details '+child.display_name+' के लिए save हो गई हैं। Username और password बच्चे के साथ safely share करें.','Login details for '+child.display_name+' have been saved. Share the username and password with your child securely.'));
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
      setNotice(tx(`${child.display_name} के learning subjects save हो गए हैं।`,`Learning subjects for ${child.display_name} have been saved.`));
    }catch(e:any){setError(e.message||'Unable to update tracks.');}
    finally{setBusyTrack(null);}
  }

  async function archiveChild(child:Child){
    if(!window.confirm(tx(`${child.display_name} का profile archive करें? Learning history बनी रहेगी, लेकिन parent link हट जाएगा.`,`Archive ${child.display_name}'s profile? Their learning history will be retained, but this parent link will be revoked.`)))return;
    setError('');setNotice('');
    try{
      const response=await fetch(`/api/v1/guardians/children/${child.id}`,{method:'DELETE'});
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to archive the profile.');
      await load();
      setNotice(tx(`${child.display_name} का profile archive हो गया है। पुराने learning records सुरक्षित हैं।`,`The profile for ${child.display_name} has been archived. Previous learning records are retained.`));
    }catch(e:any){setError(e.message||'Unable to archive profile.');}
  }

  const trackOptions=[{code:'maths',label:'गणित · Maths'},{code:'science',label:'विज्ञान · Science'}];

  return <main className="parent-app">
    <header className="parent-header">
      <QuantaEdgeBrand variant="compact" />
      <span><LocaleText hinglish="Parent account" english="Parent account" /></span>
      <Link href="/parent" className="text-link"><LocaleText hinglish="← Learning report" english="← Learning report" /></Link>
    </header>
    <section className="parent-dashboard parent-manage">
      <div className="parent-manage-hero">
        <div><span className="eyebrow"><LocaleText hinglish="FAMILY LEARNING" english="FAMILY LEARNING" /></span><h1><LocaleText hinglish="बच्चों के Profiles" english="Children’s profiles" /></h1>
          <p><LocaleText hinglish="अपने account limit तक profiles बनाएँ, हर बच्चे के subjects चुनें और उनका learning access अलग रखें।" english="Create profiles up to your account limit, choose each child’s subjects, and keep their learning access separate." /></p></div>
        <div className="parent-capacity"><span><LocaleText hinglish="Active profiles" english="Active profiles" /></span><strong>{account?account.activeChildren:'—'} <small>/ {account?account.maxChildren:'—'}</small></strong><span>{account?.remainingSlots??'—'} <LocaleText hinglish="profile slots बाकी" english="profile slots remaining" /></span></div>
      </div>

      {error&&<div className="feedback" role="alert">{error}</div>}
      {notice&&<div className="parent-notice" role="status">{notice}</div>}

      <section className="parent-manage-panel">
        <div className="parent-manage-panel-head"><div><span className="eyebrow"><LocaleText hinglish="STUDENT PROFILES" english="STUDENT PROFILES" /></span><h2><LocaleText hinglish="आपके बच्चे" english="Your children" /></h2></div><span className="count">{children.length} <LocaleText hinglish="active" english="active" /></span></div>
        {!children.length?<div className="parent-empty"><strong><LocaleText hinglish="अभी कोई active child profile नहीं है" english="No active child profile yet" /></strong><p><LocaleText hinglish="नीचे पहला profile बनाएँ और बच्चे के लिए subjects चुनें।" english="Create the first profile below and choose subjects for your child." /></p></div>:<div className="parent-child-list">
          {children.map(child=><article className="parent-child-card" key={child.id}>
            <div className="parent-child-overview">
              <div className="parent-child-avatar">{child.display_name.trim().slice(0,1)||'S'}</div>
              <div className="parent-child-name"><h3>{child.display_name}</h3><p>Class {child.class_code} · {child.board} · <LocaleText hinglish="हिन्दी माध्यम" english="Hindi medium" /></p>
                <div className="track-pill-row">{(trackDrafts[child.id]||[]).map(code=><span className="track-pill" key={code}>{code==='maths'?'गणित':'विज्ञान'}</span>)}</div>
              </div>
              <button type="button" className="parent-archive-button" onClick={()=>archiveChild(child)}><LocaleText hinglish="Profile archive करें" english="Archive profile" /></button>
            </div>
            <div className="parent-child-credentials">
              <div className="parent-credentials-heading"><strong><LocaleText hinglish="Student login details" english="Student login details" /></strong><small><LocaleText hinglish="Step 1: यहाँ username और password set करें। Step 2: ये details बच्चे के साथ share करें। Student Login पर आपका registered mobile number, username और password use होगा।" english="Step 1: Set a username and password here. Step 2: Share these details with your child. Student Login uses your registered mobile number, the username, and the password." /></small></div>
              <div className="parent-credentials-status" role="status">
                <strong className={child.login_username?'is-ready':'is-pending'}>{child.login_username?<LocaleText hinglish="✓ Student login ready है" english="✓ Student login is ready" />:<LocaleText hinglish="Student login अभी set नहीं हुआ है" english="Student login is not set yet" />}</strong>
                <p>{child.login_username?<LocaleText hinglish="इस बच्चे का login ready है; अब sign in कर सकता है।" english="This child can now sign in." />:<LocaleText hinglish="बच्चा अभी sign in नहीं कर सकता। नीचे username और password बनाएँ, फिर “Login details save करें” चुनें।" english="Your child cannot sign in yet. Create a username and password below, then select “Save login details”." />}</p>
              </div>
              {child.login_username&&<p className="parent-credentials-current"><LocaleText hinglish="Current username:" english="Current username:" /> <strong>{child.login_username}</strong></p>}
              <div className="parent-child-form parent-credentials-form">
                <label><LocaleText hinglish="Student username" english="Student username" /><input value={credentialDrafts[child.id]?.username||''} onChange={e=>setCredentialDrafts(current=>({...current,[child.id]:{username:e.target.value,password:current[child.id]?.password||''}}))} placeholder={tx('जैसे aarav07','e.g. aarav07')} autoComplete="off" /></label>
                <label><LocaleText hinglish="Password set / reset करें" english="Set or reset password" /><input type="password" value={credentialDrafts[child.id]?.password||''} onChange={e=>setCredentialDrafts(current=>({...current,[child.id]:{username:current[child.id]?.username||'',password:e.target.value}}))} placeholder={tx('कम से कम 8 characters','At least 8 characters')} autoComplete="new-password" /></label>
              </div>
              <button type="button" className="button button-light button-small" disabled={(credentialDrafts[child.id]?.username||'').trim().length<3||(credentialDrafts[child.id]?.password||'').length<8} onClick={()=>void saveCredentials(child)}><LocaleText hinglish="Login details save करें" english="Save login details" /></button>
            </div>
            <div className="parent-track-editor">
              <div><strong><LocaleText hinglish="Learning access" english="Learning access" /></strong><small><LocaleText hinglish="सिर्फ selected subjects ही student के learning area में दिखेंगे।" english="Only selected subjects appear in the student’s learning area." /></small></div>
              <div className="parent-track-options">{trackOptions.map(option=><label key={option.code}>
                <input type="checkbox" checked={(trackDrafts[child.id]||[]).includes(option.code)}
                  onChange={e=>toggleTrack(child.id,option.code,e.target.checked)}/>
                <span>{locale==='english'?(option.code==='maths'?'Mathematics':'Science'):(option.label)}</span>
              </label>)}</div>
              <button className="button button-dark button-small" disabled={busyTrack===child.id||(trackDrafts[child.id]||[]).length===0}
                onClick={()=>saveTracks(child)}>{busyTrack===child.id?<LocaleText hinglish="Save हो रहा है…" english="Saving…" />:<LocaleText hinglish="Subjects save करें" english="Save subjects" />}</button>
            </div>
          </article>)}
        </div>}
      </section>

      <section className="parent-manage-panel">
        <div className="parent-manage-panel-head"><div><span className="eyebrow"><LocaleText hinglish="ADD A CHILD" english="ADD A CHILD" /></span><h2><LocaleText hinglish="नया student profile" english="New student profile" /></h2><p><LocaleText hinglish="बच्चे का profile और login एक साथ बनाएँ। Form पूरा करके username और password set करने के बाद ही बच्चा sign in कर पाएगा।" english="Create your child’s profile and login together. Your child can sign in once you complete the form and set a username and password." /></p></div></div>
        {(account?.remainingSlots??0)<=0
          ? <div className="parent-empty"><strong><LocaleText hinglish="आपके account की profile limit पूरी हो गई है।" english="You have reached your account’s profile limit." /></strong><p><LocaleText hinglish="Profile limit बढ़ाने के लिए support से contact करें। Existing profiles और learning history available रहेंगी।" english="Contact support to increase your profile limit. Existing profiles and learning history will remain available." /></p></div>
          : <div className="parent-child-form">
            <label><LocaleText hinglish="बच्चे का नाम" english="Child’s name" /><input value={draft.displayName} onChange={e=>setDraft({...draft,displayName:e.target.value})} placeholder={tx('बच्चे का नाम डालें','Enter child’s name')} autoComplete="off"/></label>
            <label><LocaleText hinglish="Class" english="Class" /><select value={draft.classCode} onChange={e=>setDraft({...draft,classCode:e.target.value})}><option value="6"><LocaleText hinglish="Class 6" english="Class 6" /></option><option value="7"><LocaleText hinglish="Class 7" english="Class 7" /></option><option value="8"><LocaleText hinglish="Class 8" english="Class 8" /></option></select></label>
            <label><LocaleText hinglish="Student username" english="Student username" /><input value={draft.username} onChange={e=>setDraft({...draft,username:e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g,'').slice(0,32)})} placeholder={tx('जैसे aarav07','e.g. aarav07')} autoComplete="off"/></label>
            <label><LocaleText hinglish="Student password" english="Student password" /><input type="password" value={draft.password} onChange={e=>setDraft({...draft,password:e.target.value})} placeholder={tx('कम से कम 8 characters','At least 8 characters')} autoComplete="new-password"/></label>
            <fieldset className="parent-track-fieldset"><legend><LocaleText hinglish="Subjects चुनें" english="Choose subjects" /></legend>{trackOptions.map(option=><label key={option.code}><input type="checkbox" checked={draft.trackCodes.includes(option.code)} onChange={e=>setDraft({...draft,trackCodes:e.target.checked?[...draft.trackCodes,option.code]:draft.trackCodes.filter(code=>code!==option.code)})}/><span>{locale==='english'?(option.code==='maths'?'Mathematics':'Science'):option.label}</span></label>)}</fieldset>
            <label className="consent-row"><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/><span><LocaleText hinglish="मैं बच्चे का authorised guardian हूँ और profile बनाने व learning records रखने की permission देता/देती हूँ।" english="I am the child’s authorised guardian and consent to creating this profile and maintaining learning records." /></span></label>
            <button className="button button-dark" disabled={busy||draft.displayName.trim().length<2||draft.username.trim().length<3||draft.password.length<8||draft.trackCodes.length===0||!consent} onClick={createChild}>{busy?<LocaleText hinglish="Profile बन रहा है…" english="Creating profile…" />:<LocaleText hinglish="Student profile बनाएँ →" english="Create student profile →" />}</button>
          </div>}
      </section>
    </section>
  </main>;
}
