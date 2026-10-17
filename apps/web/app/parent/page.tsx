'use client';

import { UiText } from '../i18n/useUiTranslation';
import { LocaleText } from '../components/LanguageProvider';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type ChildRow={id:number;display_name:string;class_code?:string;board?:string};
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
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);
  const [switchingChild,setSwitchingChild]=useState(false);

  async function load(id?:number){
    const switching=id!==undefined;
    if(switching)setSwitchingChild(true);else setLoading(true);
    setError('');
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
      const child=cb.find((x:ChildRow)=>x.id===id)||cb[0];
      if(!child){setSelected(null);setChildCertificates([]);return;}
      setChildCertificates([]);

      const rr=await fetch(`/api/v1/guardians/children/${child.id}/report`,{cache:'no-store'});
      const rb=await readApi(rr);
      if(!rr.ok)throw new Error(rb.message||`Unable to load the learning report (HTTP ${rr.status}).`);
      if(!rb||typeof rb!=='object')throw new Error('The API returned an invalid learning report.');
      setSelected(rb);
      try{
        const certificatesResponse=await fetch(`/api/v1/guardians/children/${child.id}/certificates`,{cache:'no-store'});
        const certificateBody=await readApi(certificatesResponse);
        setChildCertificates(certificatesResponse.ok&&Array.isArray(certificateBody.items)?certificateBody.items:[]);
      }catch{
        // Keep the read-only learning report usable if certificate retrieval is temporarily unavailable.
        setChildCertificates([]);
      }
    }catch(e:any){
      setSelected(null);
      setError(e?.message||'The parent report could not be loaded. Check the local services and retry.');
    }finally{
      if(switching)setSwitchingChild(false);else setLoading(false);
    }
  }

  useEffect(()=>{void load();},[]);

  if(loading)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><UiText message="legacy.web.parent.report.ea2561e5" /></span><h1><UiText message="legacy.web.loading.your.report.550cf93b" /></h1><p><UiText message="legacy.web.connecting.your.child.s.latest.learnin.d98a9980" /></p></section></main>;

  if(error)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><UiText message="legacy.web.parent.report.ea2561e5" /></span><h1><UiText message="legacy.web.unable.to.load.the.report.ee5dd143" /></h1><p>{error}</p><div className="parent-recovery-actions"><button type="button" className="button button-dark" onClick={()=>void load()}><UiText message="legacy.web.try.again.3609bfa6" /></button><Link href="/parent/children" className="button button-light"><UiText message="legacy.web.manage.children.s.profiles.02deff46" /></Link><Link href="/login" className="text-link"><UiText message="legacy.web.sign.in.again.a85d527c" /></Link></div></section></main>;

  if(!children.length)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><UiText message="legacy.web.family.learning.9f99fe9d" /></span><h1><UiText message="legacy.web.no.active.child.profile.yet.e3baf80e" /></h1><p><UiText message="legacy.web.you.can.create.a.student.profile.and.c.29c7b980" /></p><Link href="/parent/children" className="button button-dark">Bachchon ke profiles manage karein →</Link></section></main>;

  if(!selected)return <main className="parent-app"><section className="parent-dashboard"><span className="eyebrow"><UiText message="legacy.web.parent.report.ea2561e5" /></span><h1><UiText message="legacy.web.no.report.selected.65e1f7ed" /></h1><button className="button button-dark" onClick={()=>void load()}><UiText message="legacy.web.reload.report.bd3558cd" /></button></section></main>;

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
          <span className="parent-report-switcher__copy"><strong>{c.display_name}</strong><small><UiText message="legacy.web.class.9c9232e5" /> {c.class_code||'—'}{c.board?' · '+c.board:''}</small><small>{Number(selected.id)===c.id?<UiText message="legacy.web.currently.viewing.this.report.f032823e" />:<UiText message="legacy.web.view.this.child.s.report.1a70cdbc" />}</small></span>
          <span className="parent-report-switcher__arrow">{Number(selected.id)===c.id?'✓':'→'}</span>
        </button>)}</div>
        {switchingChild&&<p className="parent-report-switcher__status" role="status"><UiText message="legacy.web.loading.the.selected.child.s.report.2520e7b9" /></p>}
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
                <a href={`/api/v1/guardians/children/${selected?.id}/certificates/${certificate.id}/view?format=pdf`} target="_blank" rel="noreferrer">View certificate ↗</a>
                <a href={`/api/v1/guardians/children/${selected?.id}/certificates/${certificate.id}/download?format=pdf`}>PDF</a>
                <a href={`/api/v1/guardians/children/${selected?.id}/certificates/${certificate.id}/download?format=png`}>PNG</a>
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
