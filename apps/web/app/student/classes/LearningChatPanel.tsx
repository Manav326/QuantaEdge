'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import styles from './Classes.module.css';
import { formatUserTimestamp } from '../../components/formatUserTimestamp';

type Conversation = {
  conversationId:number; conversationType:'CLASSROOM'|'STUDENT_TEACHER'; status:string;
  unreadCount?:number; lastMessagePreview?:string|null; studentName?:string|null;
};
type ChatMessage = {
  messageId:number; senderType:string; senderName:string; body:string; messageType:string;
  createdAt:string; pinned?:boolean;
};

async function chatApi<T>(url:string, init:RequestInit = {}):Promise<T> {
  const headers=new Headers(init.headers || {});
  if(init.body) headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();
  let body:any={};
  try { body=raw?JSON.parse(raw):{}; } catch { body={message:raw}; }
  if(!response.ok) throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));
  return body as T;
}
function clientMessageId() {
  if(typeof crypto!=='undefined' && typeof crypto.randomUUID==='function') return crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,c=>{
    const r=Math.random()*16|0;return (c==='x'?r:(r&0x3|0x8)).toString(16);
  });
}
function dateLabel(value:string, locale:string) {
  return formatUserTimestamp(value);
}

export default function LearningChatPanel({sessionId,locale}:{sessionId:number;locale:string}) {
  const [expanded,setExpanded]=useState(false);
  const [conversations,setConversations]=useState<Conversation[]>([]);
  const [activeId,setActiveId]=useState<number|null>(null);
  const [messages,setMessages]=useState<ChatMessage[]>([]);
  const [draft,setDraft]=useState('');
  const [loading,setLoading]=useState(false);
  const [sending,setSending]=useState(false);
  const [error,setError]=useState('');
  const english=locale==='english';
  const tr=(en:string,hi:string)=>english?en:hi;
  const active=useMemo(()=>conversations.find(c=>c.conversationId===activeId)||null,[conversations,activeId]);

  const refreshConversations=useCallback(async()=>{
    const rows=await chatApi<Conversation[]>('/api/v1/learning/live-classes/'+sessionId+'/conversations');
    const list=Array.isArray(rows)?rows:[];
    setConversations(list);
    return list;
  },[sessionId]);

  const openConversation=useCallback(async(type:'CLASSROOM'|'STUDENT_TEACHER')=>{
    setError('');
    const row=await chatApi<Conversation>('/api/v1/learning/live-classes/'+sessionId+'/conversations',{
      method:'POST',body:JSON.stringify({conversationType:type})
    });
    setActiveId(row.conversationId);
    await refreshConversations();
  },[sessionId,refreshConversations]);

  useEffect(()=>{
    if(!expanded)return;
    let cancelled=false;
    setLoading(true);setError('');
    (async()=>{
      try {
        const list=await refreshConversations();
        if(cancelled)return;
        const classroom=list.find(c=>c.conversationType==='CLASSROOM');
        if(classroom) setActiveId(current=>current??classroom.conversationId);
        else await openConversation('CLASSROOM');
      } catch(e) {
        if(!cancelled)setError(e instanceof Error?e.message:tr('Could not load class chat.','कक्षा चैट लोड नहीं हो सकी।'));
      } finally { if(!cancelled)setLoading(false); }
    })();
    return()=>{cancelled=true;};
  },[expanded,refreshConversations,openConversation]);

  useEffect(()=>{
    if(!expanded||!activeId)return;
    let cancelled=false;
    const poll=async()=>{
      try {
        const result=await chatApi<{messages:ChatMessage[]}>('/api/v1/learning/conversations/'+activeId+'/messages?limit=60');
        if(!cancelled)setMessages(Array.isArray(result.messages)?result.messages:[]);
      } catch(e) {
        if(!cancelled)setError(e instanceof Error?e.message:tr('Could not refresh messages.','संदेश रीफ़्रेश नहीं हो सके।'));
      }
    };
    void poll();
    const timer=window.setInterval(()=>void poll(),4000);
    return()=>{cancelled=true;window.clearInterval(timer);};
  },[expanded,activeId]);

  // WebSocket is the low-latency path; REST history polling remains the reconnect/catch-up fallback.
  useEffect(()=>{
    if(!expanded||!activeId)return;
    let stopped=false;let socket:WebSocket|null=null;let retryTimer:number|undefined;let retryDelay=2000;
    const connect=()=>{
      if(stopped)return;
      try{
        const scheme=window.location.protocol==='https:'?'wss:':'ws:';
        socket=new WebSocket(scheme+'//'+window.location.host+'/api/v1/learning-chat/ws?workspace=STUDENT');
        socket.onopen=()=>{retryDelay=2000;socket?.send('CONNECT\naccept-version:1.2\nheart-beat:0,0\n\n\u0000');};
        socket.onmessage=(event)=>{
          for(const raw of String(event.data).split('\u0000')){
            const frame=raw.replace(/^\r?\n+/,'');
            if(frame.startsWith('CONNECTED')){
              socket?.send('SUBSCRIBE\nid:qe-student-'+sessionId+'-'+activeId+'\ndestination:/user/queue/learning-conversations/'+activeId+'\nack:auto\n\n\u0000');
            }else if(frame.startsWith('MESSAGE')){
              const split=frame.indexOf('\n\n');if(split<0)continue;
              try{const message=JSON.parse(frame.slice(split+2)) as ChatMessage;
                if(typeof message.messageId==='number')setMessages(rows=>rows.some(item=>item.messageId===message.messageId)?rows:[...rows,message].slice(-60));
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

  async function send() {
    const body=draft.trim();
    if(!body||!activeId||sending)return;
    setSending(true);setError('');
    try {
      const saved=await chatApi<ChatMessage>('/api/v1/learning/conversations/'+activeId+'/messages',{
        method:'POST',body:JSON.stringify({clientMessageId:clientMessageId(),body,messageType:'TEXT'})
      });
      setDraft('');
      setMessages(current=>current.some(m=>m.messageId===saved.messageId)?current:[...current,saved]);
      await refreshConversations();
    } catch(e) {
      setError(e instanceof Error?e.message:tr('Message could not be sent.','संदेश नहीं भेजा जा सका।'));
    } finally {setSending(false);}
  }
  async function report(message:ChatMessage) {
    const reason=window.prompt(tr('Why are you reporting this message?','इस संदेश की रिपोर्ट क्यों कर रहे हैं?'));
    if(!reason)return;
    try {
      await chatApi('/api/v1/learning/messages/'+message.messageId+'/reports',{method:'POST',body:JSON.stringify({reason})});
      setError('');
    } catch(e) {setError(e instanceof Error?e.message:tr('Report could not be submitted.','रिपोर्ट नहीं भेजी जा सकी।'));}
  }

  return <div>
    <button type="button" className={styles.chatToggle} aria-expanded={expanded} onClick={()=>setExpanded(value=>!value)}>
      <span>{tr('Class chat & teacher messages','कक्षा चैट और शिक्षक संदेश')}</span>
      <span>{expanded?'−':'+'}</span>
    </button>
    {expanded&&<section className={styles.chatPanel} aria-label={tr('Class messages','कक्षा संदेश')}>
      <div className={styles.chatHeader}>
        <strong>{tr('QuantaEdge classroom','QuantaEdge कक्षा')}</strong>
        <div className={styles.chatTabs}>
          <button type="button" className={active?.conversationType==='CLASSROOM'?styles.chatActive:''} disabled={loading||sending}
            onClick={()=>void openConversation('CLASSROOM')}>{tr('Class chat','कक्षा चैट')}</button>
          <button type="button" className={active?.conversationType==='STUDENT_TEACHER'?styles.chatActive:''} disabled={loading||sending}
            onClick={()=>void openConversation('STUDENT_TEACHER')}>{tr('Private teacher message','शिक्षक को निजी संदेश')}</button>
        </div>
      </div>
      {error&&<div className={styles.chatError} role="alert">{error}</div>}
      {loading?<p className={styles.chatHint}>{tr('Loading messages…','संदेश लोड हो रहे हैं…')}</p>:<div className={styles.chatMessages} aria-live="polite">
        {messages.length===0?<p className={styles.chatHint}>{tr('No messages yet. Start the conversation.','अभी संदेश नहीं हैं। बातचीत शुरू करें।')}</p>:messages.map(message=><article className={styles.chatMessage} key={message.messageId}>
          <div className={styles.chatMessageHead}><strong>{message.senderName||tr('Member','सदस्य')}{message.pinned?' · 📌':''}</strong><span>{dateLabel(message.createdAt,locale)}</span></div>
          <p>{message.body}</p>
          <button type="button" className={styles.chatAction} onClick={()=>void report(message)}>{tr('Report','रिपोर्ट')}</button>
        </article>)}
      </div>}
      <form className={styles.chatCompose} onSubmit={e=>{e.preventDefault();void send();}}>
        <textarea value={draft} maxLength={4000} onChange={e=>setDraft(e.target.value)} placeholder={tr('Write a message…','संदेश लिखें…')} aria-label={tr('Write a message','संदेश लिखें')} />
        <button type="submit" className={styles.chatSend} disabled={sending||loading||!draft.trim()||!activeId}>{sending?tr('Sending…','भेज रहे हैं…'):tr('Send','भेजें')}</button>
      </form>
      <p className={styles.chatHint}>{tr('Messages refresh automatically. Keep private messages appropriate and never share passwords or personal contact details.','संदेश अपने आप रीफ़्रेश होते हैं। निजी संदेश उचित रखें और पासवर्ड या व्यक्तिगत संपर्क विवरण साझा न करें।')}</p>
    </section>}
  </div>;
}
