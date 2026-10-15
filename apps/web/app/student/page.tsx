'use client';

import { LocaleText } from '../components/LanguageProvider';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';
import { displayCount, displayPercent, lessonProgress, safeCount, safePercent } from './progressMetrics';

type StudentTextbook = {
  document_id: number;
  scope: 'SUBJECT_BOOK' | 'CHAPTER_PDF';
  title: string;
  page_count: number;
  class_code: string;
  subject_code: string;
  subject_name: string;
  chapter_code?: string | null;
  chapter_name?: string | null;
};

export default function StudentHome(){
  const router=useRouter();
  const [data,setData]=useState<any>(null);
  const [rec,setRec]=useState<any>(null);
  const [error,setError]=useState('');
  const [textbookDocuments,setTextbookDocuments]=useState<StudentTextbook[]>([]);
  const [textbooksLoading,setTextbooksLoading]=useState(true);
  const [textbooksError,setTextbooksError]=useState('');
  const [courses,setCourses]=useState<any[]>([]);
  const [courseProgress,setCourseProgress]=useState<Record<number,any>>({});
  const [courseBusy,setCourseBusy]=useState('');
  const [courseError,setCourseError]=useState('');

  useEffect(()=>{(async()=>{try{
    const r=await fetch('/api/v1/students/me'); if(r.status===401||r.status===403){router.replace('/login/student');return}
    const b=await r.json(); if(!r.ok) throw new Error(b.message||'Unable to load student');
    setData(b);
    const rr=await fetch('/api/v1/recommendations/next'); if(rr.ok) setRec(await rr.json());
  }catch(e:any){setError(e.message||'Student data unavailable')}})()},[router]);

  useEffect(()=>{
    let active=true;
    (async()=>{
      try {
        const response=await fetch('/api/v1/students/me/programs',{credentials:'same-origin',cache:'no-store'});
        if(response.status===401||response.status===403){router.replace('/login/student');return;}
        const body=await response.json();
        if(!response.ok) throw new Error(body.message||body.error||'Courses could not be loaded.');
        const rows=Array.isArray(body)?body:[];
        if(active)setCourses(rows);
        const enrolledRows=rows.filter((course:any)=>course.is_enrolled===true);
        const metrics=await Promise.all(enrolledRows.map(async(course:any)=>{
          const id=Number(course.id);
          if(!Number.isSafeInteger(id)||id<=0)return null;
          try{
            const progressResponse=await fetch('/api/v1/students/me?courseId='+encodeURIComponent(String(id)),{credentials:'same-origin',cache:'no-store'});
            if(!progressResponse.ok)return {id,error:true};
            return {id,data:await progressResponse.json()};
          }catch{return {id,error:true};}
        }));
        if(active){
          const next:Record<number,any>={};
          metrics.forEach((item:any)=>{if(item&&Number.isSafeInteger(item.id))next[item.id]=item;});
          setCourseProgress(next);
        }
      } catch(e:any) {
        if(active)setCourseError(e.message||'Courses could not be loaded.');
      }
    })();
    return ()=>{active=false;};
  },[router]);

  async function enrolCourse(courseId:number){
    const key=String(courseId);
    setCourseBusy(key);setCourseError('');
    try{
      const response=await fetch('/api/v1/students/me/programs/id/'+encodeURIComponent(key)+'/enroll',{
        method:'POST',credentials:'same-origin',headers:{'Content-Type':'application/json'},body:'{}'
      });
      const body=await response.json();
      if(!response.ok)throw new Error(body.message||body.error||'Course enrolment failed.');
      const refresh=await fetch('/api/v1/students/me/programs',{credentials:'same-origin',cache:'no-store'});
      const rows=await refresh.json();
      if(!refresh.ok)throw new Error(rows.message||rows.error||'Course was enrolled, but the catalogue could not refresh.');
      const courseRows=Array.isArray(rows)?rows:[];
      setCourses(courseRows);
      const enrolledRows=courseRows.filter((course:any)=>course.is_enrolled===true);
      const metrics=await Promise.all(enrolledRows.map(async(course:any)=>{
        const id=Number(course.id);
        if(!Number.isSafeInteger(id)||id<=0)return null;
        try{
          const progressResponse=await fetch('/api/v1/students/me?courseId='+encodeURIComponent(String(id)),{credentials:'same-origin',cache:'no-store'});
          return progressResponse.ok?{id,data:await progressResponse.json()}:{id,error:true};
        }catch{return {id,error:true};}
      }));
      const next:Record<number,any>={};
      metrics.forEach((item:any)=>{if(item&&Number.isSafeInteger(item.id))next[item.id]=item;});
      setCourseProgress(next);
    }catch(e:any){setCourseError(e.message||'Course enrolment failed.');}
    finally{setCourseBusy('');}
  }

  useEffect(()=>{
    let active=true;
    (async()=>{
      try {
        const response=await fetch('/api/v1/learning/documents',{credentials:'same-origin',cache:'no-store'});
        if(response.status===401||response.status===403){router.replace('/login/student');return}
        const body=await response.json();
        if(!response.ok) throw new Error(body.detail||body.message||body.error||'Textbooks could not be loaded.');
        if(active) setTextbookDocuments(Array.isArray(body)?body:[]);
      } catch(e:any) {
        if(active) setTextbooksError(e.message||'Textbooks could not be loaded.');
      } finally {
        if(active) setTextbooksLoading(false);
      }
    })();
    return ()=>{active=false};
  },[router]);


  useEffect(()=>{
    if(!data||textbooksLoading)return;
    const key='qe:student:home:scrollY';
    const raw=localStorage.getItem(key);
    if(raw===null)return;
    const target=Math.max(0,Number(raw)||0);
    requestAnimationFrame(()=>{
      window.scrollTo({top:target,behavior:'auto'});
      requestAnimationFrame(()=>{
        window.scrollTo({top:target,behavior:'auto'});
        localStorage.removeItem(key);
      });
    });
  },[data,textbooksLoading]);

  if(error)return <main className="app-shell"><section className="app-content"><div role="alert" aria-live="assertive" className="auth-card"><h1><LocaleText hinglish="Student उपलब्ध नहीं है" english="Student unavailable" /></h1><p>{error}</p><Link href="/login/student" className="button button-dark"><LocaleText hinglish="Login करें" english="Log in" /></Link></div></section></main>;
  if(!data)return <main className="app-shell"><section className="app-content"><div role="status" aria-live="polite" className="eyebrow"><LocaleText hinglish="Learning profile load हो रही है…" english="Loading learning profile…" /></div></section></main>;
  const stats=data.lessonStats || {},q=data.questionStats || {};
  const lessons = lessonProgress(stats);
  const completionLabel = displayPercent(lessons.percent);
  const attemptsLabel = displayCount(safeCount(q.attempts));
  const accuracyLabel = displayPercent(safePercent(q.accuracy_percent));
  return <main className="app-shell">
    <div className="app-layout">
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow"><LocaleText hinglish="आपकी learning journey · Class" english="Your learning journey · Class" /> {data.class_code}</span><h1><LocaleText hinglish="नमस्ते" english="Hello" />, {data.display_name} 👋</h1><p><LocaleText hinglish="आज की पढ़ाई आपकी progress और mastery के हिसाब से आगे बढ़ती है।" english="Your learning plan adapts to your progress and mastery." /></p></div><div className="streak-card">✓ <strong>{completionLabel}</strong><span><LocaleText hinglish="curriculum पूरा" english="curriculum complete" /></span></div></div>
        <div className="goal-card"><div><span><LocaleText hinglish="Learning progress" english="Learning progress" /></span><strong>{displayCount(lessons.completed)} / {displayCount(lessons.total)} <LocaleText hinglish="lessons" english="lessons" /></strong><small>{attemptsLabel} <LocaleText hinglish="attempts" english="attempts" /> · {accuracyLabel} <LocaleText hinglish="graded accuracy" english="graded accuracy" /></small></div><div className="goal-ring">{completionLabel}</div></div>
        <div className="content-heading"><h2><LocaleText hinglish="आपके दो subjects" english="Your two subjects" /></h2><span>Class {data.class_code}</span></div>
        <div className="task-list subject-tracks">
          {(['maths','science'] as const).map((subjectCode)=>{
            const track=(data.curriculum||[]).find((item:any)=>item.subject_code===subjectCode);
            const title=subjectCode==='maths'?<LocaleText hinglish="गणित" english="Mathematics" />:<LocaleText hinglish="विज्ञान" english="Science" />;
            const count=Number(track?.lessons||0);
            const subjectBook=textbookDocuments.find(item=>item.scope==='SUBJECT_BOOK'&&item.subject_code===subjectCode);
            const chapterPdfCount=textbookDocuments.filter(item=>item.scope==='CHAPTER_PDF'&&item.subject_code===subjectCode).length;
            const saveHomeScroll=()=>localStorage.setItem('qe:student:home:scrollY',String(window.scrollY));
            const subjectHref='/student/learn?subjectCode='+subjectCode;
            const bookHref=subjectBook?'/student/textbooks?documentId='+subjectBook.document_id+'&returnTo='+encodeURIComponent('/student'):'#';
            return <article key={subjectCode} className="app-task subject-track-card">
              <Link href={subjectHref} className="subject-track-main">
                <span className="task-icon">{subjectCode==='maths'?'∑':'⚗'}</span>
                <span className="subject-track-copy"><strong>{title}</strong>
                  <small>{Number(track?.chapters||0)} <LocaleText hinglish="अध्याय" english="chapters" /> · {count} <LocaleText hinglish="published lessons" english="published lessons" /></small>
                  <small>{chapterPdfCount>0?chapterPdfCount+' chapter PDFs available':'Open subject and explore chapters'}</small>
                </span>
                <span className="task-action">→</span>
              </Link>
              {subjectBook ? <Link href={bookHref} onClick={saveHomeScroll} className="subject-book-link" aria-label={'Open complete textbook PDF: '+subjectBook.title}>
                <span className="subject-pdf-mark" aria-hidden="true"><svg viewBox="0 0 24 28"><path d="M4 1.5h10l6 6V26.5H4z"/><path d="M14 1.5v6h6M6.5 16h11M6.5 20h11"/></svg><small>PDF</small></span>
                <span className="subject-book-copy"><strong>{subjectBook.title}</strong><small>Complete textbook · {subjectBook.page_count} pages</small></span>
                <span className="subject-book-open">Open ↗</span>
              </Link> : textbooksLoading ? <span className="subject-pdf-status">Checking available PDFs…</span>
                : textbooksError ? <Link href="/student/textbooks" className="subject-pdf-status" onClick={saveHomeScroll}>Open textbook library →</Link>
                : chapterPdfCount>0 ? <Link href={subjectHref} className="subject-pdf-status">{chapterPdfCount} chapter PDFs · View chapters →</Link>
                : <span className="subject-pdf-status">Textbook PDF not published yet</span>}
            </article>;
          })}
        </div>

        <div className="content-heading"><h2><LocaleText hinglish="आपके courses" english="Your courses" /></h2><span><LocaleText hinglish="स्वतंत्र learning paths" english="Independent learning paths" /></span></div>
        {courseError&&<p role="status" className="feedback">{courseError}</p>}
        <div className="task-list subject-tracks">
          {courses.length===0&&!courseError?<p className="eyebrow"><LocaleText hinglish="अभी कोई course उपलब्ध नहीं है।" english="No courses are available yet." /></p>:courses.map((course:any)=>(
            <article key={course.id} className="app-task subject-track-card">
              <div className="subject-track-main">
                <span className="task-icon">✦</span>
                <span className="subject-track-copy"><strong>{course.display_name}</strong>
                  <small><LocaleText hinglish="स्वतंत्र course" english="Independent course" /> · {course.curriculum_version}</small>
                  <small>{course.is_enrolled?<LocaleText hinglish="नामांकन सक्रिय" english="Enrolled" />:<LocaleText hinglish="आपके लिए उपलब्ध" english="Available to enrol" />}</small>
                </span>
                {!course.is_enrolled?<button type="button" className="button button-dark" disabled={courseBusy!==''} onClick={()=>void enrolCourse(Number(course.id))}>{courseBusy===String(course.id)?<LocaleText hinglish="नामांकन हो रहा है…" english="Enrolling…" />:<LocaleText hinglish="Course join करें" english="Join course" />}</button>:null}
              </div>
              {course.is_enrolled&&<div className="subject-track-copy">
                {(()=>{
                  const metric=courseProgress[Number(course.id)];
                  const progress=metric?.data;
                  const lessonStats=progress?.lessonStats||{};
                  const total=Number(lessonStats.total_lessons||0);
                  const completed=Number(lessonStats.completed_lessons||0);
                  const percent=Number(lessonStats.completion_percent||0);
                  const questionStats=progress?.questionStats||{};
                  return <div className="subject-book-link qe-course-learning-progress" role="status" aria-live="polite">
                    <span className="subject-book-copy">
                      <strong><LocaleText hinglish="आपकी learning progress" english="Your learning progress" /></strong>
                      {metric?.error
                        ? <small><LocaleText hinglish="Progress अभी load नहीं हो पाई। फिर कोशिश करें।" english="Progress could not load yet. Please try again." /></small>
                        : total>0
                          ? <><small>{completed} / {total} <LocaleText hinglish="published lessons पूरे" english="published lessons completed" /> · {Math.max(0,Math.min(100,percent))}%</small><small>{Number(questionStats.attempts||0)} <LocaleText hinglish="सवालों के प्रयास" english="question attempts" /></small><span className="qe-course-progress-track" aria-label={Math.max(0,Math.min(100,percent))+'% complete'}><span style={{width:Math.max(0,Math.min(100,percent))+'%'}} /></span></>
                          : <small><LocaleText hinglish="इस course में अभी published lessons उपलब्ध नहीं हैं। Textbook उपलब्ध है; content publish होने पर यहाँ progress दिखेगी।" english="No published lessons are available in this course yet. The textbook is available; progress will appear here when lessons are published." /></small>}
                    </span>
                    <span className="subject-book-open"><Link href={'/student/progress?courseId='+encodeURIComponent(String(course.id))}><LocaleText hinglish="पूरी progress →" english="Full progress →" /></Link></span>
                  </div>;
                })()}
                {(Array.isArray(course.subjects)?course.subjects:[]).map((subject:any)=>(
                  <Link key={subject.subject_code} href={'/student/learn?courseId='+encodeURIComponent(String(course.id))+'&subjectCode='+encodeURIComponent(String(subject.subject_code))} className="subject-book-link">
                    <span className="subject-book-copy"><strong>{subject.subject_name}</strong><small>{Number(subject.chapters||0)} <LocaleText hinglish="अध्याय" english="chapters" /> · {Number(subject.lessons||0)} <LocaleText hinglish="पाठ" english="lessons" /></small></span>
                    <span className="subject-book-open"><LocaleText hinglish="पढ़ें ↗" english="Learn ↗" /></span>
                  </Link>
                ))}
                <div className="subject-book-link">
                  <span className="subject-book-copy"><strong><LocaleText hinglish="Course tools" english="Course tools" /></strong><small><LocaleText hinglish="Tests, saved results और course progress" english="Tests, saved results and course progress" /></small></span>
                  <span className="subject-book-open"><Link href={'/student/tests?courseId='+encodeURIComponent(String(course.id))}><LocaleText hinglish="Tests" english="Tests" /></Link> · <Link href={'/student/progress?courseId='+encodeURIComponent(String(course.id))}><LocaleText hinglish="Progress" english="Progress" /></Link></span>
                </div>
              </div>}
            </article>
          ))}
        </div>

        <div className="content-heading"><h2><LocaleText hinglish="आज क्या करें?" english="What should you do today?" /></h2><span><LocaleText hinglish="mastery-driven" english="mastery-driven" /></span></div>
        <div className="task-list">
          {rec?.kind==='DIAGNOSTIC' ? <Link href="/student/diagnostic" className="app-task next"><span className="task-icon">◎</span><div><strong><LocaleText hinglish="पहला learning diagnostic" english="First learning diagnostic" /></strong><small><LocaleText hinglish="छोटा assessment → आपकी शुरुआती recommendation" english="A short assessment → your starting recommendation" /></small></div><span className="task-action">→</span></Link> : rec?.lesson ? <Link href={'/student/learn?lessonId='+rec.lesson.id} className="app-task next"><span className="task-icon">◎</span><div><strong>{rec.lesson.title}</strong><small><LocaleText hinglish="आपकी progress और mastery के आधार पर recommended lesson" english="Recommended based on your progress and mastery" /></small></div><span className="task-action">→</span></Link> : rec?.available===false ? <div className="app-task next complete"><span className="task-icon">✓</span><div><strong><LocaleText hinglish="आपने पूरा curriculum complete कर लिया 🎉" english="You’ve completed the curriculum 🎉" /></strong><small><LocaleText hinglish="अब आप practice दोहरा सकते हैं या अपनी mastery देख सकते हैं।" english="You can repeat practice or review your mastery now." /></small></div></div> : null}
          <Link href="/student/learn" className="app-task"><span className="task-icon">∑</span><div><strong><LocaleText hinglish="पढ़ाई" english="Learning" /></strong><small><LocaleText hinglish="Explanation · worked example · guided practice" english="Explanation · worked example · guided practice" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/practice" className="app-task"><span className="task-icon">✦</span><div><strong><LocaleText hinglish="अभ्यास" english="Practice" /></strong><small><LocaleText hinglish="Answers save होंगे और mastery update होगी" english="Answers are saved and mastery is updated" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/tests" className="app-task"><span className="task-icon">✓</span><div><strong><LocaleText hinglish="Topic, chapter और अन्य tests" english="Topic, chapter and other tests" /></strong><small><LocaleText hinglish="Saved attempts, teacher review और released results" english="Saved attempts, teacher review and released results" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/progress" className="app-task"><span className="task-icon">↗</span><div><strong><LocaleText hinglish="मेरी प्रगति" english="My progress" /></strong><small><LocaleText hinglish="Lessons, mastery और test results" english="Lessons, mastery and test results" /></small></div><span className="task-action">→</span></Link>
        </div>
      </section>
    </div>
  </main>;
}
