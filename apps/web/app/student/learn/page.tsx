'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';

type Lesson = { id:number; code:string; title:string; summary:string; estimated_minutes:number; chapter_name:string; subject_name:string };
type Detail = Lesson & { blocks:{sequence_no:number;block_type:string;content:string}[]; questions:{id:number;question_type:string;prompt:string;explanation:string;options:string;source_kind?:string;exam_format?:string}[] };

function parse(value:string) {
  try { return JSON.parse(value); } catch { return {}; }
}

function Block({ block, onHelp }:{block:any;onHelp:(kind:string)=>void}) {
  const data=parse(block.content);
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
    {data.url ? <a href={data.url} target="_blank" rel="noreferrer" className="button button-small">Visual देखें ↗</a> : <p>{data.alt ?? data.description ?? 'इस concept के लिए visual representation आवश्यक नहीं है।'}</p>}
    {data.caption && <p>{data.caption}</p>}
  </div>;
  if (block.block_type==='AI_HELP') return <div className="ai-help"><div className="ai-icon">✦</div><div><strong>{data.title ?? 'QuantaEdge help'}</strong><p>अटकें तो सीधे answer नहीं—पहले hint, फिर आसान explanation, example और step-by-step मदद मिलेगी।</p><div className="hint-row">{(data.actions??['EASY_EXPLANATION','EXAMPLE','STEP_BY_STEP']).map((x:string)=><button key={x} onClick={()=>onHelp(x)}>{x.replaceAll('_',' ')}</button>)}</div></div></div>;
  if (block.block_type==='SUMMARY' || block.block_type==='RECAP') return <div className="concept-card"><span className="concept-kicker">Recap</span>{data.points?.map((x:string)=><div className="feedback" key={x}><span>✓ {x}</span></div>)}</div>;
  return null;
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
        if (!target) throw new Error('No published lesson');
        const detail = await fetch(`/api/v1/learning/lessons/${target.id}`).then(r=>r.json()) as Detail;
        setLesson(detail);
      } catch { setError('Lesson load नहीं हो पाया। API health और Docker services जाँचें।'); }
    }
    load();
  }, []);

  if (error) return <main className="lesson-page"><section className="lesson-wrap"><div className="auth-card"><h1>Lesson unavailable</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  if (!lesson) return <main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow">Loading lesson…</div></section></main>;

  const firstQuestion = lesson.questions[0];
  const options = firstQuestion ? parse(firstQuestion.options) as {key:string;label:string}[] : [];

  async function submit(value:string) {
    setAnswer(value);
    if (!firstQuestion) return;
    await fetch(`/api/v1/learning/questions/${firstQuestion.id}/answer`, {
      method:'POST', headers:{'Content-Type':'application/json'}, body:JSON.stringify({answer:value})
    });
  }

  return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span className="lesson-progress">Published curriculum · {lesson.estimated_minutes} min</span><span className="avatar">अ</span></header>
    <section className="lesson-wrap">
      <div className="lesson-meta"><span className="eyebrow">कक्षा 7 · {lesson.subject_name} · {lesson.chapter_name}</span><span>~{lesson.estimated_minutes} min</span></div>
      <h1>{lesson.title}</h1>
      <p className="lesson-intro">{lesson.summary}</p>

      {lesson.blocks.map(block=><Block key={block.sequence_no} block={block} onHelp={setHelp}/>)}

      {firstQuestion && <div className="concept-card">
        <span className="concept-kicker">Check your understanding · {firstQuestion.question_type}</span>
        <p>{firstQuestion.prompt}</p>
        <div className="answer-row">{options.map(o=><button key={o.key} className={answer===o.key?'selected':''} onClick={()=>submit(o.key)}>{o.label}</button>)}</div>
        {answer && <div className="feedback"><b>उत्तर दर्ज हो गया ✓</b><span>अगले चरण में feedback और mastery rule लागू होगा।</span></div>}
      </div>}

      <div className="lesson-next"><span>Next: Practice</span><Link href="/student/practice" className="button button-dark button-small">Practice खोलें →</Link></div>
    </section>
  </main>;
}
