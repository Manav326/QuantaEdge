'use client';

import {useCallback,useEffect,useState} from 'react';
import Link from 'next/link';
import {useRouter} from 'next/navigation';

type Session={staffId?:number;displayName?:string};
type LeaveType={code:string;display_name:string;paid:boolean};
type Range={from:string;to:string};
async function api(path:string,init:RequestInit={}) {
  const headers=new Headers(init.headers);
  headers.set('X-QE-Workspace','STAFF');
  if(init.body&&!headers.has('Content-Type'))headers.set('Content-Type','application/json');
  const response=await fetch(path,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text();
  let data:any={};
  try{data=raw?JSON.parse(raw):{}}catch{data={message:raw}}
  if(!response.ok)throw new Error(data.message||data.detail||data.error||`Request failed (${response.status})`);
  return data;
}
const localDate=(date:Date)=>date.getFullYear()+'-'+String(date.getMonth()+1).padStart(2,'0')+'-'+String(date.getDate()).padStart(2,'0');
const initialRange=():Range=>{const now=new Date();return {from:localDate(new Date(now.getFullYear(),now.getMonth(),1)),to:localDate(now)}};
const pretty=(value:string)=>String(value||'').replaceAll('_',' ').toLowerCase().replace(/\\b\\w/g,c=>c.toUpperCase());
export default function EmployeeSelfServicePage(){
  const router=useRouter();
  const [session,setSession]=useState<Session|null>(null);
  const [profile,setProfile]=useState<any|null>(null);
  const [attendance,setAttendance]=useState<any[]>([]);
  const [requests,setRequests]=useState<any[]>([]);
  const [types,setTypes]=useState<LeaveType[]>([]);
  const [balances,setBalances]=useState<any[]>([]);
  const [range,setRange]=useState<Range>(initialRange);
  const [tab,setTab]=useState<'attendance'|'leave'|'balances'>('attendance');
  const [draft,setDraft]=useState({leaveTypeCode:'',startDate:localDate(new Date()),endDate:localDate(new Date()),reason:''});
  const [loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const daySpan=Math.floor((Date.parse(range.to+'T00:00:00Z')-Date.parse(range.from+'T00:00:00Z'))/86400000);
  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const me=await api('/api/v1/auth/me');
      if(!me.staffId){router.replace('/login');return}
      setSession(me);
      const ownProfile=await api('/api/v1/hr/self-service/profile');
      setProfile(ownProfile);
      const [a,l,t,b]=await Promise.all([
        api('/api/v1/hr/self-service/attendance?from='+range.from+'&to='+range.to+'&limit=200'),
        api('/api/v1/hr/self-service/leave/requests?limit=100'),
        api('/api/v1/hr/self-service/leave/types'),
        api('/api/v1/hr/self-service/leave/balances')
      ]);
      setAttendance(a.items||[]);setRequests(l.items||[]);setTypes(t||[]);setBalances(b.items||[]);
      if(t.length&&!t.some((item:LeaveType)=>item.code===draft.leaveTypeCode))setDraft(v=>({...v,leaveTypeCode:t[0].code}));
    }catch(e){
      setError(e instanceof Error?e.message:'Your employee workspace could not be loaded.');
      if(String(e).includes('Authentication required')||String(e).includes('UNAUTHENTICATED'))router.replace('/login');
    }finally{setLoading(false)}
  },[router,range.from,range.to]);
  useEffect(()=>{void load()},[load]);
  async function submitLeave(event:React.FormEvent){
    event.preventDefault();setSaving(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/hr/self-service/leave/requests',{method:'POST',body:JSON.stringify(draft)});
      setNotice('Leave request #'+result.id+' submitted for review.');
      setDraft(v=>({...v,reason:''}));
      await load();
      setTab('leave');
    }catch(e){setError(e instanceof Error?e.message:'Leave request could not be submitted.')}finally{setSaving(false)}
  }
  async function logout(){try{await api('/api/v1/auth/logout',{method:'POST'})}finally{router.replace('/login')}}
  return <div className="shell">
    <header className="topbar">
      <div className="brand">QuantaEdge <span>EMPLOYEE SELF-SERVICE</span></div>
      <nav aria-label="Workspace navigation" style={{display:'flex',flexWrap:'wrap',gap:8,alignItems:'center'}}>
        <Link href="/self-service" className="secondary" style={{textDecoration:'none'}}>My workspace</Link>
        <button type="button" className="secondary" onClick={()=>void logout()}>Sign out</button>
      </nav>
    </header>
    <main className="main">
      <div className="eyebrow">YOUR EMPLOYMENT · PRIVATE WORKSPACE</div>
      <h1>My workspace</h1>
      <p className="sub">Review your own employment details, attendance, leave requests and available leave balances. HR-only records and other employees remain outside this view.</p>
      {error&&<div className="error" role="alert">{error}</div>}
      {notice&&<div className="notice" role="status">{notice}</div>}
      {profile&&<section className="panel" aria-labelledby="profile-heading">
        <div style={{display:'flex',justifyContent:'space-between',gap:16,alignItems:'start',flexWrap:'wrap'}}>
          <div><div className="eyebrow">EMPLOYEE PROFILE</div><h2 id="profile-heading">{profile.display_name}</h2><p className="small">{profile.employee_number} · {profile.organization_name}</p></div>
          <span className="pill">{pretty(profile.employment_status)}</span>
        </div>
        <div className="cards">
          <section className="card"><div className="label">Department</div><div style={{fontWeight:700}}>{profile.department_name||'Not assigned'}</div><div className="small">{profile.designation_name||'Designation not set'}</div></section>
          <section className="card"><div className="label">Employment type</div><div style={{fontWeight:700}}>{profile.employment_type||pretty(profile.employment_type_code)}</div><div className="small">Joined {String(profile.joining_date).slice(0,10)}</div></section>
          <section className="card"><div className="label">Reporting manager</div><div style={{fontWeight:700}}>{profile.manager_name||'Not assigned'}</div><div className="small">{profile.email||profile.phone_e164||'No contact details on file'}</div></section>
        </div>
      </section>}
      <section className="panel" style={{marginTop:20}}>
        <div className="tabs" role="group" aria-label="My employment information">
          <button type="button" aria-pressed={tab==='attendance'} className={tab==='attendance'?'active':''} onClick={()=>setTab('attendance')}>My attendance</button>
          <button type="button" aria-pressed={tab==='leave'} className={tab==='leave'?'active':''} onClick={()=>setTab('leave')}>My leave</button>
          <button type="button" aria-pressed={tab==='balances'} className={tab==='balances'?'active':''} onClick={()=>setTab('balances')}>Leave balances</button>
          <button type="button" className="secondary" onClick={()=>void load()} disabled={loading}>Refresh</button>
        </div>
        {tab==='attendance'&&<>
          <div className="toolbar">
            <label className="field">From<input className="input" type="date" value={range.from} max={range.to} onChange={e=>setRange(v=>({...v,from:e.target.value}))}/></label>
            <label className="field">To<input className="input" type="date" value={range.to} min={range.from} onChange={e=>setRange(v=>({...v,to:e.target.value}))}/></label>
          </div>
          {daySpan>92&&<div className="error" role="alert">Choose a date range of 93 days or fewer.</div>}
          <div className="table-wrap"><table><thead><tr><th>Work date</th><th>Status</th><th>Check in</th><th>Check out</th><th>Worked</th><th>Note</th></tr></thead><tbody>
            {attendance.map((item:any)=><tr key={item.work_date}><td>{String(item.work_date).slice(0,10)}</td><td><span className="pill">{pretty(item.attendance_status)}</span></td><td>{item.check_in_at?new Date(item.check_in_at).toLocaleString():'—'}</td><td>{item.check_out_at?new Date(item.check_out_at).toLocaleString():'—'}</td><td>{item.minutes_worked==null?'—':Math.floor(item.minutes_worked/60)+'h '+item.minutes_worked%60+'m'}</td><td>{item.notes||'—'}</td></tr>)}
          </tbody></table>{!loading&&!attendance.length&&<div className="empty">No attendance records were returned for this date range.</div>}</div>
        </>}
        {tab==='leave'&&<>
          <form className="panel" onSubmit={submitLeave} style={{marginBottom:20}}>
            <h2>Request leave</h2>
            <p className="small">Your request is submitted for review. Overlapping pending or approved requests and ranges without working days are rejected by the server.</p>
            <div className="form-grid">
              <label className="field">Leave type<select required value={draft.leaveTypeCode} onChange={e=>setDraft(v=>({...v,leaveTypeCode:e.target.value}))}><option value="">Choose leave type…</option>{types.map(t=><option key={t.code} value={t.code}>{t.display_name}{t.paid?' · Paid':' · Unpaid'}</option>)}</select></label>
              <label className="field">Start date<input className="input" type="date" required value={draft.startDate} onChange={e=>setDraft(v=>({...v,startDate:e.target.value,endDate:v.endDate<e.target.value?e.target.value:v.endDate}))}/></label>
              <label className="field">End date<input className="input" type="date" required min={draft.startDate} value={draft.endDate} onChange={e=>setDraft(v=>({...v,endDate:e.target.value}))}/></label>
              <label className="field full">Reason<textarea rows={3} required maxLength={1000} value={draft.reason} onChange={e=>setDraft(v=>({...v,reason:e.target.value}))}/></label>
            </div>
            <div className="actions"><button className="primary" disabled={saving||loading||!types.length||!draft.reason.trim()}>{saving?'Submitting…':'Submit leave request'}</button></div>
          </form>
          <h2>My requests</h2>
          <div className="table-wrap"><table><thead><tr><th>Leave type</th><th>Dates / days</th><th>Reason</th><th>Status</th><th>Review note</th></tr></thead><tbody>
            {requests.map((item:any)=><tr key={item.id}><td>{item.leave_type}<div className="small">{item.paid?'Paid':'Unpaid'}</div></td><td>{String(item.start_date).slice(0,10)} → {String(item.end_date).slice(0,10)}<div className="small">{Number(item.requested_days)} working day(s)</div></td><td>{item.reason}</td><td><span className="pill">{pretty(item.request_status)}</span></td><td>{item.review_note||'—'}</td></tr>)}
          </tbody></table>{!loading&&!requests.length&&<div className="empty">You have not submitted any leave requests.</div>}</div>
        </>}
        {tab==='balances'&&<>
          <p className="small">Balances are calculated from the official leave-balance ledger as of today. No balance is invented when no grant/accrual has been recorded.</p>
          <div className="table-wrap"><table><thead><tr><th>Leave type</th><th>Paid</th><th>Balance (days)</th></tr></thead><tbody>
            {balances.map((item:any)=><tr key={item.leave_type_code}><td>{item.display_name}</td><td>{item.paid?'Yes':'No'}</td><td><b>{Number(item.balance_days).toFixed(2)}</b></td></tr>)}
          </tbody></table>{!loading&&!balances.length&&<div className="empty">No active leave types or balances are available.</div>}</div>
        </>}
        {loading&&<p className="small" role="status" aria-live="polite">Loading your employee records…</p>}
      </section>
      <p className="small" style={{marginTop:18}}>This workspace is scoped to the employee linked to your authenticated account. Profile changes, attendance corrections, private documents and payslips require separate controlled workflows; use People Operations support for corrections until those workflows are available.</p>
      {session&&!profile&&!loading&&<p className="small">If you believe your employment record should be linked, contact your People Operations administrator.</p>}
    </main>
  </div>;
}
