'use client';

import { formatUserTimestamp } from '../components/formatUserTimestamp';

import Link from 'next/link';
import AdminSidebar from '../components/AdminSidebar';
import PremiumPagination from '../components/PremiumPagination';
import {useContext,useEffect,useState} from 'react';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import { useUiTranslation } from '../i18n/useUiTranslation';
import {useRouter} from 'next/navigation';

type ParentRow={id:number;display_name:string;mobile_e164:string|null;contact_email?:string|null;google_email?:string|null;gmail_verified:boolean;mobile_verified:boolean;username?:string|null;active:boolean;created_at:string;active_children:number;linked_children:number};
type ChildRow={id:number;display_name:string;class_code:string;board:string;language:string;student_active:boolean;relationship_active:boolean;consent_status:string;track_codes:string;public_id:string};
const tracks=[{code:'maths'},{code:'science'}];

async function json(response:Response):Promise<any>{
  const raw=await response.text();
  try{return raw?JSON.parse(raw):{};}catch{throw new Error(`Unexpected server response (HTTP ${response.status}).`);}
}

export default function AdminParents(){
  const router=useRouter();
  const t=useUiTranslation();
  const { locale }=useContext(AdminWorkspaceContext);
  const [parents,setParents]=useState<ParentRow[]>([]);
  const [expanded,setExpanded]=useState<number|null>(null);
  const [children,setChildren]=useState<Record<number,ChildRow[]>>({});
  const [trackDraft,setTrackDraft]=useState<Record<number,string[]>>({});
  const [search,setSearch]=useState('');
  const [page,setPage]=useState(0);
  const [total,setTotal]=useState(0);
  const [pageSize,setPageSize]=useState(10);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [busy,setBusy]=useState<string|null>(null);

  async function loadParents(){
    const mr=await fetch('/api/v1/auth/me');
    if(!mr.ok){router.replace('/login');return;}
    const me=await json(mr);
    if(me.role!=='ADMIN'){router.replace('/login');return;}
    const params=new URLSearchParams({limit:String(pageSize),offset:String(page*pageSize)});
    if(search.trim())params.set('query',search.trim());
    const r=await fetch('/api/v1/admin/parents?'+params.toString(),{cache:'no-store'});
    const body=await json(r);
    if(!r.ok)throw new Error(body.message||t('admin.parents.loadFailed'));
    setParents(Array.isArray(body.items)?body.items:(Array.isArray(body)?body:[]));
    setTotal(Number(body.total ?? (Array.isArray(body)?body.length:0)));
  }

  useEffect(()=>{loadParents().catch((e:any)=>setError(e.message||t('admin.parents.loadFailed')));},[router,search,page,pageSize,t]);

  async function toggleParent(row:ParentRow){
    const active=!row.active;
    if(!active&&!window.confirm(t('admin.parents.confirmSuspendParent',{name:row.display_name||row.mobile_e164})))return;
    setBusy(`parent-${row.id}`);setError('');setNotice('');
    try{
      const r=await fetch(`/api/v1/admin/parents/${row.id}/status`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({active})});
      const b=await json(r);if(!r.ok)throw new Error(b.message||t('admin.parents.updateStatusFailed'));
      await loadParents();setNotice(active?t('admin.parents.parentReactivated'):t('admin.parents.parentSuspended'));
    }catch(e:any){setError(e.message||t('admin.parents.updateStatusFailed'));}
    finally{setBusy(null);}
  }

  async function toggleExpand(parentId:number){
    if(expanded===parentId){setExpanded(null);return;}
    setExpanded(parentId);setError('');
    try{
      const r=await fetch(`/api/v1/admin/parents/${parentId}/children`);
      const b=await json(r);if(!r.ok)throw new Error(b.message||t('admin.parents.loadChildrenFailed'));
      setChildren(current=>({...current,[parentId]:b}));
      setTrackDraft(current=>({...current,...Object.fromEntries(b.map((c:ChildRow)=>[c.id,(c.track_codes||'').split(',').filter(Boolean)]))}));
    }catch(e:any){setError(e.message||t('admin.parents.loadChildrenFailed'));}
  }

  function toggleTrack(studentId:number,code:string,checked:boolean){
    setTrackDraft(current=>{
      const next=new Set(current[studentId]||[]);
      checked?next.add(code):next.delete(code);
      return {...current,[studentId]:[...next]};
    });
  }

  async function saveTracks(child:ChildRow){
    const trackCodes=trackDraft[child.id]||[];
    setBusy(`tracks-${child.id}`);setError('');setNotice('');
    try{
      const r=await fetch(`/api/v1/admin/students/${child.id}/tracks`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({trackCodes})});
      const b=await json(r);if(!r.ok)throw new Error(b.message||t('admin.parents.saveTracksFailed'));
      setChildren(current=>({...current,...Object.fromEntries(Object.entries(current).map(([parentId,rows])=>[parentId,rows.map(c=>c.id===child.id?{...c,track_codes:trackCodes.join(',')}:c)]))}));
      setNotice(t('admin.parents.subjectAccessUpdated',{name:child.display_name}));
    }catch(e:any){setError(e.message||t('admin.parents.saveTracksFailed'));}
    finally{setBusy(null);}
  }

  async function toggleStudent(child:ChildRow){
    const active=!child.student_active;
    if(!active&&!window.confirm(t('admin.parents.confirmSuspendStudent',{name:child.display_name})))return;
    setBusy(`student-${child.id}`);setError('');setNotice('');
    try{
      const r=await fetch(`/api/v1/admin/students/${child.id}/status`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({active})});
      const b=await json(r);if(!r.ok)throw new Error(b.message||t('admin.parents.updateStudentFailed'));
      setChildren(current=>({...current,...Object.fromEntries(Object.entries(current).map(([parentId,rows])=>[parentId,rows.map(c=>c.id===child.id?{...c,student_active:active}:c)]))}));
      setNotice(active?t('admin.parents.studentAccessReactivated',{name:child.display_name}):t('admin.parents.studentAccessSuspended',{name:child.display_name}));
    }catch(e:any){setError(e.message||t('admin.parents.updateStudentFailed'));}
    finally{setBusy(null);}
  }

  const visible=parents;
  const activeCount=parents.filter(p=>p.active).length;
  const childCount=parents.reduce((sum,p)=>sum+Number(p.active_children||0),0);

  return <main className="admin-shell">
    <AdminSidebar active="parents" />
    <section className="admin-main admin-parents-main">
      <header className="admin-top">
        <div><span className="admin-kicker">{t('admin.parents.eyebrow')}</span><h1>{t('admin.parents.title')}</h1><p>{t('admin.parents.description')}</p></div>
        <Link href="/" className="button button-dark">{t('admin.parents.dashboard')}</Link>
      </header>
      {error&&<div className="feedback" role="alert">{error}</div>}
      {notice&&<div className="parent-notice" role="status">{notice}</div>}
      <section className="admin-grid stats parent-admin-kpis">
        <article><span>{t('admin.parents.accounts')}</span><strong>{total}</strong><small>{t('admin.parents.matchingAccounts',{count:total})}</small></article>
        <article><span>{t('admin.parents.activeParents')}</span><strong>{activeCount}</strong><small>{t('admin.parents.onThisPage')}</small></article>
        <article><span>{t('admin.parents.activeLinks')}</span><strong>{childCount}</strong><small>{t('admin.parents.onThisPage')}</small></article>
        <article><span>{t('admin.parents.defaultChildLimit')}</span><strong>3</strong><small>{t('admin.parents.environmentConfigurable')}</small></article>
      </section>
      <section className="admin-panel parent-admin-panel">
        <div className="panel-head">
          <div><span>{t('admin.parents.directory')}</span><h2>{t('admin.parents.registeredParents')}</h2></div>
          <span className="count">{t('admin.parents.matchingAccounts',{count:total})}</span>
        </div>
        <label className="parent-search"><span>{t('admin.parents.search')}</span><input value={search} onChange={e=>{setSearch(e.target.value);setPage(0)}} placeholder={t("admin.parents.searchPlaceholder")}/></label>
        {!visible.length?<div className="empty-state"><span>⌕</span><h2>{t('admin.parents.noMatches')}</h2><p>{t('admin.parents.tryAnotherSearch')}</p></div>:
          <div className="parent-admin-list">
            {visible.map(parent=><article className="parent-admin-row" key={parent.id}>
              <div className="parent-admin-summary">
                <div className="admin-parent-avatar">{(parent.display_name||'?').trim().slice(0,1).toUpperCase()}</div>
                <div className="parent-admin-identity"><strong>{parent.display_name||t('admin.parents.parentFallback')}</strong><span>{parent.mobile_e164||'—'}</span>
                  <small>{parent.gmail_verified && parent.google_email ? 'Gmail ✓ ' + parent.google_email : (locale === 'english' ? 'Gmail not verified' : 'Gmail सत्यापित नहीं')}</small>
                  <small>{parent.mobile_verified ? (locale === 'english' ? 'Mobile verified' : 'मोबाइल सत्यापित') : (locale === 'english' ? 'Mobile not verified' : 'मोबाइल सत्यापित नहीं')}</small>
                  {parent.username&&<small>{(locale === 'english' ? 'Username: ' : 'यूज़रनेम: ')+parent.username}</small>}
                  <small>{t('admin.parents.joined',{date:parent.created_at?formatUserTimestamp(parent.created_at):'—'})}</small></div>
                <div className="parent-admin-count"><strong>{parent.active_children}</strong><span>{t('admin.parents.activeChildren')}</span></div>
                <span className={`admin-status-pill ${parent.active?'active':'inactive'}`}>{parent.active?t('admin.parents.active'):t('admin.parents.suspended')}</span>
                <button className="parent-secondary-button" onClick={()=>toggleExpand(parent.id)}>{expanded===parent.id?t('admin.parents.hideChildren'):t('admin.parents.viewChildren')} <span>{expanded===parent.id?'↑':'↓'}</span></button>
                <button className={`admin-status-button ${parent.active?'suspend':'activate'}`} disabled={busy===`parent-${parent.id}`} onClick={()=>toggleParent(parent)}>{busy===`parent-${parent.id}`?t('admin.parents.saving'):parent.active?t('admin.parents.suspend'):t('admin.parents.reactivate')}</button>
              </div>
              {expanded===parent.id&&<div className="parent-admin-children">
                <div className="child-admin-heading"><div><strong>{t('admin.parents.linkedChildren')}</strong><span>{t('admin.parents.accessEnforcedByApi')}</span></div><span className="count">{t('admin.parents.linkedCount',{count:(children[parent.id]||[]).length})}</span></div>
                {!(children[parent.id]||[]).length?<p className="child-admin-empty">{t('admin.parents.noChildProfile')}</p>:
                  (children[parent.id]||[]).map(child=><div className="child-admin-row" key={child.id}>
                    <div className="child-admin-meta"><strong>{child.display_name}</strong><span>{t('admin.parents.classCode',{code:child.class_code})} · {child.board}</span><small>{!child.relationship_active?t('admin.parents.archivedRelationship'):!child.student_active?t('admin.parents.studentAccessSuspended'):t('admin.parents.consentStatus',{status:(child.consent_status||'unknown').toLowerCase()})}</small></div>
                    <div className="child-admin-tracks"><span>{t('admin.parents.learningTracks')}</span>{tracks.map(track=><label key={track.code}><input type="checkbox" checked={(trackDraft[child.id]||[]).includes(track.code)} disabled={!child.student_active||!child.relationship_active||!parent.active} onChange={e=>toggleTrack(child.id,track.code,e.target.checked)}/>{track.code==='maths'?t('admin.parents.maths'):t('admin.parents.science')}</label>)}
                      <button className="parent-secondary-button" disabled={!child.student_active||!child.relationship_active||!parent.active||busy===`tracks-${child.id}`||(trackDraft[child.id]||[]).length===0} onClick={()=>saveTracks(child)}>{busy===`tracks-${child.id}`?t('admin.parents.saving'):t('admin.parents.saveTracks')}</button>
                    </div>
                    <button className={`admin-status-button ${child.student_active?'suspend':'activate'}`} disabled={!child.relationship_active||busy===`student-${child.id}`} onClick={()=>toggleStudent(child)}>{busy===`student-${child.id}`?t('admin.parents.saving'):child.student_active?t('admin.parents.suspendStudent'):t('admin.parents.reactivateStudent')}</button>
                  </div>)}
              </div>}
            </article>)}
          </div>}
        <PremiumPagination page={page} total={total} pageSize={pageSize} onPageChange={setPage}
          onPageSizeChange={size=>{setPageSize(size);setPage(0);}} itemLabel={t("admin.parents.itemLabel")} />
      </section>
      <p className="admin-footer">{t('admin.parents.footer')}</p>
    </section>
  </main>;
}
