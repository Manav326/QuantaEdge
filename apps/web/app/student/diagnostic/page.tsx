'use client';

import { LocaleText, useLocale } from '../../components/LanguageProvider';
import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';
import AnswerFeedback from '../../components/AnswerFeedback';

type Q={id:number;question_type:string;prompt:string;explanation?:string;options:string;answered?:boolean;lastCorrect?:boolean|null;selectedAnswer?:any};
type DiagnosticPayload={completed?:boolean;available?:boolean;attemptId?:number;status?:string;questionCount?:number;questions?:Q[];message?:string;detail?:string};

async function readJson(response:Response):Promise<any>{
  const raw=await response.text();
  if(!raw.trim())return {};
  try{return JSON.parse(raw)}catch{return {message:raw}}
}

export default function DiagnosticPage(){
  const router=useRouter();
  const {locale}=useLocale();
  const tx=(hinglish:string,english:string)=>locale==='english'?english:hinglish;
  const [qs,setQs]=useState<Q[]>([]);
  const [idx,setIdx]=useState(0);
  const [value,setValue]=useState<any>('');
  const [result,setResult]=useState<any>(null);
  const [attemptId,setAttemptId]=useState<number|null>(null);
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);
  const [saving,setSaving]=useState(false);
  const [submitting,setSubmitting]=useState(false);
  const [unavailable,setUnavailable]=useState(false);

  useEffect(()=>{
    let live=true;
    async function load(){
      setLoading(true);setError('');
      try{
        const response=await fetch('/api/v1/diagnostic',{cache:'no-store'});
        if(response.status===401||response.status===403){router.replace('/login/student');return;}
        const body:DiagnosticPayload=await readJson(response);
        if(!response.ok)throw new Error(body.detail||body.message||tx('Diagnostic load नहीं हो पाया','Unable to load diagnostic'));
        if(body.completed){router.replace('/student');return;}
        const questions=Array.isArray(body.questions)?body.questions:[];
        if(!live)return;
        setAttemptId(Number(body.attemptId)||null);
        setQs(questions);
        setUnavailable(body.available===false||questions.length===0);
        const firstUnanswered=questions.findIndex(q=>!q.answered);
        setIdx(firstUnanswered<0?Math.max(0,questions.length-1):firstUnanswered);
        if(firstUnanswered>=0)setValue(questions[firstUnanswered].selectedAnswer??'');
        if(firstUnanswered<0&&questions.length&&body.attemptId){
          const submitted=await fetch('/api/v1/diagnostic/attempts/'+body.attemptId+'/submit',{method:'POST',cache:'no-store'});
          const submitBody=await readJson(submitted);
          if(!submitted.ok)throw new Error(submitBody.detail||submitBody.message||tx('Diagnostic submit नहीं हो पाया','Unable to submit diagnostic'));
          if(live)router.replace('/student');
        }
      }catch(e:any){if(live)setError(e?.message||tx('Diagnostic load नहीं हो पाया','Unable to load diagnostic'));}
      finally{if(live)setLoading(false);}
    }
    void load();
    return()=>{live=false;};
  },[router,locale]);

  const q=qs[idx];
  const shownResult=result || (q?.answered&&typeof q.lastCorrect==='boolean'?{correct:q.lastCorrect,feedback:tx('यह उत्तर पहले जाँचा गया था।','This answer was checked earlier.')}:null);
  let options:any[]=[];
  try{options=JSON.parse(q?.options||'[]')}catch{}

  async function answer(v:any){
    if(!q||!attemptId||saving||result)return;
    setSaving(true);setError('');
    try{
      const response=await fetch('/api/v1/diagnostic/attempts/'+attemptId+'/answers/'+q.id,{
        method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({answer:v})
      });
      const body=await readJson(response);
      if(response.status===401||response.status===403){router.replace('/login/student');return;}
      if(!response.ok)throw new Error(body.detail||body.message||tx('Answer save नहीं हुआ','Could not save this answer'));
      setValue(v);setResult(body);
      setQs(old=>old.map(item=>item.id===q.id?{...item,answered:true,lastCorrect:body.correct,selectedAnswer:v}:item));
    }catch(e:any){setError(e?.message||tx('Answer submit नहीं हो पाया','Answer could not be submitted'));}
    finally{setSaving(false);}
  }

  async function next(){
    if(!result||!attemptId||submitting)return;
    const nextIndex=qs.findIndex((item,i)=>i>idx&&!item.answered);
    if(nextIndex>=0){
      setIdx(nextIndex);setValue(qs[nextIndex].selectedAnswer??'');setResult(null);setError('');return;
    }
    setSubmitting(true);setError('');
    try{
      const response=await fetch('/api/v1/diagnostic/attempts/'+attemptId+'/submit',{method:'POST'});
      const body=await readJson(response);
      if(!response.ok)throw new Error(body.detail||body.message||tx('Diagnostic submit नहीं हो पाया','Unable to submit diagnostic'));
      router.replace('/student');
    }catch(e:any){setError(e?.message||tx('Diagnostic submit नहीं हो पाया','Unable to submit diagnostic'));}
    finally{setSubmitting(false);}
  }

  if(loading)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><LocaleText hinglish="आपका saved diagnostic खोल रहे हैं…" english="Loading your saved diagnostic…" /></div></section></main>;
  if(error&&!q)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1><LocaleText hinglish="Diagnostic load नहीं हो पाया" english="Unable to load diagnostic" /></h1><p>{error}</p><button type="button" className="button button-dark" onClick={()=>window.location.reload()}><LocaleText hinglish="दोबारा कोशिश करें" english="Try again" /></button><Link href="/student" className="text-link"><LocaleText hinglish="← वापस" english="← Back" /></Link></div></section></main>;
  if(unavailable||!q)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><LocaleText hinglish="INITIAL DIAGNOSTIC" english="INITIAL DIAGNOSTIC" /></div><h1><LocaleText hinglish="Diagnostic अभी उपलब्ध नहीं है" english="Diagnostic is not available yet" /></h1><p><LocaleText hinglish="आपकी class और enrolled subjects के लिए पर्याप्त approved questions उपलब्ध होने पर diagnostic दिखाई देगा। आपकी progress सुरक्षित है।" english="The diagnostic will appear when enough approved questions are available for your class and enrolled subjects. Your progress is preserved." /></p><Link href="/student" className="button button-dark"><LocaleText hinglish="Student home →" english="Student home →" /></Link></section></main>;

  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back"><LocaleText hinglish="← आज" english="← Today" /></Link><span><LocaleText hinglish="Initial diagnostic ·" english="Initial diagnostic ·" /> {idx+1} / {qs.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow"><LocaleText hinglish="आपका saved diagnostic" english="Your saved diagnostic" /></div>
      <h1>{q.prompt}</h1>
      <p><LocaleText hinglish="यह छोटा assessment आपकी शुरुआती learning recommendation बनाने में मदद करता है। आप बाद में लौटेंगे तो उत्तर सुरक्षित रहेंगे।" english="This short assessment helps create your starting learning recommendation. Your saved answers will remain available if you return later." /></p>
      <div className="option-grid">{options.map(o=>{
        const chosen=value===o.key;
        const outcome=chosen?shownResult?.correct:null;
        const outcomeClass=outcome===true?'correct':outcome===false?'incorrect':chosen?'selected':'';
        return <button type="button" key={o.key} className={outcomeClass} disabled={!!result||Boolean(q.answered)||saving||submitting} onClick={()=>void answer(o.key)}>{o.key}. {o.label}{outcome===true?' ✓':outcome===false?' !':''}</button>;
      })}</div>
      {error&&<p role="alert" className="feedback feedback-incorrect">{error}</p>}
      {shownResult&&<AnswerFeedback
        outcome={shownResult.correct===true?'correct':shownResult.correct===false?'incorrect':'ungraded'}
        title={shownResult.correct===true?<LocaleText hinglish="✓ सही" english="✓ Correct" />:shownResult.correct===false?<LocaleText hinglish="उत्तर गलत है" english="Incorrect answer" />:<LocaleText hinglish="उत्तर save हो गया" english="Answer saved" />}
        message={shownResult.feedback||tx('यह उत्तर पहले save किया गया था।','This answer was saved earlier.')}
        explanation={q.explanation||undefined}
        explanationLabel={<LocaleText hinglish="समझें क्यों" english="Explanation" />}
      />}
      {result&&<div className="practice-footer"><span>{idx+1} / {qs.length}</span><button type="button" className="button button-dark" disabled={submitting} onClick={()=>void next()}>{submitting?<LocaleText hinglish="Submit कर रहे हैं…" english="Submitting…" />:idx+1<qs.length?<LocaleText hinglish="अगला →" english="Next →" />:<LocaleText hinglish="Diagnostic पूरा करें →" english="Finish diagnostic →" />}</button></div>}
    </section>
  </main>;
}
