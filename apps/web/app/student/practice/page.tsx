'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';

type Question={
  id:number;lesson_id?:number;question_type:string;prompt:string;explanation:string;options:string;
  source_kind?:string;source_year?:number;board?:string;marks?:number;exam_format?:string;
  topic?:string;subtopic?:string;skill?:string;response_mode?:string
};
function parse<T=any>(v:string):T{try{return JSON.parse(v) as T}catch{return [] as T}}


type PracticeTopic={
  chapter_id:number; chapter_name:string; subject_code:string; subject_name:string;
  topic_name:string; subtopic_name:string; question_count:number; question_types:string;
};
const TYPE_LABELS:Record<string,string>={
  MCQ:'Multiple choice',TRUE_FALSE:'True / false',INPUT:'Short input',NUMERICAL:'Numerical',
  MATCH:'Match pairs',ORDER:'Arrange in order',ASSERTION_REASON:'Assertion & reason',
  CASE_BASED:'Case-based',SHORT_ANSWER:'Short answer',LONG_ANSWER:'Long answer',
  DIAGRAM:'Diagram-based',MAP:'Map-based',SOURCE_BASED:'Source-based'
};
function topicKey(topic:PracticeTopic){
  return JSON.stringify({chapterId:Number(topic.chapter_id),topic:topic.topic_name,subtopic:topic.subtopic_name||''});
}

