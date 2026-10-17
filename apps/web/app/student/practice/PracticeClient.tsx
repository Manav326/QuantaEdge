'use client';

import { translateUi } from '../../i18n/catalog';
import { UiText } from '../../i18n/useUiTranslation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';
import MathText from '../../components/MathText';
import AnswerFeedback from '../../components/AnswerFeedback';
import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';

type Question={
  id:number;lesson_id?:number;question_type:string;prompt:string;explanation:string;options:string;
  source_kind?:string;source_year?:number;board?:string;marks?:number;exam_format?:string;
  topic?:string;subtopic?:string;skill?:string;response_mode?:string;answered?:boolean;attemptCount?:number;lastCorrect?:boolean|null;translated?:boolean
};
type LessonBlock={id:number;sequence_no:number;block_type:string;content:string};
type LessonDetail={
  id:number;title:string;summary?:string;class_code?:string;chapter_name?:string;
  subject_name?:string;subject_code?:string;objective_title?:string;blocks:LessonBlock[];
};
type ApiBody={detail?:string;message?:string;sessionId?:number;status?:string;questions?:Question[];selectedCount?:number;translationComplete?:boolean;language?:string};

function parse<T=any>(v:string):T{try{return JSON.parse(v) as T}catch{return [] as T}}
async function readBody(response:Response):Promise<any>{
  const value=await response.text();
  if(!value)return {};
  try{return JSON.parse(value)}catch{return {message:value}}
}
function recapBlocks(lesson:LessonDetail|null){
  const candidates=(lesson?.blocks||[])
    .filter(block=>block.block_type==='SUMMARY'||block.block_type==='RECAP')
    .filter(block=>{
      const content=parse<any>(block.content||'{}');
      return Array.isArray(content?.points)&&content.points.some((point:unknown)=>typeof point==='string'&&point.trim());
    });
  // Some imported lessons contain the same recap twice: once as SUMMARY and again
  // as RECAP. Prefer the explicit RECAP block and render identical point lists once.
  const byContent=new Map<string,LessonBlock>();
  for(const block of candidates){
    const content=parse<any>(block.content||'{}');
    const points=(content.points as string[])
      .map(point=>point.trim().replace(/\\s+/g,' ').toLocaleLowerCase());
    const key=JSON.stringify(points);
    const previous=byContent.get(key);
    if(!previous || (block.block_type==='RECAP'&&previous.block_type!=='RECAP')){
      byContent.set(key,block);
    }
  }
  return Array.from(byContent.values()).sort((a,b)=>a.sequence_no-b.sequence_no);
}

