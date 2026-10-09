'use client';


import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import TutorDock from './TutorDock';

type Lesson = {
  id:number; code:string; title:string; summary:string; estimated_minutes:number;
  chapter_name:string; subject_name:string; subject_code:string; chapter_code:string;
};
type Question = {
  id:number; question_type:string; prompt:string; explanation:string; options:string;
  source_kind?:string; source_year?:number; board?:string; marks?:number; exam_format?:string;
  topic?:string; subtopic?:string; skill?:string; response_mode?:string;
};
type Detail = Lesson & {
  class_code:string; class_name:string;
  blocks:{id:number;sequence_no:number;block_type:string;content:string}[];
  questions:Question[];
};

function parse<T=any>(value:string):T {
  try { return JSON.parse(value) as T; } catch { return {} as T; }
}

function Block({ block, onTutorOpen }:{block:Detail['blocks'][number];onTutorOpen:()=>void}) {
  const data=parse<any>(block.content);
  if (block.block_type==='EXPLANATION' || block.block_type==='PREREQUISITE') return <div className="concept-card">
    <span className="concept-kicker">{data.heading ?? data.title ?? 'समझें'}</span>
    <p>{data.body ?? data.description}</p>
    {data.keyPoints?.map((x:string)=><div className="feedback" key={x}><span>• {x}</span></div>)}
  </div>;

  if (block.block_type==='WORKED_EXAMPLE') return <div className="concept-card">
    <span className="concept-kicker">Worked example</span>
    <h3>{data.title ?? 'उदाहरण'}</h3>
    <p>{data.problem ?? data.prompt}</p>
    {data.steps?.map((x:string,i:number)=><div className="feedback" key={i}><span>{i+1}. {x}</span></div>)}
    {data.answer && <p><strong>उत्तर:</strong> {data.answer}</p>}
  </div>;

  if (['GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE'].includes(block.block_type)) return <div className="concept-card">
    <span className="concept-kicker">{data.title ?? (block.block_type==='GUIDED_PRACTICE'?'साथ में करें':'अब खुद करें')}</span>
    <p>{data.prompt}</p>
    {data.hint && <div className="feedback"><span>Hint: {data.hint}</span></div>}
  </div>;

  if (['IMAGE','DIAGRAM','VIDEO'].includes(block.block_type)) return <div className="concept-card">
    <span className="concept-kicker">{data.title ?? 'Visual'}</span>
    <div className="feedback"><span>◈</span><span>{data.description ?? data.alt ?? 'इस concept का labelled visual देखें।'}</span></div>
    {data.url && <a href={data.url} target="_blank" rel="noreferrer" className="button button-small">Visual देखें ↗</a>}
    {data.caption && <p>{data.caption}</p>}
  </div>;

  if (block.block_type==='AI_HELP') return <div className="ai-help">
    <div className="ai-icon">✦</div>
    <div><strong>AI tutor</strong>
      <p>अटकें तो इस lesson के context में hint, explanation, example या step-by-step मदद लें।</p>
      <button type="button" className="button button-dark button-small" onClick={onTutorOpen}>Tutor खोलें →</button>
    </div>
  </div>;

  if (block.block_type==='SUMMARY' || block.block_type==='RECAP') return <div className="concept-card">
    <span className="concept-kicker">Recap</span>
    {data.points?.map((x:string)=><div className="feedback" key={x}><span>✓ {x}</span></div>)}
  </div>;

  return null;
}

function QuestionCard({q,onResult,onTutorOpen}:{q:Question;onResult:(id:number,result:any)=>void;onTutorOpen:(id:number)=>void}) {
  const [value,setValue]=useState('');
  const [busy,setBusy]=useState(false);
  const [result,setResult]=useState<any>(null);
  const options=parse<{key:string;label:string}[]>(q.options||'[]');

  async function submit(next:string) {
    setValue(next);
    if (!next.trim()) return;
    setBusy(true);
    try {
      const response=await fetch('/api/v1/learning/questions/'+q.id+'/answer',{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({answer:next})
      });
      const body=await response.json();
      setResult(body);
      onResult(q.id,body);
    } finally { setBusy(false); }
  }

  return <article className="concept-card">
    <div className="lesson-meta">
      <span className="concept-kicker">{q.question_type} · {q.exam_format ? q.exam_format : 'Practice'}{q.marks ? ' · '+q.marks+' marks' : ''}</span>
      <span>{q.source_kind === 'TEXTBOOK_ALIGNED' ? 'SCERT-aligned author question' : q.source_kind ?? 'Author-created'}</span>
    </div>
    <p><strong>{q.prompt}</strong></p>
    {options.length>0 ? <div className="answer-row">
      {options.map(o=><button key={o.key} disabled={busy} className={value===o.key?'selected':''} onClick={()=>submit(o.key)}>{o.key}. {o.label}</button>)}
    </div> : <div>
      {q.response_mode==='structured-text' || q.question_type==='LONG_ANSWER' || q.question_type==='SHORT_ANSWER' ?
        <textarea value={value} onChange={e=>setValue(e.target.value)} placeholder="अपना reasoning/उत्तर यहाँ लिखें…" rows={q.question_type==='LONG_ANSWER'?6:4}/> :
        <input value={value} onChange={e=>setValue(e.target.value)} placeholder="उत्तर लिखें…"/>}
      <div style={{display:'flex',gap:8,alignItems:'center'}}><button type="button" className="button button-dark button-small" disabled={busy || !value.trim()} onClick={()=>submit(value)}>उत्तर जाँचें</button><button type="button" className="text-link" onClick={()=>onTutorOpen(q.id)}>✦ Tutor</button></div>
    </div>}
    {result && <div className="feedback">
      <b>{result.correct===true?'✓ सही':result.correct===false?'अभी सही नहीं':'उत्तर दर्ज है'}</b>
      <span>{result.feedback}</span>
      {result.explanation && <span>{result.explanation}</span>}
    </div>}
  </article>;
}

