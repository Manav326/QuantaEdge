'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';
import MathText from '../../components/MathText';
import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';

type Question={
  id:number;lesson_id?:number;question_type:string;prompt:string;explanation:string;options:string;
  source_kind?:string;source_year?:number;board?:string;marks?:number;exam_format?:string;
  topic?:string;subtopic?:string;skill?:string;response_mode?:string;answered?:boolean
};
type LessonBlock={id:number;sequence_no:number;block_type:string;content:string};
type LessonDetail={
  id:number;title:string;summary?:string;class_code?:string;chapter_name?:string;
  subject_name?:string;subject_code?:string;objective_title?:string;blocks:LessonBlock[];
};
type ApiBody={detail?:string;message?:string;sessionId?:number;status?:string;questions?:Question[];selectedCount?:number};

function parse<T=any>(v:string):T{try{return JSON.parse(v) as T}catch{return [] as T}}
async function readBody(response:Response):Promise<any>{
  const value=await response.text();
  if(!value)return {};
  try{return JSON.parse(value)}catch{return {message:value}}
}
function recapBlocks(lesson:LessonDetail|null){
  return (lesson?.blocks||[]).filter(block=>block.block_type==='SUMMARY'||block.block_type==='RECAP');
}

export default function PracticePage(){
  const [questions,setQuestions]=useState<Question[]>([]);
  const [lesson,setLesson]=useState<LessonDetail|null>(null);
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [sessionStatus,setSessionStatus]=useState('IN_PROGRESS');
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
  const router=useRouter();
  const searchParams=useSearchParams();
  const {locale}=useLocale();
  const tx=(hinglish:string,english:string)=>locale==='english'?english:hinglish;
  const lessonIdRaw=searchParams.get('lessonId')||'';
  const sessionIdRaw=searchParams.get('sessionId')||'';
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
        if(!me.ok)throw new Error(studentBody.detail||studentBody.message||tx('Student profile नहीं मिला।','Student profile was not found.'));
        if(!active)return;

        let lessonBody:LessonDetail|null=null;
        if(lessonId){
          const lessonResponse=await fetch('/api/v1/learning/lessons/'+lessonId,{cache:'no-store'});
          const body=await readBody(lessonResponse);
          if(!lessonResponse.ok)throw new Error(body.detail||body.message||tx('Lesson उपलब्ध नहीं है।','This lesson is unavailable.'));
          lessonBody=body as LessonDetail;
          if(active)setLesson(lessonBody);
        }

        if(requestedSessionId){
          const sessionResponse=await fetch('/api/v1/learning/practice/sessions/'+requestedSessionId,{cache:'no-store'});
          const body=await readBody(sessionResponse);
          if(!sessionResponse.ok)throw new Error(body.detail||body.message||tx('Practice session दोबारा नहीं खुल पाया।','Could not reopen this practice session.'));
          if(!active)return;
          const resumed:Array<Question>=Array.isArray(body.questions)?body.questions:[];
          setSessionId(Number(body.sessionId));
          setSessionStatus(String(body.status||'IN_PROGRESS'));
          setQuestions(resumed);
          const firstUnanswered=resumed.findIndex(q=>!q.answered);
          setIndex(firstUnanswered<0?resumed.length:firstUnanswered);
          setSelected('');setResult(null);
          if(firstUnanswered<0&&resumed.length)setSessionStatus('COMPLETED');
          setNotice(tx('आपका saved practice session फिर से खुल गया।','Your saved practice session has been reopened.'));
          return;
        }

        if(!lessonId)throw new Error(tx('Practice शुरू करने के लिए पहले lesson खोलें।','Open a lesson before starting practice.'));
        const startResponse=await fetch('/api/v1/learning/practice/sessions/from-lesson',{
          method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({lessonId})
        });
        const startBody:ApiBody=await readBody(startResponse);
        if(startResponse.status===409){
          if(active){
            setNoPractice(true);
            setNotice(startBody.detail||startBody.message||tx(
              'इस micro-topic के लिए अतिरिक्त approved practice questions अभी publish नहीं हुए हैं।',
              'Additional approved practice questions have not been published for this micro-topic yet.'
            ));
          }
          return;
        }
        if(!startResponse.ok)throw new Error(startBody.detail||startBody.message||tx('इस topic का practice set नहीं बन पाया।','Could not prepare practice for this topic.'));
        if(!active)return;
        const loaded:Array<Question>=Array.isArray(startBody.questions)?startBody.questions:[];
        if(!loaded.length){
          setNoPractice(true);
          setNotice(tx('इस micro-topic के लिए अभी practice questions उपलब्ध नहीं हैं।','No practice questions are currently available for this micro-topic.'));
          return;
        }
        setSessionId(Number(startBody.sessionId));
        setSessionStatus(String(startBody.status||'IN_PROGRESS'));
        setQuestions(loaded);setIndex(0);setSelected('');setResult(null);setScoreByQuestion({});
        setNotice(String(startBody.message||tx('इस lesson का practice तैयार है।','Practice for this lesson is ready.')));
        const url=new URL(window.location.href);
        url.searchParams.set('sessionId',String(startBody.sessionId));
        window.history.replaceState({},'',url.toString());
      }catch(e:any){
        if(active)setError(e.message||tx('Practice load नहीं हो पाई।','Unable to load practice.'));
      }finally{
        if(active)setLoading(false);
      }
    }
    void load();
    return()=>{active=false;};
  },[lessonId,requestedSessionId,router,locale]);

  async function answer(value:string|object){
    if(!current||!sessionId||saving||result?.correct===true)return;
    const serialized=typeof value==='string'?value:JSON.stringify(value);
    if(!serialized.trim())return;
    setSaving(true);setAnswerError('');setError('');setSelected(serialized);
    try{
      const response=await fetch('/api/v1/learning/questions/'+current.id+'/answer',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({answer:value,practiceSessionId:sessionId})
      });
      const body=await readBody(response);
      if(!response.ok)throw new Error(body.detail||body.message||tx('उत्तर save नहीं हुआ।','Could not save your answer.'));
      setResult(body);
      setScoreByQuestion(old=>({...old,[current.id]:body}));
      const sessionResponse=await fetch('/api/v1/learning/practice/sessions/'+sessionId,{cache:'no-store'});
      if(sessionResponse.ok){
        const sessionBody=await readBody(sessionResponse);
        setSessionStatus(String(sessionBody.status||'IN_PROGRESS'));
        if(Array.isArray(sessionBody.questions))setQuestions(sessionBody.questions);
      }
    }catch(e:any){
      setAnswerError(e.message||tx('उत्तर save नहीं हुआ। फिर प्रयास करें।','Could not save your answer. Please try again.'));
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

  if(loading)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><LocaleText hinglish="आपके topic का practice तैयार हो रहा है…" english="Preparing practice for your topic…" /></div></section></main>;

  if(!lessonId&&!requestedSessionId)return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span>Topic practice</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow"><LocaleText hinglish="सीखें → अभ्यास करें" english="Learn → Practise" /></div>
      <h1><LocaleText hinglish="पहले एक lesson चुनें" english="Start with a lesson" /></h1>
      <p><LocaleText hinglish="आप lesson के MCQs के बाद सीधे उसी micro-topic का practice करेंगे। Questions QuantaEdge अपने-आप चुनेगा।" english="After the lesson MCQs, you will go straight into practice for the same micro-topic. QuantaEdge selects the questions automatically." /></p>
      <div className="concept-card"><Link href="/student/learn" className="button button-dark"><LocaleText hinglish="अपने lessons खोलें →" english="Open your lessons →" /></Link></div>
    </section>
  </main>;

  if(error)return <main className="practice-page">
    <header className="lesson-header"><Link href={lesson?'/student/learn?subjectCode='+lesson.subject_code:'/student'} className="back"><LocaleText hinglish="← वापस" english="← Back" /></Link><span>Topic practice</span><span className="avatar">अ</span></header>
    <section className="practice-wrap"><div className="practice-feedback practice-feedback-incorrect" role="alert"><strong><LocaleText hinglish="Practice नहीं खुल पाई" english="Practice could not be opened" /></strong><span>{error}</span></div><Link href={lesson?'/student/learn?lessonId='+lesson.id:'/student/learn'} className="button button-dark" style={{marginTop:20}}><LocaleText hinglish="Lesson पर लौटें" english="Return to lesson" /></Link></section>
  </main>;

  if(noPractice)return <main className="practice-page">
    <header className="lesson-header"><Link href={lesson?'/student/learn?lessonId='+lesson.id:'/student'} className="back"><LocaleText hinglish="← Lesson" english="← Lesson" /></Link><span>Topic practice</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow">{lesson?.subject_name||'QuantaEdge'} · {lesson?.chapter_name||tx('अध्याय','Chapter')}</div>
      <h1><LocaleText hinglish="Practice set अभी उपलब्ध नहीं है" english="Practice set is not available yet" /></h1>
      <p>{notice}</p>
      <div className="concept-card"><h2>{lesson?.title||tx('इस micro-topic','This micro-topic')}</h2><p><LocaleText hinglish="अलग topic के questions दिखाने के बजाय, QuantaEdge सिर्फ सही micro-topic के approved questions उपलब्ध होने पर ही practice देगा। आपके lesson के answers save हैं।" english="Instead of showing questions from an unrelated topic, QuantaEdge only starts practice when approved questions for the correct micro-topic are available. Your lesson answers are saved." /></p></div>
      {recap.map(block=>{
        const data=parse<any>(block.content||'{}');
        return <div className="concept-card" key={block.id}><span className="concept-kicker"><LocaleText hinglish="दोहराएँ · Recap" english="Recap" /></span>{data.points?.map((point:string,i:number)=><div className="feedback" key={i}><span>✓ <MathText text={point}/></span></div>)}</div>;
      })}
      <Link href={lesson?'/student/learn?subjectCode='+lesson.subject_code:'/student/learn'} className="button button-dark" style={{marginTop:22}}><LocaleText hinglish="दूसरा lesson चुनें" english="Choose another lesson" /></Link>
    </section>
  </main>;

  if(sessionStatus==='COMPLETED'||index>=questions.length)return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span>Practice complete</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow"><LocaleText hinglish="Micro-topic पूरा हुआ" english="Micro-topic complete" /></div>
      <h1><LocaleText hinglish="बहुत अच्छा! अभ्यास पूरा हुआ।" english="Well done! Practice complete." /></h1>
      <p>{lesson?.title||tx('आपका practice session','Your practice session')} · {questions.length} <LocaleText hinglish="questions" english="questions" /></p>
      <div className="concept-card"><h2><LocaleText hinglish="इस session का परिणाम" english="Session result" /></h2><p>{correctCount} / {gradedCount} <LocaleText hinglish="graded answers सही" english="graded answers correct" /></p><small><LocaleText hinglish="Progress आपके answers से save हुआ है। Mastery केवल जाँचे गए सही/गलत उत्तरों से update होती है।" english="Your answers have been saved. Mastery is updated from graded answers, not from simply opening a question." /></small></div>
      {recap.map(block=>{
        const data=parse<any>(block.content||'{}');
        return <div className="concept-card" key={block.id}><span className="concept-kicker"><LocaleText hinglish="अब दोहराएँ · Recap" english="Recap" /></span>{data.points?.map((point:string,i:number)=><div className="feedback" key={i}><span>✓ <MathText text={point}/></span></div>)}</div>;
      })}
      <div className="practice-footer"><Link href={lesson?'/student/learn?subjectCode='+lesson.subject_code:'/student/learn'} className="text-link"><LocaleText hinglish="अध्याय के lessons" english="Chapter lessons" /></Link><Link href="/student/progress" className="button button-dark"><LocaleText hinglish="Progress देखें →" english="View progress →" /></Link></div>
    </section>
  </main>;

  return <main className="practice-page">
    <header className="lesson-header"><Link href={lesson?'/student/learn?lessonId='+lesson.id:'/student'} className="back"><LocaleText hinglish="← Lesson" english="← Lesson" /></Link><span>Practice · {index+1} / {questions.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow">Class {lesson?.class_code??'—'} · {lesson?.chapter_name||tx('Topic practice','Topic practice')} · Session #{sessionId}</div>
      {notice&&<div className="feedback" role="status"><span>{notice}</span></div>}
      {error&&<div className="practice-feedback practice-feedback-incorrect" role="alert"><strong>Practice error</strong><span>{error}</span></div>}
      <div className="feedback">
        <span>{current.exam_format??tx('Topic practice','Topic practice')}{current.marks?' · '+current.marks+' marks':''}</span>
        <span>{current.source_kind==='TEXTBOOK_ALIGNED'?<LocaleText hinglish="SCERT से aligned question" english="Question aligned with SCERT" />:current.source_kind??<LocaleText hinglish="Content team का question" english="Content team question" />}</span>
        {current.source_year?<span>{current.source_year}</span>:null}
      </div>
      <h1><MathText text={current.prompt}/></h1>
      <p><LocaleText hinglish="पहले सोचें, answer दें, फिर feedback पढ़ें। गलत होने पर इसी question को दोबारा try कर सकते हैं।" english="Think first, answer, then review the feedback. If it is incorrect, you can retry this same question." /></p>

      {options.length>0?<div className="option-grid">
        {options.map(option=><button key={option.key} disabled={saving||result?.correct===true} className={selected===option.key?(result?.correct===false?'incorrect':result?.correct===true?'correct':'selected'):''} onClick={()=>void answer(option.key)}>{option.key}. <MathText text={option.label}/></button>)}
      </div>:current.question_type==='ORDER'?<OrderInput prompt={current.prompt} disabled={saving||result?.correct===true} onSubmit={answer}/>:
      current.question_type==='MATCH'?<MatchInput disabled={saving||result?.correct===true} onSubmit={answer}/>:
      <div className="concept-card">
        {current.question_type==='NUMERICAL'?<input inputMode="decimal" value={selected} disabled={saving||result?.correct===true} onChange={e=>setSelected(e.target.value)} placeholder={tx('Number वाला answer यहाँ लिखें…','Enter a numeric answer…')}/>:
          <textarea value={selected} disabled={saving||result?.correct===true} onChange={e=>setSelected(e.target.value)} placeholder={tx('अपना answer या reasoning यहाँ लिखें…','Enter your answer or reasoning here…')} rows={current.question_type==='LONG_ANSWER'?7:5}/>}
        <button className="button button-dark" disabled={saving||!selected.trim()||result?.correct===true} onClick={()=>void answer(selected)}><LocaleText hinglish="Answer submit करें" english="Submit answer" /></button>
      </div>}

      {answerError&&<div className="practice-feedback practice-feedback-incorrect" role="alert"><strong><LocaleText hinglish="उत्तर save नहीं हुआ" english="Answer was not saved" /></strong><span>{answerError}</span></div>}
      {result&&<div className={'practice-feedback '+(result.correct===false?'practice-feedback-incorrect':result.correct===true?'practice-feedback-correct':'practice-feedback-ungraded')} role={result.correct===false?'alert':'status'}>
        <strong>{result.correct===true?<LocaleText hinglish="✓ सही जवाब" english="✓ Correct answer" />:result.correct===false?<LocaleText hinglish="अभी सही नहीं" english="Not correct yet" />:<LocaleText hinglish="Answer save हो गया" english="Answer saved" />}</strong>
        <span><MathText text={String(result.feedback??'')}/></span>
        {result.explanation&&<span><MathText text={String(result.explanation)}/></span>}
      </div>}

      <div className="practice-footer">
        <span>Question {index+1} of {questions.length} · {sessionStatus.toLowerCase().replace('_',' ')}</span>
        {result?.correct===false?<button className="button button-dark" onClick={tryAgain}><LocaleText hinglish="फिर से प्रयास करें ↻" english="Try again ↻" /></button>:
          result?<button className="button button-dark" onClick={next}>{index+1<questions.length?<LocaleText hinglish="अगला question →" english="Next question →" />:<LocaleText hinglish="Practice complete →" english="Finish practice →" />}</button>:
          <span><LocaleText hinglish="उत्तर दें" english="Answer" /></span>}
      </div>
    </section>
  </main>;
}

function OrderInput({prompt,disabled,onSubmit}:{prompt:string;disabled:boolean;onSubmit:(value:string|object)=>void}){
  const letters=Array.from(new Set(prompt.match(/[A-D]/g)||[]));
  const [order,setOrder]=useState<string[]>([]);
  function pick(letter:string){if(disabled||order.includes(letter))return;setOrder([...order,letter]);}
  function reset(){if(!disabled)setOrder([])}
  return <div className="concept-card structured-card"><div className="structured-hint"><LocaleText hinglish="Sequence चुनें: हर option पर एक बार tap करें।" english="Choose the sequence by tapping each option once." /></div><div className="structured-chips">{letters.map(x=><button disabled={disabled||order.includes(x)} key={x} onClick={()=>pick(x)}>{x}</button>)}</div><div className="structured-answer">{order.length?order.join(' → '):<LocaleText hinglish="अभी sequence नहीं चुना गया" english="No sequence selected yet" />}</div><div className="practice-footer"><button className="text-link" onClick={reset}><LocaleText hinglish="Reset" english="Reset" /></button><button className="button button-dark" disabled={disabled||!order.length} onClick={()=>onSubmit(order)}><LocaleText hinglish="Sequence submit करें" english="Submit sequence" /></button></div></div>;
}

function MatchInput({disabled,onSubmit}:{disabled:boolean;onSubmit:(value:string|object)=>void}){
  const [text,setText]=useState('');
  function submit(){
    const mapping=text.split(',').map(s=>s.trim()).filter(Boolean).reduce((acc,item)=>{
      const [k,v]=item.split(':').map(x=>x.trim()); if(k&&v) (acc as any)[k]=v; return acc;
    },{} as Record<string,string>);
    if(Object.keys(mapping).length) onSubmit(mapping);
  }
  return <div className="concept-card structured-card"><div className="structured-hint"><LocaleText hinglish="Matching लिखें:" english="Enter your matches:" /> <b><LocaleText hinglish="1:A, 2:B, 3:C" english="1:A, 2:B, 3:C" /></b></div><input disabled={disabled} value={text} onChange={e=>setText(e.target.value)} placeholder="1:A, 2:B, 3:C"/><button className="button button-dark" disabled={disabled||!text.trim()} onClick={submit}><LocaleText hinglish="Matching submit करें" english="Submit matches" /></button></div>;
}
