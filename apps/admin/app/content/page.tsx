'use client';

import Link from 'next/link';
import AdminSidebar from '../components/AdminSidebar';
import PremiumPagination from '../components/PremiumPagination';
import { useUiTranslation } from '../i18n/useUiTranslation';
import {useContext,useEffect,useMemo,useRef,useState} from 'react';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';

type Row={class_code:string;class_name:string;program_code?:string|null;program_name?:string|null;subject_code:string;subject_name:string;chapter_id:number|null;chapter_code:string|null;chapter_name:string|null;chapter_description:string|null;chapter_sort_order:number|null;chapter_status:string|null;chapter_active:boolean|null;curriculum_source?:string|null;curriculum_source_url?:string|null;curriculum_source_edition?:string|null;curriculum_source_pages?:string|null;curriculum_source_verified?:boolean;lesson_id:number|null;lesson_code:string|null;lesson_title:string|null;lesson_summary:string|null;estimated_minutes:number|null;lesson_sort_order:number|null;lesson_status:string|null;lesson_active:boolean|null;alignment_source_title?:string|null;alignment_source_url?:string|null;alignment_source_edition?:string|null;alignment_page_range?:string|null;alignment_source_verified?:boolean;block_count:number|null;question_count:number|null};
type ContentBlock={id?:number;sequence_no:number;block_type:string;content:Record<string,any>;active:boolean};
type Selection={type:'chapter'|'lesson';id:number};
type CourseAssessmentRow={assessment_id:number;test_id?:string|null;title:string;description?:string|null;status:string;assessment_type?:string|null;source_exam_cycle?:string|null;source_package_id?:string|null;source_package_version?:string|null;test_pack_code?:string|null;test_pack_version?:string|null;test_pack_title?:string|null;test_pack_status?:string|null;question_count:number;max_score:number;duration_minutes:number;max_attempts:number};
type VisualCandidate={candidate_id:string;candidate_kind:string;source_book_id:string;source_pdf_sha256:string;source_pdf_page_number:number;suggested_chapter_number?:number|null;suggested_chapter_title?:string|null;chapter_map_review_status?:string|null;source_asset_sha256?:string|null;bbox_pdf_points?:number[]|null;page_text_context:string;image_url:string;source_page_url?:string;publication_allowed:boolean;human_review_required:boolean};
type VisualCatalog={schema_version:string;book_slug:string;source:{book_id:string;title:string;class:string;subject:string;language:string;edition:string;pdf_sha256:string;page_count:number};summary:{candidate_count:number;publication_allowed:boolean;review_required:boolean};visual_kinds:{id:string;label:string;description?:string}[];candidates:VisualCandidate[]};
type VisualCatalogIndex={schema_version:string;catalogs:Array<{book_slug:string;catalog_path:string;source_book_id:string;title:string;class:string;subject:string;language:string;edition:string;source_pdf_sha256:string;page_count:number;candidate_count:number;publication_allowed:boolean;review_required:boolean}>};
type VisualSourceMatch={class:string;subject:string};
const RAW_REPO_CONTENT_PREFIX='https://raw.githubusercontent.com/Manav326/QuantaEdge/main/';
const LEGACY_REVIEW_ASSET_PREFIX=RAW_REPO_CONTENT_PREFIX+'apps/web/public/content/visual-assets/';
const REVIEW_ASSET_PREFIX=RAW_REPO_CONTENT_PREFIX+'data/textbook-visual-candidates-v3/';
function normalizeReviewAssetUrl(url:string|undefined):string|undefined {
 if(!url)return url;
 return url.startsWith(LEGACY_REVIEW_ASSET_PREFIX)?REVIEW_ASSET_PREFIX+url.slice(LEGACY_REVIEW_ASSET_PREFIX.length):url;
}
function visualSourceMatchesLesson(row:Row|undefined,source:VisualSourceMatch|undefined):boolean {
 if(!row||!source)return false;
 const normalizedClass=(value:string|undefined|null)=>String(value??'').trim().toLocaleLowerCase().replace(/^(?:class|grade)[\s_-]*/,'');
 const sourceClass=normalizedClass(source.class);
 const lessonClass=normalizedClass(row.class_code);
 if(!sourceClass||sourceClass!==lessonClass)return false;
 const subjectKey=(value:string|undefined|null)=>{
  const raw=String(value??'').trim().toLocaleLowerCase().normalize('NFKC');
  if(raw.includes('विज्ञान')||raw.includes('science')||raw.includes('vigyan'))return 'science';
  if(raw.includes('गणित')||raw.includes('math')||raw.includes('ganit'))return 'mathematics';
  return raw.replace(/[^a-z0-9]+/g,'');
 };
 const sourceSubject=subjectKey(source.subject);
 const lessonSubjects=[subjectKey(row.subject_name),subjectKey(row.subject_code)];
 return Boolean(sourceSubject&&lessonSubjects.includes(sourceSubject));
}
function visualCatalogMatchesLesson(row:Row|undefined,catalog:VisualCatalog|null):boolean {
 return visualSourceMatchesLesson(row,catalog?.source);
}
const VISUAL_RELEVANCE_STOP_WORDS=new Set([
 'the','and','for','from','this','that','with','what','when','where','have','into','are','was','were','can','will',
 'you','your','our','their','they','them','its','not','but','all','any','more','most','some','such','than','then',
 'page','pages','book','figure','image','visual','grade','class','science','math','maths','mathematics','lesson',
 'का','की','के','और','यह','इस','इन','में','से','है','हैं','को','एक','पर','तक','लिए','द्वारा','भी','तो','या'
]);
function visualSearchTokens(value:unknown):Set<string> {
 const pieces:string[]=[];
 const collect=(item:unknown):void=>{
  if(typeof item==='string'){pieces.push(item);return;}
  if(Array.isArray(item)){item.forEach(collect);return;}
  if(item&&typeof item==='object')Object.values(item as Record<string,unknown>).forEach(collect);
 };
 collect(value);
 const tokens=(pieces.join(' ').toLocaleLowerCase().normalize('NFKC').match(/[A-Za-z0-9\u0900-\u097F]{3,}/g)||[]);
 return new Set(tokens.filter(token=>!VISUAL_RELEVANCE_STOP_WORDS.has(token)));
}
function visualCandidateRelevanceScore(candidate:VisualCandidate,row:Row|undefined):number {
 if(!row)return 0;
 const sourceTokens=visualSearchTokens([candidate.suggested_chapter_title,candidate.page_text_context]);
 const lessonTokens=visualSearchTokens([row.lesson_title,row.lesson_summary,row.chapter_name]);
 let score=0;
 for(const token of lessonTokens)if(sourceTokens.has(token))score+=token.length>=6?3:1;
 const normalize=(value:string|undefined|null)=>String(value??'').toLocaleLowerCase().normalize('NFKC').replace(/[^a-z0-9\u0900-\u097f]+/g,' ').trim();
 const chapter=normalize(row.chapter_name),suggestion=normalize(candidate.suggested_chapter_title);
 if(chapter&&suggestion&&(chapter===suggestion||chapter.includes(suggestion)||suggestion.includes(chapter)))score+=1000;
 return score;
}
function visualCandidateBlockRelevanceScore(candidate:VisualCandidate,block:ContentBlock):number {
 if(!['EXPLANATION','WORKED_EXAMPLE','GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE','HINT'].includes(block.block_type))return -1;
 const sourceTokens=visualSearchTokens(candidate.page_text_context);
 const blockTokens=visualSearchTokens([block.block_type,block.content]);
 let score=0;
 for(const token of blockTokens)if(sourceTokens.has(token))score+=token.length>=6?3:1;
 return score;
}
const LEARNER_BLOCK_STAGE:Record<string,number>={
 PREREQUISITE:0,EXPLANATION:1,IMAGE:2,DIAGRAM:2,VIDEO:2,AUDIO:2,ANIMATION:2,
 WORKED_EXAMPLE:3,GUIDED_PRACTICE:4,HINT:4,INDEPENDENT_PRACTICE:5,CHALLENGE:5,
 AI_HELP:6,SUMMARY:7,RECAP:7
};
const LEARNER_BLOCK_TYPES=new Set(Object.keys(LEARNER_BLOCK_STAGE));
function orderedLearnerBlocks(blocks:ContentBlock[]){
  // sequence_no is the authored teaching sequence; use id only as a stable legacy tie-breaker.
  return blocks.filter(block=>block.active!==false&&LEARNER_BLOCK_TYPES.has(block.block_type)).slice()
   .sort((a,b)=>a.sequence_no-b.sequence_no||Number(a.id||0)-Number(b.id||0));
 }
