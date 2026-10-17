'use client';

import { formatUserTimestamp } from '../components/formatUserTimestamp';

import { getUiLocaleLanguage } from '../i18n/catalog';
import AdminSidebar from '../components/AdminSidebar';
import PremiumPagination from '../components/PremiumPagination';
import { useCallback, useContext, useEffect, useRef, useState } from 'react';
import styles from './Classes.module.css';
import { useUiTranslation } from '../i18n/useUiTranslation';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import StaffLearningChatPanel from './StaffLearningChatPanel';

type Chapter = {chapter_id:number;chapter_code:string;chapter_name:string};
type Staff = {staff_id:number;display_name:string;role:string};
type LiveSession = {session_id:number;title:string;description?:string;provider:string;youtube_video_id?:string|null;starts_at:string;ends_at:string;join_window_minutes?:number;status:string;provider_operation_status?:string;provider_error_message?:string|null;provider_event_id?:string|null;teaching_assignment_id?:number|null;assigned_teacher_name?:string|null;host_staff_id?:number|null;host_staff_name?:string|null;class_code:string;subject_code:string;subject_name:string;chapter_id?:number|null;chapter_name?:string|null;attendee_count?:number;published_recording_count?:number};
type TeachingAssignmentOption = {teaching_assignment_id:number;employee_id:number;employee_name:string;employee_number:string;linked_staff_account_id?:number|null;period_name:string;effective_start:string;effective_end:string};
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
function localInput(value?:string|null){if(!value)return '';const d=new Date(value);return Number.isNaN(d.getTime())?'':new Date(d.getTime()-d.getTimezoneOffset()*60000).toISOString().slice(0,16);}
function indiaDate(value:string){const d=new Date(value);return Number.isNaN(d.getTime())?'':d.toLocaleDateString('en-CA',{timeZone:'Asia/Kolkata'});}
function dateText(value?:string|null, _locale:string='hinglish'){
  return formatUserTimestamp(value);
}
function sessionStatusText(status:string,t:TranslateFn){
  const keys:Record<string,string>={SCHEDULED:'admin.classes.scheduled',LIVE:'admin.classes.liveStatus',COMPLETED:'admin.classes.completed',CANCELLED:'admin.classes.cancelled'};
  return keys[status]?t(keys[status]):status;
}
function recordingStatusText(status:string,t:TranslateFn){
  const keys:Record<string,string>={DRAFT:'admin.classes.recordingStatusDraft',PUBLISHED:'admin.classes.recordingStatusPublished',ARCHIVED:'admin.classes.recordingStatusArchived'};
  return keys[status]?t(keys[status]):status;
}
function integrationStatusLabel(status:string,t:TranslateFn){
  const keys:Record<string,string>={CREATED:'admin.classes.integrationStatusCreated',PENDING:'admin.classes.integrationStatusPending',FAILED:'admin.classes.integrationStatusFailed',CANCELLED:'admin.classes.integrationStatusCancelled',UNKNOWN:'admin.classes.integrationStatusUnknown'};
  const normalized=status.toUpperCase();
  return keys[normalized]?t(keys[normalized]):status;
}
function formatSize(size:number){
  return size>=1024*1024?(size/(1024*1024)).toFixed(1)+' MB':Math.max(1,Math.round(size/1024))+' KB';
}
type TranslateFn=(key:string,values?:Record<string,string|number>)=>string;
function durationText(seconds?:number|null,t?:TranslateFn){
  if(!seconds)return t?t('admin.classes.durationNotSet'):'Duration not set';
  const h=Math.floor(seconds/3600),m=Math.floor((seconds%3600)/60);
  return t?(h?t('admin.classes.durationHoursMinutes',{hours:h,minutes:m}):t('admin.classes.durationMinutes',{minutes:m})):(h?h+'h '+m+'m':m+' min');
}