export default function PracticePage(){
  const [questions,setQuestions]=useState<Question[]>([]);
  const [lesson,setLesson]=useState<LessonDetail|null>(null);
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [sessionStatus,setSessionStatus]=useState('IN_PROGRESS');
  const [translationComplete,setTranslationComplete]=useState(false);
  const [index,setIndex]=useState(0);
  const [selected,setSelected]=useState('');
  const [result,setResult]=useState<any>(null);
  const [scoreByQuestion,setScoreByQuestion]=useState<Record<number,any>>({});
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [loading,setLoading]=useState(true);
  const [saving,setSaving]=useState(false);
  const [noPractice,setNoPractice]=useState(false);
  const [answerError,setAnswerError]=useState('');
  const [attemptReset,setAttemptReset]=useState(0);
  const router=useRouter();
  const searchParams=useSearchParams();
  const {locale, learningLanguage}=useLocale();
  const tx=(hinglish:string,english:string)=>locale==='english'?english:hinglish;
  const lessonIdRaw=searchParams.get('lessonId')||'';
  const sessionIdRaw=searchParams.get('sessionId')||'';
  const rawCourseId=searchParams.get('courseId')||'';
  const courseId=/^\d+$/.test(rawCourseId)&&Number(rawCourseId)>0?Number(rawCourseId):null;
  const programCode=searchParams.get('programCode');
  const courseQuery=courseId!==null?'&courseId='+courseId:programCode?'&programCode='+encodeURIComponent(programCode):'';
  const courseStartQuery=courseId!==null?'?courseId='+courseId:programCode?'?programCode='+encodeURIComponent(programCode):'';
  const lessonId=/^\d+$/.test(lessonIdRaw)?Number(lessonIdRaw):0;
  const requestedSessionId=/^\d+$/.test(sessionIdRaw)?Number(sessionIdRaw):0;

  const current=questions[index];
  const options=current?parse<{key:string;label:string}[]>(current.options||'[]'):[];
  const correctCount=Object.values(scoreByQuestion).filter(value=>value?.correct===true).length;
  const gradedCount=Object.values(scoreByQuestion).filter(value=>value?.autoGraded&&typeof value?.correct==='boolean').length;
  const recap=useMemo(()=>recapBlocks(lesson),[lesson]);

  useEffect(()=>{
    let active=true;
    async function load(){
      setLoading(true);setError('');setNoPractice(false);setAnswerError('');
      if(!lessonId&&!requestedSessionId){
        setLoading(false);
        return;
      }
      try{
        const me=await fetch('/api/v1/students/me',{cache:'no-store'});
        if(me.status===401||me.status===403){router.replace('/login/student');return;}
        const studentBody=await readBody(me);
        if(!me.ok)throw new Error(studentBody.detail||studentBody.message||translateUi(locale, 'legacy.web.student.profile.was.not.found.f6a78e13'));
        if(!active)return;

        let lessonBody:LessonDetail|null=null;
        if(lessonId){
          const language=learningLanguage;
          const lessonResponse=await fetch('/api/v1/learning/lessons/'+lessonId+'?language='+language+courseQuery,{cache:'no-store'});
          const body=await readBody(lessonResponse);
          if(!lessonResponse.ok)throw new Error(body.detail||body.message||translateUi(locale, 'legacy.web.this.lesson.is.unavailable.ae825be0'));
          lessonBody=body as LessonDetail;
          if(active)setLesson(lessonBody);
        }

        if(requestedSessionId){
          const sessionResponse=await fetch('/api/v1/learning/practice/sessions/'+requestedSessionId+'?language='+learningLanguage,{cache:'no-store'});
          const body=await readBody(sessionResponse);
          if(!sessionResponse.ok)throw new Error(body.detail||body.message||translateUi(locale, 'legacy.web.could.not.reopen.this.practice.session.ef2cc8c0'));
          if(!active)return;
          const resumed:Array<Question>=Array.isArray(body.questions)?body.questions:[];
          setSessionId(Number(body.sessionId));
          setSessionStatus(String(body.status||'IN_PROGRESS'));
          setTranslationComplete(typeof body.translationComplete==='boolean'
            ? body.translationComplete : resumed.length>0&&resumed.every(q=>q.translated===true));
          setQuestions(resumed);
          const firstUnanswered=resumed.findIndex(q=>!q.answered);
          setIndex(firstUnanswered<0?resumed.length:firstUnanswered);
          const restoredScores:Record<number,any>={};
          resumed.forEach(q=>{if(typeof q.lastCorrect==='boolean')restoredScores[q.id]={correct:q.lastCorrect,autoGraded:true};});
          setScoreByQuestion(restoredScores);
          setSelected('');setResult(null);
          if(firstUnanswered<0&&resumed.length)setSessionStatus('COMPLETED');
          else if(firstUnanswered>=0&&resumed[firstUnanswered].attemptCount&&resumed[firstUnanswered].lastCorrect===false){
            setResult({correct:false,autoGraded:true,explanation:resumed[firstUnanswered].explanation});
          }
          setNotice(translateUi(locale, 'legacy.web.your.saved.practice.session.has.been.r.b819f911'));
          return;
        }

        if(!lessonId)throw new Error(translateUi(locale, 'legacy.web.open.a.lesson.before.starting.practice.41e2102e'));
        const startResponse=await fetch('/api/v1/learning/practice/sessions/from-lesson',{
          method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({lessonId,language:learningLanguage})
        });
        const startBody:ApiBody=await readBody(startResponse);
        if(startResponse.status===409){
          if(active){
            setNoPractice(true);
            setNotice(translateUi(locale, 'legacy.web.additional.approved.practice.questions.8038d774'));
          }
          return;
        }
        if(!startResponse.ok)throw new Error(startBody.detail||startBody.message||translateUi(locale, 'legacy.web.could.not.prepare.practice.for.this.to.3eb12ea1'));
        if(!active)return;
        const loaded:Array<Question>=Array.isArray(startBody.questions)?startBody.questions:[];
        if(!loaded.length){
          setNoPractice(true);
          setNotice(translateUi(locale, 'legacy.web.no.practice.questions.are.currently.av.fce92908'));
          return;
        }
        setSessionId(Number(startBody.sessionId));
        setSessionStatus(String(startBody.status||'IN_PROGRESS'));
        setTranslationComplete(typeof startBody.translationComplete==='boolean'
          ? startBody.translationComplete : loaded.length>0&&loaded.every(q=>q.translated===true));
        setQuestions(loaded);setIndex(0);setSelected('');setResult(null);setScoreByQuestion({});
        setNotice(String(startBody.message||translateUi(locale, 'legacy.web.practice.for.this.lesson.is.ready.4345bea9')));
        const url=new URL(window.location.href);
        url.searchParams.set('sessionId',String(startBody.sessionId));
        window.history.replaceState({},'',url.toString());
      }catch(e:any){
        if(active)setError(e.message||translateUi(locale, 'legacy.web.unable.to.load.practice.ea5f9da6'));
      }finally{
        if(active)setLoading(false);
      }
    }
    void load();
    return()=>{active=false;};
  },[lessonId,requestedSessionId,router,learningLanguage]);

  async function answer(value:string|object){
    if(!current||!sessionId||saving||result?.correct===true)return;
    const serialized=typeof value==='string'?value:JSON.stringify(value);
    if(!serialized.trim())return;
    setSaving(true);setAnswerError('');setError('');setSelected(serialized);setResult(null);
    try{
      const response=await fetch('/api/v1/learning/questions/'+current.id+'/answer?language='+learningLanguage,{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({answer:value,practiceSessionId:sessionId})
      });
      const body=await readBody(response);
      if(!response.ok)throw new Error(body.detail||body.message||translateUi(locale, 'legacy.web.could.not.save.your.answer.de3eeeab'));
      setResult(body);
      setAttemptReset(old=>old+1);
      setScoreByQuestion(old=>({...old,[current.id]:body}));
      const sessionResponse=await fetch('/api/v1/learning/practice/sessions/'+sessionId+'?language='+learningLanguage,{cache:'no-store'});
      if(sessionResponse.ok){
        const sessionBody=await readBody(sessionResponse);
        setSessionStatus(String(sessionBody.status||'IN_PROGRESS'));
        if(typeof sessionBody.translationComplete==='boolean')setTranslationComplete(sessionBody.translationComplete);
        if(Array.isArray(sessionBody.questions))setQuestions(sessionBody.questions);
      }
    }catch(e:any){
      setAnswerError(e.message||translateUi(locale, 'legacy.web.could.not.save.your.answer.please.try..31956e70'));
    }finally{
      setSaving(false);
    }
  }

  function tryAgain(){
    if(result?.correct!==false)return;
    setSelected('');setResult(null);setAnswerError('');setNotice('');
  }

  function next(){
    if(!result||result.correct===false)return;
    setSelected('');setResult(null);setAnswerError('');
    if(index+1<questions.length)setIndex(index+1);
    else setIndex(questions.length);
  }

  if(loading)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><UiText message="legacy.web.preparing.practice.for.your.topic.920049af" /></div></section></main>;

  if(!lessonId&&!requestedSessionId)return <main className="practice-page">
    <section className="practice-wrap">
      <div className="eyebrow"><UiText message="legacy.web.learn.practise.bb602a9d" /></div>
      <h1><UiText message="legacy.web.start.with.a.lesson.936fa9e6" /></h1>
      <p><UiText message="legacy.web.after.the.lesson.mcqs.you.will.go.stra.c270ef80" /></p>
      <div className="concept-card"><Link href="/student/learn" className="button button-dark"><UiText message="legacy.web.open.your.lessons.6e9fe782" /></Link></div>
    </section>
  </main>;

  if(error)return <main className="practice-page">
    <section className="practice-wrap"><div className="practice-feedback practice-feedback-incorrect" role="alert"><strong><UiText message="legacy.web.practice.could.not.be.opened.5bc74c45" /></strong><span>{error}</span></div><Link href={lesson?'/student/learn?lessonId='+lesson.id+courseQuery:'/student/learn'+courseStartQuery} className="button button-dark" style={{marginTop:20}}><UiText message="legacy.web.return.to.lesson.5e757610" /></Link></section>
  </main>;

  if(noPractice)return <main className="practice-page">
    <section className="practice-wrap">
      <div className="eyebrow">{lesson?.subject_name||'QuantaEdge'} · {lesson?.chapter_name||translateUi(locale, 'legacy.web.chapter.0a3bff81')}</div>
      <h1><UiText message="legacy.web.practice.set.is.not.available.yet.d6b87368" /></h1>
      <p>{notice}</p>
      <div className="concept-card"><h2>{lesson?.title||translateUi(locale, 'legacy.web.this.micro.topic.0b4594d6')}</h2><p><UiText message="legacy.web.instead.of.showing.questions.from.an.u.bfb8bb28" /></p></div>
      {recap.map(block=>{
        const data=parse<any>(block.content||'{}');
        return <div className="concept-card" key={block.id}><span className="concept-kicker"><UiText message="legacy.web.recap.d07fa8cc" /></span>{data.points?.map((point:string,i:number)=><div className="feedback" key={i}><span>✓ <MathText text={point}/></span></div>)}</div>;
      })}
      <Link href={lesson?'/student/learn?subjectCode='+lesson.subject_code+courseQuery:'/student/learn'+courseStartQuery} className="button button-dark" style={{marginTop:22}}><UiText message="legacy.web.choose.another.lesson.dd9afbbb" /></Link>
    </section>
  </main>;

  // Keep the final answer's feedback visible until the learner advances to the summary.
  // A server-completed session alone must not unmount the current question immediately.
  if(index>=questions.length)return <main className="practice-page">
    <section className="practice-wrap">
      <div className="eyebrow"><UiText message="legacy.web.micro.topic.complete.1e23cb76" /></div>
      <h1><UiText message="legacy.web.well.done.practice.complete.1efc8d72" /></h1>
      <p>{lesson?.title||translateUi(locale, 'legacy.web.your.practice.session.8315f27d')} · {questions.length} <UiText message="legacy.web.questions.60fa1a6d" /></p>
      <div className="concept-card"><h2><UiText message="legacy.web.session.result.db276d70" /></h2>{gradedCount>0?<p>{correctCount} / {gradedCount} <UiText message="legacy.web.questions.have.a.correct.latest.graded.9c32685f" /></p>:<p><UiText message="legacy.web.your.submitted.answers.have.been.saved.fa0511e8" /></p>}<small><UiText message="legacy.web.your.answers.have.been.saved.mastery.i.0856cb2c" /></small></div>
      {recap.map(block=>{
        const data=parse<any>(block.content||'{}');
        return <div className="concept-card" key={block.id}><span className="concept-kicker"><UiText message="legacy.web.recap.da5d2bd3" /></span>{data.points?.map((point:string,i:number)=><div className="feedback" key={i}><span>✓ <MathText text={point}/></span></div>)}</div>;
      })}
      <div className="practice-footer"><Link href={lesson?'/student/learn?subjectCode='+lesson.subject_code+courseQuery:'/student/learn'+courseStartQuery} className="text-link"><UiText message="legacy.web.chapter.lessons.13ec05a5" /></Link><Link href={'/student/progress'+(courseId!==null?'?courseId='+courseId:programCode?'?programCode='+encodeURIComponent(programCode):'')} className="button button-dark"><UiText message="legacy.web.view.progress.bce928bc" /></Link></div>
    </section>
  </main>;

  const questionTypeLabel=current.question_type==='MCQ'?translateUi(locale, 'legacy.web.multiple.choice.649280af'):current.question_type==='TRUE_FALSE'?translateUi(locale, 'legacy.web.true.or.false.43e5661e'):current.question_type==='NUMERICAL'?translateUi(locale, 'legacy.web.numerical.question.3993dcf1'):current.question_type.replaceAll('_',' ').toLowerCase();
  const progressPercent=Math.round(((index+1)/Math.max(questions.length,1))*100);
  const selectedOptionIsWrong=result?.correct===false;
  const selectedOptionIsRight=result?.correct===true;

  return <main className="practice-page qe-practice-page">
    <section className="practice-wrap qe-practice-wrap">
      <div className="qe-practice-intro">
        <div className="qe-practice-intro__eyebrow"><span className="qe-practice-live-dot"/><UiText message="legacy.web.personalised.to.your.lesson.f5a1e6a2" /></div>
        <h1><UiText message="legacy.web.now.put.it.into.practice.e1b65d9b" /></h1>
        <p><UiText message="legacy.web.each.question.helps.deepen.your.unders.849160c7" /></p>
      </div>

      <div className="qe-practice-progress" aria-label={translateUi(locale, 'legacy.web.practice.progress.b8450593')}>
        <div className="qe-practice-progress__meta"><span><UiText message="legacy.web.question.progress.19dd916c" /></span><strong>{Math.min(index+1,questions.length)} <i>/</i> {questions.length}</strong></div>
        <div className="qe-practice-progress__track"><span style={{width:progressPercent+'%'}}/></div>
      </div>

      {!translationComplete&&<div className="qe-practice-notice" role="status"><span>文</span><p><UiText message="legacy.web.language.coverage.some.practice.questi.8b656cca" /></p></div>}
      {notice&&<div className="qe-practice-notice" role="status"><span>✦</span><p>{notice}</p></div>}
      {error&&<div className="qe-answer-feedback is-incorrect" role="alert"><span className="qe-answer-feedback__icon">!</span><div><strong><UiText message="legacy.web.something.went.wrong.6daea259" /></strong><p>{error}</p></div></div>}

      <article className={'qe-question-card qe-question-card--practice '+(result?.correct===false?'has-answer-incorrect':result?.correct===true?'has-answer-correct':'')}>
        <div className="qe-question-card__top">
          <span className="qe-question-index"><span>{String(index+1).padStart(2,'0')}</span></span>
          <div className="qe-question-card__labels">
            <span className="qe-question-type">{questionTypeLabel}</span>
            <span className="qe-question-context">{current.exam_format||translateUi(locale, 'legacy.web.topic.practice.3f8b0ab5')}{current.marks?' · '+current.marks+' '+translateUi(locale, 'legacy.web.marks.57368a8d'):''}</span>
          </div>
          <span className="qe-question-source">{current.source_kind==='TEXTBOOK_ALIGNED'?<UiText message="legacy.web.textbook.aligned.0545ccf1" />:current.source_kind?<MathText text={current.source_kind.replaceAll('_',' ').toLowerCase()}/>:<UiText message="legacy.web.quantaedge.question.57836f8f" />}</span>
        </div>

        <div className="qe-question-card__prompt"><MathText text={current.prompt}/></div>
        <div className="qe-practice-hint"><span>✦</span><p><UiText message="legacy.web.think.it.through.first.then.answer.and.76c4914f" /></p></div>

        {options.length>0?<div className="qe-answer-options" role="group" aria-label={translateUi(locale, 'legacy.web.answer.choices.436a84eb')}>
          {options.map((option,i)=>{
            const chosen=selected===option.key;
            const stateClass=chosen&&selectedOptionIsWrong?'is-incorrect':chosen&&selectedOptionIsRight?'is-correct':chosen?'is-selected':'';
            return <button type="button" key={option.key} disabled={saving||selectedOptionIsRight} aria-pressed={chosen} className={'qe-answer-option '+stateClass} onClick={()=>void answer(option.key)}>
              <span className="qe-answer-option__key">{option.key||String.fromCharCode(65+i)}</span>
              <span className="qe-answer-option__text"><MathText text={option.label}/></span>
              {chosen&&selectedOptionIsRight&&<span className="qe-answer-option__status" aria-label={translateUi(locale, 'legacy.web.correct.d01db0a2')}>✓</span>}
              {chosen&&selectedOptionIsWrong&&<span className="qe-answer-option__status" aria-label={translateUi(locale, 'legacy.web.incorrect.c7d63751')}>!</span>}
            </button>;
          })}
        </div>:current.question_type==='ORDER'?<OrderInput key={current.id+'-'+attemptReset} prompt={current.prompt} disabled={saving||selectedOptionIsRight} onSubmit={answer}/>:
        current.question_type==='MATCH'?<MatchInput key={current.id+'-'+attemptReset} disabled={saving||selectedOptionIsRight} onSubmit={answer}/>:
        <div className={'qe-answer-entry '+(result?.correct===false?'is-incorrect':result?.correct===true?'is-correct':'')}>
          {current.question_type==='NUMERICAL'?<input inputMode="decimal" value={selected} disabled={saving||selectedOptionIsRight} onChange={e=>setSelected(e.target.value)} placeholder={translateUi(locale, 'legacy.web.enter.your.numerical.answer.65eb7d35')}/>:
            <textarea value={selected} disabled={saving||selectedOptionIsRight} onChange={e=>setSelected(e.target.value)} placeholder={translateUi(locale, 'legacy.web.write.your.answer.or.reasoning.here.0daf3a15')} rows={current.question_type==='LONG_ANSWER'?7:5}/>}
          <button className="button button-dark" disabled={saving||!selected.trim()||selectedOptionIsRight} onClick={()=>void answer(selected)}>{saving?<UiText message="legacy.web.checking.answer.b4319f7e" />:<UiText message="legacy.web.submit.answer.10965fb7" />}</button>
        </div>}

        {answerError&&<div className="qe-answer-feedback is-incorrect" role="alert"><span className="qe-answer-feedback__icon">!</span><div><strong><UiText message="legacy.web.answer.was.not.saved.6a166e3f" /></strong><p>{answerError}</p></div></div>}
        {result&&<AnswerFeedback
          outcome={result.correct===true?'correct':result.correct===false?'incorrect':'ungraded'}
          title={result.correct===true?<UiText message="legacy.web.well.done.correct.126ebac2" />:result.correct===false?<UiText message="legacy.web.that.s.okay.try.again.25265054" />:<UiText message="legacy.web.answer.saved.b1752a46" />}
          message={result.correct===true?<UiText message="legacy.web.read.the.explanation.to.understand.why.68b982cf" />:result.correct===false?<UiText message="legacy.web.read.the.explanation.below.you.can.ret.26f5df06" />:<UiText message="legacy.web.this.answer.may.need.teacher.or.rubric.b6c2d2ed" />}
          explanation={result.explanation?<MathText text={String(result.explanation)}/>:undefined}
          explanationLabel={<UiText message="legacy.web.why.this.answer.2627f3e7" />}
        />}
        <div className="qe-practice-question-footer">
          <div className="qe-practice-session-state"><span>{saving?<i className="qe-practice-live-dot"/>:sessionStatus==='COMPLETED'?'✓':'•'}</span><span>{saving?<UiText message="legacy.web.checking.your.answer.cfd590d9" />:<UiText message="legacy.web.progress.is.saved.70d860bc" />}</span></div>
          {result?.correct===false?<button type="button" className="button button-dark" onClick={tryAgain}><UiText message="legacy.web.try.again.8da39260" /></button>:
            result?<button type="button" className="button button-dark" onClick={next}>{index+1<questions.length?<UiText message="legacy.web.next.question.28a23f5d" />:<UiText message="legacy.web.see.results.b85010b7" />}</button>:
            <span className="qe-practice-awaiting"><UiText message="legacy.web.choose.your.answer.e401c4fc" /></span>}
        </div>
      </article>

      <div className="qe-practice-trust"><span>✓</span><p><UiText message="legacy.web.approved.questions.only.your.answers.a.e62d67c0" /></p></div>
    </section>
  </main>;
}

