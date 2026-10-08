'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';

type Question={id:number;prompt:string;explanation:string;options:string};
function parse(v:string){try{return JSON.parse(v)}catch{return[]}}

export default function PracticePage(){
  const [questions,setQuestions]=useState<Question[]>([]);
  const [index,setIndex]=useState(0);
  const [selected,setSelected]=useState<string|null>(null);
  const [error,setError]=useState('');

  useEffect(()=>{
    async function load(){
      try{
        const list=await fetch('/api/v1/learning/lessons?classCode=7&subjectCode=maths').then(r=>r.json());
        const target=list.find((x:any)=>x.code==='simple-equations-foundation')??list[0];
        const qs=await fetch(`/api/v1/learning/lessons/${target.id}/questions`).then(r=>r.json());
        setQuestions(qs);
      }catch{setError('Practice load नहीं हो पाया।');}
    }
    load();
  },[]);

  if(error)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1>Practice unavailable</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  if(!questions.length)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow">Loading practice…</div></section></main>;

  const q=questions[index];
  const options=parse(q.options) as {key:string;label:string}[];
  const correct=options[0]?.key;
  const answered=selected!==null;
  const isCorrect=selected===correct;

  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span>Practice · {index+1} / {questions.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap"><div className="eyebrow">Class 7 · Maths · Real curriculum question</div><h1>{q.prompt}</h1><p>पहले सोचो। फिर विकल्प चुनो और feedback देखो।</p>
      <div className="option-grid">{options.map(o=><button key={o.key} className={selected===o.key?'selected':''} onClick={()=>setSelected(o.key)}>{o.label}</button>)}</div>
      {answered&&<div className="practice-feedback"><strong>{isCorrect?'✓ सही जवाब':'अभी नहीं'}</strong><span>{q.explanation}</span></div>}
      <div className="practice-footer"><span>Question {index+1} of {questions.length}</span>{answered && index+1<questions.length ? <button className="button button-dark" onClick={()=>{setIndex(index+1);setSelected(null)}}>अगला सवाल →</button> : answered ? <Link href="/student/progress" className="button button-dark">Progress देखें →</Link> : <span>Option चुनें</span>}</div>
    </section>
  </main>;
}
