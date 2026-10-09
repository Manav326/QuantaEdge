'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';

type Lesson={id:number;title:string;code:string;chapter_name:string;subject_name:string};
type Question={id:number;question_type:string;prompt:string;explanation:string;options:string};
type Option={key:string;label:string};
type GradeResult={questionId:number;correct:boolean;explanation:string|null;saved:boolean};
function parse(v:unknown){if(typeof v!=='string')return v??[];try{return JSON.parse(v)}catch{return[]}}

export default function PracticePage(){
  const [questions,setQuestions]=useState<Question[]>([]);
  const [lesson,setLesson]=useState<Lesson|null>(null);
  const [classCode,setClassCode]=useState('7');
  const [subjectCode,setSubjectCode]=useState('maths');
  const [index,setIndex]=useState(0);
  const [selected,setSelected]=useState<string|null>(null);
  const [grade,setGrade]=useState<GradeResult|null>(null);
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);
  const [submitting,setSubmitting]=useState(false);

  useEffect(()=>{
    let cancelled=false;
    async function load(){
      try{
        const params=new URLSearchParams(window.location.search);
        let cls=params.get('classCode')||'',subject=params.get('subjectCode')||'';
        if(!cls||!subject){
          const p=await fetch('/api/v1/students/preview',{cache:'no-store'});
          if(!p.ok)throw new Error('Student profile load नहीं हो पाया।');
          const profile=await p.json();
          cls=cls||String(profile.class_code||'7');
          subject=subject||'maths';
        }
        if(!['6','7','8'].includes(cls)||!['maths','science'].includes(subject))throw new Error('कक्षा या विषय सही नहीं है।');
        const lr=await fetch('/api/v1/learning/lessons?classCode='+encodeURIComponent(cls)+'&subjectCode='+encodeURIComponent(subject),{cache:'no-store'});
        if(!lr.ok)throw new Error('Published lessons load नहीं हो पाए।');
        const list=await lr.json() as Lesson[];
        const requested=params.get('lessonId');
        const ordered=requested?list.filter(x=>String(x.id)===requested).concat(list.filter(x=>String(x.id)!==requested)):list;
        let picked:Lesson|null=null,qs:Question[]=[];
        for(const item of ordered){
          const qr=await fetch('/api/v1/learning/lessons/'+item.id+'/questions',{cache:'no-store'});
          if(!qr.ok)continue;
          const candidate=await qr.json() as Question[];
          const usable=candidate.filter(q=>(parse(q.options) as Option[]).length>0);
          if(usable.length){picked=item;qs=usable;break}
        }
        if(cancelled)return;
        setClassCode(cls);setSubjectCode(subject);setLesson(picked);setQuestions(qs);setIndex(0);setSelected(null);setGrade(null);
      }catch(e){if(!cancelled)setError(e instanceof Error?e.message:'Practice load नहीं हो पाया。')}
      finally{if(!cancelled)setLoading(false)}
    }
    void load();return()=>{cancelled=true}
  },[]);

  async function submitAnswer(optionKey:string){
    if(!questions[index]||submitting||grade)return;
    setSelected(optionKey);setError('');setSubmitting(true);
    try{
      const response=await fetch('/api/v1/learning/questions/'+questions[index].id+'/answer',{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({selectedOption:optionKey})
      });
      if(!response.ok){
        const body=await response.json().catch(()=>({}));
        throw new Error(body.message||'उत्तर जाँचा नहीं जा सका। कृपया फिर कोशिश करें।');
      }
      const result=await response.json() as GradeResult;
      setGrade(result);
    }catch(e){
      setSelected(null);
      setError(e instanceof Error?e.message:'उत्तर जाँचा नहीं जा सका।');
    }finally{setSubmitting(false)}
  }

  function nextQuestion(){
    setIndex(index+1);setSelected(null);setGrade(null);setError('');
  }

  if(loading)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow">अभ्यास load हो रहा है…</div></section></main>;
  if(error&&!questions.length)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1>अभ्यास उपलब्ध नहीं है</h1><p>{error}</p><Link href="/student" className="button button-dark">← Student home</Link></div></section></main>;
  if(!questions.length||!lesson)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1>अभी अभ्यास उपलब्ध नहीं है</h1><p>इस विषय में कोई published multiple-choice practice question नहीं मिला।</p><Link href={'/student/learn?classCode='+encodeURIComponent(classCode)+'&subjectCode='+encodeURIComponent(subjectCode)} className="button button-dark">← विषय पर लौटें</Link></div></section></main>;

  const q=questions[index],options=parse(q.options) as Option[],answered=grade!==null;
  return <main className="practice-page"><header className="lesson-header"><Link href={'/student/learn?classCode='+encodeURIComponent(classCode)+'&subjectCode='+encodeURIComponent(subjectCode)} className="back">← {lesson.subject_name||subjectCode}</Link><span>अभ्यास · {index+1} / {questions.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap"><div className="eyebrow">कक्षा {classCode} · {lesson.subject_name||subjectCode} · {lesson.chapter_name}</div><h1>{q.prompt}</h1><p>पहले सोचो, फिर विकल्प चुनो और feedback देखो।</p>
      <div className="option-grid">{options.map(o=><button type="button" key={o.key} disabled={answered||submitting} className={selected===o.key?'selected':''} onClick={()=>void submitAnswer(o.key)}>{o.label}</button>)}</div>
      {submitting&&<div className="practice-feedback" role="status"><span>उत्तर जाँचा जा रहा है…</span></div>}
      {error&&<div className="track-alert" role="alert">{error}</div>}
      {answered&&<div className="practice-feedback"><strong>{grade.correct?'✓ सही जवाब':'अभी नहीं'}</strong><span>{grade.explanation||q.explanation}</span></div>}
      <div className="practice-footer"><span>Question {index+1} of {questions.length}</span>{answered&&index+1<questions.length?<button className="button button-dark" onClick={nextQuestion}>अगला सवाल →</button>:answered?<Link href={'/student/learn?classCode='+encodeURIComponent(classCode)+'&subjectCode='+encodeURIComponent(subjectCode)+'&lessonId='+lesson.id} className="button button-dark">Lesson पर लौटें →</Link>:<span>Option चुनें</span>}</div>
    </section>
  </main>;
}
