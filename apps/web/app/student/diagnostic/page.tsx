'use client';

import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
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
        if(!response.ok)throw new Error(body.detail||body.message||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.unable.to.load.diagnostic.f6a469cb'));
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
          if(!submitted.ok)throw new Error(submitBody.detail||submitBody.message||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.unable.to.submit.diagnostic.ec9da1e7'));
          if(live)router.replace('/student');
        }
      }catch(e:any){if(live)setError(e?.message||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.unable.to.load.diagnostic.f6a469cb'));}
      finally{if(live)setLoading(false);}
    }
    void load();
    return()=>{live=false;};
  },[router,locale]);

  const q=qs[idx];
  const shownResult=result || (q?.answered&&typeof q.lastCorrect==='boolean'?{correct:q.lastCorrect,feedback:translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.this.answer.was.checked.earlier.f93c0c7f')}:null);
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
      if(!response.ok)throw new Error(body.detail||body.message||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.could.not.save.this.answer.f5a71de8'));
      setValue(v);setResult(body);
      setQs(old=>old.map(item=>item.id===q.id?{...item,answered:true,lastCorrect:body.correct,selectedAnswer:v}:item));
    }catch(e:any){setError(e?.message||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.answer.could.not.be.submitted.05e16fa5'));}
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
      if(!response.ok)throw new Error(body.detail||body.message||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.unable.to.submit.diagnostic.ec9da1e7'));
      router.replace('/student');
    }catch(e:any){setError(e?.message||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.unable.to.submit.diagnostic.ec9da1e7'));}
    finally{setSubmitting(false);}
  }

  if(loading)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><UiText message="legacy.web.loading.your.saved.diagnostic.b2912d85" /></div></section></main>;
  if(error&&!q)return <main className="practice-page"><section className="practice-wrap"><div className="auth-card"><h1><UiText message="legacy.web.unable.to.load.diagnostic.f6a469cb" /></h1><p>{error}</p><button type="button" className="button button-dark" onClick={()=>window.location.reload()}><UiText message="legacy.web.try.again.c3572b4c" /></button><Link href="/student" className="text-link"><UiText message="legacy.web.back.97062f29" /></Link></div></section></main>;
  if(unavailable||!q)return <main className="practice-page"><section className="practice-wrap"><div className="eyebrow"><UiText message="legacy.web.initial.diagnostic.6c007aef" /></div><h1><UiText message="legacy.web.diagnostic.is.not.available.yet.33d8f4ec" /></h1><p><UiText message="legacy.web.the.diagnostic.will.appear.when.enough.04469512" /></p><Link href="/student" className="button button-dark"><UiText message="legacy.web.student.home.15fbe96d" /></Link></section></main>;

  return <main className="practice-page">
    <header className="lesson-header"><Link href="/student" className="back"><UiText message="legacy.web.today.1a73c870" /></Link><span><UiText message="legacy.web.initial.diagnostic.a2e9a619" /> {idx+1} / {qs.length}</span><span className="avatar">अ</span></header>
    <section className="practice-wrap">
      <div className="eyebrow"><UiText message="legacy.web.your.saved.diagnostic.b83de56d" /></div>
      <h1>{q.prompt}</h1>
      <p><UiText message="legacy.web.this.short.assessment.helps.create.you.4f499f99" /></p>
      <div className="option-grid">{options.map(o=>{
        const chosen=value===o.key;
        const outcome=chosen?shownResult?.correct:null;
        const outcomeClass=outcome===true?'correct':outcome===false?'incorrect':chosen?'selected':'';
        return <button type="button" key={o.key} className={outcomeClass} disabled={!!result||Boolean(q.answered)||saving||submitting} onClick={()=>void answer(o.key)}>{o.key}. {o.label}{outcome===true?' ✓':outcome===false?' !':''}</button>;
      })}</div>
      {error&&<p role="alert" className="feedback feedback-incorrect">{error}</p>}
      {shownResult&&<AnswerFeedback
        outcome={shownResult.correct===true?'correct':shownResult.correct===false?'incorrect':'ungraded'}
        title={shownResult.correct===true?<UiText message="legacy.web.correct.420829f6" />:shownResult.correct===false?<UiText message="legacy.web.incorrect.answer.72e67de0" />:<UiText message="legacy.web.answer.saved.a1a333eb" />}
        message={shownResult.feedback||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.this.answer.was.saved.earlier.39c393b2')}
        explanation={q.explanation||undefined}
        explanationLabel={<UiText message="legacy.web.explanation.162bf3ec" />}
      />}
      {result&&<div className="practice-footer"><span>{idx+1} / {qs.length}</span><button type="button" className="button button-dark" disabled={submitting} onClick={()=>void next()}>{submitting?<UiText message="legacy.web.submitting.91654fbd" />:idx+1<qs.length?<UiText message="legacy.web.next.046e02fa" />:<UiText message="legacy.web.finish.diagnostic.08e35ac6" />}</button></div>}
    </section>
  </main>;
}
