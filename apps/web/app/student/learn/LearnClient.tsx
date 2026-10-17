'use client';

import { translateUi } from '../../i18n/catalog';
import { UiText } from '../../i18n/useUiTranslation';
import { LocaleText, useLocale } from '../../components/LanguageProvider';
import MathText from '../../components/MathText';
import AnswerFeedback from '../../components/AnswerFeedback';


import Link from 'next/link';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import TutorDock from './TutorDock';
import StructuredLessonExperience from '../structured-lessons/StructuredLessonExperience';
import type { StructuredLesson } from '../structured-lessons/contract';

type Lesson = {
  id:number; code:string; title:string; summary:string; estimated_minutes:number; class_code:string;
  chapter_name:string; subject_name:string; subject_code:string; chapter_code:string;
  lesson_translated?:boolean; chapter_translated?:boolean; subject_translated?:boolean;
  mcq_count?:number; mcq_answered_count?:number;
  practice_completed_attempts?:number; practice_best_correct_count?:number|null;
  practice_best_graded_count?:number|null; practice_best_percent?:number|null;
  practice_active_answered_count?:number|null; practice_active_question_count?:number|null;
};
type TrackBrowse = {
  classCode:string; subjectCode:string; subjectName:string; lessons:Lesson[]; courseId?:number|null; courseName?:string|null; programCode?:string|null;
};
type StudentDocument = {document_id:number;scope:'SUBJECT_BOOK'|'CHAPTER_PDF';title:string;page_count:number;last_page?:number;subject_code:string;subject_name?:string;chapter_code?:string|null;chapter_name?:string|null;chapter_translated?:boolean;edition?:string|null;language?:'hi'|'en'};

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
  translation_gaps?:string[];
  structured_lesson?:StructuredLesson;
  translation_coverage?:{blocks_total:number;blocks_published:number;questions_total:number;questions_published:number;class?:boolean;lesson?:boolean;chapter?:boolean;subject?:boolean;objective?:boolean};
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

