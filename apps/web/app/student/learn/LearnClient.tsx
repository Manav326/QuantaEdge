'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';
import MathText from '../../components/MathText';
import AnswerFeedback from '../../components/AnswerFeedback';


import Link from 'next/link';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import TutorDock from './TutorDock';

type Lesson = {
  id:number; code:string; title:string; summary:string; estimated_minutes:number;
  chapter_name:string; subject_name:string; subject_code:string; chapter_code:string;
  mcq_count?:number; mcq_answered_count?:number;
  practice_completed_attempts?:number; practice_best_correct_count?:number|null;
  practice_best_graded_count?:number|null; practice_best_percent?:number|null;
  practice_active_answered_count?:number|null; practice_active_question_count?:number|null;
};
type TrackBrowse = {
  classCode:string; subjectCode:string; subjectName:string; lessons:Lesson[];
};
type StudentDocument = {document_id:number;scope:'SUBJECT_BOOK'|'CHAPTER_PDF';title:string;page_count:number;last_page?:number;subject_code:string;subject_name?:string;chapter_code?:string|null;chapter_name?:string|null;edition?:string|null;language?:'hi'|'en'};

function textbookChapterTitle(item: StudentDocument, language: 'hi' | 'en') {
  if (language === 'en') {
    const match = item.title.match(/Chapter\s+\d+\s*:\s*(.+)$/i);
    if (match?.[1]) return match[1].trim();
  }
  return item.chapter_name || item.title;
}

type Question = {
  id:number; sort_order?:number; question_type:string; prompt:string; explanation:string; options:string; translated?:boolean;
  source_kind?:string; source_year?:number; board?:string; marks?:number; exam_format?:string;
  topic?:string; subtopic?:string; skill?:string; response_mode?:string; attempted?:boolean;
};
type Detail = Lesson & {
  class_code:string; class_name:string;
  blocks:{id:number;sequence_no:number;block_type:string;content:string;translated?:boolean}[];
  questions:Question[];
  requested_language?:'hi'|'en';
  translation_complete?:boolean;
  lesson_translated?:boolean; chapter_translated?:boolean; subject_translated?:boolean;
  class_translated?:boolean; objective_translated?:boolean;
};

function parse<T=any>(value:string):T {
  try { return JSON.parse(value) as T; } catch { return {} as T; }
}

const LEARNER_BLOCK_STAGE: Record<string,number> = {
  PREREQUISITE: 0,
  EXPLANATION: 1,
  IMAGE: 2, DIAGRAM: 2, VIDEO: 2, AUDIO: 2, ANIMATION: 2,
  WORKED_EXAMPLE: 3,
  GUIDED_PRACTICE: 4, HINT: 4,
  INDEPENDENT_PRACTICE: 5, CHALLENGE: 5,
  AI_HELP: 6,
  SUMMARY: 7, RECAP: 7,
};

function orderLearnerBlocks(blocks: Detail['blocks']) {
  // Match the Admin preview: preserve every active block in its authored sequence.
  return blocks
    .filter(block => Object.prototype.hasOwnProperty.call(LEARNER_BLOCK_STAGE, block.block_type))
    .slice()
    .sort((a,b) => a.sequence_no-b.sequence_no || a.id-b.id);
 }

function learnerPathLabel(blocks: Detail['blocks'], questionCount: number) {
  const types=new Set(blocks.map(block=>block.block_type));
  const stages:{hinglish:string;english:string}[]=[];
  if(types.has('PREREQUISITE')) stages.push({hinglish:'पहले की जानकारी',english:'Prior knowledge'});
  if(types.has('EXPLANATION')) stages.push({hinglish:'समझें',english:'Understand'});
  if(['IMAGE','DIAGRAM','VIDEO','AUDIO','ANIMATION'].some(type=>types.has(type))) stages.push({hinglish:'visual से समझें',english:'Learn with visuals'});
  if(types.has('WORKED_EXAMPLE')) stages.push({hinglish:'उदाहरण देखें',english:'Worked example'});
  if(types.has('GUIDED_PRACTICE')) stages.push({hinglish:'साथ में करें',english:'Guided practice'});
  if(types.has('INDEPENDENT_PRACTICE')||types.has('CHALLENGE')) stages.push({hinglish:'खुद अभ्यास करें',english:'Independent practice'});
  if(questionCount>0) stages.push({hinglish:'assessment',english:'Assessment'});
  if(types.has('SUMMARY')||types.has('RECAP')) stages.push({hinglish:'दोहराएँ',english:'Recap'});
  return stages.length ? {
    hinglish:stages.map(stage=>stage.hinglish).join(' → '),
    english:stages.map(stage=>stage.english).join(' → ')
  } : {hinglish:'सीखने का रास्ता तैयार हो रहा है',english:'Learning path is being prepared'};
}