export default function PracticePage(){
  const [questions,setQuestions]=useState<Question[]>([]);
  const [catalog,setCatalog]=useState<PracticeTopic[]>([]);
  const [student,setStudent]=useState<any>(null);
  const [selectedTopicKeys,setSelectedTopicKeys]=useState<string[]>([]);
  const [questionTypes,setQuestionTypes]=useState<string[]>(['MCQ']);
  const [questionCount,setQuestionCount]=useState('10');
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [sessionStatus,setSessionStatus]=useState('IN_PROGRESS');
  const [index,setIndex]=useState(0);
  const [selected,setSelected]=useState('');
  const [result,setResult]=useState<any>(null);
  const [scoreByQuestion,setScoreByQuestion]=useState<Record<number,any>>({});
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [loading,setLoading]=useState(true);
  const [starting,setStarting]=useState(false);
  const router=useRouter();
  const { locale }=useLocale();
  const tx=(hinglish:string,english:string)=>locale==='english'?english:hinglish;

  useEffect(()=>{
    let active=true;
    async function load(){
      try{
        const me=await fetch('/api/v1/students/me',{cache:'no-store'});
        if(me.status===401||me.status===403){router.replace('/login/student');return;}
        const studentData=await me.json();
        if(!me.ok)throw new Error(studentData.message||tx('Student profile नहीं मिला','Student profile was not found'));
        if(!active)return;
        setStudent(studentData);
        const catalogResponse=await fetch('/api/v1/learning/practice/catalog',{cache:'no-store'});
        const catalogBody=await catalogResponse.json();
        if(!catalogResponse.ok)throw new Error(catalogBody.detail||catalogBody.message||'Unable to load practice topics.');
        if(!active)return;
        setCatalog(Array.isArray(catalogBody)?catalogBody:[]);
        const sessionParam=new URLSearchParams(window.location.search).get('sessionId');
        if(sessionParam&&/^\d+$/.test(sessionParam)){
          const sessionResponse=await fetch('/api/v1/learning/practice/sessions/'+sessionParam,{cache:'no-store'});
          const sessionBody=await sessionResponse.json();
          if(sessionResponse.ok&&active){
            setSessionId(Number(sessionBody.sessionId));
            setSessionStatus(String(sessionBody.status||'IN_PROGRESS'));
            const resumed=Array.isArray(sessionBody.questions)?sessionBody.questions:[];
            setQuestions(resumed);
            const firstUnanswered=resumed.findIndex((q:any)=>!q.answered);
            setIndex(firstUnanswered<0?Math.max(0,resumed.length-1):firstUnanswered);
            if(firstUnanswered<0&&resumed.length)setSessionStatus('COMPLETED');
            setNotice('Your saved practice session has been reopened.');
          }
        }
      }catch(e:any){if(active)setError(e.message||tx('Practice load नहीं हो पाई।','Unable to load practice.'));}
      finally{if(active)setLoading(false);}
    }
    void load();
    return()=>{active=false;};
  },[router,locale]);

  const selectedTopics=catalog.filter(topic=>selectedTopicKeys.includes(topicKey(topic)));
  const availableTypes=Array.from(new Set(catalog.flatMap(topic=>parse<string[]>(topic.question_types)||[]))).filter(type=>TYPE_LABELS[type]);
  const current=questions[index];
  const options=current?parse<{key:string;label:string}[]>(current.options||'[]'):[];
  const scoreValues=Object.values(scoreByQuestion);
  const correctCount=scoreValues.filter(value=>value?.correct===true).length;
  const gradedCount=scoreValues.filter(value=>value?.autoGraded&&typeof value?.correct==='boolean').length;

  async function startPractice(){
    if(!selectedTopicKeys.length){setError('Select at least one topic to practise.');return;}
    if(!questionTypes.length){setError('Choose at least one question type.');return;}
    setStarting(true);setError('');setNotice('');
    try{
      const topicSelections=selectedTopicKeys.map(key=>JSON.parse(key));
      const response=await fetch('/api/v1/learning/practice/sessions',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({topicSelections,questionTypes,questionCount:Number(questionCount)})
      });
      const body=await response.json();
      if(!response.ok)throw new Error(body.detail||body.message||'Could not create practice session.');
      setSessionId(Number(body.sessionId));
      setSessionStatus(String(body.status||'IN_PROGRESS'));
      setQuestions(Array.isArray(body.questions)?body.questions:[]);
      setIndex(0);setSelected('');setResult(null);setScoreByQuestion({});
      setNotice(String(body.message||'Practice set created.'));
      const url=new URL(window.location.href);
      url.searchParams.set('sessionId',String(body.sessionId));
      window.history.replaceState({},'',url.toString());
    }catch(e:any){setError(e.message||'Could not start practice.');}
    finally{setStarting(false);}
  }

  function resetPractice(){
    setQuestions([]);setSessionId(null);setSessionStatus('IN_PROGRESS');
    setIndex(0);setSelected('');setResult(null);setScoreByQuestion({});
    setError('');setNotice('');
    const url=new URL(window.location.href);url.searchParams.delete('sessionId');window.history.replaceState({},'',url.toString());
  }

  async function answer(value:string|object){
    if(!current||!sessionId||result)return;
    const serialized=typeof value==='string'?value:JSON.stringify(value);
    if(!serialized.trim())return;
    setSelected(serialized);setError('');
    try{
      const response=await fetch('/api/v1/learning/questions/'+current.id+'/answer',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({answer:value,practiceSessionId:sessionId})
      });
      const body=await response.json();
      if(!response.ok)throw new Error(body.detail||body.message||'Could not save answer.');
      setResult(body);setScoreByQuestion(old=>({...old,[current.id]:body}));
      const sessionResponse=await fetch('/api/v1/learning/practice/sessions/'+sessionId,{cache:'no-store'});
      if(sessionResponse.ok){
        const sessionBody=await sessionResponse.json();
        if(sessionBody.status==='COMPLETED')setSessionStatus('COMPLETED');
      }
    }catch(e:any){setError(e.message||'Could not save answer.');}
  }

  function next(){
    if(index+1<questions.length){setIndex(index+1);setSelected('');setResult(null);}
  }

  if(loading)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><LocaleText hinglish="Topics load हो रहे हैं…" english="Loading practice topics…" /></div></section></main>;
  if(!questions.length)return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span>Practice setup</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow">Class {student?.class_code??'—'} · Personal practice</div>
      <h1><LocaleText hinglish="अपना practice set बनाएँ" english="Build your practice set" /></h1>
      <p><LocaleText hinglish="एक या कई अध्यायों के topics चुनें, फिर questions की संख्या और प्रकार चुनें। सिर्फ approved questions ही शामिल होंगे।" english="Choose topics across one or more chapters, then choose the question count and types. Only approved questions are included." /></p>
      {error&&<div className="practice-feedback" role="alert"><strong>Could not start practice</strong><span>{error}</span></div>}
      {notice&&<div className="feedback" role="status"><span>{notice}</span></div>}
      {!catalog.length?<div className="concept-card"><h2><LocaleText hinglish="अभी approved questions उपलब्ध नहीं हैं" english="No approved practice questions yet" /></h2><p><LocaleText hinglish="जैसे ही content team questions approve और publish करेगी, वे यहाँ topic-wise दिखाई देंगे।" english="Approved and published questions will appear here by topic as the content team makes them available." /></p><Link href="/student/learn" className="button button-dark">Browse learning</Link></div>:
      <>
        <section className="concept-card">
          <h2><LocaleText hinglish="1. Topics चुनें" english="1. Choose topics" /></h2>
          <p><LocaleText hinglish="आप अलग-अलग chapters से कई topics चुन सकते हैं।" english="You can select multiple topics from different chapters." /></p>
          <div style={{display:'grid',gap:9}}>
            {catalog.map(topic=>{
              const key=topicKey(topic);const checked=selectedTopicKeys.includes(key);
              const types=parse<string[]>(topic.question_types)||[];
              return <label key={key} style={{display:'flex',alignItems:'flex-start',gap:10,padding:12,border:'1px solid '+(checked?'#d07a32':'#dce2ea'),borderRadius:10,background:checked?'#fff7ed':'#fff',cursor:'pointer'}}>
                <input type="checkbox" checked={checked} onChange={e=>setSelectedTopicKeys(old=>e.target.checked?[...old.filter(x=>x!==key),key]:old.filter(x=>x!==key))} style={{marginTop:4}}/>
                <span style={{flex:1}}><strong>{topic.topic_name}</strong>{topic.subtopic_name&&<span> / {topic.subtopic_name}</span>}<small style={{display:'block',color:'#667085',marginTop:4}}>{topic.subject_name} · {topic.chapter_name} · {topic.question_count} questions · {types.map(type=>TYPE_LABELS[type]||type).join(', ')}</small></span>
              </label>;
            })}
          </div>
          <p style={{fontSize:13,color:'#667085',marginBottom:0}}>{selectedTopics.length} topic(s) selected · {selectedTopics.reduce((sum,topic)=>sum+Number(topic.question_count||0),0)} question entries available before type filters</p>
        </section>
        <section className="concept-card">
          <h2><LocaleText hinglish="2. Questions की संख्या" english="2. Number of questions" /></h2>
          <label style={{display:'flex',alignItems:'center',gap:12,flexWrap:'wrap'}}>Questions in this set
            <select value={questionCount} onChange={e=>setQuestionCount(e.target.value)} style={{padding:'10px 12px',borderRadius:8,border:'1px solid #d0d5dd',background:'#fff'}}>
              {[5,10,15,20,30,50,100].map(n=><option key={n} value={n}>{n} questions</option>)}
            </select>
          </label>
        </section>
        <section className="concept-card">
          <h2><LocaleText hinglish="3. Question types चुनें" english="3. Choose question types" /></h2>
          <div style={{display:'grid',gridTemplateColumns:'repeat(auto-fit,minmax(185px,1fr))',gap:9}}>
            {availableTypes.map(type=><label key={type} style={{display:'flex',alignItems:'center',gap:8,padding:10,border:'1px solid #dce2ea',borderRadius:9}}>
              <input type="checkbox" checked={questionTypes.includes(type)} onChange={e=>setQuestionTypes(old=>e.target.checked?[...old,type]:old.filter(x=>x!==type))}/>
              {TYPE_LABELS[type]||type}
            </label>)}
          </div>
        </section>
        <button type="button" className="button button-dark" disabled={starting||!selectedTopicKeys.length||!questionTypes.length} onClick={()=>void startPractice()}>{starting?'Preparing questions…':'Start practice →'}</button>
      </>}
      <p style={{fontSize:13,color:'#667085',marginTop:14}}><LocaleText hinglish="Questions इस session में repeat नहीं होंगे। आपके answers और completion progress save होंगे।" english="Questions will not repeat within this session. Your answers and completion progress are saved." /></p>
    </section>
  </main>;

  const q=current;
  if(!q)return <main className="practice-page"><section className="practice-wrap"><p>No questions in this set.</p><button onClick={resetPractice}>Create another set</button></section></main>;
  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span>Practice · {index+1} / {questions.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow">Class {student?.class_code??'—'} · {q.question_type} · Session #{sessionId}</div>
      {notice&&<div className="feedback" role="status"><span>{notice}</span></div>}
      {error&&<div className="practice-feedback" role="alert"><strong>Answer not saved</strong><span>{error}</span></div>}
      <div className="feedback">
        <span>{q.exam_format??'Topic practice'}{q.marks?' · '+q.marks+' marks':''}</span>
        <span>{q.source_kind==='TEXTBOOK_ALIGNED'?<LocaleText hinglish="SCERT से aligned question" english="Question aligned with SCERT" />:q.source_kind??<LocaleText hinglish="Content team का question" english="Content team question" />}</span>
        {q.source_year?<span>{q.source_year}</span>:null}
      </div>
      <h1>{q.prompt}</h1>
      <p><LocaleText hinglish="पहले सोचें, answer दें, फिर feedback पढ़कर अपनी reasoning check करें।" english="Think first, answer, then use the feedback to check your reasoning." /></p>

      {options.length>0?<div className="option-grid">
        {options.map(o=><button key={o.key} disabled={!!result||Boolean((q as any).answered)} className={selected===o.key?'selected':''} onClick={()=>void answer(o.key)}>{o.key}. {o.label}</button>)}
      </div>:q.question_type==='ORDER'?<OrderInput prompt={q.prompt} disabled={!!result} onSubmit={answer}/>:
      q.question_type==='MATCH'?<MatchInput disabled={!!result} onSubmit={answer}/>:
      <div className="concept-card">
        {q.question_type==='NUMERICAL'?<input inputMode="decimal" value={selected} onChange={e=>setSelected(e.target.value)} placeholder={tx('Number वाला answer यहाँ लिखें…','Enter a numeric answer…')}/>:
          <textarea value={selected} onChange={e=>setSelected(e.target.value)} placeholder={tx('अपना answer या reasoning यहाँ लिखें…','Enter your answer or reasoning here…')} rows={q.question_type==='LONG_ANSWER'?7:5}/>}
        <button className="button button-dark" disabled={!selected.trim()||!!result||Boolean((q as any).answered)} onClick={()=>void answer(selected)}><LocaleText hinglish="Answer submit करें" english="Submit answer" /></button>
      </div>}

      {result&&<div className="practice-feedback">
        <strong>{result.correct===true?<LocaleText hinglish="✓ सही जवाब" english="✓ Correct answer" />:result.correct===false?<LocaleText hinglish="अभी सही नहीं" english="Not quite yet" />:<LocaleText hinglish="Answer save हो गया" english="Answer saved" />}</strong>
        <span>{result.feedback}</span>{result.explanation&&<span>{result.explanation}</span>}
      </div>}

      <div className="practice-footer">
        <span>Question {index+1} of {questions.length} · {sessionStatus.toLowerCase().replace('_',' ')}</span>
        {result&&index+1<questions.length?<button className="button button-dark" onClick={next}><LocaleText hinglish="अगला question →" english="Next question →" /></button>:
          result?<button className="button button-dark" onClick={resetPractice}><LocaleText hinglish="नया practice set बनाएँ →" english="Create another practice set →" /></button>:
          <span><LocaleText hinglish="उत्तर दें" english="Answer" /></span>}
      </div>
      <div style={{marginTop:16,display:'flex',justifyContent:'space-between',gap:10,flexWrap:'wrap'}}>
        <small style={{color:'#667085'}}>Practice score (this visit): {correctCount} correct of {gradedCount} graded</small>
        <button type="button" className="text-link" onClick={resetPractice}>Change topics / question types</button>
      </div>
      {sessionStatus==='COMPLETED'&&<div className="concept-card"><h2><LocaleText hinglish="Practice session complete" english="Practice session complete" /></h2><p>{questions.length} question(s) in this set. Your responses have been saved.</p><Link href="/student/progress" className="button button-dark"><LocaleText hinglish="Progress देखें →" english="View progress →" /></Link></div>}
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
