'use client';

import { LocaleText } from '../components/LanguageProvider';

import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import ParentAccountMenu from '../components/ParentAccountMenu';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type ChildRow={id:number;display_name:string;class_code?:string;board?:string};
type ParentReport=Record<string,any>;
type ParentProfile=Record<string,any>;

async function readApi(response:Response):Promise<any>{
  const raw=await response.text();
  if(!raw.trim())return {};
  try{return JSON.parse(raw);}
  catch{
    throw new Error(`The learning API returned an unreadable response (HTTP ${response.status}). Check that the API and web containers are running.`);
  }
}

export default function ParentPage(){
  const router=useRouter();
  const [children,setChildren]=useState<ChildRow[]>([]);
  const [selected,setSelected]=useState<ParentReport|null>(null);
  const [parentProfile,setParentProfile]=useState<ParentProfile|null>(null);
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);
  const [switchingChild,setSwitchingChild]=useState(false);

  async function load(id?:number){
    const switching=id!==undefined;
    if(switching)setSwitchingChild(true);else setLoading(true);
    setError('');
    try{
      const sessionResponse=await fetch('/api/v1/auth/me',{cache:'no-store'});
      if(!sessionResponse.ok){router.replace('/login');return;}
      const session=await readApi(sessionResponse);
      if(session.role!=='PARENT'&&session.role!=='ADMIN'){router.replace('/login');return;}
      const cr=await fetch('/api/v1/guardians/children',{cache:'no-store'});
      if(cr.status===401||cr.status===403){router.replace('/login');return;}
      const cb=await readApi(cr);
      if(!cr.ok)throw new Error(cb.message||`Unable to load child profiles (HTTP ${cr.status}).`);
      if(!Array.isArray(cb))throw new Error('The API returned an invalid child-profile list.');
      setChildren(cb);
      const profileResponse=await fetch('/api/v1/guardians/profile',{cache:'no-store'});
      const profileBody=await readApi(profileResponse);
      if(!profileResponse.ok)throw new Error(profileBody.message||'Unable to load parent account.');
      setParentProfile(profileBody);
      const child=cb.find((x:ChildRow)=>x.id===id)||cb[0];
      if(!child){setSelected(null);return;}

      const rr=await fetch(`/api/v1/guardians/children/${child.id}/report`,{cache:'no-store'});
      const rb=await readApi(rr);
      if(!rr.ok)throw new Error(rb.message||`Unable to load the learning report (HTTP ${rr.status}).`);
      if(!rb||typeof rb!=='object')throw new Error('The API returned an invalid learning report.');
      setSelected(rb);
    }catch(e:any){
      setSelected(null);
      setError(e?.message||'The parent report could not be loaded. Check the local services and retry.');
    }finally{
      if(switching)setSwitchingChild(false);else setLoading(false);
    }
  }

  useEffect(()=>{void load();},[]);

  if(loading)return <main className="parent-app"><header className="parent-header"><QuantaEdgeBrand variant="compact" /><span><LocaleText hinglish="Parent view" english="Parent dashboard" /></span><ParentAccountMenu displayName={parentProfile?.display_name || 'Parent account'} mobile={parentProfile?.mobile_e164 || ''} profileImageUrl={parentProfile?.profile_image_url} /></header><section className="parent-dashboard"><span className="eyebrow"><LocaleText hinglish="PARENT REPORT" english="PARENT REPORT" /></span><h1><LocaleText hinglish="आपकी report load हो रही है…" english="Loading your report…" /></h1><p><LocaleText hinglish="बच्चे के latest learning records जोड़े जा रहे हैं।" english="Connecting your child's latest learning records." /></p></section></main>;

  if(error)return <main className="parent-app"><header className="parent-header"><QuantaEdgeBrand variant="compact" /><ParentAccountMenu displayName={parentProfile?.display_name || 'Parent account'} mobile={parentProfile?.mobile_e164 || ''} profileImageUrl={parentProfile?.profile_image_url} /></header><section className="parent-dashboard"><span className="eyebrow"><LocaleText hinglish="PARENT REPORT" english="PARENT REPORT" /></span><h1><LocaleText hinglish="Report load नहीं हो पाई" english="Unable to load the report" /></h1><p>{error}</p><div className="parent-recovery-actions"><button type="button" className="button button-dark" onClick={()=>void load()}><LocaleText hinglish="दोबारा try करें →" english="Try again →" /></button><Link href="/parent/children" className="button button-light"><LocaleText hinglish="बच्चों के Profiles manage करें" english="Manage children’s profiles" /></Link><Link href="/login" className="text-link"><LocaleText hinglish="दोबारा sign in करें" english="Sign in again" /></Link></div></section></main>;

  if(!children.length)return <main className="parent-app"><header className="parent-header"><QuantaEdgeBrand variant="compact" /><ParentAccountMenu displayName={parentProfile?.display_name || 'Parent account'} mobile={parentProfile?.mobile_e164 || ''} profileImageUrl={parentProfile?.profile_image_url} /></header><section className="parent-dashboard"><span className="eyebrow"><LocaleText hinglish="FAMILY LEARNING" english="FAMILY LEARNING" /></span><h1><LocaleText hinglish="अभी कोई active child profile नहीं है" english="No active child profile yet" /></h1><p><LocaleText hinglish="आप नया student profile बना सकते हैं और हर बच्चे के लिए अलग subjects चुन सकते हैं।" english="You can create a student profile and choose subjects for each child." /></p><Link href="/parent/children" className="button button-dark">Bachchon ke profiles manage karein →</Link></section></main>;

  if(!selected)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><LocaleText hinglish="PARENT REPORT" english="PARENT REPORT" /></span><h1><LocaleText hinglish="कोई report select नहीं है" english="No report selected" /></h1><button className="button button-dark" onClick={()=>void load()}><LocaleText hinglish="Report दोबारा load करें →" english="Reload report →" /></button></section></main>;

  const stats=selected.lessonStats||{};
  const q=selected.questionStats||{};
  const mastery=selected.mastery||{};
  const sessionStats=selected.sessionStats||{};
  const curriculum=Array.isArray(selected.curriculum)?selected.curriculum:[];
  const masteryDetails=Array.isArray(selected.masteryDetails)?selected.masteryDetails:[];
  const recentAttempts=Array.isArray(selected.recentAttempts)?selected.recentAttempts:[];

  return <main className="parent-app">
    <header className="parent-header">
      <QuantaEdgeBrand variant="compact" />
      <span>Parent view · {selected.display_name||'Student'}</span>
      <ParentAccountMenu displayName={parentProfile?.display_name || 'Parent account'} mobile={parentProfile?.mobile_e164 || ''} profileImageUrl={parentProfile?.profile_image_url} />
    </header>
    <section className="parent-dashboard">
      <div><span className="eyebrow"><LocaleText hinglish="Learning summary" english="Learning summary" /></span><h1>{selected.display_name||'Student'} <LocaleText hinglish="ने क्या सीखा?" english="— learning summary" /></h1><p><LocaleText hinglish="यह report आपके linked child के actual learning records से बनती है।" english="This report is based on your linked child’s actual learning records." /></p><Link href="/parent/children" className="text-link"><LocaleText hinglish="बच्चों के Profiles manage करें और login details देखें →" english="Manage children’s profiles and login details →" /></Link></div>
      {children.length>1&&<section className="parent-report-switcher" aria-label="Choose a child’s learning report">
        <div className="parent-report-switcher__intro"><span className="eyebrow"><LocaleText hinglish="FAMILY ACCOUNT" english="FAMILY ACCOUNT" /></span><h2><LocaleText hinglish="हर बच्चे की अपनी progress" english="Each child has their own progress" /></h2><p><LocaleText hinglish="नीचे किसी बच्चे को select करके उसके lessons, practice accuracy, study time और concept mastery देखें। हर बच्चे के learning records अलग रहते हैं।" english="Select a child below to view their lessons, practice accuracy, study time, and concept mastery. Each child’s learning records stay separate." /></p></div>
        <div className="parent-report-switcher__profiles">{children.map(c=><button key={c.id} type="button" className={Number(selected.id)===c.id?'parent-report-switcher__profile is-current':'parent-report-switcher__profile'} aria-pressed={Number(selected.id)===c.id} disabled={switchingChild} onClick={()=>void load(c.id)}>
          <span className="parent-report-switcher__avatar">{(c.display_name||'S').trim().slice(0,1)||'S'}</span>
          <span className="parent-report-switcher__copy"><strong>{c.display_name}</strong><small><LocaleText hinglish="Class" english="Class" /> {c.class_code||'—'}{c.board?' · '+c.board:''}</small><small>{Number(selected.id)===c.id?<LocaleText hinglish="यह report अभी खुली है" english="Currently viewing this report" />:<LocaleText hinglish="इस बच्चे की report देखें" english="View this child’s report" />}</small></span>
          <span className="parent-report-switcher__arrow">{Number(selected.id)===c.id?'✓':'→'}</span>
        </button>)}</div>
        {switchingChild&&<p className="parent-report-switcher__status" role="status"><LocaleText hinglish="Selected child की report load हो रही है…" english="Loading the selected child’s report…" /></p>}
      </section>}
      <div className="parent-stats">
        <article><span><LocaleText hinglish="पूरे हुए lessons" english="Lessons completed" /></span><strong>{stats.completed_lessons??0}</strong><small>of {stats.total_lessons??0} published lessons</small></article>
        <article><span><LocaleText hinglish="Accuracy" english="Accuracy" /></span><strong>{q.accuracy_percent??0}%</strong><small>{q.attempts??0} <LocaleText hinglish="answered questions" english="answered questions" /></small></article>
        <article><span><LocaleText hinglish="Curriculum" english="Curriculum" /></span><strong>{stats.completion_percent??0}%</strong><small><LocaleText hinglish="पूरा" english="completion" /></small></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong><LocaleText hinglish="पूरा किया गया curriculum" english="Curriculum covered" /></strong><span>✓</span></div>{curriculum.length?curriculum.map((item:any)=><div className="topic-line" key={item.subject_code}><b>{item.subject_name||item.subject_code}</b><span className="good">{item.completed??0}/{item.lessons??0} <LocaleText hinglish="lessons" english="lessons" /></span></div>):<p><LocaleText hinglish="अभी किसी subscribed subject की summary available नहीं है।" english="There is no summary available for subscribed subjects yet." /></p>}</article>
        <article className="report-panel"><div className="panel-title"><strong><LocaleText hinglish="Learning signals" english="Learning signals" /></strong><span>→</span></div><div className="topic-line"><b><LocaleText hinglish="Average mastery" english="Average mastery" /></b><span className="good">{mastery.average_mastery??0}%</span></div><div className="topic-line"><b><LocaleText hinglish="Mastered concepts" english="Mastered concepts" /></b><span className="good">{mastery.mastered??0}</span></div><div className="topic-line"><b><LocaleText hinglish="जिन्हें मदद चाहिए" english="Needs support" /></b><span>{mastery.needs_support??0}</span></div><div className="topic-line"><b><LocaleText hinglish="Learning time · 30d" english="Learning time · 30d" /></b><span>{sessionStats.minutes_30d??0} min</span></div></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong><LocaleText hinglish="जिन concepts में मदद चाहिए" english="Concepts needing support" /></strong><span>↗</span></div>{masteryDetails.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={m.concept_title||i}><div><b>{m.concept_title||'Concept'}</b><small>{m.subject_name} · {m.chapter_name}</small></div><span>{m.mastery_percent??0}%</span></div>)}</article>
        <article className="report-panel"><div className="panel-title"><strong><LocaleText hinglish="हाल की Practice" english="Recent practice" /></strong><span>✓</span></div>{recentAttempts.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={i}><div><b>{m.concept_title||m.chapter_name||'Practice'}</b><small>{m.question_type||'Question'}</small></div><span className={m.correct===true?'good':''}>{m.correct===true?<LocaleText hinglish="सही" english="Correct" />:m.correct===false?<LocaleText hinglish="गलत" english="Incorrect" />:<LocaleText hinglish="Review" english="Review" />}</span></div>)}</article>
      </div>
      <p className="parent-readonly-note"><LocaleText hinglish="Parent view सिर्फ देखने के लिए है। Lessons, practice और assessments तभी available होंगे जब बच्चा अपने parent द्वारा बनाए गए login details से sign in करेगा।" english="Parent view is read-only. Lessons, practice, and assessments are available only after the child signs in with their own parent-created credentials." /></p>
    </section>
  </main>;
}
