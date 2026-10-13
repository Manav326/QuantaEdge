'use client';

import { LocaleText } from '../../components/LanguageProvider';

import Link from 'next/link';
import {useCallback,useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';
import { curriculumProgress, displayCount, displayPercent, lessonProgress, safeCount, safePercent } from '../progressMetrics';

export default function ProgressPage(){
  const router=useRouter(); const [data,setData]=useState<any>(null); const [error,setError]=useState('');
  const [certificates,setCertificates]=useState<any[]>([]);
  const [certificateEligibility,setCertificateEligibility]=useState<Record<number,any>>({});
  const [issuingAttemptId,setIssuingAttemptId]=useState<number|null>(null);
  const [certificateError,setCertificateError]=useState('');
  const [certificateNotice,setCertificateNotice]=useState('');

  const refreshCertificateRecords=useCallback(async(source:any)=>{
    try{
      const certPromise=fetch('/api/v1/students/me/certificates',{cache:'no-store',credentials:'same-origin'});
      const recent=Array.isArray(source?.recentAssessments)?source.recentAssessments:[];
      const ids=[...new Set(recent.map((item:any)=>Number(item.attempt_id)).filter((id:number)=>Number.isSafeInteger(id)&&id>0))].slice(0,100);
      const eligibilityPromise=ids.length
        ?fetch('/api/v1/students/me/certificates/assessment-eligibility?attemptIds='+ids.join(','),{cache:'no-store',credentials:'same-origin'})
        :Promise.resolve(null);
      const [certResponse,eligibilityResponse]=await Promise.all([certPromise,eligibilityPromise]);
      const certBody=await certResponse.json().catch(()=>({}));
      setCertificates(certResponse.ok&&Array.isArray(certBody.items)?certBody.items:[]);
      if(eligibilityResponse){
        const eligibilityBody=await eligibilityResponse.json().catch(()=>({}));
        const mapping:Record<number,any>={};
        if(eligibilityResponse.ok&&Array.isArray(eligibilityBody.items)){
          eligibilityBody.items.forEach((item:any)=>{const id=Number(item.attempt_id);if(Number.isSafeInteger(id)&&id>0)mapping[id]=item;});
        }
        setCertificateEligibility(mapping);
      }else setCertificateEligibility({});
    }catch{
      setCertificates([]);
      setCertificateEligibility({});
    }
  },[]);

  async function issueResultCertificate(attemptId:number){
    setIssuingAttemptId(attemptId);setCertificateError('');setCertificateNotice('');
    try{
      const response=await fetch('/api/v1/students/me/certificates/issue',{
        method:'POST',headers:{'Content-Type':'application/json'},credentials:'same-origin',
        body:JSON.stringify({achievementType:'ASSESSMENT_PASS',assessmentAttemptId:attemptId,templateId:'CLASSIC',language:'en'})
      });
      const body=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(body.message||'Certificate could not be issued.');
      setCertificateNotice('Certificate issued. View or download it from this result.');
      await refreshCertificateRecords(data);
    }catch(e:any){setCertificateError(e?.message||'Certificate could not be issued.');}
    finally{setIssuingAttemptId(null);}
  }

  useEffect(()=>{(async()=>{const r=await fetch('/api/v1/students/me',{cache:'no-store',credentials:'same-origin'});if(r.status===401||r.status===403){router.replace('/login/student');return}const b=await r.json();if(!r.ok){setError(b.message||'Unable to load progress');return}setData(b);await refreshCertificateRecords(b)})()},[router,refreshCertificateRecords]);
  if(error)return <main className="app-shell"><section className="progress-page"><div className="auth-card"><h1><LocaleText hinglish="Progress load नहीं हो पाई" english="Unable to load progress" /></h1><p>{error}</p></div></section></main>;
  if(!data)return <main className="app-shell"><section className="progress-page"><div className="eyebrow"><LocaleText hinglish="Progress load हो रही है…" english="Loading progress…" /></div></section></main>;
  const stats=data.lessonStats || {},q=data.questionStats || {};
  const lessons = lessonProgress(stats);
  const accuracy = safePercent(q.accuracy_percent);
  const mastery = data.mastery || {};
  const masteryAverage = safePercent(mastery.average_mastery);
  const curriculum = Array.isArray(data.curriculum) ? data.curriculum : [];
  const masteryDetails = Array.isArray(data.masteryDetails) ? data.masteryDetails : [];
  const assessmentStats = data.assessmentStats || {};
  const assessmentByType = Array.isArray(data.assessmentByType) ? data.assessmentByType : [];
  const recentAssessments = Array.isArray(data.recentAssessments) ? data.recentAssessments : [];
  const assessmentTypeLabel = (kind:string) => kind==='TOPIC_END'?'Topic-end test':kind==='CHAPTER_END'?'Chapter-end test':'Admin-created test';
  const assessmentStatusLabel = (status:string) => status==='RELEASED'?'Result released':status==='AWAITING_REVIEW'?'Awaiting teacher review':status==='GRADED'?'Graded · awaiting release':status==='IN_PROGRESS'?'In progress':status.replaceAll('_',' ').toLowerCase();
  return <main className="app-shell">
    <section className="progress-page"><div><span className="eyebrow"><LocaleText hinglish="आपकी progress" english="Your progress" /></span><h1><LocaleText hinglish="जो सीखा, वह साफ़ दिख रहा है।" english="See what you’ve learned at a glance." /></h1><p><LocaleText hinglish="हर score और completion आपके actual learning records से आता है।" english="Every score and completion figure comes from your actual learning records." /></p></div>
      <div className="progress-grid"><article><span><LocaleText hinglish="Lessons" english="Lessons" /></span><strong>{displayCount(lessons.completed)} / {displayCount(lessons.total)}</strong><small><LocaleText hinglish="पूरे" english="completed" /></small></article><article><span><LocaleText hinglish="Practice accuracy" english="Practice accuracy" /></span><strong>{displayPercent(accuracy)}</strong><small>{displayCount(safeCount(q.attempts))} attempts · {displayCount(safeCount(q.graded_attempts))} graded</small></article><article><span><LocaleText hinglish="Curriculum" english="Curriculum" /></span><strong>{displayPercent(lessons.percent)}</strong><small><LocaleText hinglish="published lessons पूरे" english="published lessons completed" /></small></article><article><span><LocaleText hinglish="Formal tests" english="Formal tests" /></span><strong>{displayCount(safeCount(assessmentStats.released))}</strong><small>{displayCount(safeCount(assessmentStats.awaiting_review))} awaiting review · {displayPercent(safePercent(assessmentStats.average_released_percent))} avg released</small></article></div>
      {(certificateError||certificateNotice)&&<div className={certificateError?'feedback':'parent-notice'} role={certificateError?'alert':'status'}>{certificateError||certificateNotice}</div>}
      <div className="mastery-card"><div><span><LocaleText hinglish="आपके test results" english="Your test results" /></span><strong>{displayCount(safeCount(assessmentStats.submitted))} submitted · {displayCount(safeCount(assessmentStats.released))} released</strong></div>{assessmentByType.length?assessmentByType.map((item:any)=><div className="topic-line" key={item.assessment_type}><b>{assessmentTypeLabel(item.assessment_type)}</b><span>{displayCount(safeCount(item.released))} released · {displayPercent(safePercent(item.average_released_percent))} average</span></div>):<div className="topic-line"><span><LocaleText hinglish="अभी कोई formal test result नहीं है।" english="No formal test results yet." /></span></div>}</div>
      <div className="mastery-card"><div><span><LocaleText hinglish="Curriculum coverage" english="Curriculum coverage" /></span><strong>Class {data.class_code || '—'}</strong></div>{curriculum.length ? curriculum.map((item:any)=>{const metric=curriculumProgress(item);const label=metric.percent===null?(metric.total===0?'Not published':'—'):metric.percent+'%';return <div className="mastery-row" key={item.subject_code || item.subject_name}><span>{item.subject_name || item.subject_code || 'Subject'}</span><i><b style={{width:(metric.percent ?? 0)+'%'}}/></i><strong>{label}</strong></div>}) : <div className="topic-line"><span><LocaleText hinglish="Curriculum coverage अभी उपलब्ध नहीं है।" english="Curriculum coverage is not available yet." /></span></div>}</div>
      <div className="mastery-card"><div><span><LocaleText hinglish="Mastery" english="Mastery" /></span><strong>{displayPercent(masteryAverage)} average</strong></div><div className="topic-line"><b><LocaleText hinglish="Master हो चुके concepts" english="Mastered concepts" /></b><span className="good">{displayCount(safeCount(mastery.mastered))}</span></div><div className="topic-line"><b><LocaleText hinglish="जिन concepts में मदद चाहिए" english="Concepts where you need help" /></b><span>{displayCount(safeCount(mastery.needs_support))}</span></div></div>      <div className="mastery-card"><div><span><LocaleText hinglish="Concept mastery" english="Concept mastery" /></span><strong><LocaleText hinglish="जिन concepts में सबसे ज़्यादा मदद चाहिए, वे पहले" english="Weakest concepts first" /></strong></div>{masteryDetails.length ? masteryDetails.map((m:any)=><div className="topic-line" key={m.concept_id ?? m.concept_title}><div><b>{m.concept_title || 'Concept'}</b><small>{m.subject_name || '—'} · {m.chapter_name || '—'}</small></div><span className={safePercent(m.mastery_percent)!==null && safePercent(m.mastery_percent)!>=80?'good':''}>{displayPercent(safePercent(m.mastery_percent))} · {displayCount(safeCount(m.attempts))} attempts</span></div>) : <div className="topic-line"><span><LocaleText hinglish="अभी इतना learning data नहीं है।" english="There isn’t enough learning data yet." /></span></div>}</div>
      <div className="mastery-card"><div><span><LocaleText hinglish="Recent tests" english="Recent tests" /></span><strong><LocaleText hinglish="हर attempt और result सुरक्षित रहता है" english="Every attempt and result is preserved" /></strong></div>{recentAssessments.length?recentAssessments.map((item:any)=>{
        const certificate=certificates.find((record:any)=>Number(record.assessment_attempt_id)===Number(item.attempt_id));
        const eligibility=certificateEligibility[Number(item.attempt_id)];
        return <div className="topic-line student-test-line" key={item.attempt_id}>
          <div><b>{item.assessment_title || assessmentTypeLabel(item.assessment_type)}</b><small>{assessmentTypeLabel(item.assessment_type)} · {item.subject_name || '—'}{item.chapter_name?' · '+item.chapter_name:''}</small></div>
          <div className="student-test-result">
            <span className={item.status==='RELEASED'?'good':''}>{item.status==='RELEASED'?String(item.final_score??'—')+' / '+String(item.max_score??'—'):assessmentStatusLabel(item.status)}</span>
            {certificate?.status==='ISSUED'?<div className="student-certificate-actions">
              <a href={'/api/v1/students/me/certificates/'+certificate.id+'/view?format=pdf'} target="_blank" rel="noreferrer">View ↗</a>
              <a href={'/api/v1/students/me/certificates/'+certificate.id+'/download?format=pdf'}>PDF</a>
              <a href={'/api/v1/students/me/certificates/'+certificate.id+'/download?format=png'}>PNG</a>
            </div>:certificate?.status==='REVOKED'?<small className="parent-certificate-revoked">Certificate revoked</small>:eligibility?.eligible?(
              <button type="button" className="student-cert-issue" disabled={issuingAttemptId!==null} onClick={()=>void issueResultCertificate(Number(item.attempt_id))}>
                {issuingAttemptId===Number(item.attempt_id)?'Issuing…':'Issue certificate'}
              </button>
            ):item.status==='RELEASED'?<small className="student-cert-hint">{({
              ADMIN_CREATED_TEST_ONLY:'Only admin-created tests qualify; generated tests and practice are excluded.',
              SUBJECT_COMPLETION_REQUIRED:'Complete all published lessons in this subject to unlock the test certificate.',
              PASS_THRESHOLD_NOT_MET:'The released score is below the certificate threshold.',
              PUBLISHED_LESSONS_NOT_AVAILABLE:'Published lessons are not available for this subject yet.',
              SUBJECT_TRACK_NOT_ACTIVE:'An active subject enrollment is required.',
              CERTIFICATE_REVOKED:'This certificate has been revoked.'
            } as Record<string,string>)[String(eligibility?.eligibility_reason)]||'Certificate eligibility could not be checked; open results to retry.'}</small>:null}
          </div>
        </div>;
      }):<div className="topic-line"><span><LocaleText hinglish="Test attempt शुरू करने पर उसका status और result यहाँ दिखेगा।" english="Your test status and result will appear here after you start an attempt." /></span></div>}</div>
      <Link href="/student/certificates" className="button button-light"><LocaleText hinglish="अपने प्रमाणपत्र देखें →" english="View certificates →" /></Link>
      <Link href="/student/tests" className="button button-dark"><LocaleText hinglish="सभी tests और results देखें →" english="View all tests and results →" /></Link>
      <Link href="/student/learn" className="button button-light"><LocaleText hinglish="Recommended lesson खोलें →" english="Open recommended lesson →" /></Link>
    </section></main>;
}
