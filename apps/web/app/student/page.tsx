'use client';

import { UiText } from '../i18n/useUiTranslation';
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

  if(error)return <main className="app-shell"><section className="app-content"><div role="alert" aria-live="assertive" className="auth-card"><h1><UiText message="legacy.web.student.unavailable.9bdc6a90" /></h1><p>{error}</p><Link href="/login/student" className="button button-dark"><UiText message="legacy.web.log.in.9cfe4389" /></Link></div></section></main>;
  if(!data)return <main className="app-shell"><section className="app-content"><div role="status" aria-live="polite" className="eyebrow"><UiText message="legacy.web.loading.learning.profile.71b24943" /></div></section></main>;
  const stats=data.lessonStats || {},q=data.questionStats || {};
  const lessons = lessonProgress(stats);
  const completionLabel = displayPercent(lessons.percent);
  const attemptsLabel = displayCount(safeCount(q.attempts));
  const accuracyLabel = displayPercent(safePercent(q.accuracy_percent));
  return <main className="app-shell">
    <div className="app-layout">
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow"><UiText message="legacy.web.your.learning.journey.class.c0b78f63" /> {data.class_code}</span><h1><UiText message="legacy.web.hello.f2b6fddd" />, {data.display_name} 👋</h1><p><UiText message="legacy.web.your.learning.plan.adapts.to.your.prog.73e0d599" /></p></div><div className="streak-card">✓ <strong>{completionLabel}</strong><span><UiText message="legacy.web.curriculum.complete.a2805a20" /></span></div></div>
        <div className="goal-card"><div><span><UiText message="legacy.web.learning.progress.4ff8d239" /></span><strong>{displayCount(lessons.completed)} / {displayCount(lessons.total)} <UiText message="legacy.web.lessons.19bb9b3d" /></strong><small>{attemptsLabel} <UiText message="legacy.web.attempts.17be8a41" /> · {accuracyLabel} <UiText message="legacy.web.graded.accuracy.fd9d43e1" /></small></div><div className="goal-ring">{completionLabel}</div></div>
        <div className="content-heading"><h2><UiText message="legacy.web.your.two.subjects.542c637f" /></h2><span>Class {data.class_code}</span></div>
        <div className="task-list subject-tracks">
          {(['maths','science'] as const).map((subjectCode)=>{
            const track=(data.curriculum||[]).find((item:any)=>item.subject_code===subjectCode);
            const title=subjectCode==='maths'?<UiText message="legacy.web.mathematics.4cf6be1a" />:<UiText message="legacy.web.science.f85a10aa" />;
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
                  <small>{Number(track?.chapters||0)} <UiText message="legacy.web.chapters.b7609a1c" /> · {count} <UiText message="legacy.web.published.lessons.80cf05d5" /></small>
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

        <div className="content-heading"><h2><UiText message="legacy.web.your.courses.0f970408" /></h2><span><UiText message="legacy.web.independent.learning.paths.34174ec8" /></span></div>
        {courseError&&<p role="status" className="feedback">{courseError}</p>}
        <div className="task-list subject-tracks">
          {courses.length===0&&!courseError?<p className="eyebrow"><UiText message="legacy.web.no.courses.are.available.yet.3e7cbb53" /></p>:courses.map((course:any)=>(
            <article key={course.id} className="app-task subject-track-card">
              <div className="subject-track-main">
                <span className="task-icon">✦</span>
                <span className="subject-track-copy"><strong>{course.display_name}</strong>
                  <small><UiText message="legacy.web.independent.course.56d420a8" /> · {course.curriculum_version}</small>
                  <small>{course.is_enrolled?<UiText message="legacy.web.enrolled.877240d1" />:<UiText message="legacy.web.available.to.enrol.046b7590" />}</small>
                </span>
                {!course.is_enrolled?<button type="button" className="button button-dark" disabled={courseBusy!==''} onClick={()=>void enrolCourse(Number(course.id))}>{courseBusy===String(course.id)?<UiText message="legacy.web.enrolling.0af6a662" />:<UiText message="legacy.web.join.course.064866a3" />}</button>:null}
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
                      <strong><UiText message="legacy.web.your.learning.progress.5f14fda3" /></strong>
                      {!metric
                        ? <small><UiText message="legacy.web.loading.course.progress.a62fe9b3" /></small>
                        : metric.error
                          ? <small><UiText message="legacy.web.progress.could.not.load.yet.please.try.9e8771b4" /></small>
                          : total>0
                          ? <><small>{completed} / {total} <UiText message="legacy.web.published.lessons.completed.b4fb79bf" /> · {Math.max(0,Math.min(100,percent))}%</small><small>{Number(questionStats.attempts||0)} <UiText message="legacy.web.question.attempts.d72ed417" /></small><span className="qe-course-progress-track" aria-label={Math.max(0,Math.min(100,percent))+'% complete'} style={{display:'block',height:'6px',background:'var(--border-color, #e5e7eb)',borderRadius:'999px',overflow:'hidden',marginTop:'8px'}}><span style={{display:'block',height:'100%',width:Math.max(0,Math.min(100,percent))+'%',background:'var(--brand-primary, #2563eb)',borderRadius:'999px'}} /></span></>
                          : <small><UiText message="legacy.web.no.published.lessons.are.available.in..ee9e0670" /></small>}
                    </span>
                    <span className="subject-book-open"><Link href={'/student/progress?courseId='+encodeURIComponent(String(course.id))}><UiText message="legacy.web.full.progress.c5aa541a" /></Link></span>
                  </div>;
                })()}
                {(Array.isArray(course.subjects)?course.subjects:[]).map((subject:any)=>(
                  <Link key={subject.subject_code} href={'/student/learn?courseId='+encodeURIComponent(String(course.id))+'&subjectCode='+encodeURIComponent(String(subject.subject_code))} className="subject-book-link">
                    <span className="subject-book-copy"><strong>{subject.subject_name}</strong><small>{Number(subject.chapters||0)} <UiText message="legacy.web.chapters.b7609a1c" /> · {Number(subject.lessons||0)} <UiText message="legacy.web.lessons.6f0bbbe0" /></small></span>
                    <span className="subject-book-open"><UiText message="legacy.web.learn.26a6ff06" /></span>
                  </Link>
                ))}
                <div className="subject-book-link">
                  <span className="subject-book-copy"><strong><UiText message="legacy.web.course.tools.7ff26a01" /></strong><small><UiText message="legacy.web.tests.saved.results.and.course.progres.8af52934" /></small></span>
                  <span className="subject-book-open"><Link href={'/student/tests?courseId='+encodeURIComponent(String(course.id))}><UiText message="admin.navigation.short.tests" /></Link> · <Link href={'/student/progress?courseId='+encodeURIComponent(String(course.id))}><UiText message="legacy.web.progress.953da4d3" /></Link></span>
                </div>
              </div>}
            </article>
          ))}
        </div>

        <div className="content-heading"><h2><UiText message="legacy.web.what.should.you.do.today.e962abaa" /></h2><span><UiText message="legacy.web.mastery.driven.6ffd2e51" /></span></div>
        <div className="task-list">
          {rec?.kind==='DIAGNOSTIC' ? <Link href="/student/diagnostic" className="app-task next"><span className="task-icon">◎</span><div><strong><UiText message="legacy.web.first.learning.diagnostic.dfd9c4b8" /></strong><small><UiText message="legacy.web.a.short.assessment.your.starting.recom.5d7cf83d" /></small></div><span className="task-action">→</span></Link> : rec?.lesson ? <Link href={'/student/learn?lessonId='+rec.lesson.id} className="app-task next"><span className="task-icon">◎</span><div><strong>{rec.lesson.title}</strong><small><UiText message="legacy.web.recommended.based.on.your.progress.and.3725823f" /></small></div><span className="task-action">→</span></Link> : rec?.available===false ? <div className="app-task next complete"><span className="task-icon">✓</span><div><strong><UiText message="legacy.web.you.ve.completed.the.curriculum.631eb02d" /></strong><small><UiText message="legacy.web.you.can.repeat.practice.or.review.your.73ce60f3" /></small></div></div> : null}
          <Link href="/student/learn" className="app-task"><span className="task-icon">∑</span><div><strong><UiText message="legacy.web.learning.9e92d931" /></strong><small><UiText message="legacy.web.explanation.worked.example.guided.prac.1661421d" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/practice" className="app-task"><span className="task-icon">✦</span><div><strong><UiText message="web.navigation.student.mobilePractice" /></strong><small><UiText message="legacy.web.answers.are.saved.and.mastery.is.updat.085d71fd" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/tests" className="app-task"><span className="task-icon">✓</span><div><strong><UiText message="legacy.web.topic.chapter.and.other.tests.959db31f" /></strong><small><UiText message="legacy.web.saved.attempts.teacher.review.and.rele.210e3fce" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/progress" className="app-task"><span className="task-icon">↗</span><div><strong><UiText message="web.navigation.student.myProgress" /></strong><small><UiText message="legacy.web.lessons.mastery.and.test.results.07c3bf88" /></small></div><span className="task-action">→</span></Link>
          <Link href="/student/account-lifecycle" className="app-task"><span className="task-icon">◉</span><div><strong><UiText message="legacy.web.account.status.803f48ff" /></strong><small><UiText message="legacy.web.verification.and.account.options.d346706e" /></small></div><span className="task-action">→</span></Link>
        </div>
      </section>
    </div>
  </main>;
}
