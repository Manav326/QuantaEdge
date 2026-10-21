'use client';

import { formatUserTimestamp } from '../components/formatUserTimestamp';
import { PeoplePageHeader, PeopleStatusBadge, PeopleFeedback, PeopleEmptyState, PeoplePagination } from '../components/PeopleUI';
import { useUiTranslation } from '../i18n/UiLocaleProvider';

import {useCallback,useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type Session={staffId?:number;displayName?:string};
type LeaveType={code:string;display_name:string;paid:boolean};
type Range={from:string;to:string};
async function api(path:string,init:RequestInit={}) {
  const headers=new Headers(init.headers);
  headers.set('X-QE-Workspace','HRMS');
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
const pretty=(value:string)=>String(value||'').replaceAll('_',' ').toLowerCase().replace(/\b\w/g,c=>c.toUpperCase());
export default function EmployeeSelfServicePage(){
  const router=useRouter();
  const {locale,t:ui}=useUiTranslation();
  const [session,setSession]=useState<Session|null>(null);
  const [profile,setProfile]=useState<any|null>(null);
  const [unlinked,setUnlinked]=useState(false);
  const [attendance,setAttendance]=useState<any[]>([]),[attendancePageLimit,setAttendancePageLimit]=useState(20),[attendanceOffset,setAttendanceOffset]=useState(0),[attendanceTotal,setAttendanceTotal]=useState(0);
  const [requests,setRequests]=useState<any[]>([]),[leavePageLimit,setLeavePageLimit]=useState(20),[leaveOffset,setLeaveOffset]=useState(0),[leaveTotal,setLeaveTotal]=useState(0);
  const [types,setTypes]=useState<LeaveType[]>([]);
  const [balances,setBalances]=useState<any[]>([]);
  const [payslips,setPayslips]=useState<any[]>([]),[payslipPageLimit,setPayslipPageLimit]=useState(20),[payslipOffset,setPayslipOffset]=useState(0),[payslipTotal,setPayslipTotal]=useState(0);
  const [profileChangeRequests,setProfileChangeRequests]=useState<any[]>([]),[profilePageLimit,setProfilePageLimit]=useState(20),[profileOffset,setProfileOffset]=useState(0),[profileTotal,setProfileTotal]=useState(0);
  const [attendanceCorrectionRequests,setAttendanceCorrectionRequests]=useState<any[]>([]),[correctionPageLimit,setCorrectionPageLimit]=useState(20),[correctionOffset,setCorrectionOffset]=useState(0),[correctionTotal,setCorrectionTotal]=useState(0);
  const [correctionDraft,setCorrectionDraft]=useState({workDate:'',status:'PRESENT',checkInAt:'',checkOutAt:'',notes:'',reason:''});
  const [selectedPayslip,setSelectedPayslip]=useState<any|null>(null);
  const [range,setRange]=useState<Range>(initialRange);
  const [tab,setTab]=useState<'attendance'|'leave'|'balances'|'payslips'|'profile'>('attendance');
  const [draft,setDraft]=useState({leaveTypeCode:'',startDate:localDate(new Date()),endDate:localDate(new Date()),reason:''});
  const [profileDraft,setProfileDraft]=useState({email:'',phoneE164:'',reason:''});
  const [loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
  const daySpan=Math.floor((Date.parse(range.to+'T00:00:00Z')-Date.parse(range.from+'T00:00:00Z'))/86400000);
  const load=useCallback(async()=>{
    setLoading(true);setError('');setUnlinked(false);
    try{
      const me=await api('/api/v1/auth/me');
      if(!me.staffId){router.replace('/login');return}
      setSession(me);
      let ownProfile:any;
      try {
        ownProfile=await api('/api/v1/hr/self-service/profile');
      } catch(e) {
        if(e instanceof Error && e.message.includes('No active employee record is linked to this account')) {
          setUnlinked(true);setProfile(null);setAttendance([]);setRequests([]);setTypes([]);setBalances([]);
          setPayslips([]);setProfileChangeRequests([]);setAttendanceCorrectionRequests([]);setError('');
          return;
        }
        throw e;
      }
      setUnlinked(false);setProfile(ownProfile);
      const [a,l,t,b,ps,pr,cr]=await Promise.all([
        api('/api/v1/hr/self-service/attendance?from='+range.from+'&to='+range.to+'&limit='+attendancePageLimit+'&offset='+attendanceOffset),
        api('/api/v1/hr/self-service/leave/requests?limit='+leavePageLimit+'&offset='+leaveOffset),
        api('/api/v1/hr/self-service/leave/types'),
        api('/api/v1/hr/self-service/leave/balances'),
        api('/api/v1/hr/self-service/payslips?limit='+payslipPageLimit+'&offset='+payslipOffset),
        api('/api/v1/hr/self-service/profile-change-requests?limit='+profilePageLimit+'&offset='+profileOffset),
        api('/api/v1/hr/self-service/attendance/correction-requests?limit='+correctionPageLimit+'&offset='+correctionOffset)
      ]);
      setAttendance(a.items||[]);setAttendanceTotal(Number(a.total||0));setRequests(l.items||[]);setLeaveTotal(Number(l.total||0));setTypes(t||[]);setBalances(b.items||[]);
      setPayslips((ps.items||[]).map((item:any)=>({...item,snapshot:typeof item.snapshot==='string'?JSON.parse(item.snapshot):item.snapshot})));setPayslipTotal(Number(ps.total||0));
      setProfileChangeRequests((pr.items||[]).map((item:any)=>({...item,requested_values:typeof item.requested_values==='string'?JSON.parse(item.requested_values):item.requested_values})));setProfileTotal(Number(pr.total||0));
      setAttendanceCorrectionRequests(cr.items||[]);setCorrectionTotal(Number(cr.total||0));
      if(t.length&&!t.some((item:LeaveType)=>item.code===draft.leaveTypeCode))setDraft(v=>({...v,leaveTypeCode:t[0].code}));
    }catch(e){
      setError(e instanceof Error?e.message:ui('people.selfService.loadFailed'));
      if(String(e).includes('Authentication required')||String(e).includes('UNAUTHENTICATED'))router.replace('/login');
    }finally{setLoading(false)}
  },[router,range.from,range.to,attendancePageLimit,attendanceOffset,leavePageLimit,leaveOffset,payslipPageLimit,payslipOffset,profilePageLimit,profileOffset,correctionPageLimit,correctionOffset]);
  useEffect(()=>{void load()},[load]);
  async function submitLeave(event:React.FormEvent){
    event.preventDefault();setSaving(true);setError('');setNotice('');
    try{
      const result=await api('/api/v1/hr/self-service/leave/requests',{method:'POST',body:JSON.stringify(draft)});
      setNotice(ui('people.selfService.leaveSubmitted',{id:result.id}));
      setDraft(v=>({...v,reason:''}));
      await load();
      setTab('leave');
    }catch(e){setError(e instanceof Error?e.message:ui('people.selfService.leaveSubmitFailed'))}finally{setSaving(false)}
  }
  async function submitAttendanceCorrection(event:React.FormEvent){
    event.preventDefault();setSaving(true);setError('');setNotice('');
    try{
      const payload={...correctionDraft,checkInAt:correctionDraft.checkInAt?new Date(correctionDraft.checkInAt).toISOString():null,checkOutAt:correctionDraft.checkOutAt?new Date(correctionDraft.checkOutAt).toISOString():null};const result=await api('/api/v1/hr/self-service/attendance/correction-requests',{method:'POST',body:JSON.stringify(payload)});
      setNotice(ui('people.selfService.correctionSubmitted',{id:result.id}));
      setCorrectionDraft(v=>({...v,checkInAt:'',checkOutAt:'',notes:'',reason:''}));
      await load();setTab('attendance');
    }catch(e){setError(e instanceof Error?e.message:ui('people.selfService.correctionSubmitFailed'))}finally{setSaving(false)}
  }
  async function cancelAttendanceCorrection(id:number){
    setSaving(true);setError('');setNotice('');
    try{await api('/api/v1/hr/self-service/attendance/correction-requests/'+id+'/cancel',{method:'PATCH'});setNotice(ui('people.selfService.correctionCancelled'));await load();setTab('attendance')}
    catch(e){setError(e instanceof Error?e.message:ui('people.selfService.correctionCancelFailed'))}finally{setSaving(false)}
  }
  async function submitProfileChange(event:React.FormEvent){
    event.preventDefault();setSaving(true);setError('');setNotice('');
    const body:any={reason:profileDraft.reason};
    if(profileDraft.email.trim())body.email=profileDraft.email.trim();
    if(profileDraft.phoneE164.trim())body.phoneE164=profileDraft.phoneE164.trim();
    if(!body.email&&!body.phoneE164){setError(ui('people.selfService.profileContactRequired'));setSaving(false);return}
    try{
      const result=await api('/api/v1/hr/self-service/profile-change-requests',{method:'POST',body:JSON.stringify(body)});
      setNotice(ui('people.selfService.profileChangeSubmitted',{id:result.id}));
      setProfileDraft({email:'',phoneE164:'',reason:''});
      await load();setTab('profile');
    }catch(e){setError(e instanceof Error?e.message:ui('people.selfService.profileChangeSubmitFailed'))}finally{setSaving(false)}
  }
  async function cancelProfileChange(id:number){
    setSaving(true);setError('');setNotice('');
    try{await api('/api/v1/hr/self-service/profile-change-requests/'+id+'/cancel',{method:'PATCH'});setNotice(ui('people.selfService.profileChangeCancelled'));await load();setTab('profile')}
    catch(e){setError(e instanceof Error?e.message:ui('people.selfService.profileChangeCancelFailed'))}finally{setSaving(false)}
  }
  async function logout(){try{await api('/api/v1/auth/logout',{method:'POST'})}finally{router.replace('/login')}}
  return <div className="shell">
    
    <main className="main">
      <PeoplePageHeader eyebrow={ui("people.selfService.eyebrow")} title={ui("people.selfService.title")} description={ui("people.selfService.description")} />
      {error&&<PeopleFeedback tone="danger" role="alert">{error}</PeopleFeedback>}
      {notice&&<PeopleFeedback tone="success" role="status">{notice}</PeopleFeedback>}
      {!unlinked&&profile&&<section className="panel" aria-labelledby="profile-heading">
        <div style={{display:'flex',justifyContent:'space-between',gap:16,alignItems:'start',flexWrap:'wrap'}}>
          <div><div className="eyebrow">EMPLOYEE PROFILE</div><h2 id="profile-heading">{profile.display_name}</h2><p className="small">{profile.employee_number} · {profile.organization_name}</p></div>
          <PeopleStatusBadge status={pretty(profile.employment_status)} />
        </div>
        <div className="cards">
          <section className="card"><div className="label">Department</div><div style={{fontWeight:700}}>{profile.department_name||'Not assigned'}</div><div className="small">{profile.designation_name||'Designation not set'}</div></section>
          <section className="card"><div className="label">Employment type</div><div style={{fontWeight:700}}>{profile.employment_type||pretty(profile.employment_type_code)}</div><div className="small">Joined {String(profile.joining_date).slice(0,10)}</div></section>
          <section className="card"><div className="label">Reporting manager</div><div style={{fontWeight:700}}>{profile.manager_name||'Not assigned'}</div><div className="small">{profile.email||profile.phone_e164||'No contact details on file'}</div></section>
        </div>
      </section>}
      {!unlinked&&<section className="panel" style={{marginTop:20}}>
        <div className="tabs" role="group" aria-label="My employment information">
          <button type="button" aria-pressed={tab==='attendance'} className={tab==='attendance'?'active':''} onClick={()=>setTab('attendance')}>{ui("people.selfService.tabAttendance")}</button>
          <button type="button" aria-pressed={tab==='leave'} className={tab==='leave'?'active':''} onClick={()=>setTab('leave')}>{ui("people.selfService.tabLeave")}</button>
          <button type="button" aria-pressed={tab==='balances'} className={tab==='balances'?'active':''} onClick={()=>setTab('balances')}>{ui("people.selfService.tabBalances")}</button>
          <button type="button" aria-pressed={tab==='payslips'} className={tab==='payslips'?'active':''} onClick={()=>{setTab('payslips');setSelectedPayslip(null)}}>{ui("people.selfService.tabPayslips")}</button>
          <button type="button" aria-pressed={tab==='profile'} className={tab==='profile'?'active':''} onClick={()=>setTab('profile')}>{ui("people.selfService.tabProfile")}</button>
          <button type="button" className="secondary" onClick={()=>void load()} disabled={loading}>{ui("people.selfService.refresh")}</button>
        </div>
        {tab==='profile'&&<>
          <section className="panel">
            <h2>{ui("people.selfService.requestProfileChange")}</h2>
            <p className="small">{ui("people.selfService.profileChangeHelp")}</p>
            <div className="cards">
              <div className="card"><div className="label">Current email</div><strong>{profile?.email||'Not provided'}</strong></div>
              <div className="card"><div className="label">Current phone</div><strong>{profile?.phone_e164||'Not provided'}</strong></div>
            </div>
            <form className="form-grid" onSubmit={submitProfileChange} style={{marginTop:18}}>
              <label className="field">{ui("people.selfService.newEmail")}<input className="input" type="email" maxLength={254} value={profileDraft.email} onChange={e=>setProfileDraft(v=>({...v,email:e.target.value}))} placeholder="name@example.com"/></label>
              <label className="field">{ui("people.selfService.newPhone")}<input className="input" type="tel" maxLength={24} value={profileDraft.phoneE164} onChange={e=>setProfileDraft(v=>({...v,phoneE164:e.target.value}))} placeholder="+919876543210" pattern="^\+[1-9][0-9]{7,14}$"/></label>
              <label className="field full">{ui("people.selfService.reasonChange")}<textarea className="input" rows={3} maxLength={1000} required value={profileDraft.reason} onChange={e=>setProfileDraft(v=>({...v,reason:e.target.value}))} placeholder="Explain why your contact details need updating."/></label>
              <div className="actions"><button type="submit" className="primary" disabled={saving}>{saving?ui('people.selfService.submitReview'):ui('people.selfService.submitReview')}</button></div>
            </form>
          </section>
          <section className="panel">
            <h2>{ui("people.selfService.profileRequestsTitle")}</h2>
            {profileChangeRequests.length===0?<PeopleEmptyState title={ui("people.selfService.noProfileRequests")} />:<div className="table-wrap"><table><thead><tr><th>{ui("people.selfService.submitted")}</th><th>{ui("people.selfService.requestedChanges")}</th><th>{ui("people.selfService.reason")}</th><th>{ui("people.selfService.status")}</th><th>{ui("people.selfService.reviewNote")}</th><th>{ui("people.selfService.action")}</th></tr></thead><tbody>
              {profileChangeRequests.map((item:any)=><tr key={item.id}><td>{formatUserTimestamp(item.created_at)}</td><td>{item.requested_values?.email&&<div>Email: {item.requested_values.email}</div>}{item.requested_values?.phoneE164&&<div>Phone: {item.requested_values.phoneE164}</div>}</td><td>{item.reason}</td><td><PeopleStatusBadge status={pretty(item.request_status)} /></td><td>{item.review_note||'—'}</td><td>{item.request_status==='PENDING'?<button type="button" className="secondary" disabled={saving} onClick={()=>void cancelProfileChange(item.id)}>Cancel</button>:'—'}</td></tr>)}
            </tbody></table></div>}
            <PeoplePagination limit={profilePageLimit} offset={profileOffset} total={profileTotal} onPageChange={setProfileOffset} onLimitChange={limit=>{setProfilePageLimit(limit);setProfileOffset(0)}} />
          </section>
        </>}
        {tab==='attendance'&&<>
          <div className="toolbar">
            <label className="field">{ui("people.selfService.from")}<input className="input" type="date" value={range.from} max={range.to} onChange={e=>{setRange(v=>({...v,from:e.target.value}));setAttendanceOffset(0)}}/></label>
            <label className="field">{ui("people.selfService.to")}<input className="input" type="date" value={range.to} min={range.from} onChange={e=>{setRange(v=>({...v,to:e.target.value}));setAttendanceOffset(0)}}/></label>
          </div>
          {daySpan>92&&<div className="error" role="alert">{ui("people.selfService.rangeHelp")}</div>}
          <section className="panel" style={{marginTop:18}}>
            <h2>{ui("people.selfService.requestCorrection")}</h2>
            <p className="small">{ui("people.selfService.correctionHelp")}</p>
            <form className="form-grid" onSubmit={submitAttendanceCorrection}>
              <label className="field">{ui("people.selfService.workDate")}<input className="input" type="date" required value={correctionDraft.workDate} onChange={e=>setCorrectionDraft(v=>({...v,workDate:e.target.value}))}/></label>
              <label className="field">{ui("people.selfService.requestedStatus")}<select required value={correctionDraft.status} onChange={e=>setCorrectionDraft(v=>({...v,status:e.target.value}))}>{['PRESENT','ABSENT','HALF_DAY','REMOTE','ON_LEAVE','HOLIDAY'].map(status=><option key={status} value={status}>{pretty(status)}</option>)}</select></label>
              <label className="field">{ui("people.selfService.checkInOptional")}<input className="input" type="datetime-local" value={correctionDraft.checkInAt} onChange={e=>setCorrectionDraft(v=>({...v,checkInAt:e.target.value}))}/></label>
              <label className="field">{ui("people.selfService.checkOutOptional")}<input className="input" type="datetime-local" value={correctionDraft.checkOutAt} min={correctionDraft.checkInAt||undefined} onChange={e=>setCorrectionDraft(v=>({...v,checkOutAt:e.target.value}))}/></label>
              <label className="field full">{ui("people.selfService.correctedNoteOptional")}<textarea className="input" rows={2} maxLength={500} value={correctionDraft.notes} onChange={e=>setCorrectionDraft(v=>({...v,notes:e.target.value}))}/></label>
              <label className="field full">{ui("people.selfService.correctionReason")}<textarea className="input" rows={3} required maxLength={1000} value={correctionDraft.reason} onChange={e=>setCorrectionDraft(v=>({...v,reason:e.target.value}))} placeholder="Explain what is incorrect and why it should be changed."/></label>
              <div className="actions"><button className="primary" disabled={saving||loading||!correctionDraft.workDate||!correctionDraft.reason.trim()}>{saving?ui('people.selfService.submitReview'):ui('people.selfService.submitCorrection')}</button></div>
            </form>
          </section>
          <section className="panel">
            <h2>{ui("people.selfService.correctionRequestsTitle")}</h2>
            {attendanceCorrectionRequests.length===0?<PeopleEmptyState title={ui("people.selfService.noCorrectionRequests")} />:<div className="table-wrap"><table><thead><tr><th>Work date</th><th>Requested status</th><th>{ui("people.selfService.reason")}</th><th>{ui("people.selfService.status")}</th><th>{ui("people.selfService.reviewNote")}</th><th>{ui("people.selfService.action")}</th></tr></thead><tbody>
              {attendanceCorrectionRequests.map((item:any)=><tr key={item.id}><td>{String(item.work_date).slice(0,10)}</td><td>{pretty(item.requested_status)}<div className="small">{item.requested_check_in_at?'In: '+formatUserTimestamp(item.requested_check_in_at)+' ':''}{item.requested_check_out_at?'Out: '+formatUserTimestamp(item.requested_check_out_at):''}</div></td><td>{item.reason}</td><td><PeopleStatusBadge status={pretty(item.request_status)} /></td><td>{item.review_note||'—'}</td><td>{item.request_status==='PENDING'?<button type="button" className="secondary" disabled={saving} onClick={()=>void cancelAttendanceCorrection(item.id)}>Cancel</button>:'—'}</td></tr>)}
            </tbody></table></div>}
            <PeoplePagination limit={correctionPageLimit} offset={correctionOffset} total={correctionTotal} onPageChange={setCorrectionOffset} onLimitChange={limit=>{setCorrectionPageLimit(limit);setCorrectionOffset(0)}} />
          </section>
          <div className="table-wrap"><table><thead><tr><th>Work date</th><th>{ui("people.selfService.status")}</th><th>Check in</th><th>Check out</th><th>Worked</th><th>Note</th></tr></thead><tbody>
            {attendance.map((item:any)=><tr key={item.work_date}><td>{String(item.work_date).slice(0,10)}</td><td><PeopleStatusBadge status={pretty(item.attendance_status)} /></td><td>{item.check_in_at?formatUserTimestamp(item.check_in_at):'—'}</td><td>{item.check_out_at?formatUserTimestamp(item.check_out_at):'—'}</td><td>{item.minutes_worked==null?'—':Math.floor(item.minutes_worked/60)+'h '+item.minutes_worked%60+'m'}</td><td>{item.notes||'—'}</td></tr>)}
          </tbody></table>{!loading&&!attendance.length&&<PeopleEmptyState title="No attendance records were returned for this date range." />}</div>
          <PeoplePagination limit={attendancePageLimit} offset={attendanceOffset} total={attendanceTotal} onPageChange={setAttendanceOffset} onLimitChange={limit=>{setAttendancePageLimit(limit);setAttendanceOffset(0)}} />
        </>}
        {tab==='leave'&&<>
          <form className="panel" onSubmit={submitLeave} style={{marginBottom:20}}>
            <h2>{ui("people.selfService.leaveRequestTitle")}</h2>
            <p className="small">{ui("people.selfService.leaveRequestHelp")}</p>
            <div className="form-grid">
              <label className="field">{ui("people.selfService.leaveType")}<select required value={draft.leaveTypeCode} onChange={e=>setDraft(v=>({...v,leaveTypeCode:e.target.value}))}><option value="">{ui("people.selfService.chooseLeaveType")}</option>{types.map(t=><option key={t.code} value={t.code}>{t.display_name}{t.paid?' · '+ui('people.selfService.paid'):' · '+ui('people.selfService.unpaid')}</option>)}</select></label>
              <label className="field">{ui("people.selfService.startDate")}<input className="input" type="date" required value={draft.startDate} onChange={e=>setDraft(v=>({...v,startDate:e.target.value,endDate:v.endDate<e.target.value?e.target.value:v.endDate}))}/></label>
              <label className="field">{ui("people.selfService.endDate")}<input className="input" type="date" required min={draft.startDate} value={draft.endDate} onChange={e=>setDraft(v=>({...v,endDate:e.target.value}))}/></label>
              <label className="field full">Reason<textarea rows={3} required maxLength={1000} value={draft.reason} onChange={e=>setDraft(v=>({...v,reason:e.target.value}))}/></label>
            </div>
            <div className="actions"><button className="primary" disabled={saving||loading||!types.length||!draft.reason.trim()}>{saving?ui('people.selfService.submitReview'):ui('people.selfService.submitLeave')}</button></div>
          </form>
          <h2>{ui("people.selfService.myRequests")}</h2>
          <div className="table-wrap"><table><thead><tr><th>Leave type</th><th>{ui("people.selfService.datesDays")}</th><th>{ui("people.selfService.reason")}</th><th>{ui("people.selfService.status")}</th><th>{ui("people.selfService.reviewNote")}</th></tr></thead><tbody>
            {requests.map((item:any)=><tr key={item.id}><td>{item.leave_type}<div className="small"><PeopleStatusBadge status={item.paid?'Paid':'Unpaid'} /></div></td><td>{String(item.start_date).slice(0,10)} → {String(item.end_date).slice(0,10)}<div className="small">{Number(item.requested_days)} working day(s)</div></td><td>{item.reason}</td><td><PeopleStatusBadge status={pretty(item.request_status)} /></td><td>{item.review_note||'—'}</td></tr>)}
          </tbody></table>{!loading&&!requests.length&&<PeopleEmptyState title={ui("people.selfService.noLeaveRequests")} />}</div>
          <PeoplePagination limit={leavePageLimit} offset={leaveOffset} total={leaveTotal} onPageChange={setLeaveOffset} onLimitChange={limit=>{setLeavePageLimit(limit);setLeaveOffset(0)}} />
        </>}
        {tab==='balances'&&<>
          <p className="small">{ui("people.selfService.balanceHelp")}</p>
          <div className="table-wrap"><table><thead><tr><th>Leave type</th><th>{ui("people.selfService.paid")}</th><th>{ui("people.selfService.balanceDays")}</th></tr></thead><tbody>
            {balances.map((item:any)=><tr key={item.leave_type_code}><td>{item.display_name}</td><td><PeopleStatusBadge status={item.paid?'Paid':'Unpaid'} /></td><td><b>{Number(item.balance_days).toFixed(2)}</b></td></tr>)}
          </tbody></table>{!loading&&!balances.length&&<PeopleEmptyState title={ui("people.selfService.noBalances")} />}</div>
        </>}
        {tab==='payslips'&&<>
          <h2>{ui("people.selfService.payslipsTitle")}</h2>
          <p className="small">{ui("people.selfService.payslipsHelp")}</p>
          <div className="cards no-print">{payslips.map((item:any)=><article className="card" key={item.id}>
            <div className="small">{item.snapshot?.employeeNumber||profile?.employee_number}</div>
            <h2 style={{marginTop:6}}>{item.snapshot?.period?.name||ui('people.selfService.payrollStatement')}</h2>
            <div className="label">{String(item.snapshot?.period?.startDate||'').slice(0,10)} – {String(item.snapshot?.period?.endDate||'').slice(0,10)}</div>
            <div className="metric">{new Intl.NumberFormat(locale==='english'?'en-IN':'hi-IN',{style:'currency',currency:String(item.snapshot?.currency||'INR').trim(),maximumFractionDigits:2}).format(Number(item.snapshot?.net||0))}</div>
            <div className="small">Issued {formatUserTimestamp(item.issued_at)}</div>
            <button type="button" className="primary" style={{marginTop:12}} onClick={()=>setSelectedPayslip(item)}>{ui("people.selfService.viewPayslip")}</button>
          </article>)}</div>
          <PeoplePagination limit={payslipPageLimit} offset={payslipOffset} total={payslipTotal} onPageChange={setPayslipOffset} onLimitChange={limit=>{setPayslipPageLimit(limit);setPayslipOffset(0)}} />
          {!loading&&!payslips.length&&<PeopleEmptyState title={ui("people.selfService.noPayslips")} />}
          {selectedPayslip&&<section className="panel payslip-print" aria-label="Selected payslip">
            <div className="no-print actions" style={{justifyContent:'space-between',marginTop:0,marginBottom:18}}>
              <button type="button" className="secondary" onClick={()=>setSelectedPayslip(null)}>{ui("people.selfService.backToList")}</button>
              <button type="button" className="primary" onClick={()=>{const cleanup=()=>document.body.classList.remove('printing-payslip');document.body.classList.add('printing-payslip');window.addEventListener('afterprint',cleanup,{once:true});window.print()}}>{ui("people.selfService.printSavePdf")}</button>
            </div>
            <div className="eyebrow">QUANTAEDGE · {ui("people.selfService.payslip")}</div>
            <h2 style={{fontSize:26,marginTop:8}}>{selectedPayslip.snapshot?.period?.name||ui('people.selfService.payrollStatement')}</h2>
            <p className="small">{String(selectedPayslip.snapshot?.period?.startDate||'').slice(0,10)} – {String(selectedPayslip.snapshot?.period?.endDate||'').slice(0,10)}</p>
            <div className="cards">
              <section className="card"><div className="label">{ui("people.selfService.employee")}</div><strong>{selectedPayslip.snapshot?.employeeName}</strong><div className="small">{selectedPayslip.snapshot?.employeeNumber}</div></section>
              <section className="card"><div className="label">{ui("people.selfService.grossEarnings")}</div><div className="metric">{new Intl.NumberFormat(locale==='english'?'en-IN':'hi-IN',{style:'currency',currency:String(selectedPayslip.snapshot?.currency||'INR').trim(),maximumFractionDigits:2}).format(Number(selectedPayslip.snapshot?.gross||0))}</div></section>
              <section className="card"><div className="label">{ui("people.selfService.deductions")}</div><div className="metric">{new Intl.NumberFormat(locale==='english'?'en-IN':'hi-IN',{style:'currency',currency:String(selectedPayslip.snapshot?.currency||'INR').trim(),maximumFractionDigits:2}).format(Number(selectedPayslip.snapshot?.deductions||0))}</div></section>
            </div>
            <h2>{ui("people.selfService.salaryComponents")}</h2>
            <div className="table-wrap"><table><thead><tr><th>{ui("people.selfService.component")}</th><th>{ui("people.selfService.type")}</th><th>{ui("people.selfService.amount")}</th></tr></thead><tbody>
              {(Array.isArray(selectedPayslip.snapshot?.components)?selectedPayslip.snapshot.components:[]).map((component:any,index:number)=><tr key={component.componentCode||index}><td>{component.componentName||component.componentCode}</td><td>{pretty(component.componentType)}</td><td>{new Intl.NumberFormat(locale==='english'?'en-IN':'hi-IN',{style:'currency',currency:String(component.currency||selectedPayslip.snapshot?.currency||'INR').trim(),maximumFractionDigits:2}).format(Number(component.monthlyAmount||0))}</td></tr>)}
            </tbody></table></div>
            <div className="card" style={{display:'flex',justifyContent:'space-between',alignItems:'center',gap:12,marginTop:18}}><strong>{ui("people.selfService.netPay")}</strong><div className="metric">{new Intl.NumberFormat(locale==='english'?'en-IN':'hi-IN',{style:'currency',currency:String(selectedPayslip.snapshot?.currency||'INR').trim(),maximumFractionDigits:2}).format(Number(selectedPayslip.snapshot?.net||0))}</div></div>
            <p className="small">{ui("people.selfService.issuedAt",{date:formatUserTimestamp(selectedPayslip.issued_at)})}</p>
            <p className="small">{ui('people.selfService.snapshotHash',{checksum:selectedPayslip.checksum})}</p>
            <p className="small">{ui("people.selfService.immutablePayslipNote")}</p>
          </section>}
        </>}
        {loading&&<p className="small" role="status" aria-live="polite">{ui("people.selfService.loading")}</p>}
      </section>}
      {unlinked&&<section className="panel"><PeopleEmptyState title={ui("people.selfService.unlinkedTitle")} description={ui("people.selfService.unlinkedDescription")} /></section>}
      <p className="small" style={{marginTop:18}}>{ui("people.selfService.workspaceScope")}</p>
      {!unlinked&&session&&!profile&&!loading&&<p className="small">{ui("people.selfService.contactAdmin")}</p>}
    </main>
  </div>;
}
