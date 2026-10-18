'use client';

import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './Institutions.module.css';
import InstitutionBrandAssetEditor from './InstitutionBrandAssetEditor';

type Session = { staffId?: number; displayName?: string; permissions?: string[] };
type EditableCard = 'status' | 'courses' | 'admins' | 'branding' | 'billing';
type Institution = {
  id: number; tenant_code: string; display_name: string; legal_name?: string | null; status: string;
  brand_display_name?: string | null; active_memberships?: number; authorized_courses?: number;
  current_period_billable_events?: number; current_period_amount_paise?: number; contract_currency?: string | null;
};
type Course = { id: number; code: string; display_name: string; class_code?: string | null };
type Entitlement = { program_id: number; code: string; display_name: string; entitlement_status: string };
type Admin = { id: number; username: string; display_name: string; active: boolean; must_change_password: boolean; last_login_at?: string | null };
type ThemePalette = Record<string,string>;
type ThemeTokens = { schemaVersion?: number; presetId?: string; appearance?: string; brandPrimary?: string; brandAccent?: string; primary?: string; accent?: string; background?: string; surface?: string; text?: string; light?: ThemePalette; dark?: ThemePalette; [key: string]: unknown };
type Detail = Institution & {
  brand_display_name?: string | null; logo_asset_key?: string | null; app_icon_asset_key?: string | null;
  store_listing_url?: string | null; logo_url?: string | null; app_icon_url?: string | null; themeTokens?: ThemeTokens; contract_id?: number | null; currency?: string;
  unit_price_paise?: number; billing_cadence?: string; effective_from?: string; effective_until?: string | null;
  admins?: Admin[]; courses?: Entitlement[];
};
type Overview = { institutions?: { institutions_total?: number; institutions_active?: number; institutions_suspended?: number }; billing?: { current_period_billable_enrollments?: number; amount_by_currency?: string } };
type Learner = { learner_reference?: string; display_name?: string; class_code?: string; city?: string; state?: string; membership_status?: string; joined_at?: string; completed_lessons?: number; lessons_with_activity?: number; average_lesson_progress_percent?: number };
type ThemePreset = { id: string; name: string; primary: string; accent: string; themeTokens?: ThemeTokens };
type AuditEvent = { id: number; action: string; target_type: string; target_id?: string; details?: unknown; created_at?: string };

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-QE-Workspace', 'HRMS');
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type','application/json');
  const response = await fetch(path,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw = await response.text();
  let data: any = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = {message:raw}; }
  if (!response.ok) throw new Error(String(data.message || data.detail || data.error || 'Request failed ('+response.status+')'));
  return data as T;
}
function fmt(v?:string|null) { if(!v)return '—'; const d=new Date(v); return Number.isNaN(d.getTime())?v:d.toLocaleString('en-IN'); }
function rupees(paise?:number,currency?:string|null) { if(paise===undefined||paise===null)return 'Not configured'; return new Intl.NumberFormat('en-IN',{style:'currency',currency:currency||'INR'}).format(paise/100); }

