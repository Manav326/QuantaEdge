'use client';

import { useEffect, useRef, useState } from 'react';

type TutorMessage={id?:number;role:'USER'|'ASSISTANT';mode?:string;message:string};
type Mode='HINT'|'EXPLAIN'|'EXAMPLE'|'STEP_BY_STEP'|'CHECK_MY_WORK';
const labels:Record<'hi'|'en',Record<Mode,string>>={
  hi:{HINT:'संकेत',EXPLAIN:'समझाएँ',EXAMPLE:'उदाहरण',STEP_BY_STEP:'चरण-दर-चरण',CHECK_MY_WORK:'मेरी जाँच करें'},
  en:{HINT:'Hint',EXPLAIN:'Explain',EXAMPLE:'Example',STEP_BY_STEP:'Step by step',CHECK_MY_WORK:'Check my work'}
};

export default function TutorDock({lessonId,open,onClose,currentQuestionId,language}:{lessonId:number;open:boolean;onClose:()=>void;currentQuestionId?:number;language:'hi'|'en'}){
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [available,setAvailable]=useState<boolean|null>(null);
  const [messages,setMessages]=useState<TutorMessage[]>([]);
  const [hasOlderMessages,setHasOlderMessages]=useState(false);
  const [olderBeforeId,setOlderBeforeId]=useState<number|null>(null);
  const [loadingOlder,setLoadingOlder]=useState(false);
  const preserveScrollRef=useRef(false);
  const [mode,setMode]=useState<Mode>('HINT');
  const [input,setInput]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const endRef=useRef<HTMLDivElement|null>(null);

  useEffect(()=>{
    if(!open)return;
    let cancelled=false;
    setSessionId(null);
    setAvailable(null);
    setMessages([]);
    setHasOlderMessages(false);
    setOlderBeforeId(null);
    setError('');
    (async()=>{
      try{
        const r=await fetch('/api/v1/tutor/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({lessonId,language})});
        const b=await r.json();
        if(cancelled)return;
        if(!r.ok){setAvailable(false);setError(b.message||'Tutor unavailable');return;}
        setAvailable(!!b.available);setSessionId(Number(b.sessionId));
        if(b.available){
          const mr=await fetch('/api/v1/tutor/sessions/'+b.sessionId+'/messages?limit=30');
          if(mr.ok){const history=await mr.json();setMessages(Array.isArray(history.items)?history.items:[]);setHasOlderMessages(Boolean(history.hasMore));setOlderBeforeId(history.nextBeforeId==null?null:Number(history.nextBeforeId));}
        }
      }catch(e:any){if(!cancelled){setAvailable(false);setError(e.message||'Tutor unavailable')}}
    })();
    return()=>{cancelled=true};
  },[open,lessonId]);

  useEffect(()=>{if(preserveScrollRef.current){preserveScrollRef.current=false;return;}endRef.current?.scrollIntoView({behavior:'smooth'})},[messages,busy]);

  async function loadOlderMessages(){
    if(!sessionId||!hasOlderMessages||olderBeforeId===null||loadingOlder)return;
    setLoadingOlder(true);setError('');
    try{
      const response=await fetch('/api/v1/tutor/sessions/'+sessionId+'/messages?limit=30&beforeId='+olderBeforeId);
      const data=await response.json();
      if(!response.ok)throw new Error(data.message||'Older tutor messages could not be loaded.');
      const older=Array.isArray(data.items)?data.items:[];
      preserveScrollRef.current=true;
      setMessages(current=>[...older,...current]);
      setHasOlderMessages(Boolean(data.hasMore));
      setOlderBeforeId(data.nextBeforeId==null?null:Number(data.nextBeforeId));
    }catch(e:any){setError(e.message||'Older tutor messages could not be loaded.');}
    finally{setLoadingOlder(false);}
  }

  async function send(){
    if(!input.trim()||!sessionId||busy||available!==true)return;
    const message=input.trim();setInput('');setBusy(true);setError('');
    setMessages(x=>[...x,{role:'USER',mode,message}]);
    try{
      const r=await fetch('/api/v1/tutor/sessions/'+sessionId+'/messages',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({message,mode,questionId:currentQuestionId||0,language})
      });
      const b=await r.json();
      if(!r.ok)throw new Error(b.message||'Tutor request failed');
      setMessages(x=>[...x,{role:'ASSISTANT',mode,message:b.message}]);
    }catch(e:any){setMessages(x=>x.slice(0,-1));setInput(message);setError(e.message||'Tutor request failed')}
    finally{setBusy(false)}
  }

  return <div className={'tutor-dock '+(open?'open':'')} aria-hidden={!open}>
    <div className="tutor-head">
      <div><span className="eyebrow">QUANTAEDGE TUTOR</span><strong>{language==='en'?'Ask when you are stuck':'अटकें तो पूछें'}</strong></div>
      <button aria-label={language==='en'?'Close tutor':'Tutor बंद करें'} className="tutor-close" onClick={onClose}>×</button>
    </div>
    {available===false ? <div className="tutor-unavailable"><span className="tutor-icon">✦</span><h3>{language==='en'?'Tutor is currently unavailable':'Tutor अभी उपलब्ध नहीं है'}</h3><p>{error||(language==='en'?'AI tutor configuration is required.':'AI tutor configuration की जरूरत है।')}</p></div> :
      <><div className="tutor-modes">{(Object.keys(labels) as Mode[]).map(m=><button type="button" key={m} className={mode===m?'selected':''} onClick={()=>setMode(m)}>{labels[language][m]}</button>)}</div>
      <div className="tutor-messages">
        {hasOlderMessages&&<button type="button" className="tutor-load-older" disabled={loadingOlder} onClick={()=>void loadOlderMessages()} style={{display:'block',margin:'0 auto 12px',padding:'7px 12px',borderRadius:10,border:'1px solid #d8dce5',background:'#fff',cursor:loadingOlder?'wait':'pointer'}}>{loadingOlder?(language==='en'?'Loading older messages…':'पुराने संदेश लोड हो रहे हैं…'):(language==='en'?'↑ Load older messages':'↑ पुराने संदेश देखें')}</button>}
        {messages.length===0&&<div className="tutor-welcome"><span className="tutor-icon">✦</span><strong>{language==='en'?'I will help using this lesson context.':'मैं इसी lesson के context में मदद करूँगा।'}</strong><p>{language==='en'?'Start with a hint, then choose an explanation or step-by-step help.':'पहले संकेत से शुरू करें। जरूरत हो तो explanation या चरण-दर-चरण मदद चुनें।'}</p></div>}
        {messages.map((m,i)=><div className={'tutor-message '+m.role.toLowerCase()} key={m.id||i}><span className="tutor-role">{m.role==='ASSISTANT'?'Tutor':'आप'}</span><p>{m.message}</p></div>)}
        {busy&&<div className="tutor-message assistant"><span className="tutor-role">Tutor</span><p>{language==='en'?'Thinking…':'सोच रहा हूँ…'}</p></div>}
        <div ref={endRef}/>
      </div>
      {error&&<div className="tutor-error">{error}</div>}
      <div className="tutor-input">
        <textarea value={input} maxLength={1200} onChange={e=>setInput(e.target.value.slice(0,1200))} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}}} placeholder={language==='en'?'Where are you stuck?':'आप कहाँ अटके हैं?'}/>
        <button className="button button-dark" disabled={!input.trim()||busy||available!==true} onClick={send}>{language==='en'?'Ask →':'पूछें →'}</button>
      </div>
      <small className="tutor-note">{language==='en'?'Reviewed lesson content is the source of truth; the tutor will not invent official claims.':'Lesson content source of truth है; बिना प्रमाण के official claim नहीं किया जाएगा।'}</small></>}
  </div>;
}
