'use client';

import { UiText } from '../i18n/useUiTranslation';
import { LocaleText } from '../components/LanguageProvider';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type ChildRow={id:number;display_name:string;board?:string};
type ChildCourseRow={id:number;course_id?:number;display_name?:string;course_name?:string;code?:string;enrolled?:boolean;paused?:boolean};
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
  const [childCertificates,setChildCertificates]=useState<any[]>([]);
  const [childCourses,setChildCourses]=useState<ChildCourseRow[]>([]);
  const [selectedChildId,setSelectedChildId]=useState<number|null>(null);
  const [selectedCourseId,setSelectedCourseId]=useState<number|null>(null);
  const [courseChoiceError,setCourseChoiceError]=useState('');
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);
  const [switchingChild,setSwitchingChild]=useState(false);

  async function load(id?:number, requestedCourseId?:number){
    const switchingChild=id!==undefined && id!==selectedChildId;
    const switchingCourse=requestedCourseId!==undefined;
    if(switchingChild || (!selected && !switchingCourse))setSwitchingChild(true);
    if(!selected || switchingChild || switchingCourse)setSelected(null);
    if(switchingChild){setSelectedCourseId(null);setChildCourses([]);setChildCertificates([]);}
    setError('');
    setCourseChoiceError('');
    try{
      const sessionResponse=await fetch('/api/v1/auth/me',{cache:'no-store'});
      if(!sessionResponse.ok){router.replace('/login');return;}
      const session=await readApi(sessionResponse);
      if(session.role!=='PARENT'&&session.role!=='ADMIN'){router.replace('/login');return;}
      const cr=await fetch('/api/v1/guardians/children',{cache:'no-store'});
      if(cr.status===401||cr.status===403){router.replace('/login');return;}
      const cb=await readApi(cr);
      if(!cr.ok)throw new Error(cb.message||`Unable to load child profiles (HTTP ${cr.status}).`);
      if(!Array.isArray(cb))throw new Error('The API returned an invalid child-profile list.');
      setChildren(cb);
      const child=cb.find((x:ChildRow)=>x.id===(id??selectedChildId))||cb[0];
      if(!child){setSelected(null);setChildCertificates([]);setChildCourses([]);setSelectedChildId(null);return;}
      setSelectedChildId(child.id);

      const coursesResponse=await fetch(`/api/v1/guardians/children/${child.id}/programs`,{cache:'no-store'});
      const coursesBody=await readApi(coursesResponse);
      if(!coursesResponse.ok)throw new Error(coursesBody.message||`Unable to load this child's courses (HTTP ${coursesResponse.status}).`);
      const enrolled:ChildCourseRow[]=(Array.isArray(coursesBody)?coursesBody:[]).filter((course:ChildCourseRow)=>
        (course.enrolled===true||course.paused===true) && Number(course.id||course.course_id)>0
      ).map((course:ChildCourseRow)=>({...course,id:Number(course.id||course.course_id)}));
      setChildCourses(enrolled);
      if(enrolled.length===0){
        setSelected(null);setChildCertificates([]);
        setSelectedCourseId(null);
        setCourseChoiceError('This child has no enrolled course in this institution yet.');
        router.replace('/parent?childId='+encodeURIComponent(String(child.id)));
        return;
      }

      const previouslySelected=selected && Number(selected.id)===child.id ? selectedCourseId : null;
      const requested=(requestedCourseId!==undefined ? requestedCourseId : (switchingChild ? undefined : previouslySelected));
      const course=requested!==undefined
        ? enrolled.find((item:ChildCourseRow)=>item.id===requested)
        : enrolled.length===1 ? enrolled[0] : undefined;
      if(requested!==undefined && !course)throw new Error('Choose a course that this child is enrolled in at the selected institution.');
      if(!course){
        setSelected(null);setChildCertificates([]);setSelectedCourseId(null);
        setCourseChoiceError('This child is enrolled in more than one course. Select which course report to view.');
        router.replace('/parent?childId='+encodeURIComponent(String(child.id)));
        return;
      }
      const courseId=Number(course.id);
      setSelectedCourseId(courseId);
      const rr=await fetch(`/api/v1/guardians/children/${child.id}/report?courseId=${encodeURIComponent(String(courseId))}`,{cache:'no-store'});
      const rb=await readApi(rr);
      if(!rr.ok)throw new Error(rb.message||`Unable to load the learning report (HTTP ${rr.status}).`);
      if(!rb||typeof rb!=='object')throw new Error('The API returned an invalid learning report.');
      setSelected(rb);
      router.replace('/parent?childId='+encodeURIComponent(String(child.id))+'&courseId='+encodeURIComponent(String(courseId)));
      try{
        const certificatesResponse=await fetch(`/api/v1/guardians/children/${child.id}/certificates?courseId=${encodeURIComponent(String(courseId))}`,{cache:'no-store'});
        const certificateBody=await readApi(certificatesResponse);
        setChildCertificates(certificatesResponse.ok&&Array.isArray(certificateBody.items)?certificateBody.items:[]);
      }catch{
        setChildCertificates([]);
      }
    }catch(e:any){
      setSelected(null);
      setError(e?.message||'The parent report could not be loaded. Check the local services and retry.');
    }finally{
      setSwitchingChild(false);
    }
  }

  useEffect(()=>{
    const params=new URLSearchParams(window.location.search);
    const childId=Number(params.get('childId')||0);
    const courseId=Number(params.get('courseId')||0);
    void load(childId>0?childId:undefined,courseId>0?courseId:undefined);
  },[]);

  if(loading)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><UiText message="legacy.web.parent.report.ea2561e5" /></span><h1><UiText message="legacy.web.loading.your.report.550cf93b" /></h1><p><UiText message="legacy.web.connecting.your.child.s.latest.learnin.d98a9980" /></p></section></main>;

  if(error)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><UiText message="legacy.web.parent.report.ea2561e5" /></span><h1><UiText message="legacy.web.unable.to.load.the.report.ee5dd143" /></h1><p>{error}</p><div className="parent-recovery-actions"><button type="button" className="button button-dark" onClick={()=>void load()}><UiText message="legacy.web.try.again.3609bfa6" /></button><Link href="/parent/children" className="button button-light"><UiText message="legacy.web.manage.children.s.profiles.02deff46" /></Link><Link href="/login" className="text-link"><UiText message="legacy.web.sign.in.again.a85d527c" /></Link></div></section></main>;

  if(!children.length)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><UiText message="legacy.web.family.learning.9f99fe9d" /></span><h1><UiText message="legacy.web.no.active.child.profile.yet.e3baf80e" /></h1><p><UiText message="legacy.web.you.can.create.a.student.profile.and.c.29c7b980" /></p><Link href="/parent/children" className="button button-dark">Bachchon ke profiles manage karein →</Link></section></main>;

  if(!selected)return <main className="parent-app"><section className="parent-dashboard">
    <span className="eyebrow"><UiText message="legacy.web.parent.report.ea2561e5" /></span>
    <h1>{children.find(child=>child.id===selectedChildId)?.display_name||'Child learning report'}</h1>
    <p>{courseChoiceError||'Choose a child and course to see the course-specific learning report.'}</p>
    {children.length>1&&<section className="parent-report-switcher" aria-label="Choose child report">
      <h2>Choose child</h2><div className="parent-report-switcher__profiles">{children.map(child=><button key={child.id} type="button"
        className={child.id===selectedChildId?'parent-report-switcher__profile is-current':'parent-report-switcher__profile'}
        aria-pressed={child.id===selectedChildId} disabled={switchingChild} onClick={()=>void load(child.id)}>
        <span className="parent-report-switcher__avatar">{(child.display_name||'S').trim().slice(0,1)||'S'}</span>
        <span className="parent-report-switcher__copy"><strong>{child.display_name}</strong><small>{child.id===selectedChildId?'Selected child':'View this child'}</small></span>
      </button>)}</div>
    </section>}
    {childCourses.length>0&&<div className="parent-course-focus">
      <label htmlFor="parent-course-focus"><strong>Course report</strong><select id="parent-course-focus" value={selectedCourseId===null?'':String(selectedCourseId)}
        disabled={switchingChild} onChange={event=>{const value=Number(event.target.value);if(value>0&&selectedChildId!==null)void load(selectedChildId,value);}}>
        <option value="">Select an enrolled course</option>{childCourses.map(course=><option key={course.id} value={String(course.id)}>{course.display_name||course.course_name||course.code||('Course '+course.id)}</option>)}
      </select></label>
      {childCourses.length>1&&<small>Reports show one course at a time; lessons, subjects, mastery, practice and tests won't be mixed across courses.</small>}
    </div>}
    <div className="parent-recovery-actions"><button className="button button-dark" onClick={()=>void load(selectedChildId===null?undefined:selectedChildId)} disabled={switchingChild}>Refresh</button><Link href="/parent/children" className="button button-light"><UiText message="legacy.web.manage.children.s.profiles.02deff46" /></Link></div>
  </section></main>;

  const stats=selected.lessonStats||{};
  const q=selected.questionStats||{};
  const mastery=selected.mastery||{};
  const sessionStats=selected.sessionStats||{};
  const curriculum=Array.isArray(selected.curriculum)?selected.curriculum:[];
  const masteryDetails=Array.isArray(selected.masteryDetails)?selected.masteryDetails:[];
  const recentAttempts=Array.isArray(selected.recentAttempts)?selected.recentAttempts:[];
  const assessmentStats=selected.assessmentStats||{};
  const recentAssessments=Array.isArray(selected.recentAssessments)?selected.recentAssessments:[];
  const assessmentByType=Array.isArray(selected.assessmentByType)?selected.assessmentByType:[];
  const assessmentTypeLabel=(kind:string)=>kind==='TOPIC_END'?'Topic-end test':kind==='CHAPTER_END'?'Chapter-end test':'Admin-created test';
  const assessmentStatusLabel=(status:string)=>status==='RELEASED'?'Result released':status==='AWAITING_REVIEW'?'Awaiting teacher review':status==='GRADED'?'Graded · awaiting release':status==='IN_PROGRESS'?'In progress':String(status||'—').replaceAll('_',' ').toLowerCase();

  return <main className="parent-app">
    <section className="parent-dashboard">
      <div><span className="eyebrow"><UiText message="legacy.web.learning.summary.26813e0d" /></span><h1>{selected.display_name||'Student'} <UiText message="legacy.web.learning.summary.4810ba16" /></h1><p><UiText message="legacy.web.this.report.is.based.on.your.linked.ch.22cc12a8" /></p><Link href="/parent/children" className="text-link"><UiText message="legacy.web.manage.children.s.profiles.and.login.d.13e42087" /></Link></div>
      {children.length>1&&<section className="parent-report-switcher" aria-label="Choose a child’s learning report">
        <div className="parent-report-switcher__intro"><span className="eyebrow"><UiText message="legacy.web.family.account.82aa2c57" /></span><h2><UiText message="legacy.web.each.child.has.their.own.progress.0cce94cd" /></h2><p><UiText message="legacy.web.select.a.child.below.to.view.their.les.22ac4c22" /></p></div>
        <div className="parent-report-switcher__profiles">{children.map(c=><button key={c.id} type="button" className={Number(selected.id)===c.id?'parent-report-switcher__profile is-current':'parent-report-switcher__profile'} aria-pressed={Number(selected.id)===c.id} disabled={switchingChild} onClick={()=>void load(c.id)}>
          <span className="parent-report-switcher__avatar">{(c.display_name||'S').trim().slice(0,1)||'S'}</span>
          <span className="parent-report-switcher__copy"><strong>{c.display_name}</strong><small>{c.board ? 'Board · '+c.board : 'Linked student profile'}</small><small>{Number(selected.id)===c.id?<UiText message="legacy.web.currently.viewing.this.report.f032823e" />:<UiText message="legacy.web.view.this.child.s.report.1a70cdbc" />}</small></span>
          <span className="parent-report-switcher__arrow">{Number(selected.id)===c.id?'✓':'→'}</span>
        </button>)}</div>
        {switchingChild&&<p className="parent-report-switcher__status" role="status"><UiText message="legacy.web.loading.the.selected.child.s.report.2520e7b9" /></p>}
      </section>}
      {childCourses.length>1&&<section className="parent-course-focus">
        <label htmlFor="parent-course-focus"><strong>Course report</strong>
          <select id="parent-course-focus" value={selectedCourseId===null?'':String(selectedCourseId)} disabled={switchingChild}
            onChange={event=>{const value=Number(event.target.value);if(value>0&&selectedChildId!==null)void load(selectedChildId,value);}}>
            {childCourses.map(course=><option key={course.id} value={String(course.id)}>{course.display_name||course.course_name||course.code||('Course '+course.id)}</option>)}
          </select>
        </label><small>Showing one course only: {selected.courseName||selected.course_name||'Selected course'}. Switch to view another course's report.</small>
      </section>}
      <div className="parent-stats">
        <article><span><UiText message="legacy.web.lessons.completed.717f4a1a" /></span><strong>{stats.completed_lessons??0}</strong><small>of {stats.total_lessons??0} published lessons</small></article>
        <article><span><UiText message="legacy.web.practice.accuracy.68741915" /></span><strong>{q.accuracy_percent??0}%</strong><small>{q.attempts??0} <UiText message="legacy.web.saved.attempts.7e9ba519" /></small></article>
        <article><span><UiText message="legacy.web.curriculum.6b69620f" /></span><strong>{stats.completion_percent??0}%</strong><small><UiText message="legacy.web.completion.ddfe45c9" /></small></article>
        <article><span><UiText message="legacy.web.tests.released.cdea55dd" /></span><strong>{assessmentStats.released??0}</strong><small>{assessmentStats.awaiting_review??0} awaiting review · {assessmentStats.average_released_percent??0}% avg</small></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong><UiText message="legacy.web.curriculum.covered.a8cbc92c" /></strong><span>✓</span></div>{curriculum.length?curriculum.map((item:any)=><div className="topic-line" key={item.subject_code}><b>{item.subject_name||item.subject_code}</b><span className="good">{item.completed??0}/{item.lessons??0} <UiText message="legacy.web.lessons.19bb9b3d" /></span></div>):<p><UiText message="legacy.web.there.is.no.summary.available.for.subs.c6842db3" /></p>}</article>
        <article className="report-panel"><div className="panel-title"><strong><UiText message="legacy.web.learning.signals.cc7292e3" /></strong><span>→</span></div><div className="topic-line"><b><UiText message="legacy.web.average.mastery.29b66a35" /></b><span className="good">{mastery.average_mastery??0}%</span></div><div className="topic-line"><b><UiText message="legacy.web.mastered.concepts.90892b35" /></b><span className="good">{mastery.mastered??0}</span></div><div className="topic-line"><b><UiText message="legacy.web.needs.support.65a7a010" /></b><span>{mastery.needs_support??0}</span></div><div className="topic-line"><b><UiText message="legacy.web.learning.time.30d.e549b115" /></b><span>{sessionStats.minutes_30d??0} min</span></div></article>
      </div>
      <div className="parent-grid">
        <article className="report-panel"><div className="panel-title"><strong><UiText message="legacy.web.test.history.84aa353d" /></strong><span>✓</span></div>{recentAssessments.length?recentAssessments.map((m:any)=>{
          const certificate=childCertificates.find((item:any)=>Number(item.assessment_attempt_id)===Number(m.attempt_id));
          return <div className="topic-line parent-test-line" key={m.attempt_id}>
            <div><b>{m.assessment_title||assessmentTypeLabel(m.assessment_type)}</b><small>{assessmentTypeLabel(m.assessment_type)} · {m.subject_name||'—'}{m.chapter_name?' · '+m.chapter_name:''}</small></div>
            <div className="parent-test-result">
              <span className={m.status==='RELEASED'?'good':''}>{m.status==='RELEASED'?String(m.final_score??'—')+' / '+String(m.max_score??'—'):assessmentStatusLabel(m.status)}</span>
              {certificate?.status==='ISSUED'?<div className="parent-certificate-actions">
                <a href={`/api/v1/guardians/children/${selected?.id}/certificates/${certificate.id}/view?courseId=${encodeURIComponent(String(selectedCourseId||''))}&format=pdf`} target="_blank" rel="noreferrer">View certificate ↗</a>
                <a href={`/api/v1/guardians/children/${selected?.id}/certificates/${certificate.id}/download?courseId=${encodeURIComponent(String(selectedCourseId||''))}&format=pdf`}>PDF</a>
                <a href={`/api/v1/guardians/children/${selected?.id}/certificates/${certificate.id}/download?courseId=${encodeURIComponent(String(selectedCourseId||''))}&format=png`}>PNG</a>
              </div>:certificate?.status==='REVOKED'?<small className="parent-certificate-revoked">Certificate revoked</small>:null}
            </div>
          </div>;
        }):<p><UiText message="legacy.web.the.student.s.test.attempts.will.appea.50db736e" /></p>}</article>
        <article className="report-panel"><div className="panel-title"><strong><UiText message="legacy.web.concepts.needing.support.f1ad7b3b" /></strong><span>↗</span></div>{masteryDetails.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={m.concept_title||i}><div><b>{m.concept_title||'Concept'}</b><small>{m.subject_name} · {m.chapter_name}</small></div><span>{m.mastery_percent??0}%</span></div>)}</article>
        <article className="report-panel"><div className="panel-title"><strong><UiText message="legacy.web.recent.practice.8625ca4c" /></strong><span>✓</span></div>{recentAttempts.slice(0,6).map((m:any,i:number)=><div className="topic-line" key={i}><div><b>{m.concept_title||m.chapter_name||'Practice'}</b><small>{m.question_type||'Question'}</small></div><span className={m.correct===true?'good':''}>{m.correct===true?<UiText message="legacy.web.correct.d01db0a2" />:m.correct===false?<UiText message="legacy.web.incorrect.c7d63751" />:<UiText message="legacy.web.review.3eb1797d" />}</span></div>)}</article>
      </div>
      <p className="parent-readonly-note"><UiText message="legacy.web.parent.view.is.read.only.lessons.pract.92075e32" /></p>
    </section>
  </main>;
}
