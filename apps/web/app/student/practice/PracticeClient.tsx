'use client';

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
          const language=learningLanguage;
          const lessonResponse=await fetch('/api/v1/learning/lessons/'+lessonId+'?language='+language,{cache:'no-store'});
          const body=await readBody(lessonResponse);
          if(!lessonResponse.ok)throw new Error(body.detail||body.message||tx('Lesson उपलब्ध नहीं है।','This lesson is unavailable.'));
          lessonBody=body as LessonDetail;
          if(active)setLesson(lessonBody);
        }

        if(requestedSessionId){
          const sessionResponse=await fetch('/api/v1/learning/practice/sessions/'+requestedSessionId+'?language='+learningLanguage,{cache:'no-store'});
          const body=await readBody(sessionResponse);
          if(!sessionResponse.ok)throw new Error(body.detail||body.message||tx('Practice session दोबारा नहीं खुल पाया।','Could not reopen this practice session.'));
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
          setNotice(tx('आपका saved practice session फिर से खुल गया।','Your saved practice session has been reopened.'));
          return;
        }

        if(!lessonId)throw new Error(tx('Practice शुरू करने के लिए पहले lesson खोलें।','Open a lesson before starting practice.'));
        const startResponse=await fetch('/api/v1/learning/practice/sessions/from-lesson',{
          method:'POST',headers:{'Content-Type':'application/json'},
          body:JSON.stringify({lessonId,language:learningLanguage})
        });
        const startBody:ApiBody=await readBody(startResponse);
        if(startResponse.status===409){
          if(active){
            setNoPractice(true);
            setNotice(tx(
              'इस micro-topic के लिए अतिरिक्त approved practice questions अभी publish नहीं हुए हैं। आपके दिए हुए MCQ answers save हैं।',
              'Additional approved practice questions have not been published for this micro-topic yet. Your submitted MCQ answers are saved.'
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
        setTranslationComplete(typeof startBody.translationComplete==='boolean'
          ? startBody.translationComplete : loaded.length>0&&loaded.every(q=>q.translated===true));
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
      if(!response.ok)throw new Error(body.detail||body.message||tx('उत्तर save नहीं हुआ।','Could not save your answer.'));
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
    <section className="practice-wrap">
      <div className="eyebrow"><LocaleText hinglish="सीखें → अभ्यास करें" english="Learn → Practise" /></div>
      <h1><LocaleText hinglish="पहले एक lesson चुनें" english="Start with a lesson" /></h1>
      <p><LocaleText hinglish="आप lesson के MCQs के बाद सीधे उसी micro-topic का practice करेंगे। Questions QuantaEdge अपने-आप चुनेगा।" english="After the lesson MCQs, you will go straight into practice for the same micro-topic. QuantaEdge selects the questions automatically." /></p>
      <div className="concept-card"><Link href="/student/learn" className="button button-dark"><LocaleText hinglish="अपने lessons खोलें →" english="Open your lessons →" /></Link></div>
    </section>
  </main>;

  if(error)return <main className="practice-page">
    <section className="practice-wrap"><div className="practice-feedback practice-feedback-incorrect" role="alert"><strong><LocaleText hinglish="Practice नहीं खुल पाई" english="Practice could not be opened" /></strong><span>{error}</span></div><Link href={lesson?'/student/learn?lessonId='+lesson.id:'/student/learn'} className="button button-dark" style={{marginTop:20}}><LocaleText hinglish="Lesson पर लौटें" english="Return to lesson" /></Link></section>
  </main>;

  if(noPractice)return <main className="practice-page">
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

  // Keep the final answer's feedback visible until the learner advances to the summary.
  // A server-completed session alone must not unmount the current question immediately.
  if(index>=questions.length)return <main className="practice-page">
    <section className="practice-wrap">
      <div className="eyebrow"><LocaleText hinglish="Micro-topic पूरा हुआ" english="Micro-topic complete" /></div>
      <h1><LocaleText hinglish="बहुत अच्छा! अभ्यास पूरा हुआ।" english="Well done! Practice complete." /></h1>
      <p>{lesson?.title||tx('आपका practice session','Your practice session')} · {questions.length} <LocaleText hinglish="questions" english="questions" /></p>
      <div className="concept-card"><h2><LocaleText hinglish="इस session का परिणाम" english="Session result" /></h2>{gradedCount>0?<p>{correctCount} / {gradedCount} <LocaleText hinglish="questions का latest graded answer सही है" english="questions have a correct latest graded answer" /></p>:<p><LocaleText hinglish="आपके दिए गए answers save हैं।" english="Your submitted answers have been saved." /></p>}<small><LocaleText hinglish="Progress आपके answers से save हुआ है। Mastery केवल जाँचे गए सही/गलत उत्तरों से update होती है।" english="Your answers have been saved. Mastery is updated from graded answers, not from simply opening a question." /></small></div>
      {recap.map(block=>{
        const data=parse<any>(block.content||'{}');
        return <div className="concept-card" key={block.id}><span className="concept-kicker"><LocaleText hinglish="अब दोहराएँ · Recap" english="Recap" /></span>{data.points?.map((point:string,i:number)=><div className="feedback" key={i}><span>✓ <MathText text={point}/></span></div>)}</div>;
      })}
      <div className="practice-footer"><Link href={lesson?'/student/learn?subjectCode='+lesson.subject_code:'/student/learn'} className="text-link"><LocaleText hinglish="अध्याय के lessons" english="Chapter lessons" /></Link><Link href="/student/progress" className="button button-dark"><LocaleText hinglish="Progress देखें →" english="View progress →" /></Link></div>
    </section>
  </main>;

  const questionTypeLabel=current.question_type==='MCQ'?tx('बहुविकल्पीय प्रश्न','Multiple choice'):current.question_type==='TRUE_FALSE'?tx('सही या गलत','True or false'):current.question_type==='NUMERICAL'?tx('संख्यात्मक प्रश्न','Numerical question'):current.question_type.replaceAll('_',' ').toLowerCase();
  const progressPercent=Math.round(((index+1)/Math.max(questions.length,1))*100);
  const selectedOptionIsWrong=result?.correct===false;
  const selectedOptionIsRight=result?.correct===true;

  return <main className="practice-page qe-practice-page">
    <section className="practice-wrap qe-practice-wrap">
      <div className="qe-practice-intro">
        <div className="qe-practice-intro__eyebrow"><span className="qe-practice-live-dot"/><LocaleText hinglish="आपकी सीख के अनुसार चुना गया" english="PERSONALISED TO YOUR LESSON" /></div>
        <h1><LocaleText hinglish="अब खुद करके देखें" english="Now, put it into practice" /></h1>
        <p><LocaleText hinglish="हर सवाल आपको concept को और अच्छी तरह समझने में मदद करता है। गलत उत्तर भी सीखने का हिस्सा है।" english="Each question helps deepen your understanding. An incorrect answer is part of learning." /></p>
      </div>

      <div className="qe-practice-progress" aria-label={tx('अभ्यास की प्रगति','Practice progress')}>
        <div className="qe-practice-progress__meta"><span><LocaleText hinglish="प्रश्न की प्रगति" english="QUESTION PROGRESS" /></span><strong>{Math.min(index+1,questions.length)} <i>/</i> {questions.length}</strong></div>
        <div className="qe-practice-progress__track"><span style={{width:progressPercent+'%'}}/></div>
      </div>

      {!translationComplete&&<div className="qe-practice-notice" role="status"><span>文</span><p><LocaleText
        hinglish="भाषा कवरेज: कुछ practice प्रश्नों की चुनी हुई भाषा में अलग, समीक्षा-स्वीकृत प्रति उपलब्ध नहीं है। जहाँ ज़रूरी हो, मूल प्रश्न दिखेगा; answer key नहीं बदलती।"
        english="Language coverage: some practice questions do not yet have a separately reviewed version in the selected language. Original text is retained where needed; answer keys are unchanged."
      /></p></div>}
      {notice&&<div className="qe-practice-notice" role="status"><span>✦</span><p>{notice}</p></div>}
      {error&&<div className="qe-answer-feedback is-incorrect" role="alert"><span className="qe-answer-feedback__icon">!</span><div><strong><LocaleText hinglish="कुछ ठीक नहीं हुआ" english="Something went wrong" /></strong><p>{error}</p></div></div>}

      <article className={'qe-question-card qe-question-card--practice '+(result?.correct===false?'has-answer-incorrect':result?.correct===true?'has-answer-correct':'')}>
        <div className="qe-question-card__top">
          <span className="qe-question-index"><span>{String(index+1).padStart(2,'0')}</span></span>
          <div className="qe-question-card__labels">
            <span className="qe-question-type">{questionTypeLabel}</span>
            <span className="qe-question-context">{current.exam_format||tx('Topic practice','Topic practice')}{current.marks?' · '+current.marks+' '+tx('अंक','marks'):''}</span>
          </div>
          <span className="qe-question-source">{current.source_kind==='TEXTBOOK_ALIGNED'?<LocaleText hinglish="पाठ्यपुस्तक आधारित" english="Textbook-aligned" />:current.source_kind?<MathText text={current.source_kind.replaceAll('_',' ').toLowerCase()}/>:<LocaleText hinglish="QuantaEdge प्रश्न" english="QuantaEdge question" />}</span>
        </div>

        <div className="qe-question-card__prompt"><MathText text={current.prompt}/></div>
        <div className="qe-practice-hint"><span>✦</span><p><LocaleText hinglish="पहले खुद सोचें। फिर उत्तर दें और feedback से समझें कि आपकी reasoning सही है या नहीं।" english="Think it through first. Then answer and use the feedback to check your reasoning." /></p></div>

        {options.length>0?<div className="qe-answer-options" role="group" aria-label={tx('उत्तर के विकल्प','Answer choices')}>
          {options.map((option,i)=>{
            const chosen=selected===option.key;
            const stateClass=chosen&&selectedOptionIsWrong?'is-incorrect':chosen&&selectedOptionIsRight?'is-correct':chosen?'is-selected':'';
            return <button type="button" key={option.key} disabled={saving||selectedOptionIsRight} aria-pressed={chosen} className={'qe-answer-option '+stateClass} onClick={()=>void answer(option.key)}>
              <span className="qe-answer-option__key">{option.key||String.fromCharCode(65+i)}</span>
              <span className="qe-answer-option__text"><MathText text={option.label}/></span>
              {chosen&&selectedOptionIsRight&&<span className="qe-answer-option__status" aria-label={tx('सही','Correct')}>✓</span>}
              {chosen&&selectedOptionIsWrong&&<span className="qe-answer-option__status" aria-label={tx('गलत','Incorrect')}>!</span>}
            </button>;
          })}
        </div>:current.question_type==='ORDER'?<OrderInput key={current.id+'-'+attemptReset} prompt={current.prompt} disabled={saving||selectedOptionIsRight} onSubmit={answer}/>:
        current.question_type==='MATCH'?<MatchInput key={current.id+'-'+attemptReset} disabled={saving||selectedOptionIsRight} onSubmit={answer}/>:
        <div className={'qe-answer-entry '+(result?.correct===false?'is-incorrect':result?.correct===true?'is-correct':'')}>
          {current.question_type==='NUMERICAL'?<input inputMode="decimal" value={selected} disabled={saving||selectedOptionIsRight} onChange={e=>setSelected(e.target.value)} placeholder={tx('अपना संख्यात्मक उत्तर लिखें…','Enter your numerical answer…')}/>:
            <textarea value={selected} disabled={saving||selectedOptionIsRight} onChange={e=>setSelected(e.target.value)} placeholder={tx('अपना उत्तर या reasoning यहाँ लिखें…','Write your answer or reasoning here…')} rows={current.question_type==='LONG_ANSWER'?7:5}/>}
          <button className="button button-dark" disabled={saving||!selected.trim()||selectedOptionIsRight} onClick={()=>void answer(selected)}>{saving?<LocaleText hinglish="उत्तर जाँचा जा रहा है…" english="Checking answer…" />:<LocaleText hinglish="उत्तर जमा करें" english="Submit answer" />}</button>
        </div>}

        {answerError&&<div className="qe-answer-feedback is-incorrect" role="alert"><span className="qe-answer-feedback__icon">!</span><div><strong><LocaleText hinglish="उत्तर सेव नहीं हुआ" english="Answer was not saved" /></strong><p>{answerError}</p></div></div>}
        {result&&<AnswerFeedback
          outcome={result.correct===true?'correct':result.correct===false?'incorrect':'ungraded'}
          title={result.correct===true?<LocaleText hinglish="बहुत बढ़िया — सही उत्तर!" english="Well done — correct!" />:result.correct===false?<LocaleText hinglish="कोई बात नहीं, फिर प्रयास करें" english="That's okay — try again" />:<LocaleText hinglish="उत्तर सेव हो गया" english="Answer saved" />}
          message={result.correct===true?<LocaleText hinglish="Explanation पढ़ें और समझें कि यह तरीका क्यों काम करता है।" english="Read the explanation to understand why this method works." />:result.correct===false?<LocaleText hinglish="नीचे दिया explanation पढ़ें। आप इसी सवाल को दोबारा हल कर सकते हैं।" english="Read the explanation below. You can retry this same question." />:<LocaleText hinglish="इस उत्तर की शिक्षक या rubric से जाँच आवश्यक हो सकती है।" english="This answer may need teacher or rubric review." />}
          explanation={result.explanation?<MathText text={String(result.explanation)}/>:undefined}
          explanationLabel={<LocaleText hinglish="समझें क्यों" english="WHY THIS ANSWER" />}
        />}
        <div className="qe-practice-question-footer">
          <div className="qe-practice-session-state"><span>{saving?<i className="qe-practice-live-dot"/>:sessionStatus==='COMPLETED'?'✓':'•'}</span><span>{saving?<LocaleText hinglish="उत्तर जाँचा जा रहा है" english="Checking your answer" />:<LocaleText hinglish="आपकी प्रगति सेव है" english="Progress is saved" />}</span></div>
          {result?.correct===false?<button type="button" className="button button-dark" onClick={tryAgain}><LocaleText hinglish="फिर से कोशिश करें ↻" english="Try again ↻" /></button>:
            result?<button type="button" className="button button-dark" onClick={next}>{index+1<questions.length?<LocaleText hinglish="अगला सवाल →" english="Next question →" />:<LocaleText hinglish="नतीजे देखें →" english="See results →" />}</button>:
            <span className="qe-practice-awaiting"><LocaleText hinglish="अपना उत्तर चुनें" english="Choose your answer" /></span>}
        </div>
      </article>

      <div className="qe-practice-trust"><span>✓</span><p><LocaleText hinglish="सिर्फ approved questions · आपके उत्तर सुरक्षित रूप से save होते हैं" english="Approved questions only · Your answers are saved securely" /></p></div>
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
