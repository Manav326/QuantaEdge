'use client';

import { LocaleText } from '../../components/LanguageProvider';

import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function ProgressPage(){
  const router=useRouter(); const [data,setData]=useState<any>(null); const [error,setError]=useState('');
  useEffect(()=>{(async()=>{const r=await fetch('/api/v1/students/me');if(r.status===401||r.status===403){router.replace('/login/student');return}const b=await r.json();if(!r.ok){setError(b.message||'Unable to load progress');return}setData(b)})()},[router]);
  if(error)return <main className="app-shell"><section className="progress-page"><div className="auth-card"><h1><LocaleText hinglish="Progress load नहीं हो पाई" english="Unable to load progress" /></h1><p>{error}</p></div></section></main>;
  if(!data)return <main className="app-shell"><section className="progress-page"><div className="eyebrow"><LocaleText hinglish="Progress load हो रही है…" english="Loading progress…" /></div></section></main>;
  const stats=data.lessonStats,q=data.questionStats;
  return <main className="app-shell"><header className="app-header"><QuantaEdgeBrand variant="compact" /><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link></header>
    <section className="progress-page"><div><span className="eyebrow"><LocaleText hinglish="आपकी progress" english="Your progress" /></span><h1><LocaleText hinglish="जो सीखा, वह साफ़ दिख रहा है।" english="See what you’ve learned at a glance." /></h1><p><LocaleText hinglish="हर score और completion आपके actual learning records से आता है।" english="Every score and completion figure comes from your actual learning records." /></p></div>
      <div className="progress-grid"><article><span><LocaleText hinglish="Lessons" english="Lessons" /></span><strong>{stats.complete_lessons}/{stats.total_lessons}</strong><small><LocaleText hinglish="पूरे" english="completed" /></small></article><article><span><LocaleText hinglish="Practice" english="Practice" /></span><strong>{q.accuracy_percent}%</strong><small>{q.attempts} attempts · {q.graded_attempts} graded</small></article><article><span><LocaleText hinglish="Curriculum" english="Curriculum" /></span><strong>{stats.completion_percent}%</strong><small><LocaleText hinglish="published lessons पूरे" english="published lessons completed" /></small></article></div>
      <div className="mastery-card"><div><span><LocaleText hinglish="Curriculum coverage" english="Curriculum coverage" /></span><strong>Class {data.class_code}</strong></div>{data.curriculum.map((item:any)=><div className="mastery-row" key={item.subject_code}><span>{item.subject_name}</span><i><b style={{width:`${item.lessons?Math.round(item.complete*100/item.lessons):0}%`}}/></i><strong>{item.lessons?Math.round(item.complete*100/item.lessons):0}%</strong></div>)}</div>
      <div className="mastery-card"><div><span><LocaleText hinglish="Mastery" english="Mastery" /></span><strong>{data.mastery.average_mastery}% average</strong></div><div className="topic-line"><b><LocaleText hinglish="Master हो चुके concepts" english="Mastered concepts" /></b><span className="good">{data.mastery.mastered}</span></div><div className="topic-line"><b><LocaleText hinglish="जिन concepts में मदद चाहिए" english="Concepts where you need help" /></b><span>{data.mastery.needs_support}</span></div></div>      <div className="mastery-card"><div><span><LocaleText hinglish="Concept mastery" english="Concept mastery" /></span><strong><LocaleText hinglish="जिन concepts में सबसे ज़्यादा मदद चाहिए, वे पहले" english="Weakest concepts first" /></strong></div>{data.masteryDetails?.length ? data.masteryDetails.map((m:any)=><div className="topic-line" key={m.concept_id}><div><b>{m.concept_title}</b><small>{m.subject_name} · {m.chapter_name}</small></div><span className={m.mastery_percent>=80?'good':''}>{m.mastery_percent}% · {m.attempts} attempts</span></div>) : <div className="topic-line"><span><LocaleText hinglish="अभी इतना learning data नहीं है।" english="There isn’t enough learning data yet." /></span></div>}</div>
      <Link href="/student/learn" className="button button-dark"><LocaleText hinglish="Recommended lesson खोलें →" english="Open recommended lesson →" /></Link>
    </section></main>;
}
