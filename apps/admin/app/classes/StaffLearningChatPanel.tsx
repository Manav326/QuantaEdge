'use client';

import { formatUserTimestamp } from '../components/formatUserTimestamp';

import { useCallback, useEffect, useMemo, useState } from 'react';
import styles from './Classes.module.css';

type Conversation = {conversationId:number;conversationType:'CLASSROOM'|'STUDENT_TEACHER';status:string;studentId?:number|null;studentName?:string|null;unreadCount?:number;slowModeSeconds?:number};
type Message = {messageId:number;senderType:string;senderId?:number;senderName:string;body:string;messageType:string;status:string;pinned?:boolean;createdAt:string};
type Report = {report_id:number;message_id:number;reason:string;status:string;reporter_name:string;message_body:string;created_at:string};

async function api<T>(url:string,init:RequestInit={}):Promise<T> {
  const headers=new Headers(init.headers||{});
  if(init.body)headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok)throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));
  return body as T;
}
function newMessageId(){
  if(typeof crypto!=='undefined'&&typeof crypto.randomUUID==='function')return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{const r=Math.random()*16|0;return(c==='x'?r:(r&3|8)).toString(16);});
}
export default function StaffLearningChatPanel({sessionId,locale}:{sessionId:number;locale:string}) {
  const [expanded,setExpanded]=useState(false);
  const [conversations,setConversations]=useState<Conversation[]>([]);
  const [activeId,setActiveId]=useState<number|null>(null);
  const [messages,setMessages]=useState<Message[]>([]);
  const [reports,setReports]=useState<Report[]>([]);
  const [draft,setDraft]=useState('');
  const [slowMode,setSlowMode]=useState('0');
  const [loading,setLoading]=useState(false);
  const [sending,setSending]=useState(false);
  const [error,setError]=useState('');
  const hi=locale==='hi'||locale==='hindi';
  const t=(en:string,h:string)=>hi?h:en;
  const active=useMemo(()=>conversations.find(c=>c.conversationId===activeId)||null,[conversations,activeId]);

  const loadConversations=useCallback(async()=>{
    const response=await api<Conversation[]>('/api/v1/learning/live-classes/'+sessionId+'/conversations');
    const list=Array.isArray(response)?response:[];
    setConversations(list);return list;
  },[sessionId]);
  const openClassroom=useCallback(async()=>{
    const row=await api<Conversation>('/api/v1/learning/live-classes/'+sessionId+'/conversations',{
      method:'POST',body:JSON.stringify({conversationType:'CLASSROOM'})
    });
    setActiveId(row.conversationId);setSlowMode(String(row.slowModeSeconds??0));await loadConversations();
  },[sessionId,loadConversations]);

  useEffect(()=>{
    if(!expanded)return;
    let cancelled=false;setLoading(true);setError('');
    (async()=>{try{
      const list=await loadConversations();if(cancelled)return;
      const classroom=list.find(c=>c.conversationType==='CLASSROOM');
      if(classroom){setActiveId(classroom.conversationId);setSlowMode(String(classroom.slowModeSeconds??0));}
      else await openClassroom();
    }catch(e){if(!cancelled)setError(e instanceof Error?e.message:t('Could not load chat.','चैट लोड नहीं हो सकी।'));}
    finally{if(!cancelled)setLoading(false);}})();
    return()=>{cancelled=true;};
  },[expanded,loadConversations,openClassroom]);

  const refresh=useCallback(async(id:number)=>{
    const result=await api<{messages:Message[]}>('/api/v1/learning/conversations/'+id+'/messages?limit=80');
    setMessages(Array.isArray(result.messages)?result.messages:[]);
    const reportRows=await api<Report[]>('/api/v1/learning/conversations/'+id+'/reports');
    setReports(Array.isArray(reportRows)?reportRows:[]);
  },[]);
  useEffect(()=>{
    if(!expanded||!activeId)return;
    let cancelled=false;
    const poll=async()=>{try{
      const result=await api<{messages:Message[]}>('/api/v1/learning/conversations/'+activeId+'/messages?limit=80');
      if(cancelled)return;
      setMessages(Array.isArray(result.messages)?result.messages:[]);
      const reportRows=await api<Report[]>('/api/v1/learning/conversations/'+activeId+'/reports');
      if(!cancelled)setReports(Array.isArray(reportRows)?reportRows:[]);
    }catch(e){if(!cancelled)setError(e instanceof Error?e.message:t('Could not refresh chat.','चैट रीफ़्रेश नहीं हो सकी।'));}};
    void poll();const timer=window.setInterval(()=>void poll(),5000);
    return()=>{cancelled=true;window.clearInterval(timer);};
  },[expanded,activeId]);

  // Durable REST history remains the catch-up path; WebSocket delivers conversation-scoped events.
  useEffect(()=>{
    if(!expanded||!activeId)return;
    let stopped=false;let socket:WebSocket|null=null;let retryTimer:number|undefined;let retryDelay=2000;
    const connect=()=>{
      if(stopped)return;
      try{
        const scheme=window.location.protocol==='https:'?'wss:':'ws:';
        socket=new WebSocket(scheme+'//'+window.location.host+'/api/v1/learning-chat/ws?workspace=EDUCATION_ADMIN');
        socket.onopen=()=>{retryDelay=2000;socket?.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000');};
        socket.onmessage=(event)=>{
          for(const raw of String(event.data).split('\u0000')){
            const frame=raw.replace(/^\r?\n+/,'');
            if(frame.startsWith('CONNECTED')){
              socket?.send('SUBSCRIBE\nid:qe-staff-'+sessionId+'-'+activeId+'\ndestination:/user/queue/learning-conversations/'+activeId+'\nack:auto\n\n\u0000');
            }else if(frame.startsWith('MESSAGE')){
              const split=frame.indexOf('\n\n');if(split<0)continue;
              try{const message=JSON.parse(frame.slice(split+2)) as Message;
                if(typeof message.messageId==='number')setMessages(rows=>rows.some(item=>item.messageId===message.messageId)?rows:[...rows,message].slice(-80));
              }catch{}
            }
          }
        };
        socket.onerror=()=>socket?.close();
        socket.onclose=()=>{if(!stopped){const wait=retryDelay;retryDelay=Math.min(retryDelay*2,30000);retryTimer=window.setTimeout(connect,wait);}};
      }catch{if(!stopped){const wait=retryDelay;retryDelay=Math.min(retryDelay*2,30000);retryTimer=window.setTimeout(connect,wait);}}
    };
    connect();
    return()=>{stopped=true;if(retryTimer!==undefined)window.clearTimeout(retryTimer);try{socket?.send('DISCONNECT\n\n\u0000');socket?.close(1000,'chat panel changed');}catch{}};
  },[expanded,activeId,sessionId]);

  function chooseConversation(id:number){
    setActiveId(id);setMessages([]);setError('');
    setSlowMode(String(conversations.find(c=>c.conversationId===id)?.slowModeSeconds??0));
  }
  async function send(){
    const body=draft.trim();if(!body||!activeId||sending)return;
    setSending(true);setError('');
    try{
      const saved=await api<Message>('/api/v1/learning/conversations/'+activeId+'/messages',{
        method:'POST',body:JSON.stringify({clientMessageId:newMessageId(),body,messageType:'TEXT'})
      });
      setDraft('');setMessages(old=>old.some(m=>m.messageId===saved.messageId)?old:[...old,saved]);
      await loadConversations();
    }catch(e){setError(e instanceof Error?e.message:t('Message could not be sent.','संदेश नहीं भेजा जा सका।'));}
    finally{setSending(false);}
  }
  async function moderate(message:Message,action:string){
    if(!activeId)return;
    try{
      await api('/api/v1/learning/messages/'+message.messageId+'/moderation',{method:'PATCH',body:JSON.stringify({action})});
      if(action==='HIDE')setMessages(rows=>rows.filter(row=>row.messageId!==message.messageId));
      else await refresh(activeId);
    }catch(e){setError(e instanceof Error?e.message:t('Moderation failed.','मॉडरेशन विफल हुआ।'));}
  }
  async function saveSettings(){
    if(!activeId)return;
    try{
      await api('/api/v1/learning/conversations/'+activeId+'/settings',{method:'PATCH',body:JSON.stringify({slowModeSeconds:Number(slowMode)})});
      await loadConversations();
    }catch(e){setError(e instanceof Error?e.message:t('Settings could not be saved.','सेटिंग सहेजी नहीं जा सकी।'));}
  }
  async function mute(studentId:number,minutes:number){
    if(!activeId)return;
    try{await api('/api/v1/learning/conversations/'+activeId+'/mute',{method:'POST',body:JSON.stringify({studentId,minutes})});}
    catch(e){setError(e instanceof Error?e.message:t('Mute action failed.','म्यूट नहीं हो सका।'));}
  }
  async function resolve(reportId:number,status:'RESOLVED'|'DISMISSED'){
    if(!activeId)return;
    try{
      await api('/api/v1/learning/reports/'+reportId,{method:'PATCH',body:JSON.stringify({status})});
      await refresh(activeId);
    }catch(e){setError(e instanceof Error?e.message:t('Report could not be updated.','रिपोर्ट अपडेट नहीं हुई।'));}
  }

  return <div className={styles.chatAdminWrap}>
    <button type="button" className={styles.chatAdminToggle} aria-expanded={expanded} onClick={()=>setExpanded(value=>!value)}>
      {t('Open classroom messaging and moderation','कक्षा संदेश और मॉडरेशन खोलें')} <span>{expanded?'−':'+'}</span>
    </button>
    {expanded&&<section className={styles.chatAdminPanel}>
      <div className={styles.chatAdminHeader}>
        <div><strong>{t('Conversations','बातचीत')}</strong><p>{t('History is persisted in QuantaEdge and can be recovered after reconnect.','इतिहास QuantaEdge में सहेजा जाता है और दोबारा कनेक्ट होने पर मिल जाता है।')}</p></div>
        <button type="button" onClick={()=>void loadConversations()} className={styles.chatAdminButton}>{t('Refresh','रीफ़्रेश')}</button>
      </div>
      {error&&<div role="alert" className={styles.chatAdminError}>{error}</div>}
      <div className={styles.chatAdminThreads}>
        {conversations.map(c=><button type="button" key={c.conversationId}
          className={c.conversationId===activeId?styles.chatAdminSelected:''} onClick={()=>chooseConversation(c.conversationId)}>
          <b>{c.conversationType==='CLASSROOM'?t('Classroom','कक्षा'):t('Private student thread','निजी छात्र संदेश')}</b>
          <small>{c.conversationType==='STUDENT_TEACHER'?(c.studentName||t('Student','छात्र')):t('All eligible enrolled students','सभी पात्र नामांकित छात्र')}</small>
          {Boolean(c.unreadCount)&&<em>{c.unreadCount}</em>}
        </button>)}
      </div>
      {active&&<><div className={styles.chatAdminSettings}>
        <label>{t('Slow mode (seconds)','स्लो मोड (सेकंड)')}<select value={slowMode} onChange={e=>setSlowMode(e.target.value)}><option value="0">{t('Off','बंद')}</option>{[3,5,10,15,30,60].map(n=><option key={n} value={n}>{n}</option>)}</select></label>
        <button type="button" className={styles.chatAdminButton} onClick={()=>void saveSettings()}>{t('Save','सहेजें')}</button>
      </div>
      <div className={styles.chatAdminMessages}>
        {messages.map(m=><article key={m.messageId} className={styles.chatAdminMessage}>
          <div><strong>{m.senderName||t('Member','सदस्य')} · {m.messageType}{m.pinned?' · 📌':''}</strong><small>{m.createdAt?formatUserTimestamp(m.createdAt):''}</small></div>
          <p>{m.body}</p>
          <div className={styles.chatAdminActions}>
            <button type="button" className={styles.chatAdminButton} onClick={()=>void moderate(m,m.pinned?'UNPIN':'PIN')} disabled={!['QUESTION','ANNOUNCEMENT'].includes(m.messageType)}>{m.pinned?t('Unpin','अनपिन'):t('Pin','पिन')}</button>
            <button type="button" className={styles.chatAdminButton} onClick={()=>void moderate(m,'HIDE')}>{t('Hide','छिपाएँ')}</button>
            {m.senderType==='STUDENT'&&m.senderId&&<button type="button" className={styles.chatAdminButton} onClick={()=>void mute(Number(m.senderId),10)}>{t('Mute 10 min','10 मिनट म्यूट')}</button>}
          </div>
        </article>)}
      </div>
      <form className={styles.chatAdminCompose} onSubmit={e=>{e.preventDefault();void send();}}>
        <textarea value={draft} onChange={e=>setDraft(e.target.value)} maxLength={4000} placeholder={t('Reply to this conversation…','इस बातचीत में उत्तर दें…')}/>
        <button type="submit" className={styles.chatAdminButton} disabled={sending||!draft.trim()}>{t('Send','भेजें')}</button>
      </form>
      <div className={styles.chatAdminReports}>
        <strong>{t('Message reports','संदेश रिपोर्ट')}</strong>
        {reports.filter(r=>r.status==='OPEN').length===0?<p>{t('No open reports.','कोई खुली रिपोर्ट नहीं।')}</p>:reports.filter(r=>r.status==='OPEN').map(r=><article key={r.report_id}>
          <p><b>{r.reporter_name}</b>: {r.reason}</p><blockquote>{r.message_body}</blockquote>
          <button type="button" className={styles.chatAdminButton} onClick={()=>void resolve(r.report_id,'RESOLVED')}>{t('Resolve','सुलझाएँ')}</button>
          <button type="button" className={styles.chatAdminButton} onClick={()=>void resolve(r.report_id,'DISMISSED')}>{t('Dismiss','खारिज करें')}</button>
        </article>)}
      </div></>}
      {loading&&<p>{t('Loading…','लोड हो रहा है…')}</p>}
    </section>}
  </div>;
}