export default function ClassesPage(){
  const t=useUiTranslation();
  const {locale:uiLocale}=useContext(AdminWorkspaceContext);
  const tRef=useRef(t);
  useEffect(()=>{tRef.current=t;},[t]);
  const [tab,setTab]=useState<'live'|'recorded'>('live');
  const [classCode,setClassCode]=useState('6');
  const [subjectCode,setSubjectCode]=useState('maths');
  const [statusFilter,setStatusFilter]=useState('ALL');
  const [sessions,setSessions]=useState<LiveSession[]>([]);
  const [sessionPage,setSessionPage]=useState(0); const [sessionsTotal,setSessionsTotal]=useState(0); const [sessionPageSize,setSessionPageSize]=useState(10);
  const [recordings,setRecordings]=useState<Recording[]>([]);
  const [recordingPage,setRecordingPage]=useState(0); const [recordingsTotal,setRecordingsTotal]=useState(0); const [recordingPageSize,setRecordingPageSize]=useState(10);
  const [revealedUrls,setRevealedUrls]=useState<Record<number,string>>({});
  const [chapters,setChapters]=useState<Chapter[]>([]);
  const [staff,setStaff]=useState<Staff[]>([]);
  const [teachingAssignments,setTeachingAssignments]=useState<TeachingAssignmentOption[]>([]);
  const [teachingAssignmentId,setTeachingAssignmentId]=useState('');
  const [reschedulingSession,setReschedulingSession]=useState<number|null>(null);
  const [rescheduleStartAt,setRescheduleStartAt]=useState('');
  const [rescheduleEndAt,setRescheduleEndAt]=useState('');
  const [teacherChangeSession,setTeacherChangeSession]=useState<number|null>(null);
  const [teacherChangeOptions,setTeacherChangeOptions]=useState<Record<number,TeachingAssignmentOption[]>>({});
  const [teacherChangeSelection,setTeacherChangeSelection]=useState<Record<number,string>>({});
  const [title,setTitle]=useState('');
  const [description,setDescription]=useState('');
  const [provider,setProvider]=useState('GOOGLE_MEET');
  const [joinUrl,setJoinUrl]=useState('');
  const [joinWindowMinutes,setJoinWindowMinutes]=useState('15');
  const [allowJoinAnytime,setAllowJoinAnytime]=useState(false);
  const [joinWindowDrafts,setJoinWindowDrafts]=useState<Record<number,string>>({});
  const [joinWindowAnytimeDrafts,setJoinWindowAnytimeDrafts]=useState<Record<number,boolean>>({});
  const [joinWindowSaving,setJoinWindowSaving]=useState<number|null>(null);
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
  const [recordingUploadKey,setRecordingUploadKey]=useState('');
  const [busy,setBusy]=useState(false);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const sessionStatus=['SCHEDULED','LIVE','COMPLETED','CANCELLED'].includes(statusFilter)?statusFilter:'ALL';
      const recordingStatus=['DRAFT','PUBLISHED','ARCHIVED'].includes(statusFilter)?statusFilter:'ALL';
      const sessionParams=new URLSearchParams({classCode,subjectCode,status:sessionStatus,limit:String(sessionPageSize),offset:String(sessionPage*sessionPageSize)});
      const recordingParams=new URLSearchParams({classCode,subjectCode,status:recordingStatus,limit:String(recordingPageSize),offset:String(recordingPage*recordingPageSize)});
      const chapterParams=new URLSearchParams({classCode,subjectCode});
      const [sessionRows,recordingRows,contentRows,staffRows]=await Promise.all([
        api('/api/v1/admin/live-classes?'+sessionParams.toString()),
        api('/api/v1/admin/recorded-classes?'+recordingParams.toString()),
        api('/api/v1/admin/class-chapters?'+chapterParams.toString()),
        api('/api/v1/admin/class-staff')
      ]);
      setSessions(Array.isArray(sessionRows?.items)?sessionRows.items:(Array.isArray(sessionRows)?sessionRows:[]));
      setSessionsTotal(Number(sessionRows?.total??(Array.isArray(sessionRows)?sessionRows.length:0)));
      setRecordings(Array.isArray(recordingRows?.items)?recordingRows.items:(Array.isArray(recordingRows)?recordingRows:[]));
      setRecordingsTotal(Number(recordingRows?.total??(Array.isArray(recordingRows)?recordingRows.length:0)));
      const chapterMap=new Map<number,Chapter>();
      (Array.isArray(contentRows)?contentRows:[]).forEach((row:any)=>{
        const id=Number(row.chapter_id);
        if(id>0&&row.chapter_name)chapterMap.set(id,{chapter_id:id,chapter_code:String(row.chapter_code||''),chapter_name:String(row.chapter_name)});
      });
      setChapters([...chapterMap.values()]);
      setStaff(Array.isArray(staffRows)?staffRows:[]);
      setChapterId(value=>value&&chapterMap.has(Number(value))?value:'');
      setRecordingChapterId(value=>value&&chapterMap.has(Number(value))?value:'');
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.loadFailed'));}
    finally{setLoading(false);}
  },[classCode,subjectCode,statusFilter,sessionPage,recordingPage,sessionPageSize,recordingPageSize]);
  useEffect(()=>{void load();},[load]);

  const loadApprovedAssignments=useCallback(async()=>{
    if(!startAt){setTeachingAssignments([]);return;}
    const params=new URLSearchParams({classCode,subjectCode,onDate:indiaDate(startAt)});
    try{
      const rows=await api('/api/v1/admin/approved-teaching-assignments?'+params.toString());
      const list=Array.isArray(rows)?rows:[];
      setTeachingAssignments(list);
      setTeachingAssignmentId(current=>list.some((item:TeachingAssignmentOption)=>String(item.teaching_assignment_id)===current)?current:'');
    }catch{
      setTeachingAssignments([]);
      setTeachingAssignmentId('');
    }
  },[classCode,subjectCode,startAt]);
  useEffect(()=>{void loadApprovedAssignments();},[loadApprovedAssignments]);

  async function createSession(){
    if(!title.trim()||(provider!=='GOOGLE_MEET'&&!joinUrl.trim())||!startAt||!endAt){setError(provider==='GOOGLE_MEET'?tRef.current('admin.classes.scheduleMeetMissingFields'):provider==='YOUTUBE_LIVE'?tRef.current('admin.classes.scheduleYoutubeMissingFields'):tRef.current('admin.classes.scheduleSecureMissingFields'));return;}
    if(!teachingAssignmentId){setError(tRef.current('admin.classes.approvedAssignmentRequired'));return;}
    if(new Date(endAt).getTime()<=new Date(startAt).getTime()){setError(tRef.current('admin.classes.endAfterStart'));return;}
    const configuredJoinMinutes=Number(joinWindowMinutes);
    if(!allowJoinAnytime&&(!Number.isInteger(configuredJoinMinutes)||configuredJoinMinutes<1||configuredJoinMinutes>1440)){setError(tRef.current('admin.classes.joinWindowInvalid'));return;}
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/admin/live-classes',{method:'POST',body:JSON.stringify({
        title:title.trim(),description:description.trim(),classCode,subjectCode,
        chapterId:chapterId?Number(chapterId):null,provider,joinUrl:(provider==='GOOGLE_MEET'||provider==='YOUTUBE_LIVE')?null:joinUrl.trim(),broadcastUrl:provider==='YOUTUBE_LIVE'?joinUrl.trim():null,
        teachingAssignmentId:teachingAssignmentId?Number(teachingAssignmentId):null,
        startsAt:new Date(startAt).toISOString(),endsAt:new Date(endAt).toISOString(),
        joinWindowMinutes:allowJoinAnytime?0:Number(joinWindowMinutes),
        hostStaffId:hostStaffId?Number(hostStaffId):null
      })});
      setTitle('');setDescription('');setJoinUrl('');setHostStaffId('');setChapterId('');setTeachingAssignmentId('');setJoinWindowMinutes('15');setAllowJoinAnytime(false);
      if(provider==='GOOGLE_MEET'&&result.provider_operation_status!=='CREATED'){
        setNotice(tRef.current('admin.classes.scheduleMeetPending',{status:integrationStatusLabel(String(result.provider_operation_status||'UNKNOWN'),tRef.current),detail:String(result.provider_error_message||tRef.current('admin.classes.retryMeetSetupAdvice'))}));
      }else if(provider==='GOOGLE_MEET'){
        setNotice(tRef.current('admin.classes.scheduleMeetVerified'));
      }else if(provider==='YOUTUBE_LIVE'){
        setNotice(tRef.current('admin.classes.scheduleYouTubeScheduled'));
      }else{
        setNotice(tRef.current('admin.classes.scheduleLiveClassScheduled'));
      }
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.scheduleFailed'));}
    finally{setBusy(false);}
  }
  async function saveJoinWindow(session:LiveSession){
    const raw=joinWindowDrafts[session.session_id]??String((session.join_window_minutes??15)>0?(session.join_window_minutes??15):15);
    const allowAnytime=joinWindowAnytimeDrafts[session.session_id]??(session.join_window_minutes===0);
    const value=allowAnytime?0:Number(raw);
    if(!allowAnytime&&(!Number.isInteger(value)||value<1||value>1440)){setError(tRef.current('admin.classes.joinWindowInvalid'));return;}
    setJoinWindowSaving(session.session_id);setError('');setNotice('');
    try{
      await api('/api/v1/admin/live-classes/'+session.session_id+'/join-window',{
        method:'PUT',body:JSON.stringify({joinWindowMinutes:value})
      });
      setJoinWindowDrafts(old=>{const next={...old};delete next[session.session_id];return next;});
      setJoinWindowAnytimeDrafts(old=>{const next={...old};delete next[session.session_id];return next;});
      setNotice(value===0
        ?tRef.current('admin.classes.earlyJoinDisabledForSession',{title:session.title})
        :tRef.current('admin.classes.joinWindowSavedForSession',{title:session.title,minutes:value}));
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.joinWindowSaveFailed'));}
    finally{setJoinWindowSaving(null);}
  }
  async function revealMeetingLink(session:LiveSession){
    if(revealedUrls[session.session_id]){
      setRevealedUrls(old=>{const next={...old};delete next[session.session_id];return next;});
      return;
    }
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/admin/live-classes/'+session.session_id+'/meeting-link');
      if(!result.joinUrl){
        setNotice(String(result.errorMessage||tRef.current('admin.classes.meetingLinkUnverifiedStatus',{status:integrationStatusLabel(String(result.integrationStatus||'UNKNOWN'),tRef.current)})));
        return;
      }
      setRevealedUrls(old=>({...old,[session.session_id]:String(result.joinUrl)}));
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.meetingLinkRetrieveFailed'));}
    finally{setBusy(false);}
  }
  async function copyMeetingLink(sessionId:number){
    const url=revealedUrls[sessionId];
    if(!url)return;
    try{await navigator.clipboard.writeText(url);setNotice(tRef.current('admin.classes.privateMeetingLinkCopied'));}
    catch{setNotice(tRef.current('admin.classes.privateMeetingCopyManual'));}
  }
  async function changeSessionStatus(session:LiveSession,status:'LIVE'|'COMPLETED'|'CANCELLED'|'SCHEDULED'){
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/admin/live-classes/'+session.session_id+'/status',{method:'PATCH',body:JSON.stringify({status})});
      setNotice(result.provider_operation_status==='FAILED'&&result.provider_error_message
        ?tRef.current('admin.classes.statusChangedWithProviderError',{title:session.title,status:sessionStatusText(status,tRef.current),error:result.provider_error_message})
        :tRef.current('admin.classes.statusChanged',{title:session.title,status:sessionStatusText(status,tRef.current)}));
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.liveClassStatusChangeFailed'));}
    finally{setBusy(false);}
  }
  async function retryGoogleMeet(session:LiveSession){
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/admin/live-classes/'+session.session_id+'/google-meet/retry',{method:'POST'});
      setNotice(result.provider_operation_status==='CREATED'
        ?tRef.current('admin.classes.googleMeetVerifiedReady')
        :result.provider_operation_status==='CANCELLED'
          ?tRef.current('admin.classes.googleCalendarCancellationConfirmed')
          :tRef.current('admin.classes.googleMeetOperationStatus',{status:integrationStatusLabel(String(result.provider_operation_status||'UNKNOWN'),tRef.current),detail:String(result.provider_error_message||tRef.current('admin.classes.retryMeetSetupAdvice'))}));
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.googleMeetRetryFailed'));}
    finally{setBusy(false);}
  }

  async function beginTeacherChange(session:LiveSession){
    if(teacherChangeSession===session.session_id){setTeacherChangeSession(null);return;}
    setTeacherChangeSession(session.session_id);setError('');setNotice('');
    try{
      const params=new URLSearchParams({classCode:session.class_code,subjectCode:session.subject_code,onDate:indiaDate(session.starts_at)});
      const result=await api('/api/v1/admin/approved-teaching-assignments?'+params.toString());
      const options=Array.isArray(result)?result:[];
      setTeacherChangeOptions(old=>({...old,[session.session_id]:options}));
      const current=options.some((item:TeachingAssignmentOption)=>item.teaching_assignment_id===session.teaching_assignment_id)?String(session.teaching_assignment_id):'';
      setTeacherChangeSelection(old=>({...old,[session.session_id]:current}));
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.approvedAssignmentsLoadFailed'));setTeacherChangeSession(null);}
  }

  async function saveTeacherChange(session:LiveSession){
    const assignmentId=teacherChangeSelection[session.session_id];
    if(!assignmentId){setError(tRef.current('admin.classes.replacementAssignmentRequired'));return;}
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/admin/live-classes/'+session.session_id+'/teacher-assignment',{method:'PUT',body:JSON.stringify({teachingAssignmentId:Number(assignmentId)})});
      setNotice(tRef.current('admin.classes.teacherAssignmentUpdated',{title:session.title}));
      setTeacherChangeSession(null);
      if(result.session_id)setSessions(old=>old.map(item=>item.session_id===session.session_id?{...item,...result}:item));
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.teacherReplacementFailed'));}
    finally{setBusy(false);}
  }

  function beginReschedule(session:LiveSession){
    setReschedulingSession(session.session_id);
    setRescheduleStartAt(localInput(session.starts_at));
    setRescheduleEndAt(localInput(session.ends_at));
  }

  async function saveReschedule(session:LiveSession){
    if(!rescheduleStartAt||!rescheduleEndAt||new Date(rescheduleEndAt)<=new Date(rescheduleStartAt)){
      setError(tRef.current('admin.classes.rescheduleInvalidTimes'));return;
    }
    setBusy(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/admin/live-classes/'+session.session_id+'/schedule',{method:'PUT',body:JSON.stringify({
        startsAt:new Date(rescheduleStartAt).toISOString(),endsAt:new Date(rescheduleEndAt).toISOString()
      })});
      setNotice(result.provider_operation_status==='PENDING'
        ?tRef.current('admin.classes.rescheduleMeetPending')
        :tRef.current('admin.classes.rescheduleSuccess'));
      setReschedulingSession(null);
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.rescheduleFailed'));}
    finally{setBusy(false);}
  }

  async function uploadRecording(){
    if(!file){setError(tRef.current('admin.classes.recordingFileRequired'));return;}
    if(!recordingTitle.trim()){setError(tRef.current('admin.classes.recordingTitleRequired'));return;}
    setBusy(true);setError('');setNotice('');
    try{
      const form=new FormData();
      const uploadKey=recordingUploadKey||crypto.randomUUID();
      if(!recordingUploadKey)setRecordingUploadKey(uploadKey);
      form.append('file',file);form.append('title',recordingTitle.trim());
      form.append('classCode',classCode);form.append('subjectCode',subjectCode);
      if(recordingDescription.trim())form.append('description',recordingDescription.trim());
      if(recordingChapterId)form.append('chapterId',recordingChapterId);
      if(classSessionId)form.append('classSessionId',classSessionId);
      if(durationSeconds)form.append('durationSeconds',durationSeconds);
      const result=await api('/api/v1/admin/recorded-classes',{method:'POST',headers:{'Idempotency-Key':uploadKey},body:form});
      setRecordingUploadKey('');
      setRecordingTitle('');setRecordingDescription('');setRecordingChapterId('');setClassSessionId('');setDurationSeconds('');setFile(null);
      const input=document.getElementById('qe-recorded-class-file') as HTMLInputElement|null;if(input)input.value='';
      setNotice(tRef.current('admin.classes.recordingSavedAsDraft',{fileSize:formatSize(Number(result.file_size_bytes||file.size))}));
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.recordingUploadFailed'));}
    finally{setBusy(false);}
  }
  async function changeRecordingStatus(recording:Recording,status:'PUBLISHED'|'ARCHIVED'|'DRAFT'){
    setBusy(true);setError('');setNotice('');
    try{
      await api('/api/v1/admin/recorded-classes/'+recording.recorded_class_id+'/status',{method:'PATCH',body:JSON.stringify({status})});
      setNotice(tRef.current('admin.classes.recordingStatusUpdated',{title:recording.title,status:recordingStatusText(status,tRef.current)}));
      await load();
    }catch(e){setError(e instanceof Error?e.message:tRef.current('admin.classes.recordingStatusChangeFailed'));}
    finally{setBusy(false);}
  }
  const subjectLabel=t(subjectCode==='maths'?'admin.classes.mathematics':'admin.classes.science');
  const liveCount=sessions.filter(s=>s.status==='LIVE').length;
  const upcomingCount=sessions.filter(s=>s.status==='SCHEDULED').length;
  const reviewCount=recordings.filter(r=>r.status==='DRAFT').length;

  return <main className="admin-shell">
    <AdminSidebar active="classes" variant="content"/>
    <section className={'admin-main '+styles.page}>
      <header className="admin-top"><div><span className="admin-kicker">{t('admin.classes.eyebrow')}</span><h1>{t('admin.classes.title')}</h1><p>{t('admin.classes.description')}</p></div><button type="button" className="admin-refresh" onClick={()=>void load()} title={t("admin.classes.refreshClasses")}>↻</button></header>
      <section className={styles.hero}><div className={styles.heroIcon}>◷</div><div><span>{t('admin.classes.heroEyebrow')}</span><h2>{t('admin.classes.heroTitle')}</h2><p>{t('admin.classes.heroDescription')}</p></div><div className={styles.heroStats}><strong>{liveCount}</strong><small>{t('admin.classes.liveNow')}</small><strong>{upcomingCount}</strong><small>{t('admin.classes.upcoming')}</small><strong>{reviewCount}</strong><small>{t('admin.classes.draftRecordings')}</small></div></section>
      {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.success} role="status">{notice}</div>}
      <div className={styles.trackFilters}>
        <label>{t('admin.classes.classLabel')}<select value={classCode} onChange={e=>{setClassCode(e.target.value);setSessionPage(0);setRecordingPage(0)}}><option value="6">{t("admin.classes.classValue",{classCode:6})}</option><option value="7">{t("admin.classes.classValue",{classCode:7})}</option><option value="8">{t("admin.classes.classValue",{classCode:8})}</option></select></label>
        <label>{t('admin.classes.subjectLabel')}<select value={subjectCode} onChange={e=>{setSubjectCode(e.target.value);setSessionPage(0);setRecordingPage(0)}}><option value="maths">{t("admin.classes.mathematics")}</option><option value="science">{t("admin.classes.science")}</option></select></label>
        <label>{t('admin.classes.statusView')}<select value={statusFilter} onChange={e=>{setStatusFilter(e.target.value);setSessionPage(0);setRecordingPage(0)}}><option value="ALL">{t("admin.classes.allStatuses")}</option><option value="SCHEDULED">{t("admin.classes.scheduled")}</option><option value="LIVE">{t("admin.classes.liveStatus")}</option><option value="COMPLETED">{t("admin.classes.completed")}</option><option value="CANCELLED">{t("admin.classes.cancelled")}</option><option value="DRAFT">{t("admin.classes.draftRecordingsStatus")}</option><option value="PUBLISHED">{t("admin.classes.publishedRecordingsStatus")}</option><option value="ARCHIVED">{t("admin.classes.archivedRecordingsStatus")}</option></select></label>
      </div>
      <div className={styles.tabs}><button type="button" className={tab==='live'?styles.activeTab:''} onClick={()=>setTab('live')}>{t('admin.classes.liveSessions')} <span>{sessionsTotal}</span></button><button type="button" className={tab==='recorded'?styles.activeTab:''} onClick={()=>setTab('recorded')}>{t('admin.classes.recordings')} <span>{recordingsTotal}</span></button></div>

      {tab==='live'?<>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>{t('admin.classes.step01')}</span><h2>{t('admin.classes.scheduleLiveClass')}</h2><p>{t('admin.classes.secureMeetingHelp')}</p></div><b>01</b></div>
          <div className={styles.fields}>
            <label className={styles.wide}>{t('admin.classes.classTitle')}<input value={title} onChange={e=>setTitle(e.target.value)} placeholder={t("admin.classes.classTitlePlaceholder")}/></label>
            <label>{t('admin.classes.meetingProvider')}<select value={provider} onChange={e=>setProvider(e.target.value)}><option value="GOOGLE_MEET">Google Meet</option><option value="YOUTUBE_LIVE">{t("admin.classes.youtubeLiveBroadcast")}</option><option value="ZOOM">Zoom</option><option value="MICROSOFT_TEAMS">Microsoft Teams</option><option value="CUSTOM">{t("admin.classes.otherSecureMeeting")}</option></select></label>
            <label>{t('admin.classes.hostStaffOptional')}<select value={hostStaffId} onChange={e=>setHostStaffId(e.target.value)}><option value="">{t("admin.classes.selectHost")}</option>{staff.map(person=><option key={person.staff_id} value={person.staff_id}>{person.display_name} · {person.role}</option>)}</select></label>
            {provider==='YOUTUBE_LIVE'?<label className={styles.wide}>{t('admin.classes.publicYouTubeUrl')}<input type="url" required value={joinUrl} onChange={e=>setJoinUrl(e.target.value)} placeholder={t("admin.classes.youtubeUrlPlaceholder")}/><small>{t("admin.classes.authorizedBroadcastHelp")}</small></label>:provider!=='GOOGLE_MEET'?<label className={styles.wide}>{t('admin.classes.privateMeetingUrl')}<input type="url" required value={joinUrl} onChange={e=>setJoinUrl(e.target.value)} placeholder={t("admin.classes.privateMeetingPlaceholder")}/></label>:<p className={styles.empty}>{t("admin.classes.googleMeetProvisioningHelp")}</p>}
            <label className={styles.wide}>{t('admin.classes.approvedTeacherAssignment')}<select value={teachingAssignmentId} required onChange={e=>{setTeachingAssignmentId(e.target.value);const option=teachingAssignments.find(a=>String(a.teaching_assignment_id)===e.target.value);setHostStaffId(option?.linked_staff_account_id?String(option.linked_staff_account_id):'');}}><option value="">{t("admin.classes.chooseAssignmentDate")}</option>{teachingAssignments.map(a=><option key={a.teaching_assignment_id} value={a.teaching_assignment_id}>{a.employee_name} · {a.employee_number} · {a.period_name} ({String(a.effective_start).slice(0,10)}–{String(a.effective_end).slice(0,10)})</option>)}</select>{provider==='GOOGLE_MEET'&&teachingAssignments.length===0&&<small>{t("admin.classes.noApprovedEffectiveAssignment")}</small>}</label>
            <label>{t('admin.classes.startDateTime')}<input type="datetime-local" value={startAt} onChange={e=>setStartAt(e.target.value)}/></label>
            <label>{t('admin.classes.endDateTime')}<input type="datetime-local" value={endAt} onChange={e=>setEndAt(e.target.value)}/></label>
            <label>{t("admin.classes.joinMinutesField")}<input type="number" min={1} max={1440} step={1} value={joinWindowMinutes} disabled={allowJoinAnytime} onChange={e=>setJoinWindowMinutes(e.target.value)}/><small>{t("admin.classes.joinMinutesFieldHelp")}</small></label>
            <label className={styles.joinWindowToggle}><input type="checkbox" checked={allowJoinAnytime} onChange={e=>setAllowJoinAnytime(e.target.checked)}/><span>{t("admin.classes.allowJoinAnytime")}</span></label>
            <label>{t("admin.classes.chapterOptional")}<select value={chapterId} onChange={e=>setChapterId(e.target.value)}><option value="">{t("admin.classes.wholeSubject")}</option>{chapters.map(ch=><option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}</select></label>
            <label className={styles.wide}>{t("admin.classes.studentFacingDetails")}<textarea rows={3} value={description} onChange={e=>setDescription(e.target.value)} placeholder={t("admin.classes.studentFacingPlaceholder")}/></label>
          </div>
          <div className={styles.submitBar}><p>{t("admin.classes.classSubmitSummary",{classCode,subject:subjectLabel})}</p><button type="button" className={styles.primaryButton} disabled={busy||!title.trim()||(provider!=='GOOGLE_MEET'&&!joinUrl.trim())||(!teachingAssignmentId)} onClick={()=>void createSession()}>{busy?t('admin.classes.saving'):t('admin.classes.scheduleClass')}</button></div>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>{t('admin.classes.sessionManagement')}</span><h2>{t('admin.classes.scheduledCompletedClasses')}</h2><p>{t("admin.classes.sessionLifecycleHelp")}</p></div></div>
          {loading?<p className={styles.empty}>{t("admin.classes.loadingClasses")}</p>:sessions.length===0?<div className={styles.empty}><b>{t("admin.classes.noLiveClasses")}</b><span>{t("admin.classes.scheduleSessionHint")}</span></div>:<div className={styles.list}>{sessions.map(session=><article className={styles.card} key={session.session_id}><span className={styles.sessionIcon}>◷</span><div className={styles.cardBody}><div className={styles.cardTitle}><h3>{session.title}</h3><span className={styles['status'+session.status]}>{sessionStatusText(session.status,t)}</span></div><p>{session.class_code} · {session.subject_name}{session.chapter_name?' · '+session.chapter_name:''}</p><small>{dateText(session.starts_at,uiLocale)} – {dateText(session.ends_at,uiLocale)}</small><small>{t("admin.classes.providerAndHost",{provider:session.provider.replaceAll("_"," "),host:session.host_staff_name||t("admin.classes.notAssigned")})}</small><small>{t("admin.classes.assignedTeacherAndAssignment",{teacher:session.assigned_teacher_name||t("admin.classes.notAssigned"),assignment:session.teaching_assignment_id?"#"+session.teaching_assignment_id:t("admin.classes.assignmentMissing")})}</small><small>{t("admin.classes.attendeeSummary",{attendees:session.attendee_count||0,recordings:session.published_recording_count||0})}</small>{session.status==='SCHEDULED'&&<div className={styles.joinWindowControl}><label>{t("admin.classes.joinMinutesField")}<input type="number" min={1} max={1440} step={1} aria-label={t("admin.classes.joinMinutesField") + ": " + session.title} value={joinWindowDrafts[session.session_id]??String((session.join_window_minutes??15)>0?(session.join_window_minutes??15):15)} disabled={joinWindowSaving===session.session_id||busy||(joinWindowAnytimeDrafts[session.session_id]??(session.join_window_minutes===0))} onChange={e=>setJoinWindowDrafts(old=>({...old,[session.session_id]:e.target.value}))}/></label><label className={styles.joinWindowToggle}><input type="checkbox" checked={joinWindowAnytimeDrafts[session.session_id]??(session.join_window_minutes===0)} disabled={busy||joinWindowSaving===session.session_id} onChange={e=>setJoinWindowAnytimeDrafts(old=>({...old,[session.session_id]:e.target.checked}))}/><span>{t("admin.classes.allowJoinAnytime")}</span></label><button type="button" className={styles.secondaryButton} disabled={busy||joinWindowSaving===session.session_id} onClick={()=>void saveJoinWindow(session)}>{joinWindowSaving===session.session_id?t('admin.classes.saving'):t('admin.classes.saveJoinWindow')}</button></div>}{session.provider==='GOOGLE_MEET'&&<small>{t("admin.classes.providerStatusLine",{status:integrationStatusLabel(session.provider_operation_status||"UNKNOWN",t),detail:session.provider_error_message?" · "+session.provider_error_message:""})}</small>}{session.description&&<small>{session.description}</small>}{session.status!=='CANCELLED'&&<StaffLearningChatPanel sessionId={session.session_id} locale={uiLocale} />}{session.provider==='YOUTUBE_LIVE'&&session.youtube_video_id&&<small>Broadcast video ID: {session.youtube_video_id}</small>}{revealedUrls[session.session_id]&&<div className={styles.privateLink}><small>{t("admin.classes.privateMeetingStaffOnly")}</small><input aria-label={t("admin.classes.privateMeetingStaffOnly")} readOnly value={revealedUrls[session.session_id]}/><button type="button" className={styles.secondaryButton} onClick={()=>void copyMeetingLink(session.session_id)}>{t("admin.classes.copyLink")}</button><a href={revealedUrls[session.session_id]} target="_blank" rel="noreferrer">{t("admin.classes.openMeeting")}</a></div>}</div><div className={styles.actions}>{session.provider!=='YOUTUBE_LIVE'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void revealMeetingLink(session)}>{revealedUrls[session.session_id]?t('admin.classes.hideLink'):t('admin.classes.revealLink')}</button>}{session.provider==='GOOGLE_MEET'&&(['FAILED','PENDING'].includes(session.provider_operation_status||'')||session.status==='CANCELLED'&&session.provider_operation_status==='FAILED')&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void retryGoogleMeet(session)}>{session.status==='CANCELLED'?t('admin.classes.retryCancellation'):t('admin.classes.checkRetryMeet')}</button>}{session.status==='SCHEDULED'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void beginTeacherChange(session)}>{teacherChangeSession===session.session_id?t('admin.classes.hideTeacherChange'):session.teaching_assignment_id?t('admin.classes.replaceTeacher'):t('admin.classes.assignTeacher')}</button>}{session.status==='SCHEDULED'&&(session.provider!=='GOOGLE_MEET'||session.provider_operation_status==='CREATED')&&session.teaching_assignment_id&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>beginReschedule(session)}>{reschedulingSession===session.session_id?t('admin.classes.hideReschedule'):t('admin.classes.reschedule')}</button>}{session.status==='SCHEDULED'&&<button type="button" className={styles.primaryButton} disabled={busy||(session.provider==='GOOGLE_MEET'&&session.provider_operation_status!=='CREATED')} onClick={()=>void changeSessionStatus(session,'LIVE')}>{t("admin.classes.markLive")}</button>}{session.status==='LIVE'&&<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void changeSessionStatus(session,'COMPLETED')}>{t("admin.classes.completeSession")}</button>}{['SCHEDULED','LIVE'].includes(session.status)&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeSessionStatus(session,'CANCELLED')}>{t("admin.classes.cancelSession")}</button>}{session.status==='CANCELLED'&&session.provider!=='GOOGLE_MEET'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeSessionStatus(session,'SCHEDULED')}>{t("admin.classes.restoreSession")}</button>}</div>{teacherChangeSession===session.session_id&&<div className={styles.fields}><label className={styles.wide}>{t("admin.classes.approvedReplacementAssignment")}<select required value={teacherChangeSelection[session.session_id]||''} onChange={e=>setTeacherChangeSelection(old=>({...old,[session.session_id]:e.target.value}))}><option value="">{t("admin.classes.chooseTeacherForClassDate")}</option>{(teacherChangeOptions[session.session_id]||[]).map(option=><option key={option.teaching_assignment_id} value={option.teaching_assignment_id}>{option.employee_name} · {option.employee_number} · {option.period_name}</option>)}</select>{!(teacherChangeOptions[session.session_id]||[]).length&&<small>{t("admin.classes.noApprovedReplacementAssignment")}</small>}</label><div className={styles.submitBar}><button type="button" className={styles.primaryButton} disabled={busy||!teacherChangeSelection[session.session_id]} onClick={()=>void saveTeacherChange(session)}>{busy?t('admin.classes.saving'):t('admin.classes.saveTeacherAssignment')}</button><button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>setTeacherChangeSession(null)}>{t("admin.classes.discardChanges")}</button></div></div>}{reschedulingSession===session.session_id&&<div className={styles.fields}><label>{t("admin.classes.newStartTime")}<input type="datetime-local" value={rescheduleStartAt} onChange={e=>setRescheduleStartAt(e.target.value)}/></label><label>{t("admin.classes.newEndTime")}<input type="datetime-local" value={rescheduleEndAt} onChange={e=>setRescheduleEndAt(e.target.value)}/></label><div className={styles.submitBar}><button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void saveReschedule(session)}>{busy?t('admin.classes.saving'):t('admin.classes.saveSchedule')}</button><button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>setReschedulingSession(null)}>{t("admin.classes.discardChanges")}</button></div></div>}</article>)}</div>}
        <PremiumPagination page={sessionPage} total={sessionsTotal} pageSize={sessionPageSize}
          onPageChange={setSessionPage} onPageSizeChange={size=>{setSessionPageSize(size);setSessionPage(0);}}
          itemLabel={t("admin.classes.liveClassesItemLabel")} loading={loading} />
        </section>
      </>:<>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>{t('admin.classes.step01')}</span><h2>{t('admin.classes.addRecordedClass')}</h2><p>{t("admin.classes.recordingUploadHelp")}</p></div><b>01</b></div>
          <div className={styles.fields}>
            <label className={styles.wide}>{t('admin.classes.recordingTitle')}<input value={recordingTitle} onChange={e=>setRecordingTitle(e.target.value)} placeholder={t("admin.classes.recordingTitlePlaceholder")}/></label>
            <label>Chapter (optional)<select value={recordingChapterId} onChange={e=>setRecordingChapterId(e.target.value)}><option value="">{t("admin.classes.wholeSubject")}</option>{chapters.map(ch=><option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}</select></label>
            <label>{t("admin.classes.linkLiveSession")}<select value={classSessionId} onChange={e=>setClassSessionId(e.target.value)}><option value="">{t("admin.classes.noLinkedLiveSession")}</option>{sessions.filter(s=>s.status!=='CANCELLED'&&s.class_code===classCode&&s.subject_code===subjectCode).map(s=><option key={s.session_id} value={s.session_id}>{dateText(s.starts_at,uiLocale)} · {s.title}</option>)}</select></label>
            <label>{t("admin.classes.recordingDurationOptional")}<input type="number" min="1" max="86400" value={durationSeconds} onChange={e=>setDurationSeconds(e.target.value)} placeholder={t("admin.classes.durationPlaceholder")}/></label>
            <label className={styles.wide}>{t("admin.classes.descriptionLabel")}<textarea rows={3} value={recordingDescription} onChange={e=>setRecordingDescription(e.target.value)} placeholder={t("admin.classes.recordingDescriptionPlaceholder")}/></label>
            <label className={styles.fileInput} htmlFor="qe-recorded-class-file"><span>{t("admin.classes.chooseRecording")}</span><input id="qe-recorded-class-file" type="file" accept="video/mp4,video/webm,video/quicktime,.mp4,.webm,.mov" onChange={e=>{setFile(e.target.files?.[0]||null);setRecordingUploadKey('');}}/><small>{file?file.name+' · '+formatSize(file.size):t('admin.classes.noFileSelected')}</small></label>
          </div>
          <div className={styles.submitBar}><p>{t("admin.classes.recordingUploadPolicy")}</p><button type="button" className={styles.primaryButton} disabled={busy||!file||!recordingTitle.trim()} onClick={()=>void uploadRecording()}>{busy?t('admin.classes.uploading'):t('admin.classes.uploadPrivateRecording')}</button></div>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHeading}><div><span>{t("admin.classes.recordingGovernance")}</span><h2>{t("admin.classes.recordedClassLibrary")}</h2><p>{t("admin.classes.recordingPublishPolicy")}</p></div></div>
          {loading?<p className={styles.empty}>{t("admin.classes.loadingRecordings")}</p>:recordings.length===0?<div className={styles.empty}><b>{t("admin.classes.noRecordings")}</b><span>{t("admin.classes.uploadRecordingHint")}</span></div>:<div className={styles.list}>{recordings.map(recording=><article className={styles.card} key={recording.recorded_class_id}><span className={styles.recordingIcon}>▶</span><div className={styles.cardBody}><div className={styles.cardTitle}><h3>{recording.title}</h3><span className={styles['status'+recording.status]}>{recordingStatusText(recording.status,t)}</span></div><p>{recording.class_code} · {recording.subject_name}{recording.chapter_name?' · '+recording.chapter_name:''}</p><small>{recording.original_filename} · {formatSize(recording.file_size_bytes)} · {durationText(recording.duration_seconds,t)}</small><small>{recording.media_type} · SHA-256 {recording.sha256.slice(0,12)}…</small>{recording.session_title&&<small>{t("admin.classes.linkedLiveClass",{title:recording.session_title})}</small>}{recording.description&&<small>{recording.description}</small>}</div><div className={styles.actions}>{recording.status==='DRAFT'&&<button type="button" className={styles.primaryButton} disabled={busy} onClick={()=>void changeRecordingStatus(recording,'PUBLISHED')}>{t('admin.classes.publishRecording')}</button>}{recording.status==='PUBLISHED'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeRecordingStatus(recording,'ARCHIVED')}>{t('admin.classes.archiveRecording')}</button>}{recording.status==='ARCHIVED'&&<button type="button" className={styles.secondaryButton} disabled={busy} onClick={()=>void changeRecordingStatus(recording,'DRAFT')}>{t('admin.classes.restoreDraft')}</button>}</div></article>)}</div>}
        <PremiumPagination page={recordingPage} total={recordingsTotal} pageSize={recordingPageSize}
          onPageChange={setRecordingPage} onPageSizeChange={size=>{setRecordingPageSize(size);setRecordingPage(0);}}
          itemLabel={t("admin.classes.recordingsItemLabel")} loading={loading} />
        </section>
      </>}
    </section>
  </main>;
}
