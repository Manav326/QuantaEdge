'use client';

import { useContext, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import AdminSidebar from '../components/AdminSidebar';
import PremiumPagination from '../components/PremiumPagination';
import { AdminWorkspaceContext } from '../components/AdminWorkspaceContext';
import { useUiTranslation } from '../i18n/useUiTranslation';
import { getUiLocaleLanguage } from '../i18n/catalog';

type StaffRow = {
  id:number; public_id:string; mobile_e164:string; display_name:string; role:string; active:boolean;
  created_at:string; updated_at:string; last_login_at:string|null; permissions:string[]; isCurrentStaff?:boolean;
};
type Permission = { permission_key:string; display_name:string; description:string };
type AuditRow = {
  id:number; actor_staff_id:number|null; actor_role:string; http_method:string; request_path:string;
  response_status:number; remote_address:string|null; user_agent:string|null; details:string|null; created_at:string; actor_name:string;
};

const roleOptions = [
  {value:'CONTENT_AUTHOR',message:'admin.employees.roleContentAuthor'},
  {value:'CONTENT_REVIEWER',message:'admin.employees.roleContentReviewer'},
  {value:'MODERATOR',message:'admin.employees.roleModerator'},
  {value:'PUBLISHER',message:'admin.employees.rolePublisher'},
  {value:'MANAGER',message:'admin.employees.roleManager'},
  {value:'ADMIN',message:'admin.employees.roleAdministrator'}
];
function roleLabel(t:(key:string,values?:Record<string,string|number>)=>string,role:string){const keys:Record<string,string>={CONTENT_AUTHOR:'admin.employees.roleContentAuthor',CONTENT_REVIEWER:'admin.employees.roleContentReviewer',MODERATOR:'admin.employees.roleModerator',PUBLISHER:'admin.employees.rolePublisher',MANAGER:'admin.employees.roleManager',ADMIN:'admin.employees.roleAdministrator'};return keys[role]?t(keys[role]):role.replaceAll('_',' ');}

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

async function readJson(response:Response,t:(key:string,values?:Record<string,string|number>)=>string):Promise<any> {
  const raw=await response.text();
  let value:any={};
  try { value=raw?JSON.parse(raw):{}; } catch { value={message:raw}; }
  if(!response.ok) throw new Error(value.detail||value.message||value.error||t('admin.employees.requestFailed',{status:response.status}));
  return value;
}

function prettyDate(value:string|null|undefined,locale:string,t:(key:string)=>string) {
  if(!value)return t('admin.employees.never');
  const date=new Date(value);
  return Number.isNaN(date.getTime())?'—':date.toLocaleString(getUiLocaleLanguage(locale)+'-IN');
}

export default function AdminStaffPage() {
  const router=useRouter();
  const t=useUiTranslation();
  const workspace=useContext(AdminWorkspaceContext);
  const [me,setMe]=useState<any>(null);
  const [staff,setStaff]=useState<StaffRow[]>([]);
  const [staffPage,setStaffPage]=useState(0);
  const [staffPageSize,setStaffPageSize]=useState(10);
  const [availablePermissions,setAvailablePermissions]=useState<Permission[]>([]);
  const [audit,setAudit]=useState<AuditRow[]>([]);
  const [auditPage,setAuditPage]=useState(0);
  const [auditTotal,setAuditTotal]=useState(0);
  const [auditPageSize,setAuditPageSize]=useState(10);
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
  const visibleStaff=useMemo(()=>filteredStaff.slice(staffPage*staffPageSize,(staffPage+1)*staffPageSize),[filteredStaff,staffPage,staffPageSize]);
  useEffect(()=>{
    if(!canAudit)return;
    let active=true;
    (async()=>{
      const params=new URLSearchParams({limit:String(auditPageSize),offset:String(auditPage*auditPageSize)});
      const response=await fetch('/api/v1/admin/staff/audit?'+params.toString(),{cache:'no-store'});
      const data=await readJson(response,t);
      if(!active)return;
      setAudit(Array.isArray(data?.items)?data.items:(Array.isArray(data)?data:[]));
      setAuditTotal(Number(data?.total??(Array.isArray(data)?data.length:0)));
    })().catch((e:any)=>{if(active)setError(e.message||t('admin.employees.auditLoadFailed'));});
    return()=>{active=false};
  },[canAudit,auditPage,auditPageSize]);

  const editDirty=Boolean(selected&&(draftRole!==initialRole||JSON.stringify([...draftPermissions].sort())!==JSON.stringify([...initialPermissions].sort())));
  const newReady=newName.trim().length>=2&&newMobile.replace(/\D/g,'').length===10;
  const adminPermissionsComplete=newRole!=='ADMIN'||newPermissions.length===availablePermissions.length;

  async function loadStaff() {
    const r=await fetch('/api/v1/admin/staff',{cache:'no-store'});
    const data=await readJson(r,t);
    setStaff(Array.isArray(data)?data:[]);
  }
   async function loadAdminData(admin:boolean) {
    const tasks:Promise<any>[]=[];
    if(admin) {
      tasks.push(fetch('/api/v1/admin/staff',{cache:'no-store'}).then(response=>readJson(response,t)).then(data=>setStaff(Array.isArray(data)?data:[])));
      tasks.push(fetch('/api/v1/admin/staff/permissions',{cache:'no-store'}).then(response=>readJson(response,t)).then(data=>{
        const rows=Array.isArray(data)?data:[];
        setAvailablePermissions(rows);
        setNewPermissions(defaultsForRole('CONTENT_AUTHOR',rows));
      }));
    }
     await Promise.all(tasks);
  }

  useEffect(()=>{
    let mounted=true;
    (async()=>{
      try {
        const response=await fetch('/api/v1/auth/me',{cache:'no-store'});
        const identity=await readJson(response,t);
        if(!identity.staffId) { router.replace('/login'); return; }
        if(!mounted)return;
        setMe(identity);
        const admin=identity.role==='ADMIN';
        const hasAudit=admin||(Array.isArray(identity.permissions)&&identity.permissions.includes('AUDIT_VIEW'));
        const searchParams=new URLSearchParams(window.location.search);
        setTab(searchParams.get('view')==='audit'||!admin?'audit':'staff');
        if(!admin&&!hasAudit) { router.replace('/content'); return; }
        await loadAdminData(admin);
      } catch(e:any) {
        if(mounted)setError(e.message||t('admin.employees.workspaceLoadFailed'));
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
    let next=checked?[...new Set([...current,key])]:current.filter(item=>item!==key);
    if(checked&&key.startsWith('CONTENT_')&&key!=='CONTENT_VIEW') {
      next=[...new Set([...next,'CONTENT_VIEW'])];
    }
    if(checked&&key==='CONTENT_CREATE') {
      next=[...new Set([...next,'CONTENT_VIEW','CONTENT_EDIT'])];
    }
    if(!checked&&key==='CONTENT_VIEW') {
      next=next.filter(item=>!['CONTENT_CREATE','CONTENT_EDIT','CONTENT_SUBMIT','CONTENT_REVIEW','CONTENT_PUBLISH'].includes(item));
    }
    if(!checked&&key==='CONTENT_EDIT') next=next.filter(item=>item!=='CONTENT_CREATE');
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
      await readJson(response,t);
      setNewName('');setNewMobile('');changeNewRole('CONTENT_AUTHOR');
      await loadStaff();
      setNotice(t('admin.employees.accountCreated'));
    } catch(e:any) {setError(e.message||t('admin.employees.accountCreateFailed'));}
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
      const updated=await readJson(response,t);
      setStaff(rows=>rows.map(row=>row.id===selected.id?updated:row));
      setSelected(updated);
      setInitialRole(updated.role);
      setDraftRole(updated.role);
      setInitialPermissions([...updated.permissions]);
      setDraftPermissions([...updated.permissions]);
      setNotice(t('admin.employees.permissionsUpdated'));
    } catch(e:any) {setError(e.message||t('admin.employees.permissionsSaveFailed'));}
    finally {setSaving(false);}
  }

  async function toggleActive(row:StaffRow) {
    if(row.isCurrentStaff&&!row.active)return;
    const next=!row.active;
    if(!next&&!window.confirm(t('admin.employees.suspendConfirm',{name:row.display_name})))return;
    setSaving(true);setError('');setNotice('');
    try {
      const response=await fetch('/api/v1/admin/staff/'+row.id+'/status',{
        method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({active:next})
      });
      const updated=await readJson(response,t);
      setStaff(rows=>rows.map(item=>item.id===row.id?{...item,...updated}:item));
      if(selected?.id===row.id) {
        const merged={...selected,...updated} as StaffRow;
        setSelected(merged);
      }
      setNotice(updated.message||(next?t('admin.employees.accountActivated'):t('admin.employees.accountSuspended')));
    } catch(e:any) {setError(e.message||t('admin.employees.statusUpdateFailed'));}
    finally {setSaving(false);}
  }

  if(loading)return <main className="admin-shell"><AdminSidebar active="staff"/><section className="admin-main"><div className="admin-loading"><span className="admin-kicker">{t('admin.employees.loadingKicker')}</span><h1>{t('admin.employees.preparingWorkspace')}</h1><p>{t('admin.employees.loadingDescription')}</p></div></section></main>;

  return <main className="admin-shell">
    <AdminSidebar active="staff"/>
    <section className="admin-main qe-staff-main">
      <header className="admin-top">
        <div><span className="admin-kicker">{t('admin.employees.governanceKicker')}</span><h1>{isAdmin?t('admin.employees.title'):t('admin.employees.auditOnlyTitle')}</h1><p>{isAdmin?t('admin.employees.directoryDescription'):t('admin.employees.auditOnlyDescription')}</p></div>
        <span className="top-status"><span className="status-dot"/>{isAdmin?t('admin.employees.directoryLive'):t('admin.employees.auditReadOnly')}</span>
      </header>

      <section className="qe-staff-principles">
        <span className="qe-staff-principle-mark">✓</span>
        <div><strong>{t('admin.employees.principlesTitle')}</strong><p>{t('admin.employees.principlesDescription')}</p></div>
      </section>

      {error&&<div className="admin-alert qe-feedback" role="alert"><strong>{t('admin.employees.needsAttention')}</strong><span>{error}</span><button type="button" onClick={()=>setError('')}>{t('admin.employees.dismiss')}</button></div>}
      {notice&&<div className="admin-notice qe-feedback" role="status"><strong>{t('admin.employees.workspaceUpdate')}</strong><span>{notice}</span><button type="button" onClick={()=>setNotice('')}>{t('admin.employees.dismiss')}</button></div>}

      <div className="qe-staff-tabs">
        {isAdmin&&<button type="button" className={tab==='staff'?'active':''} onClick={()=>setTab('staff')} >{t('admin.employees.staffDirectory')} <b>{staff.length}</b></button>}
        {canAudit&&<button type="button" className={tab==='audit'?'active':''} onClick={()=>setTab('audit')} >{t('admin.employees.auditTrail')} <b>{auditTotal}</b></button>}
      </div>

      {tab==='staff'&&isAdmin&&<div className="qe-staff-layout">
        <section className="admin-panel qe-staff-directory">
          <div className="panel-head"><div><span className="admin-kicker">{t('admin.employees.staffIdentities')}</span><h2>{t('admin.employees.staffDirectory')}</h2><p>{t('admin.employees.notLearnerAccounts')}</p></div><span className="count">{t('admin.employees.activeCount',{count:staff.filter(row=>row.active).length})}</span></div>
          <label className="qe-staff-search">{t('admin.employees.searchStaff')}<input value={search} onChange={e=>{setSearch(e.target.value);setStaffPage(0);}} placeholder={t('admin.employees.searchPlaceholder')}/></label>
          <div className="qe-staff-list">
            {visibleStaff.map(row=><article className={'qe-staff-row '+(selected?.id===row.id?'selected':'')} key={row.id}>
              <button type="button" className="qe-staff-person" onClick={()=>startEdit(row)}>
                <span className="qe-staff-avatar">{row.display_name.trim().slice(0,1).toUpperCase()}</span>
                <span className="qe-staff-person-copy"><strong>{row.display_name}</strong><small>{row.mobile_e164} · {roleLabel(t,row.role)}</small><small>{t('admin.employees.taskPermissionCount',{count:row.permissions.length})}</small></span>
                <span className={'qe-status-pill '+(row.active?'published':'archived')}>{row.active?t('admin.employees.active'):t('admin.employees.suspended')}</span>
              </button>
              <button type="button" className="qe-staff-row-action" onClick={()=>void toggleActive(row)} disabled={saving||row.isCurrentStaff}>{row.active?t('admin.employees.suspendAccess'):t('admin.employees.reactivate')}</button>
            </article>)}
            {!filteredStaff.length&&<div className="qe-library-empty"><strong>{t('admin.employees.noMatchingAccounts')}</strong><p>{t('admin.employees.createOrSearchHelp')}</p></div>}
          </div>
          <PremiumPagination page={staffPage} total={filteredStaff.length} pageSize={staffPageSize}
            onPageChange={setStaffPage} onPageSizeChange={size=>{setStaffPageSize(size);setStaffPage(0);}}
            itemLabel={t('admin.employees.staffAccounts')} />
        </section>

        <section className="admin-panel qe-staff-editor">
          {selected?<>
            <div className="panel-head"><div><span className="admin-kicker">{t('admin.employees.permissionAssignment')}</span><h2>{selected.display_name}</h2><p>{selected.mobile_e164} · {t('admin.employees.role')}: {roleLabel(t,selected.role)}</p></div><button type="button" className="qe-icon-button" onClick={()=>setSelected(null)} aria-label={t('admin.employees.dismiss')}>×</button></div>
            <label className="qe-staff-field">{t('admin.employees.role')}<select value={draftRole} onChange={e=>changeDraftRole(e.target.value)} disabled={saving||selected.isCurrentStaff}>{roleOptions.map(option=><option key={option.value} value={option.value}>{t(option.message)}</option>)}</select></label>
            <div className="qe-staff-permissions-head"><strong>{t('admin.employees.taskPermissions')}</strong><span>{t('admin.employees.selectedCount',{count:draftPermissions.length})}</span></div>
            <p className="qe-staff-help">{t('admin.employees.permissionHelp')}</p>
            <div className="qe-staff-permission-list">{availablePermissions.map(permission=><label key={permission.permission_key} className="qe-permission-option">
              <input type="checkbox" checked={draftPermissions.includes(permission.permission_key)} disabled={saving||draftRole==='ADMIN'||selected.isCurrentStaff||(permission.permission_key==='STAFF_MANAGE'&&draftRole!=='ADMIN')} onChange={e=>toggleDraftPermission(permission.permission_key,e.target.checked)}/>
              <span><b>{permission.display_name}</b><small>{permission.description}</small><em>{permission.permission_key.replaceAll('_',' ')}</em></span>
            </label>)}</div>
            {draftRole==='ADMIN'&&<p className="qe-staff-help">{t('admin.employees.adminRoleWarning')}</p>}
            <button type="button" className="qe-primary-button qe-save-staff" disabled={saving||!editDirty||selected.isCurrentStaff} onClick={()=>void savePermissions()}>{saving?t('admin.employees.saving'):t('admin.employees.saveRolePermissions')}</button>
          </>:<>
            <div className="panel-head"><div><span className="admin-kicker">{t('admin.employees.addStaffIdentity')}</span><h2>{t('admin.employees.createStaffAccount')}</h2><p>{t('admin.employees.createStaffHelp')}</p></div></div>
            <label className="qe-staff-field">{t('admin.employees.fullName')}<input value={newName} onChange={e=>setNewName(e.target.value)} maxLength={120} placeholder={t('admin.employees.staffNamePlaceholder')}/></label>
            <label className="qe-staff-field">{t('admin.employees.mobileNumber')}<input value={newMobile} onChange={e=>setNewMobile(e.target.value)} inputMode="tel" maxLength={18} placeholder={t('admin.employees.mobilePlaceholder')}/></label>
            <label className="qe-staff-field">{t('admin.employees.initialRole')}<select value={newRole} onChange={e=>changeNewRole(e.target.value)}>{roleOptions.map(option=><option key={option.value} value={option.value}>{t(option.message)}</option>)}</select></label>
            <div className="qe-staff-permissions-head"><strong>{t('admin.employees.initialTaskPermissions')}</strong><span>{t('admin.employees.selectedCount',{count:newPermissions.length})}</span></div>
            <p className="qe-staff-help">{t('admin.employees.roleDefaultHelp')}</p>
            <div className="qe-staff-permission-list">{availablePermissions.map(permission=><label key={permission.permission_key} className="qe-permission-option">
              <input type="checkbox" checked={newPermissions.includes(permission.permission_key)} disabled={saving||newRole==='ADMIN'||permission.permission_key==='STAFF_MANAGE'} onChange={e=>toggleDraftPermission(permission.permission_key,e.target.checked,true)}/>
              <span><b>{permission.display_name}</b><small>{permission.description}</small><em>{permission.permission_key.replaceAll('_',' ')}</em></span>
            </label>)}</div>
            <button type="button" className="qe-primary-button qe-save-staff" disabled={saving||!newReady||!adminPermissionsComplete} onClick={()=>void createStaff()}>{saving?t('admin.employees.creating'):t('admin.employees.createStaffAccount')}</button>
          </>}
        </section>
      </div>}

      {tab==='audit'&&canAudit&&<section className="admin-panel qe-audit-panel">
        <div className="panel-head"><div><span className="admin-kicker">{t('admin.employees.recordedActivity')}</span><h2>{t('admin.employees.auditTrail')}</h2><p>{t('admin.employees.auditDescription')}</p></div><span className="count">{t('admin.employees.eventCount',{count:auditTotal})}</span></div>
        <div className="qe-audit-list">
          {audit.map(row=><article key={row.id} className="qe-audit-row">
            <span className={'qe-audit-status '+(row.response_status>=200&&row.response_status<400?'success':'failure')}>{row.response_status}</span>
            <div className="qe-audit-event"><strong>{row.actor_name} <small>{{roleLabel(t,row.actor_role)}</small></strong><span><b>{row.http_method}</b> {row.request_path}</span><small>{prettyDate(row.created_at,workspace.locale,key=>t(key))} · {row.remote_address||t('admin.employees.ipUnavailable')}</small>{row.details&&<small className="qe-audit-detail">{row.details}</small>}</div>
            <button type="button" className="qe-audit-info" title={row.user_agent||t('admin.employees.noUserAgentRecorded')} onClick={()=>window.alert((row.user_agent||t('admin.employees.noUserAgentRecorded'))+'\n'+(row.remote_address||t('admin.employees.ipUnavailable')))} >{t('admin.employees.details')}</button>
          </article>)}
          {!audit.length&&<div className="qe-library-empty"><strong>{t('admin.employees.noActivity')}</strong><p>{t('admin.employees.newActivityHelp')}</p></div>}
        </div>
        <PremiumPagination page={auditPage} total={auditTotal} pageSize={auditPageSize} onPageChange={setAuditPage}
          onPageSizeChange={size=>{setAuditPageSize(size);setAuditPage(0);}} itemLabel={t('admin.employees.auditEvents')} />
      </section>}

      <p className="admin-footer qe-admin-footer">{t('admin.employees.governanceFooter')} <span>{t('admin.employees.governanceFooterDescription')}</span></p>
    </section>
  </main>;
}
