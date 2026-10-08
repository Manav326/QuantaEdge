'use client';

import { useEffect, useRef, useState } from 'react';

type TutorMessage={id?:number;role:'USER'|'ASSISTANT';mode?:string;message:string};
type Mode='HINT'|'EXPLAIN'|'EXAMPLE'|'STEP_BY_STEP'|'CHECK_MY_WORK';
const labels:Record<Mode,string>={HINT:'Hint',EXPLAIN:'समझाएँ',EXAMPLE:'उदाहरण',STEP_BY_STEP:'Step-by-step',CHECK_MY_WORK:'मेरी जाँच करें'};

export default function TutorDock({lessonId,open,onClose,currentQuestionId}:{lessonId:number;open:boolean;onClose:()=>void;currentQuestionId?:number}){
  const [sessionId,setSessionId]=useState<number|null>(null);
  const [available,setAvailable]=useState<boolean|null>(null);
  const [messages,setMessages]=useState<TutorMessage[]>([]);
  const [mode,setMode]=useState<Mode>('HINT');
  const [input,setInput]=useState('');
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const endRef=useRef<HTMLDivElement|null>(null);

  useEffect(()=>{
    if(!open)return;
    let cancelled=false;
    (async()=>{
      try{
        setError('');
        const r=await fetch('/api/v1/tutor/sessions',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({lessonId})});
        const b=await r.json();
        if(cancelled)return;
        if(!r.ok){setAvailable(false);setError(b.message||'Tutor unavailable');return;}
        setAvailable(!!b.available);setSessionId(Number(b.sessionId));
        if(b.available){
          const mr=await fetch('/api/v1/tutor/sessions/'+b.sessionId+'/messages');
          if(mr.ok)setMessages(await mr.json());
        }
      }catch(e:any){if(!cancelled){setAvailable(false);setError(e.message||'Tutor unavailable')}}
    })();
    return()=>{cancelled=true};
  },[open,lessonId]);

  useEffect(()=>{endRef.current?.scrollIntoView({behavior:'smooth'})},[messages,busy]);

  async function send(){
    if(!input.trim()||!sessionId||busy||available!==true)return;
    const message=input.trim();setInput('');setBusy(true);setError('');
    setMessages(x=>[...x,{role:'USER',mode,message}]);
    try{
      const r=await fetch('/api/v1/tutor/sessions/'+sessionId+'/messages',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({message,mode,questionId:currentQuestionId||0})
      });
      const b=await r.json();
      if(!r.ok)throw new Error(b.message||'Tutor request failed');
      setMessages(x=>[...x,{role:'ASSISTANT',mode,message:b.message}]);
    }catch(e:any){setMessages(x=>x.slice(0,-1));setError(e.message||'Tutor request failed')}
    finally{setBusy(false)}
  }

  return <div className={'tutor-dock '+(open?'open':'')} aria-hidden={!open}>
    <div className="tutor-head">
      <div><span className="eyebrow">QUANTAEDGE TUTOR</span><strong>अटकें तो पूछें</strong></div>
      <button aria-label="Tutor बंद करें" className="tutor-close" onClick={onClose}>×</button>
    </div>
    {available===false ? <div className="tutor-unavailable"><span className="tutor-icon">✦</span><h3>Tutor अभी उपलब्ध नहीं है</h3><p>{error||'AI tutor configuration की जरूरत है।'}</p></div> :
      <><div className="tutor-modes">{(Object.keys(labels) as Mode[]).map(m=><button type="button" key={m} className={mode===m?'selected':''} onClick={()=>setMode(m)}>{labels[m]}</button>)}</div>
      <div className="tutor-messages">
        {messages.length===0&&<div className="tutor-welcome"><span className="tutor-icon">✦</span><strong>मैं इसी lesson के context में मदद करूँगा।</strong><p>पहले hint से शुरू करें। जरूरत हो तो explanation या step-by-step चुनें।</p></div>}
        {messages.map((m,i)=><div className={'tutor-message '+m.role.toLowerCase()} key={m.id||i}><span className="tutor-role">{m.role==='ASSISTANT'?'Tutor':'आप'}</span><p>{m.message}</p></div>)}
        {busy&&<div className="tutor-message assistant"><span className="tutor-role">Tutor</span><p>सोच रहा हूँ…</p></div>}
        <div ref={endRef}/>
      </div>
      {error&&<div className="tutor-error">{error}</div>}
      <div className="tutor-input">
        <textarea value={input} maxLength={1200} onChange={e=>setInput(e.target.value.slice(0,1200))} onKeyDown={e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();send()}}} placeholder="आप कहाँ अटके हैं?"/>
        <button className="button button-dark" disabled={!input.trim()||busy||available!==true} onClick={send}>पूछें →</button>
      </div>
      <small className="tutor-note">Lesson content source of truth है; बिना provenance के official claim नहीं किया जाएगा।</small></>}
  </div>;
}