function stackFractionsInHtml(value:string) {
  // Transform only text between tags; never rewrite a number-like URL or HTML attribute.
  const fraction = /(^|[^\w./])(\d+)\s*\/\s*(\d+)(?![\w./])/g;
  return String(value || '').replace(/(^|>)([^<]*)(?=<|$)/g, (_whole, prefix:string, text:string) => {
    const rendered = text.replace(fraction, (_match, boundary:string, numerator:string, denominator:string) =>
      boundary + '<span class="qe-inline-fraction" role="math" aria-label="' + numerator + ' over ' + denominator + '">' +
      '<span class="qe-inline-fraction__numerator" aria-hidden="true">' + numerator + '</span>' +
      '<span class="qe-inline-fraction__denominator" aria-hidden="true">' + denominator + '</span></span>');
    return prefix + rendered;
  });
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
    {data.html ? <div className="lesson-rich-content" dangerouslySetInnerHTML={{__html:safeRichHtml(stackFractionsInHtml(String(data.html)))}}/> : <p><MathText text={String(data.body ?? data.description ?? '')}/></p>}
    {data.keyPoints?.map((x:string,i:number)=><div className="feedback" key={i}><span>• {x}</span></div>)}
  </div>;
  if (block.block_type==='WORKED_EXAMPLE') return <div className="concept-card">
    <span className="concept-kicker"><LocaleText hinglish="Worked example" english="Worked example" /></span><h3>{data.title ?? <LocaleText hinglish="उदाहरण" english="Example" />}</h3><p><MathText text={String(data.problem || data.prompt || '')}/></p>
    {data.steps?.map((x:string,i:number)=><div className="feedback" key={i}><span>{i+1}. <MathText text={x}/></span></div>)}
    {data.answer && <p className="qe-model-answer"><strong><LocaleText hinglish="Model answer:" english="Model answer:" /></strong> <MathText text={String(data.answer)}/></p>}
  </div>;
  if (['GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE','HINT'].includes(block.block_type)) return <div className="concept-card">
    <span className="concept-kicker">{data.title ?? (block.block_type==='GUIDED_PRACTICE'?<LocaleText hinglish="साथ में करें" english="Try together" />:block.block_type==='HINT'?<LocaleText hinglish="Helpful hint" english="Helpful hint" />:<LocaleText hinglish="अब खुद करें" english="Try it yourself" />)}</span><p><MathText text={String(data.prompt || data.body || '')}/></p>{data.hint && <div className="feedback"><span><LocaleText hinglish="Hint:" english="Hint:" /> <MathText text={String(data.hint)}/></span></div>}
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
  if (block.block_type==='SUMMARY' || block.block_type==='RECAP') return <div className="concept-card"><span className="concept-kicker"><LocaleText hinglish="Recap" english="Recap" /></span>{data.points?.map((x:string,i:number)=><div className="feedback" key={i}><span>✓ <MathText text={x}/></span></div>)}</div>;
  return null;
}

function QuestionCard({q,index,onResult,onTutorOpen}:{q:Question;index:number;onResult:(id:number,result:any)=>void;onTutorOpen:(id:number)=>void}) {
  const { locale, learningLanguage } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const [value,setValue]=useState('');
  const [busy,setBusy]=useState(false);
  const [result,setResult]=useState<any>(null);
  const [answerError,setAnswerError]=useState('');
  const options=parse<{key:string;label:string}[]>(q.options||'[]');
  const submitted=result?.correct===true;
  const questionLabel=q.question_type==='MCQ'?tx('बहुविकल्पीय प्रश्न','Multiple choice'):q.question_type==='TRUE_FALSE'?tx('सही या गलत','True or false'):q.question_type==='NUMERICAL'?tx('संख्यात्मक प्रश्न','Numerical question'):tx(q.question_type.replaceAll('_',' '),q.question_type.replaceAll('_',' '));

  async function submit(next:string) {
    setValue(next);
    if (!next.trim() || submitted || busy) return;
    setBusy(true);setAnswerError('');setResult(null);
    try {
      const language=learningLanguage;
      const response=await fetch('/api/v1/learning/questions/'+q.id+'/answer?language='+language,{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({answer:next})
      });
      const body=await response.json();
      if(!response.ok) throw new Error(body.detail||body.message||tx('उत्तर जाँच नहीं पाया।','Answer could not be checked.'));
      setResult(body);
      onResult(q.id,body);
    } catch(e:any) {
      setAnswerError(e.message||tx('उत्तर जाँच नहीं पाया। फिर प्रयास करें।','Answer could not be checked. Please try again.'));
    } finally { setBusy(false); }
  }

  return <article className={'qe-question-card '+(result?.correct===false?'has-answer-incorrect':result?.correct===true?'has-answer-correct':'')}>
    <div className="qe-question-card__top">
      <span className="qe-question-index"><span>{String(index).padStart(2,'0')}</span></span>
      <div className="qe-question-card__labels">
        <span className="qe-question-type">{questionLabel}</span>
        <span className="qe-question-context">{q.exam_format||tx('Lesson check','Lesson check')}{q.marks?' · '+q.marks+' '+tx('अंक','marks'):''}</span>
      </div>
      <span className="qe-question-source">{q.source_kind==='TEXTBOOK_ALIGNED'?<LocaleText hinglish="पाठ्यपुस्तक आधारित" english="Textbook-aligned" />:q.source_kind?<MathText text={q.source_kind.replaceAll('_',' ').toLowerCase()}/>:<LocaleText hinglish="QuantaEdge प्रश्न" english="QuantaEdge question" />}</span>
    </div>
    <div className="qe-question-card__prompt"><MathText text={q.prompt}/></div>

    {options.length>0 ? <div className="qe-answer-options" role="group" aria-label={tx('उत्तर के विकल्प','Answer choices')}>
      {options.map((o,i)=>{
        const chosen=value===o.key;
        const stateClass=chosen&&result?.correct===false?'is-incorrect':chosen&&result?.correct===true?'is-correct':chosen?'is-selected':'';
        return <button type="button" key={o.key} disabled={busy||submitted} aria-pressed={chosen} className={'qe-answer-option '+stateClass} onClick={()=>void submit(o.key)}>
          <span className="qe-answer-option__key">{o.key||String.fromCharCode(65+i)}</span>
          <span className="qe-answer-option__text"><MathText text={o.label}/></span>
          {chosen&&result?.correct===true&&<span className="qe-answer-option__status" aria-label={tx('सही','Correct')}>✓</span>}
          {chosen&&result?.correct===false&&<span className="qe-answer-option__status" aria-label={tx('गलत','Incorrect')}>!</span>}
        </button>;
      })}
    </div> : <div className={'qe-answer-entry '+(result?.correct===false?'is-incorrect':result?.correct===true?'is-correct':'')}>
      {q.response_mode==='structured-text' || q.question_type==='LONG_ANSWER' || q.question_type==='SHORT_ANSWER' ?
        <textarea value={value} disabled={busy||submitted} onChange={e=>setValue(e.target.value)} placeholder={tx('अपना उत्तर या reasoning यहाँ लिखें…','Write your answer or reasoning here…')} rows={q.question_type==='LONG_ANSWER'?6:4}/> :
        <input value={value} disabled={busy||submitted} onChange={e=>setValue(e.target.value)} placeholder={tx('अपना उत्तर लिखें…','Type your answer…')}/>}
      <div className="qe-answer-entry__actions">
        <button type="button" className="button button-dark button-small" disabled={busy||!value.trim()||submitted} onClick={()=>void submit(value)}>{busy?<LocaleText hinglish="जाँच रहे हैं…" english="Checking…" />:<LocaleText hinglish="उत्तर जाँचें" english="Check answer" />}</button>
        <button type="button" className="qe-tutor-link" onClick={()=>onTutorOpen(q.id)}><span>✦</span><LocaleText hinglish="Tutor से मदद लें" english="Ask the tutor" /></button>
      </div>
    </div>}

    {answerError && <div className="qe-answer-feedback is-incorrect" role="alert"><span className="qe-answer-feedback__icon">!</span><div><strong><LocaleText hinglish="उत्तर जाँच नहीं पाया" english="Could not check answer" /></strong><p><MathText text={answerError}/></p></div></div>}
    {result && <AnswerFeedback
      outcome={result.correct===true?'correct':result.correct===false?'incorrect':'ungraded'}
      title={result.correct===true?<LocaleText hinglish="बिल्कुल सही!" english="That's correct!" />:result.correct===false?<LocaleText hinglish="अभी सही नहीं — फिर प्रयास करें" english="Not quite — try again" />:<LocaleText hinglish="उत्तर सेव हो गया" english="Answer saved" />}
      message={result.correct===true?<LocaleText hinglish="बहुत बढ़िया। Explanation पढ़कर अपनी समझ पक्की करें।" english="Great work. Review the explanation to strengthen your understanding." />:result.correct===false?<LocaleText hinglish="घबराएँ नहीं। Explanation पढ़ें, फिर विकल्प दोबारा चुनें।" english="No worries. Read the explanation, then choose again." />:<LocaleText hinglish="इस उत्तर की शिक्षक या rubric से जाँच आवश्यक हो सकती है।" english="This answer may need teacher or rubric review." />}
      explanation={result.explanation?<MathText text={String(result.explanation)}/>:undefined}
      explanationLabel={<LocaleText hinglish="समझें क्यों" english="Why this works" />}
    />}
  </article>;
}

