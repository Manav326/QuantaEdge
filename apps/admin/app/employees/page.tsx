'use client';

import { useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import AdminSidebar from '../components/AdminSidebar';

type StaffRow = {
  id:number; public_id:string; mobile_e164:string; display_name:string; role:string; active:boolean;
  created_at:string; updated_at:string; last_login_at:string|null; permissions:string[]; isCurrentStaff?:boolean;
};
type Permission = { permission_key:string; display_name:string; description:string };
type AuditRow = {
  id:number; actor_staff_id:number|null; actor_role:string; http_method:string; request_path:string;
  response_status:number; remote_address:string|null; user_agent:string|null; created_at:string; actor_name:string;
};

const roleOptions = [
  {value:'CONTENT_AUTHOR',label:'Content author'},
  {value:'CONTENT_REVIEWER',label:'Content reviewer'},
  {value:'MODERATOR',label:'Moderator'},
  {value:'PUBLISHER',label:'Publisher'},
  {value:'MANAGER',label:'Manager'},
  {value:'ADMIN',label:'Administrator'}
];

function defaultsForRole(role:string, permissions:Permission[]):string[] {
  switch(role) {
    case 'ADMIN': return permissions.map(p=>p.permission_key);
    case 'MANAGER': return ['CONTENT_VIEW','CONTENT_CREATE','CONTENT_EDIT','CONTENT_SUBMIT','CONTENT_REVIEW','AUDIT_VIEW'];
    case 'MODERATOR': return ['CONTENT_VIEW','CONTENT_REVIEW'];
    case 'CONTENT_AUTHOR': return ['CONTENT_VIEW','CONTENT_CREATE','CONTENT_EDIT','CONTENT_SUBMIT'];
    case 'CONTENT_REVIEWER': return ['CONTENT_VIEW','CONTENT_REVIEW'];
    case 'PUBLISHER': return ['CONTENT_VIEW','CONTENT_PUBLISH'];
    default: return ['CONTENT_VIEW'];
  }
}

async function readJson(response:Response):Promise<any> {
  const raw=await response.text();
  let value:any={};
  try { value=raw?JSON.parse(raw):{}; } catch { value={message:raw}; }
  if(!response.ok) throw new Error(value.detail||value.message||value.error||('Request failed (HTTP '+response.status+').'));
  return value;
}

function prettyDate(value:string|null|undefined) {
  if(!value)return 'Never';
  const date=new Date(value);
  return Number.isNaN(date.getTime())?'—':date.toLocaleString();
}

export default function AdminStaffPage() {
  const router=useRouter();
  const [me,setMe]=useState<any>(null);
  const [staff,setStaff]=useState<StaffRow[]>([]);
  const [availablePermissions,setAvailablePermissions]=useState<Permission[]>([]);
  const [audit,setAudit]=useState<AuditRow[]>([]);
  const [tab,setTab]=useState<'staff'|'audit'>('staff');
  const [search,setSearch]=useState('');
  const [selected,setSelected]=useState<StaffRow|null>(null);
  const [draftRole,setDraftRole]=useState('CONTENT_AUTHOR');
  const [draftPermissions,setDraftPermissions]=useState<string[]>([]);
  const [initialRole,setInitialRole]=useState('');
  const [initialPermissions,setInitialPermissions]=useState<string[]>([]);
  const [newName,setNewName]=useState('');
  const [newMobile,setNewMobile]=useState('');
  const [newRole,setNewRole]=useState('CONTENT_AUTHOR');
  const [newPermissions,setNewPermissions]=useState<string[]>([]);
  const [loading,setLoading]=useState(true);
  const [saving,setSaving]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');

  const isAdmin=me?.role==='ADMIN';
  const permissions=Array.isArray(me?.permissions)?me.permissions:[];
  const canAudit=isAdmin||permissions.includes('AUDIT_VIEW');
  const canManageStaff=isAdmin;
  const filteredStaff=useMemo(()=>staff.filter(row=>
    [row.display_name,row.mobile_e164,row.role].some(value=>String(value||'').toLowerCase().includes(search.toLowerCase()))
  ),[staff,search]);
  const editDirty=Boolean(selected&&(draftRole!==initialRole||JSON.stringify([...draftPermissions].sort())!==JSON.stringify([...initialPermissions].sort())));
  const newReady=newName.trim().length>=2&&newMobile.replace(/\D/g,'').length===10;
  const adminPermissionsComplete=newRole!=='ADMIN'||newPermissions.length===availablePermissions.length;

  async function loadStaff() {
    const r=await fetch('/api/v1/admin/staff',{cache:'no-store'});
    const data=await readJson(r);
    setStaff(Array.isArray(data)?data:[]);
  }
  async function loadAudit() {
    const r=await fetch('/api/v1/admin/staff/audit?limit=300',{cache:'no-store'});
    const data=await readJson(r);
    setAudit(Array.isArray(data)?data:[]);
  }
  async function loadAdminData(admin:boolean,hasAudit:boolean) {
    const tasks:Promise<any>[]=[];
    if(admin) {
      tasks.push(fetch('/api/v1/admin/staff',{cache:'no-store'}).then(readJson).then(data=>setStaff(Array.isArray(data)?data:[])));
      tasks.push(fetch('/api/v1/admin/staff/permissions',{cache:'no-store'}).then(readJson).then(data=>{
        const rows=Array.isArray(data)?data:[];
        setAvailablePermissions(rows);
        setNewPermissions(defaultsForRole('CONTENT_AUTHOR',rows));
      }));
    }
    if(hasAudit) tasks.push(fetch('/api/v1/admin/staff/audit?limit=300',{cache:'no-store'}).then(readJson).then(data=>setAudit(Array.isArray(data)?data:[])));
    await Promise.all(tasks);
  }

  useEffect(()=>{
    let mounted=true;
    (async()=>{
      try {
        const response=await fetch('/api/v1/auth/me',{cache:'no-store'});
        const identity=await readJson(response);
        if(!identity.staffId) { router.replace('/login'); return; }
        if(!mounted)return;
        setMe(identity);
        const admin=identity.role==='ADMIN';
        const hasAudit=admin||(Array.isArray(identity.permissions)&&identity.permissions.includes('AUDIT_VIEW'));
        const searchParams=new URLSearchParams(window.location.search);
        setTab(searchParams.get('view')==='audit'||!admin?'audit':'staff');
        if(!admin&&!hasAudit) { router.replace('/content'); return; }
        await loadAdminData(admin,hasAudit);
      } catch(e:any) {
        if(mounted)setError(e.message||'Staff workspace could not be loaded.');
      } finally {
        if(mounted)setLoading(false);
      }
    })();
    return ()=>{mounted=false;};
  },[router]);

  function startEdit(row:StaffRow) {
    setSelected(row);
    setDraftRole(row.role);
    setInitialRole(row.role);
    setDraftPermissions([...row.permissions]);
    setInitialPermissions([...row.permissions]);
    setError('');
    setNotice('');
    setTab('staff');
  }

  function toggleDraftPermission(key:string,checked:boolean,creating=false) {
    const current=creating?newPermissions:draftPermissions;
    const next=checked?[...new Set([...current,key])]:current.filter(item=>item!==key);
    if(creating)setNewPermissions(next);else setDraftPermissions(next);
  }

  function changeNewRole(role:string) {
    setNewRole(role);
    setNewPermissions(defaultsForRole(role,availablePermissions));
  }

  function changeDraftRole(role:string) {
    setDraftRole(role);
    setDraftPermissions(defaultsForRole(role,availablePermissions));
  }

  async function createStaff() {
    setSaving(true);setError('');setNotice('');
    try {
      const response=await fetch('/api/v1/admin/staff',{
        method:'POST',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({displayName:newName.trim(),mobile:newMobile,role:newRole,permissions:newPermissions})
      });
      await readJson(response);
      setNewName('');setNewMobile('');changeNewRole('CONTENT_AUTHOR');
      await loadStaff();
      setNotice('Staff account created. The person can now use Staff access with their assigned mobile number.');
    } catch(e:any) {setError(e.message||'Staff account could not be created.');}
    finally {setSaving(false);}
  }

  async function savePermissions() {
    if(!selected)return;
    setSaving(true);setError('');setNotice('');
    try {
      const response=await fetch('/api/v1/admin/staff/'+selected.id+'/permissions',{
        method:'PUT',headers:{'Content-Type':'application/json'},
        body:JSON.stringify({role:draftRole,permissions:draftPermissions})
      });
      const updated=await readJson(response);
      setStaff(rows=>rows.map(row=>row.id===selected.id?updated:row));
      setSelected(updated);
      setInitialRole(updated.role);
      setDraftRole(updated.role);
      setInitialPermissions([...updated.permissions]);
      setDraftPermissions([...updated.permissions]);
      setNotice('Permissions updated. The new grants are checked by the API on each request.');
    } catch(e:any) {setError(e.message||'Permissions could not be saved.');}
    finally {setSaving(false);}
  }

  async function toggleActive(row:StaffRow) {
    if(row.isCurrentStaff&&!row.active)return;
    const next=!row.active;
    if(!next&&!window.confirm('Suspend '+row.display_name+'? Their active staff sessions will be revoked immediately. Audit history will be retained.'))return;
    setSaving(true);setError('');setNotice('');
    try {
      const response=await fetch('/api/v1/admin/staff/'+row.id+'/status',{
        method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({active:next})
      });
      const updated=await readJson(response);
      setStaff(rows=>rows.map(item=>item.id===row.id?{...item,...updated}:item));
      if(selected?.id===row.id) {
        const merged={...selected,...updated} as StaffRow;
        setSelected(merged);
      }
      setNotice(updated.message||('Staff account '+(next?'activated.':'suspended.')));
    } catch(e:any) {setError(e.message||'Staff status could not be updated.');}
    finally {setSaving(false);}
  }

  if(loading)return <main className="admin-shell"><AdminSidebar active="staff"/><section className="admin-main"><div className="admin-loading"><span className="admin-kicker">STAFF & GOVERNANCE</span><h1>Preparing staff workspace</h1><p>Loading staff identities, permission grants and activity history…</p></div></section></main>;

  return <main className="admin-shell">
    <AdminSidebar active="staff"/>
    <section className="admin-main qe-staff-main">
      <header className="admin-top">
        <div><span className="admin-kicker">ACCESS CONTROL / GOVERNANCE</span><h1>{isAdmin?'Staff & permissions':'Staff activity trail'}</h1><p>{isAdmin?'Keep staff identities separate from parent/student accounts and grant only the tasks each person needs.':'Review recorded staff activity. Your account cannot manage staff identities.'}</p></div>
        <span className="top-status"><span className="status-dot"/>{isAdmin?'Staff directory · live permissions':'Read-only audit access'}</span>
      </header>

      <section className="qe-staff-principles">
        <span className="qe-staff-principle-mark">✓</span>
        <div><strong>Separate identities. Least privilege. Traceable actions.</strong><p>Staff accounts are stored separately from parent/student accounts. Suspending staff revokes their sessions without deleting their audit history.</p></div>
      </section>

      {error&&<div className="admin-alert qe-feedback" role="alert"><strong>Needs attention</strong><span>{error}</span><button type="button" onClick={()=>setError('')}>Dismiss</button></div>}
      {notice&&<div className="admin-notice qe-feedback" role="status"><strong>Workspace update</strong><span>{notice}</span><button type="button" onClick={()=>setNotice('')}>Dismiss</button></div>}

      <div className="qe-staff-tabs">
        {isAdmin&&<button type="button" className={tab==='staff'?'active':''} onClick={()=>setTab('staff')}>Staff directory <b>{staff.length}</b></button>}
        {canAudit&&<button type="button" className={tab==='audit'?'active':''} onClick={()=>setTab('audit')}>Audit trail <b>{audit.length}</b></button>}
      </div>

      {tab==='staff'&&isAdmin&&<div className="qe-staff-layout">
        <section className="admin-panel qe-staff-directory">
          <div className="panel-head"><div><span className="admin-kicker">STAFF IDENTITIES</span><h2>Staff directory</h2><p>These are not parent or student user accounts.</p></div><span className="count">{staff.filter(row=>row.active).length} active</span></div>
          <label className="qe-staff-search">Search staff<input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Name, mobile or role"/></label>
          <div className="qe-staff-list">
            {filteredStaff.map(row=><article className={'qe-staff-row '+(selected?.id===row.id?'selected':'')} key={row.id}>
              <button type="button" className="qe-staff-person" onClick={()=>startEdit(row)}>
                <span className="qe-staff-avatar">{row.display_name.trim().slice(0,1).toUpperCase()}</span>
                <span className="qe-staff-person-copy"><strong>{row.display_name}</strong><small>{row.mobile_e164} · {row.role.replaceAll('_',' ')}</small><small>{row.permissions.length} task permissions</small></span>
                <span className={'qe-status-pill '+(row.active?'published':'archived')}>{row.active?'Active':'Suspended'}</span>
              </button>
              <button type="button" className="qe-staff-row-action" onClick={()=>void toggleActive(row)} disabled={saving||row.isCurrentStaff}>{row.active?'Suspend access':'Reactivate'}</button>
            </article>)}
            {!filteredStaff.length&&<div className="qe-library-empty"><strong>No matching staff accounts</strong><p>Create a staff identity or change the search text.</p></div>}
          </div>
        </section>

        <section className="admin-panel qe-staff-editor">
          {selected?<>
            <div className="panel-head"><div><span className="admin-kicker">PERMISSION ASSIGNMENT</span><h2>{selected.display_name}</h2><p>{selected.mobile_e164} · Role: {selected.role.replaceAll('_',' ')}</p></div><button type="button" className="qe-icon-button" onClick={()=>setSelected(null)} aria-label="Close permission editor">×</button></div>
            <label className="qe-staff-field">Staff role<select value={draftRole} onChange={e=>changeDraftRole(e.target.value)} disabled={saving||selected.isCurrentStaff}><option value="CONTENT_AUTHOR">Content author</option><option value="CONTENT_REVIEWER">Content reviewer</option><option value="MODERATOR">Moderator</option><option value="PUBLISHER">Publisher</option><option value="MANAGER">Manager</option><option value="ADMIN">Administrator</option></select></label>
            <div className="qe-staff-permissions-head"><strong>Task permissions</strong><span>{draftPermissions.length} selected</span></div>
            <p className="qe-staff-help">Choose the tasks this person is allowed to perform. Permissions are enforced by the API, not just by hiding buttons.</p>
            <div className="qe-staff-permission-list">{availablePermissions.map(permission=><label key={permission.permission_key} className="qe-permission-option">
              <input type="checkbox" checked={draftPermissions.includes(permission.permission_key)} disabled={saving||draftRole==='ADMIN'||selected.isCurrentStaff} onChange={e=>toggleDraftPermission(permission.permission_key,e.target.checked)}/>
              <span><b>{permission.display_name}</b><small>{permission.description}</small><em>{permission.permission_key.replaceAll('_',' ')}</em></span>
            </label>)}</div>
            {draftRole==='ADMIN'&&<p className="qe-staff-help">Administrator accounts retain every permission. Assign the Administrator role only to trusted operators.</p>}
            <button type="button" className="qe-primary-button qe-save-staff" disabled={saving||!editDirty||selected.isCurrentStaff} onClick={()=>void savePermissions()}>{saving?'Saving…':'Save role & permissions'}</button>
          </>:<>
            <div className="panel-head"><div><span className="admin-kicker">ADD A STAFF IDENTITY</span><h2>Create staff account</h2><p>Use this for a moderator, manager, author, reviewer or publisher.</p></div></div>
            <label className="qe-staff-field">Full name<input value={newName} onChange={e=>setNewName(e.target.value)} maxLength={120} placeholder="Staff member name"/></label>
            <label className="qe-staff-field">Mobile number<input value={newMobile} onChange={e=>setNewMobile(e.target.value)} inputMode="tel" maxLength={18} placeholder="10-digit Indian mobile"/></label>
            <label className="qe-staff-field">Initial role<select value={newRole} onChange={e=>changeNewRole(e.target.value)}>{roleOptions.map(option=><option key={option.value} value={option.value}>{option.label}</option>)}</select></label>
            <div className="qe-staff-permissions-head"><strong>Initial task permissions</strong><span>{newPermissions.length} selected</span></div>
            <p className="qe-staff-help">The role selects a starting set. You can then remove or add individual task permissions.</p>
            <div className="qe-staff-permission-list">{availablePermissions.map(permission=><label key={permission.permission_key} className="qe-permission-option">
              <input type="checkbox" checked={newPermissions.includes(permission.permission_key)} disabled={saving||newRole==='ADMIN'} onChange={e=>toggleDraftPermission(permission.permission_key,e.target.checked,true)}/>
              <span><b>{permission.display_name}</b><small>{permission.description}</small><em>{permission.permission_key.replaceAll('_',' ')}</em></span>
            </label>)}</div>
            <button type="button" className="qe-primary-button qe-save-staff" disabled={saving||!newReady||!adminPermissionsComplete} onClick={()=>void createStaff()}>{saving?'Creating…':'Create staff account'}</button>
          </>}
        </section>
      </div>}

      {tab==='audit'&&canAudit&&<section className="admin-panel qe-audit-panel">
        <div className="panel-head"><div><span className="admin-kicker">RECORDED STAFF ACTIVITY</span><h2>Audit trail</h2><p>Requests to the admin API are captured with actor, endpoint, result and timestamp. Request bodies and OTP values are not stored here.</p></div><span className="count">{audit.length} events</span></div>
        <div className="qe-audit-list">
          {audit.map(row=><article key={row.id} className="qe-audit-row">
            <span className={'qe-audit-status '+(row.response_status>=200&&row.response_status<400?'success':'failure')}>{row.response_status}</span>
            <div className="qe-audit-event"><strong>{row.actor_name} <small>{row.actor_role}</small></strong><span><b>{row.http_method}</b> {row.request_path}</span><small>{prettyDate(row.created_at)} · {row.remote_address||'IP unavailable'}</small></div>
            <button type="button" className="qe-audit-info" title={row.user_agent||'No user-agent recorded'} onClick={()=>window.alert((row.user_agent||'No user-agent recorded')+'\n'+(row.remote_address||'IP unavailable'))}>Details</button>
          </article>)}
          {!audit.length&&<div className="qe-library-empty"><strong>No staff activity is recorded yet</strong><p>New staff API actions will appear here. Existing pre-audit events are not synthesized.</p></div>}
        </div>
      </section>}

      <p className="admin-footer qe-admin-footer">QuantaEdge · Staff governance <span>Identities, permissions and audit history are separate from learner accounts.</span></p>
    </section>
  </main>;
}