function learnerPathLabel(blocks:ContentBlock[],questionCount:number){
 const types=new Set(blocks.map(block=>block.block_type));
 const stages:string[]=[];
 if(types.has('PREREQUISITE'))stages.push('Prior knowledge');
 if(types.has('EXPLANATION'))stages.push('Understand');
 if(['IMAGE','DIAGRAM','VIDEO','AUDIO','ANIMATION'].some(type=>types.has(type)))stages.push('Learn with visuals');
 if(types.has('WORKED_EXAMPLE'))stages.push('Worked example');
 if(types.has('GUIDED_PRACTICE'))stages.push('Guided practice');
 if(types.has('INDEPENDENT_PRACTICE')||types.has('CHALLENGE'))stages.push('Independent practice');
 if(questionCount>0)stages.push('Assessment');
 if(types.has('SUMMARY')||types.has('RECAP'))stages.push('Recap');
 return stages.join(' → ')||'Learning path is being prepared';
}
function lessonStructureIssues(blocks:ContentBlock[]){
 const active=blocks.filter(block=>block.active!==false);
 const types=new Set(active.map(block=>block.block_type));
 const issues:string[]=[];
 if(!types.has('EXPLANATION'))issues.push('Add a complete Explanation block.');
 if(!['GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE'].some(type=>types.has(type)))issues.push('Add guided practice, independent practice, or a challenge.');
 if(!types.has('SUMMARY')&&!types.has('RECAP'))issues.push('Add a Recap block with meaningful takeaways.');
 const unsupported=active.filter(block=>!LEARNER_BLOCK_TYPES.has(block.block_type));
 if(unsupported.length)issues.push('Unsupported student block(s): '+[...new Set(unsupported.map(block=>block.block_type))].join(', ')+'. Convert question content to the lesson Questions section or deactivate these legacy blocks.');
 return issues;
}
const BLOCK_OPTIONS=[
 {type:'EXPLANATION',label:'Rich explanation',icon:'Aa',hint:'Formatted teaching text, key ideas and examples'},
 {type:'WORKED_EXAMPLE',label:'Worked example',icon:'↗',hint:'A problem, step-by-step solution and answer'},
 {type:'IMAGE',label:'Image / graphic',icon:'▧',hint:'A picture, illustration or labelled graphic'},
 {type:'DIAGRAM',label:'Diagram',icon:'◇',hint:'A diagram or visual model for the concept'},
 {type:'VIDEO',label:'Video',icon:'▶',hint:'A video URL or an HTTPS MP4/WebM file'},
 {type:'AUDIO',label:'Audio',icon:'♫',hint:'A spoken explanation or audio clip'},
 {type:'ANIMATION',label:'Animation / GIF',icon:'✦',hint:'An animated GIF/WebP or educational visual'},
 {type:'GUIDED_PRACTICE',label:'Guided practice',icon:'✓',hint:'A prompt with optional hint to work through together'},
 {type:'INDEPENDENT_PRACTICE',label:'Try it yourself',icon:'✎',hint:'An independent task for the learner'},
 {type:'CHALLENGE',label:'Challenge',icon:'★',hint:'A stretch question or deeper application'},
 {type:'SUMMARY',label:'Key takeaways',icon:'☑',hint:'The most important ideas to remember'},
 {type:'RECAP',label:'Recap',icon:'↻',hint:'Revisit the main ideas after the learner has attempted the questions'},
 {type:'PREREQUISITE',label:'Before you begin',icon:'↶',hint:'Activate prior knowledge'},
 {type:'HINT',label:'Helpful hint',icon:'?',hint:'A nudge that supports thinking without giving away the answer'},
 {type:'AI_HELP',label:'AI tutor entry',icon:'✧',hint:'A point where the learner can ask the tutor for help'}
];
const EMPTY={displayName:'',description:'',chapterStatus:'DRAFT',chapterSortOrder:1,curriculumSource:'',curriculumSourceUrl:'',curriculumSourceEdition:'',curriculumSourcePages:'',curriculumSourceVerified:false,title:'',summary:'',estimatedMinutes:10,lessonStatus:'DRAFT',sortOrder:1,alignmentSourceTitle:'',alignmentSourceUrl:'',alignmentSourceEdition:'',alignmentPageRange:'',alignmentSourceVerified:false};
function parsed(value:unknown,fallback:any={}){if(value===null||value===undefined||value==='')return fallback;if(typeof value==='string'){try{return JSON.parse(value)}catch{return fallback}}return value}
function esc(value:string){return value.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;')}
function htmlFromText(value:string){return String(value||'').split(/\n{2,}/).map(p=>'<p>'+esc(p).replace(/\n/g,'<br>')+'</p>').join('')}
function safeRichHtml(value:string){
 let html=String(value||'');
 html=html.replace(/<font\b([^>]*)>/gi,(_m,attrs:string)=>{
  const color=attrs.match(/\bcolor\s*=\s*["']?(#[0-9a-f]{3,8}|[a-z]+)["']?/i)?.[1];
  const size=attrs.match(/\bsize\s*=\s*["']?([1-7])["']?/i)?.[1];
  const fontSizes:Record<string,string>={'1':'12px','2':'14px','3':'16px','4':'20px','5':'24px','6':'30px','7':'36px'};
  const styles=[color&&/^(#[0-9a-f]{3,8}|[a-z]+)$/i.test(color)?'color:'+color:null,size?'font-size:'+fontSizes[size]:null].filter(Boolean).join(';');
  return '<span'+(styles?' style="'+styles+'"':'')+'>';
 }).replace(/<\/font>/gi,'</span>');
 html=html.replace(/<\s*(script|style|iframe|object|embed|form|input|button)[\s\S]*?<\/\s*\1\s*>/gi,'');
 html=html.replace(/<\s*(script|style|iframe|object|embed|form|input|button)\b[^>]*\/?>/gi,'');
 html=html.replace(/\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi,'');
 html=html.replace(/\s+(href|src)\s*=\s*(['"])\s*(?:javascript|vbscript|data):[\s\S]*?\2/gi,'');
 html=html.replace(/<(?!\/?(?:p|div|span|br|strong|b|em|i|u|ul|ol|li|h2|h3|h4|blockquote|code|pre|a|img)\b)[^>]*>/gi,'');
 html=html.replace(/\s+style\s*=\s*(['"])([\s\S]*?)\1/gi,(_m,q,styles:string)=>{
   const allowed=styles.split(';').map(rule=>rule.trim()).filter(Boolean)
    .filter(rule=>/^(color|background-color|font-size|font-family|font-weight|font-style|text-align|text-decoration|margin-left)\s*:/i.test(rule))
    .filter(rule=>!/(url\s*\(|expression|javascript|@import)/i.test(rule)).join(';');
   return allowed?' style="'+allowed.replace(/"/g,'&quot;')+'"':'';
 });
 html=html.replace(/\s+(?:href|src)\s*=\s*(['"])(?!https?:\/\/|\/|#)[\s\S]*?\1/gi,'');
 return html;
}
async function api(url:string,init:RequestInit={}){
 const headers=new Headers(init.headers||{});if(init.body)headers.set('Content-Type','application/json');
 const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
 const raw=await response.text();let body:any={};
 try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
 if(!response.ok)throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));
 return body;
}
function contentFor(type:string):Record<string,any>{
 if(type==='EXPLANATION'||type==='PREREQUISITE'){const body='यहाँ अपना explanation लिखें। मुख्य विचार को सरल भाषा में समझाएँ और जरूरत के अनुसार उदाहरण या media जोड़ें।';return {heading:type==='PREREQUISITE'?'पहले से क्या जानते हैं?':'आइए समझते हैं',html:htmlFromText(body),body};}
 if(type==='WORKED_EXAMPLE')return {title:'Worked example',problem:'समस्या या प्रश्न यहाँ लिखें।',steps:['पहला चरण लिखें','अगला चरण लिखें'],answer:''};
 if(['IMAGE','DIAGRAM','ANIMATION'].includes(type))return {title:type==='ANIMATION'?'Concept animation':type==='DIAGRAM'?'Concept diagram':'Concept visual',url:'',description:'विद्यार्थी इस visual से क्या समझें?',alt:'Concept illustration',caption:''};
 if(type==='VIDEO'||type==='AUDIO')return {title:type==='AUDIO'?'सुनकर समझें':'देखकर समझें',url:'',description:'इस media से विद्यार्थी क्या सीखेंगे?',caption:''};
 if(type==='SUMMARY'||type==='RECAP')return {points:['पहला मुख्य विचार','दूसरा मुख्य विचार','याद रखने वाली बात']};
 if(type==='GUIDED_PRACTICE')return {title:'आइए, साथ में करें',prompt:'अगला छोटा कदम क्या होगा?',hint:'छोटा संकेत दें, पूरा उत्तर नहीं।'};
 if(type==='INDEPENDENT_PRACTICE')return {title:'अब आप करके देखें',prompt:'विद्यार्थी के लिए अभ्यास निर्देश लिखें',hint:''};
 if(type==='CHALLENGE')return {title:'Challenge',prompt:'थोड़ा कठिन सवाल या task लिखें',hint:''};
 if(type==='HINT')return {title:'Helpful hint',body:'ऐसा संकेत दें जो सोचने में मदद करे।'};
 return {title:'Learning support'};
}
function normalizeBlock(block:ContentBlock){
  const next={...block,content:{...(block.content||{})}};
  if((next.block_type==='EXPLANATION'||next.block_type==='PREREQUISITE')&&!next.content.html&&next.content.body)next.content.html=htmlFromText(String(next.content.body));
  return next;
}
function cleanContentText(value:unknown){
 return String(value??'').replace(/<[^>]*>/g,' ').replace(/&nbsp;|&#160;/gi,' ').replace(/&amp;/gi,'&').replace(/\s+/g,' ').trim();
}
function meaningfulContent(value:unknown){
 const text=cleanContentText(value).toLocaleLowerCase();
 if(!text)return false;
 return !/^(?:यहाँ अपना explanation लिखें.*|समस्या या प्रश्न यहाँ लिखें\.?|पहला चरण लिखें|अगला चरण लिखें|पहला मुख्य विचार|दूसरा मुख्य विचार|याद रखने वाली बात|explain the core idea|show the important pattern|connect it to a real example|add teaching content|विद्यार्थी इस visual से क्या समझें\??|इस media से विद्यार्थी क्या सीखेंगे\??|अगला छोटा कदम क्या होगा\??|विद्यार्थी के लिए अभ्यास निर्देश लिखें|ऐसा संकेत दें जो सोचने में मदद करे.*|untitled.*|write .* here\.?|your question will appear here\.?|answer choice)$/i.test(text);
}
function blockHasPublishableContent(block:ContentBlock){
 const value=block.content||{};
 switch(block.block_type){
  case 'EXPLANATION': case 'PREREQUISITE':
   return meaningfulContent(value.html||value.body||value.description);
  case 'WORKED_EXAMPLE':
   return meaningfulContent(value.problem||value.prompt)&&Array.isArray(value.steps)&&value.steps.length>0&&value.steps.every((step:unknown)=>meaningfulContent(step))&&meaningfulContent(value.answer);
  case 'IMAGE': case 'DIAGRAM': case 'ANIMATION': case 'VIDEO': case 'AUDIO':
   return /^https:\/\//i.test(String(value.url||''))&&meaningfulContent(value.description);
  case 'GUIDED_PRACTICE': case 'INDEPENDENT_PRACTICE': case 'CHALLENGE':
   return meaningfulContent(value.prompt||value.body);
  case 'SUMMARY': case 'RECAP':
   return Array.isArray(value.points)&&value.points.length>0&&value.points.every((point:unknown)=>meaningfulContent(point));
  case 'HINT':
   return meaningfulContent(value.body||value.hint);
  case 'AI_HELP':
   return true;
  case 'MCQ': case 'TRUE_FALSE': case 'QUESTION':
   return meaningfulContent(value.prompt||value.question||value.body)&&Array.isArray(value.options)&&value.options.length>=2&&value.options.every((option:any)=>meaningfulContent(option.label));
  case 'MATCH': case 'ORDER': case 'INPUT':
   return meaningfulContent(value.prompt||value.question||value.body||value.instructions);
  default:
   return meaningfulContent(value.body||value.description||value.prompt||value.question||value.text);
 }
}
function questionReadinessIssues(question:any):string[]{
 const issues:string[]=[];
 const type=String(question.question_type||'').toUpperCase();
 const status=String(question.review_status||'DRAFT').toUpperCase();
 if(question.active===false)issues.push('This question is inactive and is not counted toward publication.');
 if(!meaningfulContent(question.prompt))issues.push('Add a meaningful question prompt.');
 if(!['APPROVED','PUBLISHED'].includes(status))issues.push(status==='REJECTED'?'This question was returned; correct it and resubmit for review.':'A reviewer must approve this question before the micro-topic can be published.');
 if(!['MCQ','TRUE_FALSE','INPUT','NUMERICAL','SHORT_ANSWER','LONG_ANSWER'].includes(type))issues.push('This question type is not supported in the current assessment flow.');
 const answer=question.answer_payload&&typeof question.answer_payload==='object'?question.answer_payload:parsed(question.answer_payload,{});
 if(type==='MCQ'||type==='TRUE_FALSE'){
  const options=Array.isArray(question.options)?question.options:parsed(question.options,[]);
  const correctOptions=options.filter((option:any)=>Boolean(option.correct));
  if(options.length<2)issues.push('Add at least two answer options.');
  if(options.some((option:any)=>!meaningfulContent(option.label)))issues.push('Fill in every answer option.');
  if(correctOptions.length!==1)issues.push('Mark exactly one answer as correct.');
  if(correctOptions.length===1&&(answer.kind!=='OPTION'||String(answer.value)!==String(correctOptions[0]?.key)))issues.push('The answer key must match the option marked correct.');
 }else if(['INPUT','SHORT_ANSWER','LONG_ANSWER'].includes(type)){
  if(answer.kind!=='TEXT'||!meaningfulContent(answer.value))issues.push('Add a staff-only reference answer or marking rubric.');
 }else if(type==='NUMERICAL'){
  if(answer.kind!=='NUMERIC'||answer.value===undefined||answer.value===null||String(answer.value).trim()==='')issues.push('Add the numeric answer key.');
 }
 const sourceKind=String(question.source_kind||'AUTHOR_CREATED').toUpperCase();
 if(sourceKind!=='AUTHOR_CREATED'){
  if(!question.source_id||!String(question.source_ref||'').trim()||!question.source_year||!String(question.source_title||'').trim()||!String(question.board||'').trim()){
   issues.push('Complete the registered source, board, year and exact page/reference for this sourced question.');
  }
  if(!['VERIFIED','APPROVED','PUBLISHED'].includes(String(question.source_status||'').toUpperCase())){
   issues.push('The registered source must be verified or approved before this sourced question can be published.');
  }
 }
 return [...new Set(issues)];
}
function questionHasValidAnswer(question:any){
 return questionReadinessIssues(question).length===0;
}
function questionsForSnapshot(showAdvanced:boolean,questionJson:string,questions:any[]){
 if(!showAdvanced)return questions;
 try{const value=JSON.parse(questionJson);return Array.isArray(value)?value:[{invalid_json:questionJson}]}catch{return [{invalid_json:questionJson}]}
}
function RichTextEditor({blockKey,initialHtml,onChange}:{blockKey:number;initialHtml:string;onChange:(html:string)=>void}){
 const t=useUiTranslation();
 const ref=useRef<HTMLDivElement|null>(null);
 useEffect(()=>{if(ref.current)ref.current.innerHTML=safeRichHtml(initialHtml||'')},[blockKey]);
 function finish(){if(ref.current)onChange(ref.current.innerHTML)}
 function command(name:string,value?:string){if(!ref.current)return;ref.current.focus();document.execCommand(name,false,value);finish()}
 function size(value:string){
  command('fontSize',value);if(!ref.current)return;
  ref.current.querySelectorAll('font[size]').forEach(el=>{const font=el as HTMLElement;const sizes:Record<string,string>={'1':'12px','2':'14px','3':'16px','4':'20px','5':'24px','6':'30px','7':'36px'};const span=document.createElement('span');span.style.fontSize=sizes[font.getAttribute('size')||'3']||'16px';while(font.firstChild)span.appendChild(font.firstChild);font.replaceWith(span)});finish();
 }
 function insertLink(){const url=window.prompt(t('admin.contentStudio.pasteHttpsLink'));if(!url||!/^https?:\/\//i.test(url))return;command('createLink',url)}
 function insertImage(){const url=window.prompt(t('admin.contentStudio.pasteHttpsImageUrl'));if(!url||!/^https?:\/\//i.test(url))return;command('insertImage',url)}
 return <div className="qe-rich-wrap">
  <div className="qe-rich-toolbar" aria-label={t('admin.contentStudio.richTextToolbar')}>
   <button type="button" title={t('admin.contentStudio.bold')} onMouseDown={e=>e.preventDefault()} onClick={()=>command('bold')}><b>B</b></button>
   <button type="button" title={t('admin.contentStudio.italic')} onMouseDown={e=>e.preventDefault()} onClick={()=>command('italic')}><i>I</i></button>
   <button type="button" title={t('admin.contentStudio.underline')} onMouseDown={e=>e.preventDefault()} onClick={()=>command('underline')}><u>U</u></button>
   <select aria-label={t('admin.contentStudio.fontSize')} defaultValue="3" onChange={e=>size(e.target.value)}><option value="2">{t('admin.contentStudio.sizeSmall')}</option><option value="3">{t('admin.contentStudio.sizeNormal')}</option><option value="4">{t('admin.contentStudio.sizeLarge')}</option><option value="5">{t('admin.contentStudio.sizeHeading')}</option><option value="6">{t('admin.contentStudio.sizeVeryLarge')}</option></select>
   <label className="qe-color-pick" title={t('admin.contentStudio.textColor')}>A <input aria-label={t('admin.contentStudio.textColor')} type="color" defaultValue="#243453" onChange={e=>command('foreColor',e.target.value)}/></label>
   <label className="qe-color-pick" title={t('admin.contentStudio.highlightColor')}>▰ <input aria-label={t('admin.contentStudio.highlightColor')} type="color" defaultValue="#fff0b8" onChange={e=>command('hiliteColor',e.target.value)}/></label>
   <button type="button" onMouseDown={e=>e.preventDefault()} onClick={()=>command('insertUnorderedList')}>{t('admin.contentStudio.unorderedList')}</button>
   <button type="button" onMouseDown={e=>e.preventDefault()} onClick={()=>command('insertOrderedList')}>{t('admin.contentStudio.orderedList')}</button>
   <button type="button" onMouseDown={e=>e.preventDefault()} onClick={insertLink}>{t('admin.contentStudio.insertLink')}</button>
   <button type="button" onMouseDown={e=>e.preventDefault()} onClick={insertImage}>{t('admin.contentStudio.insertImage')}</button>
   <button type="button" onMouseDown={e=>e.preventDefault()} onClick={()=>command('removeFormat')}>{t('admin.contentStudio.clearFormat')}</button>
  </div>
  <div ref={ref} className="qe-rich-editable" contentEditable suppressContentEditableWarning role="textbox" aria-label={t('admin.contentStudio.lessonExplanationEditor')} aria-multiline="true" onInput={finish} onBlur={finish}/>
  <div className="qe-rich-helper">{t('admin.contentStudio.richTextHelper')}</div>
 </div>;
}
function MediaElement({type,content,language='en'}:{type:string;content:Record<string,any>;language?:'hi'|'en'}){
 const [zoomed,setZoomed]=useState(false);
 const url=String(content.url||'').trim();
 const directVideo=/\.(mp4|webm|ogg)(\?.*)?$/i.test(url);
 const isYoutube=/youtube\.com\/watch\?/i.test(url)||/youtu\.be\//i.test(url);
 let embed=url;
 if(isYoutube){try{const parsedUrl=new URL(url);const id=parsedUrl.hostname.includes('youtu.be')?parsedUrl.pathname.slice(1):parsedUrl.searchParams.get('v')||'';if(id)embed='https://www.youtube-nocookie.com/embed/'+encodeURIComponent(id)}catch{}}
 if(type==='AUDIO')return url?<audio className="qe-preview-audio" controls preload="metadata" src={url}>{language==='hi'?'ऑडियो पूर्वावलोकन उपलब्ध नहीं है।':'Audio preview unavailable.'}</audio>:<div className="qe-media-placeholder">♫ {language==='hi'?'पूर्वावलोकन के लिए ऑडियो URL जोड़ें':'Add an audio URL to preview it'}</div>;
 if(type==='ANIMATION'&&directVideo)return <video className="qe-preview-video" controls preload="metadata" playsInline src={url}/>;
 if(type==='VIDEO')return !url?<div className="qe-media-placeholder">▶ {language==='hi'?'पूर्वावलोकन के लिए HTTPS वीडियो लिंक जोड़ें':'Add an HTTPS video link to preview it'}</div>:directVideo?<video className="qe-preview-video" controls preload="metadata" playsInline src={url}/>:isYoutube?<div className="qe-embed-box"><iframe src={embed} title={String(content.title||(language==='hi'?'पाठ का वीडियो':'Lesson video'))} allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowFullScreen referrerPolicy="strict-origin-when-cross-origin"/></div>:<div className="qe-media-link">{language==='hi'?'वीडियो लिंक':'Video link'} <a href={url} target="_blank" rel="noreferrer">{language==='hi'?'मीडिया खोलें ↗':'Open media ↗'}</a></div>;
 const alt=String(content.alt||content.title||(language==='hi'?'सीखने का चित्र':'Learning visual'));
 return url?<>
  <img className="qe-preview-image qe-preview-image-zoomable" src={url} alt={alt} role="button" tabIndex={0}
   aria-label={language==='hi'?'चित्र बड़ा करके देखें':'Expand visual'}
   onClick={()=>setZoomed(true)}
   onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();setZoomed(true)}}}/>
  {zoomed&&<div className="qe-admin-visual-lightbox" role="dialog" aria-modal="true" aria-label={language==='hi'?'बड़ा चित्र':'Expanded visual'}
   onClick={event=>{if(event.target===event.currentTarget)setZoomed(false)}}>
   <button type="button" onClick={()=>setZoomed(false)}>{language==='hi'?'बंद करें':'Close'}</button>
   <img src={url} alt={alt}/>
  </div>}
 </>:<div className="qe-media-placeholder">▧ {language==='hi'?'पूर्वावलोकन के लिए visual URL जोड़ें':'Add a visual URL to preview it'}</div>;
}
function LearnerBlock({block,language='hi'}:{block:ContentBlock;language?:'hi'|'en'}){
 const c=block.content||{};
 if(block.block_type==='EXPLANATION'||block.block_type==='PREREQUISITE')return <article className="qe-student-card"><span className="qe-student-kicker">{c.heading||c.title||(block.block_type==='PREREQUISITE'?'पहले से क्या जानते हैं?':'समझें')}</span>{c.html?<div className="qe-student-rich" dangerouslySetInnerHTML={{__html:safeRichHtml(String(c.html))}}/>:<p>{c.body||c.description||''}</p>}{Array.isArray(c.keyPoints)&&c.keyPoints.map((point:string,i:number)=><div className="qe-preview-feedback" key={i}>• {point}</div>)}</article>;
 if(block.block_type==='WORKED_EXAMPLE')return <article className="qe-student-card"><span className="qe-student-kicker">Worked example</span><h3>{c.title||'उदाहरण'}</h3><p>{c.problem||c.prompt||''}</p>{(c.steps||[]).filter(Boolean).map((step:string,i:number)=><div className="qe-preview-feedback" key={i}>{i+1}. {step}</div>)}{c.answer&&<p className="qe-model-answer"><strong>Model answer:</strong> {c.answer}</p>}</article>;
 if(['IMAGE','DIAGRAM','ANIMATION','VIDEO','AUDIO'].includes(block.block_type)){const lang=language;const title=lang==='hi'?(c.title_hi||c.title):c.title;const description=lang==='hi'?(c.description_hi||c.description):c.description;const caption=lang==='hi'?(c.caption_hi||c.caption):c.caption;const mediaContent={...c,title,description,caption,alt:lang==='hi'?(c.alt_hi||c.alt):c.alt};const kinds:Record<string,{en:string;hi:string}>={PHOTO:{en:'Photo',hi:'फ़ोटो'},ILLUSTRATION:{en:'Illustration',hi:'चित्रण'},DIAGRAM:{en:'Diagram',hi:'आरेख'},CHART:{en:'Chart / graph',hi:'चार्ट / ग्राफ़'},TABLE:{en:'Table / comparison',hi:'तालिका / तुलना'},INFOGRAPHIC:{en:'Infographic',hi:'सूचनात्मक चित्र'},SEQUENCE:{en:'Sequence / process',hi:'क्रम / प्रक्रिया'},TEXTBOOK_FIGURE:{en:'Unclassified source figure',hi:'अवर्गीकृत स्रोत चित्र'}};return <article className="qe-student-card qe-student-visual-card"><span className="qe-student-kicker">{title||(block.block_type==='AUDIO'?'सुनकर समझें':block.block_type==='VIDEO'?'देखकर समझें':'Visual')}</span>{['IMAGE','DIAGRAM','ANIMATION'].includes(block.block_type)?<span className="qe-visual-kind-pill">{(kinds[String(c.visual_kind||'TEXTBOOK_FIGURE')]||{en:'Learning visual',hi:'सीखने का चित्र'})[lang]}</span>:null}{description&&<p>{description}</p>}<MediaElement type={block.block_type} content={mediaContent} language={lang}/>{caption&&<p className="qe-preview-caption">{caption}</p>}{c.source_candidate_id?<div className="qe-visual-source-caption"><span>{lang==='hi'?'स्रोत: ':'Source: '}{c.source_book_title||c.source_book_id} · {lang==='hi'?'PDF पृष्ठ ':'PDF page '}{c.source_pdf_page_number} · {lang==='hi'?'स्रोत संस्करण ':'source edition '}{c.source_edition||(lang==='hi'?'दर्ज नहीं':'not recorded')}</span>{String(c.source_page_url||'').startsWith('https://')?<a href={c.source_page_url} target="_blank" rel="noreferrer">{lang==='hi'?'स्रोत पृष्ठ देखें ↗':'View source page ↗'}</a>:null}</div>:null}</article>};
 if(['GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE','HINT'].includes(block.block_type))return <article className={'qe-student-card qe-student-callout '+block.block_type.toLowerCase()}><span className="qe-student-kicker">{c.title||(block.block_type==='GUIDED_PRACTICE'?'साथ में करें':block.block_type==='HINT'?'Helpful hint':'अब खुद करें')}</span><p>{c.prompt||c.body||''}</p>{c.hint&&<div className="qe-preview-feedback">Hint: {c.hint}</div>}</article>;
 if(['SUMMARY','RECAP'].includes(block.block_type))return <article className="qe-student-card"><span className="qe-student-kicker">Recap</span>{(c.points||[]).filter(Boolean).map((point:string,i:number)=><div className="qe-preview-feedback" key={i}>✓ {point}</div>)}</article>;
 if(block.block_type==='AI_HELP')return <article className="qe-student-card qe-student-callout"><span className="qe-student-kicker">AI tutor</span><p>Students can open the AI tutor for help on this topic.</p></article>;
 return <article className="qe-student-card"><span className="qe-student-kicker">{block.block_type}</span><p>{String(c.body||c.description||'Learning block')}</p></article>;
}
export default function ContentStudio(){
 const t=useUiTranslation();
 const {permissions:workspacePermissions}=useContext(AdminWorkspaceContext);
 const canCreateCourse=workspacePermissions.includes('CONTENT_CREATE')&&workspacePermissions.includes('CONTENT_EDIT');
 const canManageCourseRelease=workspacePermissions.includes('INSTITUTION_RELEASE_MANAGE');
 function statusLabel(value:string|null|undefined){
  const code=String(value||'DRAFT').toUpperCase();
  const keys:Record<string,string>={PUBLISHED:'admin.contentStudio.statusPublished',DRAFT:'admin.contentStudio.statusDraft',REVIEW:'admin.contentStudio.statusReview',ARCHIVED:'admin.contentStudio.statusArchived'};
  return keys[code]?t(keys[code]):code.replaceAll('_',' ').toLowerCase();
 }
 const [rows,setRows]=useState<Row[]>([]);
 const [assessmentRows,setAssessmentRows]=useState<CourseAssessmentRow[]>([]);
 const [identity,setIdentity]=useState<any>(null);
 const [questionSummary,setQuestionSummary]=useState<any>(null);
 const [baselineSnapshot,setBaselineSnapshot]=useState('');
 const [classFilter,setClassFilter]=useState('ALL'),[subjectFilter,setSubjectFilter]=useState('ALL'),[statusFilter,setStatusFilter]=useState('ALL'),[search,setSearch]=useState('');
 const [programFilter,setProgramFilter]=useState(''),[programs,setPrograms]=useState<any[]>([]);
  const [chapterPage,setChapterPage]=useState(0),[chapterPageSize,setChapterPageSize]=useState(10);
  const [expandedChapters,setExpandedChapters]=useState<Set<number>>(()=>new Set());
  const [editorOpen,setEditorOpen]=useState(false);
 const [selected,setSelected]=useState<Selection|null>(null),[detail,setDetail]=useState<any>(null),[form,setForm]=useState<any>(EMPTY);
 const [blocks,setBlocks]=useState<ContentBlock[]>([]),[questions,setQuestions]=useState<any[]>([]);
 const [visualCatalog,setVisualCatalog]=useState<VisualCatalog|null>(null),[visualCatalogIndex,setVisualCatalogIndex]=useState<VisualCatalogIndex|null>(null),[visualCatalogSlug,setVisualCatalogSlug]=useState(''),[visualCatalogOptions,setVisualCatalogOptions]=useState<VisualCatalogIndex['catalogs']>([]),[visualCatalogLoading,setVisualCatalogLoading]=useState(false),[visualCatalogError,setVisualCatalogError]=useState(''),[visualSearch,setVisualSearch]=useState('');
 const [loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [versionBusy,setVersionBusy]=useState(false);
 const [createType,setCreateType]=useState<'chapter'|'lesson'|null>(null);
 const [createForm,setCreateForm]=useState<any>({classCode:'6',subjectCode:'maths',chapterId:0,code:'',displayName:'',description:'',sortOrder:1,title:'',summary:'',estimatedMinutes:10});
 const [activeBlock,setActiveBlock]=useState(0),[showPreview,setShowPreview]=useState(true),[previewMode,setPreviewMode]=useState<'desktop'|'phone'>('desktop'),[previewLanguage,setPreviewLanguage]=useState<'hi'|'en'>('hi'),[showAdvancedQuestions,setShowAdvancedQuestions]=useState(false),[questionJson,setQuestionJson]=useState('[]');
 const workspaceRef=useRef<HTMLElement|null>(null);
  const editorReturnFocusRef=useRef<HTMLElement|null>(null);
 const backToLibraryRef=useRef<(()=>void)|null>(null);
 useEffect(()=>{if((selected||createType)&&workspaceRef.current)workspaceRef.current.scrollIntoView({behavior:'smooth',block:'start'})},[selected,createType,detail]);
 useEffect(()=>{
  let mounted=true;
  fetch(REVIEW_ASSET_PREFIX+'index.v1.json',{cache:'no-store'})
   .then(async response=>{if(!response.ok)throw new Error('The textbook visual catalog index is not available yet.');return response.json()})
   .then(value=>{
    if(!mounted)return;
    if(value?.schema_version!=='quantaedge.visual-catalog-index.v1'||!Array.isArray(value.catalogs))throw new Error('The textbook visual catalog index has an unsupported format.');
    setVisualCatalogIndex(value as VisualCatalogIndex);setVisualCatalogError('');
   })
   .catch(error=>{if(mounted)setVisualCatalogError(error instanceof Error?error.message:'Textbook visual catalog index could not be loaded.')})
   .finally(()=>{if(mounted)setVisualCatalogLoading(false)});
  return ()=>{mounted=false};
 },[]);
 useEffect(()=>{
  let mounted=true;
  if(!visualCatalogIndex){
   setVisualCatalog(null);setVisualCatalogOptions([]);setVisualCatalogLoading(true);
   return ()=>{mounted=false};
  }
  if(!selected||selected.type!=='lesson'){
   setVisualCatalog(null);setVisualCatalogOptions([]);setVisualCatalogError('');setVisualCatalogLoading(false);
   return ()=>{mounted=false};
  }
  const targetRow=rows.find(row=>row.lesson_id===selected.id);
  if(!targetRow){
   setVisualCatalog(null);setVisualCatalogOptions([]);setVisualCatalogError('Select an existing micro-topic to browse its matching textbook visuals.');setVisualCatalogLoading(false);
   return ()=>{mounted=false};
  }
  const matches=visualCatalogIndex.catalogs.filter(item=>visualSourceMatchesLesson(targetRow,item));
  setVisualCatalogOptions(matches);
  if(matches.length===0){
   setVisualCatalog(null);setVisualCatalogError('No V3 textbook visual catalog matches the subject '+targetRow.subject_name+' yet. Add a catalog for this source book before attaching its figures.');setVisualCatalogLoading(false);
   return ()=>{mounted=false};
  }
  let entry=matches.find(item=>item.book_slug===visualCatalogSlug);
  if(!entry&&matches.length===1){
   entry=matches[0];setVisualCatalogSlug(entry.book_slug);
  }
  if(!entry){
   setVisualCatalog(null);setVisualCatalogError('More than one textbook source matches this lesson. Choose the exact book and edition before attaching a visual.');setVisualCatalogLoading(false);
   return ()=>{mounted=false};
  }
  if(!/^data\/textbook-visual-candidates-v3\/catalogs\/[a-z0-9]+(?:[.-][a-z0-9]+)*\.v3\.json$/.test(entry.catalog_path)){
   setVisualCatalog(null);setVisualCatalogError('The matching visual catalog has an invalid path.');setVisualCatalogLoading(false);
   return ()=>{mounted=false};
  }
  setVisualCatalogLoading(true);setVisualCatalogError('');
  fetch(RAW_REPO_CONTENT_PREFIX+entry.catalog_path,{cache:'no-store'})
   .then(async response=>{if(!response.ok)throw new Error('The matching textbook visual catalog could not be loaded.');return response.json()})
   .then(value=>{
    if(!mounted)return;
    if(value?.schema_version!=='quantaedge.visual-attachment-catalog.v3'||!Array.isArray(value.candidates)
      ||value.book_slug!==entry?.book_slug||value.source?.book_id!==entry?.source_book_id
      ||value.source?.pdf_sha256!==entry?.source_pdf_sha256
      ||value.summary?.publication_allowed!==false||value.summary?.review_required!==true)
      throw new Error('The selected textbook catalog does not match its checked-in index or is not review-only.');
    const catalog=value as VisualCatalog;
    setVisualCatalog({...catalog,candidates:catalog.candidates.map(candidate=>({...candidate,
      image_url:normalizeReviewAssetUrl(candidate.image_url)||'',
      source_page_url:normalizeReviewAssetUrl(candidate.source_page_url)
    }))});setVisualCatalogError('');
   })
   .catch(error=>{if(mounted){setVisualCatalog(null);setVisualCatalogError(error instanceof Error?error.message:'Textbook visual catalog could not be loaded.')}})
   .finally(()=>{if(mounted)setVisualCatalogLoading(false)});
  return ()=>{mounted=false};
 },[selected,rows,visualCatalogIndex,visualCatalogSlug]);
 async function load(nextProgram:string=programFilter){setLoading(true);setError('');try{
  const programRows=await api('/api/v1/admin/content/programs');
  const availablePrograms=Array.isArray(programRows)?programRows:[];
  setPrograms(availablePrograms);
  const requestedFromUrl=typeof window!=='undefined'?(new URLSearchParams(window.location.search).get('courseId')||''): '';
   const requestedProgram=nextProgram||programFilter||requestedFromUrl;
   const requestedIsExplicit=Boolean(requestedProgram);
   const requestedIsAvailable=availablePrograms.some((p:any)=>String(p.id||p.course_id)===String(requestedProgram));
   // An explicit deep link names one course; never substitute a different course if it
   // is no longer assigned to this institution.
   const selectedProgram=requestedIsAvailable
    ? String(requestedProgram)
    : requestedIsExplicit ? '' : String(availablePrograms[0]?.id||availablePrograms[0]?.course_id||'');
  if(!selectedProgram){
   setProgramFilter('');setRows([]);setAssessmentRows([]);setQuestionSummary({});setSelected(null);setDetail(null);
   setError(requestedIsExplicit
     ? 'The requested course is not assigned to this institution. Select a valid assigned course.'
     : 'No courses are assigned to this institution. Assign a course before managing content.');
   return;
  }
  if(selectedProgram!==programFilter)setProgramFilter(selectedProgram);
  const selectedCourse=availablePrograms.find((p:any)=>String(p.id||p.course_id)===selectedProgram);
  if(String(selectedCourse?.course_type_code||'').toUpperCase()==='ASSESSMENT'){
   const inventory=await api('/api/v1/admin/content/courses/'+encodeURIComponent(selectedProgram)+'/assessment-inventory');
   setAssessmentRows(Array.isArray(inventory)?inventory:[]);
   setRows([]);
   setQuestionSummary({});
   setSelected(null);setDetail(null);setCreateType(null);
   return;
  }
  setAssessmentRows([]);
  const [data,questionCounts]=await Promise.all([
   api('/api/v1/admin/content?courseId='+encodeURIComponent(selectedProgram)),
   api('/api/v1/admin/content/questions/review-summary?courseId='+encodeURIComponent(selectedProgram))
  ]);
  setRows(Array.isArray(data)?data:[]);
  setQuestionSummary(questionCounts);
 }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.contentLibraryLoadFailed'))}finally{setLoading(false)}}
 useEffect(()=>{
  let mounted=true;
  void (async()=>{
   try{
    const me=await api('/api/v1/auth/me');
    if(!me.staffId){window.location.assign('/login');return;}
    if(!mounted)return;
    setIdentity(me);
    await load();
   }catch(e){
    if(mounted){setError(e instanceof Error?e.message:t('admin.contentStudio.staffIdentityUnverified'));setLoading(false);}
   }
  })();
  return ()=>{mounted=false};
 },[]);
 const classOptions=useMemo(()=>Array.from(new Map(rows.map(r=>[r.class_code,r.class_name])).entries()),[rows]);
 const subjectOptions=useMemo(()=>Array.from(new Map(rows.map(r=>[r.subject_code,r.subject_name])).entries()),[rows]);
 const filtered=useMemo(()=>rows.filter(r=>{if(subjectFilter!=='ALL'&&r.subject_code!==subjectFilter)return false;const term=search.trim().toLocaleLowerCase();return !term||[r.class_name,r.subject_name,r.chapter_name||'',r.lesson_title||'',r.chapter_code||'',r.lesson_code||''].some(v=>v.toLocaleLowerCase().includes(term))}),[rows,subjectFilter,search]);
 const groups=useMemo(()=>{const map=new Map<number,Row[]>();filtered.forEach(row=>{if(row.chapter_id===null)return;const g=map.get(row.chapter_id)||[];g.push(row);map.set(row.chapter_id,g)});return Array.from(map.entries()).map(([id,g])=>({id,rows:g,chapter:g[0],lessons:g.filter(r=>r.lesson_id!==null&&(statusFilter==='ALL'||r.lesson_status===statusFilter))})).filter(g=>statusFilter==='ALL'||g.chapter.chapter_status===statusFilter||g.lessons.length>0)},[filtered,statusFilter]);
 const pagedGroups=useMemo(()=>groups.slice(chapterPage*chapterPageSize,(chapterPage+1)*chapterPageSize),[groups,chapterPage,chapterPageSize]);
  useEffect(()=>{setChapterPage(0)},[programFilter,subjectFilter,statusFilter,search]);
  useEffect(()=>{if(!editorOpen&&selected?.type==='lesson'&&typeof document!=='undefined')document.getElementById('content-topic-'+selected.id)?.scrollIntoView({block:'nearest',behavior:'smooth'})},[editorOpen,selected]);
  const uniqueChapters=new Set(rows.map(r=>r.chapter_id).filter((id):id is number=>id!==null)).size;
 const uniqueLessons=new Set(rows.map(r=>r.lesson_id).filter((id):id is number=>id!==null)).size;
 const publishedLessons=new Set(rows.filter(r=>r.lesson_id!==null&&r.lesson_status==='PUBLISHED'&&r.lesson_active&&r.chapter_status==='PUBLISHED'&&r.chapter_active===true).map(r=>r.lesson_id)).size;
 const publishedChapters=new Set(rows.filter(r=>r.chapter_id!==null&&r.chapter_status==='PUBLISHED'&&r.chapter_active===true).map(r=>r.chapter_id)).size;
 const draftLessons=new Set(rows.filter(r=>r.lesson_id!==null&&(r.lesson_status==='DRAFT'||r.lesson_status==='REVIEW')).map(r=>r.lesson_id)).size;
 const activeQuestionCount=(statuses:string[]) => (Array.isArray(questionSummary?.byStatus)?questionSummary.byStatus:[])
  .filter((row:any)=>row.active===true&&statuses.includes(String(row.review_status||'DRAFT').toUpperCase()))
  .reduce((n:number,row:any)=>n+Number(row.count||0),0);
 const activeQuestionsAwaitingReview=activeQuestionCount(['REVIEW']);
 const activeQuestionDrafts=activeQuestionCount(['DRAFT']);
 const activeRejectedQuestions=activeQuestionCount(['REJECTED']);
 const active=activeBlock>=0&&activeBlock<blocks.length?blocks[activeBlock]:null;
 const permissions=Array.isArray(identity?.permissions)?identity.permissions:[];
 const hasPermission=(permission:string)=>permissions.includes(permission);
 const activeProgram=programs.find((p:any)=>String(p.id||p.course_id)===String(programFilter));
 const isAssessmentCourse=String(activeProgram?.course_type_code||'').toUpperCase()==='ASSESSMENT';
 const isSourcedCourse=activeProgram?.course_relationship==='SOURCED';
 const releaseState=String(activeProgram?.release_state||'DRAFT').toUpperCase();
 // Released and in-review course graphs are immutable. Source edits must happen in a forked draft,
 // never by writing into the graph that current offers, learners or invoices already reference.
 const isEditableDraft=isSourcedCourse&&['DRAFT','REJECTED'].includes(releaseState);
 const canView=hasPermission('CONTENT_VIEW');
 const canCreate=hasPermission('CONTENT_CREATE')&&isEditableDraft;
 const canEdit=hasPermission('CONTENT_EDIT')&&isEditableDraft;
 const canSubmit=hasPermission('CONTENT_SUBMIT')&&isEditableDraft;
 const canReview=hasPermission('CONTENT_REVIEW')&&isEditableDraft;
 const canPublishPermission=hasPermission('CONTENT_PUBLISH')&&isEditableDraft;
 const canForkVersion=Boolean(isSourcedCourse
  && ['SHAREABLE','WITHDRAWN','LEGACY_MIGRATED'].includes(releaseState)
  && hasPermission('CONTENT_CREATE')&&hasPermission('CONTENT_EDIT'));
 async function createDraftVersion(){
  const sourceCourseId=Number(programFilter);
  if(!Number.isSafeInteger(sourceCourseId)||sourceCourseId<=0||!canForkVersion)return;
  setVersionBusy(true);setError('');setNotice('');
  try{
   const result=await api('/api/v1/admin/content/courses/'+sourceCourseId+'/draft-version',{method:'POST'});
   const nextCourseId=Number(result?.courseId||result?.course_id||0);
   if(!Number.isSafeInteger(nextCourseId)||nextCourseId<=0)throw new Error('The server did not return the new draft course ID.');
   const nextCourseVersion=Number(result?.contentVersionNo||result?.content_version_no||0);
   setSelected(null);setDetail(null);setEditorOpen(false);setCreateType(null);
   setSubjectFilter('ALL');setClassFilter('ALL');
   setProgramFilter(String(nextCourseId));
   const url=new URL(window.location.href);url.searchParams.set('courseId',String(nextCourseId));window.history.replaceState({},'',url.toString());
   await load(String(nextCourseId));
   setNotice('Created draft content version v'+(nextCourseVersion||'?')+'. The previous published version remains unchanged; complete source review and the full HRMS Marketplace update stepper before this version is distributed.');
  }catch(e){setError(e instanceof Error?e.message:'Could not create a safe draft version. The published version was not changed.');}
  finally{setVersionBusy(false);}
 }
  const visualTargetRow=selected?.type==='lesson'?rows.find(row=>row.lesson_id===selected.id):undefined;
  const visualCatalogMatchesTarget=visualCatalogMatchesLesson(visualTargetRow,visualCatalog);
 const isEditorView=Boolean(editorOpen&&(selected||createType));
 const createHasInput=Boolean(createType&&['displayName','description','code','title','summary','curriculumSource','curriculumSourceUrl','alignmentSourceTitle','alignmentSourceUrl'].some(key=>String(createForm[key]??'').trim()));
 const draftSnapshot=useMemo(()=>{
  if(createType)return JSON.stringify({createForm});
  if(selected?.type==='chapter')return JSON.stringify({form});
  if(selected?.type==='lesson')return JSON.stringify({form,blocks,questions:questionsForSnapshot(showAdvancedQuestions,questionJson,questions)});
  return '';
 },[selected,createType,createForm,form,blocks,questions,showAdvancedQuestions,questionJson]);
 const hasUnsavedChanges=createType?createHasInput:Boolean(baselineSnapshot&&baselineSnapshot!==draftSnapshot);
 const previewReviewed=Boolean(!hasUnsavedChanges&&Number(detail?.content_revision)>0&&Number(detail?.preview_checked_revision)===Number(detail?.content_revision));
 const backToLibrary=()=>{
  if(hasUnsavedChanges&&!window.confirm(t('admin.contentStudio.unsavedChangesConfirm')))return;
  setEditorOpen(false);
  if(createType){setCreateType(null);setSelected(null);setDetail(null);}
  setBaselineSnapshot('');setError('');setNotice('');
 };
 backToLibraryRef.current=backToLibrary;
 useEffect(()=>{
  if(!editorOpen){editorReturnFocusRef.current?.focus({preventScroll:true});editorReturnFocusRef.current=null;return;}
  const dialog=workspaceRef.current;
  if(!dialog)return;
  const focusable=()=>Array.from(dialog.querySelectorAll<HTMLElement>('a[href],button:not([disabled]),input:not([disabled]),select:not([disabled]),textarea:not([disabled]),[tabindex]:not([tabindex="-1"])')).filter(node=>!node.hasAttribute('hidden')&&node.getAttribute('aria-hidden')!=='true');
  (focusable()[0]||dialog).focus({preventScroll:true});
  const handleKeyDown=(event:KeyboardEvent)=>{
   if(event.key==='Escape'){event.preventDefault();backToLibraryRef.current?.();return;}
   if(event.key!=='Tab')return;
   const nodes=focusable();
   if(nodes.length===0){event.preventDefault();dialog.focus();return;}
   const first=nodes[0],last=nodes[nodes.length-1];
   if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}
   else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}
  };
  dialog.addEventListener('keydown',handleKeyDown);
  return()=>dialog.removeEventListener('keydown',handleKeyDown);
 },[editorOpen]);
 const markPreviewReviewed=async()=>{
  if(!selected||selected.type!=='lesson'||!detail||saving||hasUnsavedChanges||!canSubmit||!preparationReady)return;
  const revision=Number(detail.content_revision);
  if(!Number.isInteger(revision)||revision<1){
   setError(t('admin.contentStudio.previewVersionMissing'));
   return;
  }
  setSaving(true);setError('');setNotice('');
  try{
   await api('/api/v1/admin/content/lessons/'+selected.id+'/preview-check',{
    method:'POST',body:JSON.stringify({contentRevision:revision,courseId:Number(programFilter)})
   });
   await load();await open(selected);
   setNotice(t('admin.contentStudio.previewConfirmed',{revision}));
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.previewConfirmFailed'))}
  finally{setSaving(false)}
 };
 function updateContent(index:number,patch:Record<string,any>){setBlocks(old=>old.map((b,i)=>i===index?{...b,content:{...b.content,...patch}}:b))}
 async function open(item:Selection){
  if(!editorOpen&&typeof document!=='undefined')editorReturnFocusRef.current=document.activeElement instanceof HTMLElement?document.activeElement:null;
  setEditorOpen(true);
  if(item.type==='lesson'){
   const openedRow=rows.find(row=>row.lesson_id===item.id);
   if(openedRow?.chapter_id!==null&&openedRow?.chapter_id!==undefined)setExpandedChapters(current=>new Set([...current,openedRow.chapter_id as number]));
  }
  setSelected(item);setDetail(null);setError('');setNotice('');setCreateType(null);setActiveBlock(0);setShowAdvancedQuestions(false);
  try{
   const data=await api('/api/v1/admin/content/'+(item.type==='chapter'?'chapters':'lessons')+'/'+item.id+'?courseId='+encodeURIComponent(programFilter));
   setDetail(data);
   const nextForm=item.type==='chapter'?{...EMPTY,displayName:data.chapter_name||'',description:data.chapter_description||'',chapterStatus:data.chapter_status||'DRAFT',chapterSortOrder:Number(data.chapter_sort_order||1),curriculumSource:data.curriculum_source||'',curriculumSourceUrl:data.curriculum_source_url||'',curriculumSourceEdition:data.curriculum_source_edition||'',curriculumSourcePages:data.curriculum_source_pages||'',curriculumSourceVerified:Boolean(data.curriculum_source_verified)}:{...EMPTY,title:data.lesson_title||'',summary:data.lesson_summary||'',estimatedMinutes:Number(data.estimated_minutes||10),lessonStatus:data.lesson_status||'DRAFT',sortOrder:Number(data.lesson_sort_order||1),alignmentSourceTitle:data.alignment_source_title||'',alignmentSourceUrl:data.alignment_source_url||'',alignmentSourceEdition:data.alignment_source_edition||'',alignmentPageRange:data.alignment_page_range||'',alignmentSourceVerified:Boolean(data.alignment_source_verified)};
   setForm(nextForm);
   const nextBlocks=(data.blocks||[]).map((b:any,i:number)=>normalizeBlock({id:Number(b.id||0),sequence_no:Number(b.sequence_no||i+1),block_type:String(b.block_type||'EXPLANATION'),content:parsed(b.content,{}),active:b.active!==false}));
   const qs=(data.questions||[]).map((q:any)=>({...q,options:parsed(q.options,[]),tags:parsed(q.tags,[]),answer_payload:parsed(q.answer_payload,{})}));
   setBlocks(nextBlocks);setQuestions(qs);setQuestionJson(JSON.stringify(qs,null,2));
   setBaselineSnapshot(item.type==='chapter'?JSON.stringify({form:nextForm}):JSON.stringify({form:nextForm,blocks:nextBlocks,questions:qs}));
   
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.openContentFailed'))}
 }
 function beginCreate(type:'chapter'|'lesson'){
  if(!editorOpen&&typeof document!=='undefined')editorReturnFocusRef.current=document.activeElement instanceof HTMLElement?document.activeElement:null;
  const chapter=rows.find(r=>r.chapter_id!==null&&(subjectFilter==='ALL'||r.subject_code===subjectFilter));
  const selectedCourse=programs.find((p:any)=>String(p.id||p.course_id)===String(programFilter));
  const subjectCode=subjectFilter!=='ALL'?subjectFilter:(chapter?.subject_code||selectedCourse?.subjects?.[0]?.subject_code||selectedCourse?.subjects?.[0]?.code||'');
  const mappedSubject=(selectedCourse?.subjects||[]).find((subject:any)=>String(subject.subject_code||subject.code||'')===subjectCode);
  const classCode=chapter?.class_code||mappedSubject?.curriculum_class_code||selectedCourse?.curriculum_class_code||'';
  setSelected(null);setDetail(null);setEditorOpen(true);setError('');setNotice('');setCreateType(type);setBaselineSnapshot('');
  setCreateForm({classCode,subjectCode,courseId:programFilter||'',chapterId:selected?.type==='chapter'?selected.id:Number(chapter?.chapter_id||0),code:'',displayName:'',description:'',curriculumSource:'',curriculumSourceUrl:'',curriculumSourceEdition:'',curriculumSourcePages:'',alignmentSourceTitle:'',alignmentSourceUrl:'',alignmentSourceEdition:'',alignmentPageRange:'',sortOrder:1,title:'',summary:'',estimatedMinutes:10});
 }
 async function create(){
  if(!createType)return;setSaving(true);setError('');setNotice('');
  try{
   if(!programFilter||!createForm.subjectCode||!createForm.classCode)throw new Error('Select a mapped subject in the selected course before creating a chapter.');
   const result=createType==='chapter'?await api('/api/v1/admin/content/chapters',{method:'POST',body:JSON.stringify({classCode:createForm.classCode,subjectCode:createForm.subjectCode,courseId:Number(programFilter),code:createForm.code,displayName:createForm.displayName,description:createForm.description,curriculumSource:createForm.curriculumSource,curriculumSourceUrl:createForm.curriculumSourceUrl,curriculumSourceEdition:createForm.curriculumSourceEdition,curriculumSourcePages:createForm.curriculumSourcePages,sortOrder:Number(createForm.sortOrder),status:'DRAFT'})}):await api('/api/v1/admin/content/lessons',{method:'POST',body:JSON.stringify({courseId:Number(programFilter),chapterId:Number(createForm.chapterId),code:createForm.code,title:createForm.title,summary:createForm.summary,estimatedMinutes:Number(createForm.estimatedMinutes),alignmentSourceTitle:createForm.alignmentSourceTitle,alignmentSourceUrl:createForm.alignmentSourceUrl,alignmentSourceEdition:createForm.alignmentSourceEdition,alignmentPageRange:createForm.alignmentPageRange,sortOrder:Number(createForm.sortOrder),status:'DRAFT'})});
   const id=Number(createType==='chapter'?result.chapter_id:result.lesson_id);const kind=createType;setCreateType(null);await load();await open({type:kind,id});setNotice(t('admin.contentStudio.draftCreated',{kind:t(kind==='chapter'?'admin.contentStudio.chapter':'admin.contentStudio.microTopic')}));
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.createFailed'))}finally{setSaving(false)}
 }
 async function verifyChapterSource(){
   if(!selected||selected.type!=='chapter'||saving||hasUnsavedChanges||!canReview||!chapterSourceDetailsComplete||form.curriculumSourceVerified)return;
   setSaving(true);setError('');setNotice('');
   try{
    await api('/api/v1/admin/content/chapters/'+selected.id+'/verify-source?courseId='+encodeURIComponent(programFilter),{method:'POST'});
    await load();await open(selected);
    setNotice(t('admin.contentStudio.curriculumVerified'));
   }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.curriculumVerifyFailed'))}finally{setSaving(false)}
  }
  async function verifyLessonSource(){
   if(!selected||selected.type!=='lesson'||saving||hasUnsavedChanges||!canReview||!lessonSourceDetailsComplete||form.alignmentSourceVerified)return;
   setSaving(true);setError('');setNotice('');
   try{
    await api('/api/v1/admin/content/lessons/'+selected.id+'/verify-source?courseId='+encodeURIComponent(programFilter),{method:'POST'});
    await load();await open(selected);
    setNotice(t('admin.contentStudio.textbookMappingVerified'));
   }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.textbookVerifyFailed'))}finally{setSaving(false)}
  }
  async function setChapterPublication(publish:boolean){
  if(!selected||selected.type!=='chapter'||saving||hasUnsavedChanges||!canPublishPermission)return;
  setSaving(true);setError('');setNotice('');
  try{
   await api('/api/v1/admin/content/chapters/'+selected.id+(publish?'/publish':'/unpublish')+'?courseId='+encodeURIComponent(programFilter),{method:'POST'});
   await load();await open(selected);
   setNotice(publish?t('admin.contentStudio.chapterPublished'):t('admin.contentStudio.chapterUnpublished'));
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.chapterPublicationFailed'))}finally{setSaving(false)}
 }
 async function setLessonPublication(publish:boolean){
  if(!selected||selected.type!=='lesson'||saving||hasUnsavedChanges||!canPublishPermission)return;
  setSaving(true);setError('');setNotice('');
  try{
   await api('/api/v1/admin/content/lessons/'+selected.id+(publish?'/publish':'/unpublish')+'?courseId='+encodeURIComponent(programFilter),{method:'POST'});
   await load();await open(selected);
   setNotice(publish?t('admin.contentStudio.microTopicPublished'):t('admin.contentStudio.microTopicUnpublished'));
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.publicationFailed'))}finally{setSaving(false)}
 }
 async function submitLessonForReview(){
  if(!selected||selected.type!=='lesson'||saving||hasUnsavedChanges||!canSubmit)return;
  setSaving(true);setError('');setNotice('');
  try{
   await api('/api/v1/admin/content/lessons/'+selected.id+'/submit?courseId='+encodeURIComponent(programFilter),{method:'POST'});
   await load();await open(selected);
   setNotice(t('admin.contentStudio.submittedToReview'));
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.submitReviewFailed'))}finally{setSaving(false)}
 }
 async function reviewQuestion(questionId:number,status:'APPROVED'|'REJECTED'|'REVIEW'){
  if(!selected||selected.type!=='lesson'||saving||hasUnsavedChanges||!canReview||!questionId)return;
  if(!['REVIEW','PUBLISHED'].includes(String(form.lessonStatus||'').toUpperCase())){setError(t('admin.contentStudio.submitDraftBeforeQuestionReview'));return;}
  const note=status==='REJECTED'?window.prompt(t('admin.contentStudio.authorFeedbackPrompt')):null;
  if(status==='REJECTED'&&note===null)return;
  if(status==='REJECTED'&&!String(note||'').trim()){setError(t('admin.contentStudio.authorFeedbackRequired'));return;}
  setSaving(true);setError('');setNotice('');
  try{
   await api('/api/v1/admin/content/lessons/'+selected.id+'/questions/'+questionId+'/review?courseId='+encodeURIComponent(programFilter),{
    method:'POST',body:JSON.stringify({status,reviewNotes:note||undefined})
   });
   await load();await open(selected);
   setNotice(status==='APPROVED'?t('admin.contentStudio.questionApproved'):t('admin.contentStudio.questionReturned'));
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.questionReviewFailed'))}finally{setSaving(false)}
 }
 async function saveChapter(nextStatus?:string){
  if(!selected||selected.type!=='chapter')return;setSaving(true);setError('');setNotice('');
  try{await api('/api/v1/admin/content/chapters/'+selected.id,{method:'PATCH',body:JSON.stringify({courseId:Number(programFilter),displayName:form.displayName,description:form.description,curriculumSource:form.curriculumSource,curriculumSourceUrl:form.curriculumSourceUrl,curriculumSourceEdition:form.curriculumSourceEdition,curriculumSourcePages:form.curriculumSourcePages,curriculumSourceVerified:Boolean(form.curriculumSourceVerified),status:nextStatus||form.chapterStatus,sortOrder:Number(form.chapterSortOrder)})});await load();await open(selected);setNotice(nextStatus==='PUBLISHED'?'Publish request completed. Review the resulting state.':'Chapter details saved.')}
  catch(e){setError(e instanceof Error?e.message:'Chapter could not be saved.')}finally{setSaving(false)}
 }
 function addBlock(type:string){
  if(blocks.length>=100){setError(t('admin.contentStudio.maxTeachingBlocksReached',{count:100}));return}
  const next:ContentBlock={sequence_no:blocks.length+1,block_type:type,content:contentFor(type),active:true};setBlocks(old=>[...old,next]);setActiveBlock(blocks.length);setNotice('Added '+(BLOCK_OPTIONS.find(x=>x.type===type)?.label||type)+'. Complete the content, then save.');
 }

 function attachVisualCandidate(candidate:VisualCandidate){
  if(candidate.candidate_kind==='page_preview'){setNotice(t('admin.contentStudio.wholePagePreviewContext'));return;}
  if(!canEdit||saving||!selected||selected.type!=='lesson')return;
  const targetRow=rows.find(row=>row.lesson_id===selected.id);
  if(!visualCatalogMatchesLesson(targetRow,visualCatalog)){
   const sourceLabel=visualCatalog?('Class '+visualCatalog.source.class+' '+visualCatalog.source.subject):'the selected textbook';
   const lessonLabel=targetRow?('Class '+targetRow.class_code+' '+targetRow.subject_name):'the selected lesson';
   setError(t('admin.contentStudio.visualSourceMismatch',{sourceLabel,lessonLabel}));return;
  }
  const existing=blocks.findIndex(block=>block.content?.source_candidate_id===candidate.candidate_id&&block.content?.source_book_id===candidate.source_book_id&&block.content?.source_pdf_sha256===candidate.source_pdf_sha256);
  if(existing>=0){setActiveBlock(existing);setNotice(t('admin.contentStudio.candidateAlreadyAttached'));return;}
  if(blocks.length>=100){setError(t('admin.contentStudio.maxTeachingBlocksReached',{count:100}));return;}
  if(!candidate.image_url){setError(t('admin.contentStudio.candidateNoImage'));return;}
  const sourcePage=Number(candidate.source_pdf_page_number);
  const next:ContentBlock={sequence_no:blocks.length+1,block_type:'IMAGE',active:true,content:{
   title:'Textbook visual · PDF page '+sourcePage,
   url:candidate.image_url,
   description:'',
   alt:'',
   caption:'',
   visual_kind:'TEXTBOOK_FIGURE',
   source_candidate_id:candidate.candidate_id,
   source_book_id:candidate.source_book_id,
   source_class:visualCatalog?.source?.class||'',
   source_subject:visualCatalog?.source?.subject||'',
   source_book_title:visualCatalog?.source?.title||candidate.source_book_id,
   source_edition:visualCatalog?.source?.edition||'',
   source_language:visualCatalog?.source?.language||'english',
   source_pdf_sha256:candidate.source_pdf_sha256,
   source_pdf_page_number:sourcePage,
   source_asset_sha256:candidate.source_asset_sha256||'',
   source_bbox_pdf_points:candidate.bbox_pdf_points||null,
   source_page_url:candidate.source_page_url||'',
   source_page_context:String(candidate.page_text_context||'').slice(0,1200),
   rights_status:'SOURCE_REFERENCE_ONLY',
   visual_review_status:'NEEDS_REVIEW',
   publication_allowed:false,
   human_review_required:true
  }};
  const scored=blocks.map((block,index)=>({index,score:visualCandidateBlockRelevanceScore(candidate,block)})).filter(item=>item.score>=0);
  const best=scored.reduce((current,item)=>item.score>current.score?item:current,{index:-1,score:0});
  let insertAt=best.index>=0&&best.score>0?best.index+1:-1;
  if(insertAt<0){
   const firstExplanation=blocks.findIndex(block=>block.block_type==='EXPLANATION');
   if(firstExplanation>=0)insertAt=firstExplanation+1;
   else {
    const firstRecap=blocks.findIndex(block=>block.block_type==='SUMMARY'||block.block_type==='RECAP');
    insertAt=firstRecap>=0?firstRecap:blocks.length;
   }
  }
  setBlocks(old=>[...old.slice(0,insertAt),next,...old.slice(insertAt)].map((block,index)=>({...block,sequence_no:index+1})));
  setActiveBlock(insertAt);
  setNotice(best.index>=0&&best.score>0
   ?t('admin.contentStudio.visualAddedSourceOverlap')
   :t('admin.contentStudio.visualAddedSuggestedPlacement'));
 }
 function updateQuestion(index:number,patch:Record<string,any>){
  const editsQuestionContent=Object.keys(patch).some(key=>!['review_status','review_notes','active','sort_order'].includes(key));
  setQuestions(old=>{
   const next=old.map((q,i)=>{
    if(i!==index)return q;
    const updated={...q,...patch};
    if(editsQuestionContent&&['APPROVED','PUBLISHED','REVIEW','REJECTED'].includes(String(q.review_status||'').toUpperCase())&&!Object.prototype.hasOwnProperty.call(patch,'review_status')){
     updated.review_status='DRAFT';
     updated.review_notes='Content changed; review it again before publishing.';
    }
    return updated;
   });
   setQuestionJson(JSON.stringify(next,null,2));return next;
  });
 }
 function updateOption(qIndex:number,oIndex:number,patch:Record<string,any>){const q=questions[qIndex];if(!q)return;updateQuestion(qIndex,{options:(q.options||[]).map((o:any,i:number)=>({...o,...(i===oIndex?patch:{})}))})}
 function addQuestion(){
  const index=questions.length+1;const q={question_type:'MCQ',prompt:'',explanation:'',difficulty:'CORE',sort_order:index,active:true,review_status:'DRAFT',review_notes:null,marks:1,exam_format:'Practice',source_kind:'AUTHOR_CREATED',source_title:null,source_ref:null,source_year:null,source_id:null,board:null,topic:form.title||null,subtopic:null,skill:null,tags:[],answer_payload:{kind:'OPTION',value:'A'},options:[{key:'A',label:'',correct:true,sort_order:1},{key:'B',label:'',correct:false,sort_order:2}]};const next=[...questions,q];setQuestions(next);setQuestionJson(JSON.stringify(next,null,2));
 }
 function applyQuestionJson(){try{const q=JSON.parse(questionJson);if(!Array.isArray(q))throw new Error(t('admin.contentStudio.questionsMustJsonArray'));setQuestions(q);setShowAdvancedQuestions(false);setNotice(t('admin.contentStudio.advancedQuestionDataLoaded'))}catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.invalidQuestionJson'))}}
 function removeBlock(index:number){if(blocks.length<=1){setError(t('admin.contentStudio.atLeastOneTeachingBlock'));return}setBlocks(old=>old.filter((_,i)=>i!==index).map((b,i)=>({...b,sequence_no:i+1})));setActiveBlock(Math.max(0,Math.min(index,blocks.length-2)))}
 function moveBlock(index:number,direction:number){const target=index+direction;if(target<0||target>=blocks.length)return;setBlocks(old=>{const next=[...old];const item=next.splice(index,1)[0];next.splice(target,0,item);return next.map((b,i)=>({...b,sequence_no:i+1}))});setActiveBlock(target)}
 function duplicateBlock(index:number){if(blocks.length>=100){setError(t('admin.contentStudio.maxTeachingBlocksReached',{count:100}));return}setBlocks(old=>[...old.slice(0,index+1),{...old[index],content:{...old[index].content},sequence_no:index+2},...old.slice(index+1)].map((b,i)=>({...b,sequence_no:i+1})));setActiveBlock(index+1)}
 async function saveLesson(nextStatus?:string){
  if(!selected||selected.type!=='lesson')return;
  const previousStatus=String(form.lessonStatus||'DRAFT').toUpperCase();
  setSaving(true);setError('');setNotice('');
  try{
   const nextBlocks=blocks.map((b,i)=>({sequence_no:i+1,block_type:b.block_type,content:normalizeBlock(b).content,active:b.active!==false}));
   if(!nextBlocks.length)throw new Error(t('admin.contentStudio.atLeastOneTeachingBlock'));
   const nextQuestions=showAdvancedQuestions?JSON.parse(questionJson):questions;if(!Array.isArray(nextQuestions))throw new Error(t('admin.contentStudio.assessmentQuestionsMustList'));
   await api('/api/v1/admin/content/lessons/'+selected.id,{method:'PATCH',body:JSON.stringify({courseId:Number(programFilter),title:form.title,summary:form.summary,estimatedMinutes:Number(form.estimatedMinutes),alignmentSourceTitle:form.alignmentSourceTitle,alignmentSourceUrl:form.alignmentSourceUrl,alignmentSourceEdition:form.alignmentSourceEdition,alignmentPageRange:form.alignmentPageRange,alignmentSourceVerified:Boolean(form.alignmentSourceVerified),status:nextStatus||form.lessonStatus,sortOrder:Number(form.sortOrder),blocks:nextBlocks,questions:nextQuestions})});
   await load();await open(selected);setNotice(nextStatus==='REVIEW'?t('admin.contentStudio.lessonSavedForReview'):nextStatus==='PUBLISHED'?t('admin.contentStudio.publishRequestComplete'):['REVIEW','PUBLISHED','ARCHIVED'].includes(previousStatus)?t('admin.contentStudio.changesSavedAsDraft'):t('admin.contentStudio.lessonAndBlocksSaved'));
  }catch(e){setError(e instanceof Error?e.message:t('admin.contentStudio.lessonSaveFailed'))}finally{setSaving(false)}
 }
 const basicsReady=Boolean(String(form.title||'').trim()&&String(form.summary||'').trim()&&Number(form.estimatedMinutes)>=1&&Number(form.estimatedMinutes)<=120);
  const activeBlocks=blocks.filter(block=>block.active!==false);
  const blocksCompleteCount=activeBlocks.filter(blockHasPublishableContent).length;
  const structureStageIssues=lessonStructureIssues(activeBlocks);
  const blocksReady=activeBlocks.length>0&&activeBlocks.some(block=>block.block_type!=='AI_HELP')&&blocksCompleteCount===activeBlocks.length&&structureStageIssues.length===0;
  const previewBlocks=orderedLearnerBlocks(activeBlocks);
  // Mirror LearnClient: keep every non-recap block in authored order before assessment, then show recap blocks after practice.
  const previewTeachingBlocks=previewBlocks.filter(block=>block.block_type!=='SUMMARY'&&block.block_type!=='RECAP');
  const previewRecapBlocks=previewBlocks.filter(block=>block.block_type==='SUMMARY'||block.block_type==='RECAP');
  const unsupportedActiveBlocks=activeBlocks.filter(block=>!LEARNER_BLOCK_TYPES.has(block.block_type));
  const activeQuestions=questions.filter(question=>question.active!==false);
  const validActiveQuestionCount=activeQuestions.filter(questionHasValidAnswer).length;
  const invalidActiveQuestionDetails=activeQuestions.map((question:any,index:number)=>({number:index+1,issues:questionReadinessIssues(question)})).filter((item:any)=>item.issues.length>0);
  const pendingQuestionReviewCount=activeQuestions.filter((question:any)=>!['APPROVED','PUBLISHED'].includes(String(question.review_status||'DRAFT').toUpperCase())).length;
  const learnerVisibleQuestions=activeQuestions.filter((question:any)=>['APPROVED','PUBLISHED'].includes(String(question.review_status||'DRAFT').toUpperCase()));
  const learnerVisibleMcqs=learnerVisibleQuestions.filter((question:any)=>String(question.question_type||'').toUpperCase()==='MCQ');
  const otherApprovedPracticeQuestionCount=learnerVisibleQuestions.length-learnerVisibleMcqs.length;
  const questionsReady=activeQuestions.length>0&&validActiveQuestionCount===activeQuestions.length;
  const lessonSourceDetailsComplete=Boolean(String(form.alignmentSourceTitle||'').trim()&&/^https:\/\//i.test(String(form.alignmentSourceUrl||''))&&String(form.alignmentSourceEdition||'').trim()&&String(form.alignmentPageRange||'').trim());
  const chapterSourceDetailsComplete=Boolean(String(form.curriculumSource||'').trim()&&/^https:\/\//i.test(String(form.curriculumSourceUrl||''))&&String(form.curriculumSourceEdition||'').trim()&&String(form.curriculumSourcePages||'').trim());
  const lessonSourceDetailsChanged=Boolean(selected?.type==='lesson'&&detail&&(
   String(form.alignmentSourceTitle||'')!==String(detail.alignment_source_title||'')||
   String(form.alignmentSourceUrl||'')!==String(detail.alignment_source_url||'')||
   String(form.alignmentSourceEdition||'')!==String(detail.alignment_source_edition||'')||
   String(form.alignmentPageRange||'')!==String(detail.alignment_page_range||'')
  ));
  const chapterSourceDetailsChanged=Boolean(selected?.type==='chapter'&&detail&&(
   String(form.curriculumSource||'')!==String(detail.curriculum_source||'')||
   String(form.curriculumSourceUrl||'')!==String(detail.curriculum_source_url||'')||
   String(form.curriculumSourceEdition||'')!==String(detail.curriculum_source_edition||'')||
   String(form.curriculumSourcePages||'')!==String(detail.curriculum_source_pages||'')
  ));
  const sourceReady=Boolean(form.alignmentSourceVerified&&lessonSourceDetailsComplete&&!lessonSourceDetailsChanged);
  const chapterSourceReady=Boolean(form.curriculumSourceVerified&&chapterSourceDetailsComplete&&!chapterSourceDetailsChanged);
  const chapterReady=detail?.chapter_status==='PUBLISHED'&&detail?.chapter_active!==false;
  const parentChapterAvailable=Boolean(detail?.chapter_id)&&detail?.chapter_active!==false;
  const publishReadyLessonCount=Number(detail?.publish_ready_lesson_count||0);
  const chapterReadyToPublish=publishReadyLessonCount>0;
  const lessonStatus=String(form.lessonStatus||'DRAFT').toUpperCase();
  const submittedForReview=!hasUnsavedChanges&&['REVIEW','PUBLISHED'].includes(lessonStatus);
  const savedContentRevision=Number(detail?.content_revision);
  const sourceReadinessDetail=sourceReady
    ? 'Official source title, URL, edition and page range have been verified.'
    : lessonSourceDetailsChanged
      ? 'Source details have changed. Save them, then have a reviewer verify the mapping again.'
      : !lessonSourceDetailsComplete
        ? 'Enter the official source title, HTTPS URL, edition and exact page range.'
        : canReview
          ? 'Verify the completed source mapping against the official source.'
          : 'A content reviewer must verify the completed source mapping before submission.';
  const questionApprovalDetail=activeQuestions.length===0
    ? 'Add and activate at least one practice question first.'
    : lessonStatus==='DRAFT'
      ? activeQuestions.length+' active question(s) included. Submit the topic first; then every active question must be reviewed and approved.'
      : questionsReady
        ? 'All '+activeQuestions.length+' active questions are approved and valid.'
        : validActiveQuestionCount+' of '+activeQuestions.length+' active questions are approved and valid; '+pendingQuestionReviewCount+' still need review or correction.'+(invalidActiveQuestionDetails.length?' '+invalidActiveQuestionDetails.slice(0,2).map((item:any)=>'Q'+item.number+': '+item.issues[0]).join(' · ')+(invalidActiveQuestionDetails.length>2?' · and '+(invalidActiveQuestionDetails.length-2)+' more':''):'');
  const chapterReadinessDetail=chapterReady
    ? 'The parent chapter is published and active.'
    : !parentChapterAvailable
      ? 'The parent chapter is missing or inactive. Restore it before submitting this topic.'
      : !submittedForReview
        ? hasUnsavedChanges
          ? 'Save your changes and resubmit the topic before preparing the parent chapter for publication.'
          : 'The chapter may remain a draft during review. Publish it after the questions are approved.'
        : !questionsReady
          ? 'Finish reviewing all active questions first. Then publish the parent chapter before this topic.'
          : 'All question reviews are complete. Publish the parent chapter before publishing this micro-topic.';
  const canSubmitLessonForReview=basicsReady&&blocksReady&&previewReviewed&&activeQuestions.length>0&&sourceReady&&parentChapterAvailable;
  const readiness=[
   {ok:basicsReady,label:'1. Topic details are complete',detail:basicsReady?(hasUnsavedChanges?'Title, learner goal and time are complete in the editor. Save changes before continuing.':'Title, learner goal and a valid learning time are saved.'):'Add a clear topic title, learner goal and learning time between 1 and 120 minutes.'},
   {ok:blocksReady,label:'2. Teaching content follows the learner path',detail:activeBlocks.length?blocksCompleteCount+' of '+activeBlocks.length+' active teaching blocks are complete'+(blocksReady?'':'. '+[...(blocksCompleteCount===activeBlocks.length?[]:['Complete or deactivate every unfinished block.']),...structureStageIssues].join(' ')):'Add an Explanation, practice activity, and Recap with real content.'},
   {ok:sourceReady,label:'3. Official textbook mapping is verified',detail:sourceReadinessDetail},
   {ok:activeQuestions.length>0,label:'4. Practice questions are included',detail:activeQuestions.length?activeQuestions.length+' active practice question(s) included. All active questions must be approved in step 7.':'Add at least one active practice question. Inactive questions do not count.'},
   {ok:previewReviewed,label:'5. Current saved learner preview is checked',detail:hasUnsavedChanges?'Save your changes first. The preview confirmation must refer to the current saved version.':previewReviewed?'Saved content version '+savedContentRevision+' has been checked.':'Open the learner preview, inspect the current saved version and confirm it there.'},
   {ok:submittedForReview,label:lessonStatus==='PUBLISHED'&&!hasUnsavedChanges?'6. Review is complete':'6. Micro-topic is submitted for review',detail:hasUnsavedChanges&&['REVIEW','PUBLISHED','ARCHIVED'].includes(lessonStatus)?'Unsaved edits will return this topic to draft when saved. Save, confirm the new preview, then submit it for review again.':lessonStatus==='REVIEW'?'Submitted. The topic is waiting for review and question decisions.':lessonStatus==='PUBLISHED'?'The review workflow is complete and the micro-topic is published.':lessonStatus==='ARCHIVED'?'This topic is archived. Restore it to draft before submitting it again.':'After checks 1–5 pass, save the topic and select “Submit for review”.'},
   {ok:questionsReady,label:'7. Every active question is approved and valid',detail:questionApprovalDetail},
   {ok:chapterReady,label:'8. Parent chapter is published',detail:chapterReadinessDetail}
  ];
  const preparationReady=lessonStatus!=='ARCHIVED'&&basicsReady&&blocksReady&&sourceReady&&activeQuestions.length>0;
  const reviewStageReady=submittedForReview&&questionsReady;
  const publishedStageReady=lessonStatus==='PUBLISHED'&&chapterReady;
  const workflowSteps=[
   {label:lessonStatus==='ARCHIVED'?'Restore':'Prepare',ok:preparationReady},
   {label:'Preview',ok:previewReviewed},
   {label:'Review',ok:reviewStageReady},
   {label:'Published',ok:publishedStageReady}
  ];
  const currentWorkflowStep=workflowSteps.findIndex(step=>!step.ok);
  const canPublishLessonReady=basicsReady&&blocksReady&&previewReviewed&&submittedForReview&&questionsReady&&sourceReady&&chapterReady;
  const canPublishThisContent=selected?.type==='chapter'?chapterSourceReady:canPublishLessonReady;
  const firstMissingRequirement=selected?.type==='chapter'
    ? (chapterSourceReady?'The chapter meets its visible checks. The server will also validate the child micro-topics.':(canReview?'Complete and verify the official curriculum source before publishing.':'Complete the official curriculum source details; a reviewer must verify them before publication.'))
    : (!parentChapterAvailable?chapterReadinessDetail:(readiness.find(item=>!item.ok)?.detail||'All eight publishing checks are complete.'));
 return <main className="admin-shell qe-content-shell">
  <AdminSidebar active="content" variant="content" />
  <section className="admin-main qe-content-main" id="top">
   <header className={'admin-top qe-studio-top '+(isEditorView?'qe-editor-page-top':'')}><div><span className="admin-kicker">{t('admin.contentStudio.headerKicker')}</span><h1>{t('admin.contentStudio.pageTitle')} <span className="admin-title-spark">✦</span></h1><p>{t('admin.contentStudio.pageIntro')}</p></div><div className="admin-top-actions">{canCreateCourse?<Link href="/content/create" className="qe-secondary-button">{t('admin.institutionOperations.createCourse')}</Link>:null}{canCreateCourse?<Link href="/content/assessment-course" className="qe-secondary-button">Assessment course</Link>:null}{canCreateCourse?<Link href="/content/import" className="qe-secondary-button">{t('admin.institutionOperations.importCourse')}</Link>:null}{canManageCourseRelease&&programFilter?<Link href={'/institution-operations?view=courses&courseId='+encodeURIComponent(programFilter)} className="qe-secondary-button">{t('admin.institutionOperations.handoffToHrms')}</Link>:null}<Link href="/structured-lesson-preview" className="qe-secondary-button" >{t('admin.contentStudio.structuredPreview')}</Link><span className="top-status" role="status" aria-live="polite"><span className="status-dot" aria-hidden="true"/>{loading?t('admin.contentStudio.librarySyncing'):canView?t('admin.contentStudio.connectedToApi'):t('admin.contentStudio.checkingStaffAccess')}</span><button type="button" className="admin-refresh" onClick={()=>void load()} aria-label={t('admin.contentStudio.refreshContent')} title={t('admin.contentStudio.refreshContent')}>↻</button></div></header>
   <nav className="admin-mobile-nav" aria-label={t('admin.contentStudio.adminNavigation')}><Link href="/">{t('admin.contentStudio.overview')}</Link><Link className="active" href="/content">{t('admin.contentStudio.pageTitle')}</Link><Link href="/structured-lesson-preview" >{t('admin.contentStudio.structuredPreview')}</Link><Link href="/students">{t('admin.contentStudio.students')}</Link><Link href="/parents">{t('admin.contentStudio.families')}</Link></nav>
   
   {error&&<div className="admin-alert qe-feedback" role="alert"><strong>{t('admin.contentStudio.needsAttention')}</strong><span>{error}</span><button type="button" onClick={()=>setError('')}>{t('admin.contentStudio.dismiss')}</button></div>}{notice&&<div className="admin-notice qe-feedback" role="status"><strong>{t('admin.contentStudio.workspaceUpdate')}</strong><span>{notice}</span><button type="button" onClick={()=>setNotice('')}>{t('admin.contentStudio.dismiss')}</button></div>}
   {isAssessmentCourse ? (
    <section className="qe-library-panel" id="content-library">
     <div className="qe-section-head">
      <div>
       <span className="admin-kicker">ASSESSMENT COURSE</span>
       <h2>Tests, test packs and questions</h2>
       <p>This course uses its own assessment hierarchy, not school chapters or micro-topics. Use the course-scoped Assessments workspace to inspect tests and review question answers.</p>
      </div>
     </div>
     <div className="qe-filters">
      <label>{t('admin.contentStudio.course')}
       <select value={programFilter} onChange={e=>{const value=e.target.value;setProgramFilter(value);setClassFilter('ALL');setSubjectFilter('ALL');setSelected(null);setDetail(null);setCreateType(null);const url=new URL(window.location.href);url.searchParams.set('courseId',value);window.history.replaceState({},'',url.toString());void load(value)}}>
        {programs.map((p:any)=><option value={String(p.id||p.course_id)} key={p.id||p.course_id}>{p.display_name} · {p.curriculum_version} · content v{p.content_version_no||1} · {p.course_relationship==='SOURCED'?'Sourced':'Shared (read-only)'}</option>)}
       </select>
      </label>
     </div>
     {activeProgram ? (
      <div role="status" style={{margin:'0 0 14px',padding:'12px 14px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:10,background:'var(--admin-surface,#fff)',color:'var(--admin-text,#1b2430)'}}>
       <strong>{activeProgram.course_relationship==='SOURCED'?'SOURCED ASSESSMENT COURSE':'SHARED ASSESSMENT COURSE · READ ONLY'}</strong>
       <p style={{margin:'5px 0',fontSize:13,color:'var(--admin-muted,#64748b)'}}>
        {activeProgram.course_relationship==='SOURCED'&&isEditableDraft
          ? 'This institution owns the source content. Correct test questions and answer keys through the existing course-scoped assessment editor.'
          : 'This release cannot be edited in place. Content changes must use the source-owned draft/version lifecycle and return through HRMS review.'}
       </p>
       <small>Content version v{activeProgram.content_version_no||1} · Release v{activeProgram.release_version||1} · {activeProgram.release_state||'UNKNOWN'} · Source institution ID {activeProgram.source_institution_id}</small>
      </div>
     ) : null}
     <div className="qe-assessment-course-library">
      <p style={{margin:'0 0 12px',fontSize:13,color:'var(--admin-muted,#667085)'}}>Course → test packs → assessments/tests → ordered question snapshots. Each source answer must pass human review; saving reviewed answers does not itself publish a test.</p>
      <div style={{display:'flex',gap:8,flexWrap:'wrap',marginBottom:12}}>
       <span className="qe-status-pill">{assessmentRows.length} tests</span>
       <span className="qe-status-pill">{assessmentRows.reduce((sum,row)=>sum+Number(row.question_count||0),0)} question snapshots</span>
       <span className="qe-status-pill">{assessmentRows.filter(row=>String(row.status).toUpperCase()==='DRAFT').length} draft tests</span>
      </div>
      {loading ? <div className="qe-library-empty" role="status"><strong>Loading assessment inventory…</strong></div>
       : assessmentRows.length===0 ? <div className="qe-library-empty" role="status"><strong>No assessments are recorded for this course.</strong><p>Refresh the course list or inspect the draft import. Do not create K–12 chapters to make an assessment-only course appear.</p><Link href={'/assessments?courseId='+encodeURIComponent(programFilter)} className="qe-primary-button">Open assessment workspace</Link></div>
       : <div className="qe-curriculum-map">{assessmentRows.map(row=>{
         const imported=Boolean(row.source_package_id);
         const canReviewImported=imported&&canReview;
         const href='/assessments?courseId='+encodeURIComponent(programFilter)+(canReviewImported?'&assessmentId='+encodeURIComponent(String(row.assessment_id)):'');
         return <article className="qe-chapter-group" key={row.assessment_id}>
          <div className="qe-chapter-heading" style={{alignItems:'center'}}>
           <div className="qe-chapter-row" style={{flex:1,cursor:'default'}}>
            <span className="qe-chapter-glyph">✓</span>
            <span className="qe-chapter-title"><small>{row.test_pack_title||row.source_exam_cycle||row.source_package_id||row.assessment_type||'ASSESSMENT'}{row.test_pack_version?' · Pack v'+row.test_pack_version:''}{row.test_id?' · '+row.test_id:''}</small><strong>{row.title}</strong><em>{row.description||'Course-level test. Questions stay attached to the source test and are never mapped into school chapters.'}</em></span>
            <span className="qe-chapter-count">{Number(row.question_count||0)} questions <small>{Number(row.max_score||0)} marks · {Number(row.duration_minutes||0)} min</small></span>
            <span className={'qe-status-pill '+String(row.status||'DRAFT').toLowerCase()}>{statusLabel(row.status)}{row.test_pack_status?' · pack '+String(row.test_pack_status).toLowerCase():''}</span>
           </div>
          </div>
          <div style={{display:'flex',justifyContent:'flex-end',gap:8,flexWrap:'wrap',padding:'10px 14px',borderTop:'1px solid var(--admin-border,#e2e7ef)'}}>
           <Link href={href} className="qe-secondary-button">{canReviewImported?'Review questions and answers':'Open assessment workspace'}</Link>
          </div>
         </article>;
       })}</div>}
      {activeProgram?.course_relationship==='SOURCED'&&releaseState==='REJECTED'
       ? <p style={{margin:'12px 0 0',fontSize:12,color:'var(--admin-muted,#667085)'}}>This release was returned for correction. Finish human answer review, publish every test and pack, then submit a fresh release through Institution Operations for HRMS quality review.</p>
       : null}
     </div>
    </section>
   ) : (
   <>
   <section className="qe-stats-grid">
    <article><span className="qe-stat-icon purple">▦</span><div><small>{t('admin.contentStudio.courseSubjects')}</small><strong>{subjectOptions.length||'—'}</strong><span>{t('admin.contentStudio.databaseRecords')}</span></div></article>
    <article><span className="qe-stat-icon blue">◇</span><div><small>{t('admin.contentStudio.chaptersTopics')}</small><strong>{uniqueChapters}</strong><span>{t('admin.contentStudio.publishedUnpublished')}</span></div></article>
    <article><span className="qe-stat-icon green">▤</span><div><small>{t('admin.contentStudio.publishedLessons')}</small><strong>{publishedLessons}</strong><span>{t('admin.contentStudio.availableToEnrolled')}</span></div></article>
    <article><span className="qe-stat-icon amber">✎</span><div><small>{t('admin.contentStudio.lessonsNeedingAuthoring')}</small><strong>{draftLessons}</strong><span>{t('admin.contentStudio.draftOrReviewTopics')}</span></div></article>
    <article><span className="qe-stat-icon amber">✓</span><div><small>{t('admin.contentStudio.questionsAwaitingReview')}</small><strong>{activeQuestionsAwaitingReview}</strong><span>{t('admin.contentStudio.activeQuestionCounts',{drafts:activeQuestionDrafts,rejected:activeRejectedQuestions})}</span></div></article>
   </section>
   <section className="qe-library-panel" id="content-library">
    <div className="qe-section-head"><div><span className="admin-kicker">{t('admin.contentStudio.curriculumMap')}</span><h2>{t('admin.contentStudio.findChapterOrTopic')}</h2><p>{t('admin.contentStudio.openEditorHint')}</p></div><div className="qe-create-actions"><button type="button" className="qe-secondary-button" disabled={loading||!canCreate} title={!canCreate?t('admin.contentStudio.noCreatePermission'):undefined} onClick={()=>beginCreate('chapter')} >＋ {t('admin.contentStudio.newChapter')}</button><button type="button" className="qe-primary-button" disabled={loading||!canCreate||!rows.some(r=>r.chapter_id!==null)} title={!canCreate?t('admin.contentStudio.noCreatePermission'):undefined} onClick={()=>beginCreate('lesson')} >＋ {t('admin.contentStudio.newMicroTopic')}</button></div></div>
    {activeProgram?<div role="status" style={{margin:'0 0 14px',padding:'12px 14px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:10,background:'var(--admin-surface,#fff)',color:'var(--admin-text,#1b2430)'}}>
      <strong>{activeProgram.course_relationship==='SOURCED'?'SOURCED COURSE':'SHARED COURSE · READ ONLY'}</strong>
      <div style={{marginTop:4,fontSize:13,color:'var(--admin-muted,#64748b)'}}>{activeProgram.course_relationship==='SOURCED'
        ? (isEditableDraft
          ? 'This institution owns the source content. You may edit this draft according to your staff permissions and the normal content-review workflow.'
          : 'This version is locked because it has entered release review or publication. Its content, assessments and resource links cannot be edited in place. Create a new draft version; the existing approved version remains unchanged until HRMS approves the update.')
        : 'This course belongs to another source institution. You may inspect its content, but cannot create, edit, review or publish it from this institution. Content updates must be made by its source institution as a new version and re-approved through the full HRMS Marketplace stepper before distribution.'}</div>
      <small style={{display:'block',marginTop:4}}>Content version v{activeProgram.content_version_no||1} · Release v{activeProgram.release_version||1} · {activeProgram.release_state||'UNKNOWN'} · Source institution ID {activeProgram.source_institution_id}</small>
      {canForkVersion?<div style={{display:'flex',gap:10,flexWrap:'wrap',marginTop:10,alignItems:'center'}}>
       <button type="button" className="qe-secondary-button" disabled={versionBusy||saving} onClick={()=>void createDraftVersion()}>{versionBusy?'Creating draft version…':'Create draft version from published course'}</button>
       <small>Edits will be isolated in a new version. Nothing is pushed to other institutions before HRMS approval.</small>
      </div>:null}
    </div>:null}
    <div className="qe-filters"><label>{t('admin.contentStudio.course')}<select value={programFilter} onChange={e=>{const value=e.target.value;setProgramFilter(value);setClassFilter('ALL');setSubjectFilter('ALL');setSelected(null);setDetail(null);setCreateType(null);const url=new URL(window.location.href);url.searchParams.set('courseId',value);window.history.replaceState({},'',url.toString());void load(value)}}>{programs.map((p:any)=><option value={String(p.id||p.course_id)} key={p.id||p.course_id}>{p.display_name} · {p.curriculum_version} · content v{p.content_version_no||1} · {p.course_relationship==='SOURCED'?'Sourced':'Shared (read-only)'}</option>)}</select></label><label>{t('admin.contentStudio.subject')}<select value={subjectFilter} onChange={e=>setSubjectFilter(e.target.value)}><option value="ALL">{t('admin.contentStudio.allSubjects')}</option>{subjectOptions.map(([code,name])=><option key={code} value={code}>{name}</option>)}</select></label><label>{t('admin.contentStudio.status')}<select value={statusFilter} onChange={e=>setStatusFilter(e.target.value)}><option value="ALL">{t('admin.contentStudio.allStatuses')}</option><option value="PUBLISHED">{t('admin.contentStudio.published')}</option><option value="DRAFT">{t('admin.contentStudio.draft')}</option><option value="REVIEW">{t('admin.contentStudio.inReview')}</option><option value="ARCHIVED">{t('admin.contentStudio.archived')}</option></select></label><label className="qe-search-field"><span>{t('admin.contentStudio.search')}</span><input value={search} onChange={e=>setSearch(e.target.value)} placeholder={t('admin.contentStudio.searchPlaceholder')}/></label></div>
    <div className="qe-curriculum-map">
     {loading&&rows.length===0?<div className="qe-library-empty"><span className="qe-empty-symbol">◌</span><strong>{t('admin.contentStudio.connectingCurriculum')}</strong><p>{t('admin.contentStudio.loadingContentRecords')}</p></div>:groups.length===0?<div className="qe-library-empty"><span className="qe-empty-symbol">⌕</span><strong>{t('admin.contentStudio.noMatchingContent')}</strong><p>{t('admin.contentStudio.adjustFilters')}</p></div>:pagedGroups.map(group=>{
      const chapter=group.chapter;
      const totalLessons=group.rows.filter(r=>r.lesson_id!==null).length;
      const visibleLessons=group.lessons.length?group.lessons:statusFilter==='ALL'?group.rows.filter(r=>r.lesson_id!==null):[];
      const publishedCount=group.rows.filter(r=>r.lesson_id!==null&&r.lesson_status==='PUBLISHED').length;
      const expanded=expandedChapters.has(group.id);
      return <article className="qe-chapter-group" key={group.id}>
       <div className="qe-chapter-heading">
        <button type="button" className={'qe-chapter-row '+(selected?.type==='chapter'&&selected.id===group.id?'selected':'')} aria-expanded={expanded} aria-controls={'chapter-topics-'+group.id} aria-label={expanded?t('admin.contentStudio.hideMicroTopics'):t('admin.contentStudio.showMicroTopics')} onClick={()=>setExpandedChapters(current=>{const next=new Set(current);if(next.has(group.id))next.delete(group.id);else next.add(group.id);return next;})}>
         <span className="qe-chapter-glyph">▤</span>
         <span className="qe-chapter-title"><small>{programs.find((p:any)=>String(p.id||p.course_id)===String(programFilter))?.display_name||chapter.program_name||t('admin.contentStudio.course')} <i>·</i> {chapter.subject_name}</small><strong>{chapter.chapter_name||t('admin.contentStudio.untitledChapter')}</strong><em>{chapter.chapter_description||t('admin.contentStudio.chapterDescriptionMissing')}</em></span>
         <span className="qe-chapter-count">{t('admin.contentStudio.topicCount',{count:totalLessons})} <small>{t('admin.contentStudio.publishedCount',{count:publishedCount})}</small></span>
         <span className={'qe-status-pill '+String(chapter.chapter_status||'DRAFT').toLowerCase()}>{statusLabel(chapter.chapter_status)}</span>
         <span className="qe-row-arrow" aria-hidden="true">{expanded?'⌄':'›'}</span>
        </button>
        <button type="button" className="qe-chapter-edit-button" onClick={()=>void open({type:'chapter',id:group.id})}>{t('admin.contentStudio.chapterEditor')}</button>
       </div>
       {expanded&&<div className="qe-chapter-topics" id={'chapter-topics-'+group.id}>
        {visibleLessons.map(lesson=><button type="button" id={'content-topic-'+lesson.lesson_id} key={lesson.lesson_id} className={'qe-topic-row '+(selected?.type==='lesson'&&selected.id===lesson.lesson_id?'selected':'')} onClick={()=>void open({type:'lesson',id:Number(lesson.lesson_id)})}>
         <span className="qe-topic-stem" aria-hidden="true">↳</span><span className="qe-topic-icon" aria-hidden="true">✎</span>
         <span className="qe-topic-title"><strong>{lesson.lesson_title||'Untitled micro-topic'}</strong><small>{lesson.estimated_minutes||10} min <i>·</i> {lesson.block_count||0} blocks <i>·</i> {lesson.question_count||0} questions</small></span>
         <span className={'qe-status-pill '+String(lesson.lesson_status||'DRAFT').toLowerCase()}>{statusLabel(lesson.lesson_status)}</span><span className="qe-row-arrow" aria-hidden="true">→</span>
        </button>)}
        {!totalLessons&&<div className="qe-topic-empty">{t('admin.contentStudio.noMicroTopics')} {t('admin.contentStudio.createFirstTopic')}</div>}
       </div>}
      </article>;
     })}</div><PremiumPagination page={chapterPage} total={groups.length} pageSize={chapterPageSize} onPageChange={setChapterPage} onPageSizeChange={size=>{setChapterPageSize(size);setChapterPage(0);}} itemLabel={t('admin.contentStudio.chaptersPaginationLabel')} />
    <div className="qe-library-foot"><span><b>{t('admin.contentStudio.librarySummary',{chapters:uniqueChapters,lessons:uniqueLessons,published:publishedChapters})}</b></span><span>{t('admin.contentStudio.teachingOrderHint')}</span></div>
   </section>
   </>
   )}
   {editorOpen&&(createType||selected)&&<section role="dialog" aria-modal="true" aria-label={createType?(createType==='chapter'?t('admin.contentStudio.createChapterTitle'):t('admin.contentStudio.createMicroTopicTitle')):selected?.type==='chapter'?t('admin.contentStudio.chapterEditor'):t('admin.contentStudio.microTopicEditor')} tabIndex={-1} className={'qe-workspace '+(isEditorView?'qe-workspace-fullscreen':'')} id="editor" ref={workspaceRef}>
    <div className="qe-fullscreen-bar">
     <div><strong>{createType?(createType==='chapter'?t('admin.contentStudio.createChapterTitle'):t('admin.contentStudio.createMicroTopicTitle')):selected?.type==='chapter'?t('admin.contentStudio.chapterEditor'):t('admin.contentStudio.microTopicEditor')}</strong><p>{selected&&detail?t('admin.contentStudio.workspaceSelectedContext',{classCode:detail.class_code,subject:detail.subject_name,chapter:selected.type==='chapter'?detail.chapter_name:detail.chapter_name+' / '+(form.title||detail.lesson_title)}):t('admin.contentStudio.workspaceIntro')}</p></div>
     <div className="qe-fullscreen-actions"><span className={'qe-edit-state '+(hasUnsavedChanges?'dirty':'')}>{createType?(createHasInput?t('admin.contentStudio.unsavedDraftDetails'):t('admin.contentStudio.newDraft')):hasUnsavedChanges?t('admin.contentStudio.unsavedChanges'):t('admin.contentStudio.noUnsavedChanges')}</span><button type="button" className="qe-secondary-button" onClick={backToLibrary} disabled={saving}>{t('admin.contentStudio.backToLibrary')}</button></div>
    </div>
    {error&&<div className="qe-fullscreen-message error" role="alert"><b>Needs attention</b><span>{error}</span></div>}
    {notice&&<div className="qe-fullscreen-message notice" role="status"><b>Update</b><span>{notice}</span></div>}
    {!canEdit&&!createType&&<div className="qe-permission-message"><span>i</span><div><b>{canReview?t('admin.contentStudio.reviewOnlyAccess'):t('admin.contentStudio.readOnlyAccess')}</b><p>{canReview?t('admin.contentStudio.reviewOnlyDescription'):t('admin.contentStudio.readOnlyDescription')}</p></div></div>}
    {createType?<div className="qe-edit-card qe-create-card"><div className="qe-editor-top"><div><span className="admin-kicker">{t('admin.contentStudio.newContentKicker')}</span><h2>{createType==='chapter'?'Create a chapter':'Create a micro-topic'}</h2><p>{t('admin.contentStudio.draftVisibilityHint')}</p></div><button type="button" className="qe-icon-button" onClick={backToLibrary} aria-label={t('admin.contentStudio.closeEditor')}>×</button></div>
     {createType==='chapter'?<><div className="qe-form-grid"><label>{t('admin.contentStudio.subject')}<select value={createForm.subjectCode} onChange={e=>setCreateForm({...createForm,subjectCode:e.target.value})}>{subjectOptions.map(([code,name])=><option value={code} key={code}>{name}</option>)}</select></label></div><label className="qe-form-field">{t('admin.contentStudio.chapterTitle')}<input maxLength={160} value={createForm.displayName} onChange={e=>setCreateForm({...createForm,displayName:e.target.value})} placeholder={t('admin.contentStudio.chapterTitlePlaceholder')}/></label><label className="qe-form-field">{t('admin.contentStudio.shortDescription')}<textarea rows={3} maxLength={500} value={createForm.description} onChange={e=>setCreateForm({...createForm,description:e.target.value})}/></label><label className="qe-form-field">{t('admin.contentStudio.chapterCode')}<input maxLength={60} value={createForm.code} onChange={e=>setCreateForm({...createForm,code:e.target.value.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'')} )} placeholder={t('admin.contentStudio.chapterCodePlaceholder')}/></label><div className="qe-form-grid"><label>{t('admin.contentStudio.officialCurriculumSource')}<input value={createForm.curriculumSource} onChange={e=>setCreateForm({...createForm,curriculumSource:e.target.value})}/></label><label>{t('admin.contentStudio.officialSourceUrl')}<input type="url" value={createForm.curriculumSourceUrl} onChange={e=>setCreateForm({...createForm,curriculumSourceUrl:e.target.value})} placeholder={t('admin.contentStudio.httpsPlaceholder')}/></label><label>{t('admin.contentStudio.editionSession')}<input value={createForm.curriculumSourceEdition} onChange={e=>setCreateForm({...createForm,curriculumSourceEdition:e.target.value})}/></label><label>{t('admin.contentStudio.pageRange')}<input value={createForm.curriculumSourcePages} onChange={e=>setCreateForm({...createForm,curriculumSourcePages:e.target.value})}/></label><label>{t('admin.contentStudio.teachingOrder')}<input type="number" min={1} value={createForm.sortOrder} onChange={e=>setCreateForm({...createForm,sortOrder:Number(e.target.value)})}/></label></div></>:<><label className="qe-form-field">{t('admin.contentStudio.parentChapter')}<select value={createForm.chapterId} onChange={e=>setCreateForm({...createForm,chapterId:Number(e.target.value)})}><option value={0}>{t('admin.contentStudio.chooseChapter')}</option>{Array.from(new Map(rows.filter(r=>r.chapter_id!==null).map(r=>[Number(r.chapter_id),r])).entries()).map(([id,r])=><option key={id} value={id}>Class {r.class_code} · {r.subject_name} · {r.chapter_name}</option>)}</select></label><label className="qe-form-field">{t('admin.contentStudio.microTopicTitle')}<input maxLength={240} value={createForm.title} onChange={e=>setCreateForm({...createForm,title:e.target.value})} placeholder={t('admin.contentStudio.microTopicTitlePlaceholder')}/></label><label className="qe-form-field">{t('admin.contentStudio.learnerSummary')}<textarea rows={3} maxLength={800} value={createForm.summary} onChange={e=>setCreateForm({...createForm,summary:e.target.value})}/></label><div className="qe-form-grid"><label>{t('admin.contentStudio.topicCode')}<input maxLength={100} value={createForm.code} onChange={e=>setCreateForm({...createForm,code:e.target.value.toLowerCase().replace(/[^a-z0-9]+/g,'-').replace(/^-|-$/g,'')} )} placeholder={t('admin.contentStudio.topicCodePlaceholder')}/></label><label>{t('admin.contentStudio.estimatedTime')}<input type="number" min={1} max={120} value={createForm.estimatedMinutes} onChange={e=>setCreateForm({...createForm,estimatedMinutes:Number(e.target.value)})}/></label><label>{t('admin.contentStudio.officialTextbookGuide')}<input value={createForm.alignmentSourceTitle} onChange={e=>setCreateForm({...createForm,alignmentSourceTitle:e.target.value})}/></label><label>{t('admin.contentStudio.sourceUrl')}<input type="url" value={createForm.alignmentSourceUrl} onChange={e=>setCreateForm({...createForm,alignmentSourceUrl:e.target.value})}/></label><label>{t('admin.contentStudio.editionSession')}<input value={createForm.alignmentSourceEdition} onChange={e=>setCreateForm({...createForm,alignmentSourceEdition:e.target.value})}/></label><label>{t('admin.contentStudio.pageRange')}<input value={createForm.alignmentPageRange} onChange={e=>setCreateForm({...createForm,alignmentPageRange:e.target.value})}/></label><label>{t('admin.contentStudio.teachingOrder')}<input type="number" min={1} value={createForm.sortOrder} onChange={e=>setCreateForm({...createForm,sortOrder:Number(e.target.value)})}/></label></div></>}
     <div className="qe-editor-actions"><button type="button" className="qe-primary-button" disabled={saving||!canCreate||!(createType==='chapter'?createForm.displayName.trim()&&createForm.code.trim():createForm.title.trim()&&createForm.code.trim()&&Number(createForm.chapterId)>0)} onClick={()=>void create()}>{saving?t('admin.contentStudio.creating'):t('admin.contentStudio.createDraft',{kind:t(createType==='chapter'?'admin.contentStudio.chapter':'admin.contentStudio.microTopic')})}</button><button type="button" className="qe-secondary-button" onClick={backToLibrary}>{t('admin.contentStudio.cancel')}</button></div>
    </div>:!detail?<div className="qe-edit-card qe-library-empty"><span className="qe-empty-symbol">◌</span><strong>{t('admin.contentStudio.openingEditor')}</strong><p>{t('admin.contentStudio.loadingContentDetails')}</p></div>:selected?.type==='chapter'?<div className={'qe-edit-card '+(!canEdit?'qe-viewer-mode':'')}>
     <div className="qe-editor-top"><div><span className="admin-kicker">CHAPTER SETTINGS</span><h2>{detail.chapter_name}</h2><p>Class {detail.class_code} <i>·</i> {detail.subject_name} <i>·</i> chapter-level curriculum details</p></div><span className={'qe-status-pill '+String(form.chapterStatus).toLowerCase()}>{form.chapterStatus}</span></div>
     <div className="qe-editor-section"><div><h3>Learning structure</h3><p>Use a clear chapter name and explain the intended learning outcome.</p></div><label className="qe-form-field">{t('admin.contentStudio.chapterTitle')}<input maxLength={160} value={form.displayName} onChange={e=>setForm({...form,displayName:e.target.value})}/></label><label className="qe-form-field">Chapter description<textarea rows={3} maxLength={500} value={form.description} onChange={e=>setForm({...form,description:e.target.value})}/></label><div className="qe-form-grid"><label>{t('admin.contentStudio.teachingOrder')}<input type="number" min={1} max={10000} value={form.chapterSortOrder} onChange={e=>setForm({...form,chapterSortOrder:Number(e.target.value)})}/></label></div></div>
     <div className="qe-editor-section"><div><h3>Curriculum source and accuracy</h3><p>Keep an exact source edition and page range so topics remain aligned with the right curriculum.</p></div><label className="qe-form-field">Official curriculum source title<input value={form.curriculumSource} onChange={e=>setForm({...form,curriculumSource:e.target.value})}/></label><label className="qe-form-field">Official HTTPS source URL<input type="url" value={form.curriculumSourceUrl} onChange={e=>setForm({...form,curriculumSourceUrl:e.target.value})}/></label><div className="qe-form-grid"><label>{t('admin.contentStudio.editionSession')}<input value={form.curriculumSourceEdition} onChange={e=>setForm({...form,curriculumSourceEdition:e.target.value})}/></label><label>Official page range<input value={form.curriculumSourcePages} onChange={e=>setForm({...form,curriculumSourcePages:e.target.value})}/></label><div className="qe-source-verification-panel"><span>Source verification</span><b>{chapterSourceDetailsChanged?'Needs re-verification':form.curriculumSourceVerified?'Verified against source':'Not verified'}</b>{!form.curriculumSourceVerified&&canReview?<button type="button" disabled={saving||hasUnsavedChanges||!chapterSourceDetailsComplete} onClick={()=>void verifyChapterSource()}>Verify source</button>:null}{!canReview&&(!form.curriculumSourceVerified||chapterSourceDetailsChanged)?<small>A content reviewer must verify this source before publication.</small>:null}</div></div></div>
     <div className="qe-readiness-summary"><div><strong>{chapterSourceReady&&chapterReadyToPublish?'Chapter requirements complete':'Chapter requirements incomplete'}</strong><p>{!chapterSourceReady?(canReview?'Add the official source title, HTTPS URL, edition and exact page range, then verify it against the source.':'Complete the official source title, HTTPS URL, edition and exact page range; a reviewer must verify it.'):chapterReadyToPublish?publishReadyLessonCount+' micro-topic(s) pass the current server publication checks. Topics that are not ready can remain drafts.':'Complete at least one active micro-topic: real teaching content, verified textbook mapping, valid practice questions and approvals.'}</p></div><span className={'qe-status-pill '+(form.chapterStatus==='PUBLISHED'?'published':'draft')}>{form.chapterStatus}</span></div>
     <div className="qe-editor-actions qe-sticky-actions"><button type="button" className="qe-primary-button" disabled={saving||!canEdit||!hasUnsavedChanges||(['PUBLISHED','ARCHIVED'].includes(String(detail.chapter_status))&&!canPublishPermission)} onClick={()=>void saveChapter()}>{saving?'Saving…':'Save chapter changes'}</button><button type="button" className="qe-secondary-button" disabled={saving||!canPublishPermission||hasUnsavedChanges||(form.chapterStatus!=='PUBLISHED'&&(!chapterSourceReady||!chapterReadyToPublish))} onClick={()=>void setChapterPublication(form.chapterStatus!=='PUBLISHED')}>{saving?'Updating…':form.chapterStatus==='PUBLISHED'?'Unpublish chapter':'Publish chapter'}</button></div>
    </div>:<div className="qe-authoring-layout">
     <section className={'qe-edit-card qe-main-authoring '+(!canEdit?'qe-viewer-mode':'')}>
      <div className="qe-editor-top"><div><span className="admin-kicker">MICRO-TOPIC AUTHORING</span><h2>{form.title||detail.lesson_title}</h2><p>Class {detail.class_code} <i>·</i> {detail.subject_name} <i>·</i> {detail.chapter_name}</p></div><span className={'qe-status-pill '+String(form.lessonStatus).toLowerCase()}>{form.lessonStatus}</span></div>
      <div className="qe-step-strip" aria-label="Publishing workflow">{workflowSteps.map((step,index)=><span key={step.label} className={step.ok?'done':currentWorkflowStep===index?'current':'todo'}><span>{step.ok?'✓':String(index+1).padStart(2,'0')}</span><b>{step.label}</b></span>)}</div>
      <div className="qe-editor-section"><div className="qe-editor-section-title"><div><h3>Topic essentials</h3><p>Use student-friendly language; internal codes only organise content.</p></div></div><label className="qe-form-field">{t('admin.contentStudio.microTopicTitle')}<input maxLength={240} value={form.title} onChange={e=>setForm({...form,title:e.target.value})} placeholder="Give this topic a clear, student-friendly title"/></label><label className="qe-form-field">What will the learner understand?<textarea rows={3} maxLength={800} value={form.summary} onChange={e=>setForm({...form,summary:e.target.value})} placeholder={t('admin.contentStudio.learnerSummaryPlaceholder')}/></label><div className="qe-form-grid"><label>Estimated learning time (minutes)<input type="number" min={1} max={120} value={form.estimatedMinutes} onChange={e=>setForm({...form,estimatedMinutes:Number(e.target.value)})}/></label><label>{t('admin.contentStudio.teachingOrder')}<input type="number" min={1} max={10000} value={form.sortOrder} onChange={e=>setForm({...form,sortOrder:Number(e.target.value)})}/></label><div className="qe-workflow-state"><span>Workflow status</span><b>{form.lessonStatus}</b><small>{form.lessonStatus==='PUBLISHED'?'Visible to eligible learners':'Not published to learners yet'}</small></div></div></div>
      <div className="qe-editor-section qe-visual-library">
       <div className="qe-editor-section-title"><div><h3>Textbook visuals</h3><p>Browse V3 candidates from the whole source book and attach the relevant figure to this micro-topic. Selection never publishes it.</p></div><span className="qe-block-count">{visualCatalog?.summary?.candidate_count??0} candidates</span></div>
       <div className="qe-visual-source-note"><span>Source-bound sample</span><p>{visualCatalog?visualCatalog.source.title+' · Grade '+visualCatalog.source.class+' · '+visualCatalog.source.subject+' · '+visualCatalog.source.language+' · '+visualCatalog.source.edition:visualCatalogLoading?'Loading the matching source manifest…':'No matching source catalog selected yet.'}</p><small>PDF SHA-256: {visualCatalog?.source?.pdf_sha256||'—'} · All candidates remain review-only.</small></div>
       {selected?.type==='lesson'&&!visualCatalogMatchesTarget?<div className="qe-visual-empty">Attach is disabled: this catalog belongs to Grade {visualCatalog?.source.class} {visualCatalog?.source.subject}; the selected lesson is {visualTargetRow?.class_name} {visualTargetRow?.subject_name}. Choose a lesson with the matching class and subject.</div>:null}
       {visualCatalogOptions.length>1?<label className="qe-form-field">Source textbook / edition<select value={visualCatalogOptions.some(item=>item.book_slug===visualCatalogSlug)?visualCatalogSlug:''} onChange={event=>{setVisualCatalogSlug(event.target.value);setVisualCatalog(null);setVisualCatalogError('Loading the selected source textbook…')}}><option value="">Choose the exact source book…</option>{visualCatalogOptions.map(item=><option key={item.book_slug} value={item.book_slug}>{item.title} · Grade {item.class} · {item.subject} · {item.language} · {item.edition}</option>)}</select></label>:null}
       <label className="qe-form-field qe-visual-search">Search candidate pages and context<input value={visualSearch} onChange={e=>setVisualSearch(e.target.value)} placeholder="Search topic, page text, or candidate ID"/></label>
       {visualCatalogLoading?<div className="qe-visual-empty">Loading source-bound visual candidates…</div>:visualCatalogError?<div className="qe-visual-empty">{visualCatalogError}</div>:null}
       {visualCatalog&&(()=>{
        const candidates=visualCatalog.candidates.filter(candidate=>(candidate.candidate_id+' '+candidate.candidate_kind+' '+candidate.source_pdf_page_number+' '+candidate.suggested_chapter_title+' '+candidate.page_text_context).toLocaleLowerCase().includes(visualSearch.trim().toLocaleLowerCase())).slice().sort((left,right)=>visualCandidateRelevanceScore(right,visualTargetRow)-visualCandidateRelevanceScore(left,visualTargetRow));
        return <><div className="qe-visual-library-grid">{candidates.map(candidate=><article className="qe-visual-candidate-card" key={candidate.candidate_id}>
          <a className="qe-visual-thumb" href={candidate.source_page_url||candidate.image_url} target="_blank" rel="noreferrer" aria-label={'Open source page '+candidate.source_pdf_page_number}><img src={candidate.image_url} alt={'Unreviewed visual candidate from PDF page '+candidate.source_pdf_page_number} loading="lazy"/></a>
          <div className="qe-visual-candidate-copy"><span className="qe-visual-candidate-state">{candidate.candidate_kind==='page_preview'?'Source-page context only':'Needs review'} · PDF p. {candidate.source_pdf_page_number}</span><strong>{candidate.candidate_kind==='embedded_image'?'Embedded figure':'Whole-page preview'} · {candidate.candidate_id}</strong>{candidate.suggested_chapter_title?<div className="qe-visual-chapter-suggestion"><b>TOC suggestion: Ch. {candidate.suggested_chapter_number} · {candidate.suggested_chapter_title}</b><small>Verify against the actual source and selected lesson; this mapping is not approved.</small></div>:null}<p>{candidate.page_text_context||'No extracted page context available.'}</p><small>{candidate.source_asset_sha256?'Asset SHA-256 '+candidate.source_asset_sha256.slice(0,16)+'…':'Source page preview'} </small></div>
          <button type="button" className="qe-secondary-button" disabled={!canEdit||saving||!candidate.image_url||candidate.candidate_kind==='page_preview'||!selected||selected.type!=='lesson'||!visualCatalogMatchesTarget} onClick={()=>attachVisualCandidate(candidate)}>{candidate.candidate_kind==='page_preview'?'Context only — not attachable':'＋ Attach as draft visual'}</button>
        </article>)}</div>{candidates.length===0?<div className="qe-visual-empty">No candidates match that search.</div>:null}</>;
       })()}
       <p className="qe-visual-library-footnote">Available visual categories: photo, illustration, diagram, chart/graph, table/comparison, infographic and sequence/process. The V3 candidates are not semantically classified yet; choose the correct category in the block editor after comparing the visual with the source page and concept.</p>
      </div>
      <div className="qe-editor-section qe-teaching-section"><div className="qe-editor-section-title"><div><h3>Teaching sequence</h3><p>Add and reorder short blocks. Use text, worked examples, diagrams, audio or video where they improve understanding.</p></div><span className="qe-block-count">{blocks.length} blocks</span></div>
       <div className="qe-block-workspace"><div className="qe-block-list">{blocks.map((block,i)=><button type="button" key={i} className={'qe-block-list-item '+(activeBlock===i?'selected':'')} onClick={()=>setActiveBlock(i)}><span className="qe-block-index">{String(i+1).padStart(2,'0')}</span><span className="qe-block-list-copy"><b>{BLOCK_OPTIONS.find(x=>x.type===block.block_type)?.label||block.block_type.replaceAll('_',' ')}</b><small>{String(block.content.heading||block.content.title||block.content.body||block.content.description||'Add teaching content').replace(/<[^>]+>/g,' ').slice(0,55)}</small></span><span className="qe-block-status">{block.active===false?'Hidden':'●'}</span></button>)}<button type="button" className="qe-add-block" disabled={!canEdit||saving} onClick={()=>addBlock('EXPLANATION')}>＋ Add text block</button></div>
        <div className="qe-block-editor">{active?<><div className="qe-block-editor-head"><div><span>BLOCK {String(activeBlock+1).padStart(2,'0')}</span><h4>{BLOCK_OPTIONS.find(x=>x.type===active.block_type)?.label||active.block_type}</h4><p>{BLOCK_OPTIONS.find(x=>x.type===active.block_type)?.hint||'Add a clear teaching step.'}</p></div><div className="qe-block-controls"><button type="button" disabled={activeBlock===0} title="Move up" onClick={()=>moveBlock(activeBlock,-1)}>↑</button><button type="button" disabled={activeBlock===blocks.length-1} title="Move down" onClick={()=>moveBlock(activeBlock,1)}>↓</button><button type="button" title="Duplicate block" onClick={()=>duplicateBlock(activeBlock)}>▢</button><button type="button" title="Remove block" onClick={()=>removeBlock(activeBlock)}>×</button></div></div>
         <div className="qe-block-type-picker"><label>Block type<select value={active.block_type} onChange={e=>setBlocks(old=>old.map((b,i)=>i===activeBlock?{...b,block_type:e.target.value,content:contentFor(e.target.value)}:b))}>{BLOCK_OPTIONS.map(option=><option key={option.type} value={option.type}>{option.label}</option>)}</select></label><label className="qe-toggle"><input type="checkbox" checked={active.active!==false} onChange={e=>setBlocks(old=>old.map((b,i)=>i===activeBlock?{...b,active:e.target.checked}:b))}/> Show to learners</label></div>
         {['EXPLANATION','PREREQUISITE'].includes(active.block_type)?<><label className="qe-form-field">Section heading<input value={active.content.heading||''} onChange={e=>updateContent(activeBlock,{heading:e.target.value})} placeholder="A short, meaningful heading"/></label><RichTextEditor key={(selected?.id||0)+'-'+activeBlock+'-'+active.block_type} blockKey={activeBlock} initialHtml={String(active.content.html||htmlFromText(String(active.content.body||'')))} onChange={html=>updateContent(activeBlock,{html,body:html.replace(/<[^>]*>/g,' ').replace(/\s+/g,' ').trim()})}/></>:null}
         {active.block_type==='WORKED_EXAMPLE'?<><label className="qe-form-field">Example heading<input value={active.content.title||''} onChange={e=>updateContent(activeBlock,{title:e.target.value})}/></label><label className="qe-form-field">Problem / question<textarea rows={3} value={active.content.problem||''} onChange={e=>updateContent(activeBlock,{problem:e.target.value})}/></label><label className="qe-form-field">Solution steps <small>One step per line</small><textarea rows={5} value={(active.content.steps||[]).join('\n')} onChange={e=>updateContent(activeBlock,{steps:e.target.value.split('\n')})}/></label><label className="qe-form-field">Final answer<input value={active.content.answer||''} onChange={e=>updateContent(activeBlock,{answer:e.target.value})}/></label></>:null}
         {['IMAGE','DIAGRAM','ANIMATION','VIDEO','AUDIO'].includes(active.block_type)?<><label className="qe-form-field">Media heading (English)<input value={active.content.title||''} onChange={e=>updateContent(activeBlock,{title:e.target.value})}/></label><label className="qe-form-field">{active.content.source_candidate_id?'मीडिया शीर्षक (Hindi, required for publication)':'मीडिया शीर्षक (Hindi, optional)'}<input value={active.content.title_hi||''} onChange={e=>updateContent(activeBlock,{title_hi:e.target.value})}/></label>{['IMAGE','DIAGRAM','ANIMATION'].includes(active.block_type)&&<label className="qe-form-field">Visual category<select value={active.content.visual_kind|| (active.block_type==='DIAGRAM'?'DIAGRAM':active.block_type==='ANIMATION'?'SEQUENCE':'TEXTBOOK_FIGURE')} onChange={e=>updateContent(activeBlock,{visual_kind:e.target.value})}><option value="PHOTO">Photo</option><option value="ILLUSTRATION">Illustration</option><option value="DIAGRAM">Diagram</option><option value="CHART">Chart / graph</option><option value="TABLE">Table / comparison</option><option value="INFOGRAPHIC">Infographic</option><option value="SEQUENCE">Sequence / process</option><option value="TEXTBOOK_FIGURE">Unclassified textbook figure (draft only)</option></select><small>Classify by what the visual teaches, not by the file format. Unclassified textbook figures cannot be published.</small></label>}<label className="qe-form-field">{active.block_type==='AUDIO'?'Audio URL':active.block_type==='VIDEO'?'Video URL (HTTPS)':'Image, diagram or animation URL'}<input type="url" value={active.content.url||''} onChange={e=>updateContent(activeBlock,{url:e.target.value})} placeholder={active.block_type==='VIDEO'?'https://… or a YouTube URL':'https://…'}/><small>Use a stable HTTPS media URL. GIF/WebP or video files can be used for a purposeful animation with a static fallback.</small></label><label className="qe-form-field">What should learners notice? (English)<textarea rows={2} value={active.content.description||''} onChange={e=>updateContent(activeBlock,{description:e.target.value})} placeholder="Explain why this visual or recording matters."/></label><label className="qe-form-field">विद्यार्थी क्या समझें? (Hindi, optional)<textarea rows={2} value={active.content.description_hi||''} onChange={e=>updateContent(activeBlock,{description_hi:e.target.value})} placeholder="इस visual से जुड़ा मुख्य विचार समझाएँ।"/></label>{['IMAGE','DIAGRAM','ANIMATION'].includes(active.block_type)&&<><label className="qe-form-field">Alternative text (English)<textarea rows={2} value={active.content.alt||''} onChange={e=>updateContent(activeBlock,{alt:e.target.value})} placeholder="Describe the important information for screen readers."/></label><label className="qe-form-field">{active.content.source_candidate_id?'वैकल्पिक पाठ (Hindi, required for publication)':'वैकल्पिक पाठ (Hindi, optional)'}<textarea rows={2} value={active.content.alt_hi||''} onChange={e=>updateContent(activeBlock,{alt_hi:e.target.value})} placeholder="चित्र में क्या दिखता है, यह लिखें।"/></label></> }<label className="qe-form-field">Caption (English, optional)<input value={active.content.caption||''} onChange={e=>updateContent(activeBlock,{caption:e.target.value})}/></label><label className="qe-form-field">{active.content.source_candidate_id?'Caption (Hindi, required when English caption is filled)':'चित्र का विवरण (Hindi, optional)'}<input value={active.content.caption_hi||''} onChange={e=>updateContent(activeBlock,{caption_hi:e.target.value})}/></label>{active.content.source_candidate_id?<div className="qe-visual-provenance"><div><span>Source provenance</span><b>{active.content.source_book_title||active.content.source_book_id} · PDF page {active.content.source_pdf_page_number}</b><small>Source PDF SHA-256 {String(active.content.source_pdf_sha256||'').slice(0,20)}…</small><small>Source asset SHA-256 {String(active.content.source_asset_sha256||'').slice(0,20)}…</small>{active.content.source_page_context?<p>{active.content.source_page_context}</p>:null}</div><div className="qe-visual-review-fields"><label>Visual review<select disabled={!canReview||saving} value={active.content.visual_review_status||'NEEDS_REVIEW'} onChange={e=>updateContent(activeBlock,{visual_review_status:e.target.value})}><option value="NEEDS_REVIEW">Needs review</option><option value="APPROVED">Approved</option><option value="REJECTED">Rejected</option></select></label><label>Rights decision<select disabled={!canReview||saving} value={active.content.rights_status||'SOURCE_REFERENCE_ONLY'} onChange={e=>updateContent(activeBlock,{rights_status:e.target.value})}><option value="SOURCE_REFERENCE_ONLY">Reference only — not cleared for publication</option><option value="LICENSE_CONFIRMED">Publication rights confirmed</option></select></label><small>{canReview?'Review the actual figure, the text context, accessibility text and publication rights before approving.':'Only a content reviewer can approve a visual or confirm publication rights.'}</small></div></div>:null}<div className="qe-inline-media-preview"><span>LEARNER VISUAL PREVIEW</span><MediaElement type={active.block_type} content={active.content}/>{active.content.source_candidate_id?<p className="qe-preview-caption">PDF page {active.content.source_pdf_page_number} · {active.content.visual_review_status||'NEEDS_REVIEW'} · {active.content.rights_status||'SOURCE_REFERENCE_ONLY'}</p>:null}</div></>:null}
         {['SUMMARY','RECAP'].includes(active.block_type)?<label className="qe-form-field">Key takeaways <small>One idea per line</small><textarea rows={6} value={(active.content.points||[]).join('\n')} onChange={e=>updateContent(activeBlock,{points:e.target.value.split('\n')})} placeholder={'Explain the core idea\nShow the important pattern\nConnect it to a real example'}/></label>:null}
         {['GUIDED_PRACTICE','INDEPENDENT_PRACTICE','CHALLENGE'].includes(active.block_type)?<><label className="qe-form-field">Activity heading<input value={active.content.title||''} onChange={e=>updateContent(activeBlock,{title:e.target.value})}/></label><label className="qe-form-field">Instructions / prompt<textarea rows={4} value={active.content.prompt||''} onChange={e=>updateContent(activeBlock,{prompt:e.target.value})}/></label><label className="qe-form-field">Hint (optional)<textarea rows={2} value={active.content.hint||''} onChange={e=>updateContent(activeBlock,{hint:e.target.value})}/></label></>:null}
         {active.block_type==='HINT'?<><label className="qe-form-field">Hint heading<input value={active.content.title||''} onChange={e=>updateContent(activeBlock,{title:e.target.value})}/></label><label className="qe-form-field">Hint text<textarea rows={4} value={active.content.body||''} onChange={e=>updateContent(activeBlock,{body:e.target.value})}/></label></>:null}
         {active.block_type==='AI_HELP'?<div className="qe-editor-hint">The AI tutor entry is rendered in the student app. Add it where a learner benefits from contextual help.</div>:null}
        </>:<div className="qe-library-empty">Choose a block to edit.</div>}</div></div>
       <div className="qe-block-catalog"><div><span className="admin-kicker">ADD TO TEACHING SEQUENCE</span><p>Pick the content format that best clarifies this micro-topic.</p></div><div className="qe-block-catalog-grid">{BLOCK_OPTIONS.map(option=><button type="button" key={option.type} disabled={!canEdit||saving} onClick={()=>addBlock(option.type)}><span>{option.icon}</span><b>{option.label}</b><small>{option.hint}</small></button>)}</div></div>
      </div>
      <div className="qe-editor-section qe-assessment-section"><div className="qe-editor-section-title"><div><h3>Practice and understanding</h3><p>Keep answer keys accurate. All review and publishing restrictions remain validated by the API.</p></div><button type="button" className="qe-secondary-button" disabled={!canEdit||saving} onClick={addQuestion}>＋ Add MCQ</button></div>
       {questions.length===0?<div className="qe-empty-questions"><span>✦</span><b>No practice questions yet</b><p>Add a question to check that the learner understood the concept.</p><button type="button" className="qe-secondary-button" disabled={!canEdit||saving} onClick={addQuestion}>Create first question</button></div>:questions.map((q:any,index:number)=><article className="qe-question-author" key={q.id||'new-'+index}><header><span className="qe-question-num">Q{index+1}</span><div><strong>{q.prompt||'Untitled question'}</strong><small>{q.question_type} <i>·</i> {q.review_status||'DRAFT'} <i>·</i> {q.active===false?'INACTIVE':'ACTIVE'} <i>·</i> {q.difficulty||'CORE'}</small></div><button type="button" className={'qe-question-activate '+(q.active===false?'is-inactive':'')} disabled={!canEdit||saving} onClick={()=>updateQuestion(index,{active:q.active===false})}>{q.active===false?'Activate':'Deactivate'}</button><button type="button" className="qe-question-remove" title="Remove question" disabled={!canEdit||saving} onClick={()=>{const next=questions.filter((_,i)=>i!==index);setQuestions(next);setQuestionJson(JSON.stringify(next,null,2))}}>×</button></header>{q.active===false?<div className="qe-question-diagnostic inactive"><strong>Not counted for publishing</strong><span>Activate this question to include it in the learning experience, then save your changes.</span></div>:questionReadinessIssues(q).length>0?<div className="qe-question-diagnostic"><strong>{t('admin.contentStudio.needsAttention')}</strong><span>{questionReadinessIssues(q).join(' ')}</span></div>:<div className="qe-question-diagnostic ready"><strong>Question checks passed</strong><span>This active question has an approved status and a valid answer key.</span></div>}
        {q.question_type==='MCQ'||q.question_type==='TRUE_FALSE'?<><label className="qe-form-field">Question prompt<textarea rows={2} value={q.prompt||''} onChange={e=>updateQuestion(index,{prompt:e.target.value})}/></label><div className="qe-options-list">{(q.options||[]).map((option:any,oi:number)=><label key={option.key||oi}><input type="radio" name={'correct-'+index} checked={Boolean(option.correct)} disabled={!canEdit||saving} onChange={()=>{const options=(q.options||[]).map((o:any,j:number)=>({...o,correct:j===oi}));updateQuestion(index,{options,answer_payload:{kind:'OPTION',value:option.key}})}}/><input value={option.label||''} onChange={e=>updateOption(index,oi,{label:e.target.value})} aria-label={'Answer option '+(oi+1)}/><span>{option.key}</span></label>)}</div><label className="qe-form-field">Explanation after answering<textarea rows={2} value={q.explanation||''} onChange={e=>updateQuestion(index,{explanation:e.target.value})}/></label><div className="qe-form-grid"><label>Question difficulty<select disabled={!canEdit||saving} value={q.difficulty||'CORE'} onChange={e=>updateQuestion(index,{difficulty:e.target.value})}><option value="FOUNDATION">Foundation</option><option value="CORE">Core</option><option value="CHALLENGE">Challenge</option></select></label><div className="qe-question-workflow-state"><span>Review state</span><b>{String(q.review_status||'DRAFT').replaceAll('_',' ')}</b><small>{['APPROVED','PUBLISHED'].includes(String(q.review_status||'').toUpperCase())?'Reviewed content is currently approved':'A reviewer must approve this question before publishing'}</small></div></div>
        {canReview&&q.id&&['REVIEW','PUBLISHED'].includes(String(form.lessonStatus||'').toUpperCase())?<div className="qe-question-review">
          <small>{hasUnsavedChanges?'Save pending changes before review.':form.lessonStatus==='PUBLISHED'?'Question review does not unpublish this live topic.':'Review each question separately after the topic is submitted.'}</small>
          {String(q.review_status||'DRAFT').toUpperCase()==='REVIEW'?<>
            <button type="button" disabled={saving||hasUnsavedChanges||!meaningfulContent(q.prompt)} onClick={()=>void reviewQuestion(Number(q.id),'APPROVED')}>Approve question</button>
            <button type="button" disabled={saving||hasUnsavedChanges||!meaningfulContent(q.prompt)} onClick={()=>void reviewQuestion(Number(q.id),'REJECTED')}>Return to author</button>
          </>:<button type="button" className="qe-question-submit-review" disabled={saving||hasUnsavedChanges||!meaningfulContent(q.prompt)} onClick={()=>void reviewQuestion(Number(q.id),'REVIEW')}>Send to review</button>}
        </div>:canReview&&!q.id?<p className="qe-staff-help">Save this new question before a reviewer can review it.</p>:null}</>:<div className="qe-editor-hint">This existing {q.question_type} question is preserved. Use the advanced assessment editor below to edit specialist question formats.</div>}
       </article>)}
       <button type="button" className="qe-advanced-toggle" disabled={!canEdit||saving} onClick={()=>{setQuestionJson(JSON.stringify(questions,null,2));setShowAdvancedQuestions(v=>!v)}}>{showAdvancedQuestions?'Hide advanced question data':'Open advanced assessment editor (all question types)'} <span>{showAdvancedQuestions?'−':'＋'}</span></button>
       {showAdvancedQuestions&&<div className="qe-advanced-question"><p>Specialist question formats retain their full record. The API validates all saved fields.</p><textarea spellCheck={false} rows={14} value={questionJson} onChange={e=>setQuestionJson(e.target.value)}/><button type="button" className="qe-secondary-button" disabled={!canEdit||saving} onClick={applyQuestionJson}>Apply advanced data</button></div>}
      </div>
      <div className="qe-editor-section"><div><h3>Source and publishing</h3><p>Record the exact source edition and page range before publishing academic content.</p></div><label className="qe-form-field">Official source title<input value={form.alignmentSourceTitle} onChange={e=>setForm({...form,alignmentSourceTitle:e.target.value})}/></label><label className="qe-form-field">Official HTTPS URL<input type="url" value={form.alignmentSourceUrl} onChange={e=>setForm({...form,alignmentSourceUrl:e.target.value})}/></label><div className="qe-form-grid"><label>Edition / session<input value={form.alignmentSourceEdition} onChange={e=>setForm({...form,alignmentSourceEdition:e.target.value})}/></label><label>Official page range<input value={form.alignmentPageRange} onChange={e=>setForm({...form,alignmentPageRange:e.target.value})}/></label><div className="qe-source-verification-panel"><span>Verification</span><b>{lessonSourceDetailsChanged?'Needs re-verification':form.alignmentSourceVerified?'Verified against source':'Not verified'}</b>{!form.alignmentSourceVerified&&canReview?<button type="button" disabled={saving||hasUnsavedChanges||!lessonSourceDetailsComplete} onClick={()=>void verifyLessonSource()}>Verify source</button>:null}{!canReview&&(!form.alignmentSourceVerified||lessonSourceDetailsChanged)?<small>A content reviewer must verify this source before publication.</small>:null}</div></div><div className="qe-readiness-summary"><div><strong>{lessonStatus==='PUBLISHED'?'Micro-topic is published':canPublishLessonReady?'Ready to publish':(readiness.filter(item=>item.ok).length)+' of '+readiness.length+' checks complete'}</strong><p>{hasUnsavedChanges?'Save changes first. Editing a submitted or published topic returns it to draft and resets its preview confirmation.':lessonStatus==='PUBLISHED'?'This topic is live. Any future content edit must be saved, previewed and reviewed again.':'Next action: '+firstMissingRequirement}</p></div><span className={'qe-status-pill '+(canPublishLessonReady?'published':'draft')}>{lessonStatus==='PUBLISHED'?'Published':canPublishLessonReady?'Ready':'In progress'}</span></div><div className="qe-readiness-list">{readiness.map(item=><div key={item.label} className={item.ok?'ready':'todo'}><span>{item.ok?'✓':'○'}</span><div><b>{item.label}</b><small>{item.detail}</small></div></div>)}</div></div>
      <div className="qe-editor-actions qe-sticky-actions"><button type="button" className="qe-primary-button" disabled={saving||!canEdit||!hasUnsavedChanges||(['PUBLISHED','ARCHIVED'].includes(String(form.lessonStatus))&&!canPublishPermission)} onClick={()=>void saveLesson()}>{saving?'Saving…':hasUnsavedChanges?'Save changes':'No changes to save'}</button><button type="button" className="qe-secondary-button" disabled={saving||!canSubmit||!canSubmitLessonForReview||hasUnsavedChanges||['REVIEW','PUBLISHED','ARCHIVED'].includes(String(form.lessonStatus))} title={!canSubmitLessonForReview?(!parentChapterAvailable?'Restore or activate the parent chapter before submitting. It may remain unpublished until the topic is ready to go live.':'Complete steps 1–5 first: topic details, teaching content, verified textbook mapping, at least one active question, and a checked saved preview.'):undefined} onClick={()=>void submitLessonForReview()}>{form.lessonStatus==='REVIEW'?'In review':'Submit for review'}</button><button type="button" className="qe-publish-button" disabled={saving||!canPublishPermission||hasUnsavedChanges||(lessonStatus!=='PUBLISHED'&&lessonStatus!=='ARCHIVED'&&!canPublishThisContent)} title={lessonStatus==='ARCHIVED'?'Restore this topic to draft, then preview and submit it for review again.':undefined} onClick={()=>void setLessonPublication(lessonStatus!=='PUBLISHED'&&lessonStatus!=='ARCHIVED')}>{saving?'Updating…':lessonStatus==='PUBLISHED'?'Unpublish micro-topic':lessonStatus==='ARCHIVED'?'Restore to draft':'Publish micro-topic ↗'}</button></div>
      <p className="qe-editor-footnote">{hasUnsavedChanges?'Unsaved changes are not yet saved. Save them before reviewing or publishing.':!canPublishPermission?'You do not have publishing permission. The publication action is disabled.':canPublishLessonReady?'All visible requirements are complete; the API will perform final publication validation.':'Publishing is disabled until each requirement above is complete.'} The API remains authoritative for final validation.</p>
     </section>
     {showPreview&&<aside className="qe-preview-column"><div className="qe-preview-head"><div><span className="admin-kicker">LEARNER PREVIEW</span><h3>Review the saved lesson</h3><p>{hasUnsavedChanges?'Save pending changes before confirming this preview.':'Confirmation is recorded against the current saved content version.'}</p></div><button type="button" className="qe-icon-button" onClick={()=>setShowPreview(false)} title="Hide preview">×</button></div><div className="qe-preview-mode"><button type="button" className={previewMode==='desktop'?'active':''} onClick={()=>setPreviewMode('desktop')}>▭ Desktop</button><button type="button" className={previewMode==='phone'?'active':''} onClick={()=>setPreviewMode('phone')}>▯ Phone</button><button type="button" className={previewLanguage==='en'?'active':''} onClick={()=>setPreviewLanguage('en')}>English</button><button type="button" className={previewLanguage==='hi'?'active':''} onClick={()=>setPreviewLanguage('hi')}>हिन्दी</button><button type="button" className="qe-mark-preview-button" disabled={saving||!showPreview||hasUnsavedChanges||!preparationReady||!canSubmit||previewReviewed||!(Number(detail?.content_revision)>0)} title={hasUnsavedChanges?'Save the current changes before confirming the preview.':!preparationReady?'Complete steps 1–4 before confirming the preview.':!canSubmit?'You need submit permission to record this check.':undefined} onClick={()=>void markPreviewReviewed()}>{saving?'Saving check…':previewReviewed?'✓ Saved version checked':'Confirm saved version'}</button></div><div className={'qe-device-frame '+previewMode}><div className="qe-device-screen"><header className="qe-student-topbar"><span className="qe-student-brand"><b>Q</b> QuantaEdge</span><span>Class {detail.class_code}</span></header><div className="qe-preview-lesson"><div className="qe-preview-breadcrumb">कक्षा {detail.class_code} · {detail.subject_name} · {detail.chapter_name}</div><div className="qe-preview-time">◷ {form.estimatedMinutes||10} min lesson <span>·</span> Preview</div><h2>{form.title||'Untitled micro-topic'}</h2><p className="qe-preview-intro">{form.summary||'A short, student-friendly explanation of what this topic will teach.'}</p><div className="qe-preview-path"><b>Learning path</b><span>{learnerPathLabel(previewBlocks,learnerVisibleMcqs.length)}</span></div>
      {previewTeachingBlocks.map((block,i)=><LearnerBlock key={block.sequence_no+'-'+i} block={block} language={previewLanguage}/>)}
      <section className="qe-preview-practice">
       <span className="qe-student-kicker">ASSESSMENT</span>
       <h3>Check your understanding with MCQs</h3>
       {learnerVisibleMcqs.map((q:any,i:number)=><div className="qe-preview-question" key={q.id||i}><p>{q.prompt||'Your question will appear here.'}</p>{(q.options||[]).length>0?(q.options||[]).map((o:any)=><div key={o.key} className="qe-preview-option">{o.key}. {o.label||'Answer choice'}</div>):<div className="qe-preview-option">Answer options are not available for this question.</div>}</div>)}
       {learnerVisibleMcqs.length===0&&<p>No MCQs have been published for this lesson yet. Learners can still open the approved topic practice set.</p>}
       {otherApprovedPracticeQuestionCount>0&&<p>{otherApprovedPracticeQuestionCount} other approved question(s) appear in topic practice, not in the inline MCQ check.</p>}
      </section>
      <section className="qe-preview-practice qe-preview-topic-practice">
       <span className="qe-student-kicker">NEXT STEP</span>
       <h3>Now practise this micro-topic</h3>
       <p>QuantaEdge selects approved questions mapped to this lesson's learning objective and concept. Learners do not need to choose topics or question types.</p>
       <div className="qe-preview-option">Start topic practice →</div>
      </section>
      {previewRecapBlocks.map((block,i)=><LearnerBlock key={block.sequence_no+'-recap-'+i} block={block} language={previewLanguage}/>)}<footer className="qe-preview-footer">QuantaEdge Learning <span>Learn · Practise · Progress</span></footer></div></div></div><div className="qe-preview-disclaimer"><span>ⓘ</span><p>Preview shows active non-recap blocks in learner order, inline active approved MCQs, the topic-practice step, then every active recap block. {pendingQuestionReviewCount>0?pendingQuestionReviewCount+' active question(s) are still awaiting approval and will not be shown to students. ':''}{unsupportedActiveBlocks.length>0?unsupportedActiveBlocks.length+' active legacy block(s) are hidden because the student screen cannot render them. ':''}Final visibility still requires the published chapter, verified source and server-side access checks.</p></div></aside>}
     {!showPreview&&<button type="button" className="qe-show-preview" onClick={()=>setShowPreview(true)}>▣ Show learner preview</button>}
    </div>}
   </section>}
   <footer className="admin-footer qe-admin-footer">QuantaEdge Academic Console <span>Source-aware authoring · role-secured APIs · student preview</span></footer>
  </section>
 </main>;
}