function OrderInput({prompt,disabled,onSubmit}:{prompt:string;disabled:boolean;onSubmit:(value:string|object)=>void}){
  const letters=Array.from(new Set(prompt.match(/[A-D]/g)||[]));
  const [order,setOrder]=useState<string[]>([]);
  function pick(letter:string){if(disabled||order.includes(letter))return;setOrder([...order,letter]);}
  function reset(){if(!disabled)setOrder([])}
  return <div className="concept-card structured-card"><div className="structured-hint"><UiText message="legacy.web.choose.the.sequence.by.tapping.each.op.e0727d5b" /></div><div className="structured-chips">{letters.map(x=><button disabled={disabled||order.includes(x)} key={x} onClick={()=>pick(x)}>{x}</button>)}</div><div className="structured-answer">{order.length?order.join(' → '):<UiText message="legacy.web.no.sequence.selected.yet.464c236b" />}</div><div className="practice-footer"><button className="text-link" onClick={reset}><UiText message="legacy.web.reset.a46f4415" /></button><button className="button button-dark" disabled={disabled||!order.length} onClick={()=>onSubmit(order)}><UiText message="legacy.web.submit.sequence.c3a8f3bb" /></button></div></div>;
}

function MatchInput({disabled,onSubmit}:{disabled:boolean;onSubmit:(value:string|object)=>void}){
  const [text,setText]=useState('');
  function submit(){
    const mapping=text.split(',').map(s=>s.trim()).filter(Boolean).reduce((acc,item)=>{
      const [k,v]=item.split(':').map(x=>x.trim()); if(k&&v) (acc as any)[k]=v; return acc;
    },{} as Record<string,string>);
    if(Object.keys(mapping).length) onSubmit(mapping);
  }
  return <div className="concept-card structured-card"><div className="structured-hint"><UiText message="legacy.web.enter.your.matches.9e8a54ae" /> <b><UiText message="legacy.web.1.a.2.b.3.c.4c65fa05" /></b></div><input disabled={disabled} value={text} onChange={e=>setText(e.target.value)} placeholder="1:A, 2:B, 3:C"/><button className="button button-dark" disabled={disabled||!text.trim()} onClick={submit}><UiText message="legacy.web.submit.matches.e3628269" /></button></div>;
}