const VISUAL_KIND_LABELS:Record<string,{en:string;hi:string}>={
 PHOTO:{en:'Photo',hi:'फ़ोटो'},ILLUSTRATION:{en:'Illustration',hi:'चित्रण'},
 DIAGRAM:{en:'Diagram',hi:'आरेख'},CHART:{en:'Chart / graph',hi:'चार्ट / ग्राफ़'},
 TABLE:{en:'Table / comparison',hi:'तालिका / तुलना'},INFOGRAPHIC:{en:'Infographic',hi:'सूचनात्मक चित्र'},
 SEQUENCE:{en:'Sequence / process',hi:'क्रम / प्रक्रिया'},
 TEXTBOOK_FIGURE:{en:'Learning visual',hi:'सीखने का चित्र'},UNCLASSIFIED:{en:'Learning visual',hi:'सीखने का चित्र'}
};
function visualKindLabel(kind:string, locale:string){
 const item=VISUAL_KIND_LABELS[String(kind||'TEXTBOOK_FIGURE').toUpperCase()]||VISUAL_KIND_LABELS.TEXTBOOK_FIGURE;
 return locale==='english'?item.en:item.hi;
}
function Block({ block, onTutorOpen }:{block:Detail['blocks'][number];onTutorOpen:()=>void}) {
  const { locale } = useLocale();
  const [visualZoomOpen,setVisualZoomOpen]=useState(false);

  const data=parse<any>(block.content);
  if (block.block_type==='EXPLANATION' || block.block_type==='PREREQUISITE') return <div className="concept-card">
    <span className="concept-kicker">{data.heading ?? data.title ?? (block.block_type==='PREREQUISITE'?<UiText message="legacy.web.what.you.already.know.82c2662c" />:<UiText message="legacy.web.understand.499af863" />)}</span>
    {data.html ? <div className="lesson-rich-content" dangerouslySetInnerHTML={{__html:safeRichHtml(stackFractionsInHtml(String(data.html)))}}/> : <p><MathText text={String(data.body ?? data.description ?? '')}/></p>}
    {data.keyPoints?.map((x:string,i:number)=><div className="feedback" key={i}><span>• {x}</span></div>)}
  </div>;
  if (block.block_type==='WORKED_EXAMPLE') return <div className="concept-card">
    <span className="concept-kicker"><UiText message="legacy.web.worked.example.84fe1ccd" /></span><h3>{data.title ?? <UiText message="legacy.web.example.4b2669a8" />}</h3><p><MathText text={String(data.problem || data.prompt || '')}/></p>
    {data.steps?.map((x:string,i:number)=><div className="feedback" key={i}><span>{i+1}. <MathText text={x}/></span></div>)}
    {data.answer && <p className="qe-model-answer"><strong><UiText message="legacy.web.model.answer.16b8e37d" /></strong> <MathText text={String(data.answer)}/></p>}
  </div>;
  if (['GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE','HINT'].includes(block.block_type)) return <div className="concept-card">
    <span className="concept-kicker">{data.title ?? (block.block_type==='GUIDED_PRACTICE'?<UiText message="legacy.web.try.together.2813d564" />:block.block_type==='HINT'?<UiText message="legacy.web.helpful.hint.e653ab3f" />:<UiText message="legacy.web.try.it.yourself.817b2366" />)}</span><p><MathText text={String(data.prompt || data.body || '')}/></p>{data.hint && <div className="feedback"><span><UiText message="legacy.web.hint.0b3b3ecd" /> <MathText text={String(data.hint)}/></span></div>}
  </div>;
  if (['IMAGE','DIAGRAM','VIDEO','AUDIO','ANIMATION'].includes(block.block_type)) {
    const url=String(data.url||'');
    const directVideo=/\.(mp4|webm|ogg)(\?.*)?$/i.test(url);
    const youtube=/youtube\.com\/watch\?/i.test(url)||/youtu\.be\//i.test(url);
    let embed=url;
    if(youtube){try{const parsedUrl=new URL(url);const id=parsedUrl.hostname.includes('youtu.be')?parsedUrl.pathname.slice(1):parsedUrl.searchParams.get('v')||'';if(id)embed='https://www.youtube-nocookie.com/embed/'+encodeURIComponent(id)}catch{}}
    const isVisual=['IMAGE','DIAGRAM','ANIMATION'].includes(block.block_type);
    const localized=(key:string)=>String((locale==='english'?data[key]:data[key+'_hi'])||data[key]||'');
    const title=localized('title')||(block.block_type==='AUDIO'?(locale==='english'?'Listen and learn':'सुनकर समझें'):block.block_type==='VIDEO'?(locale==='english'?'Watch and learn':'देखकर समझें'):(locale==='english'?'Learning visual':'सीखने का चित्र'));
    const description=localized('description');
    const alt=String((locale==='english'?data.alt:data.alt_hi)||data.alt||title||'Learning visual');
    const caption=localized('caption');
    const visualKind=visualKindLabel(String(data.visual_kind|| (block.block_type==='DIAGRAM'?'DIAGRAM':'TEXTBOOK_FIGURE')),locale);
    return <div className="concept-card qe-lesson-media-card">
      <span className="concept-kicker">{title}</span>
      {isVisual&&<span className="qe-lesson-visual-kind">{visualKind}</span>}
      {description && <p>{description}</p>}
      {url && isVisual && directVideo && <video className="lesson-visual-video" controls preload="metadata" playsInline src={url}/>}
      {url && isVisual && !directVideo && <button type="button" className="qe-lesson-visual-open" onClick={()=>setVisualZoomOpen(true)} aria-label={locale==='english'?'Expand learning visual':'चित्र बड़ा करके देखें'}><img className="lesson-visual-media" src={url} alt={alt} loading="lazy"/></button>}
      {url && block.block_type==='VIDEO' && directVideo && <video className="lesson-visual-video" controls preload="metadata" playsInline src={url}/>}
      {url && block.block_type==='VIDEO' && youtube && <div className="lesson-visual-embed"><iframe src={embed} title={title} allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowFullScreen referrerPolicy="strict-origin-when-cross-origin"/></div>}
      {url && block.block_type==='AUDIO' && <audio className="lesson-visual-audio" controls preload="metadata" src={url}/>}
      {url && block.block_type==='VIDEO' && !directVideo && !youtube && <a href={url} target="_blank" rel="noreferrer" className="button button-small"><UiText message="legacy.web.watch.video.982362cc" /></a>}
      {url && block.block_type==='AUDIO' && <a href={url} target="_blank" rel="noreferrer" className="text-link"><UiText message="legacy.web.open.audio.d6296323" /></a>}
      {!url && <div className="feedback"><span>◈</span><span>{description||alt||(locale==='english'?'No visual asset is available yet.':'अभी कोई चित्र उपलब्ध नहीं है।')}</span></div>}
      {caption && <p className="lesson-media-caption">{caption}</p>}
      {data.source_candidate_id && <div className="qe-lesson-source-attribution"><span>{locale==='english'?'Source reference':'स्रोत संदर्भ'}</span><span>{String(data.source_book_title||data.source_book_id||'Textbook')} · {locale==='english'?'PDF page':'PDF पृष्ठ'} {String(data.source_pdf_page_number||'—')}{data.source_edition?' · '+String(data.source_edition):''}</span>{data.source_page_url&&<a href={String(data.source_page_url)} target="_blank" rel="noreferrer">{locale==='english'?'View source page ↗':'स्रोत पृष्ठ देखें ↗'}</a>}</div>}
      {visualZoomOpen&&isVisual&&<div className="qe-visual-lightbox" role="dialog" aria-modal="true" aria-label={title} onKeyDown={event=>{if(event.key==='Escape')setVisualZoomOpen(false)}}><button type="button" className="qe-visual-lightbox-close" onClick={()=>setVisualZoomOpen(false)} aria-label={locale==='english'?'Close visual':'चित्र बंद करें'}>×</button>{directVideo?<video controls autoPlay playsInline src={url}/>:<img src={url} alt={alt}/ >}{caption&&<p>{caption}</p>}</div>}
    </div>;
  }
  if (block.block_type==='AI_HELP') return <div className="ai-help"><div className="ai-icon">✦</div><div><strong><UiText message="legacy.web.ai.tutor.30676145" /></strong><p><UiText message="legacy.web.if.you.get.stuck.ask.for.a.hint.explan.cc1a4459" /></p><button type="button" className="button button-dark button-small" onClick={onTutorOpen}><UiText message="legacy.web.open.tutor.3a75221b" /></button></div></div>;
  if (block.block_type==='SUMMARY' || block.block_type==='RECAP') return <div className="concept-card">{data.points?.map((x:string,i:number)=><div className="feedback" key={i}><span>✓ <MathText text={x}/></span></div>)}</div>;
  return null;
}

