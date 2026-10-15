'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';

import Link from 'next/link';
import {useCallback,useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';
import { curriculumProgress, displayCount, displayPercent, lessonProgress, safeCount, safePercent } from '../progressMetrics';

export default function ProgressPage(){
  const router=useRouter();
  const {locale}=useLocale();
  const t=(english:string,hindi:string)=>locale==='english'?english:hindi;
  const [data,setData]=useState<any>(null); const [error,setError]=useState('');
  const [courseId,setCourseId]=useState<number|null>(null);
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
        body:JSON.stringify({achievementType:'ASSESSMENT_PASS',assessmentAttemptId:attemptId,templateId:'CLASSIC',language:String(data?.language||'hi').toLowerCase().startsWith('en')?'en':'hi',...(courseId!==null?{courseId}:{})})
      });
      const body=await response.json().catch(()=>({}));
      if(!response.ok)throw new Error(body.message||'Certificate could not be issued.');
      setCertificateNotice('Certificate issued. View or download it from this result.');
      await refreshCertificateRecords(data);
    }catch(e:any){setCertificateError(e?.message||'Certificate could not be issued.');}
    finally{setIssuingAttemptId(null);}
  }

  useEffect(()=>{(async()=>{
    const query=new URLSearchParams(window.location.search);
    const rawCourseId=query.get('courseId')||'';
    const rawProgramCode=query.get('programCode')||'';
    const scopedCourseId=/^\d+$/.test(rawCourseId)&&Number(rawCourseId)>0?Number(rawCourseId):null;
    setCourseId(scopedCourseId);
    const scopeQuery=scopedCourseId!==null?'?courseId='+scopedCourseId:rawProgramCode?'?programCode='+encodeURIComponent(rawProgramCode):'';
    const r=await fetch('/api/v1/students/me'+scopeQuery,{cache:'no-store',credentials:'same-origin'});
    if(r.status===401||r.status===403){router.replace('/login/student');return}
    const b=await r.json();if(!r.ok){setError(b.message||'Unable to load progress');return}
    if(scopedCourseId===null&&b.programId)setCourseId(Number(b.programId));
    setData(b);await refreshCertificateRecords(b)
  })()},[router,refreshCertificateRecords]);
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
  const assessmentTypeLabel = (kind:string) => kind==='TOPIC_END'?t('Topic-end test','टॉपिक-एंड टेस्ट'):kind==='CHAPTER_END'?t('Chapter-end test','अध्याय-एंड टेस्ट'):t('Admin-created test','एडमिन द्वारा बनाया गया टेस्ट');
  const assessmentStatusLabel = (status:string) => status==='RELEASED'?t('Result released','परिणाम जारी'):status==='AWAITING_REVIEW'?t('Awaiting teacher review','शिक्षक की जाँच बाकी'):status==='GRADED'?t('Graded · awaiting release','जाँचा गया · जारी होना बाकी'):status==='IN_PROGRESS'?t('In progress','जारी'):status.replaceAll('_',' ').toLowerCase();
  return <main className="app-shell">
    <section className="progress-page"><div><span className="eyebrow"><LocaleText hinglish="आपकी progress" english="Your progress" /></span><h1><LocaleText hinglish="जो सीखा, वह साफ़ दिख रहा है।" english="See what you’ve learned at a glance." /></h1><p><LocaleText hinglish="हर score और completion आपके actual learning records से आता है।" english="Every score and completion figure comes from your actual learning records." /></p></div>
      <div className="progress-grid"><article><span><LocaleText hinglish="पाठ (micro-topics)" english="Lessons (micro-topics)" /></span><strong>{displayCount(lessons.completed)} / {displayCount(lessons.total)}</strong><small><LocaleText hinglish="हर पाठ एक प्रकाशित micro-topic है, पूरा अध्याय नहीं।" english="Each lesson is a published micro-topic, not a whole chapter." /></small></article><article><span><LocaleText hinglish="अभ्यास की सटीकता" english="Practice accuracy" /></span><strong>{displayPercent(accuracy)}</strong><small>{displayCount(safeCount(q.attempts))} <LocaleText hinglish="उत्तर प्रयास" english="responses" /> · {displayCount(safeCount(q.graded_attempts))} <LocaleText hinglish="जाँचे गए" english="graded" /></small></article><article><span><LocaleText hinglish="Curriculum" english="Curriculum" /></span><strong>{displayPercent(lessons.percent)}</strong><small><LocaleText hinglish="प्रकाशित पाठ पूरे" english="published lessons completed" /></small></article><article><span><LocaleText hinglish="Formal tests" english="Formal tests" /></span><strong>{displayCount(safeCount(assessmentStats.released))}</strong><small>{displayCount(safeCount(assessmentStats.awaiting_review))} <LocaleText hinglish="जाँच बाकी" english="awaiting review" /> · {displayPercent(safePercent(assessmentStats.average_released_percent))} <LocaleText hinglish="जारी परिणामों का औसत" english="average released score" /></small></article></div>
      {(certificateError||certificateNotice)&&<div className={certificateError?'feedback':'parent-notice'} role={certificateError?'alert':'status'}>{certificateError||certificateNotice}</div>}
      <div className="mastery-card"><div><span><LocaleText hinglish="आपके test results" english="Your test results" /></span><strong>{displayCount(safeCount(assessmentStats.submitted))} <LocaleText hinglish="जमा" english="submitted" /> · {displayCount(safeCount(assessmentStats.released))} <LocaleText hinglish="परिणाम जारी" english="released" /></strong><small className="qe-progress-card-help"><LocaleText hinglish="जमा = टेस्ट पूरा करके भेजा गया; जारी = अंतिम परिणाम देखने के लिए उपलब्ध है।" english="Submitted means the test was finished and sent; released means the final result is available to view." /></small></div>{assessmentByType.length?assessmentByType.map((item:any)=><div className="topic-line" key={item.assessment_type}><b>{assessmentTypeLabel(item.assessment_type)}</b><span>{displayCount(safeCount(item.released))} <LocaleText hinglish="जारी" english="released" /> · {displayPercent(safePercent(item.average_released_percent))} <LocaleText hinglish="औसत" english="average" /></span></div>):<div className="topic-line"><span><LocaleText hinglish="अभी कोई formal test result नहीं है।" english="No formal test results yet." /></span></div>}</div>
      <div className="mastery-card"><div><span><LocaleText hinglish="Curriculum coverage" english="Curriculum coverage" /></span><strong>{courseId!==null?(data.programName||t('Selected course','चुना गया course')):t('Class ','कक्षा ')+(data.class_code||'—')}</strong></div>{curriculum.length ? curriculum.map((item:any)=>{const metric=curriculumProgress(item);const label=metric.percent===null?(metric.total===0?'Not published':'—'):metric.percent+'%';return <div className="mastery-row" key={item.subject_code || item.subject_name}><span>{item.subject_name || item.subject_code || 'Subject'}</span><i><b style={{width:(metric.percent ?? 0)+'%'}}/></i><strong>{label}</strong></div>}) : <div className="topic-line"><span><LocaleText hinglish="Curriculum coverage अभी उपलब्ध नहीं है।" english="Curriculum coverage is not available yet." /></span></div>}</div>
      <div className="mastery-card">
        <div><span><LocaleText hinglish="अवधारणा प्रगति" english="Concept mastery" /></span><strong>{displayPercent(masteryAverage)} <LocaleText hinglish="औसत" english="average" /></strong></div>
        <div className="topic-line"><div><b><LocaleText hinglish="महारत हासिल concepts" english="Mastered concepts" /></b><small><LocaleText hinglish="80% या अधिक mastery" english="80% mastery or higher" /></small></div><span className="good">{displayCount(safeCount(mastery.mastered))}</span></div>
        <div className="topic-line"><div><b><LocaleText hinglish="जिन concepts में मदद चाहिए" english="Concepts where you need help" /></b><small><LocaleText hinglish="50% से कम mastery" english="Below 50% mastery" /></small></div><span>{displayCount(safeCount(mastery.needs_support))}</span></div>
      </div>
      <div className="mastery-card">
        <div><span><LocaleText hinglish="Concept mastery" english="Concept mastery" /></span><strong><LocaleText hinglish="कम mastery वाले concepts पहले" english="Lowest mastery first" /></strong>
          <small className="qe-progress-card-help"><LocaleText hinglish="यहाँ 80% से कम mastery वाले concepts दिखते हैं। दर्ज जवाबों में दोबारा किए गए प्रयास भी शामिल हैं; mastery हर प्रश्न के सबसे हाल के जाँचे जवाब से निकलती है।" english="Only concepts below 80% mastery appear here. Recorded responses include retries; mastery is calculated from the latest graded response for each question." /></small>
        </div>
        {masteryDetails.length ? masteryDetails.map((m:any)=>{
          const subjectCode=String(m.subject_code||'').toLowerCase();
          const masteryPercent=safePercent(m.mastery_percent);
          const subjectTagClass=subjectCode==='maths'||subjectCode==='mathematics'?'qe-progress-subject-tag--maths':subjectCode==='science'?'qe-progress-subject-tag--science':'qe-progress-subject-tag--other';
          return <div className="topic-line" key={m.concept_id ?? m.concept_title}>
            <div className="qe-progress-concept-copy"><b>{m.concept_title || t('Concept','अवधारणा')}</b>
              <div className="qe-progress-concept-meta"><span className={'qe-progress-subject-tag '+subjectTagClass}>{subjectCode==='maths'||subjectCode==='mathematics'?t('Mathematics','गणित'):subjectCode==='science'?t('Science','विज्ञान'):(m.subject_name||t('Subject','विषय'))}</span><small>{m.chapter_name || '—'}</small></div>
            </div>
            <span className={masteryPercent!==null&&masteryPercent>=50?'good':''}>{displayPercent(masteryPercent)} · {displayCount(safeCount(m.attempts))} <LocaleText hinglish="जवाब दर्ज" english="responses recorded" /></span>
          </div>;
        }) : <div className="topic-line"><span><LocaleText hinglish={Number(mastery.concepts||0)>0?"आपके दर्ज किए गए सभी concepts की mastery कम-से-कम 80% है।":"अभी concept results दर्ज नहीं हैं।"} english={Number(mastery.concepts||0)>0?"All recorded concepts have at least 80% mastery.":"No concept results have been recorded yet."} /></span></div>}
      </div>
      <div className="mastery-card"><div><span><LocaleText hinglish="Recent tests" english="Recent tests" /></span><strong><LocaleText hinglish="हर attempt और result सुरक्षित रहता है" english="Every attempt and result is preserved" /></strong></div>{recentAssessments.length?recentAssessments.map((item:any)=>{
        const certificate=certificates.find((record:any)=>Number(record.assessment_attempt_id)===Number(item.attempt_id));
        const eligibility=certificateEligibility[Number(item.attempt_id)];
        return <div className="topic-line student-test-line" key={item.attempt_id}>
          <div><b>{item.assessment_title || assessmentTypeLabel(item.assessment_type)}</b><small>{assessmentTypeLabel(item.assessment_type)} · {item.subject_name || '—'}{item.chapter_name?' · '+item.chapter_name:''}</small></div>
          <div className="student-test-result">
            <span className={item.status==='RELEASED'?'good':''}>{item.status==='RELEASED'?String(item.final_score??'—')+' / '+String(item.max_score??'—'):assessmentStatusLabel(item.status)}</span>
            {certificate?.status==='ISSUED'?<div className="student-certificate-actions"><Link href={'/student/certificates?certificateId='+certificate.id}><LocaleText hinglish="प्रमाणपत्र देखें ↗" english="View certificate ↗" /></Link></div>:certificate?.status==='REVOKED'?<small className="parent-certificate-revoked"><LocaleText hinglish="प्रमाणपत्र निरस्त" english="Certificate revoked" /></small>:eligibility?.eligible?(
              <button type="button" className="student-cert-issue" disabled={issuingAttemptId!==null} onClick={()=>void issueResultCertificate(Number(item.attempt_id))}>
                {issuingAttemptId===Number(item.attempt_id)?t('Issuing…','जारी हो रहा है…'):t('Issue certificate','प्रमाणपत्र जारी करें')}
              </button>
            ):item.status==='RELEASED'&&eligibility?.eligibility_reason!=='ADMIN_CREATED_TEST_ONLY'?<small className="student-cert-hint">{({
              SUBJECT_COMPLETION_REQUIRED:t('Complete all published lessons in this subject to unlock the certificate.','प्रमाणपत्र पाने के लिए इस विषय के सभी प्रकाशित पाठ पूरे करें।'),
              PASS_THRESHOLD_NOT_MET:t('The released score is below the certificate threshold.','जारी स्कोर प्रमाणपत्र की निर्धारित सीमा से कम है।'),
              PUBLISHED_LESSONS_NOT_AVAILABLE:t('Published lessons are not available for this subject yet.','इस विषय के प्रकाशित पाठ अभी उपलब्ध नहीं हैं।'),
              SUBJECT_TRACK_NOT_ACTIVE:t('An active subject enrollment is required.','इस विषय में सक्रिय नामांकन आवश्यक है।'),
              CERTIFICATE_REVOKED:t('This certificate has been revoked.','यह प्रमाणपत्र निरस्त कर दिया गया है।')            } as Record<string,string>)[String(eligibility?.eligibility_reason)]||t('Certificate eligibility could not be checked; open results to retry.','प्रमाणपत्र की पात्रता जाँची नहीं जा सकी; फिर जाँचने के लिए परिणाम खोलें।')}</small>:null}
          </div>
        </div>;
      }):<div className="topic-line"><span><LocaleText hinglish="Test attempt शुरू करने पर उसका status और result यहाँ दिखेगा।" english="Your test status and result will appear here after you start an attempt." /></span></div>}</div>
      <Link href="/student/certificates" className="button button-light"><LocaleText hinglish="अपने प्रमाणपत्र देखें →" english="View certificates →" /></Link>
      <Link href={'/student/tests'+(courseId!==null?'?courseId='+courseId:'')} className="button button-dark"><LocaleText hinglish="सभी tests और results देखें →" english="View all tests and results →" /></Link>
      <Link href={courseId!==null?'/student/learn?courseId='+courseId+'&subjectCode='+encodeURIComponent(String(curriculum[0]?.subject_code||'maths')):'/student/learn'} className="button button-light"><LocaleText hinglish="Recommended lesson खोलें →" english="Open recommended lesson →" /></Link>
    </section></main>;
}
