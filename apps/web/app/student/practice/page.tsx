
'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';

type Question={
  id:number;question_type:string;prompt:string;explanation:string;options:string;
  source_kind?:string;source_year?:number;board?:string;marks?:number;exam_format?:string;
  topic?:string;subtopic?:string;skill?:string;response_mode?:string
};
function parse<T=any>(v:string):T{try{return JSON.parse(v) as T}catch{return [] as T}}

export default function PracticePage(){
  const [questions,setQuestions]=useState<Question[]>([]);
  const [index,setIndex]=useState(0);
  const [selected,setSelected]=useState('');
  const [result,setResult]=useState<any>(null);
  const [error,setError]=useState('');

  useEffect(()=>{
    async function load(){
      try{
        const list=await fetch('/api/v1/learning/lessons?classCode=7&subjectCode=maths').then(r=>r.json());
        const target=list.find((x:any)=>x.code==='simple-equations-guided')??list[0];
        if(!target) throw new Error('No published lesson');
        const qs=await fetch('/api/v1/learning/lessons/'+target.id+'/questions').then(r=>r.json());
        setQuestions(qs);
      }catch{setError('Practice load नहीं हो पाया।');}
    }
    load();
  },[]);

  if(error)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1>Practice unavailable</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  if(!questions.length)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow">Loading practice…</div></section></main>;

  const q=questions[index];
  const options=parse<{key:string;label:string}[]>(q.options||'[]');

  async function answer(value:string){
    if(!value.trim()) return;
    setSelected(value);
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
      <div className="eyebrow">Class 7 · Maths · {q.question_type}</div>
      <div className="feedback">
        <span>{q.exam_format ?? 'Concept practice'}{q.marks ? ' · '+q.marks+' marks' : ''}</span>
        <span>{q.source_kind==='TEXTBOOK_ALIGNED'?'SCERT-aligned author question':q.source_kind ?? 'Author-created'}</span>
        {q.source_year ? <span>{q.source_year}</span>:null}
      </div>
      <h1>{q.prompt}</h1>
      <p>पहले सोचो। उत्तर दो। फिर feedback पढ़कर reasoning जाँचो।</p>

      {options.length>0 ? <div className="option-grid">
        {options.map(o=><button key={o.key} className={selected===o.key?'selected':''} onClick={()=>answer(o.key)}>{o.key}. {o.label}</button>)}
      </div> : <div className="concept-card">
        {q.response_mode==='structured-text' || q.question_type==='LONG_ANSWER' || q.question_type==='SHORT_ANSWER'
          ? <textarea value={selected} onChange={e=>setSelected(e.target.value)} placeholder="अपना reasoning/उत्तर लिखें…" rows={q.question_type==='LONG_ANSWER'?7:5}/>
          : <input value={selected} onChange={e=>setSelected(e.target.value)} placeholder="उत्तर लिखें…"/>}
        <button className="button button-dark" disabled={!selected.trim()} onClick={()=>answer(selected)}>उत्तर जमा करें</button>
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