export default function LearnClient() {
  const { locale, learningLanguage } = useLocale();
  const tx = (hinglish: string, english: string) => locale === 'english' ? english : hinglish;
  const [lessons,setLessons]=useState<Lesson[]>([]);
  const [lesson,setLesson]=useState<Detail|null>(null);
  const [trackBrowse,setTrackBrowse]=useState<TrackBrowse|null>(null);
  const [studentDocuments,setStudentDocuments]=useState<StudentDocument[]>([]);
  const [documentsLoaded,setDocumentsLoaded]=useState(false);
  const [openChapter,setOpenChapter]=useState('');
  const initializedChapterScope=useRef('');
  const [help,setHelp]=useState('none');
  const [tutorOpen,setTutorOpen]=useState(false);
  const [tutorQuestionId,setTutorQuestionId]=useState<number|undefined>(undefined);
  const [error,setError]=useState('');
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [sessionStarted,setSessionStarted]=useState<number|null>(null);
  const [mcqAttempted,setMcqAttempted]=useState<Record<number,boolean>>({});
  const router=useRouter();
  const searchParams=useSearchParams();

  const currentIndex=useMemo(()=>lesson ? lessons.findIndex(x=>x.id===lesson.id) : -1,[lesson,lessons]);
  const orderedBlocks=useMemo(()=>lesson ? orderLearnerBlocks(lesson.blocks) : [],[lesson]);
  // The lesson screen teaches the concept first. Recap is rendered after the student's
  // automatically selected micro-topic practice, not between the MCQ check and practice.
  const learningBlocks=useMemo(
    ()=>orderedBlocks.filter(block=>block.block_type!=='SUMMARY'&&block.block_type!=='RECAP'),
    [orderedBlocks]);
  const recapBlocks=useMemo(
    ()=>orderedBlocks.filter(block=>block.block_type==='SUMMARY'||block.block_type==='RECAP'),
    [orderedBlocks]);
  const lessonMcqs=useMemo(
    ()=>lesson?.questions.filter(question=>question.question_type==='MCQ')??[],
    [lesson]);
  const attemptedMcqs=lessonMcqs.filter(question=>mcqAttempted[question.id]).length;
  const canStartTopicPractice=lessonMcqs.length===0||attemptedMcqs===lessonMcqs.length;
  const trackGroups=useMemo(()=>{
    if(!trackBrowse)return [];
    const grouped=new Map<string,{code:string;name:string;lessons:Lesson[]}>();
    const sameName=(left:string,right:string)=>left.trim().toLocaleLowerCase()===right.trim().toLocaleLowerCase();
    trackBrowse.lessons.forEach(item=>{
      const key=item.chapter_code||item.chapter_name||'chapter';
      const current=grouped.get(key)||{code:key,name:item.chapter_name||'Chapter',lessons:[]};
      current.lessons.push(item);grouped.set(key,current);
    });
    // A reviewed, published chapter PDF can establish its chapter section even if authored lesson content
    // is not published yet. Merge on curriculum code first, then display name as a safe fallback.
    studentDocuments
      .filter(item=>item.scope==='CHAPTER_PDF'&&item.subject_code===trackBrowse.subjectCode)
      .forEach(document=>{
        const existing=Array.from(grouped.values()).find(group=>
          (Boolean(document.chapter_code)&&group.code===document.chapter_code)||
          (Boolean(document.chapter_name)&&sameName(group.name,document.chapter_name!))
        );
        if(existing){
          if(document.chapter_code&&existing.code===existing.name)existing.code=document.chapter_code;
          if(learningLanguage==='en')existing.name=textbookChapterTitle(document,'en');
          return;
        }
        const name=learningLanguage==='en'?textbookChapterTitle(document,'en'):(document.chapter_name||document.title);
        const key=document.chapter_code||name||('chapter-'+document.document_id);
        grouped.set(key,{code:key,name,lessons:[]});
      });
    return Array.from(grouped.values());
  },[trackBrowse,studentDocuments,locale,learningLanguage]);

  async function loadLesson(id:number) {
    try {
      const started=await fetch('/api/v1/learning/lessons/'+id+'/start',{method:'POST'});
      if(!started.ok) throw new Error('lesson progress');
      const detail=await fetch('/api/v1/learning/lessons/'+id+'?language='+learningLanguage).then(r=>{
        if(!r.ok) throw new Error('lesson');
        return r.json();
      }) as Detail;
      setLesson(detail);
      setMcqAttempted(Object.fromEntries(detail.questions
        .filter(question=>question.question_type==='MCQ'&&question.attempted)
        .map(question=>[question.id,true])));
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
          const selectedLanguage=learningLanguage;
          const listResponse=await fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(String(student.class_code))+'&subjectCode='+encodeURIComponent(selectedSubject)+'&language='+selectedLanguage);
          const listBody=await listResponse.json();
          if(!listResponse.ok) throw new Error(listBody.message||'विषय की पाठ सूची नहीं खुल पाई।');
          if(cancelled)return;
          const trackLessons=listBody as Lesson[];
          if(cancelled)return;
          const subjectName=selectedSubject==='maths'?tx('गणित','Mathematics'):tx('विज्ञान','Science');
          setLessons(trackLessons);setLesson(null);setSessionId(null);setSessionStarted(null);setMcqAttempted({});
          setStudentDocuments([]);
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
        const selectedLanguage=learningLanguage;
        const detail=await fetch('/api/v1/learning/lessons/'+targetId+'?language='+selectedLanguage);
        if(!detail.ok) throw new Error('lesson');
        const d=await detail.json() as Detail;
        const list=await fetch('/api/v1/learning/lessons?classCode='+student.class_code+'&subjectCode='+d.subject_code+'&language='+selectedLanguage).then(r=>r.json()) as Lesson[];
        setLessons(list);
        await fetch('/api/v1/learning/lessons/'+targetId+'/start',{method:'POST'});
        const sr=await fetch('/api/v1/learning/sessions/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source:'LESSON'})});
        if(sr.ok){const sb=await sr.json();if(!cancelled){setSessionId(Number(sb.sessionId));setSessionStarted(Date.now());}}
        if(!cancelled){
          setStudentDocuments([]);setTrackBrowse(null);setLesson(d);
          setMcqAttempted(Object.fromEntries(d.questions
            .filter(question=>question.question_type==='MCQ'&&question.attempted)
            .map(question=>[question.id,true])));
        }
      }catch(e:any){if(!cancelled)setError(e.message==='Student profile नहीं मिला'?tx('Student login ज़रूरी है।','Student sign-in is required.'):tx('Lesson load नहीं हो पाया।','Unable to load the lesson.'));}
    }
    void load();
    return ()=>{cancelled=true;};
  },[router,searchParams]);

  // Switching languages refreshes presentation only; it must not restart progress or a learning session.
  const lastContentLocale=useRef(learningLanguage);
  useEffect(()=>{
    if(lastContentLocale.current===learningLanguage)return;
    lastContentLocale.current=learningLanguage;
    const language=learningLanguage;
    let cancelled=false;
    async function refreshLanguage(){
      try{
        if(trackBrowse){
          const response=await fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(trackBrowse.classCode)
            +'&subjectCode='+encodeURIComponent(trackBrowse.subjectCode)+'&language='+language,{cache:'no-store'});
          const rows=await response.json();
          if(!response.ok)throw new Error(rows?.message||'Could not refresh lesson language.');
          if(!cancelled){
            const newLessons=Array.isArray(rows)?rows as Lesson[]:[];
            setLessons(newLessons);
            setTrackBrowse(old=>old?{...old,subjectName:old.subjectCode==='maths'?tx('गणित','Mathematics'):tx('विज्ञान','Science'),lessons:newLessons}:old);
          }
          return;
        }
        if(lesson){
          const [detailResponse,listResponse]=await Promise.all([
            fetch('/api/v1/learning/lessons/'+lesson.id+'?language='+language,{cache:'no-store'}),
            fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(lesson.class_code)
              +'&subjectCode='+encodeURIComponent(lesson.subject_code)+'&language='+language,{cache:'no-store'})
          ]);
          const [detailBody,listBody]=await Promise.all([detailResponse.json(),listResponse.json()]);
          if(!detailResponse.ok)throw new Error(detailBody?.message||'Could not refresh lesson language.');
          if(!listResponse.ok)throw new Error(listBody?.message||'Could not refresh lesson list language.');
          if(!cancelled){
            setLesson(detailBody as Detail);
            setLessons(Array.isArray(listBody)?listBody as Lesson[]:[]);
          }
        }
      }catch(e){
        // Preserve the currently visible lesson if a language refresh fails.
        console.error('QuantaEdge content language refresh failed',e);
      }
    }
    void refreshLanguage();
    return()=>{cancelled=true;};
  },[learningLanguage,lesson?.id,lesson?.class_code,lesson?.subject_code,trackBrowse?.classCode,trackBrowse?.subjectCode]);

  // Fetch the selected language edition independently: changing locale must not restart
  // the lesson, reset the session, or issue a second progress-start request.
  useEffect(()=>{
    const subjectCode=trackBrowse?.subjectCode||lesson?.subject_code||searchParams.get('subjectCode');
    if(!subjectCode||!['maths','science'].includes(subjectCode)){
      setStudentDocuments([]);
      setDocumentsLoaded(false);
      return;
    }
    let cancelled=false;
    const preferredLanguage=learningLanguage;
    const fallbackLanguage=preferredLanguage==='en'?'hi':'en';
    setDocumentsLoaded(false);
    setStudentDocuments([]);

    // Show the chosen language first, but do not hide a chapter when its only
    // published PDF is available in the other language.
    const loadLanguage=(language:string):Promise<StudentDocument[]> =>
      fetch('/api/v1/learning/documents?subjectCode='+encodeURIComponent(subjectCode)+'&language='+language,{cache:'no-store'})
        .then(async response=>response.ok?response.json():[])
        .then(body=>Array.isArray(body)?body as StudentDocument[]:[])
        .catch(()=>[]);

    Promise.all([loadLanguage(preferredLanguage),loadLanguage(fallbackLanguage)])
      .then(([preferredDocuments,fallbackDocuments])=>{
        if(cancelled)return;
        const resourceKey=(item:StudentDocument)=>{
          const resource=item.scope==='SUBJECT_BOOK'
            ? 'subject-book'
            : item.chapter_code
              ? 'chapter-code:'+item.chapter_code.trim().toLocaleLowerCase()
              : item.chapter_name
                ? 'chapter-name:'+item.chapter_name.trim().toLocaleLowerCase()
                : 'document:'+item.document_id;
          return item.scope+':'+item.subject_code+':'+resource;
        };
        const merged=[...preferredDocuments];
        const seen=new Set(merged.map(resourceKey));
        for(const item of fallbackDocuments){
          const key=resourceKey(item);
          if(!seen.has(key)){seen.add(key);merged.push(item);}
        }
        setStudentDocuments(merged);
      })
      .finally(()=>{if(!cancelled)setDocumentsLoaded(true);});
    return()=>{cancelled=true;};
  },[learningLanguage,trackBrowse?.subjectCode,lesson?.subject_code,searchParams]);

  useEffect(()=>{
    return ()=>{
      if(sessionId && sessionStarted){
        const minutes=Math.max(0,Math.min(240,Math.round((Date.now()-sessionStarted)/60000)));
        void fetch('/api/v1/learning/sessions/'+sessionId+'/end',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({minutes}),keepalive:true});
      }
    };
  },[sessionId,sessionStarted]);

  const chapterStorageScope=trackBrowse?trackBrowse.classCode+':'+trackBrowse.subjectCode:'';
  useEffect(()=>{
    if(!trackBrowse||!documentsLoaded||!chapterStorageScope||initializedChapterScope.current===chapterStorageScope)return;
    const key='qe:student:open-chapter:'+chapterStorageScope;
    const remembered=localStorage.getItem(key);
    const initial=trackGroups.find(group=>group.code===remembered)?.code||trackGroups[0]?.code||'';
    setOpenChapter(initial);
    if(initial)localStorage.setItem(key,initial);
    initializedChapterScope.current=chapterStorageScope;
  },[trackBrowse?.classCode,trackBrowse?.subjectCode,documentsLoaded,chapterStorageScope,trackGroups]);

  useEffect(()=>{
    if(!trackBrowse||!documentsLoaded||!chapterStorageScope||initializedChapterScope.current!==chapterStorageScope)return;
    const key='qe:student:learn-scroll:'+chapterStorageScope;
    const raw=localStorage.getItem(key);
    if(raw===null)return;
    const target=Math.max(0,Number(raw)||0);
    requestAnimationFrame(()=>requestAnimationFrame(()=>{
      window.scrollTo({top:target,behavior:'auto'});
      localStorage.removeItem(key);
    }));
  },[trackBrowse?.classCode,trackBrowse?.subjectCode,documentsLoaded,chapterStorageScope,openChapter,trackGroups.length]);

  useEffect(()=>{
    if(!lesson)return;
    const key='qe:student:lesson-scroll:'+lesson.id;
    const raw=localStorage.getItem(key);
    if(raw===null)return;
    const target=Math.max(0,Number(raw)||0);
    requestAnimationFrame(()=>requestAnimationFrame(()=>{
      window.scrollTo({top:target,behavior:'auto'});
      localStorage.removeItem(key);
    }));
  },[lesson?.id]);

  function rememberTrackPosition(chapterCode?:string){
    if(!trackBrowse)return;
    if(chapterCode){
      setOpenChapter(chapterCode);
      localStorage.setItem('qe:student:open-chapter:'+chapterStorageScope,chapterCode);
    }
    localStorage.setItem('qe:student:learn-scroll:'+chapterStorageScope,String(window.scrollY));
  }
  function subjectDocumentHref(documentId:number){
    return '/student/textbooks?documentId='+documentId+'&returnTo='+encodeURIComponent('/student/learn?subjectCode='+trackBrowse?.subjectCode);
  }
  function rememberLessonPosition(){
    if(lesson)localStorage.setItem('qe:student:lesson-scroll:'+lesson.id,String(window.scrollY));
  }

  if(error) return <main className="lesson-page"><section className="lesson-wrap"><div className="auth-card"><h1><LocaleText hinglish="Lesson load नहीं हो पाया" english="Unable to load lesson" /></h1><p>{error}</p><Link href="/student" className="button button-dark"><LocaleText hinglish="← Student home पर" english="← Student home" /></Link></div></section></main>;
  if(trackBrowse) return <main className="lesson-page qe-subject-reading-page">
    <section className="lesson-wrap">
      <div className="lesson-meta"><span className="eyebrow"><LocaleText hinglish="कक्षा" english="Class" /> {trackBrowse.classCode} · {trackBrowse.subjectCode==='maths'?<LocaleText hinglish="गणित" english="Mathematics" />:<LocaleText hinglish="विज्ञान" english="Science" />}</span><span>{trackGroups.length} <LocaleText hinglish="अध्याय" english="chapters" /> · {trackBrowse.lessons.length} <LocaleText hinglish="प्रकाशित पाठ" english="published lessons" /></span></div>
      <h1>{trackBrowse.subjectCode==='maths'?<LocaleText hinglish="गणित की पढ़ाई" english="Mathematics learning" />:<LocaleText hinglish="विज्ञान की पढ़ाई" english="Science learning" />}</h1>
      <p className="lesson-intro"><LocaleText hinglish="किसी chapter को खोलकर उसके topics और PDF देखें। आपका आखिरी खुला chapter याद रखा जाएगा।" english="Open a chapter to see its topics and PDF. Your last open chapter is remembered." /></p>
      {studentDocuments.filter(item=>item.scope==='SUBJECT_BOOK'&&item.subject_code===trackBrowse.subjectCode).map(item=>
        <Link key={item.document_id} className="qe-student-pdf-link qe-subject-book-link" href={subjectDocumentHref(item.document_id)} onClick={()=>rememberTrackPosition(openChapter||trackGroups[0]?.code)}>
          <span className="qe-student-pdf-mark" aria-hidden="true"><svg viewBox="0 0 24 28"><path d="M4 1.5h10l6 6V26.5H4z"/><path d="M14 1.5v6h6M6.5 16h11M6.5 20h11"/></svg><small>PDF</small></span>
          <span className="qe-student-pdf-copy"><span><LocaleText hinglish="पूरी subject book" english="Complete subject textbook" /></span><strong>{item.title}</strong><small>{item.page_count} pages · {item.edition||tx('ऑनलाइन पढ़ें','Read online')}</small></span>
          <span className="qe-student-pdf-action"><LocaleText hinglish="PDF खोलें" english="Open PDF" /> ↗</span>
        </Link>
      )}
      {!documentsLoaded&&<div className="qe-chapter-loading"><span className="qe-reader-spinner"/><LocaleText hinglish="PDFs load हो रहे हैं…" english="Checking available PDFs…" /></div>}
      {trackGroups.length===0 ? <div className="concept-card">
        <span className="concept-kicker"><LocaleText hinglish="विषय की सामग्री" english="Subject content" /></span>
        <h2><LocaleText hinglish="अभी कोई published lesson या chapter PDF available नहीं है" english="No published lessons or chapter PDFs are available yet" /></h2>
        <p><LocaleText hinglish="इस विषय की सामग्री तैयार और publish होने पर यहाँ दिखाई देगी।" english="Content will appear here when it has been prepared and published." /></p>
        <Link href={'/student/learn?subjectCode='+(trackBrowse.subjectCode==='maths'?'science':'maths')} className="button button-dark"><LocaleText hinglish="दूसरा subject देखें →" english="View another subject →" /></Link>
        <p><Link href="/student" className="text-link"><LocaleText hinglish="Student home पर वापस जाएँ" english="Return to student home" /></Link></p>
      </div> : trackGroups.map((group,index)=>{
        const chapterDocuments=studentDocuments.filter(item=>item.scope==='CHAPTER_PDF'&&(
          (Boolean(item.chapter_code)&&item.chapter_code===group.code)||
          (Boolean(item.chapter_name)&&item.chapter_name!.trim().toLocaleLowerCase()===group.name.trim().toLocaleLowerCase())));
        const expanded=openChapter===group.code;
        return <section className={'qe-student-chapter '+(expanded?'expanded':'')} id={'qe-chapter-'+encodeURIComponent(group.code)} key={group.code}>
          <button type="button" className="qe-student-chapter-toggle" aria-expanded={expanded} onClick={()=>{
            const next=expanded?'':group.code;
            setOpenChapter(next);
            if(next)localStorage.setItem('qe:student:open-chapter:'+chapterStorageScope,next);
            else localStorage.removeItem('qe:student:open-chapter:'+chapterStorageScope);
          }}>
            <span className="qe-student-chapter-number">{String(index+1).padStart(2,'0')}</span>
            <span className="qe-student-chapter-title"><small><LocaleText hinglish="CHAPTER" english="CHAPTER" /> {group.code!==group.name?'· '+group.code:''}</small><strong>{group.name}</strong><span>{group.lessons.length} topics · {chapterDocuments.length} PDFs</span></span>
            <span className="qe-student-chapter-indicator">{expanded?'−':'+'}</span>
          </button>
          {chapterDocuments.map(item=><Link key={item.document_id} className="qe-student-pdf-link qe-chapter-pdf-row" href={subjectDocumentHref(item.document_id)} onClick={()=>rememberTrackPosition(group.code)}>
            <span className="qe-student-pdf-mark" aria-hidden="true"><svg viewBox="0 0 24 28"><path d="M4 1.5h10l6 6V26.5H4z"/><path d="M14 1.5v6h6M6.5 16h11M6.5 20h11"/></svg><small>PDF</small></span>
            <span className="qe-student-pdf-copy"><strong>{item.title||item.chapter_name||tx('Chapter PDF','Chapter PDF')}</strong><small>{item.page_count} {tx('पृष्ठ','pages')} · {textbookChapterTitle(item, learningLanguage)}</small></span>
            <span className="qe-student-pdf-action"><LocaleText hinglish="PDF खोलें" english="Open PDF" /> ↗</span>
          </Link>)}
          {expanded&&<div className="qe-student-chapter-content">
            {group.lessons.length>0&&<div className="qe-student-topic-list">{group.lessons.map((item,topicIndex)=>{
              const mcqCount=Math.max(0,Number(item.mcq_count||0));
              const mcqAnswered=Math.max(0,Math.min(mcqCount,Number(item.mcq_answered_count||0)));
              const mcqsComplete=mcqCount===0||mcqAnswered>=mcqCount;
              const practiceCompleted=Number(item.practice_completed_attempts||0)>0;
              const topicComplete=mcqsComplete&&practiceCompleted;
              const bestCorrect=Number(item.practice_best_correct_count||0);
              const bestGraded=Number(item.practice_best_graded_count||0);
              const bestPercent=Number(item.practice_best_percent||0);
              const activeAnswered=Math.max(0,Number(item.practice_active_answered_count||0));
              const activeQuestionCount=Math.max(0,Number(item.practice_active_question_count||0));
              return <Link key={item.id} className={'qe-student-topic-row'+(topicComplete?' is-complete':'')} href={'/student/learn?subjectCode='+trackBrowse.subjectCode+'&lessonId='+item.id}>
                <span className="qe-student-topic-index" aria-label={'Microtopic '+(topicIndex+1)}>{String(topicIndex+1).padStart(2,'0')}</span>
                <span className="qe-student-topic-copy"><strong>{item.title}</strong>
                  <small>{item.estimated_minutes} <LocaleText hinglish="मिनट · प्रकाशित microtopic" english="min · published microtopic" /></small>
                  <span className="qe-student-topic-progress">
                    <small className={mcqsComplete?'is-done':''}>{mcqCount>0?'MCQ '+mcqAnswered+'/'+mcqCount:'MCQs not required'}</small>
                    {bestGraded>0
                      ? <small className="is-best"><LocaleText hinglish="Best" english="Best" /> {bestCorrect}/{bestGraded} · {bestPercent}%</small>
                      : practiceCompleted
                        ? <small className="is-best"><LocaleText hinglish="Practice complete" english="Practice complete" /></small>
                        : activeQuestionCount>0
                          ? <small><LocaleText hinglish="Practice saved" english="Practice saved" /> {activeAnswered}/{activeQuestionCount}</small>
                          : <small><LocaleText hinglish="Practice शुरू नहीं" english="Practice not started" /></small>}
                    {practiceCompleted&&activeQuestionCount>0&&<small><LocaleText hinglish="New attempt" english="New attempt" /> {activeAnswered}/{activeQuestionCount}</small>}
                  </span>
                </span>
                {topicComplete
                  ? <span className="qe-student-topic-complete" role="img" aria-label={tx('विषय पूरा हुआ','Topic completed')} title={tx('MCQ और practice पूरे','MCQs and practice completed')}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.2 4.1L19 6.8"/></svg></span>
                  : <span className="qe-student-topic-arrow" aria-hidden="true">→</span>}
              </Link>;
            })}</div>}
            {chapterDocuments.length===0&&group.lessons.length===0&&<p className="qe-student-chapter-empty"><LocaleText hinglish="इस chapter में अभी PDF या published topic नहीं है।" english="No PDF or published topic is available in this chapter yet." /></p>}
          </div>}
        </section>;
      })}
      <div className="qe-subject-reading-footer"><Link href="/student"><LocaleText hinglish="← Student home" english="← Student home" /></Link><Link href="/student/textbooks"><LocaleText hinglish="सभी textbooks देखें →" english="View all textbooks →" /></Link></div>
    </section>
  </main>;
  if(!lesson) return <main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow"><LocaleText hinglish="Lesson load हो रहा है…" english="Loading lesson…" /></div></section></main>;

  return <main className="lesson-page">
    <section className="lesson-wrap">
      <div className="lesson-meta">
        <span className="eyebrow"><LocaleText hinglish="कक्षा" english="Class" /> {lesson.class_code} · {lesson.subject_code==='maths'?<LocaleText hinglish="गणित" english="Mathematics" />:<LocaleText hinglish="विज्ञान" english="Science" />} · {lesson.chapter_name}</span>
        <span>Lesson {currentIndex+1} / {lessons.length}</span>
      </div>
      <h1>{lesson.title}</h1>
      <p className="lesson-intro">{lesson.summary}</p>
      {!lesson.translation_complete&&<div className="feedback" role="status">
        <span><LocaleText hinglish="भाषा समीक्षा" english="Language coverage" /></span>
        <span><LocaleText
          hinglish="इस पाठ के कुछ हिस्सों की चुनी हुई भाषा में समीक्षा-स्वीकृत सामग्री अभी उपलब्ध नहीं है। जहाँ अनुवाद नहीं है, वहाँ मूल सामग्री दिखाई दे सकती है।"
          english="Some parts of this lesson do not yet have reviewed content in the selected language. Original-language text may appear where a translation is not available."
        /></span>
      </div>}
      <div className="feedback"><span><LocaleText hinglish="Learning path" english="Learning path" /></span><span><LocaleText hinglish={learnerPathLabel(orderedBlocks,lessonMcqs.length).hinglish} english={learnerPathLabel(orderedBlocks,lessonMcqs.length).english} /></span></div>

      {learningBlocks.map(block=><Block key={block.id} block={block} onTutorOpen={()=>setTutorOpen(true)}/>)}
      {help !== 'none' && <div className="feedback"><b>{help.replaceAll('_',' ')} <LocaleText hinglish="सहायता" english="help" /></b><span><LocaleText hinglish="पहले concept को अपने words में समझें, फिर example देखकर दोबारा try करें।" english="Explain the concept in your own words, then review the example and try again." /></span></div>}

      <div className="content-heading"><h2><LocaleText hinglish="अब MCQ से जाँचें" english="Check your understanding with MCQs" /></h2><span>{attemptedMcqs} / {lessonMcqs.length} <LocaleText hinglish="attempt किए" english="attempted" /></span></div>
      {lessonMcqs.length>0&&<div className="qe-mcq-progress" aria-label={tx('MCQ की प्रगति','MCQ progress')}><div><span><LocaleText hinglish="सभी सवालों के उत्तर दें" english="Answer every question to continue" /></span><strong>{Math.round(attemptedMcqs/lessonMcqs.length*100)}%</strong></div><i><span style={{width:(attemptedMcqs/lessonMcqs.length*100)+'%'}}/></i></div>}
      {lessonMcqs.length ? lessonMcqs.map((q,index)=><QuestionCard key={q.id} q={q} index={index+1} onResult={id=>setMcqAttempted(old=>({...old,[id]:true}))} onTutorOpen={id=>{setTutorQuestionId(id);setTutorOpen(true)}}/>) : <div className="concept-card"><p><LocaleText hinglish="इस lesson के लिए MCQ अभी publish नहीं हुए हैं। आप फिर भी इस topic का उपलब्ध practice set खोल सकते हैं।" english="MCQs have not been published for this lesson yet. You can still open any approved practice set for this topic." /></p></div>}

      <section className="concept-card practice-launch-card">
        <span className="concept-kicker"><LocaleText hinglish="अगला चरण" english="Next step" /></span>
        <h2><LocaleText hinglish="अब इसी micro-topic का अभ्यास करें" english="Now practise this micro-topic" /></h2>
        <p><LocaleText hinglish="QuantaEdge इसी lesson के learning objective/concept से जुड़े approved questions चुनेगा। आपको topics या question types चुनने की ज़रूरत नहीं है।" english="QuantaEdge selects approved questions mapped to this lesson's learning objective and concept. You do not need to choose topics or question types." /></p>
        {canStartTopicPractice
          ? <Link href={'/student/practice?lessonId='+lesson.id} className="button button-dark"><LocaleText hinglish="Practice शुरू करें →" english="Start topic practice →" /></Link>
          : <div className="qe-practice-gate"><button type="button" className="button button-dark" disabled><LocaleText hinglish="पहले MCQ पूरे करें" english="Finish the MCQs first" /></button><p><LocaleText hinglish={`आगे बढ़ने के लिए ${lessonMcqs.length-attemptedMcqs} सवालों के उत्तर देना बाकी है।`} english={`${lessonMcqs.length-attemptedMcqs} question(s) still need an answer before you can continue.`} /></p></div>}
      </section>

      {recapBlocks.length>0&&<section className="lesson-recap" aria-label={tx('पाठ का दोहराव','Lesson recap')}>
        <div className="content-heading">
          <h2><LocaleText hinglish="अब दोहराएँ" english="Recap" /></h2>
          <span><LocaleText hinglish="मुख्य बातें" english="Key takeaways" /></span>
        </div>
        {recapBlocks.map(block=><Block key={block.id} block={block} onTutorOpen={()=>setTutorOpen(true)}/>)}
      </section>}

      {!tutorOpen && <button type="button" className="tutor-launch" onClick={()=>setTutorOpen(true)} aria-label={tx('AI tutor खोलें','Open AI tutor')} title={tx('AI tutor खोलें','Open AI tutor')}>
        <span className="tutor-launch-icon" aria-hidden="true">✦</span>
      </button>}
      <TutorDock lessonId={lesson.id} open={tutorOpen} onClose={()=>setTutorOpen(false)} currentQuestionId={tutorQuestionId} />

      <div className="lesson-next">
        {currentIndex>0 ? <button type="button" className="button button-small" onClick={()=>loadLesson(lessons[currentIndex-1].id)}><LocaleText hinglish="← पिछला" english="← Previous" /></button> : <span/>}
        {currentIndex>=0 && currentIndex<lessons.length-1
          ? <button type="button" className="button button-dark button-small" onClick={()=>loadLesson(lessons[currentIndex+1].id)}><LocaleText hinglish="अगला lesson →" english="Next lesson →" /></button>
          : <Link href="/student" className="button button-dark button-small"><LocaleText hinglish="Student home →" english="Student home →" /></Link>}
      </div>
    </section>
  </main>;
}
