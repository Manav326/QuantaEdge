'use client';

import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
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
  if(error)return <main className="app-shell"><section className="progress-page"><div className="auth-card"><h1><UiText message="legacy.web.unable.to.load.progress.5f5e48c0" /></h1><p>{error}</p></div></section></main>;
  if(!data)return <main className="app-shell"><section className="progress-page"><div className="eyebrow"><UiText message="legacy.web.loading.progress.f8172f0f" /></div></section></main>;
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
  const assessmentTypeLabel = (kind:string) => kind==='TOPIC_END'?translateUi(locale, 'legacy.web.topic.end.test.8a9ba225'):kind==='CHAPTER_END'?translateUi(locale, 'legacy.web.chapter.end.test.fede453c'):translateUi(locale, 'legacy.web.admin.created.test.7c1bcdfd');
  const assessmentStatusLabel = (status:string) => status==='RELEASED'?translateUi(locale, 'legacy.web.result.released.140bcdcb'):status==='AWAITING_REVIEW'?translateUi(locale, 'legacy.web.awaiting.teacher.review.9022669f'):status==='GRADED'?translateUi(locale, 'legacy.web.graded.awaiting.release.254753e7'):status==='IN_PROGRESS'?translateUi(locale, 'legacy.web.in.progress.52fed821'):status.replaceAll('_',' ').toLowerCase();
  return <main className="app-shell">
    <section className="progress-page"><div><span className="eyebrow"><UiText message="legacy.web.your.progress.c5b2f5ab" /></span><h1><UiText message="legacy.web.see.what.you.ve.learned.at.a.glance.2e25b589" /></h1><p><UiText message="legacy.web.every.score.and.completion.figure.come.2a2142b0" /></p></div>
      <div className="progress-grid"><article><span><UiText message="legacy.web.lessons.micro.topics.ad57ed28" /></span><strong>{displayCount(lessons.completed)} / {displayCount(lessons.total)}</strong><small><UiText message="legacy.web.each.lesson.is.a.published.micro.topic.b2433996" /></small></article><article><span><UiText message="legacy.web.practice.accuracy.761c6990" /></span><strong>{displayPercent(accuracy)}</strong><small>{displayCount(safeCount(q.attempts))} <UiText message="legacy.web.responses.6ccf67eb" /> · {displayCount(safeCount(q.graded_attempts))} <UiText message="legacy.web.graded.de067772" /></small></article><article><span><UiText message="legacy.web.curriculum.6b69620f" /></span><strong>{displayPercent(lessons.percent)}</strong><small><UiText message="legacy.web.published.lessons.completed.68f842b9" /></small></article><article><span><UiText message="legacy.web.formal.tests.b493c4c1" /></span><strong>{displayCount(safeCount(assessmentStats.released))}</strong><small>{displayCount(safeCount(assessmentStats.awaiting_review))} <UiText message="legacy.web.awaiting.review.5579170f" /> · {displayPercent(safePercent(assessmentStats.average_released_percent))} <UiText message="legacy.web.average.released.score.52d7549b" /></small></article></div>
      {(certificateError||certificateNotice)&&<div className={certificateError?'feedback':'parent-notice'} role={certificateError?'alert':'status'}>{certificateError||certificateNotice}</div>}
      <div className="mastery-card"><div><span><UiText message="legacy.web.your.test.results.0b8d5440" /></span><strong>{displayCount(safeCount(assessmentStats.submitted))} <UiText message="legacy.web.submitted.367b8358" /> · {displayCount(safeCount(assessmentStats.released))} <UiText message="legacy.web.released.ffcb67dc" /></strong><small className="qe-progress-card-help"><UiText message="legacy.web.submitted.means.the.test.was.finished..456da266" /></small></div>{assessmentByType.length?assessmentByType.map((item:any)=><div className="topic-line" key={item.assessment_type}><b>{assessmentTypeLabel(item.assessment_type)}</b><span>{displayCount(safeCount(item.released))} <UiText message="legacy.web.released.7fac6ede" /> · {displayPercent(safePercent(item.average_released_percent))} <UiText message="legacy.web.average.79b0b3c2" /></span></div>):<div className="topic-line"><span><UiText message="legacy.web.no.formal.test.results.yet.642fb2cf" /></span></div>}</div>
      <div className="mastery-card"><div><span><UiText message="legacy.web.curriculum.coverage.c5d41a31" /></span><strong>{courseId!==null?(data.programName||translateUi(locale, 'legacy.web.selected.course.45273acf')):translateUi(locale, 'legacy.web.class.3f617911')+(data.class_code||'—')}</strong></div>{curriculum.length ? curriculum.map((item:any)=>{const metric=curriculumProgress(item);const label=metric.percent===null?(metric.total===0?'Not published':'—'):metric.percent+'%';return <div className="mastery-row" key={item.subject_code || item.subject_name}><span>{item.subject_name || item.subject_code || 'Subject'}</span><i><b style={{width:(metric.percent ?? 0)+'%'}}/></i><strong>{label}</strong></div>}) : <div className="topic-line"><span><UiText message="legacy.web.curriculum.coverage.is.not.available.y.8f3020d8" /></span></div>}</div>
      <div className="mastery-card">
        <div><span><UiText message="legacy.web.concept.mastery.2274fbe3" /></span><strong>{displayPercent(masteryAverage)} <UiText message="legacy.web.average.79b0b3c2" /></strong></div>
        <div className="topic-line"><div><b><UiText message="legacy.web.mastered.concepts.d20a3bd7" /></b><small><UiText message="legacy.web.80.mastery.or.higher.fdd7170e" /></small></div><span className="good">{displayCount(safeCount(mastery.mastered))}</span></div>
        <div className="topic-line"><div><b><UiText message="legacy.web.concepts.where.you.need.help.6a12b22f" /></b><small><UiText message="legacy.web.below.50.mastery.8ddc1658" /></small></div><span>{displayCount(safeCount(mastery.needs_support))}</span></div>
      </div>
      <div className="mastery-card">
        <div><span><UiText message="legacy.web.concept.mastery.af1f7901" /></span><strong><UiText message="legacy.web.lowest.mastery.first.6147147f" /></strong>
          <small className="qe-progress-card-help"><UiText message="legacy.web.only.concepts.below.80.mastery.appear..805a8f68" /></small>
        </div>
        {masteryDetails.length ? masteryDetails.map((m:any)=>{
          const subjectCode=String(m.subject_code||'').toLowerCase();
          const masteryPercent=safePercent(m.mastery_percent);
          const subjectTagClass=subjectCode==='maths'||subjectCode==='mathematics'?'qe-progress-subject-tag--maths':subjectCode==='science'?'qe-progress-subject-tag--science':'qe-progress-subject-tag--other';
          return <div className="topic-line" key={m.concept_id ?? m.concept_title}>
            <div className="qe-progress-concept-copy"><b>{m.concept_title || translateUi(locale, 'legacy.web.concept.a596c73d')}</b>
              <div className="qe-progress-concept-meta"><span className={'qe-progress-subject-tag '+subjectTagClass}>{subjectCode==='maths'||subjectCode==='mathematics'?translateUi(locale, 'legacy.web.mathematics.4cf6be1a'):subjectCode==='science'?translateUi(locale, 'legacy.web.science.f85a10aa'):(m.subject_name||translateUi(locale, 'legacy.web.subject.0af41287'))}</span><small>{m.chapter_name || '—'}</small></div>
            </div>
            <span className={masteryPercent!==null&&masteryPercent>=50?'good':''}>{displayPercent(masteryPercent)} · {displayCount(safeCount(m.attempts))} <UiText message="legacy.web.responses.recorded.bc733c11" /></span>
          </div>;
        }) : <div className="topic-line"><span><LocaleText hinglish={Number(mastery.concepts||0)>0?"आपके दर्ज किए गए सभी concepts की mastery कम-से-कम 80% है।":"अभी concept results दर्ज नहीं हैं।"} english={Number(mastery.concepts||0)>0?"All recorded concepts have at least 80% mastery.":"No concept results have been recorded yet."} /></span></div>}
      </div>
      <div className="mastery-card"><div><span><UiText message="legacy.web.recent.tests.fc0cb5e1" /></span><strong><UiText message="legacy.web.every.attempt.and.result.is.preserved.c403e137" /></strong></div>{recentAssessments.length?recentAssessments.map((item:any)=>{
        const certificate=certificates.find((record:any)=>Number(record.assessment_attempt_id)===Number(item.attempt_id));
        const eligibility=certificateEligibility[Number(item.attempt_id)];
        return <div className="topic-line student-test-line" key={item.attempt_id}>
          <div><b>{item.assessment_title || assessmentTypeLabel(item.assessment_type)}</b><small>{assessmentTypeLabel(item.assessment_type)} · {item.subject_name || '—'}{item.chapter_name?' · '+item.chapter_name:''}</small></div>
          <div className="student-test-result">
            <span className={item.status==='RELEASED'?'good':''}>{item.status==='RELEASED'?String(item.final_score??'—')+' / '+String(item.max_score??'—'):assessmentStatusLabel(item.status)}</span>
            {certificate?.status==='ISSUED'?<div className="student-certificate-actions"><Link href={'/student/certificates?certificateId='+certificate.id}><UiText message="legacy.web.view.certificate.07663aac" /></Link></div>:certificate?.status==='REVOKED'?<small className="parent-certificate-revoked"><UiText message="legacy.web.certificate.revoked.29f7fafd" /></small>:eligibility?.eligible?(
              <button type="button" className="student-cert-issue" disabled={issuingAttemptId!==null} onClick={()=>void issueResultCertificate(Number(item.attempt_id))}>
                {issuingAttemptId===Number(item.attempt_id)?translateUi(locale, 'legacy.web.issuing.1efee91b'):translateUi(locale, 'legacy.web.issue.certificate.2e69c02c')}
              </button>
            ):item.status==='RELEASED'&&eligibility?.eligibility_reason!=='ADMIN_CREATED_TEST_ONLY'?<small className="student-cert-hint">{({
              SUBJECT_COMPLETION_REQUIRED:translateUi(locale, 'legacy.web.complete.all.published.lessons.in.this.8b607e36'),
              PASS_THRESHOLD_NOT_MET:translateUi(locale, 'legacy.web.the.released.score.is.below.the.certif.7255a5d5'),
              PUBLISHED_LESSONS_NOT_AVAILABLE:translateUi(locale, 'legacy.web.published.lessons.are.not.available.fo.815aa65c'),
              SUBJECT_TRACK_NOT_ACTIVE:translateUi(locale, 'legacy.web.an.active.subject.enrollment.is.requir.89911d63'),
              CERTIFICATE_REVOKED:translateUi(locale, 'legacy.web.this.certificate.has.been.revoked.6ccdb9f1')            } as Record<string,string>)[String(eligibility?.eligibility_reason)]||translateUi(locale, 'legacy.web.certificate.eligibility.could.not.be.c.4abc65d8')}</small>:null}
          </div>
        </div>;
      }):<div className="topic-line"><span><UiText message="legacy.web.your.test.status.and.result.will.appea.0798ef2f" /></span></div>}</div>
      <Link href="/student/certificates" className="button button-light"><UiText message="legacy.web.view.certificates.fec657d5" /></Link>
      <Link href={'/student/tests'+(courseId!==null?'?courseId='+courseId:'')} className="button button-dark"><UiText message="legacy.web.view.all.tests.and.results.f924b495" /></Link>
      <Link href={courseId!==null?'/student/learn?courseId='+courseId+'&subjectCode='+encodeURIComponent(String(curriculum[0]?.subject_code||'maths')):'/student/learn'} className="button button-light"><UiText message="legacy.web.open.recommended.lesson.e9600941" /></Link>
    </section></main>;
}
