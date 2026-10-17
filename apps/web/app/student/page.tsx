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
  const [activeCourseId,setActiveCourseId]=useState<number|null>(null);
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
        if(active) {
          setCourses(rows);
          const ownedRows=rows.filter((course:any)=>course.is_enrolled===true||course.paused===true);
          const serverActive=ownedRows.find((course:any)=>course.is_enrolled===true);
          const activeId=serverActive?Number(serverActive.id):null;
          setActiveCourseId(Number.isSafeInteger(activeId)&&activeId>0?activeId:null);
          if(activeId&&Number.isSafeInteger(activeId)&&activeId>0) {
            try { window.localStorage.setItem('qe:student:activeCourseId',String(activeId)); } catch {}
          }
        }
        const ownedRows=rows.filter((course:any)=>course.is_enrolled===true||course.paused===true);
        const metrics=await Promise.all(ownedRows.map(async(course:any)=>{
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
  const activeCourse = courses.find((course:any)=>Number(course.id)===activeCourseId && course.is_enrolled===true)
    || courses.find((course:any)=>course.is_enrolled===true)
    || null;
  const activeMetric = activeCourse ? courseProgress[Number(activeCourse.id)] : null;
  const activeProgress = activeMetric?.data || null;
  const stats = activeProgress?.lessonStats || {};
  const q = activeProgress?.questionStats || {};
  const lessons = lessonProgress(stats);
  const completionLabel = displayPercent(lessons.percent);
  const attemptsLabel = displayCount(safeCount(q.attempts));
  const accuracyLabel = displayPercent(safePercent(q.accuracy_percent));
  return <main className="app-shell">
    <div className="app-layout">
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow"><UiText message="legacy.web.your.learning.journey.class.c0b78f63" /> {data.class_code}</span><h1><UiText message="legacy.web.hello.f2b6fddd" />, {data.display_name} 👋</h1><p><UiText message="legacy.web.your.learning.plan.adapts.to.your.prog.73e0d599" /></p></div><div className="streak-card">✓ <strong>{completionLabel}</strong><span><UiText message="legacy.web.curriculum.complete.a2805a20" /></span></div></div>
        <div className="goal-card"><div><span><UiText message="legacy.web.learning.progress.4ff8d239" /></span><strong>{displayCount(lessons.completed)} / {displayCount(lessons.total)} <UiText message="legacy.web.lessons.19bb9b3d" /></strong><small>{attemptsLabel} <UiText message="legacy.web.attempts.17be8a41" /> · {accuracyLabel} <UiText message="legacy.web.graded.accuracy.fd9d43e1" /></small></div><div className="goal-ring">{completionLabel}</div></div>
        <section className="qe-active-course-section" aria-label="Active course">
          <div className="content-heading">
            <h2><LocaleText hinglish="आपका कोर्स" english="Your course" /></h2>
            <span>{activeCourse ? <LocaleText hinglish="अभी सीख रहे हैं" english="Learning now" /> : <LocaleText hinglish="कोर्स चुनें" english="Choose a course" />}</span>
          </div>
          <article className="app-task subject-track-card qe-active-course-card">
            <div className="subject-track-main">
              <span className="task-icon">✦</span>
              <span className="subject-track-copy">
                <strong>{activeCourse?.display_name || <LocaleText hinglish="कोई सक्रिय कोर्स नहीं" english="No active course" />}</strong>
                <small>{activeCourse?.course_tag || activeCourse?.tag || activeCourse?.curriculum_version || <LocaleText hinglish="अपनी सीखने की योजना चुनें" english="Choose your learning path" />}</small>
                {activeCourse && activeMetric?.error && <small role="status"><LocaleText hinglish="इस कोर्स की प्रगति अभी लोड नहीं हो पाई।" english="Progress for this course could not be loaded yet." /></small>}
              </span>
              {activeCourse && <span className="qe-active-course-badge"><LocaleText hinglish="सक्रिय" english="Active" /></span>}
            </div>
            {courseError && <p role="status" className="feedback">{courseError}</p>}
            {activeCourse && activeMetric && !activeMetric.error && Number(stats.total_lessons||0)>0 && (
              <div className="subject-book-link qe-course-learning-progress" role="status" aria-live="polite">
                <span className="subject-book-copy">
                  <strong><LocaleText hinglish="इस कोर्स की प्रगति" english="Course learning progress" /></strong>
                  <small>{Number(stats.completed_lessons||0)} / {Number(stats.total_lessons||0)} <LocaleText hinglish="पाठ पूरे" english="lessons completed" /> · {Math.max(0,Math.min(100,Number(stats.completion_percent||0)))}%</small>
                  <span className="qe-course-progress-track" style={{display:'block',height:'6px',background:'var(--border-color, #e5e7eb)',borderRadius:'999px',overflow:'hidden',marginTop:'8px'}}>
                    <span style={{display:'block',height:'100%',width:Math.max(0,Math.min(100,Number(stats.completion_percent||0)))+'%',background:'var(--brand-primary, #e87524)',borderRadius:'999px'}} />
                  </span>
                </span>
                <span className="subject-book-open"><Link href={'/student/progress?courseId='+encodeURIComponent(String(activeCourse.id))}><LocaleText hinglish="पूरी प्रगति" english="Full progress" /></Link></span>
              </div>
            )}
            <div className="qe-active-course-actions">
              <Link href="/student/courses" className="button button-light"><LocaleText hinglish="सभी कोर्स" english="All courses" /> ↗</Link>
              <Link href="/student/marketplace" className="button button-dark"><LocaleText hinglish="मार्केटप्लेस" english="Explore Marketplace" /> ↗</Link>
            </div>
          </article>

          {activeCourse && (
            <>
              <div className="content-heading"><h2><LocaleText hinglish="विषय" english="Subjects" /></h2><span>{activeCourse.display_name}</span></div>
              <div className="task-list subject-tracks">
                {(Array.isArray(activeCourse.subjects)?activeCourse.subjects:[]).map((subject:any)=>{
                  const subjectCode=String(subject.subject_code||subject.code||'').toLowerCase();
                  const subjectBook=textbookDocuments.find(item=>item.scope==='SUBJECT_BOOK'&&item.subject_code===subjectCode);
                  const chapterPdfCount=textbookDocuments.filter(item=>item.scope==='CHAPTER_PDF'&&item.subject_code===subjectCode).length;
                  const saveHomeScroll=()=>localStorage.setItem('qe:student:home:scrollY',String(window.scrollY));
                  const subjectHref='/student/learn?courseId='+encodeURIComponent(String(activeCourse.id))+'&subjectCode='+encodeURIComponent(subjectCode);
                  const bookHref=subjectBook?'/student/textbooks?documentId='+subjectBook.document_id+'&returnTo='+encodeURIComponent('/student'):'#';
                  return <article key={subjectCode} className="app-task subject-track-card">
                    <Link href={subjectHref} className="subject-track-main">
                      <span className="task-icon">{subjectCode==='maths'?'∑':subjectCode==='science'?'⚗':'✦'}</span>
                      <span className="subject-track-copy"><strong>{subject.subject_name||subject.subject_code||subjectCode}</strong>
                        <small>{Number(subject.chapters||0)} <LocaleText hinglish="अध्याय" english="chapters" /> · {Number(subject.lessons||0)} <LocaleText hinglish="पाठ" english="lessons" /></small>
                        <small><LocaleText hinglish="संरचित पाठ खोलें" english="Open structured lessons" /></small>
                      </span>
                      <span className="task-action">→</span>
                    </Link>
                    {subjectBook ? <Link href={bookHref} onClick={saveHomeScroll} className="subject-book-link" aria-label={'Open complete textbook PDF: '+subjectBook.title}>
                      <span className="subject-pdf-mark" aria-hidden="true"><svg viewBox="0 0 24 28"><path d="M4 1.5h10l6 6V26.5H4z"/><path d="M14 1.5v6h6M6.5 16h11M6.5 20h11"/></svg><small>PDF</small></span>
                      <span className="subject-book-copy"><strong>{subjectBook.title}</strong><small><LocaleText hinglish="पूरी पाठ्यपुस्तक" english="Complete textbook" /> · {subjectBook.page_count} <LocaleText hinglish="पृष्ठ" english="pages" /></small></span>
                      <span className="subject-book-open"><LocaleText hinglish="खोलें" english="Open" /> ↗</span>
                    </Link> : textbooksLoading ? <span className="subject-pdf-status"><LocaleText hinglish="PDF देख रहे हैं…" english="Checking PDFs…" /></span>
                      : textbooksError ? <Link href="/student/textbooks" className="subject-pdf-status" onClick={saveHomeScroll}><LocaleText hinglish="पुस्तक लाइब्रेरी खोलें" english="Open textbook library" /> →</Link>
                      : chapterPdfCount>0 ? <Link href={subjectHref} className="subject-pdf-status">{chapterPdfCount} <LocaleText hinglish="अध्याय PDF उपलब्ध" english="chapter PDFs available" /> · <LocaleText hinglish="अध्याय देखें" english="View chapters" /> →</Link>
                      : <span className="subject-pdf-status"><LocaleText hinglish="अभी PDF प्रकाशित नहीं है" english="Textbook PDF not published yet" /></span>}
                  </article>;
                })}
                {(!Array.isArray(activeCourse.subjects)||activeCourse.subjects.length===0) && (
                  <p className="eyebrow"><LocaleText hinglish="इस कोर्स के विषय अभी उपलब्ध नहीं हैं।" english="Subjects are not available for this course yet." /></p>
                )}
              </div>
            </>
          )}
        </section>

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
