'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';


import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import TutorDock from './TutorDock';

type Lesson = {
  id:number; code:string; title:string; summary:string; estimated_minutes:number;
  chapter_name:string; subject_name:string; subject_code:string; chapter_code:string;
};
type TrackBrowse = {
  classCode:string; subjectCode:string; subjectName:string; lessons:Lesson[];
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

function safeRichHtml(value:string) {
  let html=String(value||'');
  html=html.replace(/<font\b([^>]*)>/gi,(_m,attrs:string)=>{
    const color=attrs.match(/\bcolor\s*=\s*["']?(#[0-9a-f]{3,8}|[a-z]+)["']?/i)?.[1];
    const size=attrs.match(/\bsize\s*=\s*["']?([1-7])["']?/i)?.[1];
    const sizes:Record<string,string>={'1':'12px','2':'14px','3':'16px','4':'20px','5':'24px','6':'30px','7':'36px'};
    const styles=[color?'color:'+color:null,size?'font-size:'+sizes[size]:null].filter(Boolean).join(';');
    return '<span'+(styles?' style="'+styles+'"':'')+'>';
  }).replace(/<\/font>/gi,'</span>');
  html=html.replace(/<\s*(script|style|iframe|object|embed|form|input|button)[\s\S]*?<\/\s*\1\s*>/gi,'');
  html=html.replace(/<\s*(script|style|iframe|object|embed|form|input|button)\b[^>]*\/?>/gi,'');
  html=html.replace(/\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi,'');
  html=html.replace(/\s+(href|src)\s*=\s*(['"])\s*(?:javascript|vbscript|data):[\s\S]*?\2/gi,'');
  html=html.replace(/<(?!\/?(?:p|div|span|br|strong|b|em|i|u|ul|ol|li|h2|h3|h4|blockquote|code|pre|a|img)\b)[^>]*>/gi,'');
  html=html.replace(/\s+style\s*=\s*(['"])([\s\S]*?)\1/gi,(_m,q,styles:string)=>{
    const allowed=styles.split(';').map(rule=>rule.trim()).filter(Boolean).filter(rule=>/^(color|background-color|font-size|font-family|font-weight|font-style|text-align|text-decoration|margin-left)\s*:/i.test(rule)).filter(rule=>!/(url\s*\(|expression|javascript|@import)/i.test(rule)).join(';');
    return allowed?' style="'+allowed.replace(/"/g,'&quot;')+'"':'';
  });
  html=html.replace(/\s+(?:href|src)\s*=\s*(['"])(?!https?:\/\/|\/|#)[\s\S]*?\1/gi,'');
  return html;
}

function Block({ block, onTutorOpen }:{block:Detail['blocks'][number];onTutorOpen:()=>void}) {
  const data=parse<any>(block.content);
  if (block.block_type==='EXPLANATION' || block.block_type==='PREREQUISITE') return <div className="concept-card">
    <span className="concept-kicker">{data.heading ?? data.title ?? (block.block_type==='PREREQUISITE'?<LocaleText hinglish="पहले से क्या जानते हैं?" english="What you already know" />:<LocaleText hinglish="समझें" english="Understand" />)}</span>
    {data.html ? <div className="lesson-rich-content" dangerouslySetInnerHTML={{__html:safeRichHtml(String(data.html))}}/> : <p>{data.body ?? data.description}</p>}
    {data.keyPoints?.map((x:string,i:number)=><div className="feedback" key={i}><span>• {x}</span></div>)}
  </div>;
  if (block.block_type==='WORKED_EXAMPLE') return <div className="concept-card">
    <span className="concept-kicker"><LocaleText hinglish="Worked example" english="Worked example" /></span><h3>{data.title ?? <LocaleText hinglish="उदाहरण" english="Example" />}</h3><p>{data.problem ?? data.prompt}</p>
    {data.steps?.map((x:string,i:number)=><div className="feedback" key={i}><span>{i+1}. {x}</span></div>)}
    {data.answer && <p><strong><LocaleText hinglish="उत्तर:" english="Answer:" /></strong> {data.answer}</p>}
  </div>;
  if (['GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE','HINT'].includes(block.block_type)) return <div className="concept-card">
    <span className="concept-kicker">{data.title ?? (block.block_type==='GUIDED_PRACTICE'?<LocaleText hinglish="साथ में करें" english="Try together" />:block.block_type==='HINT'?<LocaleText hinglish="Helpful hint" english="Helpful hint" />:<LocaleText hinglish="अब खुद करें" english="Try it yourself" />)}</span><p>{data.prompt ?? data.body}</p>{data.hint && <div className="feedback"><span><LocaleText hinglish="Hint:" english="Hint:" /> {data.hint}</span></div>}
  </div>;
  if (['IMAGE','DIAGRAM','VIDEO','AUDIO','ANIMATION'].includes(block.block_type)) {
    const url=String(data.url||'');
    const directVideo=/\.(mp4|webm|ogg)(\?.*)?$/i.test(url);
    const youtube=/youtube\.com\/watch\?/i.test(url)||/youtu\.be\//i.test(url);
    let embed=url;
    if(youtube){try{const parsedUrl=new URL(url);const id=parsedUrl.hostname.includes('youtu.be')?parsedUrl.pathname.slice(1):parsedUrl.searchParams.get('v')||'';if(id)embed='https://www.youtube-nocookie.com/embed/'+encodeURIComponent(id)}catch{}}
    return <div className="concept-card"><span className="concept-kicker">{data.title ?? (block.block_type==='AUDIO'?<LocaleText hinglish="सुनकर समझें" english="Listen and learn" />:block.block_type==='VIDEO'?<LocaleText hinglish="देखकर समझें" english="Watch and learn" />:<LocaleText hinglish="Visual" english="Visual" />)}</span>
      {data.description && <p>{data.description}</p>}
      {url && ['IMAGE','DIAGRAM','ANIMATION'].includes(block.block_type) && <img className="lesson-visual-media" src={url} alt={String(data.alt||data.title||'Learning visual')}/>}
      {url && block.block_type==='VIDEO' && directVideo && <video className="lesson-visual-video" controls preload="metadata" src={url}/>}
      {url && block.block_type==='VIDEO' && youtube && <div className="lesson-visual-embed"><iframe src={embed} title={String(data.title||'Lesson video')} allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowFullScreen referrerPolicy="strict-origin-when-cross-origin"/></div>}
      {url && block.block_type==='AUDIO' && <audio className="lesson-visual-audio" controls preload="metadata" src={url}/>}
      {url && block.block_type==='VIDEO' && !directVideo && !youtube && <a href={url} target="_blank" rel="noreferrer" className="button button-small"><LocaleText hinglish="Video देखें ↗" english="Watch video ↗" /></a>}
      {url && block.block_type==='AUDIO' && <a href={url} target="_blank" rel="noreferrer" className="text-link"><LocaleText hinglish="Audio खोलें ↗" english="Open audio ↗" /></a>}
      {!url && <div className="feedback"><span>◈</span><span>{data.description ?? data.alt ?? <LocaleText hinglish="इस concept का labelled visual देखें।" english="View a labelled visual for this concept." />}</span></div>}
      {data.caption && <p className="lesson-media-caption">{data.caption}</p>}
    </div>;
  }
  if (block.block_type==='AI_HELP') return <div className="ai-help"><div className="ai-icon">✦</div><div><strong><LocaleText hinglish="AI tutor" english="AI tutor" /></strong><p><LocaleText hinglish="अगर कहीं अटकें, तो इसी lesson के context में hint, explanation, example या step-by-step help लें।" english="If you get stuck, ask for a hint, explanation, example, or step-by-step help in the context of this lesson." /></p><button type="button" className="button button-dark button-small" onClick={onTutorOpen}><LocaleText hinglish="Tutor खोलें →" english="Open tutor →" /></button></div></div>;
  if (block.block_type==='SUMMARY' || block.block_type==='RECAP') return <div className="concept-card"><span className="concept-kicker"><LocaleText hinglish="Recap" english="Recap" /></span>{data.points?.map((x:string,i:number)=><div className="feedback" key={i}><span>✓ {x}</span></div>)}</div>;
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
      <span>{q.source_kind === 'TEXTBOOK_ALIGNED' ? <LocaleText hinglish="SCERT से aligned question" english="Question aligned with SCERT" /> : q.source_kind ?? <LocaleText hinglish="Content team का question" english="Content team question" />}</span>
    </div>
    <p><strong>{q.prompt}</strong></p>
    {options.length>0 ? <div className="answer-row">
      {options.map(o=><button key={o.key} disabled={busy} className={value===o.key?'selected':''} onClick={()=>submit(o.key)}>{o.key}. {o.label}</button>)}
    </div> : <div>
      {q.response_mode==='structured-text' || q.question_type==='LONG_ANSWER' || q.question_type==='SHORT_ANSWER' ?
        <textarea value={value} onChange={e=>setValue(e.target.value)} placeholder={tx('अपना answer या reasoning यहाँ लिखें…','Enter your answer or reasoning here…')} rows={q.question_type==='LONG_ANSWER'?6:4}/> :
        <input value={value} onChange={e=>setValue(e.target.value)} placeholder="उत्तर लिखें…"/>}
      <div style={{display:'flex',gap:8,alignItems:'center'}}><button type="button" className="button button-dark button-small" disabled={busy || !value.trim()} onClick={()=>submit(value)}><LocaleText hinglish="Answer check करें" english="Check answer" /></button><button type="button" className="text-link" onClick={()=>onTutorOpen(q.id)}><LocaleText hinglish="✦ Tutor" english="✦ Tutor" /></button></div>
    </div>}
    {result && <div className="feedback">
      <b>{result.correct===true?<LocaleText hinglish="✓ सही" english="✓ Correct" />:result.correct===false?<LocaleText hinglish="अभी सही नहीं" english="Not quite yet" />:<LocaleText hinglish="Answer save हो गया" english="Answer saved" />}</b>
      <span>{result.feedback}</span>
      {result.explanation && <span>{result.explanation}</span>}
    </div>}
  </article>;
}

export default function LearnClient() {
  const { locale } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const [lessons,setLessons]=useState<Lesson[]>([]);
  const [lesson,setLesson]=useState<Detail|null>(null);
  const [trackBrowse,setTrackBrowse]=useState<TrackBrowse|null>(null);
  const [help,setHelp]=useState('none');
  const [tutorOpen,setTutorOpen]=useState(false);
  const [tutorQuestionId,setTutorQuestionId]=useState<number|undefined>(undefined);
  const [error,setError]=useState('');
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [sessionStarted,setSessionStarted]=useState<number|null>(null);
  const router=useRouter();
  const searchParams=useSearchParams();

  const currentIndex=useMemo(()=>lesson ? lessons.findIndex(x=>x.id===lesson.id) : -1,[lesson,lessons]);
  const trackGroups=useMemo(()=>{
    if(!trackBrowse)return [];
    const grouped=new Map<string,{code:string;name:string;lessons:Lesson[]}>();
    trackBrowse.lessons.forEach(item=>{
      const key=item.chapter_code||item.chapter_name||'chapter';
      const current=grouped.get(key)||{code:key,name:item.chapter_name||'अध्याय',lessons:[]};
      current.lessons.push(item);grouped.set(key,current);
    });
    return Array.from(grouped.values());
  },[trackBrowse]);

  async function loadLesson(id:number) {
    try {
      const started=await fetch('/api/v1/learning/lessons/'+id+'/start',{method:'POST'});
      if(!started.ok) throw new Error('lesson progress');
      const detail=await fetch('/api/v1/learning/lessons/'+id).then(r=>{
        if(!r.ok) throw new Error('lesson');
        return r.json();
      }) as Detail;
      setLesson(detail);
    } catch {
      setError(tx('इस पाठ को खोलने या progress save करने में समस्या हुई। फिर प्रयास करें।','There was a problem opening this lesson or saving progress. Please try again.'));
    }
  }

  useEffect(()=>{
    let cancelled=false;
    async function load(){
      setError('');
      try{
        const me=await fetch('/api/v1/students/me');
        if(me.status===401||me.status===403){router.replace('/login/student');return;}
        const student=await me.json();
        if(!me.ok) throw new Error(student.message||tx('Student profile नहीं मिला','Student profile was not found'));
        const requestedId=Number(searchParams.get('lessonId')||0);
        const selectedSubject=searchParams.get('subjectCode');
        if(!requestedId && selectedSubject){
          if(selectedSubject!=='maths' && selectedSubject!=='science') throw new Error(tx('Subject सही नहीं है।','Invalid subject.'));
          const listResponse=await fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(String(student.class_code))+'&subjectCode='+encodeURIComponent(selectedSubject));
          const listBody=await listResponse.json();
          if(!listResponse.ok) throw new Error(listBody.message||'विषय की पाठ सूची नहीं खुल पाई।');
          if(cancelled)return;
          const trackLessons=listBody as Lesson[];
          const subjectName=selectedSubject==='maths'?'गणित':'विज्ञान';
          setLessons(trackLessons);setLesson(null);setSessionId(null);setSessionStarted(null);
          setTrackBrowse({classCode:String(student.class_code),subjectCode:selectedSubject,subjectName,lessons:trackLessons});
          return;
        }
        setTrackBrowse(null);
        let targetId=requestedId;
        if(!targetId){
          const rec=await fetch('/api/v1/recommendations/next');
          const rb=await rec.json();
          if(rec.ok&&rb.kind==='DIAGNOSTIC'){router.replace('/student/diagnostic');return;}
          if(rec.ok&&rb.available&&rb.lesson) targetId=Number(rb.lesson.id);
        }
        if(!targetId) throw new Error(tx('अभी कोई recommended lesson नहीं मिला।','No recommended lesson is available yet.'));
        const detail=await fetch('/api/v1/learning/lessons/'+targetId);
        if(!detail.ok) throw new Error('lesson');
        const d=await detail.json() as Detail;
        const list=await fetch('/api/v1/learning/lessons?classCode='+student.class_code+'&subjectCode='+d.subject_code).then(r=>r.json()) as Lesson[];
        setLessons(list);
        await fetch('/api/v1/learning/lessons/'+targetId+'/start',{method:'POST'});
        const sr=await fetch('/api/v1/learning/sessions/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source:'LESSON'})});
        if(sr.ok){const sb=await sr.json();if(!cancelled){setSessionId(Number(sb.sessionId));setSessionStarted(Date.now());}}
        if(!cancelled){setTrackBrowse(null);setLesson(d);}
      }catch(e:any){if(!cancelled)setError(e.message==='Student profile nahi मिला'||e.message==='Student profile नहीं मिला'?tx('Student login ज़रूरी है।','Student sign-in is required.'):tx('Lesson load नहीं हो पाया।','Unable to load the lesson.'));}
    }
    void load();
    return ()=>{cancelled=true;};
  },[router,searchParams]);

  useEffect(()=>{
    return ()=>{
      if(sessionId && sessionStarted){
        const minutes=Math.max(0,Math.min(240,Math.round((Date.now()-sessionStarted)/60000)));
        void fetch('/api/v1/learning/sessions/'+sessionId+'/end',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({minutes}),keepalive:true});
      }
    };
  },[sessionId,sessionStarted]);

  if(error) return <main className="lesson-page"><section className="lesson-wrap"><div className="auth-card"><h1><LocaleText hinglish="Lesson load नहीं हो पाया" english="Unable to load lesson" /></h1><p>{error}</p><Link href="/student" className="button button-dark"><LocaleText hinglish="← Student home पर" english="← Student home" /></Link></div></section></main>;
  if(trackBrowse) return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span className="lesson-progress"><LocaleText hinglish="Class" english="Class" /> {trackBrowse.classCode} · <LocaleText hinglish="Published syllabus" english="Published syllabus" /></span><span className="avatar">अ</span></header>
    <section className="lesson-wrap">
      <div className="lesson-meta"><span className="eyebrow"><LocaleText hinglish="कक्षा" english="Class" /> {trackBrowse.classCode} · {trackBrowse.subjectName}</span><span>{trackBrowse.lessons.length} <LocaleText hinglish="प्रकाशित पाठ" english="published lessons" /></span></div>
      <h1>{trackBrowse.subjectName} <LocaleText hinglish="की पढ़ाई" english="learning" /></h1>
      <p className="lesson-intro"><LocaleText hinglish="यहाँ सिर्फ published lessons दिखते हैं। Review या writing में मौजूद content अभी students को नहीं दिखता।" english="Only published lessons appear here. Content still in review or being written is not visible to students." /></p>
      {trackGroups.length===0 ? <div className="concept-card">
        <span className="concept-kicker"><LocaleText hinglish="विषय की सामग्री" english="Subject content" /></span>
        <h2><LocaleText hinglish="अभी कोई published lesson available नहीं है" english="No published lessons are available yet" /></h2>
        <p><LocaleText hinglish="इस विषय के chapters listed हैं, लेकिन उनके actual lessons अभी writing/review में हैं। Lessons तैयार और publish होते ही यहाँ दिखेंगे।" english="Chapters are listed for this subject, but their lessons are still being written or reviewed. They’ll appear here when ready and published." /></p>
        <Link href={'/student/learn?subjectCode='+(trackBrowse.subjectCode==='maths'?'science':'maths')} className="button button-dark"><LocaleText hinglish="दूसरा subject देखें →" english="View another subject →" /></Link>
        <p><Link href="/student" className="text-link"><LocaleText hinglish="Student home पर वापस जाएँ" english="Return to student home" /></Link></p>
      </div> : trackGroups.map(group=><section className="concept-card" key={group.code}>
        <span className="concept-kicker"><LocaleText hinglish="Chapter" english="Chapter" /></span><h2>{group.name}</h2>
        <div className="task-list">{group.lessons.map(item=><Link key={item.id} className="app-task" href={'/student/learn?subjectCode='+trackBrowse.subjectCode+'&lessonId='+item.id}>
          <span className="task-icon">▣</span><div><strong>{item.title}</strong><small>{item.estimated_minutes} <LocaleText hinglish="मिनट · प्रकाशित पाठ" english="min · published lesson" /></small></div><span className="task-action">→</span>
        </Link>)}</div>
      </section>)}
    </section>
  </main>;
  if(!lesson) return <main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow"><LocaleText hinglish="Lesson load हो रहा है…" english="Loading lesson…" /></div></section></main>;

  return <main className="lesson-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span className="lesson-progress">Published syllabus · {lesson.estimated_minutes} min</span><span className="avatar">अ</span></header>
    <section className="lesson-wrap">
      <div className="lesson-meta">
        <span className="eyebrow"><LocaleText hinglish="कक्षा" english="Class" /> {lesson.class_code} · {lesson.subject_name} · {lesson.chapter_name}</span>
        <span>Lesson {currentIndex+1} / {lessons.length}</span>
      </div>
      <h1>{lesson.title}</h1>
      <p className="lesson-intro">{lesson.summary}</p>
      <div className="feedback"><span><LocaleText hinglish="Learning path" english="Learning path" /></span><span><LocaleText hinglish="पहले की जानकारी → explanation → worked example → guided practice → खुद practice → assessment → recap" english="Prerequisites → explanation → worked example → guided practice → independent practice → assessment → recap" /></span></div>

      {lesson.blocks.map(block=><Block key={block.id} block={block} onTutorOpen={()=>setTutorOpen(true)}/>) }
      {help !== 'none' && <div className="feedback"><b>{help.replaceAll('_',' ')} <LocaleText hinglish="सहायता" english="help" /></b><span><LocaleText hinglish="पहले concept को अपने words में समझें, फिर example देखकर दोबारा try करें।" english="Explain the concept in your own words, then review the example and try again." /></span></div>}

      <div className="content-heading"><h2><LocaleText hinglish="इस lesson के questions" english="Questions for this lesson" /></h2><span>{lesson.questions.length} questions</span></div>
      {lesson.questions.map(q=><QuestionCard key={q.id} q={q} onResult={()=>{}} onTutorOpen={id=>{setTutorQuestionId(id);setTutorOpen(true)}}/>)}

      <button type="button" className="tutor-launch" onClick={()=>setTutorOpen(true)} aria-label={tx('AI tutor खोलें','Open AI tutor')}>✦ <span><LocaleText hinglish="AI Tutor" english="AI Tutor" /></span></button>
      <TutorDock lessonId={lesson.id} open={tutorOpen} onClose={()=>setTutorOpen(false)} currentQuestionId={tutorQuestionId} />

      <div className="lesson-next">
        {currentIndex>0 ? <button className="button button-small" onClick={()=>loadLesson(lessons[currentIndex-1].id)}><LocaleText hinglish="← पिछला" english="← Previous" /></button> : <span/>}
        {currentIndex>=0 && currentIndex<lessons.length-1
          ? <button className="button button-dark button-small" onClick={()=>loadLesson(lessons[currentIndex+1].id)}><LocaleText hinglish="अगला lesson →" english="Next lesson →" /></button>
          : <Link href="/student/practice" className="button button-dark button-small"><LocaleText hinglish="Practice खोलें →" english="Open practice →" /></Link>}
      </div>
    </section>
  </main>;
}
