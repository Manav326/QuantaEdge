'use client';

import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
import Link from 'next/link';
import PremiumPagination from '../../components/PremiumPagination';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale } from '../../components/LanguageProvider';
import styles from './Classes.module.css';

type LiveClass = {
  session_id:number;title:string;description?:string;provider:string;youtube_video_id?:string|null;starts_at:string;ends_at:string;status:string;
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
  const [activeVideoId,setActiveVideoId]=useState<string|null>(null);
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
      if(active&&!joinedSessionRef.current){joinedSessionRef.current=active.session_id;setJoinedSessionId(active.session_id);setActiveVideoId(active.youtube_video_id||null);}
    }catch(e:any){
      if(e?.auth){router.replace('/login/student');return;}
      setError(e instanceof Error?e.message:translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.your.class.library.could.not.be.loaded.ef90dceb'));
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
    const popup=session.provider==='YOUTUBE_LIVE'?null:window.open('about:blank','_blank');
    if(popup){
      try{popup.opener=null;popup.document.title=translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.opening.secure.class.3af0c2c8');popup.document.body.textContent=translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.checking.your.class.access.e8457b8f');}catch{}
    }
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/learning/live-classes/'+session.session_id+'/join',{method:'POST'});
      joinedSessionRef.current=session.session_id;setJoinedSessionId(session.session_id);setActiveJoinUrl(String(result.joinUrl||''));setActiveVideoId(String(result.youtubeVideoId||'')||null);
      if(popup&&!popup.closed&&result.joinUrl){popup.location.replace(String(result.joinUrl));}
      setNotice(result.youtubeVideoId?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.attendance.is.saved.the.youtube.broadc.1ea76529'):popup&&!popup.closed
        ? translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.attendance.is.saved.for.this.session.k.ba5a0623')
        : translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.you.are.checked.in.use.the.secure.meet.01b4e99f'));
      await load();
    }catch(e:any){
      if(popup&&!popup.closed)popup.close();
      if(e?.auth){router.replace('/login/student');return;}
      setError(e instanceof Error?e.message:translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.this.class.cannot.be.joined.right.now.d6d56808'));
    }finally{setBusy(false);}
  }

  async function openMeetingAgain(sessionId:number){
    setBusy(true);setError('');
    try{
      const result=await api('/api/v1/learning/live-classes/'+sessionId+'/join',{method:'POST'});
      if(result.joinUrl)window.open(String(result.joinUrl),'_blank','noopener,noreferrer');
      joinedSessionRef.current=sessionId;setJoinedSessionId(sessionId);setActiveJoinUrl(String(result.joinUrl||''));setActiveVideoId(String(result.youtubeVideoId||'')||null);
      await load();
    }catch(e:any){setError(e instanceof Error?e.message:translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.meeting.access.is.not.available.8160dfb5'));}
    finally{setBusy(false);}
  }

  async function leaveClass(sessionId:number){
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/learning/live-classes/'+sessionId+'/leave',{method:'POST'});
      joinedSessionRef.current=null;setJoinedSessionId(null);setActiveJoinUrl(null);setActiveVideoId(null);
      setNotice(translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.you.left.the.class.your.attendance.dur.039507eb'));
      await load();
    }catch(e:any){setError(e instanceof Error?e.message:translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.attendance.could.not.be.updated.9b97c093'));}
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
      else setError(e instanceof Error?e.message:translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.playback.progress.could.not.be.saved.091263d8'));
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
      {activeJoined&&<div className={styles.joinedBanner}><span>●</span><div><b>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.you.are.checked.in.7ace0a90')}</b><small>{activeJoined.title} · {translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.attendance.is.being.recorded.while.you.96ff7a5a')}</small></div>{activeJoinUrl&&joinedSessionId===activeJoined.session_id&&<a className={styles.openSecureLink} href={activeJoinUrl} target="_blank" rel="noreferrer">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.open.secure.meeting.569ad001')}</a>}{activeJoined.provider!=='YOUTUBE_LIVE'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void openMeetingAgain(activeJoined.session_id)}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.refresh.meeting.link.64ac8938')}</button>}<button type="button" className={styles.leaveButton} disabled={busy} onClick={()=>void leaveClass(activeJoined.session_id)}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.leave.save.attendance.a503253e')}</button></div>}
      {activeJoined&&activeJoined.provider==='YOUTUBE_LIVE'&&activeVideoId&&<section className={styles.videoFrame} aria-label={translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.live.broadcast.0729c57a')}><iframe title={activeJoined.title} src={'https://www.youtube-nocookie.com/embed/'+encodeURIComponent(activeVideoId)+'?autoplay=1'} style={{width:'100%',aspectRatio:'16 / 9',border:0}} allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowFullScreen referrerPolicy="strict-origin-when-cross-origin"/></section>}
      <div className={styles.toolbar}><div className={styles.tabs}><button type="button" className={tab==='live'?styles.activeTab:''} onClick={()=>setTab('live')}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.live.classes.82bc4e33')} <span>{liveTotal}</span></button><button type="button" className={tab==='recorded'?styles.activeTab:''} onClick={()=>setTab('recorded')}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.recorded.classes.733f426a')} <span>{recordingsTotal}</span></button></div><label>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.subject.0af41287')}<select value={subjectFilter} onChange={e=>{setSubjectFilter(e.target.value);setLivePage(0);setRecordingPage(0)}}><option value="ALL">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.all.subjects.7895d918')}</option><option value="maths">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.mathematics.4cf6be1a')}</option><option value="science">{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.science.f85a10aa')}</option></select></label></div>

      {tab==='live'?<>
        <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.your.timetable.15fa760f')}</span><h2>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.live.and.scheduled.sessions.16cb640d')}</h2><p>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.meeting.links.are.released.only.after..814f18cc')}</p></div><button type="button" className={styles.refreshButton} onClick={()=>void load()}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.refresh.e9337860')}</button></div>
        {loading?<div className={styles.empty}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.loading.your.live.classes.a6f72a7c')}</div>:filteredSessions.length===0?<div className={styles.empty}><span>◷</span><b>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.no.classes.scheduled.yet.9205810d')}</b><small>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.your.teacher.s.live.sessions.will.appe.bdac74da')}</small></div>:<div className={styles.sessionGrid}>{filteredSessions.map(session=>{
          const start=new Date(session.starts_at).getTime(),end=new Date(session.ends_at).getTime();
          const ended=end<now||session.status==='COMPLETED';
          const canJoin=!ended&&['SCHEDULED','LIVE'].includes(session.status)&&start<=now+15*60*1000&&end>now;
          const joined=joinedSessionId===session.session_id||session.currently_joined;
          return <article key={session.session_id} className={styles.sessionCard}><div className={styles.sessionTop}><span className={styles.liveSymbol}>◷</span><span className={styles['status'+session.status]}>{session.status==='LIVE'?'● LIVE':session.status}</span></div><h3>{session.title}</h3><p>{session.subject_name}{session.chapter_name?' · '+session.chapter_name:''}</p><div className={styles.sessionTime}><span>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.start.06a5086a')}</span><b>{formatDate(session.starts_at)}</b><span>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.end.2b49ae79')}</span><b>{formatDate(session.ends_at)}</b></div>{session.description&&<p className={styles.description}>{session.description}</p>}<div className={styles.sessionMeta}><span>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.host.1fcf2d4b')}: {session.host_staff_name||translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.teacher.99596fa6')}</span><span>{session.provider.replaceAll('_',' ')}</span>{session.has_recording&&<span>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.recording.available.9a1efcd9')}</span>}</div>{joined?<div className={styles.joinedActions}><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void openMeetingAgain(session.session_id)}>{session.provider==='YOUTUBE_LIVE'?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.show.broadcast.63e430cc'):translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.open.meeting.38f53565')}</button><button type="button" className={styles.leaveButton} disabled={busy} onClick={()=>void leaveClass(session.session_id)}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.leave.class.f1fb2366')}</button></div>:canJoin?<button type="button" className={styles.primaryButton+' '+styles.fullButton} disabled={busy} onClick={()=>void joinClass(session)}>{busy?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.checking.access.2ede368f'):session.provider==='YOUTUBE_LIVE'?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.watch.live.broadcast.a78a181f'):session.status==='LIVE'?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.join.live.class.f38a8797'):translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.join.class.23ef83b7')}</button>:<button type="button" className={styles.disabledButton} disabled>{ended?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.session.ended.60939415'):start>now+15*60*1000?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.join.opens.94e12768')+formatDate(session.starts_at):translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.not.available.to.join.6abc1852')}</button>}{session.has_recording&&<button type="button" className={styles.textButton} onClick={()=>{setTab('recorded');}}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.view.class.recordings.7f691c40')}</button>}</article>;
        })}</div>}
        <PremiumPagination page={livePage} total={liveTotal} pageSize={livePageSize} onPageChange={setLivePage}
          onPageSizeChange={size=>{setLivePageSize(size);setLivePage(0);}} itemLabel={translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.live.classes.af252253')} loading={loading} />
      </>:<>
        <div className={styles.sectionHeading}><div><span className={styles.eyebrow}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.watch.at.your.pace.96149ee9')}</span><h2>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.recorded.classes.733f426a')}</h2><p>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.videos.stream.from.private.storage.aft.e66dbc54')}</p></div><button type="button" className={styles.refreshButton} onClick={()=>void load()}>↻ Refresh</button></div>
        {loading?<div className={styles.empty}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.loading.your.recorded.classes.ff88278f')}</div>:filteredRecordings.length===0?<div className={styles.empty}><span>▶</span><b>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.no.recorded.classes.available.yet.53d499a5')}</b><small>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.published.class.recordings.for.your.en.0866397a')}</small></div>:<div className={styles.recordingGrid}>{filteredRecordings.map(recording=><article key={recording.recorded_class_id} className={styles.recordingCard}><div className={styles.videoPoster}><span>▶</span><small>{durationText(recording.duration_seconds)}</small>{recording.completed&&<b>✓ COMPLETED</b>}</div><div className={styles.recordingBody}><span className={styles.subjectTag}>{recording.subject_name}</span><h3>{recording.title}</h3><p>{recording.chapter_name||recording.session_title||'Recorded lesson'}{recording.session_starts_at?' · '+formatDate(recording.session_starts_at):''}</p>{recording.description&&<p className={styles.description}>{recording.description}</p>}<div className={styles.recordingProgress}><div><span>{recording.completed?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.completed.1a8e9ec4'):recording.last_position_seconds>0?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.continue.watching.54b53715'):translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.not.started.c7474e3d')}</span><b>{percent(recording)}%</b></div><i><b style={{width:percent(recording)+'%'}}/></i></div><div className={styles.recordingMeta}><span>{formatSize(recording.file_size_bytes)}</span><span>{formatDate(recording.created_at)}</span></div><button type="button" className={styles.primaryButton+' '+styles.fullButton} onClick={()=>openRecording(recording)}>{recording.last_position_seconds>0&&!recording.completed?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.resume.watching.54926979'):translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.watch.online.f9af89eb')} ↗</button></div></article>)}</div>}
        <PremiumPagination page={recordingPage} total={recordingsTotal} pageSize={recordingPageSize} onPageChange={setRecordingPage}
          onPageSizeChange={size=>{setRecordingPageSize(size);setRecordingPage(0);}} itemLabel={translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.recordings.081cdae6')} loading={loading} />
      </>}
      <div className={styles.privacyNote}><span>🔒</span><p><b>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.protected.class.access.e1bd23e6')}</b> {translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.meeting.links.and.video.file.paths.are.6b5c8429')}</p></div>
    </section>

    {selectedRecording&&<div className={styles.playerBackdrop} role="presentation" onMouseDown={e=>{if(e.target===e.currentTarget)closeRecording();}}>
      <section className={styles.playerModal} role="dialog" aria-modal="true" aria-labelledby="class-video-title">
        <header><div><span className={styles.eyebrow}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.private.class.player.8257500f')}</span><h2 id="class-video-title">{selectedRecording.title}</h2><p>{selectedRecording.chapter_name||selectedRecording.session_title||selectedRecording.subject_name}</p></div><button type="button" aria-label={translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.close.video.player.067f7815')} onClick={closeRecording}>×</button></header>
        <div className={styles.videoFrame}><video ref={videoRef} key={selectedRecording.recorded_class_id} controls playsInline preload="metadata" controlsList="nodownload noplaybackrate" disablePictureInPicture src={'/api/v1/learning/recorded-classes/'+selectedRecording.recorded_class_id+'/stream'} onLoadedMetadata={e=>{const video=e.currentTarget;const start=Number(selectedRecording.last_position_seconds||0);if(start>0&&start<(video.duration||Infinity)){video.currentTime=start;}watchRef.current.lastMediaTime=video.currentTime;watchRef.current.lastSavedPosition=start;}} onTimeUpdate={handleTimeUpdate} onPause={e=>void persistPlayback(selectedRecording,e.currentTarget.currentTime,e.currentTarget.ended)} onEnded={e=>void persistPlayback(selectedRecording,e.currentTarget.currentTime,true)} onError={()=>setError(translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.this.recording.could.not.be.played.try.5697bd4a'))}/></div>
        <footer><div><b>{savingProgress?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.saving.playback.progress.864a86b7'):selectedRecording.completed?translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.completed.1a8e9ec4'):translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.progress.is.saved.automatically.628acef7')}</b><small>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.position.2d412552')}{durationText(Math.floor(selectedRecording.last_position_seconds||0))}{selectedRecording.duration_seconds?' '+translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.of.ffc7a9ee')+' '+durationText(selectedRecording.duration_seconds):''}</small></div><button type="button" className={styles.secondaryButton} onClick={closeRecording}>{translateUi(locale === 'english' ? 'english' : 'hinglish', 'legacy.web.close.player.f56f02e7')}</button></footer>
      </section>
    </div>}
  </main>;
}
