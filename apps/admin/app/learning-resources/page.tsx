'use client';

import { formatUserTimestamp } from '../components/formatUserTimestamp';

import AdminSidebar from '../components/AdminSidebar';
import Link from 'next/link';
import {useCallback,useEffect,useMemo,useState} from 'react';
import {useRouter} from 'next/navigation';
import styles from './LearningResources.module.css';

type Resource={resource_id:number;resource_type:string;title:string;description?:string|null;language:string;source_kind:string;source_reference?:string|null;file_size_bytes?:number|null;download_allowed:boolean;status:string;version_no:number;assignment_count:number;created_at?:string|null;};
type Chapter={chapter_id:number;chapter_code:string;chapter_name:string;class_code:string;class_name:string;subject_code:string;subject_name:string;content_status?:string;};
type Lesson={lesson_id:number;lesson_code:string;lesson_title:string;lesson_status:string;chapter_id:number;chapter_code:string;chapter_name:string;class_code:string;subject_code:string;};
type Artifact={artifact_id:string;class_code:string;subject_code:string;chapter_number:number;chapter_title:string;title:string;mapped_chapter_id:number|null;};
type Assignment={assignment_id:number;resource_id:number;target_kind:string;chapter_id:number;lesson_id?:number|null;artifact_id?:string|null;sort_order:number;status:string;chapter_code:string;chapter_name:string;lesson_title?:string|null;class_code:string;subject_code:string;};
type Targets={chapters:Chapter[];lessons:Lesson[];structuredArtifacts:Artifact[];};
async function api(url:string,init:RequestInit={}){
 const headers=new Headers(init.headers||{});if(init.body&&!(init.body instanceof FormData))headers.set('Content-Type','application/json');
 const res=await fetch(url,{...init,headers,credentials:'same-origin',cache:'no-store'});const raw=await res.text();let body:any={};
 try{body=raw?JSON.parse(raw):{}}catch{body={message:raw};}
 if(!res.ok){if(res.status===401||res.status===403)throw Object.assign(new Error('Staff authorization is required.'),{auth:true});throw new Error(body.detail||body.message||body.error||('Request failed ('+res.status+')'));}
 return body;
}
const types=['VIDEO','PDF','WORKSHEET','SLIDES','AUDIO','IMAGE','OTHER'];
const sources=['PRIVATE_UPLOAD','RECORDED_CLASS','LEARNING_DOCUMENT','CONTENT_ASSET','EXTERNAL_URL'];
const label=(s:string)=>s.replaceAll('_',' ').toLowerCase().replace(/\b\w/g,c=>c.toUpperCase());
const sizeText=(n?:number|null)=>!n?'—':n>=1048576?(n/1048576).toFixed(1)+' MB':Math.max(1,Math.round(n/1024))+' KB';
const dateText=(v?:string|null)=>formatUserTimestamp(v);

