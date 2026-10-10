'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';

type Question={
  id:number;question_type:string;prompt:string;explanation:string;options:string;
  source_kind?:string;source_year?:number;board?:string;marks?:number;exam_format?:string;
  topic?:string;subtopic?:string;skill?:string;response_mode?:string
};
function parse<T=any>(v:string):T{try{return JSON.parse(v) as T}catch{return [] as T}}

export default function PracticePage(){
  const [questions,setQuestions]=useState<Question[]>([]);
  const [student,setStudent]=useState<any>(null);
  const [index,setIndex]=useState(0);
  const [selected,setSelected]=useState('');
  const [result,setResult]=useState<any>(null);
  const [error,setError]=useState('');
  const router=useRouter();
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;

  useEffect(()=>{
    async function load(){
      try{
        const me=await fetch('/api/v1/students/me');
        if(me.status===401||me.status===403){router.replace('/login/student');return;}
        const studentData=await me.json(); if(!me.ok) throw new Error(studentData.message||tx('Student profile नहीं मिला','Student profile was not found'));
        setStudent(studentData);
        const rec=await fetch('/api/v1/recommendations/next'); const rb=await rec.json();
        if(rec.ok&&rb.kind==='DIAGNOSTIC'){router.replace('/student/diagnostic');return;}
        if(!rec.ok||!rb.available||!rb.lesson) throw new Error(tx('अभी कोई recommended lesson नहीं मिला','No recommended lesson is available yet'));
        const qs=await fetch('/api/v1/learning/lessons/'+rb.lesson.id+'/questions').then(r=>r.json());
        setQuestions(qs);
      }catch(e:any){setError(e.message||tx('Practice load नहीं हो पाई।','Unable to load practice.'));}
    }
    load();
  },[router]);

  if(error)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1><LocaleText hinglish="Practice load नहीं हो पाई" english="Unable to load practice" /></h1><p>{error}</p><Link href="/student" className="button button-dark"><LocaleText hinglish="← Student home" english="← Student home" /></Link></div></section></main>;
  if(!questions.length)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><LocaleText hinglish="Practice load हो रही है…" english="Loading practice…" /></div></section></main>;

  const q=questions[index];
  const options=parse<{key:string;label:string}[]>(q.options||'[]');

  async function answer(value:string|object){
    const serialized=typeof value==='string'?value:JSON.stringify(value);
    if(!serialized.trim()) return;
    setSelected(serialized);
    const response=await fetch('/api/v1/learning/questions/'+q.id+'/answer',{
      method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({answer:value})
    });
    setResult(await response.json());
  }

  const next=()=>{
    if(index+1<questions.length){setIndex(index+1);setSelected('');setResult(null);}
  };

  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span>Practice · {index+1} / {questions.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow">Class {student?.class_code ?? '—'} · {q.question_type}</div>
      <div className="feedback">
        <span>{q.exam_format ?? 'Concept practice'}{q.marks ? ' · '+q.marks+' marks' : ''}</span>
        <span>{q.source_kind==='TEXTBOOK_ALIGNED'?<LocaleText hinglish="SCERT से aligned question" english="Question aligned with SCERT" />:q.source_kind ?? <LocaleText hinglish="Content team का question" english="Content team question" />}</span>
        {q.source_year ? <span>{q.source_year}</span>:null}
      </div>
      <h1>{q.prompt}</h1>
      <p><LocaleText hinglish="पहले सोचें, answer दें, फिर feedback पढ़कर अपनी reasoning check करें।" english="Think first, answer, then use the feedback to check your reasoning." /></p>

      {options.length>0 ? <div className="option-grid">
        {options.map(o=><button key={o.key} disabled={!!result} className={selected===o.key?'selected':''} onClick={()=>answer(o.key)}>{o.key}. {o.label}</button>)}
      </div> : q.question_type==='ORDER' ? <OrderInput prompt={q.prompt} disabled={!!result} onSubmit={answer}/> :
      q.question_type==='MATCH' ? <MatchInput disabled={!!result} onSubmit={answer}/> :
      <div className="concept-card">
        {q.question_type==='NUMERICAL'
          ? <input inputMode="decimal" value={selected} onChange={e=>setSelected(e.target.value)} placeholder="Number wala answer yahan likhein…"/>
          : <textarea value={selected} onChange={e=>setSelected(e.target.value)} placeholder="Apna answer ya reasoning yahan likhein…" rows={q.question_type==='LONG_ANSWER'?7:5}/>}
        <button className="button button-dark" disabled={!selected.trim()||!!result} onClick={()=>answer(selected)}><LocaleText hinglish="Answer submit करें" english="Submit answer" /></button>
      </div>}

      {result&&<div className="practice-feedback">
        <strong>{result.correct===true?<LocaleText hinglish="✓ सही जवाब" english="✓ Correct answer" />:result.correct===false?<LocaleText hinglish="अभी सही नहीं" english="Not quite yet" />:<LocaleText hinglish="Answer save हो गया" english="Answer saved" />}</strong>
        <span>{result.feedback}</span>
        {result.explanation&&<span>{result.explanation}</span>}
      </div>}

      <div className="practice-footer">
        <span>Question {index+1} of {questions.length}</span>
        {result && index+1<questions.length ? <button className="button button-dark" onClick={next}><LocaleText hinglish="अगला question →" english="Next question →" /></button>
          : result ? <Link href="/student/progress" className="button button-dark"><LocaleText hinglish="Progress देखें →" english="View progress →" /></Link>
          : <span>उत्तर दें</span>}
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
