'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type Q={id:number;question_type:string;prompt:string;explanation:string;options:string};

export default function DiagnosticPage(){
  const router=useRouter();
  const [qs,setQs]=useState<Q[]>([]);
  const [idx,setIdx]=useState(0);
  const [value,setValue]=useState('');
  const [result,setResult]=useState<any>(null);
  const [session,setSession]=useState<number|null>(null);
  const [started,setStarted]=useState<number|null>(null);
  const [error,setError]=useState('');

  useEffect(()=>{
    (async()=>{
      const r=await fetch('/api/v1/diagnostic');
      if(r.status===401||r.status===403){router.replace('/login');return;}
      const b=await r.json();
      if(!r.ok){setError(b.message||'Diagnostic load nahi ho paaya');return;}
      if(b.completed){router.replace('/student');return;}
      setQs(b.questions||[]);
      const sr=await fetch('/api/v1/learning/sessions/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source:'DIAGNOSTIC'})});
      if(sr.ok){const sb=await sr.json();setSession(Number(sb.sessionId));setStarted(Date.now());}
    })().catch(e=>setError(e.message||'Diagnostic load nahi ho paaya'));
  },[router]);

  useEffect(()=>()=>{if(session&&started){
    const minutes=Math.max(0,Math.min(240,Math.round((Date.now()-started)/60000)));
    void fetch('/api/v1/learning/sessions/'+session+'/end',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({minutes}),keepalive:true});
  }},[session,started]);

  if(error)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1>Diagnostic load nahi ho paaya</h1><p>{error}</p><Link href="/student" className="button button-dark">← वापस</Link></div></section></main>;
  if(!qs.length)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow">Initial diagnostic ready ho raha hai…</div></section></main>;

  const q=qs[idx];
  let options:any[]=[];
  try{options=JSON.parse(q.options||'[]')}catch{}

  async function answer(v:string){
    setValue(v);
    const r=await fetch('/api/v1/learning/questions/'+q.id+'/answer',{
      method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({answer:v})
    });
    const b=await r.json();
    if(!r.ok){setError(b.message||'Answer failed');return;}
    setResult(b);
  }

  function next(){
    if(idx+1<qs.length){setIdx(idx+1);setValue('');setResult(null);}
    else router.replace('/student');
  }

  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span>Initial diagnostic · {idx+1} / {qs.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow">Pehle diagnostic, phir aapke hisaab se learning</div>
      <h1>{q.prompt}</h1>
      <p>Yeh chhota assessment aapki starting learning recommendation banane mein help karta hai.</p>
      <div className="option-grid">{options.map(o=><button key={o.key} className={value===o.key?'selected':''} disabled={!!result} onClick={()=>answer(o.key)}>{o.key}. {o.label}</button>)}</div>
      {result&&<div className="practice-feedback"><strong>{result.correct===true?'✓ सही':'Agla attempt humein aapki learning samajhne mein help karega'}</strong><span>{result.feedback}</span></div>}
      {result&&<div className="practice-footer"><span>{idx+1} / {qs.length}</span><button className="button button-dark" onClick={next}>{idx+1<qs.length?'अगला →':'Learning journey shuru karein →'}</button></div>}
    </section>
  </main>;
}
