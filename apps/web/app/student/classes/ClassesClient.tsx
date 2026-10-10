'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { LocaleText } from '../../components/LanguageProvider';
import styles from './Classes.module.css';

type LiveClass = {
  session_id:number;title:string;description?:string;provider:string;starts_at:string;ends_at:string;status:string;
  subject_code:string;subject_name:string;chapter_id?:number|null;chapter_name?:string|null;host_staff_name?:string|null;
  has_recording?:boolean;join_count?:number;attended_seconds?:number;currently_joined?:boolean;
};
type Recording = {
  recorded_class_id:number;title:string;description?:string;media_type:string;file_size_bytes:number;
  duration_seconds?:number|null;created_at?:string|null;subject_code:string;subject_name:string;class_code:string;
  chapter_id?:number|null;chapter_name?:string|null;session_id?:number|null;session_title?:string|null;
  session_starts_at?:string|null;last_position_seconds:number;watched_seconds:number;completed:boolean;
};

async function api(url:string,init:RequestInit={}) {
  const headers=new Headers(init.headers||{});
  if(init.body)headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok){if(response.status===401||response.status===403)throw Object.assign(new Error('Student sign-in is required.'),{auth:true});throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));}
  return body;
}
function formatDate(value?:string|null){
  if(!value)return '—';const d=new Date(value);
  return Number.isNaN(d.getTime())?'—':d.toLocaleString([], {weekday:'short',month:'short',day:'numeric',hour:'numeric',minute:'2-digit'});
}
function formatSize(size:number){return size>=1024*1024?(size/(1024*1024)).toFixed(1)+' MB':Math.max(1,Math.round(size/1024))+' KB'}
function durationText(seconds?:number|null){
  if(!seconds)return 'Duration not listed';const h=Math.floor(seconds/3600),m=Math.floor((seconds%3600)/60);
  return h?h+'h '+m+'m':m+' min';
}
function percent(recording:Recording){return recording.duration_seconds?Math.min(100,Math.round(recording.last_position_seconds*100/recording.duration_seconds)):0}

