'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';

type Lesson = { id:number; code:string; title:string; summary:string; estimated_minutes:number; chapter_name:string; subject_name:string };
type Detail = Lesson & { blocks:{sequence_no:number;block_type:string;content:string}[]; questions:{id:number;question_type:string;prompt:string;explanation:string;options:string}[] };

function parse(value:string) {
  try { return JSON.parse(value); } catch { return {}; }
}

export default function LearnPage() {
  const [lesson, setLesson] = useState<Detail|null>(null);
  const [answer, setAnswer] = useState<string|null>(null);
  const [help, setHelp] = useState('none');
  const [error, setError] = useState('');

  useEffect(() => {
    async function load() {
      try {
        const list = await fetch('/api/v1/learning/lessons?classCode=7&subjectCode=maths').then(r=>r.json()) as Lesson[];
        const target = list.find(x => x.code === 'simple-equations-foundation') ?? list[0];
        const detail = await fetch(`/api/v1/learning/lessons/${target.id}`).then(r=>r.json()) as Detail;
        setLesson(detail);
      } catch { setError('Lesson load नहीं हो पाया। API health और Docker services जाँचें।'); }
    }
    load();
  }, []);

  if (error) return <main className="lesson-page"><section className="lesson-wrap"><div className="auth-card"><h1>Lesson unavailable</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  if (!lesson) return <main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow">Loading lesson…</div></section></main>;

  const firstQuestion = lesson.questions[0];
  const options = firstQuestion ? parse(firstQuestion.options) as {key:string;label:string;correct?:boolean}[] : [];
  const correct = options.find(o=>o.correct)?.key;
  const blocks = lesson.blocks.map(b=>({...b, data:parse(b.content)}));

  return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span className="lesson-progress">Published curriculum · {lesson.estimated_minutes} min</span><span className="avatar">अ</span></header>
    <section className="lesson-wrap">
      <div className="lesson-meta"><span className="eyebrow">कक्षा 7 · {lesson.subject_name} · {lesson.chapter_name}</span><span>~{lesson.estimated_minutes} min</span></div>
      <h1>{lesson.title}</h1>
      <p className="lesson-intro">{lesson.summary}</p>

      {blocks.map((block:any)=>block.block_type==='EXPLANATION' ? <div className="concept-card" key={block.sequence_no}>
        <span className="concept-kicker">{block.data.heading}</span>
        <p>{block.data.body}</p>
        {block.data.keyPoints?.map((x:string)=><div className="feedback" key={x}><span>• {x}</span></div>)}
      </div> : block.block_type==='CHALLENGE' ? <div className="concept-card" key={block.sequence_no}>
        <span className="concept-kicker">{block.data.title}</span><p>{block.data.prompt}</p><div className="feedback"><span>{block.data.hint}</span></div>
      </div> : null)}

      {firstQuestion && <div className="concept-card">
        <span className="concept-kicker">Check your understanding</span>
        <p>{firstQuestion.prompt}</p>
        <div className="answer-row">{options.map(o=><button key={o.key} className={answer===o.key && o.key===correct?'correct':''} onClick={()=>setAnswer(o.key)}>{o.label}</button>)}</div>
        {answer && <div className="feedback"><b>{answer===correct?'बहुत बढ़िया! ✓':'एक बार फिर सोचें'}</b><span>{firstQuestion.explanation}</span></div>}
      </div>}

      <div className="ai-help"><div className="ai-icon">✦</div><div><strong>QuantaEdge help</strong><p>{help==='easy'?'इस concept को बहुत आसान भाषा में एक छोटे उदाहरण के साथ समझेंगे।':help==='example'?'अपने आसपास की किसी स्थिति से यही concept जोड़कर देखें।':help==='steps'?'पहले जानकारी पहचानें → नियम चुनें → कदम लागू करें → उत्तर जाँचें।':'तुरंत answer देने के बजाय hint से सोचने में मदद करेंगे।'}</p><div className="hint-row"><button onClick={()=>setHelp('easy')}>आसान भाषा में समझाओ</button><button onClick={()=>setHelp('example')}>Example से समझाओ</button><button onClick={()=>setHelp('steps')}>Step-by-step</button></div></div></div>
      <div className="lesson-next"><span>Next: Practice</span><Link href="/student/practice" className="button button-dark button-small">Practice खोलें →</Link></div>
    </section>
  </main>;
}
