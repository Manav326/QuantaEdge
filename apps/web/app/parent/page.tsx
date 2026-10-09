'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type ChildRow={id:number;display_name:string};
type ParentReport=Record<string,any>;

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
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);

  async function logout(){
    await fetch('/api/v1/auth/logout',{method:'POST'});
    router.replace('/login');
  }

  async function load(id?:number){
    setLoading(true);
    setError('');
    try{
      let cr=await fetch('/api/v1/guardians/children',{cache:'no-store'});
      if(cr.status===401){router.replace('/login');return;}
      if(cr.status===403){
        const sr=await fetch('/api/v1/auth/switch-parent',{method:'POST'});
        if(!sr.ok){router.replace('/login');return;}
        cr=await fetch('/api/v1/guardians/children',{cache:'no-store'});
      }
      const cb=await readApi(cr);
      if(!cr.ok)throw new Error(cb.message||`Unable to load child profiles (HTTP ${cr.status}).`);
      if(!Array.isArray(cb))throw new Error('The API returned an invalid child-profile list.');
      setChildren(cb);
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
      setLoading(false);
    }
  }

  useEffect(()=>{void load();},[]);

  if(loading)return <main className="parent-app"><header className="parent-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><span>Parent view</span></header><section className="parent-dashboard"><span className="eyebrow">PARENT REPORT</span><h1>Loading your report…</h1><p>Connecting to your child’s current learning records.</p></section></main>;

  if(error)return <main className="parent-app"><header className="parent-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><Link href="/parent/children" className="text-link">Manage children</Link></header><section className="parent-dashboard"><span className="eyebrow">PARENT REPORT</span><h1>Your report couldn’t load</h1><p>{error}</p><div className="parent-recovery-actions"><button type="button" className="button button-dark" onClick={()=>void load()}>Retry report →</button><Link href="/parent/children" className="button button-light">Manage child profiles</Link><Link href="/login" className="text-link">Sign in again</Link></div></section></main>;

  if(!children.length)return <main className="parent-app"><header className="parent-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><button className="text-link" onClick={logout}>Logout</button></header><section className="parent-dashboard"><span className="eyebrow">FAMILY LEARNING</span><h1>अभी कोई active child profile नहीं है</h1><p>आप नया student profile बना सकते हैं और child के लिए अलग subject access चुन सकते हैं।</p><Link href="/parent/children" className="button button-dark">Manage child profiles →</Link></section></main>;

  if(!selected)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow">PARENT REPORT</span><h1>No report selected</h1><button className="button button-dark" onClick={()=>void load()}>Reload report →</button></section></main>;

  const stats=selected.lessonStats||{};
  const q=selected.questionStats||{};
  const mastery=selected.mastery||{};
  const sessionStats=selected.sessionStats||{};
  const curriculum=Array.isArray(selected.curriculum)?selected.curriculum:[];
  const masteryDetails=Array.isArray(selected.masteryDetails)?selected.masteryDetails:[];
  const recentAttempts=Array.isArray(selected.recentAttempts)?selected.recentAttempts:[];

  return <main className="parent-app">
    <header className="parent-header">
      <Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link>
      <span>Parent view · {selected.display_name||'Student'}</span>
      <div className="parent-header-actions"><Link href="/parent/children" className="text-link">Manage children</Link><button className="text-link" onClick={logout}>Logout</button></div>
    </header>
    <section className="parent-dashboard">
      <div><span className="eyebrow">Learning summary</span><h1>{selected.display_name||'Student'} ने क्या सीखा?</h1><p>यह report आपके linked child के वास्तविक learning records से बनती है।</p></div>
      {children.length>1&&<label>Child<select value={selected.id} onChange={e=>void load(Number(e.target.value))}>{children.map(c=><option key={c.id} value={c.id}>{c.display_name}</option>)}</select></label>}
      <div className="parent-stats">
        <article><span>Lessons complete</span><strong>{stats.completed_lessons??0}</strong><small>of {stats.total_lessons??0} published lessons</small></article>
        <article><span>Accuracy</span><strong>{q.accuracy_percent??0}%</strong><small>{q.attempts??0} answered questions</small></article>
        <article><span>Curriculum</span><strong>{stats.completion_percent??0}%</strong><small>completion</small></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong>Curriculum covered</strong><span>✓</span></div>{curriculum.length?curriculum.map((item:any)=><div className="topic-line" key={item.subject_code}><b>{item.subject_name||item.subject_code}</b><span className="good">{item.completed??0}/{item.lessons??0} lessons</span></div>):<p>No subscribed subject summary is available yet.</p>}</article>
        <article className="report-panel"><div className="panel-title"><strong>Learning signals</strong><span>→</span></div><div className="topic-line"><b>Average mastery</b><span className="good">{mastery.average_mastery??0}%</span></div><div className="topic-line"><b>Mastered concepts</b><span className="good">{mastery.mastered??0}</span></div><div className="topic-line"><b>Needs support</b><span>{mastery.needs_support??0}</span></div><div className="topic-line"><b>Learning time · 30d</b><span>{sessionStats.minutes_30d??0} min</span></div></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong>Concepts needing support</strong><span>↗</span></div>{masteryDetails.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={m.concept_title||i}><div><b>{m.concept_title||'Concept'}</b><small>{m.subject_name} · {m.chapter_name}</small></div><span>{m.mastery_percent??0}%</span></div>)}</article>
        <article className="report-panel"><div className="panel-title"><strong>Recent practice</strong><span>✓</span></div>{recentAttempts.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={i}><div><b>{m.concept_title||m.chapter_name||'Practice'}</b><small>{m.question_type||'Question'}</small></div><span className={m.correct===true?'good':''}>{m.correct===true?'सही':m.correct===false?'गलत':'Review'}</span></div>)}</article>
      </div>
      <Link href="/student" className="button button-dark">Student journey खोलें →</Link>
    </section>
  </main>;
}
