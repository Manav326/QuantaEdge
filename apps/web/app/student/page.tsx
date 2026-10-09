'use client';

import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import StudentAccountMenu from '../components/StudentAccountMenu';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function StudentHome(){
  const router=useRouter();
  const [data,setData]=useState<any>(null); const [rec,setRec]=useState<any>(null); const [error,setError]=useState('');
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
    <header className="app-header"><QuantaEdgeBrand variant="compact" /><StudentAccountMenu displayName={data.display_name} classCode={data.class_code} profileImageUrl={data.profile_image_url} /></header>
    <div className="app-layout"><aside className="side-nav"><Link className="side-active" href="/student">⌂ <span>आज</span></Link><Link href="/student/learn">▣ <span>Padhai</span></Link><Link href="/student/practice">✦ <span>Practice</span></Link><Link href="/student/progress">↗ <span>मेरी प्रगति</span></Link></aside>
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow">Aapki learning journey · Class {data.class_code}</span><h1>Namaste, {data.display_name} 👋</h1><p>Aaj ki padhai aapki progress aur mastery ke hisaab se aage badhti hai.</p></div><div className="streak-card">✓ <strong>{Math.round(Number(stats.completion_percent))}%</strong><span>curriculum complete</span></div></div>
        <div className="goal-card"><div><span>Learning progress</span><strong>{stats.completed_lessons} / {stats.total_lessons} lessons</strong><small>{q.attempts} attempts · {q.accuracy_percent}% graded accuracy</small></div><div className="goal-ring">{Math.round(Number(stats.completion_percent))}%</div></div>
        <div className="content-heading"><h2>Aapke do subjects</h2><span>Class {data.class_code}</span></div>
        <div className="task-list subject-tracks">
          {(['maths','science'] as const).map((subjectCode)=>{
            const track=(data.curriculum||[]).find((item:any)=>item.subject_code===subjectCode);
            const title=subjectCode==='maths'?'गणित':'विज्ञान';
            const count=Number(track?.lessons||0);
            return <Link key={subjectCode} href={'/student/learn?subjectCode='+subjectCode} className="app-task">
              <span className="task-icon">{subjectCode==='maths'?'∑':'⚗'}</span>
              <div><strong>{title}</strong><small>{Number(track?.chapters||0)} अध्याय · {count} published lessons</small>
              <small>{count>0?'उपलब्ध पाठ देखें':'Is subject ke lessons review ya writing mein hain; publish hone par yahan dikh jayenge.'}</small></div>
              <span className="task-action">→</span>
            </Link>;
          })}
        </div>
        <div className="content-heading"><h2>Aaj kya karein?</h2><span>mastery-driven</span></div>
        <div className="task-list">
          {rec?.kind==='DIAGNOSTIC' ? <Link href="/student/diagnostic" className="app-task next"><span className="task-icon">◎</span><div><strong>Pehla learning diagnostic</strong><small>Chhota assessment → aapki starting recommendation</small></div><span className="task-action">→</span></Link> : rec?.lesson ? <Link href={'/student/learn?lessonId='+rec.lesson.id} className="app-task next"><span className="task-icon">◎</span><div><strong>{rec.lesson.title}</strong><small>Aapki progress aur mastery ke basis par recommended lesson</small></div><span className="task-action">→</span></Link> : rec?.available===false ? <div className="app-task next complete"><span className="task-icon">✓</span><div><strong>Aapne poora curriculum complete kar liya 🎉</strong><small>Ab aap practice repeat kar sakte hain ya apni mastery dekh sakte hain.</small></div></div> : null}
          <Link href="/student/learn" className="app-task"><span className="task-icon">∑</span><div><strong>Padhai</strong><small>Explanation · worked example · guided practice</small></div><span className="task-action">→</span></Link>
          <Link href="/student/practice" className="app-task"><span className="task-icon">✦</span><div><strong>Practice</strong><small>Answers save होंगे और mastery update होगी</small></div><span className="task-action">→</span></Link>
          <Link href="/student/progress" className="app-task"><span className="task-icon">↗</span><div><strong>मेरी प्रगति</strong><small>Real learning records se progress</small></div><span className="task-action">→</span></Link>
        </div>
      </section>
    </div>
    <nav className="mobile-nav"><Link className="side-active" href="/student">⌂<span>आज</span></Link><Link href="/student/learn">▣<span>Padhai</span></Link><Link href="/student/practice">✦<span>Practice</span></Link><Link href="/student/progress">↗<span>प्रगति</span></Link></nav>
  </main>;
}
