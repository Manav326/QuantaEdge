
'use client';

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

  useEffect(()=>{
    async function load(){
      try{
        const me=await fetch('/api/v1/students/me');
        if(me.status===401||me.status===403){router.replace('/login');return;}
        const studentData=await me.json(); if(!me.ok) throw new Error(studentData.message||'Student unavailable');
        setStudent(studentData);
        const rec=await fetch('/api/v1/recommendations/next'); const rb=await rec.json();
        if(rec.ok&&rb.kind==='DIAGNOSTIC'){router.replace('/student/diagnostic');return;}
        if(!rec.ok||!rb.available||!rb.lesson) throw new Error('No recommended lesson');
        const qs=await fetch('/api/v1/learning/lessons/'+rb.lesson.id+'/questions').then(r=>r.json());
        setQuestions(qs);
      }catch(e:any){setError(e.message||'Practice load नहीं हो पाया।');}
    }
    load();
  },[router]);

  if(error)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1>Practice unavailable</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  if(!questions.length)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow">Loading practice…</div></section></main>;

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
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span>Practice · {index+1} / {questions.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow">Class {student?.class_code ?? '—'} · {q.question_type}</div>
      <div className="feedback">
        <span>{q.exam_format ?? 'Concept practice'}{q.marks ? ' · '+q.marks+' marks' : ''}</span>
        <span>{q.source_kind==='TEXTBOOK_ALIGNED'?'SCERT-aligned author question':q.source_kind ?? 'Author-created'}</span>
        {q.source_year ? <span>{q.source_year}</span>:null}
      </div>
      <h1>{q.prompt}</h1>
      <p>पहले सोचो। उत्तर दो। फिर feedback पढ़कर reasoning जाँचो।</p>

      {options.length>0 ? <div className="option-grid">
        {options.map(o=><button key={o.key} disabled={!!result} className={selected===o.key?'selected':''} onClick={()=>answer(o.key)}>{o.key}. {o.label}</button>)}
      </div> : q.question_type==='ORDER' ? <OrderInput prompt={q.prompt} disabled={!!result} onSubmit={answer}/> :
      q.question_type==='MATCH' ? <MatchInput disabled={!!result} onSubmit={answer}/> :
      <div className="concept-card">
        {q.question_type==='NUMERICAL'
          ? <input inputMode="decimal" value={selected} onChange={e=>setSelected(e.target.value)} placeholder="संख्यात्मक उत्तर लिखें…"/>
          : <textarea value={selected} onChange={e=>setSelected(e.target.value)} placeholder="अपना reasoning/उत्तर लिखें…" rows={q.question_type==='LONG_ANSWER'?7:5}/>}
        <button className="button button-dark" disabled={!selected.trim()||!!result} onClick={()=>answer(selected)}>उत्तर जमा करें</button>
      </div>}

      {result&&<div className="practice-feedback">
        <strong>{result.correct===true?'✓ सही जवाब':result.correct===false?'अभी नहीं':'उत्तर दर्ज है'}</strong>
        <span>{result.feedback}</span>
        {result.explanation&&<span>{result.explanation}</span>}
      </div>}

      <div className="practice-footer">
        <span>Question {index+1} of {questions.length}</span>
        {result && index+1<questions.length ? <button className="button button-dark" onClick={next}>अगला सवाल →</button>
          : result ? <Link href="/student/progress" className="button button-dark">Progress देखें →</Link>
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
  return <div className="concept-card structured-card"><div className="structured-hint">क्रम चुनें: हर विकल्प पर एक बार टैप करें।</div><div className="structured-chips">{letters.map(x=><button disabled={disabled||order.includes(x)} key={x} onClick={()=>pick(x)}>{x}</button>)}</div><div className="structured-answer">{order.length?order.join(' → '):'अभी कोई क्रम नहीं चुना'}</div><div className="practice-footer"><button className="text-link" onClick={reset}>Reset</button><button className="button button-dark" disabled={disabled||!order.length} onClick={()=>onSubmit(order)}>क्रम जमा करें</button></div></div>;
}

function MatchInput({disabled,onSubmit}:{disabled:boolean;onSubmit:(value:string|object)=>void}){
  const [text,setText]=useState('');
  function submit(){
    const mapping=text.split(',').map(s=>s.trim()).filter(Boolean).reduce((acc,item)=>{
      const [k,v]=item.split(':').map(x=>x.trim()); if(k&&v) (acc as any)[k]=v; return acc;
    },{} as Record<string,string>);
    if(Object.keys(mapping).length) onSubmit(mapping);
  }
  return <div className="concept-card structured-card"><div className="structured-hint">मिलान लिखें: <b>1:A, 2:B, 3:C</b></div><input disabled={disabled} value={text} onChange={e=>setText(e.target.value)} placeholder="1:A, 2:B, 3:C"/><button className="button button-dark" disabled={disabled||!text.trim()} onClick={submit}>मिलान जमा करें</button></div>;
}
