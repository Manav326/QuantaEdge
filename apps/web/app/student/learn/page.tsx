'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';

type Lesson = {id:number;code:string;title:string;summary:string;estimated_minutes:number;chapter_code:string;chapter_name:string;subject_name:string;class_code:string;subject_code:string;status?:string};
type Block = {sequence_no:number;block_type:string;content:string;active?:boolean};
type Option = {key:string;label:string;correct?:boolean};
type Question = {id:number;question_type:string;prompt:string;explanation:string;options:string};
type Detail = Lesson & {blocks:Block[];questions:Question[]};
function parse(value:unknown,fallback:any={}) { if(typeof value!=='string') return value??fallback; try{return JSON.parse(value)}catch{return fallback} }
const labels:Record<string,string>={maths:'गणित',science:'विज्ञान'};

export default function LearnPage(){
  const [lessons,setLessons]=useState<Lesson[]>([]);
  const [lesson,setLesson]=useState<Detail|null>(null);
  const [classCode,setClassCode]=useState('7');
  const [subjectCode,setSubjectCode]=useState('maths');
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [answer,setAnswer]=useState<string|null>(null);
  const [help,setHelp]=useState('none');
  useEffect(()=>{
    let cancelled=false;
    async function load(){
      try{
        const params=new URLSearchParams(window.location.search);
        let cls=params.get('classCode')||'',subject=params.get('subjectCode')||'';
        if(!cls||!subject){
          const p=await fetch('/api/v1/students/preview',{cache:'no-store'});
          if(!p.ok)throw new Error('Student profile load नहीं हो पाया।');
          const profile=await p.json(); cls=cls||String(profile.class_code||'7'); subject=subject||'maths';
        }
        if(!['maths','science'].includes(subject))throw new Error('कृपया गणित या विज्ञान में से विषय चुनें।');
        if(!['6','7','8'].includes(cls))throw new Error('कक्षा 6, 7 या 8 चुनें।');
        const res=await fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(cls)+'&subjectCode='+encodeURIComponent(subject),{cache:'no-store'});
        if(!res.ok)throw new Error('Published lessons load नहीं हो पाए।');
        const list=await res.json() as Lesson[];
        if(cancelled)return;
        setClassCode(cls);setSubjectCode(subject);setLessons(list);
        const id=params.get('lessonId');
        if(id&&list.some(x=>String(x.id)===id)){
          const detailResponse=await fetch('/api/v1/learning/lessons/'+encodeURIComponent(id),{cache:'no-store'});
          if(!detailResponse.ok)throw new Error('यह lesson अभी published नहीं है।');
          const selected=await detailResponse.json() as Detail;
          if(!cancelled)setLesson(selected);
        }
        setError('');
      }catch(e){if(!cancelled)setError(e instanceof Error?e.message:'Learning content load नहीं हो पाया।')}
      finally{if(!cancelled)setLoading(false)}
    }
    void load(); return ()=>{cancelled=true};
  },[]);
  async function openLesson(item:Lesson){
    setLoading(true);setError('');setAnswer(null);
    try{
      const res=await fetch('/api/v1/learning/lessons/'+item.id,{cache:'no-store'});
      if(!res.ok)throw new Error('यह lesson अभी published नहीं है।');
      const data=await res.json() as Detail;setLesson(data);
      const params=new URLSearchParams(window.location.search);params.set('classCode',classCode);params.set('subjectCode',subjectCode);params.set('lessonId',String(item.id));
      window.history.replaceState(null,'',window.location.pathname+'?'+params.toString());
    }catch(e){setError(e instanceof Error?e.message:'Lesson load नहीं हो पाया।')}finally{setLoading(false)}
  }
  function backToLessons(){
    setLesson(null);setAnswer(null);const params=new URLSearchParams(window.location.search);params.delete('lessonId');params.set('classCode',classCode);params.set('subjectCode',subjectCode);
    window.history.replaceState(null,'',window.location.pathname+'?'+params.toString());
  }
  const grouped=Array.from(lessons.reduce((map,item)=>{
    const key=item.chapter_code||item.chapter_name,group=map.get(key)||{name:item.chapter_name,items:[] as Lesson[]};group.items.push(item);map.set(key,group);return map;
  },new Map<string,{name:string;items:Lesson[]}>()).values());
  const subjectName=lessons[0]?.subject_name||labels[subjectCode]||subjectCode;
  const question=lesson?.questions?.[0];
  const options=question?parse(question.options,[]) as Option[]:[];
  const correct=options.find(option=>option.correct)?.key;
  const blocks=lesson?.blocks.map(block=>({...block,data:parse(block.content,{}) as Record<string,any>}))||[];
  if(loading&&lessons.length===0&&!lesson)return <main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow">पाठ्यक्रम load हो रहा है…</div></section></main>;
  if(error&&lessons.length===0&&!lesson)return <main className="lesson-page"><section className="lesson-wrap"><div className="auth-card"><h1>पढ़ाई अभी उपलब्ध नहीं है</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back">← आज</Link><span className="lesson-progress">कक्षा {classCode} · {subjectName}</span><span className="avatar">अ</span></header>
    <section className="lesson-wrap track-wrap">
      <div className="track-toolbar"><div><span className="eyebrow">आपका learning track</span><h1 className="track-title">{subjectName}</h1><p className="lesson-intro">अध्यायों को क्रम से खोलें। केवल published content यहाँ दिखाई देगा।</p></div>
        <Link className="track-switch" href={'/student/learn?classCode='+encodeURIComponent(classCode)+'&subjectCode='+(subjectCode==='maths'?'science':'maths')}>{subjectCode==='maths'?'विज्ञान खोलें →':'गणित खोलें →'}</Link></div>
      {error&&<div className="track-alert" role="alert">{error}</div>}
      {!lesson&&<div className="track-lessons"><div className="content-heading"><h2>अध्याय और lessons</h2><span>{lessons.length} published lessons</span></div>
        {grouped.length===0?<div className="track-empty"><h3>इस track में अभी published lessons नहीं हैं</h3><p>Admin content library में इस कक्षा और विषय का content publish करने के बाद यहाँ आएगा।</p></div>:
          grouped.map((group,i)=><section className="chapter-group" key={group.name+'-'+i}><div className="chapter-group-heading"><span>{String(i+1).padStart(2,'0')}</span><div><h3>{group.name}</h3><small>{group.items.length} lessons</small></div></div><div className="chapter-lesson-list">
            {group.items.map((item,index)=><div className="chapter-lesson-row" key={item.id}><button type="button" className="chapter-lesson-open" onClick={()=>void openLesson(item)}><span className="lesson-number">{index+1}</span><span><strong>{item.title}</strong><small>{item.estimated_minutes} min · {item.summary}</small></span><b>→</b></button><Link className="lesson-practice-link" href={'/student/practice?classCode='+encodeURIComponent(classCode)+'&subjectCode='+encodeURIComponent(subjectCode)+'&lessonId='+item.id}>अभ्यास</Link></div>)}
          </div></section>)
        }</div>}
      {lesson&&<div className="lesson-detail-view"><button type="button" className="back-to-track" onClick={backToLessons}>← सभी अध्याय और lessons</button>
        <div className="lesson-meta"><span className="eyebrow">कक्षा {lesson.class_code} · {lesson.subject_name} · {lesson.chapter_name}</span><span>~{lesson.estimated_minutes} min</span></div>
        <h2 className="lesson-detail-title">{lesson.title}</h2><p className="lesson-intro">{lesson.summary}</p>
        {blocks.map(block=>{
          const data=block.data;
          const heading=data.heading||data.title||({
            EXPLANATION:'पहले समझें',CHALLENGE:'अब अभ्यास करें',SUMMARY:'आज का सारांश',
            IMAGE:'चित्र',DIAGRAM:'आरेख',VIDEO:'वीडियो',AI_HELP:'सीखने में मदद',
            QUESTION:'प्रश्न',MCQ:'बहुविकल्पीय प्रश्न',TRUE_FALSE:'सही या गलत',
            MATCH:'मिलान करें',ORDER:'सही क्रम',INPUT:'उत्तर लिखें',HINT:'संकेत'
          } as Record<string,string>)[block.block_type]||block.block_type;
          const imageSrc=data.url||data.src||data.imageUrl;
          const videoSrc=data.url||data.src||data.videoUrl;
          if(block.block_type==='EXPLANATION') return <div className="concept-card" key={block.sequence_no}><span className="concept-kicker">{heading}</span><p>{data.body||data.text||''}</p>{(data.keyPoints||[]).map((point:string)=><div className="feedback" key={point}><span>• {point}</span></div>)}</div>;
          if(block.block_type==='CHALLENGE') return <div className="concept-card" key={block.sequence_no}><span className="concept-kicker">{heading}</span><p>{data.prompt||data.body||''}</p>{data.hint&&<div className="feedback"><span>{data.hint}</span></div>}</div>;
          if(block.block_type==='SUMMARY') return <div className="concept-card" key={block.sequence_no}><span className="concept-kicker">{heading}</span>{(data.points||[]).map((point:string)=><p key={point}>{point}</p>)}</div>;
          if(block.block_type==='IMAGE') return <div className="concept-card" key={block.sequence_no}><span className="concept-kicker">{heading}</span>{imageSrc&&<img className="lesson-block-image" src={imageSrc} alt={data.alt||data.caption||heading}/>}{(data.caption||data.description||data.body)&&<p>{data.caption||data.description||data.body}</p>}</div>;
          if(block.block_type==='VIDEO') return <div className="concept-card" key={block.sequence_no}><span className="concept-kicker">{heading}</span>{videoSrc&&<video className="lesson-block-video" controls preload="metadata" src={videoSrc}>{'आपका browser वीडियो नहीं चला पा रहा है।'}</video>}{(data.caption||data.description||data.body)&&<p>{data.caption||data.description||data.body}</p>}</div>;
          if(block.block_type==='DIAGRAM') return <div className="concept-card" key={block.sequence_no}><span className="concept-kicker">{heading}</span>{(data.body||data.description||data.caption)&&<p>{data.body||data.description||data.caption}</p>}{(data.diagram||data.content)&&<pre className="lesson-diagram-text">{typeof (data.diagram||data.content)==='string'?(data.diagram||data.content):JSON.stringify(data.diagram||data.content,null,2)}</pre>}</div>;
          if(block.block_type==='AI_HELP') return <div className="ai-help" key={block.sequence_no}><div className="ai-icon">✦</div><div><strong>{heading}</strong><p>{data.body||data.description||'इस पाठ को समझने के लिए संकेत चुनें।'}</p>{Array.isArray(data.actions)&&<div className="hint-row">{data.actions.map((action:string)=><span className="lesson-action-chip" key={action}>{String(action).replace(/_/g,' ').toLowerCase()}</span>)}</div>}</div></div>;
          return <div className="concept-card" key={block.sequence_no}><span className="concept-kicker">{heading}</span>{(data.body||data.text||data.description)&&<p>{data.body||data.text||data.description}</p>}{data.prompt&&<p>{data.prompt}</p>}{Array.isArray(data.points)&&data.points.map((point:string)=><p key={point}>{point}</p>)}{Array.isArray(data.options)&&<div className="lesson-block-options">{data.options.map((option:any,index:number)=><span key={option.key||option.label||index}>{option.label||option.text||String(option)}</span>)}</div>}{data.url&&<a className="lesson-resource-link" href={data.url} target="_blank" rel="noreferrer">संबंधित सामग्री खोलें ↗</a>}</div>;
        })}
        {question&&<div className="concept-card"><span className="concept-kicker">अपनी समझ जाँचें</span><p>{question.prompt}</p><div className="answer-row">{options.map(option=><button type="button" key={option.key} className={answer===option.key&&option.key===correct?'correct':''} onClick={()=>setAnswer(option.key)}>{option.label}</button>)}</div>{answer&&<div className="feedback"><b>{answer===correct?'बहुत बढ़िया! ✓':'एक बार फिर सोचें'}</b><span>{question.explanation}</span></div>}</div>}
        <div className="ai-help"><div className="ai-icon">✦</div><div><strong>QuantaEdge help</strong><p>{help==='easy'?'इस concept को आसान भाषा में समझें।':help==='example'?'अपने आसपास के उदाहरण से concept जोड़ें।':help==='steps'?'पहले जानकारी पहचानें → तरीका चुनें → कदम करें → उत्तर जाँचें।':'तुरंत answer देने के बजाय hint से सोचने में मदद करेंगे।'}</p><div className="hint-row"><button type="button" onClick={()=>setHelp('easy')}>आसान भाषा</button><button type="button" onClick={()=>setHelp('example')}>Example</button><button type="button" onClick={()=>setHelp('steps')}>Step-by-step</button></div></div></div>
        <div className="lesson-next"><span>Practice · {lesson.chapter_name}</span><Link href={'/student/practice?classCode='+encodeURIComponent(classCode)+'&subjectCode='+encodeURIComponent(subjectCode)+'&lessonId='+lesson.id} className="button button-dark button-small">अभ्यास खोलें →</Link></div>
      </div>}
    </section>
  </main>;
}