export default function LearningResourcesPage(){
 const router=useRouter();
 const [resources,setResources]=useState<Resource[]>([]),[total,setTotal]=useState(0),[selectedId,setSelectedId]=useState('');
 const [targets,setTargets]=useState<Targets>({chapters:[],lessons:[],structuredArtifacts:[]}),[assignments,setAssignments]=useState<Assignment[]>([]);
 const [busy,setBusy]=useState(false),[loading,setLoading]=useState(true),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [statusFilter,setStatusFilter]=useState('ALL'),[typeFilter,setTypeFilter]=useState('ALL');
 const [title,setTitle]=useState(''),[description,setDescription]=useState(''),[resourceType,setResourceType]=useState('VIDEO'),[language,setLanguage]=useState('mul');
 const [sourceKind,setSourceKind]=useState('PRIVATE_UPLOAD'),[sourceId,setSourceId]=useState(''),[externalUrl,setExternalUrl]=useState(''),[downloadAllowed,setDownloadAllowed]=useState(false),[file,setFile]=useState<File|null>(null);
 const [classFilter,setClassFilter]=useState('ALL'),[subjectFilter,setSubjectFilter]=useState('ALL'),[targetKind,setTargetKind]=useState('CHAPTER');
 const [chapterId,setChapterId]=useState(''),[lessonId,setLessonId]=useState(''),[artifactId,setArtifactId]=useState(''),[sortOrder,setSortOrder]=useState('0');

 const loadResources=useCallback(async()=>{
  setLoading(true);setError('');
  try{
   const p=new URLSearchParams({status:statusFilter==='ALL'?'':statusFilter,resourceType:typeFilter==='ALL'?'':typeFilter,limit:'100',offset:'0'});
   const d=await api('/api/v1/admin/learning-resources?'+p);const rows=Array.isArray(d?.items)?d.items:[];
   setResources(rows);setTotal(Number(d?.total??rows.length));setSelectedId(old=>rows.some((x:Resource)=>String(x.resource_id)===old)?old:(rows[0]?String(rows[0].resource_id):''));
  }catch(e:any){if(e?.auth){router.replace('/login');return;}setError(e instanceof Error?e.message:'Resources could not be loaded.');}
  finally{setLoading(false);}
 },[statusFilter,typeFilter,router]);
 useEffect(()=>{void loadResources();},[loadResources]);
 useEffect(()=>{let active=true;api('/api/v1/admin/learning-resource-targets').then(d=>{if(active)setTargets({chapters:d.chapters||[],lessons:d.lessons||[],structuredArtifacts:d.structuredArtifacts||[]});}).catch((e:any)=>{if(active)setError(e.message||'Curriculum targets could not be loaded.');});return()=>{active=false;};},[]);
 const selected=resources.find(r=>String(r.resource_id)===selectedId);
 const chapters=useMemo(()=>targets.chapters.filter(x=>(classFilter==='ALL'||x.class_code===classFilter)&&(subjectFilter==='ALL'||x.subject_code===subjectFilter)),[targets,classFilter,subjectFilter]);
 const lessons=useMemo(()=>targets.lessons.filter(x=>(classFilter==='ALL'||x.class_code===classFilter)&&(subjectFilter==='ALL'||x.subject_code===subjectFilter)),[targets,classFilter,subjectFilter]);
 const artifacts=useMemo(()=>targets.structuredArtifacts.filter(x=>(classFilter==='ALL'||x.class_code===classFilter)&&(subjectFilter==='ALL'||x.subject_code===subjectFilter)),[targets,classFilter,subjectFilter]);
 const classCodes=[...new Set(targets.chapters.map(x=>x.class_code))].sort((a,b)=>Number(a)-Number(b));
 const subjectCodes=[...new Set(targets.chapters.filter(x=>classFilter==='ALL'||x.class_code===classFilter).map(x=>x.subject_code))].sort();

 const loadAssignments=useCallback(async(id:string)=>{if(!id){setAssignments([]);return;}try{const d=await api('/api/v1/admin/learning-resource-assignments?'+new URLSearchParams({resourceId:id,limit:'200',offset:'0'}));setAssignments(Array.isArray(d?.items)?d.items:[]);}catch(e:any){setError(e.message||'Assignments could not be loaded.');}},[]);
 useEffect(()=>{void loadAssignments(selectedId);},[selectedId,loadAssignments]);

 async function createResource(){
  if(!title.trim()){setError('Enter a title.');return;}
  if(sourceKind==='PRIVATE_UPLOAD'&&!file){setError('Choose a file.');return;}
  if(sourceKind==='EXTERNAL_URL'&&!externalUrl.trim()){setError('Enter an external HTTPS URL.');return;}
  if(['RECORDED_CLASS','LEARNING_DOCUMENT','CONTENT_ASSET'].includes(sourceKind)&&!(Number(sourceId)>0)){setError('Enter a valid existing source ID.');return;}
  if(sourceKind==='RECORDED_CLASS'&&resourceType!=='VIDEO'){setError('Existing recorded classes must use resource type VIDEO.');return;}
  if(sourceKind==='LEARNING_DOCUMENT'&&resourceType!=='PDF'){setError('Existing textbook documents must use resource type PDF.');return;}
  setBusy(true);setError('');setNotice('');
  try{
   let out:any;
   if(sourceKind==='PRIVATE_UPLOAD'){
    const fd=new FormData();fd.set('file',file!);fd.set('title',title.trim());fd.set('resourceType',resourceType);fd.set('description',description.trim());fd.set('language',language);fd.set('downloadAllowed',String(downloadAllowed));
    out=await api('/api/v1/admin/learning-resources/upload',{method:'POST',body:fd});
   }else{
    const body:any={title:title.trim(),description:description.trim(),resourceType,language,sourceKind,downloadAllowed};
    if(sourceKind==='EXTERNAL_URL')body.externalUrl=externalUrl.trim();
    if(sourceKind==='RECORDED_CLASS')body.recordedClassId=Number(sourceId);
    if(sourceKind==='LEARNING_DOCUMENT')body.learningDocumentId=Number(sourceId);
    if(sourceKind==='CONTENT_ASSET')body.contentAssetId=Number(sourceId);
    out=await api('/api/v1/admin/learning-resources',{method:'POST',body:JSON.stringify(body)});
   }
   setTitle('');setDescription('');setSourceId('');setExternalUrl('');setFile(null);setDownloadAllowed(false);
   setNotice('Saved as draft. Publish the underlying source where required, then publish the resource and its placement.');
   await loadResources();if(out?.resource_id)setSelectedId(String(out.resource_id));
  }catch(e:any){if(e?.auth){router.replace('/login');return;}setError(e.message||'Resource could not be saved.');}
  finally{setBusy(false);}
 }
 async function setResourceStatus(r:Resource,next:string){
  setBusy(true);setError('');setNotice('');
  try{await api('/api/v1/admin/learning-resources/'+r.resource_id+'/status',{method:'PATCH',body:JSON.stringify({status:next})});setNotice('Resource status changed to '+next+'.');await loadResources();await loadAssignments(String(r.resource_id));}
  catch(e:any){setError(e.message||'Resource status could not be changed.');}finally{setBusy(false);}
 }
 async function setDownload(r:Resource,next:boolean){
  setBusy(true);setError('');setNotice('');
  try{await api('/api/v1/admin/learning-resources/'+r.resource_id+'/download-policy',{method:'PUT',body:JSON.stringify({downloadAllowed:next})});setNotice('Managed download policy updated.');await loadResources();}
  catch(e:any){setError(e.message||'Download policy could not be changed.');}finally{setBusy(false);}
 }
 async function createAssignment(){
  if(!selected){setError('Select a resource first.');return;}const body:any={resourceId:selected.resource_id,targetKind,sortOrder:Math.max(0,Number(sortOrder)||0)};
  if(targetKind==='CHAPTER'){if(!Number(chapterId)){setError('Choose a chapter.');return;}body.chapterId=Number(chapterId);}
  else if(targetKind==='LESSON'){const l=targets.lessons.find(x=>String(x.lesson_id)===lessonId);if(!l){setError('Choose a lesson.');return;}body.lessonId=l.lesson_id;body.chapterId=l.chapter_id;}
  else {const a=targets.structuredArtifacts.find(x=>x.artifact_id===artifactId);if(!a){setError('Choose a published structured lesson.');return;}body.artifactId=a.artifact_id;body.chapterId=Number(a.mapped_chapter_id)||Number(chapterId);if(!body.chapterId){setError('Select the canonical chapter mapping.');return;}}
  setBusy(true);setError('');setNotice('');
  try{await api('/api/v1/admin/learning-resource-assignments',{method:'POST',body:JSON.stringify(body)});setNotice('Placement created as draft. Publish it after the resource and target are ready.');await loadAssignments(String(selected.resource_id));}
  catch(e:any){setError(e.message||'Placement could not be created.');}finally{setBusy(false);}
 }
 async function setAssignmentStatus(a:Assignment,next:string){
  setBusy(true);setError('');setNotice('');
  try{await api('/api/v1/admin/learning-resource-assignments/'+a.assignment_id+'/status',{method:'PATCH',body:JSON.stringify({status:next})});setNotice('Placement status changed to '+next+'.');await loadAssignments(String(a.resource_id));}
  catch(e:any){setError(e.message||'Placement status could not be changed.');}finally{setBusy(false);}
 }
 async function reorder(a:Assignment,n:number){
  setBusy(true);setError('');try{await api('/api/v1/admin/learning-resource-assignments/'+a.assignment_id+'/order',{method:'PUT',body:JSON.stringify({sortOrder:Math.max(0,n)})});await loadAssignments(String(a.resource_id));}
  catch(e:any){setError(e.message||'Placement order could not be changed.');}finally{setBusy(false);}
 }
 return <main className="admin-shell"><AdminSidebar active="resources" variant="content"/><section className={'admin-main '+styles.page}>
  <header className="admin-top"><div><span className="admin-kicker">QUANTAEDGE LEARNING / RESOURCE GOVERNANCE</span><h1>Learning resource catalogue</h1><p>Upload once, then attach videos, PDFs, worksheets, slides and other resources to chapter, lesson or structured lesson targets.</p></div><div className={styles.topActions}><Link href="/resources" className={styles.secondaryButton}>Textbook PDF library</Link><button type="button" className={styles.secondaryButton} onClick={()=>void loadResources()}>↻ Refresh</button></div></header>
  {error&&<div className={styles.error} role="alert">{error}</div>}{notice&&<div className={styles.success} role="status">{notice}</div>}
  <section className={styles.hero}><div className={styles.heroIcon}>▤</div><div><span>RESOURCE GOVERNANCE</span><h2>One resource. Multiple placements.</h2><p>Private uploads remain in protected storage. Existing QuantaEdge videos and documents are referenced, not copied. Resource and placement publishing are separate approval gates.</p></div><div className={styles.stats}><strong>{total}</strong><small>Matching resources</small></div></section>
  <section className={styles.panel}><div className={styles.sectionHead}><div><span>STEP 01</span><h2>Create or link a resource</h2><p>Uploads, published recordings, textbook documents, content assets and HTTPS external links use the same catalogue.</p></div></div>
   <div className={styles.grid}>
    <label className={styles.field}>Title<input value={title} maxLength={240} onChange={e=>setTitle(e.target.value)} placeholder="e.g. Fractions revision video"/></label>
    <label className={styles.field}>Resource type<select value={resourceType} onChange={e=>setResourceType(e.target.value)}>{types.map(t=><option value={t} key={t}>{label(t)}</option>)}</select></label>
    <label className={styles.field}>Source<select value={sourceKind} onChange={e=>{setSourceKind(e.target.value);setDownloadAllowed(false);if(e.target.value==='RECORDED_CLASS')setResourceType('VIDEO');if(e.target.value==='LEARNING_DOCUMENT')setResourceType('PDF');if(e.target.value==='EXTERNAL_URL')setResourceType('OTHER');}}>{sources.map(s=><option value={s} key={s}>{label(s)}</option>)}</select></label>
    <label className={styles.field}>Language<select value={language} onChange={e=>setLanguage(e.target.value)}><option value="hi">Hindi</option><option value="en">English</option><option value="mul">Language independent</option></select></label>
    <label className={styles.field+' '+styles.wide}>Description<textarea rows={3} value={description} maxLength={1600} onChange={e=>setDescription(e.target.value)} placeholder="What will the learner use this resource for?"/></label>
    {sourceKind==='PRIVATE_UPLOAD'&&<label className={styles.field+' '+styles.wide}>Private file<input type="file" onChange={e=>setFile(e.target.files?.[0]||null)}/><small>Checks, file size validation and SHA-256 are handled by the API.</small></label>}
    {sourceKind==='EXTERNAL_URL'&&<label className={styles.field+' '+styles.wide}>External HTTPS URL<input type="url" value={externalUrl} onChange={e=>setExternalUrl(e.target.value)} placeholder="https://…"/></label>}
    {['RECORDED_CLASS','LEARNING_DOCUMENT','CONTENT_ASSET'].includes(sourceKind)&&<label className={styles.field}>Existing source ID<input inputMode="numeric" value={sourceId} onChange={e=>setSourceId(e.target.value)} placeholder="Numeric QuantaEdge ID"/><small>Do not upload a duplicate; reference the original record.</small></label>}
    {['PRIVATE_UPLOAD','RECORDED_CLASS'].includes(sourceKind)&&<label className={styles.toggle}><input type="checkbox" checked={downloadAllowed} onChange={e=>setDownloadAllowed(e.target.checked)}/><span><b>Allow managed downloads</b><small>Requires publish permission; eligibility is enforced by the download API.</small></span></label>}
   </div>
   <div className={styles.footer}><p>New resources start as drafts. Linked sources must already be published and approved before the learning resource is published.</p><button className={styles.primaryButton} type="button" disabled={busy} onClick={()=>void createResource()}>{busy?'Saving…':'Save resource draft'}</button></div>
  </section>
  <section className={styles.panel}><div className={styles.sectionHead}><div><span>STEP 02</span><h2>Resource library</h2><p>Select a resource to manage publishing, download policy and the placements attached to it.</p></div></div>
   <div className={styles.filters}><label className={styles.field}>Status<select value={statusFilter} onChange={e=>setStatusFilter(e.target.value)}>{['ALL','DRAFT','REVIEW','PUBLISHED','ARCHIVED'].map(x=><option key={x} value={x}>{x==='ALL'?'All statuses':x}</option>)}</select></label><label className={styles.field}>Type<select value={typeFilter} onChange={e=>setTypeFilter(e.target.value)}>{['ALL',...types].map(x=><option key={x} value={x}>{x==='ALL'?'All types':label(x)}</option>)}</select></label></div>
   {loading?<div className={styles.empty}>Loading resources…</div>:resources.length===0?<div className={styles.empty}><b>No resources in this view</b><span>Upload a file or link an existing source to begin.</span></div>:<div className={styles.resourceGrid}>{resources.map(r=><article className={styles.resourceCard+(selectedId===String(r.resource_id)?' '+styles.selected:'')} key={r.resource_id}>
    <button type="button" className={styles.selectResource} onClick={()=>setSelectedId(String(r.resource_id))} aria-pressed={selectedId===String(r.resource_id)}><div className={styles.rowTitle}><span className={styles.type}>{label(r.resource_type)}</span><span className={styles['state'+r.status]}>{r.status}</span></div><h3>{r.title}</h3><p>{r.description||'No description.'}</p><small>{label(r.source_kind)} · {r.language.toUpperCase()} · v{r.version_no}</small><small>{sizeText(r.file_size_bytes)} · {r.assignment_count} placement(s) · {dateText(r.created_at)}</small></button>
    <div className={styles.actions}>{r.status!=='PUBLISHED'&&r.status!=='ARCHIVED'&&<button className={styles.primaryButton} disabled={busy} onClick={()=>void setResourceStatus(r,'PUBLISHED')}>Publish</button>}{r.status==='PUBLISHED'&&<button className={styles.secondaryButton} disabled={busy} onClick={()=>void setResourceStatus(r,'DRAFT')}>Return to draft</button>}{r.status!=='ARCHIVED'&&<button className={styles.secondaryButton} disabled={busy} onClick={()=>void setResourceStatus(r,'ARCHIVED')}>Archive</button>}{r.status==='ARCHIVED'&&<button className={styles.secondaryButton} disabled={busy} onClick={()=>void setResourceStatus(r,'DRAFT')}>Restore draft</button>}{['PRIVATE_UPLOAD','RECORDED_CLASS'].includes(r.source_kind)&&<button className={styles.secondaryButton} disabled={busy} onClick={()=>void setDownload(r,!r.download_allowed)}>{r.download_allowed?'Disable downloads':'Enable downloads'}</button>}</div>
   </article>)}</div>}
  </section>
  <section className={styles.panel}><div className={styles.sectionHead}><div><span>STEP 03</span><h2>Assign to learning content</h2><p>Chapter resources appear across the chapter; lesson and structured artifact assignments are scoped to the matching published learning content.</p></div></div>
   {!selected?<div className={styles.empty}>Select a resource above to manage assignments.</div>:<>
    <div className={styles.selected}><b>{selected.title}</b><small>Resource #{selected.resource_id} · {selected.status}</small></div>
    <div className={styles.grid}>
     <label className={styles.field}>Class<select value={classFilter} onChange={e=>{setClassFilter(e.target.value);setChapterId('');setLessonId('');setArtifactId('');}}><option value="ALL">All classes</option>{classCodes.map(x=><option key={x} value={x}>{x}</option>)}</select></label>
     <label className={styles.field}>Subject<select value={subjectFilter} onChange={e=>{setSubjectFilter(e.target.value);setChapterId('');setLessonId('');setArtifactId('');}}><option value="ALL">All subjects</option>{subjectCodes.map(x=><option key={x} value={x}>{label(x)}</option>)}</select></label>
     <label className={styles.field}>Target<select value={targetKind} onChange={e=>{setTargetKind(e.target.value);setChapterId('');setLessonId('');setArtifactId('');}}><option value="CHAPTER">Chapter</option><option value="LESSON">Legacy lesson</option><option value="STRUCTURED_ARTIFACT">Published structured lesson</option></select></label>
     {targetKind==='CHAPTER'&&<label className={styles.field+' '+styles.wide}>Chapter<select value={chapterId} onChange={e=>setChapterId(e.target.value)}><option value="">Choose chapter…</option>{chapters.map(c=><option key={c.chapter_id} value={c.chapter_id}>{c.class_code} · {c.subject_code} · {c.chapter_code} — {c.chapter_name}</option>)}</select></label>}
     {targetKind==='LESSON'&&<label className={styles.field+' '+styles.wide}>Lesson<select value={lessonId} onChange={e=>{setLessonId(e.target.value);setChapterId(String(targets.lessons.find(x=>String(x.lesson_id)===e.target.value)?.chapter_id||''));}}><option value="">Choose lesson…</option>{lessons.map(l=><option key={l.lesson_id} value={l.lesson_id}>{l.class_code} · {l.subject_code} · {l.chapter_code} · {l.lesson_title} ({l.lesson_status})</option>)}</select></label>}
     {targetKind==='STRUCTURED_ARTIFACT'&&<label className={styles.field+' '+styles.wide}>Structured lesson<select value={artifactId} onChange={e=>{setArtifactId(e.target.value);setChapterId(String(targets.structuredArtifacts.find(x=>x.artifact_id===e.target.value)?.mapped_chapter_id||''));}}><option value="">Choose structured lesson…</option>{artifacts.map(a=><option key={a.artifact_id} value={a.artifact_id}>{a.class_code} · {a.subject_code} · Ch {a.chapter_number} · {a.title}{a.mapped_chapter_id?' (mapped)':' (needs mapping)'}</option>)}</select></label>}
     {targetKind==='STRUCTURED_ARTIFACT'&&!targets.structuredArtifacts.find(x=>x.artifact_id===artifactId)?.mapped_chapter_id&&<label className={styles.field+' '+styles.wide}>Canonical chapter mapping<select value={chapterId} onChange={e=>setChapterId(e.target.value)}><option value="">Choose chapter…</option>{chapters.map(c=><option key={c.chapter_id} value={c.chapter_id}>{c.class_code} · {c.subject_code} · {c.chapter_code} — {c.chapter_name}</option>)}</select></label>}
     <label className={styles.field}>Display order<input type="number" min="0" max="1000000" value={sortOrder} onChange={e=>setSortOrder(e.target.value)}/></label>
    </div>
    <div className={styles.footer}><p>A placement is drafted first. Publish the resource and ensure the chapter/lesson is published before publishing its assignment.</p><button className={styles.primaryButton} type="button" disabled={busy||selected.status!=='PUBLISHED'} onClick={()=>void createAssignment()}>Create draft placement</button></div>
    <h3 className={styles.listHeading}>Placements for this resource</h3>
    {assignments.length===0?<div className={styles.empty}>No placements have been created.</div>:<div className={styles.assignmentList}>{assignments.map(a=><article className={styles.assignmentCard} key={a.assignment_id}><div><div className={styles.rowTitle}><b>#{a.assignment_id} · {label(a.target_kind)}</b><span className={styles['state'+a.status]}>{a.status}</span></div><p>{a.class_code} · {a.subject_code} · {a.chapter_code} — {a.chapter_name}{a.lesson_title?' · '+a.lesson_title:''}{a.artifact_id?' · '+a.artifact_id:''}</p><small>Display order: {a.sort_order}</small></div><div className={styles.assignmentActions}><button className={styles.secondaryButton} disabled={busy} onClick={()=>void reorder(a,a.sort_order+1)}>Move later</button>{a.status!=='PUBLISHED'&&a.status!=='ARCHIVED'&&<button className={styles.primaryButton} disabled={busy||selected.status!=='PUBLISHED'} onClick={()=>void setAssignmentStatus(a,'PUBLISHED')}>Publish placement</button>}{a.status==='PUBLISHED'&&<button className={styles.secondaryButton} disabled={busy} onClick={()=>void setAssignmentStatus(a,'DRAFT')}>Return to draft</button>}{a.status!=='ARCHIVED'&&<button className={styles.secondaryButton} disabled={busy} onClick={()=>void setAssignmentStatus(a,'ARCHIVED')}>Archive placement</button>}{a.status==='ARCHIVED'&&<button className={styles.secondaryButton} disabled={busy} onClick={()=>void setAssignmentStatus(a,'DRAFT')}>Restore placement</button>}</div></article>)}</div>}
   </>}
  </section>
 </section></main>;
}
