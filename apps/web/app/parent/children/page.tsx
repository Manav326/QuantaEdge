'use client';

import { UiText } from '../../i18n/useUiTranslation';
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
type ChildProfileDraft = {displayName:string;classCode:string;language:string};
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
  const [childPrograms,setChildPrograms]=useState<Record<number,any[]>>({});
  const [busyCourse,setBusyCourse]=useState<string>('');
  const [profileDrafts,setProfileDrafts]=useState<Record<number,ChildProfileDraft>>({});
  const [credentialDrafts,setCredentialDrafts]=useState<Record<number,CredentialDraft>>({});
  const [archivedChildren,setArchivedChildren]=useState<Child[]>([]);
  const [creationRequestId,setCreationRequestId]=useState('');
  const [consent,setConsent]=useState(false);
  const [busy,setBusy]=useState(false);
  const [busyTrack,setBusyTrack]=useState<number|null>(null);
  const [busyProfile,setBusyProfile]=useState<number|null>(null);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  async function load(){
    setError('');
    const meResponse=await fetch('/api/v1/auth/me');
    if(!meResponse.ok){router.replace('/login');return;}
    let me=await readJson(meResponse);
    if(me.role!=='PARENT'&&me.role!=='ADMIN'){router.replace('/login');return;}
    const [accountResponse,childrenResponse,archivedResponse]=await Promise.all([
      fetch('/api/v1/guardians/account'),fetch('/api/v1/guardians/children'),
      fetch('/api/v1/guardians/children/archived')
    ]);
    const accountBody=await readJson(accountResponse);
    const childrenBody=await readJson(childrenResponse);
    const archivedBody=await readJson(archivedResponse);
    if(!accountResponse.ok)throw new Error(accountBody.message||'Unable to load parent account.');
    if(!childrenResponse.ok)throw new Error(childrenBody.message||'Unable to load child profiles.');
    if(!archivedResponse.ok)throw new Error(archivedBody.message||'Unable to load archived profiles.');
    setAccount(accountBody);
    setChildren(childrenBody);
    setArchivedChildren(archivedBody);
    const programLists=await Promise.all(childrenBody.map(async(child:Child)=>{
      try{
        const response=await fetch('/api/v1/guardians/children/'+child.id+'/programs',{credentials:'same-origin',cache:'no-store'});
        const body=await readJson(response);
        return [child.id,response.ok&&Array.isArray(body)?body:[]] as const;
      }catch{return [child.id,[]] as const;}
    }));
    setChildPrograms(Object.fromEntries(programLists));
    setProfileDrafts(Object.fromEntries(childrenBody.map((child:Child)=>[
      child.id,{displayName:child.display_name,classCode:String(child.class_code),language:child.language||'hi'}
    ])));
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
      const requestId=creationRequestId||crypto.randomUUID();
      if(!creationRequestId)setCreationRequestId(requestId);
      const response=await fetch('/api/v1/guardians/children',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({...draft,consentAccepted:consent,requestId})
      });
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to create the student profile.');
      setDraft(emptyDraft);setConsent(false);setCreationRequestId('');
      const successMessage=tx('Student profile बन गया है और login details save हो गई हैं। अब बच्चा आपके बनाए username और password से sign in कर सकता है।','Student profile and login details are saved. Your child can now sign in with the username and password you created.');
      setNotice(successMessage);
      try{
        await load();
        setNotice(successMessage);
      }catch{
        setError(tx('Profile बन गया है, लेकिन list refresh नहीं हो पाई। दोबारा profile न बनाएँ; page reload करके जाँचें।','The profile was created successfully, but the list could not refresh. Do not create it again; reload the page to verify.'));
      }
    }catch(e:any){setError(e.message||'Unable to create student profile.');}
    finally{setBusy(false);}
  }

  function updateChildDraft(child:Child,patch:Partial<ChildProfileDraft>){
    setProfileDrafts(current=>{
      const existing=current[child.id]||{displayName:child.display_name,classCode:String(child.class_code),language:child.language||'hi'};
      return {...current,[child.id]:{...existing,...patch}};
    });
  }

  async function saveChildProfile(child:Child){
    setError('');setNotice('');setBusyProfile(child.id);
    try{
      const profile=profileDrafts[child.id]||{displayName:child.display_name,classCode:String(child.class_code),language:child.language||'hi'};
      const response=await fetch('/api/v1/guardians/children/'+child.id+'/profile',{
        method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(profile)
      });
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to update the child profile.');
      await load();
      setNotice(tx('Student profile की details save हो गई हैं; पुरानी learning history सुरक्षित है.','Student profile details have been saved; previous learning history is preserved.'));
    }catch(e:any){setError(e.message||'Unable to update the child profile.');}
    finally{setBusyProfile(null);}
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

  async function assignCourse(child:Child,course:any){
    const key=child.id+':'+course.id;
    setError('');setNotice('');setBusyCourse(key);
    try{
      const response=await fetch('/api/v1/guardians/children/'+child.id+'/programs/'+course.id+'/enroll',{
        method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:'{}'
      });
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to assign this course.');
      await load();
      setNotice(tx(course.display_name+' बच्चे के learning track में जोड़ दिया गया है.',course.display_name+' has been added to the child’s learning tracks.'));
    }catch(e:any){setError(e.message||'Unable to assign this course.');}
    finally{setBusyCourse('');}
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

  async function restoreChild(child:Child){
    if(!window.confirm(tx(
      'मैं authorised guardian हूँ और इस profile को restore करने व current guardian consent देने की पुष्टि करता/करती हूँ।',
      'I confirm that I am an authorised guardian and consent to restoring this profile.'
    )))return;
    setError('');setNotice('');
    try{
      const response=await fetch('/api/v1/guardians/children/'+child.id+'/restore',{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({consentAccepted:true})
      });
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.message||'Unable to restore the profile.');
      await load();
      setNotice(tx(
        child.display_name+' का profile restore हो गया है। Security के लिए student login credentials फिर से set करें।',
        child.display_name+'’s profile has been restored. Set the student login credentials again for security.'
      ));
    }catch(e:any){setError(e.message||'Unable to restore profile.');}
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
    <section className="parent-dashboard parent-manage">
      <div className="parent-manage-hero">
        <div><span className="eyebrow"><UiText message="legacy.web.family.learning.9f99fe9d" /></span><h1><UiText message="legacy.web.children.s.profiles.110b8bde" /></h1>
          <p><UiText message="legacy.web.create.profiles.up.to.your.account.lim.b2d42aee" /></p></div>
        <div className="parent-capacity"><span><UiText message="legacy.web.active.profiles.c6b083a5" /></span><strong>{account?account.activeChildren:'—'} <small>/ {account?account.maxChildren:'—'}</small></strong><span>{account?.remainingSlots??'—'} <UiText message="legacy.web.profile.slots.remaining.8928cc72" /></span></div>
      </div>

      {error&&<div className="feedback" role="alert">{error}</div>}
      {notice&&<div className="parent-notice" role="status">{notice}</div>}

      <section className="parent-manage-panel">
        <div className="parent-manage-panel-head"><div><span className="eyebrow"><UiText message="legacy.web.student.profiles.bfb0042f" /></span><h2><UiText message="legacy.web.your.children.492f3c5d" /></h2></div><span className="count">{children.length} <UiText message="legacy.web.active.2b76438d" /></span></div>
        {!children.length?<div className="parent-empty"><strong><UiText message="legacy.web.no.active.child.profile.yet.e3baf80e" /></strong><p><UiText message="legacy.web.create.the.first.profile.below.and.cho.77d6bd19" /></p></div>:<div className="parent-child-list">
          {children.map(child=><article className="parent-child-card" key={child.id}>
            <div className="parent-child-overview">
              <div className="parent-child-avatar">{child.display_name.trim().slice(0,1)||'S'}</div>
              <div className="parent-child-name"><h3>{child.display_name}</h3><p>Class {child.class_code} · {child.board} · {tx('पढ़ाई की भाषा: '+((child.language||'hi')==='en'?'English':'हिन्दी'),'Learning content: '+((child.language||'hi')==='en'?'English':'Hindi'))}</p>
                <div className="track-pill-row">{(trackDrafts[child.id]||[]).map(code=><span className="track-pill" key={code}>{code==='maths'?'गणित':'विज्ञान'}</span>)}</div>
              </div>
              <button type="button" className="parent-archive-button" onClick={()=>archiveChild(child)}><UiText message="legacy.web.archive.profile.4dc003f9" /></button>
            </div>
            <div className="parent-child-credentials parent-profile-editor">
              <div className="parent-credentials-heading"><strong><UiText message="legacy.web.profile.details.614c25cd" /></strong><small><UiText message="legacy.web.update.the.name.and.learning.language..dc96f21b" /></small></div>
              <div className="parent-child-form">
                <label><UiText message="legacy.web.child.s.name.8e59b3df" /><input value={profileDrafts[child.id]?.displayName??child.display_name} maxLength={120} onChange={e=>updateChildDraft(child,{displayName:e.target.value})} /></label>
                <label><UiText message="legacy.web.class.9c9232e5" /><select value={profileDrafts[child.id]?.classCode??String(child.class_code)} onChange={e=>updateChildDraft(child,{classCode:e.target.value})}>
                  <option value={String(child.class_code)}>Class {child.class_code}</option>
                  {child.class_code==='6'&&<option value="7">Promote to Class 7</option>}
                  {child.class_code==='7'&&<option value="8">Promote to Class 8</option>}
                </select></label>
                <label><UiText message="legacy.web.learning.language.8be9be9d" /><select value={profileDrafts[child.id]?.language??child.language??'hi'} onChange={e=>updateChildDraft(child,{language:e.target.value})}>
                  <option value="hi">हिन्दी (Hindi)</option><option value="en">English</option>
                </select></label>
              </div>
              <button type="button" className="button button-light button-small" disabled={busyProfile===child.id||(profileDrafts[child.id]?.displayName??child.display_name).trim().length<2}
                onClick={()=>void saveChildProfile(child)}>{busyProfile===child.id?<UiText message="legacy.web.saving.ec034ad4" />:<UiText message="legacy.web.save.profile.5dbd6753" />}</button>
            </div>
            <div className="parent-child-credentials">
              <div className="parent-credentials-heading"><strong><UiText message="legacy.web.student.login.details.7429647d" /></strong><small><UiText message="legacy.web.step.1.set.a.username.and.password.her.4d30cfe2" /></small></div>
              <div className="parent-credentials-status" role="status">
                <strong className={child.login_username?'is-ready':'is-pending'}>{child.login_username?<UiText message="legacy.web.student.login.is.ready.68c4022e" />:<UiText message="legacy.web.student.login.is.not.set.yet.8669d2ea" />}</strong>
                <p>{child.login_username?<UiText message="legacy.web.this.child.can.now.sign.in.308512b7" />:<UiText message="legacy.web.your.child.cannot.sign.in.yet.create.a.aff32fb8" />}</p>
              </div>
              {child.login_username&&<p className="parent-credentials-current"><UiText message="legacy.web.current.username.440d701d" /> <strong>{child.login_username}</strong></p>}
              <div className="parent-child-form parent-credentials-form">
                <label><UiText message="legacy.web.student.username.2552c8f7" /><input value={credentialDrafts[child.id]?.username||''} onChange={e=>setCredentialDrafts(current=>({...current,[child.id]:{username:e.target.value,password:current[child.id]?.password||''}}))} placeholder={tx('जैसे aarav07','e.g. aarav07')} autoComplete="off" /></label>
                <label><UiText message="legacy.web.set.or.reset.password.d599fa87" /><input type="password" value={credentialDrafts[child.id]?.password||''} onChange={e=>setCredentialDrafts(current=>({...current,[child.id]:{username:current[child.id]?.username||'',password:e.target.value}}))} placeholder={tx('कम से कम 8 characters','At least 8 characters')} autoComplete="new-password" /></label>
              </div>
              <button type="button" className="button button-light button-small" disabled={(credentialDrafts[child.id]?.username||'').trim().length<3||(credentialDrafts[child.id]?.password||'').length<8} onClick={()=>void saveCredentials(child)}><UiText message="legacy.web.save.login.details.bcf9bcd7" /></button>
            </div>
            <div className="parent-track-editor">
              <div><strong><UiText message="legacy.web.learning.access.dc503c1d" /></strong><small><UiText message="legacy.web.only.selected.subjects.appear.in.the.s.993efbad" /></small></div>
              <div className="parent-track-options">{trackOptions.map(option=><label key={option.code}>
                <input type="checkbox" checked={(trackDrafts[child.id]||[]).includes(option.code)}
                  onChange={e=>toggleTrack(child.id,option.code,e.target.checked)}/>
                <span>{locale==='english'?(option.code==='maths'?'Mathematics':'Science'):(option.label)}</span>
              </label>)}</div>
              <button className="button button-dark button-small" disabled={busyTrack===child.id||(trackDrafts[child.id]||[]).length===0}
                onClick={()=>saveTracks(child)}>{busyTrack===child.id?<UiText message="legacy.web.saving.ec034ad4" />:<UiText message="legacy.web.save.subjects.123d5ff5" />}</button>
            </div>
            <div className="parent-track-editor qe-child-course-editor">
              <div><strong><UiText message="legacy.web.independent.courses.13023fbd" /></strong><small><UiText message="legacy.web.assign.a.course.to.this.child.s.accoun.b2c771e2" /></small></div>
              {(childPrograms[child.id]||[]).length===0
                ? <p className="eyebrow"><UiText message="legacy.web.no.independent.courses.are.available.y.7da2a26b" /></p>
                : <div className="parent-course-options">{(childPrograms[child.id]||[]).map((course:any)=>(
                  <div className="parent-course-option" key={course.id}>
                    <div><strong>{course.display_name}</strong><small>{course.public_id||course.code} · {course.curriculum_version}</small>
                      <small>{(course.subjects||[]).map((subject:any)=>subject.subject_name+' ('+Number(subject.lessons||0)+' lessons)').join(' · ')}</small></div>
                    {course.is_enrolled||course.enrolled
                      ? <span className="track-pill"><UiText message="legacy.web.assigned.f5b576f5" /></span>
                      : <button type="button" className="button button-light button-small" disabled={busyCourse!==''} onClick={()=>void assignCourse(child,course)}>{busyCourse===child.id+':'+course.id?<UiText message="legacy.web.assigning.f0834a8f" />:<UiText message="legacy.web.assign.course.c9842149" />}</button>}
                  </div>
                ))}</div>}
            </div>
          </article>)}
        </div>}
      </section>

      {archivedChildren.length>0&&<section className="parent-manage-panel">
        <div className="parent-manage-panel-head"><div><span className="eyebrow"><UiText message="legacy.web.archived.profiles.2a69713d" /></span><h2><UiText message="legacy.web.archived.children.10cc65d1" /></h2><p><UiText message="legacy.web.learning.history.is.retained.restoring.3c3f6204" /></p></div></div>
        <div className="parent-child-list">{archivedChildren.map(child=><article className="parent-child-card" key={'archived-'+child.id}>
          <div className="parent-child-overview"><div className="parent-child-avatar">{child.display_name.trim().slice(0,1)||'S'}</div><div className="parent-child-name"><h3>{child.display_name}</h3><p>Class {child.class_code} · {child.board}</p></div>
            <button type="button" className="button button-light button-small" onClick={()=>void restoreChild(child)}><UiText message="legacy.web.restore.profile.e209e8ad" /></button></div>
        </article>)}</div>
      </section>}

      <section className="parent-manage-panel">
        <div className="parent-manage-panel-head"><div><span className="eyebrow"><UiText message="legacy.web.add.a.child.3e7ac755" /></span><h2><UiText message="legacy.web.new.student.profile.c8b13062" /></h2><p><UiText message="legacy.web.create.your.child.s.profile.and.login..b6c4b0d7" /></p></div></div>
        {(account?.remainingSlots??0)<=0
          ? <div className="parent-empty"><strong><UiText message="legacy.web.you.have.reached.your.account.s.profil.819488f8" /></strong><p><UiText message="legacy.web.contact.support.to.increase.your.profi.3aa0e91d" /></p></div>
          : <div className="parent-child-form">
            <label><UiText message="legacy.web.child.s.name.8e59b3df" /><input value={draft.displayName} onChange={e=>setDraft({...draft,displayName:e.target.value})} placeholder={tx('बच्चे का नाम डालें','Enter child’s name')} autoComplete="off"/></label>
            <label><UiText message="legacy.web.class.9c9232e5" /><select value={draft.classCode} onChange={e=>setDraft({...draft,classCode:e.target.value})}><option value="6"><UiText message="legacy.web.class.6.c917d5d1" /></option><option value="7"><UiText message="legacy.web.class.7.0a63ed61" /></option><option value="8"><UiText message="legacy.web.class.8.ccf4ad25" /></option></select></label>
            <label><UiText message="legacy.web.learning.language.8be9be9d" /><select value={draft.language} onChange={e=>setDraft({...draft,language:e.target.value})}><option value="hi">हिन्दी (Hindi)</option><option value="en">English</option></select></label>
            <label><UiText message="legacy.web.student.username.2552c8f7" /><input value={draft.username} onChange={e=>setDraft({...draft,username:e.target.value.toLowerCase().replace(/[^a-z0-9._-]/g,'').slice(0,32)})} placeholder={tx('जैसे aarav07','e.g. aarav07')} autoComplete="off"/></label>
            <label><UiText message="legacy.web.student.password.9ba6b0cd" /><input type="password" value={draft.password} onChange={e=>setDraft({...draft,password:e.target.value})} placeholder={tx('कम से कम 8 characters','At least 8 characters')} autoComplete="new-password"/></label>
            <fieldset className="parent-track-fieldset"><legend><UiText message="legacy.web.choose.subjects.1c060640" /></legend>{trackOptions.map(option=><label key={option.code}><input type="checkbox" checked={draft.trackCodes.includes(option.code)} onChange={e=>setDraft({...draft,trackCodes:e.target.checked?[...draft.trackCodes,option.code]:draft.trackCodes.filter(code=>code!==option.code)})}/><span>{locale==='english'?(option.code==='maths'?'Mathematics':'Science'):option.label}</span></label>)}</fieldset>
            <label className="consent-row"><input type="checkbox" checked={consent} onChange={e=>setConsent(e.target.checked)}/><span><UiText message="legacy.web.i.am.the.child.s.authorised.guardian.a.45b1acdf" /></span></label>
            <button className="button button-dark" disabled={busy||draft.displayName.trim().length<2||draft.username.trim().length<3||draft.password.length<8||draft.trackCodes.length===0||!consent} onClick={createChild}>{busy?<UiText message="legacy.web.creating.profile.03f9567c" />:<UiText message="legacy.web.create.student.profile.b50fed07" />}</button>
          </div>}
      </section>
    </section>
  </main>;
}
