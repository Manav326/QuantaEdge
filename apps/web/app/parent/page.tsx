'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function ParentPage(){
  const router=useRouter(); const [children,setChildren]=useState<any[]>([]); const [selected,setSelected]=useState<any>(null); const [error,setError]=useState('');
  async function load(id?:number){const cr=await fetch('/api/v1/guardians/children');if(cr.status===401||cr.status===403){router.replace('/login');return}const cb=await cr.json();if(!cr.ok){setError(cb.message||'Unable to load children');return}setChildren(cb);const child=cb.find((x:any)=>x.id===id)||cb[0];if(child){const rr=await fetch('/api/v1/guardians/children/'+child.id+'/report');const rb=await rr.json();if(rr.ok)setSelected(rb)}}
  useEffect(()=>{load()},[]);
  if(error)return <main className="parent-app"><section className="parent-dashboard"><div className="auth-card"><h1>Parent report unavailable</h1><p>{error}</p></div></section></main>;
  if(!children.length)return <main className="parent-app"><section className="parent-dashboard"><div className="auth-card"><h1>अभी child profile नहीं है</h1><p>Login flow में student profile बनाकर फिर यहाँ आएँ।</p><Link href="/login" className="button button-dark">Student profile बनाएं →</Link></div></section></main>;
  if(!selected)return <main className="parent-app"><section className="parent-dashboard"><div className="eyebrow">Report लोड हो रही है…</div></section></main>;
  const stats=selected.lessonStats,q=selected.questionStats;
  return <main className="parent-app"><header className="parent-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><span>Parent view · {selected.display_name}</span></header>
    <section className="parent-dashboard"><div><span className="eyebrow">Learning summary</span><h1>{selected.display_name} ने क्या सीखा?</h1><p>यह report आपके linked child के वास्तविक learning records से बनती है।</p></div>
      {children.length>1&&<label>Child<select value={selected.id} onChange={e=>load(Number(e.target.value))}>{children.map(c=><option key={c.id} value={c.id}>{c.display_name}</option>)}</select></label>}
      <div className="parent-stats"><article><span>Lessons complete</span><strong>{stats.completed_lessons}</strong><small>of {stats.total_lessons} published lessons</small></article><article><span>Accuracy</span><strong>{q.accuracy_percent}%</strong><small>{q.attempts} answered questions</small></article><article><span>Curriculum</span><strong>{stats.completion_percent}%</strong><small>completion</small></article></div>
      <div className="parent-grid"><article className="report-panel"><div className="panel-title"><strong>Curriculum covered</strong><span>✓</span></div>{selected.curriculum.map((item:any)=><div className="topic-line" key={item.subject_code}><b>{item.subject_name}</b><span className="good">{item.completed}/{item.lessons} lessons</span></div>)}</article><article className="report-panel"><div className="panel-title"><strong>Learning signals</strong><span>→</span></div><div className="topic-line"><b>Average mastery</b><span className="good">{selected.mastery.average_mastery}%</span></div><div className="topic-line"><b>Mastered concepts</b><span className="good">{selected.mastery.mastered}</span></div><div className="topic-line"><b>Needs support</b><span>{selected.mastery.needs_support}</span></div></article></div>
      <Link href="/student" className="button button-dark">Student journey खोलें →</Link>
    </section></main>;
}