export default function LearnClient() {
  const [lessons,setLessons]=useState<Lesson[]>([]);
  const [lesson,setLesson]=useState<Detail|null>(null);
  const [help,setHelp]=useState('none');
  const [tutorOpen,setTutorOpen]=useState(false);
  const [tutorQuestionId,setTutorQuestionId]=useState<number|undefined>(undefined);
  const [error,setError]=useState('');
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [sessionStarted,setSessionStarted]=useState<number|null>(null);
  const router=useRouter();
  const searchParams=useSearchParams();

  const currentIndex=useMemo(()=>lesson ? lessons.findIndex(x=>x.id===lesson.id) : -1,[lesson,lessons]);

  async function loadLesson(id:number) {
    const detail=await fetch('/api/v1/learning/lessons/'+id).then(r=>{
      if(!r.ok) throw new Error('lesson');
      return r.json();
    }) as Detail;
    setLesson(detail);
  }

  useEffect(()=>{
    async function load(){
      try{
        const me=await fetch('/api/v1/students/me');
        if(me.status===401||me.status===403){router.replace('/login');return;}
        const student=await me.json();
        if(!me.ok) throw new Error(student.message||'Student unavailable');
        const requestedId=Number(searchParams.get('lessonId')||0);
        let targetId=requestedId;
        if(!targetId){
          const rec=await fetch('/api/v1/recommendations/next');
          const rb=await rec.json();
          if(rec.ok&&rb.kind==='DIAGNOSTIC'){router.replace('/student/diagnostic');return;}
          if(rec.ok&&rb.available&&rb.lesson) targetId=Number(rb.lesson.id);
        }
        if(!targetId) throw new Error('No recommendation available');
        const detail=await fetch('/api/v1/learning/lessons/'+targetId);
        if(!detail.ok) throw new Error('lesson');
        const d=await detail.json() as Detail;
        const list=await fetch('/api/v1/learning/lessons?classCode='+student.class_code+'&subjectCode='+d.subject_code).then(r=>r.json()) as Lesson[];
        setLessons(list);
        await fetch('/api/v1/learning/lessons/'+targetId+'/start',{method:'POST'});
        const sr=await fetch('/api/v1/learning/sessions/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source:'LESSON'})});
        if(sr.ok){const sb=await sr.json();setSessionId(Number(sb.sessionId));setSessionStarted(Date.now());}
        setLesson(d);
      }catch(e:any){setError(e.message==='Student unavailable'?'Student login required':'Lesson load नहीं हो पाया।');}
    }
    load();
  },[router,searchParams]);

  useEffect(()=>{
    return ()=>{
      if(sessionId && sessionStarted){
        const minutes=Math.max(0,Math.min(240,Math.round((Date.now()-sessionStarted)/60000)));
        void fetch('/api/v1/learning/sessions/'+sessionId+'/end',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({minutes}),keepalive:true});
      }
    };
  },[sessionId,sessionStarted]);

  if(error) return <main className="lesson-page"><section className="lesson-wrap"><div className="auth-card"><h1>Lesson unavailable</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  if(!lesson) return <main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow">Loading lesson…</div></section></main>;

  return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span className="lesson-progress">Published curriculum · {lesson.estimated_minutes} min</span><span className="avatar">अ</span></header>
    <section className="lesson-wrap">
      <div className="lesson-meta">
        <span className="eyebrow">कक्षा {lesson.class_code} · {lesson.subject_name} · {lesson.chapter_name}</span>
        <span>Lesson {currentIndex+1} / {lessons.length}</span>
      </div>
      <h1>{lesson.title}</h1>
      <p className="lesson-intro">{lesson.summary}</p>
      <div className="feedback"><span>Learning path</span><span>पूर्व ज्ञान → explanation → worked example → guided → independent → assessment → recap</span></div>

      {lesson.blocks.map(block=><Block key={block.id} block={block} onTutorOpen={()=>setTutorOpen(true)}/>) }
      {help !== 'none' && <div className="feedback"><b>{help.replaceAll('_',' ')} सहायता</b><span>पहले concept को अपने शब्दों में समझें, फिर example देखकर नया प्रयास करें।</span></div>}

      <div className="content-heading"><h2>इस lesson के सभी प्रश्न</h2><span>{lesson.questions.length} questions</span></div>
      {lesson.questions.map(q=><QuestionCard key={q.id} q={q} onResult={()=>{}} onTutorOpen={id=>{setTutorQuestionId(id);setTutorOpen(true)}}/>)}

      <button type="button" className="tutor-launch" onClick={()=>setTutorOpen(true)} aria-label="AI tutor खोलें">✦ <span>AI Tutor</span></button>
      <TutorDock lessonId={lesson.id} open={tutorOpen} onClose={()=>setTutorOpen(false)} currentQuestionId={tutorQuestionId} />

      <div className="lesson-next">
        {currentIndex>0 ? <button className="button button-small" onClick={()=>loadLesson(lessons[currentIndex-1].id)}>← पिछला</button> : <span/>}
        {currentIndex>=0 && currentIndex<lessons.length-1
          ? <button className="button button-dark button-small" onClick={()=>loadLesson(lessons[currentIndex+1].id)}>अगला lesson →</button>
          : <Link href="/student/practice" className="button button-dark button-small">Practice खोलें →</Link>}
      </div>
    </section>
  </main>;
}