function QuestionCard({q,index,onResult,onTutorOpen}:{q:Question;index:number;onResult:(id:number,result:any)=>void;onTutorOpen:(id:number)=>void}) {
  const { locale, learningLanguage } = useLocale();
  const searchParams = useSearchParams();
  const [value,setValue]=useState('');
  const [busy,setBusy]=useState(false);
  const [result,setResult]=useState<any>(null);
  const [answerError,setAnswerError]=useState('');
  const options=parse<{key:string;label:string}[]>(q.options||'[]');
  const submitted=result?.correct===true;
  const questionLabel=q.question_type==='MCQ'?translateUi(locale, 'legacy.web.multiple.choice.649280af'):q.question_type==='TRUE_FALSE'?translateUi(locale, 'legacy.web.true.or.false.43e5661e'):q.question_type==='NUMERICAL'?translateUi(locale, 'legacy.web.numerical.question.3993dcf1'):q.question_type.replaceAll('_',' ');

  async function submit(next:string) {
    setValue(next);
    if (!next.trim() || submitted || busy) return;
    setBusy(true);setAnswerError('');setResult(null);
    try {
      const language=learningLanguage;
      const response=await fetch('/api/v1/learning/questions/'+q.id+'/answer?language='+language+(searchParams.get('courseId')?'&courseId='+encodeURIComponent(searchParams.get('courseId')!):(searchParams.get('programCode')?'&programCode='+encodeURIComponent(searchParams.get('programCode')!):'')),{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({answer:next})
      });
      const body=await response.json();
      if(!response.ok) throw new Error(body.detail||body.message||translateUi(locale, 'legacy.web.answer.could.not.be.checked.bbb567f8'));
      setResult(body);
      onResult(q.id,body);
    } catch(e:any) {
      setAnswerError(e.message||translateUi(locale, 'legacy.web.answer.could.not.be.checked.please.try.716acfb3'));
    } finally { setBusy(false); }
  }

  return <article className={'qe-question-card '+(result?.correct===false?'has-answer-incorrect':result?.correct===true?'has-answer-correct':'')}>
    <div className="qe-question-card__top">
      <span className="qe-question-index"><span>{String(index).padStart(2,'0')}</span></span>
      <div className="qe-question-card__labels">
        <span className="qe-question-type">{questionLabel}</span>
        <span className="qe-question-context">{q.exam_format||translateUi(locale, 'legacy.web.lesson.check.73221abd')}{q.marks?' · '+q.marks+' '+translateUi(locale, 'legacy.web.marks.57368a8d'):''}</span>
      </div>
      <span className="qe-question-source">{q.source_kind==='TEXTBOOK_ALIGNED'?<UiText message="legacy.web.textbook.aligned.0545ccf1" />:q.source_kind?<MathText text={q.source_kind.replaceAll('_',' ').toLowerCase()}/>:locale==='english'?'Practice question':'अभ्यास प्रश्न'}</span>
    </div>
    <div className="qe-question-card__prompt"><MathText text={q.prompt}/></div>

    {options.length>0 ? <div className="qe-answer-options" role="group" aria-label={translateUi(locale, 'legacy.web.answer.choices.436a84eb')}>
      {options.map((o,i)=>{
        const chosen=value===o.key;
        const stateClass=chosen&&result?.correct===false?'is-incorrect':chosen&&result?.correct===true?'is-correct':chosen?'is-selected':'';
        return <button type="button" key={o.key} disabled={busy||submitted} aria-pressed={chosen} className={'qe-answer-option '+stateClass} onClick={()=>void submit(o.key)}>
          <span className="qe-answer-option__key">{o.key||String.fromCharCode(65+i)}</span>
          <span className="qe-answer-option__text"><MathText text={o.label}/></span>
          {chosen&&result?.correct===true&&<span className="qe-answer-option__status" aria-label={translateUi(locale, 'legacy.web.correct.d01db0a2')}>✓</span>}
          {chosen&&result?.correct===false&&<span className="qe-answer-option__status" aria-label={translateUi(locale, 'legacy.web.incorrect.c7d63751')}>!</span>}
        </button>;
      })}
    </div> : <div className={'qe-answer-entry '+(result?.correct===false?'is-incorrect':result?.correct===true?'is-correct':'')}>
      {q.response_mode==='structured-text' || q.question_type==='LONG_ANSWER' || q.question_type==='SHORT_ANSWER' ?
        <textarea value={value} disabled={busy||submitted} onChange={e=>setValue(e.target.value)} placeholder={translateUi(locale, 'legacy.web.write.your.answer.or.reasoning.here.0daf3a15')} rows={q.question_type==='LONG_ANSWER'?6:4}/> :
        <input value={value} disabled={busy||submitted} onChange={e=>setValue(e.target.value)} placeholder={translateUi(locale, 'legacy.web.type.your.answer.3952914d')}/>}
      <div className="qe-answer-entry__actions">
        <button type="button" className="button button-dark button-small" disabled={busy||!value.trim()||submitted} onClick={()=>void submit(value)}>{busy?<UiText message="legacy.web.checking.45fef9a5" />:<UiText message="legacy.web.check.answer.293518af" />}</button>
        <button type="button" className="qe-tutor-link" onClick={()=>onTutorOpen(q.id)}><span>✦</span><UiText message="legacy.web.ask.the.tutor.3a67dbaf" /></button>
      </div>
    </div>}

    {answerError && <div className="qe-answer-feedback is-incorrect" role="alert"><span className="qe-answer-feedback__icon">!</span><div><strong><UiText message="legacy.web.could.not.check.answer.875a5a14" /></strong><p><MathText text={answerError}/></p></div></div>}
    {result && <AnswerFeedback
      outcome={result.correct===true?'correct':result.correct===false?'incorrect':'ungraded'}
      title={result.correct===true?<UiText message="legacy.web.that.s.correct.eb4b4a6d" />:result.correct===false?<UiText message="legacy.web.not.quite.try.again.caea98e7" />:<UiText message="legacy.web.answer.saved.b1752a46" />}
      message={result.correct===true?<UiText message="legacy.web.great.work.review.the.explanation.to.s.56045018" />:result.correct===false?<UiText message="legacy.web.no.worries.read.the.explanation.then.c.cc8aee7d" />:<UiText message="legacy.web.this.answer.may.need.teacher.or.rubric.b6c2d2ed" />}
      explanation={result.explanation?<MathText text={String(result.explanation)}/>:undefined}
      explanationLabel={<UiText message="legacy.web.why.this.works.213b7eaf" />}
    />}
  </article>;
}

