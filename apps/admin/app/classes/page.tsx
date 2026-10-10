'use client';

import AdminSidebar from '../components/AdminSidebar';
import { useCallback, useEffect, useState } from 'react';
import styles from './Classes.module.css';

type Chapter = {chapter_id:number;chapter_code:string;chapter_name:string};
type Staff = {staff_id:number;display_name:string;role:string};
type LiveSession = {session_id:number;title:string;description?:string;provider:string;starts_at:string;ends_at:string;status:string;host_staff_id?:number|null;host_staff_name?:string|null;class_code:string;subject_code:string;subject_name:string;chapter_id?:number|null;chapter_name?:string|null;attendee_count?:number;published_recording_count?:number};
type Recording = {recorded_class_id:number;title:string;description?:string;original_filename:string;media_type:string;file_size_bytes:number;sha256:string;duration_seconds?:number|null;status:string;class_session_id?:number|null;session_title?:string|null;class_code:string;subject_code:string;subject_name:string;chapter_id?:number|null;chapter_name?:string|null};

async function api(url:string,init:RequestInit={}) {
  const headers=new Headers(init.headers||{});
  if(init.body && !(init.body instanceof FormData))headers.set('Content-Type','application/json');
  const response=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();let body:any={};
  try{body=raw?JSON.parse(raw):{}}catch{body={message:raw}}
  if(!response.ok)throw new Error(body.detail||body.message||body.error||('Request failed ('+response.status+')'));
  return body;
}
function localTime(offsetMinutes:number){
  const d=new Date(Date.now()+offsetMinutes*60000);
  return new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);
}
function dateText(value?:string|null){
  if(!value)return '—';
  const date=new Date(value);
  return Number.isNaN(date.getTime())?'—':date.toLocaleString();
}
function formatSize(size:number){
  return size>=1024*1024?(size/(1024*1024)).toFixed(1)+' MB':Math.max(1,Math.round(size/1024))+' KB';
}
function durationText(seconds?:number|null){
  if(!seconds)return 'Duration not set';
  const h=Math.floor(seconds/3600),m=Math.floor((seconds%3600)/60);
  return h? h+'h '+m+'m':m+' min';
}