export default function StudentClassesClient(){
  const router=useRouter();
  const [tab,setTab]=useState<'live'|'recorded'>('live');
  const [subjectFilter,setSubjectFilter]=useState('ALL');
  const [sessions,setSessions]=useState<LiveClass[]>([]);
  const [recordings,setRecordings]=useState<Recording[]>([]);
  const [joinedSessionId,setJoinedSessionId]=useState<number|null>(null);
  const joinedSessionRef=useRef<number|null>(null);
  const [selectedRecording,setSelectedRecording]=useState<Recording|null>(null);
  const videoRef=useRef<HTMLVideoElement|null>(null);
  const watchRef=useRef<{recordingId:number;lastMediaTime:number;watchedSeconds:number;lastSavedPosition:number}>({
    recordingId:0,lastMediaTime:0,watchedSeconds:0,lastSavedPosition:0
  });
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState(false);
  const [savingProgress,setSavingProgress]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const params=new URLSearchParams();
      if(subjectFilter!=='ALL')params.set('subjectCode',subjectFilter);
      const query=params.toString()?'?'+params.toString():'';
      const [live,videoRows]=await Promise.all([
        api('/api/v1/learning/live-classes'+query),
        api('/api/v1/learning/recorded-classes'+query)
      ]);
      setSessions(Array.isArray(live)?live:[]);
      setRecordings(Array.isArray(videoRows)?videoRows:[]);
      const active=(Array.isArray(live)?live:[]).find((s:LiveClass)=>s.currently_joined);
      if(active&&!joinedSessionRef.current){joinedSessionRef.current=active.session_id;setJoinedSessionId(active.session_id);}
    }catch(e:any){
      if(e?.auth){router.replace('/login/student');return;}
      setError(e instanceof Error?e.message:'Your class library could not be loaded.');
    }finally{setLoading(false);}
  },[subjectFilter,router]);
  useEffect(()=>{void load();},[load]);

  useEffect(()=>()=>{
    const id=joinedSessionRef.current;
    if(id)void fetch('/api/v1/learning/live-classes/'+id+'/leave',{method:'POST',credentials:'same-origin',keepalive:true}).catch(()=>undefined);
  },[]);

  async function joinClass(session:LiveClass){
    if(busy)return;
    // Open a temporary same-origin window during the click so popup blockers do not
    // discard the provider URL returned by the access-checked API.
    const popup=window.open('about:blank','_blank');
    if(popup){
      try{popup.opener=null;popup.document.title='Opening secure class';popup.document.body.textContent='Checking your class access…';}catch{}
    }
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/learning/live-classes/'+session.session_id+'/join',{method:'POST'});
      joinedSessionRef.current=session.session_id;setJoinedSessionId(session.session_id);
      if(popup&&!popup.closed){popup.location.replace(String(result.joinUrl));}
      else{setNotice('You are checked in. Use “Open meeting” below to enter the live class.');}
      setNotice('Attendance is saved for this session. Keep this page open so you can leave the class cleanly when finished.');
      await load();
    }catch(e:any){
      if(popup&&!popup.closed)popup.close();
      if(e?.auth){router.replace('/login/student');return;}
      setError(e instanceof Error?e.message:'This class cannot be joined right now.');
    }finally{setBusy(false);}
  }

  async function openMeetingAgain(sessionId:number){
    setBusy(true);setError('');
    try{
      const result=await api('/api/v1/learning/live-classes/'+sessionId+'/join',{method:'POST'});
      window.open(String(result.joinUrl),'_blank','noopener,noreferrer');
      joinedSessionRef.current=sessionId;setJoinedSessionId(sessionId);
      await load();
    }catch(e:any){setError(e instanceof Error?e.message:'Meeting access is not available.');}
    finally{setBusy(false);}
  }

  async function leaveClass(sessionId:number){
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/learning/live-classes/'+sessionId+'/leave',{method:'POST'});
      joinedSessionRef.current=null;setJoinedSessionId(null);
      setNotice('You left the class. Your attendance duration has been saved.');
      await load();
    }catch(e:any){setError(e instanceof Error?e.message:'Attendance could not be updated.');}
    finally{setBusy(false);}
  }

  function openRecording(recording:Recording){
    setError('');setNotice('');
    setSelectedRecording(recording);
    watchRef.current={
      recordingId:recording.recorded_class_id,
      lastMediaTime:recording.last_position_seconds||0,
      watchedSeconds:recording.watched_seconds||0,
      lastSavedPosition:recording.last_position_seconds||0
    };
    setTab('recorded');
  }

  async function persistPlayback(recording:Recording,positionSeconds:number,completed=false){
    if(watchRef.current.recordingId!==recording.recorded_class_id)return;
    const safePosition=Math.max(0,Math.floor(positionSeconds));
    setSavingProgress(true);
    try{
      const result=await api('/api/v1/learning/recorded-classes/'+recording.recorded_class_id+'/progress',{
        method:'PUT',body:JSON.stringify({
          positionSeconds:safePosition,
          watchedSeconds:Math.max(watchRef.current.watchedSeconds,safePosition),
          completed
        })
      });
      watchRef.current.lastSavedPosition=safePosition;
      setRecordings(rows=>rows.map(row=>row.recorded_class_id===recording.recorded_class_id?{
        ...row,last_position_seconds:safePosition,
        watched_seconds:Math.max(row.watched_seconds||0,Math.max(watchRef.current.watchedSeconds,safePosition)),
        completed:Boolean(result.completed)
      }:row));
      setSelectedRecording(old=>old&&old.recorded_class_id===recording.recorded_class_id?{
        ...old,last_position_seconds:safePosition,
        watched_seconds:Math.max(old.watched_seconds||0,Math.max(watchRef.current.watchedSeconds,safePosition)),
        completed:Boolean(result.completed)
      }:old);
    }catch(e:any){
      if(e?.auth)router.replace('/login/student');
      else setError(e instanceof Error?e.message:'Playback progress could not be saved.');
    }finally{setSavingProgress(false);}
  }

  function handleTimeUpdate(){
    const video=videoRef.current,recording=selectedRecording;
    if(!video||!recording||video.readyState<1)return;
    const current=video.currentTime;
    const previous=watchRef.current.lastMediaTime;
    const delta=current-previous;
    if(!video.paused&&delta>0&&delta<=3.5)watchRef.current.watchedSeconds+=delta;
    watchRef.current.lastMediaTime=current;
    if(Math.abs(current-watchRef.current.lastSavedPosition)>=10){
      void persistPlayback(recording,current,false);
    }
  }

  function closeRecording(){
    const video=videoRef.current,recording=selectedRecording;
    if(video&&recording){
      const id=recording.recorded_class_id,position=video.currentTime;
      void persistPlayback(recording,position,video.ended);
      watchRef.current.recordingId=0;
      try{video.pause();}catch{}
      if(id===recording.recorded_class_id)setSelectedRecording(null);
    }else setSelectedRecording(null);
  }

  const filteredSessions=sessions.filter(s=>subjectFilter==='ALL'||s.subject_code===subjectFilter);
  const filteredRecordings=recordings.filter(r=>subjectFilter==='ALL'||r.subject_code===subjectFilter);
  const activeJoined=sessions.find(s=>s.session_id===joinedSessionId);
  const now=Date.now();

  return <main className={styles.shell}>
    <header className={styles.header}><Link href="/student" className={styles.brand}><span>Q</span><b>QuantaEdge<small>STUDENT LEARNING</small></b></Link><nav><Link href="/student/learn"><LocaleText hinglish="पढ़ाई" english="Learning"/></Link><Link href="/student/textbooks"><LocaleText hinglish="किताबें" english="Textbooks"/></Link><Link href="/student/tests"><LocaleText hinglish="Tests" english="Tests"/></Link><Link href="/student">Home</Link></nav></header>
    <section className={styles.wrap}>
      <div className={styles.hero}><div><span className={styles.eyebrow}>YOUR CLASSROOM · LIVE & ON DEMAND</span><h1><LocaleText hinglish="कक्षा में शामिल हों। बाद में वहीं से सीखना जारी रखें।" english="Join a class live. Continue learning on demand." /></h1><p><LocaleText hinglish="Live sessions में आपका attendance save होता है। Recorded classes में playback position save होती है ताकि आप वहीं से फिर शुरू कर सकें।" english="Attendance is recorded for live sessions, and recorded classes save your playback position so you can pick up where you stopped." /></p><div className={styles.heroChips}><span>◷ Attendance saved</span><span>▶ Resume playback</span><span>🔒 Enrollment checked</span></div></div><div className={styles.heroArt} aria-hidden="true"><div className={styles.orbitOne}/><div className={styles.orbitTwo}/><span>▶</span><small>LEARN<br/>TOGETHER</small></div></div>
      {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.notice} role="status">{notice}</div>}
      {activeJoined&&<div className={styles.joinedBanner}><span>●</span><div><b>You are checked in</b><small>{activeJoined.title} · attendance is being recorded while your session is active.</small></div><button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void openMeetingAgain(activeJoined.session_id)}>Open meeting again ↗</button><button type="button" className={styles.leaveButton} disabled={busy} onClick={()=>void leaveClass(activeJoined.session_id)}>Leave & save attendance</button></div>}
      <div className={styles.toolbar}><div className={styles.tabs}><button type="button" className={tab==='live'?styles.activeTab:''} onClick={()=>setTab('live')}>Live classes <span>{filteredSessions.length}</span></button><button type="button" className={tab==='recorded'?styles.activeTab:''} onClick={()=>setTab('recorded')}>Recorded classes <span>{filteredRecordings.length}</span></button></div><label>Subject<select value={subjectFilter} onChange={e=>setSubjectFilter(e.target.value)}><option value="ALL">All subjects</option><option value="maths">Mathematics</option><option value="science">Science</option></select></label></div>

      {tab==='live'?<>
        <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>YOUR TIMETABLE</span><h2>Live and scheduled sessions</h2><p>Meeting links are released only after the access check and within the join window.</p></div><button type="button" className={styles.refreshButton} onClick={()=>void load()}>↻ Refresh</button></div>
        {loading?<div className={styles.empty}>Loading your live classes…</div>:filteredSessions.length===0?<div className={styles.empty}><span>◷</span><b>No classes scheduled yet</b><small>Your teacher’s live sessions will appear here when published for your subject.</small></div>:<div className={styles.sessionGrid}>{filteredSessions.map(session=>{
          const start=new Date(session.starts_at).getTime(),end=new Date(session.ends_at).getTime();
          const ended=end<now||session.status==='COMPLETED';
          const canJoin=!ended&&['SCHEDULED','LIVE'].includes(session.status)&&start<=now+15*60*1000&&end>now;
          const joined=joinedSessionId===session.session_id||session.currently_joined;
          return <article key={session.session_id} className={styles.sessionCard}><div className={styles.sessionTop}><span className={styles.liveSymbol}>◷</span><span className={styles['status'+session.status]}>{session.status==='LIVE'?'● LIVE':session.status}</span></div><h3>{session.title}</h3><p>{session.subject_name}{session.chapter_name?' · '+session.chapter_name:''}</p><div className={styles.sessionTime}><span>START</span><b>{formatDate(session.starts_at)}</b><span>END</span><b>{formatDate(session.ends_at)}</b></div>{session.description&&<p className={styles.description}>{session.description}</p>}<div className={styles.sessionMeta}><span>Host: {session.host_staff_name||'Teacher'}</span><span>{session.provider.replaceAll('_',' ')}</span>{session.has_recording&&<span>Recording available</span>}</div>{joined?<div className={styles.joinedActions}><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void openMeetingAgain(session.session_id)}>Open meeting ↗</button><button type="button" className={styles.leaveButton} disabled={busy} onClick={()=>void leaveClass(session.session_id)}>Leave class</button></div>:canJoin?<button type="button" className={styles.primaryButton+' '+styles.fullButton} disabled={busy} onClick={()=>void joinClass(session)}>{busy?'Checking access…':session.status==='LIVE'?'Join live class ↗':'Join class ↗'}</button>:<button type="button" className={styles.disabledButton} disabled>{ended?'Session ended':start>now+15*60*1000?'Join opens '+formatDate(session.starts_at):'Not available to join'}</button>}{session.has_recording&&<button type="button" className={styles.textButton} onClick={()=>{setTab('recorded');}}>View class recordings →</button>}</article>;
        })}</div>}
      </>:<>
        <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>WATCH AT YOUR PACE</span><h2>Recorded classes</h2><p>Videos stream from private storage after each access check. Your place is saved as you watch.</p></div><button type="button" className={styles.refreshButton} onClick={()=>void load()}>↻ Refresh</button></div>
        {loading?<div className={styles.empty}>Loading your recorded classes…</div>:filteredRecordings.length===0?<div className={styles.empty}><span>▶</span><b>No recorded classes available yet</b><small>Published class recordings for your enrolled subjects will appear here.</small></div>:<div className={styles.recordingGrid}>{filteredRecordings.map(recording=><article key={recording.recorded_class_id} className={styles.recordingCard}><div className={styles.videoPoster}><span>▶</span><small>{durationText(recording.duration_seconds)}</small>{recording.completed&&<b>✓ COMPLETED</b>}</div><div className={styles.recordingBody}><span className={styles.subjectTag}>{recording.subject_name}</span><h3>{recording.title}</h3><p>{recording.chapter_name||recording.session_title||'Recorded lesson'}{recording.session_starts_at?' · '+formatDate(recording.session_starts_at):''}</p>{recording.description&&<p className={styles.description}>{recording.description}</p>}<div className={styles.recordingProgress}><div><span>{recording.completed?'Completed':recording.last_position_seconds>0?'Continue watching':'Not started'}</span><b>{percent(recording)}%</b></div><i><b style={{width:percent(recording)+'%'}}/></i></div><div className={styles.recordingMeta}><span>{formatSize(recording.file_size_bytes)}</span><span>{formatDate(recording.created_at)}</span></div><button type="button" className={styles.primaryButton+' '+styles.fullButton} onClick={()=>openRecording(recording)}>{recording.last_position_seconds>0&&!recording.completed?'Resume watching':'Watch online'} ↗</button></div></article>)}</div>}
      </>}
      <div className={styles.privacyNote}><span>🔒</span><p><b>Protected class access.</b> Meeting links and video file paths are never listed publicly. QuantaEdge verifies your account and active subject enrollment for every join and playback request. A browser cannot fully prevent screen recording or capture of content already displayed.</p></div>
    </section>

    {selectedRecording&&<div className={styles.playerBackdrop} role="presentation" onMouseDown={e=>{if(e.target===e.currentTarget)closeRecording();}}>
      <section className={styles.playerModal} role="dialog" aria-modal="true" aria-labelledby="class-video-title">
        <header><div><span className={styles.eyebrow}>PRIVATE CLASS PLAYER</span><h2 id="class-video-title">{selectedRecording.title}</h2><p>{selectedRecording.chapter_name||selectedRecording.session_title||selectedRecording.subject_name}</p></div><button type="button" aria-label="Close video player" onClick={closeRecording}>×</button></header>
        <div className={styles.videoFrame}><video ref={videoRef} key={selectedRecording.recorded_class_id} controls playsInline preload="metadata" controlsList="nodownload noplaybackrate" disablePictureInPicture src={'/api/v1/learning/recorded-classes/'+selectedRecording.recorded_class_id+'/stream'} onLoadedMetadata={e=>{const video=e.currentTarget;const start=Number(selectedRecording.last_position_seconds||0);if(start>0&&start<(video.duration||Infinity)){video.currentTime=start;}watchRef.current.lastMediaTime=video.currentTime;watchRef.current.lastSavedPosition=start;}} onTimeUpdate={handleTimeUpdate} onPause={e=>void persistPlayback(selectedRecording,e.currentTarget.currentTime,e.currentTarget.ended)} onEnded={e=>void persistPlayback(selectedRecording,e.currentTarget.currentTime,true)} onError={()=>setError('This recording could not be played. Try refreshing the library or notify your teacher.')}/></div>
        <footer><div><b>{savingProgress?'Saving playback progress…':selectedRecording.completed?'Completed':'Progress is saved automatically'}</b><small>Position {durationText(Math.floor(selectedRecording.last_position_seconds||0))}{selectedRecording.duration_seconds?' of '+durationText(selectedRecording.duration_seconds):''}</small></div><button type="button" className={styles.secondaryButton} onClick={closeRecording}>Close player</button></footer>
      </section>
    </div>}
  </main>;
}