export default function InstitutionsAdminPage() {
  const router=useRouter();
  const [session,setSession]=useState<Session|null>(null);
  const [ready,setReady]=useState(false);
  const [overview,setOverview]=useState<Overview|null>(null);
  const [institutions,setInstitutions]=useState<Institution[]>([]);
  const [catalog,setCatalog]=useState<Course[]>([]);
  const [detail,setDetail]=useState<Detail|null>(null);
  const [roster,setRoster]=useState<Learner[]>([]);
  const [auditRows,setAuditRows]=useState<AuditEvent[]>([]);
  const [tab,setTab]=useState<'summary'|'learners'|'audit'>('summary');
  const [query,setQuery]=useState('');
  const [showCreate,setShowCreate]=useState(false);
  const [showAdmin,setShowAdmin]=useState(false);
  const [editingCard,setEditingCard]=useState<EditableCard|null>(null);
  const [courseDraft,setCourseDraft]=useState<Record<number,boolean>>({});
  const [pendingCrop,setPendingCrop]=useState<{assetType:'logo'|'app-icon';file:File}|null>(null);
  const [loading,setLoading]=useState(true);
  const [saving,setSaving]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [joinCode,setJoinCode]=useState('');
  const [joinCodeInstitutionId,setJoinCodeInstitutionId]=useState<number|null>(null);
  const [codeExpiry,setCodeExpiry]=useState('');
  const [create,setCreate]=useState({tenantCode:'',displayName:'',legalName:'',adminUsername:'',adminDisplayName:'',initialPassword:''});
  const [admin,setAdmin]=useState({username:'',displayName:'',initialPassword:''});
  const [brand,setBrand]=useState({displayName:'',logoAssetKey:'',appIconAssetKey:'',storeListingUrl:'',presetId:'quantaedge-orange',appearance:'system',primary:'#F97316',accent:'#C2410C'});
  const [themePresets,setThemePresets]=useState<ThemePreset[]>([]);
  const [themePreview,setThemePreview]=useState<ThemeTokens|null>(null);
  const [themePreviewLoading,setThemePreviewLoading]=useState(false);
  const [billing,setBilling]=useState({currency:'INR',unitPriceRupees:'0.00',billingCadence:'MONTHLY',effectiveFrom:new Date().toISOString().slice(0,10),effectiveUntil:''});
  const owner=session?.permissions?.includes('QE_PLATFORM_OWNER')===true;
  const selectedInstitutionId=detail?.id;
  const lightPreview=themePreview?.light;
  const darkPreview=themePreview?.dark;

  const loadBase=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const me=await api<Session>('/api/v1/auth/me');setSession(me);setReady(true);
      if(!me.staffId){router.replace('/login');return;}
      if(!me.permissions?.includes('QE_PLATFORM_OWNER'))return;
      const [o,i,c,presets]=await Promise.all([
        api<Overview>('/api/v1/hrms/platform/institutions/overview'),
        api<Institution[]>('/api/v1/hrms/platform/institutions'),
        api<Course[]>('/api/v1/hrms/platform/institutions/catalog/courses'),
        api<ThemePreset[]>('/api/v1/hrms/platform/institutions/theme-presets'),
      ]);
      setOverview(o);setInstitutions(Array.isArray(i)?i:[]);setCatalog(Array.isArray(c)?c:[]);setThemePresets(Array.isArray(presets)?presets:[]);
    }catch(e){const msg=e instanceof Error?e.message:'Platform tenant records could not be loaded.';setError(msg);if(/authentication|required|session expired/i.test(msg))router.replace('/login');}
    finally{setLoading(false);}
  },[router]);
  const loadDetail=useCallback(async(id:number)=>{
    setLoading(true);setError('');
    try{
      const [d,r,a]=await Promise.all([
        api<Detail>('/api/v1/hrms/platform/institutions/'+id),
        api<{learners?:Learner[]}>('/api/v1/hrms/platform/institutions/'+id+'/roster'),
        api<AuditEvent[]>('/api/v1/hrms/platform/institutions/'+id+'/audit'),
      ]);
      setDetail(d);setRoster(r.learners||[]);setAuditRows(a||[]);setTab('summary');setEditingCard(null);setShowAdmin(false);
      setBrand({
        displayName:d.brand_display_name||d.display_name,logoAssetKey:d.logo_asset_key||'',
        appIconAssetKey:d.app_icon_asset_key||'',storeListingUrl:d.store_listing_url||'',
        presetId:String(d.themeTokens?.presetId||'quantaedge-orange'),
        appearance:String(d.themeTokens?.appearance||'system'),
        primary:String(d.themeTokens?.brandPrimary||d.themeTokens?.primary||'#F97316'),
        accent:String(d.themeTokens?.brandAccent||d.themeTokens?.accent||'#C2410C'),
      });
      setBilling({currency:d.currency||'INR',unitPriceRupees:d.unit_price_paise===undefined?'0.00':(d.unit_price_paise/100).toFixed(2),
        billingCadence:d.billing_cadence||'MONTHLY',effectiveFrom:d.effective_from?.slice(0,10)||new Date().toISOString().slice(0,10),effectiveUntil:d.effective_until?.slice(0,10)||''});
    }catch(e){setError(e instanceof Error?e.message:'Institution details could not be loaded.');}
    finally{setLoading(false);}
  },[]);
  useEffect(()=>{void loadBase();},[loadBase]);

  // Preview is generated by the API's canonical palette algorithm, not a second client-side colour implementation.
  useEffect(()=>{
    if(selectedInstitutionId===undefined){
      setThemePreview(null);
      setThemePreviewLoading(false);
      return;
    }
    const aborter=new AbortController();
    setThemePreviewLoading(true);
    const timer=window.setTimeout(()=>{
      void api<{themeTokens:ThemeTokens}>('/api/v1/hrms/platform/institutions/theme-preview',{
        method:'POST',
        body:JSON.stringify({themeTokens:{
          schemaVersion:2,presetId:brand.presetId,appearance:brand.appearance,
          brandPrimary:brand.primary,brandAccent:brand.accent,
        }}),
        signal:aborter.signal,
      }).then(result=>{
        if(!aborter.signal.aborted)setThemePreview(result.themeTokens);
      }).catch(()=>{
        if(!aborter.signal.aborted)setThemePreview(null);
      }).finally(()=>{
        if(!aborter.signal.aborted)setThemePreviewLoading(false);
      });
    },180);
    return ()=>{window.clearTimeout(timer);aborter.abort();};
  },[selectedInstitutionId,brand.presetId,brand.appearance,brand.primary,brand.accent]);

  const filtered=useMemo(()=>{const q=query.trim().toLowerCase();return institutions.filter(i=>!q||[i.tenant_code,i.display_name,i.legal_name,i.status].some(v=>String(v||'').toLowerCase().includes(q)));},[institutions,query]);
  const setCreateField=(key:keyof typeof create,value:string)=>setCreate(old=>({...old,[key]:value}));
  const doCreate=async(event:FormEvent)=>{
    event.preventDefault();setSaving(true);setError('');setNotice('');setJoinCode('');
    try{
      const result=await api<{institutionId:number;displayName:string;nextStep:string}>('/api/v1/hrms/platform/institutions',{method:'POST',body:JSON.stringify(create)});
      setJoinCode('');setNotice('Institution '+result.displayName+' created. Configure its billing contract, then issue the learner join code.');
      setCreate({tenantCode:'',displayName:'',legalName:'',adminUsername:'',adminDisplayName:'',initialPassword:''});
      setShowCreate(false);await loadBase();await loadDetail(result.institutionId);
    }catch(e){setError(e instanceof Error?e.message:'Could not create institution.');}finally{setSaving(false);}
  };
  async function changeStatus(status:'ACTIVE'|'SUSPENDED'|'CLOSED'){
    if(!detail)return;const reason=window.prompt('Reason for changing '+detail.tenant_code+' to '+status+':','');if(reason===null)return;
    setSaving(true);setError('');
    try{await api('/api/v1/hrms/platform/institutions/'+detail.id+'/status',{method:'PUT',body:JSON.stringify({status,reason})});setNotice('Institution status saved.');await loadBase();await loadDetail(detail.id);}
    catch(e){setError(e instanceof Error?e.message:'Status change failed.');}finally{setSaving(false);}
  }
  async function saveCourseAuthorisation(){
    if(!detail)return;
    const changes=catalog.filter(course=>{
      const enabled=detail.courses?.some(x=>x.program_id===course.id&&x.entitlement_status==='ACTIVE')||false;
      return Boolean(courseDraft[course.id])!==enabled;
    });
    if(!changes.length){setNotice('No course authorisation changes to save.');setEditingCard(null);return;}
    setSaving(true);setError('');setNotice('');
    try{
      for(const course of changes){await api('/api/v1/hrms/platform/institutions/'+detail.id+'/courses/'+course.id,{method:'PUT',body:JSON.stringify({enabled:Boolean(courseDraft[course.id])})});}
      setNotice('Saved '+changes.length+' course authorisation change'+(changes.length===1?'':'s')+'.');
      await loadBase();await loadDetail(detail.id);
    }catch(e){
      const message=e instanceof Error?e.message:'Course authorisation could not be saved.';
      await loadDetail(detail.id);
      setError('Course authorisation may have been partially saved. '+message+' The current server state has been reloaded; review it before retrying.');
    }finally{setSaving(false);}
  }
  function cancelCardEdit(card:EditableCard){
    if(detail){
      if(card==='courses')setCourseDraft(Object.fromEntries(catalog.map(course=>[course.id,(detail.courses||[]).some(x=>x.program_id===course.id&&x.entitlement_status==='ACTIVE')])));
      if(card==='branding')setBrand({displayName:detail.brand_display_name||detail.display_name,logoAssetKey:detail.logo_asset_key||'',appIconAssetKey:detail.app_icon_asset_key||'',storeListingUrl:detail.store_listing_url||'',presetId:String(detail.themeTokens?.presetId||'quantaedge-orange'),appearance:String(detail.themeTokens?.appearance||'system'),primary:String(detail.themeTokens?.brandPrimary||detail.themeTokens?.primary||'#F97316'),accent:String(detail.themeTokens?.brandAccent||detail.themeTokens?.accent||'#C2410C')});
      if(card==='billing')setBilling({currency:detail.currency||'INR',unitPriceRupees:detail.unit_price_paise===undefined?'0.00':(detail.unit_price_paise/100).toFixed(2),billingCadence:detail.billing_cadence||'MONTHLY',effectiveFrom:detail.effective_from?.slice(0,10)||new Date().toISOString().slice(0,10),effectiveUntil:detail.effective_until?.slice(0,10)||''});
    }
    setShowAdmin(false);setAdmin({username:'',displayName:'',initialPassword:''});setEditingCard(null);setError('');
  }
  function beginBillingEdit(){
    if(!detail)return;
    setBilling({currency:detail.currency||'INR',unitPriceRupees:detail.unit_price_paise===undefined?'0.00':(detail.unit_price_paise/100).toFixed(2),billingCadence:detail.billing_cadence||'MONTHLY',effectiveFrom:detail.effective_from?.slice(0,10)||new Date().toISOString().slice(0,10),effectiveUntil:detail.effective_until?.slice(0,10)||''});
    setEditingCard('billing');
  }
  async function saveBranding(event:FormEvent){
    event.preventDefault();if(!detail)return;setSaving(true);setError('');
    try{await api('/api/v1/hrms/platform/institutions/'+detail.id+'/branding',{method:'PUT',body:JSON.stringify({
      displayName:brand.displayName,storeListingUrl:brand.storeListingUrl,
      themeTokens:{schemaVersion:2,presetId:brand.presetId,appearance:brand.appearance,brandPrimary:brand.primary,brandAccent:brand.accent},
    })});setNotice('Tenant branding settings saved.');setEditingCard(null);await loadBase();await loadDetail(detail.id);}
    catch(e){setError(e instanceof Error?e.message:'Branding could not be saved.');}finally{setSaving(false);}
  }
  async function uploadBrandAsset(assetType:'logo'|'app-icon',file:File){
    if(!detail)return;
    if(!['image/png','image/jpeg'].includes(file.type)){setError('Choose a PNG or JPEG image. SVG and animated formats are not accepted.');return;}
    if(file.size>2*1024*1024){setError('Branding images must be 2 MiB or smaller.');return;}
    setSaving(true);setError('');setNotice('');
    try{
      const form=new FormData();form.set('file',file);
      const headers=new Headers({'X-QE-Workspace':'HRMS','Accept':'application/json'});
      const response=await fetch('/api/v1/hrms/platform/institutions/'+detail.id+'/branding/assets/'+assetType,{
        method:'POST',body:form,headers,credentials:'same-origin',cache:'no-store',
      });
      const raw=await response.text();
      let data:any={};
      try{data=raw?JSON.parse(raw):{};}catch{data={message:raw};}
      if(!response.ok)throw new Error(String(data.message||data.detail||data.error||'Image upload failed ('+response.status+')'));
      setNotice(assetType==='logo'?'School logo uploaded and versioned.':'School app icon uploaded and versioned.');
      const resumeBrandEdit=editingCard==='branding';
      await loadDetail(detail.id);
      if(resumeBrandEdit)setEditingCard('branding');
    }catch(e){setError(e instanceof Error?e.message:'Branding image could not be uploaded.');}
    finally{setSaving(false);}
  }
  async function removeBrandAsset(assetType:'logo'|'app-icon'){
    if(!detail)return;
    const label=assetType==='logo'?'school logo':'school app icon';
    if(!window.confirm('Remove the current '+label+' from '+(detail.brand_display_name||detail.display_name)+'? It will no longer be served to learner apps.'))return;
    setSaving(true);setError('');setNotice('');
    try{
      const result=await api<{removed:boolean;brandingVersion:number}>(
        '/api/v1/hrms/platform/institutions/'+detail.id+'/branding/assets/'+assetType,
        {method:'DELETE'});
      setNotice(result.removed
        ? (assetType==='logo'?'School logo removed. Learner surfaces will use the school initial until another logo is uploaded.':'School app icon removed. Learner browser icons will use the school initial until another icon is uploaded.')
        : 'There was no active '+label+' to remove.');
      const resumeBrandEdit=editingCard==='branding';
      await loadDetail(detail.id);
      if(resumeBrandEdit)setEditingCard('branding');
    }catch(e){setError(e instanceof Error?e.message:'The branding asset could not be removed.');}
    finally{setSaving(false);}
  }
  async function saveBilling(event:FormEvent){
    event.preventDefault();if(!detail)return;setSaving(true);setError('');
    try{await api('/api/v1/hrms/platform/institutions/'+detail.id+'/billing-contract',{method:'PUT',body:JSON.stringify(billing)});setNotice('Billing contract saved. New billable activations will be logged in the ledger.');setEditingCard(null);await loadBase();await loadDetail(detail.id);}
    catch(e){setError(e instanceof Error?e.message:'Billing contract could not be saved.');}finally{setSaving(false);}
  }
  async function rotateCode(){
    if(!detail||!window.confirm('Rotate the join code? The old code stops working immediately.'))return;setSaving(true);setError('');
    try{const result=await api<{joinCode:string;expiresAt:string}>('/api/v1/hrms/platform/institutions/'+detail.id+'/join-code/rotate',{method:'POST',body:JSON.stringify({})});setJoinCode(result.joinCode);setJoinCodeInstitutionId(detail.id);setCodeExpiry(result.expiresAt);setNotice('Join code rotated; the old code was revoked.');}
    catch(e){setError(e instanceof Error?e.message:'Join code could not be rotated.');}finally{setSaving(false);}
  }
  async function reconcileBilling(){
    if(!detail)return;
    setSaving(true);setError('');setNotice('');
    try{
      const result=await api<{insertedEvents:number;totalChargeEvents:number;periodStart:string;periodEnd:string}>(
        '/api/v1/hrms/platform/institutions/'+detail.id+'/billing/reconcile',{method:'POST',body:JSON.stringify({})});
      setNotice('Ledger reconciled for '+result.periodStart+' through '+result.periodEnd+'. New charge events: '+result.insertedEvents+'; total events: '+result.totalChargeEvents+'.');
      await loadBase();await loadDetail(detail.id);
    }catch(e){setError(e instanceof Error?e.message:'Billing ledger could not be reconciled.');}
    finally{setSaving(false);}
  }
  function learnerAppOrigin(): string {
    const configured=process.env.NEXT_PUBLIC_LEARNER_APP_ORIGIN?.trim();
    if(configured){
      try{
        const parsed=new URL(configured);
        const local=parsed.hostname==='localhost'||parsed.hostname==='127.0.0.1';
        if(parsed.protocol==='https:'||(local&&parsed.protocol==='http:'))return parsed.origin;
      }catch{/* Use the documented deployment fallback below. */}
    }
    if(typeof window!=='undefined'&&(window.location.hostname==='localhost'||window.location.hostname==='127.0.0.1'))return 'http://localhost:3000';
    return 'https://qe.thinkwithsujeet.in';
  }
  function schoolLearningLink(): string {
    if(!detail||!joinCode||joinCodeInstitutionId!==detail.id)return '';
    const url=new URL('/school/'+encodeURIComponent(detail.tenant_code)+'/',learnerAppOrigin());
    url.hash=new URLSearchParams({join:joinCode}).toString();
    return url.toString();
  }
  async function copyCode(){
    if(!joinCode||!detail||joinCodeInstitutionId!==detail.id)return;
    try{await navigator.clipboard.writeText(joinCode);setNotice('Join code copied.');}
    catch{setNotice('Copy the join code manually: '+joinCode);}
  }
  async function copySchoolLink(){
    const link=schoolLearningLink();
    if(!link)return;
    try{await navigator.clipboard.writeText(link);setNotice('School-branded learner link copied. Share it only with this school’s learners.');}
    catch{setNotice('Copy the school learner link manually: '+link);}
  }
  async function createAdmin(event:FormEvent){
    event.preventDefault();if(!detail)return;setSaving(true);setError('');
    try{const result=await api<{username:string}>('/api/v1/hrms/platform/institutions/'+detail.id+'/admins',{method:'POST',body:JSON.stringify(admin)});setAdmin({username:'',displayName:'',initialPassword:''});setShowAdmin(false);setNotice('Administrator '+result.username+' was created. They must change the temporary password at first sign-in.');await loadDetail(detail.id);}
    catch(e){setError(e instanceof Error?e.message:'Administrator could not be created.');}finally{setSaving(false);}
  }
  async function toggleAdmin(account:Admin){
    if(!detail||!window.confirm((account.active?'Suspend ':'Activate ')+account.username+'?'))return;setSaving(true);setError('');
    try{await api('/api/v1/hrms/platform/institutions/'+detail.id+'/admins/'+account.id+'/status',{method:'PUT',body:JSON.stringify({active:!account.active})});setNotice('Administrator status saved.');await loadDetail(detail.id);}
    catch(e){setError(e instanceof Error?e.message:'Could not update admin status.');}finally{setSaving(false);}
  }

  if(!ready)return <main className={styles.page}><div className={styles.emptyPanel}>Checking HRMS access…</div></main>;
  if(!session?.staffId)return <main className={styles.page}><div className={styles.emptyPanel}><h1>Sign in required</h1><button onClick={()=>router.replace('/login')}>Open People Operations sign-in</button></div></main>;
  if(!owner)return <main className={styles.page}><div className={styles.emptyPanel}><span className={styles.eyebrow}>RESTRICTED</span><h1>Platform owner access required</h1><p>An HRMS title or employee account does not by itself grant cross-institution access. Ask the platform owner to grant the specific permission if appropriate.</p></div></main>;

  return <main className={styles.page}>
    <header className={styles.pageHeader}><div><span className={styles.eyebrow}>HRMS · PLATFORM OWNER</span><h1>Institution operations</h1><p>Control school and college tenants, course authorisation, white-label identity and billable enrollments.</p></div><div className={styles.headerActions}><button className={styles.secondary} onClick={()=>void loadBase()} disabled={loading}>Refresh data</button><button className={styles.primary} onClick={()=>setShowCreate(v=>!v)}>{showCreate?'Close form':'＋ Add institution'}</button></div></header>
    {error?<div className={styles.error} role="alert">{error}</div>:null}{notice?<div className={styles.notice} role="status">{notice}</div>:null}
    {joinCode&&detail&&joinCodeInstitutionId===detail.id?<div className={styles.codeNotice}><div><span>SCHOOL LEARNING LINK · SHARE SECURELY</span><strong>{detail.brand_display_name||detail.display_name}</strong><small>Expires {fmt(codeExpiry)}. This invitation is scoped to {detail.tenant_code} and links learners directly into the school-branded app.</small><small className={styles.invitationLinkPreview}>{schoolLearningLink()}</small></div><button className={styles.primary} onClick={()=>void copySchoolLink()}>Copy school learning link</button><button className={styles.secondary} onClick={()=>void copyCode()}>Copy code only</button><button className={styles.quiet} onClick={()=>{setJoinCode('');setJoinCodeInstitutionId(null);}}>Dismiss</button></div>:null}
    <section className={styles.metrics}>
      <article><span>All institutions</span><strong>{overview?.institutions?.institutions_total??institutions.length}</strong><small>Customer tenants</small></article>
      <article><span>Active</span><strong>{overview?.institutions?.institutions_active??institutions.filter(x=>x.status==='ACTIVE').length}</strong><small>Sign-in and joins enabled</small></article>
      <article><span>Suspended</span><strong>{overview?.institutions?.institutions_suspended??institutions.filter(x=>x.status==='SUSPENDED').length}</strong><small>Access suspended</small></article>
      <article><span>Billable enrollments</span><strong>{overview?.billing?.current_period_billable_enrollments??0}</strong><small>Current period ledger count</small></article>
    </section>
    {showCreate?<section className={styles.panel}><h2>Provision a school or college</h2><p>Creates the customer tenant and first administrator. Set the billing contract before issuing a learner join code. No demo data is added.</p><form className={styles.form} onSubmit={doCreate}>
      <label>Institution code *<input required minLength={3} maxLength={32} pattern="[A-Za-z0-9][A-Za-z0-9_-]{2,31}" value={create.tenantCode} onChange={e=>setCreateField('tenantCode',e.target.value.toUpperCase())} placeholder="e.g. PATNA-SCHOOL-01"/></label>
      <label>Display name *<input required minLength={2} maxLength={180} value={create.displayName} onChange={e=>setCreateField('displayName',e.target.value)}/></label>
      <label>Legal name<input maxLength={220} value={create.legalName} onChange={e=>setCreateField('legalName',e.target.value)}/></label>
      <label>Admin username *<input required minLength={3} maxLength={64} value={create.adminUsername} onChange={e=>setCreateField('adminUsername',e.target.value)}/></label>
      <label>Administrator full name *<input required minLength={2} maxLength={120} value={create.adminDisplayName} onChange={e=>setCreateField('adminDisplayName',e.target.value)}/></label>
      <label>Temporary password *<input required type="password" minLength={12} maxLength={72} autoComplete="new-password" value={create.initialPassword} onChange={e=>setCreateField('initialPassword',e.target.value)}/><small>12+ characters; the admin must change this after first sign-in.</small></label>
      <div className={styles.formActions}><button className={styles.primary} disabled={saving}>{saving?'Creating…':'Create institution'}</button></div>
    </form></section>:null}
    <div className={styles.columns}>
      <section className={styles.panel}><div className={styles.panelHeading}><div><h2>Customer institutions</h2><p>Tenant boundary, course scope and billing scope are separate.</p></div><b>{institutions.length}</b></div>
        <input className={styles.search} value={query} onChange={e=>setQuery(e.target.value)} placeholder="Search name, code or status…" aria-label="Search institutions"/>
        <div className={styles.list}>{filtered.map(item=><button type="button" key={item.id} onClick={()=>{if(editingCard!==null&&!window.confirm('Leave this edit state? Unsaved changes on the current card will be discarded.'))return;void loadDetail(item.id);}} className={styles.listItem+(detail?.id===item.id?' '+styles.selected:'')}>
          <span className={styles.avatar}>{(item.brand_display_name||item.display_name).trim().slice(0,1).toUpperCase()}</span><span className={styles.listCopy}><strong>{item.display_name}</strong><small>{item.tenant_code} · {item.active_memberships||0} learners</small><small>{item.authorized_courses||0} authorised courses</small></span><span className={styles.status+(item.status==='ACTIVE'?' '+styles.active:item.status==='SUSPENDED'?' '+styles.suspended:'')}>{item.status}</span>
        </button>)}{!filtered.length?<p className={styles.emptyText}>{loading?'Loading…':'No matching institutions. Add a real customer to begin.'}</p>:null}</div>
      </section>
      <section className={styles.detailColumn}>{!detail?<div className={styles.emptyPanel}><div className={styles.emptyIcon}>▤</div><h2>Select an institution</h2><p>Its settings and reports will appear here. All changes are audited and institution admins remain read-only.</p></div>:<>
        <section className={styles.panel}><div className={styles.detailHeader}><div><span className={styles.eyebrow}>{detail.tenant_code}</span><h2>{detail.display_name}</h2><p>{detail.legal_name||'Legal name not supplied'}</p></div><span className={styles.status+(detail.status==='ACTIVE'?' '+styles.active:detail.status==='SUSPENDED'?' '+styles.suspended:'')}>{detail.status}</span></div>
          <div className={styles.actions}><button className={styles.secondary} onClick={()=>{void navigator.clipboard?.writeText(detail.tenant_code).then(()=>setNotice('Tenant code copied.')).catch(()=>setNotice('Institution code: '+detail.tenant_code));}}>Copy tenant code</button>
            {editingCard==='status'?<>
              {detail.status==='ACTIVE'?<button className={styles.warning} disabled={saving} onClick={()=>void changeStatus('SUSPENDED')}>Suspend</button>:detail.status==='SUSPENDED'?<button className={styles.primary} disabled={saving} onClick={()=>void changeStatus('ACTIVE')}>Reactivate</button>:null}
              {detail.status!=='CLOSED'?<button className={styles.danger} disabled={saving} onClick={()=>void changeStatus('CLOSED')}>Close tenant</button>:null}
              <button type="button" className={styles.quiet} disabled={saving} onClick={()=>cancelCardEdit('status')}>Done</button>
            </>:<button type="button" className={styles.secondary} disabled={saving||editingCard!==null} onClick={()=>setEditingCard('status')}>Edit status</button>}</div>
          <nav className={styles.tabs}>{([['summary','Settings'],['learners','Learner records'],['audit','Audit history']] as const).map(([id,label])=><button type="button" key={id} className={tab===id?styles.tabActive:''} onClick={()=>setTab(id)}>{label}</button>)}</nav>
        </section>
        {tab==='summary'?<>
          <section className={styles.panel}>
            <div className={styles.panelHeading}>
              <div><h3>Course authorisation</h3><p>Only active course products are available. Course content itself remains centrally managed.</p></div>
              {editingCard==='courses'?<div className={styles.cardActions}><button type="button" className={styles.secondary} onClick={()=>cancelCardEdit('courses')} disabled={saving}>Cancel</button><button type="button" className={styles.primary} onClick={()=>void saveCourseAuthorisation()} disabled={saving}>{saving?'Saving…':'Save changes'}</button></div>:<button type="button" className={styles.secondary} onClick={()=>{setCourseDraft(Object.fromEntries(catalog.map(course=>[course.id,(detail.courses||[]).some(x=>x.program_id===course.id&&x.entitlement_status==='ACTIVE')])));setEditingCard('courses');}} disabled={saving||editingCard!==null}>Edit</button>}
            </div>
            {editingCard==='courses'?<div className={styles.courseList}>{catalog.map(course=><label key={course.id} className={styles.courseItem}><span><strong>{course.display_name}</strong><small>{course.code}{course.class_code?' · Class '+course.class_code:''}</small></span><input type="checkbox" checked={Boolean(courseDraft[course.id])} disabled={saving||detail.status==='CLOSED'} onChange={e=>setCourseDraft(current=>({...current,[course.id]:e.target.checked}))} aria-label={'Authorise '+course.display_name}/></label>)}{!catalog.length?<p className={styles.emptyText}>No active courses in the catalogue.</p>:null}</div>:<div className={styles.readOnlySummary}><div className={styles.readOnlyMetric}><strong>{(detail.courses||[]).filter(x=>x.entitlement_status==='ACTIVE').length}</strong><span>Authorised course(s)</span></div><div className={styles.readOnlyValues}>{(detail.courses||[]).filter(x=>x.entitlement_status==='ACTIVE').map(course=><span key={course.program_id} className={styles.valueChip}>{course.display_name}</span>)}{!(detail.courses||[]).some(x=>x.entitlement_status==='ACTIVE')?<span className={styles.emptyValue}>No courses authorised yet.</span>:null}</div></div>}
          </section>
          <section className={styles.panel}>
            <div className={styles.panelHeading}><div><h3>Institution administrators</h3><p>Only platform owner can create or suspend client admins.</p></div>
              {editingCard==='admins'?<div className={styles.cardActions}><button type="button" className={styles.secondary} onClick={()=>cancelCardEdit('admins')} disabled={saving}>Done</button><button type="button" className={styles.primary} onClick={()=>setShowAdmin(v=>!v)} disabled={saving}>{showAdmin?'Cancel add':'＋ Add admin'}</button></div>:<button type="button" className={styles.secondary} onClick={()=>setEditingCard('admins')} disabled={saving||editingCard!==null}>Edit</button>}
            </div>
            {editingCard==='admins'&&showAdmin?<form className={styles.form} onSubmit={createAdmin}><label>Username *<input required minLength={3} maxLength={64} value={admin.username} onChange={e=>setAdmin(v=>({...v,username:e.target.value}))}/></label><label>Full name *<input required minLength={2} maxLength={120} value={admin.displayName} onChange={e=>setAdmin(v=>({...v,displayName:e.target.value}))}/></label><label>Temporary password *<input required type="password" minLength={12} maxLength={72} value={admin.initialPassword} onChange={e=>setAdmin(v=>({...v,initialPassword:e.target.value}))}/></label><div className={styles.formActions}><button className={styles.primary} disabled={saving}>Provision admin</button></div></form>:null}
            <div className={styles.list}>{(detail.admins||[]).map(a=><div className={styles.listItem} key={a.id}><span className={styles.avatar}>{a.display_name.slice(0,1).toUpperCase()}</span><span className={styles.listCopy}><strong>{a.display_name}</strong><small>@{a.username} · {a.must_change_password?'initial password not changed':'password set'}</small><small>Last sign-in: {fmt(a.last_login_at)}</small></span>{editingCard==='admins'?<button className={styles.secondary} disabled={saving} onClick={()=>void toggleAdmin(a)}>{a.active?'Suspend':'Activate'}</button>:<span className={styles.status+(a.active?' '+styles.active:' '+styles.suspended)}>{a.active?'ACTIVE':'SUSPENDED'}</span>}</div>)}
            {!(detail.admins||[]).length?<p className={styles.emptyText}>No institution administrators configured.</p>:null}</div>
            {editingCard==='admins'?<p className={styles.disclaimer}>Admin provisioning and activation changes are applied immediately and recorded in the audit history.</p>:null}
          </section>
          <section className={styles.panel}>
            {editingCard==='branding'?<>
              <div className={styles.panelHeading}><div><h3>White-label branding</h3><p>School identity for learner-facing web and supported in-app surfaces. Logo files open in the crop editor; image assets are versioned and scoped to this institution.</p></div><div className={styles.cardActions}><button type="button" className={styles.secondary} onClick={()=>cancelCardEdit('branding')} disabled={saving}>Cancel</button><button type="submit" form="institution-branding-form" className={styles.primary} disabled={saving}>{saving?'Saving…':'Save branding'}</button></div></div>
              <form id="institution-branding-form" className={styles.form} onSubmit={saveBranding}>
                <label>Branded display name *<input required minLength={2} maxLength={180} value={brand.displayName} onChange={e=>setBrand(v=>({...v,displayName:e.target.value}))}/></label>
                <div className={styles.assetEditorRow}><div><strong>School logo</strong><small>Select an image to crop/reposition and normalize it for the learner logo. Transparent backgrounds are preserved where possible.</small></div><label className={styles.fileButton}><span>{detail.logo_url?'Replace and crop logo':'Upload and crop logo'}</span><input type="file" accept="image/png,image/jpeg" disabled={saving} onChange={e=>{const file=e.currentTarget.files?.[0];e.currentTarget.value='';if(file)setPendingCrop({assetType:'logo',file});}}/></label></div>
                {detail.logo_url?<div className={styles.brandAssetPreview}><span>Current learner logo</span><img src={detail.logo_url} alt={(detail.brand_display_name||detail.display_name)+' logo'}/><div className={styles.brandAssetActions}><button type="button" className={styles.danger} disabled={saving} onClick={()=>void removeBrandAsset('logo')}>Remove logo</button></div></div>:<p className={styles.disclaimer}>No school logo uploaded yet. A school initial is used until a logo is approved.</p>}
                <div className={styles.assetEditorRow}><div><strong>App icon</strong><small>Separate square crop for browser/app icon use. This is not forced onto the wider school logo.</small></div><label className={styles.fileButton}><span>{detail.app_icon_url?'Replace and crop app icon':'Upload and crop app icon'}</span><input type="file" accept="image/png,image/jpeg" disabled={saving} onChange={e=>{const file=e.currentTarget.files?.[0];e.currentTarget.value='';if(file)setPendingCrop({assetType:'app-icon',file});}}/></label></div>
                {detail.app_icon_url?<div className={styles.brandAssetPreview}><span>Current browser/app icon</span><img src={detail.app_icon_url} alt={(detail.brand_display_name||detail.display_name)+' app icon'}/><div className={styles.brandAssetActions}><button type="button" className={styles.danger} disabled={saving} onClick={()=>void removeBrandAsset('app-icon')}>Remove app icon</button></div></div>:<p className={styles.disclaimer}>No custom app icon uploaded yet.</p>}
                <label>Google Play listing URL<input type="url" value={brand.storeListingUrl} onChange={e=>setBrand(v=>({...v,storeListingUrl:e.target.value}))} placeholder="https://play.google.com/store/apps/details?id=…"/></label>
                <div className={styles.themeSettingsGrid}>
                  <label>Palette preset<select value={brand.presetId} onChange={e=>{const id=e.target.value;const preset=themePresets.find(item=>item.id===id);setBrand(v=>({...v,presetId:id,...(id!=='custom'&&preset?{primary:preset.primary,accent:preset.accent}:{})}));}}>
                    {themePresets.map(p=><option key={p.id} value={p.id}>{p.name}</option>)}
                  </select></label>
                  <label>Default appearance<select value={brand.appearance} onChange={e=>setBrand(v=>({...v,appearance:e.target.value}))}><option value="system">Follow device preference</option><option value="light">Always light</option><option value="dark">Always dark</option></select></label>
                </div>
                <div className={styles.colorGrid}><label>Primary brand colour<input type="color" value={brand.primary} onChange={e=>setBrand(v=>({...v,presetId:'custom',primary:e.target.value}))}/></label><label>Accent colour<input type="color" value={brand.accent} onChange={e=>setBrand(v=>({...v,presetId:'custom',accent:e.target.value}))}/></label></div>
                <div className={styles.themePreviewGrid} aria-label="Generated light and dark learner theme previews" aria-busy={themePreviewLoading}>
                  {themePreview&&lightPreview&&darkPreview?([{key:'light',title:'Light appearance',palette:lightPreview,sectionClass:styles.themePreviewLight},{key:'dark',title:'Dark appearance',palette:darkPreview,sectionClass:styles.themePreviewDark}] as const).map(view=><section key={view.key} className={view.sectionClass} style={{background:view.palette.background,color:view.palette.text,borderColor:view.palette.borderSubtle}}>
                    <div className={styles.themePreviewMeta}><strong>{view.title}</strong><span style={{background:view.palette.selectedBackground,color:view.palette.selectedText}}>Class 7 · Maths</span></div>
                    <div className={styles.themePreviewCard} style={{background:view.palette.surface,color:view.palette.onSurface,borderColor:view.palette.borderSubtle}}><small style={{color:view.palette.textMuted}}>YOUR COURSE</small><strong>Fractions and decimals</strong><p style={{color:view.palette.textMuted}}>Continue where you left off.</p><span className={styles.themePreviewPrimary} style={{background:view.palette.primary,color:view.palette.onPrimary}}>Start lesson</span><span className={styles.themePreviewSelected} style={{background:view.palette.accentContainer,color:view.palette.onAccentContainer,borderColor:view.palette.borderSubtle}}>Secondary action</span><span className={styles.themePreviewSelected} style={{background:view.palette.selectedBackground,color:view.palette.selectedText,borderColor:view.palette.borderSubtle}}>Selected lesson</span><div className={styles.themePreviewStates}><span style={{background:view.palette.successContainer,color:view.palette.onSuccessContainer}}>Correct</span><span style={{background:view.palette.warningContainer,color:view.palette.onWarningContainer}}>Review</span><span style={{background:view.palette.errorContainer,color:view.palette.onErrorContainer}}>Incorrect</span></div></div>
                  </section>):<p className={styles.themePreviewNotice} role="status">{themePreviewLoading?'Generating the exact server palette…':'Live preview is unavailable. Saving still uses the API-validated palette generator.'}</p>}
                </div>
                <p className={styles.disclaimer}>Colour tokens are generated by the central API palette engine. Image cropping only changes the uploaded asset; it does not alter the theme or the QuantaEdge operator brand.</p>
              </form>
            </>:<>
              <div className={styles.panelHeading}><div><h3>White-label branding</h3><p>Review the saved institution identity. Click Edit to change branding settings.</p></div><button type="button" className={styles.secondary} onClick={()=>setEditingCard('branding')} disabled={saving||editingCard!==null}>Edit</button></div>
              <div className={styles.readOnlyGrid}>
                <div className={styles.readOnlyItem}><span>Branded display name</span><strong>{detail.brand_display_name||detail.display_name}</strong></div>
                <div className={styles.readOnlyItem}><span>Google Play listing</span><strong className={styles.wrapValue}>{detail.store_listing_url||'Not configured'}</strong></div>
                <div className={styles.readOnlyItem}><span>School logo</span>{detail.logo_url?<img className={styles.readOnlyLogo} src={detail.logo_url} alt={(detail.brand_display_name||detail.display_name)+' logo'}/>:<small className={styles.emptyValue}>No logo uploaded</small>}</div>
                <div className={styles.readOnlyItem}><span>App icon</span>{detail.app_icon_url?<img className={styles.readOnlyIcon} src={detail.app_icon_url} alt={(detail.brand_display_name||detail.display_name)+' app icon'}/>:<small className={styles.emptyValue}>No app icon uploaded</small>}</div>
                <div className={styles.readOnlyItem}><span>Palette preset</span><strong>{themePresets.find(p=>p.id===String(detail.themeTokens?.presetId||'quantaedge-orange'))?.name||String(detail.themeTokens?.presetId||'QuantaEdge Orange')}</strong></div>
                <div className={styles.readOnlyItem}><span>Default appearance</span><strong>{String(detail.themeTokens?.appearance||'system').replace('system','Follow device preference').replace('light','Always light').replace('dark','Always dark')}</strong></div>
                <div className={styles.readOnlyItem}><span>Brand colours</span><div className={styles.brandSwatches}><span><i style={{background:String(detail.themeTokens?.brandPrimary||detail.themeTokens?.primary||'#F97316')}}/>Primary {String(detail.themeTokens?.brandPrimary||detail.themeTokens?.primary||'#F97316')}</span><span><i style={{background:String(detail.themeTokens?.brandAccent||detail.themeTokens?.accent||'#C2410C')}}/>Accent {String(detail.themeTokens?.brandAccent||detail.themeTokens?.accent||'#C2410C')}</span></div></div>
              </div>
            </>}
          </section>
          <section className={styles.panel}>
            {editingCard==='billing'?<>
              <div className={styles.panelHeading}><div><h3>Enrollment billing contract</h3><p>Changes are owner-controlled; review the rate and effective dates before saving.</p></div><div className={styles.cardActions}><button type="button" className={styles.secondary} onClick={()=>cancelCardEdit('billing')} disabled={saving}>Cancel</button><button type="submit" form="institution-billing-form" className={styles.primary} disabled={saving}>{saving?'Saving…':'Save contract'}</button></div></div>
              <div className={styles.billingBox}><span>Recorded charge total this period</span><strong>{rupees(institutions.find(i=>i.id===detail.id)?.current_period_amount_paise,detail.contract_currency)}</strong><small>{institutions.find(i=>i.id===detail.id)?.current_period_billable_events||0} membership charge event(s) in the list snapshot</small></div>
              <form id="institution-billing-form" className={styles.form} onSubmit={saveBilling}>
                <label>Billing currency<input required value="INR" readOnly aria-readonly="true"/><small>Currently supported: INR, stored in paise.</small></label>
                <label>Price per billed enrollment *<input required type="number" min="0" step="0.01" value={billing.unitPriceRupees} onChange={e=>setBilling(v=>({...v,unitPriceRupees:e.target.value}))}/></label>
                <label>Cadence<select value={billing.billingCadence} onChange={e=>setBilling(v=>({...v,billingCadence:e.target.value}))}><option value="MONTHLY">Monthly</option><option value="ANNUAL">Annual</option></select></label>
                <label>Effective from *<input required type="date" value={billing.effectiveFrom} onChange={e=>setBilling(v=>({...v,effectiveFrom:e.target.value}))}/></label>
                <label>End date<input type="date" value={billing.effectiveUntil} onChange={e=>setBilling(v=>({...v,effectiveUntil:e.target.value}))}/></label>
                <div className={styles.formActions}><button type="button" className={styles.secondary} disabled={saving||!detail.contract_id} title={!detail.contract_id?'Configure and save an active billing contract first':undefined} onClick={()=>void rotateCode()}>Issue / rotate join code</button><button type="button" className={styles.secondary} disabled={saving||!detail.contract_id} title={!detail.contract_id?'Configure and save an active billing contract first':undefined} onClick={()=>void reconcileBilling()}>Reconcile current period</button></div>
              </form>
              <p className={styles.disclaimer}>Invoice issuance, tax/credits, payment collection, contract versioning and ledger reconciliation are not yet release-ready.</p>
            </>:<>
              <div className={styles.panelHeading}><div><h3>Enrollment billing contract</h3><p>Contract settings are read-only until you select Edit.</p></div><button type="button" className={styles.secondary} onClick={beginBillingEdit} disabled={saving||editingCard!==null}>Edit</button></div>
              <div className={styles.readOnlyGrid}>
                <div className={styles.readOnlyItem}><span>Rate per billed enrollment</span><strong>{rupees(detail.unit_price_paise,detail.currency||'INR')}</strong></div>
                <div className={styles.readOnlyItem}><span>Billing currency</span><strong>{detail.currency||'INR'}</strong></div>
                <div className={styles.readOnlyItem}><span>Billing cadence</span><strong>{detail.billing_cadence==='ANNUAL'?'Annual':detail.billing_cadence==='MONTHLY'?'Monthly':detail.billing_cadence||'Not configured'}</strong></div>
                <div className={styles.readOnlyItem}><span>Effective from</span><strong>{detail.effective_from?.slice(0,10)||'Not configured'}</strong></div>
                <div className={styles.readOnlyItem}><span>End date</span><strong>{detail.effective_until?.slice(0,10)||'No end date'}</strong></div>
                <div className={styles.readOnlyItem}><span>Billable events this period</span><strong>{institutions.find(i=>i.id===detail.id)?.current_period_billable_events||0}</strong></div>
              </div>
              <div className={styles.billingBox}><span>Recorded charge total this period</span><strong>{rupees(institutions.find(i=>i.id===detail.id)?.current_period_amount_paise,detail.contract_currency)}</strong><small>Ledger snapshot; reconcile before issuing invoices.</small></div>
              <div className={styles.formActions}><button type="button" className={styles.secondary} disabled={saving||!detail.contract_id||editingCard!==null} title={!detail.contract_id?'Configure and save an active billing contract first':undefined} onClick={()=>void rotateCode()}>Issue / rotate join code</button><button type="button" className={styles.secondary} disabled={saving||!detail.contract_id||editingCard!==null} title={!detail.contract_id?'Configure and save an active billing contract first':undefined} onClick={()=>void reconcileBilling()}>Reconcile current period</button></div>
              <p className={styles.disclaimer}>Invoice issuance, tax/credits, payment collection and final invoicing are not yet release-ready.</p>
            </>}
          </section>

        </>:tab==='learners'?<section className={styles.panel}><h3>Institution learners</h3><p>Only records associated with this tenant are returned. The current profile stores city/state, not exact home address.</p><div className={styles.tableScroll}><table><thead><tr><th>Learner</th><th>Class</th><th>City/state</th><th>Membership</th><th>Progress</th></tr></thead><tbody>{roster.map((r,i)=><tr key={r.learner_reference||i}><td>{r.display_name}<small>{r.learner_reference}</small></td><td>{r.class_code||'—'}</td><td>{[r.city,r.state].filter(Boolean).join(', ')||'Not supplied'}</td><td>{r.membership_status}<small>{fmt(r.joined_at)}</small></td><td>{r.average_lesson_progress_percent||0}%<small>{r.completed_lessons||0} completed</small></td></tr>)}{!roster.length?<tr><td colSpan={5} className={styles.emptyCell}>No institution-linked learners yet.</td></tr>:null}</tbody></table></div></section>:<section className={styles.panel}><h3>Audit history</h3><p>Recent institutional lifecycle, access, branding and report events.</p><div className={styles.auditList}>{auditRows.map(row=><article key={row.id}><strong>{row.action.replaceAll('_',' ')}</strong><small>{row.target_type}{row.target_id?' · '+row.target_id:''} · {fmt(row.created_at)}</small><p>{typeof row.details==='string'?row.details:JSON.stringify(row.details||{})}</p></article>)}{!auditRows.length?<p className={styles.emptyText}>No audited events for this tenant.</p>:null}</div></section>}
      </>}</section>
    </div>
    {pendingCrop?<InstitutionBrandAssetEditor assetType={pendingCrop.assetType} file={pendingCrop.file} onCancel={()=>setPendingCrop(null)} onConfirm={file=>{const assetType=pendingCrop?.assetType||'logo';setPendingCrop(null);void uploadBrandAsset(assetType,file);}}/>:null}
    <footer className={styles.footer}><span>QuantaEdge platform operations · HRMS owner access only</span><span>API permission checks remain authoritative.</span></footer>
  </main>;
}
