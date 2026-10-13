'use client';

import Link from 'next/link';
import PremiumPagination from '../../components/PremiumPagination';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale } from '../../components/LanguageProvider';
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
  const {locale}=useLocale();
  const t=(english:string,hindi:string)=>locale==='english'?english:hindi;
  const [tab,setTab]=useState<'live'|'recorded'>('live');
  const [subjectFilter,setSubjectFilter]=useState('ALL');
  const [sessions,setSessions]=useState<LiveClass[]>([]);
  const [recordings,setRecordings]=useState<Recording[]>([]);
  const [joinedSessionId,setJoinedSessionId]=useState<number|null>(null);
  const [activeJoinUrl,setActiveJoinUrl]=useState<string|null>(null);
  const joinedSessionRef=useRef<number|null>(null);
  const [selectedRecording,setSelectedRecording]=useState<Recording|null>(null);
  const [livePage,setLivePage]=useState(0); const [liveTotal,setLiveTotal]=useState(0); const [livePageSize,setLivePageSize]=useState(10);
  const [recordingPage,setRecordingPage]=useState(0); const [recordingsTotal,setRecordingsTotal]=useState(0); const [recordingPageSize,setRecordingPageSize]=useState(10);
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
      const liveParams=new URLSearchParams(params.toString());liveParams.set('limit',String(livePageSize));liveParams.set('offset',String(livePage*livePageSize));
      const recordingParams=new URLSearchParams(params.toString());recordingParams.set('limit',String(recordingPageSize));recordingParams.set('offset',String(recordingPage*recordingPageSize));
      const [live,videoRows]=await Promise.all([
        api('/api/v1/learning/live-classes?'+liveParams.toString()),
        api('/api/v1/learning/recorded-classes?'+recordingParams.toString())
      ]);
      setSessions(Array.isArray(live?.items)?live.items:(Array.isArray(live)?live:[]));
      setLiveTotal(Number(live?.total??(Array.isArray(live)?live.length:0)));
      setRecordings(Array.isArray(videoRows?.items)?videoRows.items:(Array.isArray(videoRows)?videoRows:[]));
      setRecordingsTotal(Number(videoRows?.total??(Array.isArray(videoRows)?videoRows.length:0)));
      const active=(Array.isArray(live?.items)?live.items:(Array.isArray(live)?live:[])).find((s:LiveClass)=>s.currently_joined);
      if(active&&!joinedSessionRef.current){joinedSessionRef.current=active.session_id;setJoinedSessionId(active.session_id);}
    }catch(e:any){
      if(e?.auth){router.replace('/login/student');return;}
      setError(e instanceof Error?e.message:t('Your class library could not be loaded.','आपकी कक्षा लाइब्रेरी लोड नहीं हो सकी।'));
    }finally{setLoading(false);}
  },[subjectFilter,livePage,recordingPage,livePageSize,recordingPageSize,router,locale]);
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
      try{popup.opener=null;popup.document.title=t('Opening secure class','सुरक्षित कक्षा खोली जा रही है');popup.document.body.textContent=t('Checking your class access…','कक्षा की अनुमति जाँची जा रही है…');}catch{}
    }
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/learning/live-classes/'+session.session_id+'/join',{method:'POST'});
      joinedSessionRef.current=session.session_id;setJoinedSessionId(session.session_id);setActiveJoinUrl(String(result.joinUrl||''));
      if(popup&&!popup.closed){popup.location.replace(String(result.joinUrl));}
      setNotice(popup&&!popup.closed
        ? t('Attendance is saved for this session. Keep this page open so you can leave cleanly when finished.','इस सत्र की उपस्थिति सहेज ली गई है। समाप्त होने पर सही तरीके से बाहर निकलने के लिए यह पेज खुला रखें।')
        : t('You are checked in. Use the secure meeting link below to enter the class.','आपकी उपस्थिति दर्ज है। कक्षा में प्रवेश करने के लिए नीचे दिया सुरक्षित लिंक खोलें।'));
      await load();
    }catch(e:any){
      if(popup&&!popup.closed)popup.close();
      if(e?.auth){router.replace('/login/student');return;}
      setError(e instanceof Error?e.message:t('This class cannot be joined right now.','अभी इस कक्षा में शामिल नहीं हो सकते।'));
    }finally{setBusy(false);}
  }

  async function openMeetingAgain(sessionId:number){
    setBusy(true);setError('');
    try{
      const result=await api('/api/v1/learning/live-classes/'+sessionId+'/join',{method:'POST'});
      window.open(String(result.joinUrl),'_blank','noopener,noreferrer');
      joinedSessionRef.current=sessionId;setJoinedSessionId(sessionId);setActiveJoinUrl(String(result.joinUrl||''));
      await load();
    }catch(e:any){setError(e instanceof Error?e.message:t('Meeting access is not available.','मीटिंग का एक्सेस अभी उपलब्ध नहीं है।'));}
    finally{setBusy(false);}
  }

  async function leaveClass(sessionId:number){
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/learning/live-classes/'+sessionId+'/leave',{method:'POST'});
      joinedSessionRef.current=null;setJoinedSessionId(null);setActiveJoinUrl(null);
      setNotice(t('You left the class. Your attendance duration has been saved.','आप कक्षा से बाहर आ गए हैं। आपकी उपस्थिति की अवधि सहेज ली गई है।'));
      await load();
    }catch(e:any){setError(e instanceof Error?e.message:t('Attendance could not be updated.','उपस्थिति अपडेट नहीं हो सकी।'));}
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
    const watchedSeconds=Math.max(0,Math.floor(watchRef.current.watchedSeconds));
    setSavingProgress(true);
    try{
      const result=await api('/api/v1/learning/recorded-classes/'+recording.recorded_class_id+'/progress',{
        method:'PUT',body:JSON.stringify({
          positionSeconds:safePosition,
          watchedSeconds,
          completed
        })
      });
      watchRef.current.lastSavedPosition=safePosition;
      setRecordings(rows=>rows.map(row=>row.recorded_class_id===recording.recorded_class_id?{
        ...row,last_position_seconds:safePosition,
        watched_seconds:Math.max(row.watched_seconds||0,watchedSeconds),
        completed:Boolean(result.completed)
      }:row));
      setSelectedRecording(old=>old&&old.recorded_class_id===recording.recorded_class_id?{
        ...old,last_position_seconds:safePosition,
        watched_seconds:Math.max(old.watched_seconds||0,watchedSeconds),
        completed:Boolean(result.completed)
      }:old);
    }catch(e:any){
      if(e?.auth)router.replace('/login/student');
      else setError(e instanceof Error?e.message:t('Playback progress could not be saved.','वीडियो देखने की प्रगति सहेजी नहीं जा सकी।'));
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
    <section className={styles.wrap}>
      {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.notice} role="status">{notice}</div>}
      {activeJoined&&<div className={styles.joinedBanner}><span>●</span><div><b>{t('You are checked in','आपकी उपस्थिति दर्ज है')}</b><small>{activeJoined.title} · {t('Attendance is being recorded while your session is active.','सत्र सक्रिय रहने तक उपस्थिति दर्ज की जा रही है।')}</small></div>{activeJoinUrl&&joinedSessionId===activeJoined.session_id&&<a className={styles.openSecureLink} href={activeJoinUrl} target="_blank" rel="noreferrer">{t('Open secure meeting ↗','सुरक्षित मीटिंग खोलें ↗')}</a>}<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void openMeetingAgain(activeJoined.session_id)}>{t('Refresh meeting link','मीटिंग लिंक रिफ्रेश करें')}</button><button type="button" className={styles.leaveButton} disabled={busy} onClick={()=>void leaveClass(activeJoined.session_id)}>{t('Leave & save attendance','बाहर निकलें और उपस्थिति सहेजें')}</button></div>}
      <div className={styles.toolbar}><div className={styles.tabs}><button type="button" className={tab==='live'?styles.activeTab:''} onClick={()=>setTab('live')}>{t('Live classes','लाइव कक्षाएँ')} <span>{liveTotal}</span></button><button type="button" className={tab==='recorded'?styles.activeTab:''} onClick={()=>setTab('recorded')}>{t('Recorded classes','रिकॉर्डेड कक्षाएँ')} <span>{recordingsTotal}</span></button></div><label>{t('Subject','विषय')}<select value={subjectFilter} onChange={e=>{setSubjectFilter(e.target.value);setLivePage(0);setRecordingPage(0)}}><option value="ALL">{t('All subjects','सभी विषय')}</option><option value="maths">{t('Mathematics','गणित')}</option><option value="science">{t('Science','विज्ञान')}</option></select></label></div>

      {tab==='live'?<>
        <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>{t('YOUR TIMETABLE','आपकी समय-सारणी')}</span><h2>{t('Live and scheduled sessions','लाइव और निर्धारित सत्र')}</h2><p>{t('Meeting links are released only after the access check and within the join window.','मीटिंग लिंक की जाँच के बाद और निर्धारित समय-सीमा में ही उपलब्ध होते हैं।')}</p></div><button type="button" className={styles.refreshButton} onClick={()=>void load()}>{t('↻ Refresh','↻ रिफ्रेश')}</button></div>
        {loading?<div className={styles.empty}>{t('Loading your live classes…','लाइव कक्षाएँ लोड हो रही हैं…')}</div>:filteredSessions.length===0?<div className={styles.empty}><span>◷</span><b>{t('No classes scheduled yet','अभी कोई कक्षा निर्धारित नहीं है')}</b><small>{t('Your teacher’s live sessions will appear here when published for your subject.','आपके विषय के लिए शिक्षक द्वारा प्रकाशित लाइव सत्र यहाँ दिखेंगे।')}</small></div>:<div className={styles.sessionGrid}>{filteredSessions.map(session=>{
          const start=new Date(session.starts_at).getTime(),end=new Date(session.ends_at).getTime();
          const ended=end<now||session.status==='COMPLETED';
          const canJoin=!ended&&['SCHEDULED','LIVE'].includes(session.status)&&start<=now+15*60*1000&&end>now;
          const joined=joinedSessionId===session.session_id||session.currently_joined;
          return <article key={session.session_id} className={styles.sessionCard}><div className={styles.sessionTop}><span className={styles.liveSymbol}>◷</span><span className={styles['status'+session.status]}>{session.status==='LIVE'?'● LIVE':session.status}</span></div><h3>{session.title}</h3><p>{session.subject_name}{session.chapter_name?' · '+session.chapter_name:''}</p><div className={styles.sessionTime}><span>{t('START','शुरू')}</span><b>{formatDate(session.starts_at)}</b><span>{t('END','समाप्त')}</span><b>{formatDate(session.ends_at)}</b></div>{session.description&&<p className={styles.description}>{session.description}</p>}<div className={styles.sessionMeta}><span>{t('Host','होस्ट')}: {session.host_staff_name||t('Teacher','शिक्षक')}</span><span>{session.provider.replaceAll('_',' ')}</span>{session.has_recording&&<span>{t('Recording available','रिकॉर्डिंग उपलब्ध')}</span>}</div>{joined?<div className={styles.joinedActions}><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void openMeetingAgain(session.session_id)}>{t('Open meeting ↗','मीटिंग खोलें ↗')}</button><button type="button" className={styles.leaveButton} disabled={busy} onClick={()=>void leaveClass(session.session_id)}>{t('Leave class','कक्षा छोड़ें')}</button></div>:canJoin?<button type="button" className={styles.primaryButton+' '+styles.fullButton} disabled={busy} onClick={()=>void joinClass(session)}>{busy?t('Checking access…','अनुमति जाँची जा रही है…'):session.status==='LIVE'?t('Join live class ↗','लाइव कक्षा में शामिल हों ↗'):t('Join class ↗','कक्षा में शामिल हों ↗')}</button>:<button type="button" className={styles.disabledButton} disabled>{ended?t('Session ended','सत्र समाप्त'):start>now+15*60*1000?t('Join opens ','शामिल होने का समय ')+formatDate(session.starts_at):t('Not available to join','अभी शामिल नहीं हो सकते')}</button>}{session.has_recording&&<button type="button" className={styles.textButton} onClick={()=>{setTab('recorded');}}>{t('View class recordings →','कक्षा की रिकॉर्डिंग देखें →')}</button>}</article>;
        })}</div>}
        <PremiumPagination page={livePage} total={liveTotal} pageSize={livePageSize} onPageChange={setLivePage}
          onPageSizeChange={size=>{setLivePageSize(size);setLivePage(0);}} itemLabel={t('live classes','लाइव कक्षाएँ')} loading={loading} />
      </>:<>
        <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>{t('WATCH AT YOUR PACE','अपनी गति से देखें')}</span><h2>{t('Recorded classes','रिकॉर्डेड कक्षाएँ')}</h2><p>{t('Videos stream from private storage after each access check. Your place is saved as you watch.','वीडियो की अनुमति जाँचने के बाद निजी स्टोरेज से चलाए जाते हैं। देखते समय आपकी जगह सहेजी जाती है।')}</p></div><button type="button" className={styles.refreshButton} onClick={()=>void load()}>↻ Refresh</button></div>
        {loading?<div className={styles.empty}>{t('Loading your recorded classes…','रिकॉर्डेड कक्षाएँ लोड हो रही हैं…')}</div>:filteredRecordings.length===0?<div className={styles.empty}><span>▶</span><b>{t('No recorded classes available yet','अभी कोई रिकॉर्डेड कक्षा उपलब्ध नहीं है')}</b><small>{t('Published class recordings for your enrolled subjects will appear here.','आपके नामांकित विषयों की प्रकाशित रिकॉर्डिंग यहाँ दिखेंगी।')}</small></div>:<div className={styles.recordingGrid}>{filteredRecordings.map(recording=><article key={recording.recorded_class_id} className={styles.recordingCard}><div className={styles.videoPoster}><span>▶</span><small>{durationText(recording.duration_seconds)}</small>{recording.completed&&<b>✓ COMPLETED</b>}</div><div className={styles.recordingBody}><span className={styles.subjectTag}>{recording.subject_name}</span><h3>{recording.title}</h3><p>{recording.chapter_name||recording.session_title||'Recorded lesson'}{recording.session_starts_at?' · '+formatDate(recording.session_starts_at):''}</p>{recording.description&&<p className={styles.description}>{recording.description}</p>}<div className={styles.recordingProgress}><div><span>{recording.completed?t('Completed','पूरा हुआ'):recording.last_position_seconds>0?t('Continue watching','आगे देखें'):t('Not started','शुरू नहीं हुआ')}</span><b>{percent(recording)}%</b></div><i><b style={{width:percent(recording)+'%'}}/></i></div><div className={styles.recordingMeta}><span>{formatSize(recording.file_size_bytes)}</span><span>{formatDate(recording.created_at)}</span></div><button type="button" className={styles.primaryButton+' '+styles.fullButton} onClick={()=>openRecording(recording)}>{recording.last_position_seconds>0&&!recording.completed?t('Resume watching','देखना जारी रखें'):t('Watch online','ऑनलाइन देखें')} ↗</button></div></article>)}</div>}
        <PremiumPagination page={recordingPage} total={recordingsTotal} pageSize={recordingPageSize} onPageChange={setRecordingPage}
          onPageSizeChange={size=>{setRecordingPageSize(size);setRecordingPage(0);}} itemLabel={t('recordings','रिकॉर्डिंग')} loading={loading} />
      </>}
      <div className={styles.privacyNote}><span>🔒</span><p><b>{t('Protected class access.','सुरक्षित कक्षा एक्सेस।')}</b> {t('Meeting links and video file paths are never listed publicly. QuantaEdge verifies your account and active subject enrollment for every join and playback request. A browser cannot fully prevent screen recording or capture of content already displayed.','मीटिंग लिंक और वीडियो फ़ाइल पथ सार्वजनिक नहीं किए जाते। हर बार शामिल होने या प्लेबैक से पहले QuantaEdge आपके खाते और सक्रिय विषय नामांकन की जाँच करता है। ब्राउज़र स्क्रीन पर दिखाई गई सामग्री की रिकॉर्डिंग या कैप्चर को पूरी तरह रोक नहीं सकता।')}</p></div>
    </section>

    {selectedRecording&&<div className={styles.playerBackdrop} role="presentation" onMouseDown={e=>{if(e.target===e.currentTarget)closeRecording();}}>
      <section className={styles.playerModal} role="dialog" aria-modal="true" aria-labelledby="class-video-title">
        <header><div><span className={styles.eyebrow}>{t('PRIVATE CLASS PLAYER','निजी कक्षा प्लेयर')}</span><h2 id="class-video-title">{selectedRecording.title}</h2><p>{selectedRecording.chapter_name||selectedRecording.session_title||selectedRecording.subject_name}</p></div><button type="button" aria-label={t('Close video player','वीडियो प्लेयर बंद करें')} onClick={closeRecording}>×</button></header>
        <div className={styles.videoFrame}><video ref={videoRef} key={selectedRecording.recorded_class_id} controls playsInline preload="metadata" controlsList="nodownload noplaybackrate" disablePictureInPicture src={'/api/v1/learning/recorded-classes/'+selectedRecording.recorded_class_id+'/stream'} onLoadedMetadata={e=>{const video=e.currentTarget;const start=Number(selectedRecording.last_position_seconds||0);if(start>0&&start<(video.duration||Infinity)){video.currentTime=start;}watchRef.current.lastMediaTime=video.currentTime;watchRef.current.lastSavedPosition=start;}} onTimeUpdate={handleTimeUpdate} onPause={e=>void persistPlayback(selectedRecording,e.currentTarget.currentTime,e.currentTarget.ended)} onEnded={e=>void persistPlayback(selectedRecording,e.currentTarget.currentTime,true)} onError={()=>setError(t('This recording could not be played. Try refreshing the library or notify your teacher.','यह रिकॉर्डिंग नहीं चल सकी। लाइब्रेरी रिफ्रेश करें या शिक्षक को सूचित करें।'))}/></div>
        <footer><div><b>{savingProgress?t('Saving playback progress…','वीडियो प्रगति सहेजी जा रही है…'):selectedRecording.completed?t('Completed','पूरा हुआ'):t('Progress is saved automatically','प्रगति अपने-आप सहेजी जाती है')}</b><small>{t('Position ','स्थिति ')}{durationText(Math.floor(selectedRecording.last_position_seconds||0))}{selectedRecording.duration_seconds?' '+t('of','में से')+' '+durationText(selectedRecording.duration_seconds):''}</small></div><button type="button" className={styles.secondaryButton} onClick={closeRecording}>{t('Close player','प्लेयर बंद करें')}</button></footer>
      </section>
    </div>}
  </main>;
}