export default function ClassesPage(){
  const [tab,setTab]=useState<'live'|'recorded'>('live');
  const [classCode,setClassCode]=useState('6');
  const [subjectCode,setSubjectCode]=useState('maths');
  const [statusFilter,setStatusFilter]=useState('ALL');
  const [sessions,setSessions]=useState<LiveSession[]>([]);
  const [recordings,setRecordings]=useState<Recording[]>([]);
  const [chapters,setChapters]=useState<Chapter[]>([]);
  const [staff,setStaff]=useState<Staff[]>([]);
  const [title,setTitle]=useState('');
  const [description,setDescription]=useState('');
  const [provider,setProvider]=useState('GOOGLE_MEET');
  const [joinUrl,setJoinUrl]=useState('');
  const [startAt,setStartAt]=useState(()=>localTime(60));
  const [endAt,setEndAt]=useState(()=>localTime(120));
  const [hostStaffId,setHostStaffId]=useState('');
  const [chapterId,setChapterId]=useState('');
  const [recordingTitle,setRecordingTitle]=useState('');
  const [recordingDescription,setRecordingDescription]=useState('');
  const [recordingChapterId,setRecordingChapterId]=useState('');
  const [classSessionId,setClassSessionId]=useState('');
  const [durationSeconds,setDurationSeconds]=useState('');
  const [file,setFile]=useState<File|null>(null);
  const [busy,setBusy]=useState(false);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const sessionStatus=['SCHEDULED','LIVE','COMPLETED','CANCELLED'].includes(statusFilter)?statusFilter:'ALL';
      const recordingStatus=['DRAFT','PUBLISHED','ARCHIVED'].includes(statusFilter)?statusFilter:'ALL';
      const sessionParams=new URLSearchParams({classCode,subjectCode,status:sessionStatus});
      const recordingParams=new URLSearchParams({classCode,subjectCode,status:recordingStatus});
      const chapterParams=new URLSearchParams({classCode,subjectCode});
      const [sessionRows,recordingRows,contentRows,staffRows]=await Promise.all([
        api('/api/v1/admin/live-classes?'+sessionParams.toString()),
        api('/api/v1/admin/recorded-classes?'+recordingParams.toString()),
        api('/api/v1/admin/class-chapters?'+chapterParams.toString()),
        api('/api/v1/admin/class-staff')
      ]);
      setSessions(Array.isArray(sessionRows)?sessionRows:[]);
      setRecordings(Array.isArray(recordingRows)?recordingRows:[]);
      const chapterMap=new Map<number,Chapter>();
      (Array.isArray(contentRows)?contentRows:[]).forEach((row:any)=>{
        const id=Number(row.chapter_id);
        if(id>0&&row.chapter_name)chapterMap.set(id,{chapter_id:id,chapter_code:String(row.chapter_code||''),chapter_name:String(row.chapter_name)});
      });
      setChapters([...chapterMap.values()]);
      setStaff(Array.isArray(staffRows)?staffRows:[]);
      setChapterId(value=>value&&chapterMap.has(Number(value))?value:'');
      setRecordingChapterId(value=>value&&chapterMap.has(Number(value))?value:'');
    }catch(e){setError(e instanceof Error?e.message:'Live classes and recordings could not be loaded.');}
    finally{setLoading(false);}
  },[classCode,subjectCode,statusFilter]);
  useEffect(()=>{void load();},[load]);

  async function createSession(){
    if(!title.trim()||!joinUrl.trim()||!startAt||!endAt){setError('Complete the live class title, secure meeting URL, start time, and end time.');return;}
    if(new Date(endAt).getTime()<=new Date(startAt).getTime()){setError('End time must be after the start time.');return;}
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/admin/live-classes',{method:'POST',body:JSON.stringify({
        title:title.trim(),description:description.trim(),classCode,subjectCode,
        chapterId:chapterId?Number(chapterId):null,provider,joinUrl:joinUrl.trim(),
        startsAt:new Date(startAt).toISOString(),endsAt:new Date(endAt).toISOString(),
        hostStaffId:hostStaffId?Number(hostStaffId):null
      })});
      setTitle('');setDescription('');setJoinUrl('');setHostStaffId('');setChapterId('');
      setNotice('Live class scheduled. Its private join link is returned to enrolled students only during the join window.');
      await load();
    }catch(e){setError(e instanceof Error?e.message:'The live class could not be scheduled.');}
    finally{setBusy(false);}
  }
  async function changeSessionStatus(session:LiveSession,status:'LIVE'|'COMPLETED'|'CANCELLED'|'SCHEDULED'){
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/admin/live-classes/'+session.session_id+'/status',{method:'PATCH',body:JSON.stringify({status})});
      setNotice('“'+session.title+'” is now '+status.toLowerCase()+'.');
      await load();
    }catch(e){setError(e instanceof Error?e.message:'The live class status could not be changed.');}
    finally{setBusy(false);}
  }
  async function uploadRecording(){
    if(!file){setError('Choose a recording file first.');return;}
    if(!recordingTitle.trim()){setError('Enter a title students will recognize.');return;}
    setBusy(true);setError('');setNotice('');
    try{
      const form=new FormData();
      form.append('file',file);form.append('title',recordingTitle.trim());
      form.append('classCode',classCode);form.append('subjectCode',subjectCode);
      if(recordingDescription.trim())form.append('description',recordingDescription.trim());
      if(recordingChapterId)form.append('chapterId',recordingChapterId);
      if(classSessionId)form.append('classSessionId',classSessionId);
      if(durationSeconds)form.append('durationSeconds',durationSeconds);
      const result=await api('/api/v1/admin/recorded-classes',{method:'POST',body:form});
      setRecordingTitle('');setRecordingDescription('');setRecordingChapterId('');setClassSessionId('');setDurationSeconds('');setFile(null);
      const input=document.getElementById('qe-recorded-class-file') as HTMLInputElement|null;if(input)input.value='';
      setNotice('Recording stored privately as a draft ('+formatSize(Number(result.file_size_bytes||file.size))+'). Publish it after checking the subject/chapter and playback.');
      await load();
    }catch(e){setError(e instanceof Error?e.message:'Recording upload failed.');}
    finally{setBusy(false);}
  }
  async function changeRecordingStatus(recording:Recording,status:'PUBLISHED'|'ARCHIVED'|'DRAFT'){
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/admin/recorded-classes/'+recording.recorded_class_id+'/status',{method:'PATCH',body:JSON.stringify({status})});
      setNotice('“'+recording.title+'” is now '+status.toLowerCase()+'.');
      await load();
    }catch(e){setError(e instanceof Error?e.message:'The recording status could not be changed.');}
    finally{setBusy(false);}
  }
  const subjectLabel=subjectCode==='maths'?'Mathematics':'Science';
  const liveCount=sessions.filter(s=>s.status==='LIVE').length;
  const upcomingCount=sessions.filter(s=>s.status==='SCHEDULED').length;
  const reviewCount=recordings.filter(r=>r.status==='DRAFT').length;

  return <main className="admin-shell">
    <AdminSidebar active="classes" variant="content"/>
    <section className={'admin-main '+styles.page}>
      <header className="admin-top"><div><span className="admin-kicker">QUANTAEDGE LEARNING / CLASS OPERATIONS</span><h1>Live & recorded classes</h1><p>Schedule classes, issue private meeting access, and manage protected class recordings.</p></div><button type="button" className="admin-refresh" onClick={()=>void load()} title="Refresh classes">↻</button></header>
      <section className={styles.hero}><div className={styles.heroIcon}>◷</div><div><span>CLASSROOM CONTROL CENTRE</span><h2>One schedule. Protected access. Saved attendance.</h2><p>Meeting links stay private until a student is eligible to join. Uploaded recordings are stored outside public assets and streamed only after checking enrollment.</p></div><div className={styles.heroStats}><strong>{liveCount}</strong><small>Live now</small><strong>{upcomingCount}</strong><small>Upcoming</small><strong>{reviewCount}</strong><small>Draft recordings</small></div></section>
      {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.success} role="status">{notice}</div>}
      <div className={styles.trackFilters}>
        <label>Class<select value={classCode} onChange={e=>setClassCode(e.target.value)}><option value="6">Class 6</option><option value="7">Class 7</option><option value="8">Class 8</option></select></label>
        <label>Subject<select value={subjectCode} onChange={e=>setSubjectCode(e.target.value)}><option value="maths">Mathematics</option><option value="science">Science</option></select></label>
        <label>Status view<select value={statusFilter} onChange={e=>setStatusFilter(e.target.value)}><option value="ALL">All statuses</option><option value="SCHEDULED">Scheduled</option><option value="LIVE">Live now</option><option value="COMPLETED">Completed</option><option value="CANCELLED">Cancelled</option><option value="DRAFT">Draft recordings</option><option value="PUBLISHED">Published recordings</option><option value="ARCHIVED">Archived recordings</option></select></label>
      </div>
      <div className={styles.tabs}><button type="button" className={tab==='live'?styles.activeTab:''} onClick={()=>setTab('live')}>Live sessions <span>{sessions.length}</span></button><button type="button" className={tab==='recorded'?styles.activeTab:''} onClick={()=>setTab('recorded')}>Recordings <span>{recordings.length}</span></button></div>

      {tab==='live'?<>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>STEP 01</span><h2>Schedule a live class</h2><p>Use a meeting service URL over HTTPS. The student catalog never exposes the join URL; it is returned only from the authenticated join action during the allowed window.</p></div><b>01</b></div>
          <div className={styles.fields}>
            <label className={styles.wide}>Class title<input value={title} onChange={e=>setTitle(e.target.value)} placeholder="e.g. Live revision — Fractions and decimals"/></label>
            <label>Meeting provider<select value={provider} onChange={e=>setProvider(e.target.value)}><option value="GOOGLE_MEET">Google Meet</option><option value="ZOOM">Zoom</option><option value="MICROSOFT_TEAMS">Microsoft Teams</option><option value="CUSTOM">Other secure meeting</option></select></label>
            <label>Host staff (optional)<select value={hostStaffId} onChange={e=>setHostStaffId(e.target.value)}><option value="">Select host…</option>{staff.map(person=><option key={person.staff_id} value={person.staff_id}>{person.display_name} · {person.role}</option>)}</select></label>
            <label className={styles.wide}>Private meeting URL<input type="url" value={joinUrl} onChange={e=>setJoinUrl(e.target.value)} placeholder="https://meet.google.com/…"/></label>
            <label>Start date and time<input type="datetime-local" value={startAt} onChange={e=>setStartAt(e.target.value)}/></label>
            <label>End date and time<input type="datetime-local" value={endAt} onChange={e=>setEndAt(e.target.value)}/></label>
            <label>Chapter (optional)<select value={chapterId} onChange={e=>setChapterId(e.target.value)}><option value="">Whole subject</option>{chapters.map(ch=><option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}</select></label>
            <label className={styles.wide}>Student-facing details<textarea rows={3} value={description} onChange={e=>setDescription(e.target.value)} placeholder="What will students learn or prepare for?"/></label>
          </div>
          <div className={styles.submitBar}><p>Class  {classCode} · {subjectLabel}. Enrolled students can join 15 minutes before the start time through the class page.</p><button type="button" className={styles.primaryButton} disabled={busy||!title.trim()||!joinUrl.trim()} onClick={()=>void createSession()}>{busy?'Saving…':'Schedule class'}</button></div>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>SESSION MANAGEMENT</span><h2>Scheduled and completed classes</h2><p>Change a session to Live when the meeting opens, then mark it completed when finished. Attendance records are retained.</p></div></div>
          {loading?<p className={styles.empty}>Loading classes…</p>:sessions.length===0?<div className={styles.empty}><b>No live class records in this view</b><span>Schedule a session above to make it available to enrolled students.</span></div>:<div className={styles.list}>{sessions.map(session=><article className={styles.card} key={session.session_id}><span className={styles.sessionIcon}>◷</span><div className={styles.cardBody}><div className={styles.cardTitle}><h3>{session.title}</h3><span className={styles['status'+session.status]}>{session.status}</span></div><p>{session.class_code} · {session.subject_name}{session.chapter_name?' · '+session.chapter_name:''}</p><small>{dateText(session.starts_at)} – {dateText(session.ends_at)}</small><small>Provider: {session.provider.replaceAll('_',' ')} · Host: {session.host_staff_name||'Not assigned'}</small><small>{session.attendee_count||0} attendees · {session.published_recording_count||0} published recording(s)</small>{session.description&&<small>{session.description}</small>}</div><div className={styles.actions}>{session.status==='SCHEDULED'&&<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void changeSessionStatus(session,'LIVE')}>Mark live</button>}{session.status==='LIVE'&&<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void changeSessionStatus(session,'COMPLETED')}>Complete</button>}{['SCHEDULED','LIVE'].includes(session.status)&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeSessionStatus(session,'CANCELLED')}>Cancel</button>}{session.status==='CANCELLED'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeSessionStatus(session,'SCHEDULED')}>Restore</button>}</div></article>)}</div>}
        </section>
      </>:<>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>STEP 01</span><h2>Add a recorded class</h2><p>Upload an MP4, WebM or QuickTime file up to 500 MB. The file is placed in private persistent storage with a generated key, not under the public web folder.</p></div><b>01</b></div>
          <div className={styles.fields}>
            <label className={styles.wide}>Recording title<input value={recordingTitle} onChange={e=>setRecordingTitle(e.target.value)} placeholder="e.g. Fractions — worked examples and recap"/></label>
            <label>Chapter (optional)<select value={recordingChapterId} onChange={e=>setRecordingChapterId(e.target.value)}><option value="">Whole subject</option>{chapters.map(ch=><option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}</select></label>
            <label>Link to live session (optional)<select value={classSessionId} onChange={e=>setClassSessionId(e.target.value)}><option value="">No linked live session</option>{sessions.filter(s=>s.status!=='CANCELLED'&&s.class_code===classCode&&s.subject_code===subjectCode).map(s=><option key={s.session_id} value={s.session_id}>{dateText(s.starts_at)} · {s.title}</option>)}</select></label>
            <label>Duration (seconds, optional)<input type="number" min="1" max="86400" value={durationSeconds} onChange={e=>setDurationSeconds(e.target.value)} placeholder="e.g. 2700"/></label>
            <label className={styles.wide}>Description<textarea rows={3} value={recordingDescription} onChange={e=>setRecordingDescription(e.target.value)} placeholder="Topic coverage, notes or related class details"/></label>
            <label className={styles.fileInput} htmlFor="qe-recorded-class-file"><span>Choose recording</span><input id="qe-recorded-class-file" type="file" accept="video/mp4,video/webm,video/quicktime,.mp4,.webm,.mov" onChange={e=>setFile(e.target.files?.[0]||null)}/><small>{file?file.name+' · '+formatSize(file.size):'No file selected'}</small></label>
          </div>
          <div className={styles.submitBar}><p>New uploads remain Draft until reviewed and published. Playback is authorized for the enrolled subject and uses private byte-range streaming.</p><button type="button" className={styles.primaryButton} disabled={busy||!file||!recordingTitle.trim()} onClick={()=>void uploadRecording()}>{busy?'Uploading…':'Upload private recording'}</button></div>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>RECORDING GOVERNANCE</span><h2>Recorded class library</h2><p>Publish only after verifying the title, chapter mapping, and playback in the student experience.</p></div></div>
          {loading?<p className={styles.empty}>Loading recordings…</p>:recordings.length===0?<div className={styles.empty}><b>No recorded classes in this view</b><span>Upload a recording above, then publish it for enrolled students.</span></div>:<div className={styles.list}>{recordings.map(recording=><article className={styles.card} key={recording.recorded_class_id}><span className={styles.recordingIcon}>▶</span><div className={styles.cardBody}><div className={styles.cardTitle}><h3>{recording.title}</h3><span className={styles['status'+recording.status]}>{recording.status}</span></div><p>{recording.class_code} · {recording.subject_name}{recording.chapter_name?' · '+recording.chapter_name:''}</p><small>{recording.original_filename} · {formatSize(recording.file_size_bytes)} · {durationText(recording.duration_seconds)}</small><small>{recording.media_type} · SHA-256 {recording.sha256.slice(0,12)}…</small>{recording.session_title&&<small>Linked live class: {recording.session_title}</small>}{recording.description&&<small>{recording.description}</small>}</div><div className={styles.actions}>{recording.status==='DRAFT'&&<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void changeRecordingStatus(recording,'PUBLISHED')}>Publish</button>}{recording.status==='PUBLISHED'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeRecordingStatus(recording,'ARCHIVED')}>Archive</button>}{recording.status==='ARCHIVED'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeRecordingStatus(recording,'DRAFT')}>Restore draft</button>}</div></article>)}</div>}
        </section>
      </>}
    </section>
  </main>;
}
