'use client';

import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function ProgressPage(){
  const router=useRouter(); const [data,setData]=useState<any>(null); const [error,setError]=useState('');
  useEffect(()=>{(async()=>{const r=await fetch('/api/v1/students/me');if(r.status===401||r.status===403){router.replace('/login');return}const b=await r.json();if(!r.ok){setError(b.message||'Unable to load progress');return}setData(b)})()},[router]);
  if(error)return <main className="app-shell"><section className="progress-page"><div className="auth-card"><h1>Progress load nahi ho paayi</h1><p>{error}</p></div></section></main>;
  if(!data)return <main className="app-shell"><section className="progress-page"><div className="eyebrow">Progress load ho rahi hai…</div></section></main>;
  const stats=data.lessonStats,q=data.questionStats;
  return <main className="app-shell"><header className="app-header"><QuantaEdgeBrand variant="compact" /><Link href="/student" className="back">← आज</Link></header>
    <section className="progress-page"><div><span className="eyebrow">Aapki progress</span><h1>Jo seekha, woh clearly dikh raha hai.</h1><p>Har score aur completion aapke actual learning records se aata hai.</p></div>
      <div className="progress-grid"><article><span>Lessons</span><strong>{stats.complete_lessons}/{stats.total_lessons}</strong><small>complete</small></article><article><span>Practice</span><strong>{q.accuracy_percent}%</strong><small>{q.attempts} attempts · {q.graded_attempts} graded</small></article><article><span>Curriculum</span><strong>{stats.completion_percent}%</strong><small>published lessons complete</small></article></div>
      <div className="mastery-card"><div><span>Curriculum coverage</span><strong>Class {data.class_code}</strong></div>{data.curriculum.map((item:any)=><div className="mastery-row" key={item.subject_code}><span>{item.subject_name}</span><i><b style={{width:`${item.lessons?Math.round(item.complete*100/item.lessons):0}%`}}/></i><strong>{item.lessons?Math.round(item.complete*100/item.lessons):0}%</strong></div>)}</div>
      <div className="mastery-card"><div><span>Mastery</span><strong>{data.mastery.average_mastery}% average</strong></div><div className="topic-line"><b>Master ho chuke concepts</b><span className="good">{data.mastery.mastered}</span></div><div className="topic-line"><b>Jin concepts mein help chahiye</b><span>{data.mastery.needs_support}</span></div></div>      <div className="mastery-card"><div><span>Concept mastery</span><strong>Weakest concepts first</strong></div>{data.masteryDetails?.length ? data.masteryDetails.map((m:any)=><div className="topic-line" key={m.concept_id}><div><b>{m.concept_title}</b><small>{m.subject_name} · {m.chapter_name}</small></div><span className={m.mastery_percent>=80?'good':''}>{m.mastery_percent}% · {m.attempts} attempts</span></div>) : <div className="topic-line"><span>Abhi itna learning data nahi hai.</span></div>}</div>
      <Link href="/student/learn" className="button button-dark">Recommended lesson open karein →</Link>
    </section></main>;
}