export default function LearnClient() {
  const { locale, learningLanguage } = useLocale();
  const [lessons,setLessons]=useState<Lesson[]>([]);
  const [lesson,setLesson]=useState<Detail|null>(null);
  const [trackBrowse,setTrackBrowse]=useState<TrackBrowse|null>(null);
  const [studentDocuments,setStudentDocuments]=useState<StudentDocument[]>([]);
  const [documentsLoaded,setDocumentsLoaded]=useState(false);
  const [openChapter,setOpenChapter]=useState('');
  const [lastOpenedLessonId,setLastOpenedLessonId]=useState<number|null>(null);
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
  const courseIdValue=searchParams.get('courseId')||'';
  const selectedCourseId=/^\d+$/.test(courseIdValue)&&Number(courseIdValue)>0?Number(courseIdValue):null;
  const selectedProgram=searchParams.get('programCode');
  const courseQuery=selectedCourseId!==null?'&courseId='+selectedCourseId:selectedProgram?'&programCode='+encodeURIComponent(selectedProgram):'';
  const courseStartQuery=selectedCourseId!==null?'?courseId='+selectedCourseId:selectedProgram?'?programCode='+encodeURIComponent(selectedProgram):'';
  const [courseDisplayName,setCourseDisplayName]=useState('');

  const lessonSwipeStartRef=useRef<{x:number;y:number}|null>(null);
  const currentIndex=useMemo(()=>lesson ? lessons.findIndex(x=>x.id===lesson.id) : -1,[lesson,lessons]);
  const orderedBlocks=useMemo(()=>lesson ? orderLearnerBlocks(lesson.blocks) : [],[lesson]);
  // The lesson screen teaches the concept first. Recap is rendered after the student's
  // automatically selected micro-topic practice, not between the MCQ check and practice.
  const learningBlocks=useMemo(
    ()=>orderedBlocks.filter(block=>block.block_type!=='SUMMARY'&&block.block_type!=='RECAP'),
    [orderedBlocks]);
  // Prefer the dedicated RECAP block; use SUMMARY only for older lessons that lack one.
  // Rendering both types produced duplicate recap cards for lessons carrying both blocks.
  const recapBlock=useMemo(
    ()=>orderedBlocks.find(block=>block.block_type==='RECAP')
      ?? orderedBlocks.find(block=>block.block_type==='SUMMARY'),
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
          // The lesson list is already localized for the selected learning language.
          // A fallback PDF must never overwrite that canonical translated chapter title.
          return;
        }
        const name=textbookChapterTitle(document,learningLanguage);
        const key=document.chapter_code||name||('chapter-'+document.document_id);
        grouped.set(key,{code:key,name,lessons:[]});
      });
    return Array.from(grouped.values());
  },[trackBrowse,studentDocuments,locale,learningLanguage]);

  async function loadLesson(id:number) {
    try {
      const started=await fetch('/api/v1/learning/lessons/'+id+'/start'+courseStartQuery,{method:'POST'});
      if(!started.ok) throw new Error('lesson progress');
      const detail=await fetch('/api/v1/learning/lessons/'+id+'?language='+learningLanguage+courseQuery).then(r=>{
        if(!r.ok) throw new Error('lesson');
        return r.json();
      }) as Detail;
      setLesson(detail);
      localStorage.setItem('qe:student:last-open-lesson:'+(selectedCourseId!==null?'course-'+selectedCourseId+':':selectedProgram?'program-'+selectedProgram+':':'')+detail.class_code+':'+detail.subject_code,String(detail.id));
      setMcqAttempted(Object.fromEntries(detail.questions
        .filter(question=>question.question_type==='MCQ'&&question.attempted)
        .map(question=>[question.id,true])));
    } catch {
      setError(translateUi(locale, 'legacy.web.there.was.a.problem.opening.this.lesso.21e2eae0'));
    }
  }

  useEffect(()=>{
    let cancelled=false;
    async function load(){
      setError('');
      try{
        const me=await fetch('/api/v1/students/me'+(selectedCourseId!==null?'?courseId='+selectedCourseId:selectedProgram?'?programCode='+encodeURIComponent(selectedProgram):''));
        if(me.status===401||me.status===403){router.replace('/login/student');return;}
        const student=await me.json();
        if(!me.ok) throw new Error(student.message||translateUi(locale, 'legacy.web.student.profile.was.not.found.0d806b0d'));
        setCourseDisplayName(String(student.programName||''));
        const requestedId=Number(searchParams.get('lessonId')||0);
        const selectedSubject=searchParams.get('subjectCode');
        if(!requestedId && selectedCourseId!==null && !selectedSubject) throw new Error(translateUi(locale, 'legacy.web.choose.a.subject.from.this.course.firs.ccf1b720'));
        const scopedCourse=selectedCourseId!==null||Boolean(selectedProgram);
        if(!requestedId && selectedSubject && (!/^[a-z0-9][a-z0-9_-]{0,39}$/i.test(selectedSubject)||(!scopedCourse&&!['maths','science'].includes(selectedSubject)))) throw new Error(translateUi(locale, 'legacy.web.invalid.subject.4f71f3e5'));
        const programQuery=courseQuery;
        if(!requestedId && selectedSubject){
          const selectedLanguage=learningLanguage;
          const listResponse=await fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(String(student.class_code))+'&subjectCode='+encodeURIComponent(selectedSubject)+'&language='+selectedLanguage+programQuery);
          const listBody=await listResponse.json();
          if(!listResponse.ok) throw new Error(listBody.message||'विषय की पाठ सूची नहीं खुल पाई।');
          if(cancelled)return;
          const trackLessons=listBody as Lesson[];
          if(cancelled)return;
          const subjectName=selectedSubject==='maths'?translateUi(locale, 'legacy.web.mathematics.4cf6be1a'):translateUi(locale, 'legacy.web.science.f85a10aa');
          setLessons(trackLessons);setLesson(null);setSessionId(null);setSessionStarted(null);setMcqAttempted({});
          setStudentDocuments([]);
          setTrackBrowse({classCode:String(trackLessons[0]?.class_code||student.class_code),subjectCode:selectedSubject,subjectName:String(trackLessons[0]?.subject_name||subjectName),lessons:trackLessons,courseId:selectedCourseId,courseName:String(student.programName||''),programCode:selectedProgram});
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
        if(!targetId) throw new Error(translateUi(locale, 'legacy.web.no.recommended.lesson.is.available.yet.da29dcc9'));
        const selectedLanguage=learningLanguage;
        const detail=await fetch('/api/v1/learning/lessons/'+targetId+'?language='+selectedLanguage+programQuery);
        if(!detail.ok) throw new Error('lesson');
        const d=await detail.json() as Detail;
        const list=await fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(String(student.class_code))+'&subjectCode='+encodeURIComponent(d.subject_code)+'&language='+selectedLanguage+programQuery).then(r=>r.json()) as Lesson[];
        setLessons(list);
        await fetch('/api/v1/learning/lessons/'+targetId+'/start'+courseStartQuery,{method:'POST'});
        const sr=await fetch('/api/v1/learning/sessions/start',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({source:'LESSON',...(selectedCourseId!==null?{courseId:selectedCourseId}:{}),...(selectedProgram?{programCode:selectedProgram}:{})})});
        if(sr.ok){const sb=await sr.json();if(!cancelled){setSessionId(Number(sb.sessionId));setSessionStarted(Date.now());}}
        if(!cancelled){
          localStorage.setItem('qe:student:last-open-lesson:'+(selectedCourseId!==null?'course-'+selectedCourseId+':':selectedProgram?'program-'+selectedProgram+':':'')+d.class_code+':'+d.subject_code,String(d.id));
          setStudentDocuments([]);setTrackBrowse(null);setLesson(d);
          setMcqAttempted(Object.fromEntries(d.questions
            .filter(question=>question.question_type==='MCQ'&&question.attempted)
            .map(question=>[question.id,true])));
        }
      }catch(e:any){if(!cancelled)setError(e.message==='Student profile नहीं मिला'?translateUi(locale, 'legacy.web.student.sign.in.is.required.3c988af9'):translateUi(locale, 'legacy.web.unable.to.load.the.lesson.52412c24'));}
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
            +'&subjectCode='+encodeURIComponent(trackBrowse.subjectCode)+'&language='+language
            +(trackBrowse.courseId!==null&&trackBrowse.courseId!==undefined?'&courseId='+trackBrowse.courseId:(trackBrowse.programCode?'&programCode='+encodeURIComponent(trackBrowse.programCode):'')),{cache:'no-store'});
          const rows=await response.json();
          if(!response.ok)throw new Error(rows?.message||'Could not refresh lesson language.');
          if(!cancelled){
            const newLessons=Array.isArray(rows)?rows as Lesson[]:[];
            setLessons(newLessons);
            setTrackBrowse(old=>old?{...old,subjectName:old.subjectCode==='maths'?translateUi(locale, 'legacy.web.mathematics.4cf6be1a'):translateUi(locale, 'legacy.web.science.f85a10aa'),lessons:newLessons}:old);
          }
          return;
        }
        if(lesson){
          const [detailResponse,listResponse]=await Promise.all([
            fetch('/api/v1/learning/lessons/'+lesson.id+'?language='+language+courseQuery,{cache:'no-store'}),
            fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(lesson.class_code)
              +'&subjectCode='+encodeURIComponent(lesson.subject_code)+'&language='+language+courseQuery,{cache:'no-store'})
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
  },[learningLanguage,lesson?.id,lesson?.class_code,lesson?.subject_code,trackBrowse?.classCode,trackBrowse?.subjectCode,selectedCourseId,selectedProgram]);

  // Fetch the selected language edition independently: changing locale must not restart
  // the lesson, reset the session, or issue a second progress-start request.
  useEffect(()=>{
    const subjectCode=trackBrowse?.subjectCode||lesson?.subject_code||searchParams.get('subjectCode');
    const scopedCourse=selectedCourseId!==null||Boolean(selectedProgram);
    if(!subjectCode||(!scopedCourse&&!['maths','science'].includes(subjectCode))){
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
      fetch('/api/v1/learning/documents?subjectCode='+encodeURIComponent(subjectCode)+'&language='+language+'&displayLanguage='+preferredLanguage
        +courseQuery,{cache:'no-store'})
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
  },[learningLanguage,trackBrowse?.subjectCode,lesson?.subject_code,searchParams,selectedCourseId,selectedProgram]);

  useEffect(()=>{
    return ()=>{
      if(sessionId && sessionStarted){
        const minutes=Math.max(0,Math.min(240,Math.round((Date.now()-sessionStarted)/60000)));
        void fetch('/api/v1/learning/sessions/'+sessionId+'/end',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({minutes}),keepalive:true});
      }
    };
  },[sessionId,sessionStarted]);

  const chapterStorageScope=trackBrowse?(trackBrowse.courseId!==null&&trackBrowse.courseId!==undefined?'course-'+trackBrowse.courseId+':':trackBrowse.programCode?'program-'+trackBrowse.programCode+':':'')+trackBrowse.classCode+':'+trackBrowse.subjectCode:'';
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
    if(!trackBrowse||!chapterStorageScope){setLastOpenedLessonId(null);return;}
    const remembered=Number(localStorage.getItem('qe:student:last-open-lesson:'+chapterStorageScope)||0);
    setLastOpenedLessonId(trackBrowse.lessons.some(item=>Number(item.id)===remembered)?remembered:null);
  },[trackBrowse?.classCode,trackBrowse?.subjectCode,trackBrowse?.lessons,chapterStorageScope]);

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

  function rememberTrackPosition(chapterCode?:string,lessonId?:number){
    if(!trackBrowse)return;
    if(chapterCode){
      setOpenChapter(chapterCode);
      localStorage.setItem('qe:student:open-chapter:'+chapterStorageScope,chapterCode);
    }
    if(lessonId){
      localStorage.setItem('qe:student:last-open-lesson:'+chapterStorageScope,String(lessonId));
      setLastOpenedLessonId(lessonId);
    }
    localStorage.setItem('qe:student:learn-scroll:'+chapterStorageScope,String(window.scrollY));
  }
  function subjectDocumentHref(documentId:number){
    return '/student/textbooks?documentId='+documentId+'&returnTo='+encodeURIComponent('/student/learn?subjectCode='+trackBrowse?.subjectCode+(trackBrowse?.courseId!==null&&trackBrowse?.courseId!==undefined?'&courseId='+trackBrowse.courseId:(trackBrowse?.programCode?'&programCode='+trackBrowse.programCode:'')));
  }
  function rememberLessonPosition(){
    if(lesson)localStorage.setItem('qe:student:lesson-scroll:'+lesson.id,String(window.scrollY));
  }

  function translationGapText(gap: string) {
    const coverage=lesson?.translation_coverage;
    switch(gap){
      case 'class_name': return translateUi(locale, 'legacy.web.class.name.4af6f3b3');
      case 'lesson_title_summary': return translateUi(locale, 'legacy.web.lesson.title.summary.5f9445c3');
      case 'chapter_name': return translateUi(locale, 'legacy.web.chapter.name.4b8a805b');
      case 'subject_name': return translateUi(locale, 'legacy.web.subject.name.a190525f');
      case 'objective_title': return translateUi(locale, 'legacy.web.learning.objective.65da87a3');
      case 'lesson_blocks': { const missing=Math.max(0,Number(coverage?.blocks_total||0)-Number(coverage?.blocks_published||0)); return translateUi(locale, 'student.learn.missingTeachingBlocks', { count: missing }); }
      case 'questions': { const missing=Math.max(0,Number(coverage?.questions_total||0)-Number(coverage?.questions_published||0)); return translateUi(locale, 'student.learn.missingQuestions', { count: missing }); }
      default: return gap.replaceAll('_',' ');
    }
  }

  const lastOpenedLesson=trackBrowse?.lessons.find(item=>Number(item.id)===lastOpenedLessonId);
  if(error) return <main className="lesson-page"><section className="lesson-wrap"><div className="auth-card"><h1><UiText message="legacy.web.unable.to.load.lesson.2df59c7d" /></h1><p>{error}</p><Link href="/student" className="button button-dark"><UiText message="legacy.web.student.home.c4ae779f" /></Link></div></section></main>;
  if(trackBrowse) return <main className="lesson-page qe-subject-reading-page">
    <section className="lesson-wrap">
      <div className="lesson-meta"><span className="eyebrow">{(trackBrowse.courseId!==null&&trackBrowse.courseId!==undefined)||trackBrowse.programCode?(trackBrowse.courseName||courseDisplayName||translateUi(locale, 'legacy.web.course.0d5ab113'))+' · '+trackBrowse.subjectName:<><UiText message="legacy.web.class.98aaaa31" /> {trackBrowse.classCode} · {trackBrowse.subjectName}</>}</span><span>{trackGroups.length} <UiText message="legacy.web.chapters.b7609a1c" /> · {trackBrowse.lessons.length} <UiText message="legacy.web.published.lessons.b59dcc4d" /></span></div>
      <h1>{(trackBrowse.courseId!==null&&trackBrowse.courseId!==undefined)||trackBrowse.programCode?trackBrowse.subjectName:trackBrowse.subjectCode==='maths'?<UiText message="legacy.web.mathematics.learning.f39c3269" />:<UiText message="legacy.web.science.learning.38127305" />}</h1>
      <p className="lesson-intro"><UiText message="legacy.web.open.a.chapter.to.see.its.topics.and.p.2ba51701" /></p>
      {lastOpenedLesson&&<div className="feedback qe-last-opened-topic"><span><UiText message="legacy.web.last.opened.topic.74a31687" /></span><Link href={'/student/learn?subjectCode='+trackBrowse.subjectCode+(trackBrowse.courseId!==null&&trackBrowse.courseId!==undefined?'&courseId='+trackBrowse.courseId:(trackBrowse.programCode?'&programCode='+encodeURIComponent(trackBrowse.programCode):''))+'&lessonId='+lastOpenedLesson.id}><strong>{lastOpenedLesson.title}</strong> · <UiText message="legacy.web.continue.3be96765" /></Link></div>}
      {studentDocuments.filter(item=>item.scope==='SUBJECT_BOOK'&&item.subject_code===trackBrowse.subjectCode).map(item=>
        <Link key={item.document_id} className="qe-student-pdf-link qe-subject-book-link" href={subjectDocumentHref(item.document_id)} onClick={()=>rememberTrackPosition(openChapter||trackGroups[0]?.code)}>
          <span className="qe-student-pdf-mark" aria-hidden="true"><svg viewBox="0 0 24 28"><path d="M4 1.5h10l6 6V26.5H4z"/><path d="M14 1.5v6h6M6.5 16h11M6.5 20h11"/></svg><small>PDF</small></span>
          <span className="qe-student-pdf-copy"><span><UiText message="legacy.web.complete.subject.textbook.3fba5a6f" /></span><strong>{item.title}</strong><small>{item.language==='en'?translateUi(locale, 'legacy.web.english.edition.7a790205'):translateUi(locale, 'legacy.web.hindi.edition.242c7645')} · {item.page_count} pages · {item.edition||translateUi(locale, 'legacy.web.read.online.731d0a17')}</small></span>
          <span className="qe-student-pdf-action"><UiText message="legacy.web.open.pdf.a6054393" /> ↗</span>
        </Link>
      )}
      {!documentsLoaded&&<div className="qe-chapter-loading"><span className="qe-reader-spinner"/><UiText message="legacy.web.checking.available.pdfs.8cb79079" /></div>}
      {trackGroups.length===0 ? <div className="concept-card">
        <span className="concept-kicker"><UiText message="legacy.web.subject.content.9f593e47" /></span>
        <h2><UiText message="legacy.web.no.published.lessons.or.chapter.pdfs.a.c3c39a7e" /></h2>
        <p><UiText message="legacy.web.content.will.appear.here.when.it.has.b.9ddc3075" /></p>
        <Link href={(trackBrowse.courseId!==null&&trackBrowse.courseId!==undefined)||trackBrowse.programCode?'/student':'/student/learn?subjectCode='+(trackBrowse.subjectCode==='maths'?'science':'maths')} className="button button-dark"><UiText message="legacy.web.view.another.subject.5b4ac3fb" /></Link>
        <p><Link href="/student" className="text-link"><UiText message="legacy.web.return.to.student.home.bc1b2e2f" /></Link></p>
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
            <span className="qe-student-chapter-title"><small><UiText message="legacy.web.chapter.17e373cd" /> {group.code!==group.name?'· '+group.code:''}</small><strong>{group.name}</strong><span>{group.lessons.length} topics · {chapterDocuments.length} PDFs</span></span>
            <span className="qe-student-chapter-indicator">{expanded?'−':'+'}</span>
          </button>
          {chapterDocuments.map(item=><Link key={item.document_id} className="qe-student-pdf-link qe-chapter-pdf-row" href={subjectDocumentHref(item.document_id)} onClick={()=>rememberTrackPosition(group.code)}>
            <span className="qe-student-pdf-mark" aria-hidden="true"><svg viewBox="0 0 24 28"><path d="M4 1.5h10l6 6V26.5H4z"/><path d="M14 1.5v6h6M6.5 16h11M6.5 20h11"/></svg><small>PDF</small></span>
            <span className="qe-student-pdf-copy"><strong>{item.title||item.chapter_name||translateUi(locale, 'legacy.web.chapter.pdf.8639d049')}</strong><small>{item.language==='en'?translateUi(locale, 'legacy.web.english.edition.7a790205'):translateUi(locale, 'legacy.web.hindi.edition.242c7645')} · {item.page_count} {translateUi(locale, 'legacy.web.pages.216981bc')} · {textbookChapterTitle(item, learningLanguage)}</small></span>
            <span className="qe-student-pdf-action"><UiText message="legacy.web.open.pdf.a6054393" /> ↗</span>
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
              return <Link key={item.id} className={'qe-student-topic-row'+(topicComplete?' is-complete':'')} href={'/student/learn?subjectCode='+trackBrowse.subjectCode+(trackBrowse.courseId!==null&&trackBrowse.courseId!==undefined?'&courseId='+trackBrowse.courseId:(trackBrowse.programCode?'&programCode='+encodeURIComponent(trackBrowse.programCode):''))+'&lessonId='+item.id} onClick={()=>rememberTrackPosition(group.code,item.id)}>
                <span className="qe-student-topic-index" aria-label={'Microtopic '+(topicIndex+1)}>{String(topicIndex+1).padStart(2,'0')}</span>
                <span className="qe-student-topic-copy"><strong>{item.title}</strong>
                  <small>{item.estimated_minutes} <UiText message="legacy.web.min.published.microtopic.c9b9be28" /></small>
                  <span className="qe-student-topic-progress">
                    <small className={mcqsComplete?'is-done':''}>{mcqCount>0?'MCQ '+mcqAnswered+'/'+mcqCount:'MCQs not required'}</small>
                    {bestGraded>0
                      ? <small className="is-best"><UiText message="legacy.web.best.fe4104ad" /> {bestCorrect}/{bestGraded} · {bestPercent}%</small>
                      : practiceCompleted
                        ? <small className="is-best"><UiText message="legacy.web.practice.complete.5434d15d" /></small>
                        : activeQuestionCount>0
                          ? <small><UiText message="legacy.web.practice.saved.8c07a841" /> {activeAnswered}/{activeQuestionCount}</small>
                          : <small><UiText message="legacy.web.practice.not.started.dd077277" /></small>}
                    {practiceCompleted&&activeQuestionCount>0&&<small><UiText message="legacy.web.new.attempt.436d74f5" /> {activeAnswered}/{activeQuestionCount}</small>}
                  </span>
                </span>
                {topicComplete
                  ? <span className="qe-student-topic-complete" role="img" aria-label={translateUi(locale, 'legacy.web.topic.completed.8ab16ef3')} title={translateUi(locale, 'legacy.web.mcqs.and.practice.completed.850ed197')}><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m5 12.5 4.2 4.1L19 6.8"/></svg></span>
                  : <span className="qe-student-topic-arrow" aria-hidden="true">→</span>}
              </Link>;
            })}</div>}
            {chapterDocuments.length===0&&group.lessons.length===0&&<p className="qe-student-chapter-empty"><UiText message="legacy.web.no.pdf.or.published.topic.is.available.740a2b14" /></p>}
          </div>}
        </section>;
      })}
      <div className="qe-subject-reading-footer"><Link href="/student"><UiText message="legacy.web.student.home.d833cae1" /></Link><Link href="/student/textbooks"><UiText message="legacy.web.view.all.textbooks.f101aba4" /></Link></div>
    </section>
  </main>;
  if(!lesson) return <main className="lesson-page"><section className="lesson-wrap"><div className="eyebrow"><UiText message="legacy.web.loading.lesson.7b69e4b9" /></div></section></main>;

  // Additive adapter: current database lessons continue through the legacy path below.
  // The API may embed a server-sanitized, explicitly published structured artifact when that delivery path is enabled.
  if(lesson.structured_lesson) return <main className="lesson-page qe-structured-student-page">
    <section className="lesson-wrap">
      <div className="lesson-meta">
        <span className="eyebrow">{translateUi(locale, 'legacy.web.structured.lesson.17db6801')} · {lesson.chapter_name}</span>
        <span>{lesson.class_code} · {lesson.subject_name}</span>
      </div>
      <StructuredLessonExperience lesson={lesson.structured_lesson} locale={learningLanguage} mode="student" />
      <div className="lesson-next"><Link href="/student" className="button button-dark button-small"><UiText message="legacy.web.student.home.15fbe96d" /></Link></div>
    </section>
  </main>;

  return <main className="lesson-page">
    <section className="lesson-wrap"
      onTouchStart={event=>{
        const touch=event.changedTouches[0];
        lessonSwipeStartRef.current=touch?{x:touch.clientX,y:touch.clientY}:null;
      }}
      onTouchEnd={event=>{
        const start=lessonSwipeStartRef.current;
        lessonSwipeStartRef.current=null;
        const touch=event.changedTouches[0];
        if(!start||!touch||!lesson||currentIndex<0)return;
        const dx=touch.clientX-start.x;
        const dy=touch.clientY-start.y;
        if(Math.abs(dx)<80||Math.abs(dx)<Math.abs(dy)*1.35)return;
        if(dx>0&&currentIndex>0)void loadLesson(lessons[currentIndex-1].id);
        else if(dx<0&&currentIndex<lessons.length-1)void loadLesson(lessons[currentIndex+1].id);
      }}>
      <div className="lesson-meta">
        <span className="eyebrow">{selectedCourseId!==null||selectedProgram?(courseDisplayName||translateUi(locale, 'legacy.web.course.0d5ab113'))+' · '+lesson.subject_name:<><UiText message="legacy.web.class.98aaaa31" /> {lesson.class_code} · {lesson.subject_name}</>} · {lesson.chapter_name}</span>
        <span>Lesson {currentIndex+1} / {lessons.length}</span>
      </div>
      <h1>{lesson.title}</h1>
      <p className="lesson-intro">{lesson.summary}</p>
      {!lesson.translation_complete&&<div className="feedback" role="status">
        <span><UiText message="legacy.web.language.coverage.is.incomplete.b287daa1" /></span>
        <span><UiText message="legacy.web.current.translations.for.some.parts.ar.d95df64e" /></span>
        {!!lesson.translation_gaps?.length&&<small>{lesson.translation_gaps.map(translationGapText).join(" · ")}</small>}
      </div>}
      <div className="feedback"><span><UiText message="legacy.web.learning.path.43212585" /></span><span><LocaleText hinglish={learnerPathLabel(orderedBlocks,lessonMcqs.length).hinglish} english={learnerPathLabel(orderedBlocks,lessonMcqs.length).english} /></span></div>

      {learningBlocks.map(block=><Block key={block.id} block={block} onTutorOpen={()=>{setTutorQuestionId(undefined);setTutorOpen(true);}}/>)}
      {help !== 'none' && <div className="feedback"><b>{help.replaceAll('_',' ')} <UiText message="legacy.web.help.0201f8c2" /></b><span><UiText message="legacy.web.explain.the.concept.in.your.own.words..8ad26ef4" /></span></div>}

      <div className="content-heading"><h2><UiText message="legacy.web.check.your.understanding.with.mcqs.858bdd53" /></h2><span>{attemptedMcqs} / {lessonMcqs.length} <UiText message="legacy.web.attempted.b3b638cd" /></span></div>
      {lessonMcqs.length>0&&<div className="qe-mcq-progress" aria-label={translateUi(locale, 'legacy.web.mcq.progress.392d6eb6')}><div><span><UiText message="legacy.web.answer.every.question.to.continue.5dafc788" /></span><strong>{Math.round(attemptedMcqs/lessonMcqs.length*100)}%</strong></div><i><span style={{width:(attemptedMcqs/lessonMcqs.length*100)+'%'}}/></i></div>}
      {lessonMcqs.length ? lessonMcqs.map((q,index)=><QuestionCard key={q.id} q={q} index={index+1} onResult={id=>setMcqAttempted(old=>({...old,[id]:true}))} onTutorOpen={id=>{setTutorQuestionId(id);setTutorOpen(true)}}/>) : <div className="concept-card"><p><UiText message="legacy.web.mcqs.have.not.been.published.for.this..18b64bb6" /></p></div>}

      <section className="concept-card practice-launch-card">
        <span className="concept-kicker"><UiText message="legacy.web.next.step.0a765b4d" /></span>
        <h2><UiText message="legacy.web.now.practise.this.micro.topic.d41eb5e1" /></h2>
        <p><UiText message="legacy.web.quantaedge.selects.approved.questions..39820922" /></p>
        {canStartTopicPractice
          ? <Link href={'/student/practice?lessonId='+lesson.id+courseQuery} className="button button-dark"><UiText message="legacy.web.start.topic.practice.74092f61" /></Link>
          : <div className="qe-practice-gate"><button type="button" className="button button-dark" disabled><UiText message="legacy.web.finish.the.mcqs.first.7354ffa5" /></button><p><LocaleText hinglish={`आगे बढ़ने के लिए ${lessonMcqs.length-attemptedMcqs} सवालों के उत्तर देना बाकी है।`} english={`${lessonMcqs.length-attemptedMcqs} question(s) still need an answer before you can continue.`} /></p></div>}
      </section>

      {recapBlock&&<section className="lesson-recap" aria-label={translateUi(locale, 'legacy.web.lesson.recap.8d872474')}>
        <div className="content-heading">
          <h2><UiText message="legacy.web.recap.c772ab09" /></h2>
          <span><UiText message="legacy.web.key.takeaways.0c7d1936" /></span>
        </div>
        <Block key={recapBlock.id} block={recapBlock} onTutorOpen={()=>{setTutorQuestionId(undefined);setTutorOpen(true);}}/>
      </section>}

      {!tutorOpen && <button type="button" className="tutor-launch" onClick={()=>{setTutorQuestionId(undefined);setTutorOpen(true);}} aria-label={translateUi(locale, 'legacy.web.open.ai.tutor.5bd7c753')} title={translateUi(locale, 'legacy.web.open.ai.tutor.5bd7c753')}>
        <span className="tutor-launch-icon" aria-hidden="true">✦</span>
      </button>}
      <TutorDock lessonId={lesson.id} open={tutorOpen} onClose={()=>setTutorOpen(false)} currentQuestionId={tutorQuestionId} />

      <div className="lesson-next">
        {currentIndex>0 ? <button type="button" className="button button-small" onClick={()=>loadLesson(lessons[currentIndex-1].id)}><UiText message="legacy.web.previous.c5e11564" /></button> : <span/>}
        {currentIndex>=0 && currentIndex<lessons.length-1
          ? <button type="button" className="button button-dark button-small" onClick={()=>loadLesson(lessons[currentIndex+1].id)}><UiText message="legacy.web.next.lesson.7803b6e6" /></button>
          : <Link href="/student" className="button button-dark button-small"><UiText message="legacy.web.student.home.15fbe96d" /></Link>}
      </div>
    </section>
  </main>;
}
