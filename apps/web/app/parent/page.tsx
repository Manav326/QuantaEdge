'use client';

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

  if(loading)return <main className="parent-app"><header className="parent-header"><QuantaEdgeBrand variant="compact" /><span>Parent view</span><ParentAccountMenu displayName={parentProfile?.display_name || 'Parent account'} mobile={parentProfile?.mobile_e164 || ''} profileImageUrl={parentProfile?.profile_image_url} /></header><section className="parent-dashboard"><span className="eyebrow">PARENT REPORT</span><h1>Aapki report load ho rahi hai…</h1><p>Bachche ke latest learning records connect kiye ja rahe hain.</p></section></main>;

  if(error)return <main className="parent-app"><header className="parent-header"><QuantaEdgeBrand variant="compact" /><ParentAccountMenu displayName={parentProfile?.display_name || 'Parent account'} mobile={parentProfile?.mobile_e164 || ''} profileImageUrl={parentProfile?.profile_image_url} /></header><section className="parent-dashboard"><span className="eyebrow">PARENT REPORT</span><h1>Report load nahi ho paayi</h1><p>{error}</p><div className="parent-recovery-actions"><button type="button" className="button button-dark" onClick={()=>void load()}>Dobara try karein →</button><Link href="/parent/children" className="button button-light">Bachchon ke profiles manage karein</Link><Link href="/login" className="text-link">Dobara sign in karein</Link></div></section></main>;

  if(!children.length)return <main className="parent-app"><header className="parent-header"><QuantaEdgeBrand variant="compact" /><ParentAccountMenu displayName={parentProfile?.display_name || 'Parent account'} mobile={parentProfile?.mobile_e164 || ''} profileImageUrl={parentProfile?.profile_image_url} /></header><section className="parent-dashboard"><span className="eyebrow">FAMILY LEARNING</span><h1>Abhi koi active child profile nahi hai</h1><p>Aap naya student profile bana sakte hain aur har bachche ke liye alag subjects choose kar sakte hain.</p><Link href="/parent/children" className="button button-dark">Bachchon ke profiles manage karein →</Link></section></main>;

  if(!selected)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow">PARENT REPORT</span><h1>Koi report select nahi hai</h1><button className="button button-dark" onClick={()=>void load()}>Report dobara load karein →</button></section></main>;

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
      <div><span className="eyebrow">Learning summary</span><h1>{selected.display_name||'Student'} ne kya seekha?</h1><p>Yeh report aapke linked child ke actual learning records se banti hai.</p><Link href="/parent/children" className="text-link">Bachchon ke profiles manage karein and login details →</Link></div>
      {children.length>1&&<section className="parent-report-switcher" aria-label="Choose a child’s learning report">
        <div className="parent-report-switcher__intro"><span className="eyebrow">FAMILY ACCOUNT</span><h2>Har bachche ki apni progress</h2><p>Neeche kisi bachche ko select karke uske lessons, practice accuracy, study time aur concept mastery dekhein. Har bachche ke learning records alag rehte hain.</p></div>
        <div className="parent-report-switcher__profiles">{children.map(c=><button key={c.id} type="button" className={Number(selected.id)===c.id?'parent-report-switcher__profile is-current':'parent-report-switcher__profile'} aria-pressed={Number(selected.id)===c.id} disabled={switchingChild} onClick={()=>void load(c.id)}>
          <span className="parent-report-switcher__avatar">{(c.display_name||'S').trim().slice(0,1)||'S'}</span>
          <span className="parent-report-switcher__copy"><strong>{c.display_name}</strong><small>Class {c.class_code||'—'}{c.board?' · '+c.board:''}</small><small>{Number(selected.id)===c.id?'Currently viewing this report':'View this child’s report'}</small></span>
          <span className="parent-report-switcher__arrow">{Number(selected.id)===c.id?'✓':'→'}</span>
        </button>)}</div>
        {switchingChild&&<p className="parent-report-switcher__status" role="status">Loading the selected child’s report…</p>}
      </section>}
      <div className="parent-stats">
        <article><span>Lessons complete</span><strong>{stats.completed_lessons??0}</strong><small>of {stats.total_lessons??0} published lessons</small></article>
        <article><span>Accuracy</span><strong>{q.accuracy_percent??0}%</strong><small>{q.attempts??0} answered questions</small></article>
        <article><span>Curriculum</span><strong>{stats.completion_percent??0}%</strong><small>completion</small></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong>Cover kiya gaya curriculum</strong><span>✓</span></div>{curriculum.length?curriculum.map((item:any)=><div className="topic-line" key={item.subject_code}><b>{item.subject_name||item.subject_code}</b><span className="good">{item.completed??0}/{item.lessons??0} lessons</span></div>):<p>Abhi kisi subscribed subject ki summary available nahi hai.</p>}</article>
        <article className="report-panel"><div className="panel-title"><strong>Learning signals</strong><span>→</span></div><div className="topic-line"><b>Average mastery</b><span className="good">{mastery.average_mastery??0}%</span></div><div className="topic-line"><b>Mastered concepts</b><span className="good">{mastery.mastered??0}</span></div><div className="topic-line"><b>Needs support</b><span>{mastery.needs_support??0}</span></div><div className="topic-line"><b>Learning time · 30d</b><span>{sessionStats.minutes_30d??0} min</span></div></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong>Concepts needing support</strong><span>↗</span></div>{masteryDetails.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={m.concept_title||i}><div><b>{m.concept_title||'Concept'}</b><small>{m.subject_name} · {m.chapter_name}</small></div><span>{m.mastery_percent??0}%</span></div>)}</article>
        <article className="report-panel"><div className="panel-title"><strong>Recent practice</strong><span>✓</span></div>{recentAttempts.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={i}><div><b>{m.concept_title||m.chapter_name||'Practice'}</b><small>{m.question_type||'Question'}</small></div><span className={m.correct===true?'good':''}>{m.correct===true?'सही':m.correct===false?'गलत':'Review'}</span></div>)}</article>
      </div>
      <p className="parent-readonly-note">Parent view is read-only. Lessons, practice and assessments are available only after the child signs in with their own parent-created credentials.</p>
    </section>
  </main>;
}
