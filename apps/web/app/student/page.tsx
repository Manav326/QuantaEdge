'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function StudentHome(){
  const router=useRouter(); const [data,setData]=useState<any>(null); const [rec,setRec]=useState<any>(null); const [error,setError]=useState('');
  useEffect(()=>{(async()=>{try{
    const r=await fetch('/api/v1/students/me'); if(r.status===401||r.status===403){router.replace('/login');return}
    const b=await r.json(); if(!r.ok) throw new Error(b.message||'Unable to load student');
    setData(b);
    const rr=await fetch('/api/v1/recommendations/next'); if(rr.ok) setRec(await rr.json());
  }catch(e:any){setError(e.message||'Student data unavailable')}})()},[router]);
  if(error)return <main className="app-shell"><section className="app-content"><div className="auth-card"><h1>Student unavailable</h1><p>{error}</p><Link href="/login" className="button button-dark">Login करें</Link></div></section></main>;
  if(!data)return <main className="app-shell"><section className="app-content"><div className="eyebrow">Learning profile लोड हो रहा है…</div></section></main>;
  const stats=data.lessonStats,q=data.questionStats;
  return <main className="app-shell">
    <header className="app-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><div className="student-profile"><span>{data.display_name}</span><span className="avatar">अ</span></div></header>
    <div className="app-layout"><aside className="side-nav"><Link className="side-active" href="/student">⌂ <span>आज</span></Link><Link href="/student/learn">▣ <span>पढ़ाई</span></Link><Link href="/student/practice">✦ <span>अभ्यास</span></Link><Link href="/student/progress">↗ <span>मेरी प्रगति</span></Link></aside>
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow">आपकी learning journey · Class {data.class_code}</span><h1>नमस्ते, {data.display_name} 👋</h1><p>आज की पढ़ाई आपकी progress और mastery के आधार पर आगे बढ़ती है।</p></div><div className="streak-card">✓ <strong>{Math.round(Number(stats.completion_percent))}%</strong><span>curriculum complete</span></div></div>
        <div className="goal-card"><div><span>Learning progress</span><strong>{stats.completed_lessons} / {stats.total_lessons} lessons</strong><small>{q.attempts} attempts · {q.accuracy_percent}% graded accuracy</small></div><div className="goal-ring">{Math.round(Number(stats.completion_percent))}%</div></div>
        <div className="content-heading"><h2>आज क्या करें?</h2><span>mastery-driven</span></div>
        <div className="task-list">
          {rec?.lesson && <Link href={'/student/learn?lessonId='+rec.lesson.id} className="app-task next"><span className="task-icon">◎</span><div><strong>{rec.lesson.title}</strong><small>{rec.reason==='PROGRESS_AND_MASTERY'?'आपकी progress के आधार पर recommended lesson':'Recommended lesson'}</small></div><span className="task-action">→</span></Link>}
          <Link href="/student/learn" className="app-task"><span className="task-icon">∑</span><div><strong>पढ़ाई</strong><small>Explanation · worked example · guided practice</small></div><span className="task-action">→</span></Link>
          <Link href="/student/practice" className="app-task"><span className="task-icon">✦</span><div><strong>अभ्यास</strong><small>Answers save होंगे और mastery update होगी</small></div><span className="task-action">→</span></Link>
          <Link href="/student/progress" className="app-task"><span className="task-icon">↗</span><div><strong>मेरी प्रगति</strong><small>Real learning records से progress</small></div><span className="task-action">→</span></Link>
          <Link href="/parent" className="app-task"><span className="task-icon">◉</span><div><strong>Parent report</strong><small>Guardian account से learning summary</small></div><span className="task-action">→</span></Link>
        </div>
      </section>
    </div>
    <nav className="mobile-nav"><Link className="side-active" href="/student">⌂<span>आज</span></Link><Link href="/student/learn">▣<span>पढ़ाई</span></Link><Link href="/student/practice">✦<span>अभ्यास</span></Link><Link href="/student/progress">↗<span>प्रगति</span></Link></nav>
  </main>;
}
