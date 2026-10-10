'use client';

import { LocaleText } from '../components/LanguageProvider';

import QuantaEdgeBrand from '../components/QuantaEdgeBrand';
import StudentAccountMenu from '../components/StudentAccountMenu';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function StudentHome(){
  const router=useRouter();
  const [data,setData]=useState<any>(null); const [rec,setRec]=useState<any>(null); const [error,setError]=useState('');
  useEffect(()=>{(async()=>{try{
    const r=await fetch('/api/v1/students/me'); if(r.status===401||r.status===403){router.replace('/login/student');return}
    const b=await r.json(); if(!r.ok) throw new Error(b.message||'Unable to load student');
    setData(b);
    const rr=await fetch('/api/v1/recommendations/next'); if(rr.ok) setRec(await rr.json());
  }catch(e:any){setError(e.message||'Student data unavailable')}})()},[router]);
  if(error)return <main className="app-shell"><section className="app-content"><div className="auth-card"><h1><LocaleText hinglish="Student उपलब्ध नहीं है" english="Student unavailable" /></h1><p>{error}</p><Link href="/login/student" className="button button-dark"><LocaleText hinglish="Login करें" english="Log in" /></Link></div></section></main>;
  if(!data)return <main className="app-shell"><section className="app-content"><div className="eyebrow"><LocaleText hinglish="Learning profile load हो रही है…" english="Loading learning profile…" /></div></section></main>;
  const stats=data.lessonStats,q=data.questionStats;
  return <main className="app-shell">
    <header className="app-header"><QuantaEdgeBrand variant="compact" /><StudentAccountMenu displayName={data.display_name} classCode={data.class_code} profileImageUrl={data.profile_image_url} /></header>
    <div className="app-layout"><aside className="side-nav"><Link className="side-active" href="/student">⌂ <span><LocaleText hinglish="आज" english="Today" /></span></Link><Link href="/student/learn">▣ <span><LocaleText hinglish="पढ़ाई" english="Learning" /></span></Link><Link href="/student/practice">✦ <span><LocaleText hinglish="अभ्यास" english="Practice" /></span></Link><Link href="/student/progress">↗ <span><LocaleText hinglish="मेरी प्रगति" english="My progress" /></span></Link></aside>
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow"><LocaleText hinglish="आपकी learning journey · Class" english="Your learning journey · Class" /> {data.class_code}</span><h1><LocaleText hinglish="नमस्ते" english="Hello" />, {data.display_name} 👋</h1><p><LocaleText hinglish="आज की पढ़ाई आपकी progress और mastery के हिसाब से आगे बढ़ती है।" english="Your learning plan adapts to your progress and mastery." /></p></div><div className="streak-card">✓ <strong>{Math.round(Number(stats.completion_percent))}%</strong><span><LocaleText hinglish="curriculum पूरा" english="curriculum complete" /></span></div></div>
        <div className="goal-card"><div><span><LocaleText hinglish="Learning progress" english="Learning progress" /></span><strong>{stats.completed_lessons} / {stats.total_lessons} <LocaleText hinglish="lessons" english="lessons" /></strong><small>{q.attempts} <LocaleText hinglish="attempts" english="attempts" /> · {q.accuracy_percent}% <LocaleText hinglish="graded accuracy" english="graded accuracy" /></small></div><div className="goal-ring">{Math.round(Number(stats.completion_percent))}%</div></div>
        <div className="content-heading"><h2><LocaleText hinglish="आपके दो subjects" english="Your two subjects" /></h2><span>Class {data.class_code}</span></div>
        <div className="task-list subject-tracks">
          {(['maths','science'] as const).map((subjectCode)=>{
            const track=(data.curriculum||[]).find((item:any)=>item.subject_code===subjectCode);
            const title=subjectCode==='maths'?'गणित':'विज्ञान';
            const count=Number(track?.lessons||0);
            return <Link key={subjectCode} href={'/student/learn?subjectCode='+subjectCode} className="app-task">
              <span className="task-icon">{subjectCode==='maths'?'∑':'⚗'}</span>
              <div><strong>{title}</strong><small>{Number(track?.chapters||0)} <LocaleText hinglish="अध्याय" english="chapters" /> · {count} <LocaleText hinglish="published lessons" english="published lessons" /></small>
              <small>{count>0?<LocaleText hinglish="उपलब्ध पाठ देखें" english="View available lessons" />:<LocaleText hinglish="इस subject के lessons अभी review या writing में हैं। Publish होने पर यहाँ दिखेंगे।" english="Lessons for this subject are being written or reviewed. They will appear here when published." />}</small></div>
              <span className="task-action">→</span>
            </Link>;
          })}
        </div>
        <div className="content-heading"><h2><LocaleText hinglish="आज क्या करें?" english="What should you do today?" /></h2><span><LocaleText hinglish="mastery-driven" english="mastery-driven" /></span></div>
        <div className="task-list">
          {rec?.kind==='DIAGNOSTIC' ? <Link href="/student/diagnostic" className="app-task next"><span className="task-icon">◎</span><div><strong><LocaleText hinglish="पहला learning diagnostic" english="First learning diagnostic" /></strong><small><LocaleText hinglish="छोटा assessment → आपकी शुरुआती recommendation" english="A short assessment → your starting recommendation" /></small></div><span className="task-action">→</span></Link> : rec?.lesson ? <Link href={'/student/learn?lessonId='+rec.lesson.id} className="app-task next"><span className="task-icon">◎</span><div><strong>{rec.lesson.title}</strong><small><LocaleText hinglish="आपकी progress और mastery के आधार पर recommended lesson" english="Recommended based on your progress and mastery" /></small></div><span className="task-action">→</span></Link> : rec?.available===false ? <div className="app-task next complete"><span className="task-icon">✓</span><div><strong><LocaleText hinglish="आपने पूरा curriculum complete कर लिया 🎉" english="You’ve completed the curriculum 🎉" /></strong><small><LocaleText hinglish="अब आप practice दोहरा सकते हैं या अपनी mastery देख सकते हैं।" english="You can repeat practice or review your mastery now." /></small></div></div> : null}
          <Link href="/student/learn" className="app-task"><span className="task-icon">∑</span><div><strong><LocaleText hinglish="पढ़ाई" english="Learning" /></strong><small><LocaleText hinglish="Explanation · worked example · guided practice" english="Explanation · worked example · guided practice" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/practice" className="app-task"><span className="task-icon">✦</span><div><strong><LocaleText hinglish="अभ्यास" english="Practice" /></strong><small><LocaleText hinglish="Answers save होंगे और mastery update होगी" english="Answers are saved and mastery is updated" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/progress" className="app-task"><span className="task-icon">↗</span><div><strong><LocaleText hinglish="मेरी प्रगति" english="My progress" /></strong><small><LocaleText hinglish="Real learning records से progress" english="Progress from real learning records" /></small></div><span className="task-action">→</span></Link>
        </div>
      </section>
    </div>
    <nav className="mobile-nav"><Link className="side-active" href="/student">⌂<span><LocaleText hinglish="आज" english="Today" /></span></Link><Link href="/student/learn">▣<span>Padhai</span></Link><Link href="/student/practice">✦<span>Practice</span></Link><Link href="/student/progress">↗<span>प्रगति</span></Link></nav>
  </main>;
}
