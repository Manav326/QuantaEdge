'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type ParentRow={id:number;display_name:string;mobile_e164:string;active:boolean;created_at:string;active_children:number;linked_children:number};
type ChildRow={id:number;display_name:string;class_code:string;board:string;language:string;student_active:boolean;relationship_active:boolean;consent_status:string;track_codes:string;public_id:string};
const tracks=[{code:'maths',label:'गणित · Maths'},{code:'science',label:'विज्ञान · Science'}];

async function json(response:Response):Promise<any>{
  const raw=await response.text();
  try{return raw?JSON.parse(raw):{};}catch{throw new Error(`Unexpected server response (HTTP ${response.status}).`);}
}

export default function AdminParents(){
  const router=useRouter();
  const [parents,setParents]=useState<ParentRow[]>([]);
  const [expanded,setExpanded]=useState<number|null>(null);
  const [children,setChildren]=useState<Record<number,ChildRow[]>>({});
  const [trackDraft,setTrackDraft]=useState<Record<number,string[]>>({});
  const [search,setSearch]=useState('');
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [busy,setBusy]=useState<string|null>(null);

  async function loadParents(){
    const mr=await fetch('/api/v1/auth/me');
    if(!mr.ok){router.replace('/login');return;}
    const me=await json(mr);
    if(me.role!=='ADMIN'){router.replace('/login');return;}
    const r=await fetch('/api/v1/admin/parents');
    const body=await json(r);
    if(!r.ok)throw new Error(body.message||'Unable to load parent accounts.');
    setParents(body);
  }

  useEffect(()=>{loadParents().catch((e:any)=>setError(e.message||'Unable to load parent accounts.'));},[router]);

  async function toggleParent(row:ParentRow){
    const active=!row.active;
    if(!active&&!window.confirm(`Suspend ${row.display_name||row.mobile_e164}'s parent account? Their active sessions will be revoked.`))return;
    setBusy(`parent-${row.id}`);setError('');setNotice('');
    try{
      const r=await fetch(`/api/v1/admin/parents/${row.id}/status`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({active})});
      const b=await json(r);if(!r.ok)throw new Error(b.message||'Unable to update parent status.');
      await loadParents();setNotice(`Parent account ${active?'reactivated':'suspended'}.`);
    }catch(e:any){setError(e.message||'Unable to update parent status.');}
    finally{setBusy(null);}
  }

  async function toggleExpand(parentId:number){
    if(expanded===parentId){setExpanded(null);return;}
    setExpanded(parentId);setError('');
    try{
      const r=await fetch(`/api/v1/admin/parents/${parentId}/children`);
      const b=await json(r);if(!r.ok)throw new Error(b.message||'Unable to load child profiles.');
      setChildren(current=>({...current,[parentId]:b}));
      setTrackDraft(current=>({...current,...Object.fromEntries(b.map((c:ChildRow)=>[c.id,(c.track_codes||'').split(',').filter(Boolean)]))}));
    }catch(e:any){setError(e.message||'Unable to load child profiles.');}
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
      const b=await json(r);if(!r.ok)throw new Error(b.message||'Unable to save subject access.');
      setChildren(current=>({...current,...Object.fromEntries(Object.entries(current).map(([parentId,rows])=>[parentId,rows.map(c=>c.id===child.id?{...c,track_codes:trackCodes.join(',')}:c)]))}));
      setNotice(`Subject access updated for ${child.display_name}.`);
    }catch(e:any){setError(e.message||'Unable to save subject access.');}
    finally{setBusy(null);}
  }

  async function toggleStudent(child:ChildRow){
    const active=!child.student_active;
    if(!active&&!window.confirm(`Suspend ${child.display_name}'s learning access? Their progress will be retained.`))return;
    setBusy(`student-${child.id}`);setError('');setNotice('');
    try{
      const r=await fetch(`/api/v1/admin/students/${child.id}/status`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({active})});
      const b=await json(r);if(!r.ok)throw new Error(b.message||'Unable to update student status.');
      setChildren(current=>({...current,...Object.fromEntries(Object.entries(current).map(([parentId,rows])=>[parentId,rows.map(c=>c.id===child.id?{...c,student_active:active}:c)]))}));
      setNotice(`${child.display_name}'s learning access ${active?'reactivated':'suspended'}.`);
    }catch(e:any){setError(e.message||'Unable to update student status.');}
    finally{setBusy(null);}
  }

  const visible=parents.filter(p=>[p.display_name,p.mobile_e164].some(x=>(x||'').toLowerCase().includes(search.toLowerCase())));
  const activeCount=parents.filter(p=>p.active).length;
  const childCount=parents.reduce((sum,p)=>sum+Number(p.active_children||0),0);

  return <main className="admin-shell">
    <aside className="admin-sidebar">
      <Link href="/" className="admin-brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>ADMIN</small></span></Link>
      <nav><div className="nav-label">WORKSPACE</div><Link href="/">▦ Dashboard</Link><Link href="/content">◈ Content Studio</Link><Link href="/students">◉ Students</Link><Link href="/parents" className="active">♧ Parents & families</Link><Link href="/legacy-content">Detailed authoring</Link></nav>
    </aside>
    <section className="admin-main admin-parents-main">
      <header className="admin-top">
        <div><span className="admin-kicker">FAMILY & ACCESS MANAGEMENT</span><h1>Parents & children</h1><p>Manage parent access, child profiles and per-child learning subscriptions.</p></div>
        <Link href="/" className="button button-dark">← Dashboard</Link>
      </header>
      {error&&<div className="feedback" role="alert">{error}</div>}
      {notice&&<div className="parent-notice" role="status">{notice}</div>}
      <section className="admin-grid stats parent-admin-kpis">
        <article><span>Parent accounts</span><strong>{parents.length}</strong><small>registered accounts</small></article>
        <article><span>Active parents</span><strong>{activeCount}</strong><small>eligible to sign in</small></article>
        <article><span>Active child profiles</span><strong>{childCount}</strong><small>production students</small></article>
        <article><span>Default child limit</span><strong>3</strong><small>environment configurable</small></article>
      </section>
      <section className="admin-panel parent-admin-panel">
        <div className="panel-head">
          <div><span>ACCOUNT DIRECTORY</span><h2>Registered parents</h2></div>
          <span className="count">{visible.length} accounts</span>
        </div>
        <label className="parent-search"><span>Search parent</span><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Name or mobile number"/></label>
        {!visible.length?<div className="empty-state"><span>⌕</span><h2>No matching parent accounts</h2><p>Try a different mobile number or name.</p></div>:
          <div className="parent-admin-list">
            {visible.map(parent=><article className="parent-admin-row" key={parent.id}>
              <div className="parent-admin-summary">
                <div className="admin-parent-avatar">{(parent.display_name||'?').trim().slice(0,1).toUpperCase()}</div>
                <div className="parent-admin-identity"><strong>{parent.display_name||'Parent'}</strong><span>{parent.mobile_e164}</span><small>Joined {parent.created_at?new Date(parent.created_at).toLocaleDateString():'—'}</small></div>
                <div className="parent-admin-count"><strong>{parent.active_children}</strong><span>active children</span></div>
                <span className={`admin-status-pill ${parent.active?'active':'inactive'}`}>{parent.active?'Active':'Suspended'}</span>
                <button className="parent-secondary-button" onClick={()=>toggleExpand(parent.id)}>{expanded===parent.id?'Hide children':'View children'} <span>{expanded===parent.id?'↑':'↓'}</span></button>
                <button className={`admin-status-button ${parent.active?'suspend':'activate'}`} disabled={busy===`parent-${parent.id}`} onClick={()=>toggleParent(parent)}>{busy===`parent-${parent.id}`?'Saving…':parent.active?'Suspend':'Reactivate'}</button>
              </div>
              {expanded===parent.id&&<div className="parent-admin-children">
                <div className="child-admin-heading"><div><strong>Linked child profiles</strong><span>Subject access is enforced by the API, not just the interface.</span></div><span className="count">{(children[parent.id]||[]).length} linked</span></div>
                {!(children[parent.id]||[]).length?<p className="child-admin-empty">No production child profile is linked to this account.</p>:
                  (children[parent.id]||[]).map(child=><div className="child-admin-row" key={child.id}>
                    <div className="child-admin-meta"><strong>{child.display_name}</strong><span>Class {child.class_code} · {child.board}</span><small>{!child.relationship_active?'Archived relationship':!child.student_active?'Student access suspended':'Consent '+(child.consent_status||'unknown').toLowerCase()}</small></div>
                    <div className="child-admin-tracks"><span>Learning tracks</span>{tracks.map(track=><label key={track.code}><input type="checkbox" checked={(trackDraft[child.id]||[]).includes(track.code)} disabled={!child.student_active||!child.relationship_active||!parent.active} onChange={e=>toggleTrack(child.id,track.code,e.target.checked)}/>{track.code==='maths'?'Maths':'Science'}</label>)}
                      <button className="parent-secondary-button" disabled={!child.student_active||!child.relationship_active||!parent.active||busy===`tracks-${child.id}`||(trackDraft[child.id]||[]).length===0} onClick={()=>saveTracks(child)}>{busy===`tracks-${child.id}`?'Saving…':'Save tracks'}</button>
                    </div>
                    <button className={`admin-status-button ${child.student_active?'suspend':'activate'}`} disabled={!child.relationship_active||busy===`student-${child.id}`} onClick={()=>toggleStudent(child)}>{busy===`student-${child.id}`?'Saving…':child.student_active?'Suspend student':'Reactivate student'}</button>
                  </div>)}
              </div>}
            </article>)}
          </div>}
      </section>
      <p className="admin-footer">Suspensions revoke active sessions. Archiving a parent-child link and suspending a student are separate actions; neither deletes historical learning data.</p>
    </section>
  </main>;
}
