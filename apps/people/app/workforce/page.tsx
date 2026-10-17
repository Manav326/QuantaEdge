'use client';
import { PeoplePageHeader, PeopleStatusBadge, PeopleFeedback, PeopleEmptyState, PeoplePagination } from '../components/PeopleUI';
import { useUiTranslation } from '../i18n/UiLocaleProvider';

import {useCallback,useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type Session={staffId?:number;role?:string;displayName?:string;permissions?:string[]};
type Tab='recruitment'|'performance'|'assets'|'exits';
async function api(path:string,init:RequestInit={}) {
  const headers=new Headers(init.headers); headers.set('X-QE-Workspace','HRMS');
  if(init.body&&!headers.has('Content-Type'))headers.set('Content-Type','application/json');
  const response=await fetch(path,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text(); let data:any={}; try{data=raw?JSON.parse(raw):{}}catch{data={message:raw}}
  if(!response.ok)throw new Error(data.message||data.detail||data.error||`Request failed (${response.status})`);
  return data;
}
const today=()=>new Date().toISOString().slice(0,10);
const pretty=(s:any)=>String(s??'—').replaceAll('_',' ').toLowerCase().replace(/\b\w/g,c=>c.toUpperCase());
export default function ExtendedWorkforcePage(){
 const router=useRouter();
 const {t}=useUiTranslation();
 const [session,setSession]=useState<Session|null>(null),[org,setOrg]=useState<any>(null),[tab,setTab]=useState<Tab>('recruitment');
 const [vacancies,setVacancies]=useState<any[]>([]),[candidates,setCandidates]=useState<any[]>([]),[cycles,setCycles]=useState<any[]>([]),[reviews,setReviews]=useState<any[]>([]),[assets,setAssets]=useState<any[]>([]),[exits,setExits]=useState<any[]>([]),[employees,setEmployees]=useState<any[]>([]);
 const [pageParams,setPageParams]=useState<Record<string,{limit:number;offset:number}>>({
   vacancies:{limit:20,offset:0},candidates:{limit:20,offset:0},cycles:{limit:20,offset:0},
   reviews:{limit:20,offset:0},assets:{limit:20,offset:0},exits:{limit:20,offset:0}
 });
 const [pageTotals,setPageTotals]=useState<Record<string,number>>({vacancies:0,candidates:0,cycles:0,reviews:0,assets:0,exits:0});
 const updatePage=(key:string,limit:number,offset:number)=>setPageParams(p=>({...p,[key]:{limit,offset}}));
 const [loading,setLoading]=useState(true),[saving,setSaving]=useState(false),[error,setError]=useState(''),[notice,setNotice]=useState('');
 const [candidateToConvert,setCandidateToConvert]=useState<any|null>(null),[convertDate,setConvertDate]=useState(today()),[convertType,setConvertType]=useState('FULL_TIME');
 const [selectedExitId,setSelectedExitId]=useState<number|null>(null),[checklistRows,setChecklistRows]=useState<any[]>([]),[approvedExitDates,setApprovedExitDates]=useState<Record<number,string>>({});
 const [vacancy,setVacancy]=useState({title:'',description:'',openings:'1'}),[candidate,setCandidate]=useState({fullName:'',email:'',phoneE164:'',vacancyId:'',notes:''});
 const [cycle,setCycle]=useState({name:'',startDate:today(),endDate:today(),dueDate:''}),[review,setReview]=useState({cycleId:'',employeeId:'',reviewerEmployeeId:''});
 const [asset,setAsset]=useState({assetTag:'',assetType:'',description:'',serialNumber:''}),[assign,setAssign]=useState({assetId:'',employeeId:'',condition:'GOOD',note:'',expectedReturnDate:''});
 const [exit,setExit]=useState({employeeId:'',exitType:'RESIGNATION',requestedLastWorkingDate:today(),noticePeriodDays:'30',reason:''});
 const exitNextStatus:Record<string,string>={REQUESTED:'UNDER_REVIEW',UNDER_REVIEW:'APPROVED',APPROVED:'CLEARANCE',CLEARANCE:'READY_FOR_SETTLEMENT'};
 const can=(p:string)=>session?.role==='ADMIN'||session?.permissions?.includes(p);
 const load=useCallback(async()=>{
  setLoading(true);setError('');
  try{
   const me=await api('/api/v1/auth/me'); if(!me.staffId){router.replace('/login');return} setSession(me);
   const organization=await api('/api/v1/hr/organization');setOrg(organization);
   const permissionByTab:Record<Tab,string>={recruitment:'HR_RECRUITMENT_VIEW',performance:'HR_PERFORMANCE_VIEW',assets:'HR_ASSET_VIEW',exits:'HR_EXIT_VIEW'};
   const activeTab=tab;
   if(me.role!=='ADMIN'&&!me.permissions?.includes(permissionByTab[activeTab])){setError(t('people.workforce.permissionMissing'));return}
   if(activeTab==='recruitment'){
    const vp=pageParams.vacancies,cp=pageParams.candidates;
    const [v,c]=await Promise.all([
      api('/api/v1/hr/recruitment/vacancies?organizationId='+organization.id+'&limit='+vp.limit+'&offset='+vp.offset),
      api('/api/v1/hr/recruitment/candidates?organizationId='+organization.id+'&limit='+cp.limit+'&offset='+cp.offset)
    ]);
    setVacancies(v.items||[]);setCandidates(c.items||[]);
    setPageTotals(p=>({...p,vacancies:Number(v.total||0),candidates:Number(c.total||0)}));
   } else if(activeTab==='performance'){
    const yp=pageParams.cycles,rp=pageParams.reviews;
    const [cy,r,emp]=await Promise.all([
      api('/api/v1/hr/performance/cycles?organizationId='+organization.id+'&limit='+yp.limit+'&offset='+yp.offset),
      api('/api/v1/hr/performance/reviews?organizationId='+organization.id+'&limit='+rp.limit+'&offset='+rp.offset),
      api('/api/v1/hr/employees?limit=50&offset=0&includeInactive=false')
    ]);
    setCycles(cy.items||[]);setReviews(r.items||[]);
    setPageTotals(p=>({...p,cycles:Number(cy.total||0),reviews:Number(r.total||0)}));
    setEmployees((emp.items||[]).filter((e:any)=>e.employment_status==='ACTIVE'));
   } else if(activeTab==='assets'){
    const ap=pageParams.assets;
    const [a,emp]=await Promise.all([
      api('/api/v1/hr/assets?organizationId='+organization.id+'&limit='+ap.limit+'&offset='+ap.offset),
      api('/api/v1/hr/employees?limit=50&offset=0&includeInactive=false')
    ]);
    setAssets(a.items||[]);setPageTotals(p=>({...p,assets:Number(a.total||0)}));
    setEmployees((emp.items||[]).filter((e:any)=>e.employment_status==='ACTIVE'));
   } else {
    const ep=pageParams.exits;
    const [x,emp]=await Promise.all([
      api('/api/v1/hr/exits?organizationId='+organization.id+'&limit='+ep.limit+'&offset='+ep.offset),
      api('/api/v1/hr/employees?limit=50&offset=0&includeInactive=false')
    ]);
    setExits(x.items||[]);setPageTotals(p=>({...p,exits:Number(x.total||0)}));
    setEmployees((emp.items||[]).filter((e:any)=>['ACTIVE','ON_LEAVE','NOTICE_PERIOD'].includes(e.employment_status)));
   }
  }catch(e){setError(e instanceof Error?e.message:t('people.workforce.loadFailed'));if(String(e).includes('Authentication required')||String(e).includes('UNAUTHENTICATED'))router.replace('/login')}
  finally{setLoading(false)}
 },[router,tab,pageParams]);
 useEffect(()=>{void load()},[load]);
 async function mutate(path:string,body:any,method='POST'){
  setSaving(true);setError('');setNotice('');
  try{await api(path,{method,body:JSON.stringify(body)});setNotice(t('people.workforce.saved'));await load()}
  catch(e){setError(e instanceof Error?e.message:t('people.workforce.saveFailed'))}
  finally{setSaving(false)}
 }
 const employeeName=(id:any)=>employees.find(e=>String(e.id)===String(id))?.display_name||'Select employee';
 async function createVacancy(e:React.FormEvent){e.preventDefault();if(!org)return;await mutate('/api/v1/hr/recruitment/vacancies',{...vacancy,organizationId:org.id,openings:Number(vacancy.openings)})}
 async function createCandidate(e:React.FormEvent){e.preventDefault();if(!org)return;await mutate('/api/v1/hr/recruitment/candidates',{...candidate,organizationId:org.id,vacancyId:candidate.vacancyId||null})}
 async function createCycle(e:React.FormEvent){e.preventDefault();if(!org)return;await mutate('/api/v1/hr/performance/cycles',{...cycle,organizationId:org.id,dueDate:cycle.dueDate||null})}
 async function createAsset(e:React.FormEvent){e.preventDefault();if(!org)return;await mutate('/api/v1/hr/assets',{...asset,organizationId:org.id})}
 async function startExit(e:React.FormEvent){e.preventDefault();await mutate('/api/v1/hr/exits',{...exit,employeeId:Number(exit.employeeId),noticePeriodDays:Number(exit.noticePeriodDays)})}
 async function openChecklist(id:number){setError('');setSelectedExitId(id);try{setChecklistRows(await api('/api/v1/hr/exits/'+id+'/checklist'))}catch(e){setError(e instanceof Error?e.message:t('people.workforce.exitChecklistLoadFailed'))}}
 async function updateChecklist(itemId:number,status:string){setSaving(true);setError('');setNotice('');try{await api('/api/v1/hr/exits/'+selectedExitId+'/checklist/'+itemId,{method:'PATCH',body:JSON.stringify({status,notes:'Updated by authorised HR reviewer'})});setChecklistRows(await api('/api/v1/hr/exits/'+selectedExitId+'/checklist'));setNotice('Checklist item updated.')}catch(e){setError(e instanceof Error?e.message:t('people.workforce.checklistUpdateFailed'))}finally{setSaving(false)}}
 async function convertCandidate(e:React.FormEvent){e.preventDefault();if(!candidateToConvert)return;await mutate('/api/v1/hr/recruitment/candidates/'+candidateToConvert.id+'/convert',{joiningDate:convertDate,employmentTypeCode:convertType,reason:'Approved recruitment conversion'});setCandidateToConvert(null)}
 async function logout(){try{await api('/api/v1/auth/logout',{method:'POST'})}finally{router.replace('/login')}}
 return <div className="shell">
  
  <main className="main"><PeoplePageHeader eyebrow={t("people.workforce.eyebrow")} title={t("people.workforce.title")} description={t("people.workforce.description")} />
   {error&&<PeopleFeedback tone="danger" role="alert">{error}</PeopleFeedback>}{notice&&<PeopleFeedback tone="success" role="status">{notice}</PeopleFeedback>}
   <section className="panel"><div className="tabs" role="group" aria-label={t("people.workforce.sections")}>
    {(['recruitment','performance','assets','exits'] as Tab[]).map(t=><button key={t} type="button" className={tab===t?'active':''} aria-pressed={tab===t} onClick={()=>{setTab(t);setError('');setNotice('')}}>{pretty(t)}</button>)}
   </div>
   {loading?<div className="empty" role="status">Loading authorised HR records…</div>:<>
    {tab==='recruitment'&&<><div className="cards"><section className="card"><div className="label">Vacancies</div><div className="metric">{vacancies.length}</div><div className="small">Draft and published roles</div></section><section className="card"><div className="label">Candidates</div><div className="metric">{candidates.length}</div><div className="small">Applications in the pipeline</div></section><section className="card"><div className="label">Offers approved</div><div className="metric">{candidates.filter(c=>c.hiring_decision==='APPROVED'&&!c.converted_employee_id).length}</div><div className="small">Ready for canonical onboarding</div></section></div>
     {can('HR_RECRUITMENT_MANAGE')&&<div className="cards"><form className="card" onSubmit={createVacancy}><h2>New vacancy</h2><label>Job title<input className="input" required maxLength={160} value={vacancy.title} onChange={e=>setVacancy({...vacancy,title:e.target.value})}/></label><label>Job description<textarea className="input" required rows={3} value={vacancy.description} onChange={e=>setVacancy({...vacancy,description:e.target.value})}/></label><label>Openings<input className="input" type="number" min="1" max="10000" value={vacancy.openings} onChange={e=>setVacancy({...vacancy,openings:e.target.value})}/></label><button className="primary" disabled={saving}>Create vacancy</button></form>
     <form className="card" onSubmit={createCandidate}><h2>Record candidate</h2><label>Full name<input className="input" required value={candidate.fullName} onChange={e=>setCandidate({...candidate,fullName:e.target.value})}/></label><label>Email<input className="input" type="email" value={candidate.email} onChange={e=>setCandidate({...candidate,email:e.target.value})}/></label><label>Phone<input className="input" value={candidate.phoneE164} onChange={e=>setCandidate({...candidate,phoneE164:e.target.value})}/></label><label>Vacancy<select className="input" value={candidate.vacancyId} onChange={e=>setCandidate({...candidate,vacancyId:e.target.value})}><option value="">General application</option>{vacancies.filter(v=>v.vacancy_status==='OPEN').map(v=><option key={v.id} value={v.id}>{v.title}</option>)}</select></label><label>Notes<textarea className="input" rows={2} value={candidate.notes} onChange={e=>setCandidate({...candidate,notes:e.target.value})}/></label><button className="primary" disabled={saving}>Add candidate</button></form></div>}
     <div className="table-wrap"><table><thead><tr><th>Vacancy</th><th>Department</th><th>Openings</th><th>{t('people.workforce.status')}</th>{can('HR_RECRUITMENT_MANAGE')&&<th>Workflow</th>}</tr></thead><tbody>{vacancies.map(v=><tr key={v.id}><td><strong>{v.title}</strong><div className="small">{v.description}</div></td><td>{v.department_id||'Unassigned'}</td><td>{v.openings}</td><td><PeopleStatusBadge status={pretty(v.vacancy_status)} /></td>{can('HR_RECRUITMENT_MANAGE')&&<td>{v.vacancy_status==='DRAFT'&&<button className="secondary" disabled={saving} onClick={()=>void mutate('/api/v1/hr/recruitment/vacancies/'+v.id+'/status',{status:'OPEN'})}>Publish</button>}{v.vacancy_status==='OPEN'&&<button className="secondary" disabled={saving} onClick={()=>void mutate('/api/v1/hr/recruitment/vacancies/'+v.id+'/status',{status:'CLOSED'})}>Close</button>}</td>}</tr>)}</tbody></table>{!vacancies.length&&<PeopleEmptyState title={t("people.workforce.noVacancies")} />}</div><PeoplePagination limit={pageParams.vacancies.limit} offset={pageParams.vacancies.offset} total={pageTotals.vacancies} onPageChange={offset=>updatePage('vacancies',pageParams.vacancies.limit,offset)} onLimitChange={limit=>updatePage('vacancies',limit,0)} />
     {candidateToConvert&&can('HR_RECRUITMENT_MANAGE')&&<form className="card" onSubmit={convertCandidate}><h2>Onboard {candidateToConvert.full_name}</h2><p className="small">This creates the canonical employee record. No login or extra permissions are created.</p><div className="cards"><label>Joining date<input className="input" type="date" required value={convertDate} onChange={e=>setConvertDate(e.target.value)}/></label><label>Employment type<select className="input" value={convertType} onChange={e=>setConvertType(e.target.value)}>{['FULL_TIME','PART_TIME','FIXED_TERM','VISITING_FACULTY','DAILY_WAGE','INTERN','CONSULTANT'].map(x=><option key={x} value={x}>{pretty(x)}</option>)}</select></label></div><button className="primary" disabled={saving}>Create employee record</button><button className="secondary" type="button" onClick={()=>setCandidateToConvert(null)}>Cancel</button></form>}<div className="table-wrap"><table><thead><tr><th>Candidate</th><th>Vacancy</th><th>Stage</th><th>Hiring decision</th><th>Onboarding</th>{can('HR_RECRUITMENT_MANAGE')&&<th>{t('people.workforce.actions')}</th>}</tr></thead><tbody>{candidates.map(c=><tr key={c.id}><td><strong>{c.full_name}</strong><div className="small">{c.email||c.phone_e164}</div></td><td>{c.vacancy_title||t('people.workforce.generalApplication')}</td><td><PeopleStatusBadge status={pretty(c.pipeline_stage)} /></td><td><PeopleStatusBadge status={pretty(c.hiring_decision)} /></td><td>{c.converted_employee_id?'Employee #'+c.converted_employee_id:t('people.workforce.notConverted')}</td>{can('HR_RECRUITMENT_MANAGE')&&<td style={{display:'flex',gap:6,flexWrap:'wrap'}}>{c.pipeline_stage==='APPLIED'&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/recruitment/candidates/'+c.id+'/stage',{stage:'SCREENING'},'PATCH')}>Screen</button>}{['APPLIED','SCREENING'].includes(c.pipeline_stage)&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/recruitment/candidates/'+c.id+'/stage',{stage:'INTERVIEW'},'PATCH')}>Interview</button>}{c.hiring_decision==='PENDING'&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/recruitment/candidates/'+c.id+'/evaluations',{evaluationType:'HIRING_DECISION',outcome:'APPROVED',notes:'Hiring decision recorded'})}>{t('people.workforce.approveOffer')}</button>}{c.hiring_decision==='APPROVED'&&!c.converted_employee_id&&<button className="secondary" onClick={()=>{setCandidateToConvert(c);setConvertDate(today());setConvertType('FULL_TIME')}}>Complete onboarding</button>}</td>}</tr>)}</tbody></table>{!candidates.length&&<PeopleEmptyState title={t("people.workforce.noCandidates")} />}</div><PeoplePagination limit={pageParams.candidates.limit} offset={pageParams.candidates.offset} total={pageTotals.candidates} onPageChange={offset=>updatePage('candidates',pageParams.candidates.limit,offset)} onLimitChange={limit=>updatePage('candidates',limit,0)} />
    </>}
    {tab==='performance'&&<>{can('HR_PERFORMANCE_MANAGE')&&<div className="cards"><form className="card" onSubmit={createCycle}><h2>Review cycle</h2><label>Cycle name<input className="input" required value={cycle.name} onChange={e=>setCycle({...cycle,name:e.target.value})}/></label><label>Start date<input className="input" type="date" required value={cycle.startDate} onChange={e=>setCycle({...cycle,startDate:e.target.value})}/></label><label>End date<input className="input" type="date" required value={cycle.endDate} onChange={e=>setCycle({...cycle,endDate:e.target.value})}/></label><label>Due date<input className="input" type="date" value={cycle.dueDate} onChange={e=>setCycle({...cycle,dueDate:e.target.value})}/></label><button className="primary" disabled={saving}>Create cycle</button></form><form className="card" onSubmit={e=>{e.preventDefault();void mutate('/api/v1/hr/performance/reviews',{cycleId:Number(review.cycleId),employeeId:Number(review.employeeId),reviewerEmployeeId:review.reviewerEmployeeId?Number(review.reviewerEmployeeId):null})}}><h2>Assign review</h2><label>Cycle<select className="input" required value={review.cycleId} onChange={e=>setReview({...review,cycleId:e.target.value})}><option value="">Choose cycle</option>{cycles.filter(c=>c.cycle_status==='OPEN').map(c=><option key={c.id} value={c.id}>{c.name}</option>)}</select></label><label>Employee<select className="input" required value={review.employeeId} onChange={e=>setReview({...review,employeeId:e.target.value})}><option value="">Choose employee</option>{employees.map(e=><option key={e.id} value={e.id}>{e.employee_number} · {e.display_name}</option>)}</select></label><label>Reviewer<select className="input" value={review.reviewerEmployeeId} onChange={e=>setReview({...review,reviewerEmployeeId:e.target.value})}><option value="">Not assigned</option>{employees.map(e=><option key={e.id} value={e.id}>{e.display_name}</option>)}</select></label><button className="primary" disabled={saving}>Assign review</button></form></div>}
     <div className="table-wrap"><table><thead><tr><th>Cycle</th><th>Period</th><th>{t('people.workforce.status')}</th>{can('HR_PERFORMANCE_MANAGE')&&<th>Action</th>}</tr></thead><tbody>{cycles.map(c=><tr key={c.id}><td>{c.name}</td><td>{c.start_date} – {c.end_date}</td><td><PeopleStatusBadge status={pretty(c.cycle_status)} /></td>{can('HR_PERFORMANCE_MANAGE')&&<td>{c.cycle_status==='DRAFT'&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/performance/cycles/'+c.id+'/status',{status:'OPEN'},'PATCH')}>Open</button>}{c.cycle_status==='OPEN'&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/performance/cycles/'+c.id+'/status',{status:'IN_REVIEW'},'PATCH')}>Review phase</button>}</td>}</tr>)}</tbody></table>{!cycles.length&&<PeopleEmptyState title={t("people.workforce.noReviewCycles")} />}</div><PeoplePagination limit={pageParams.cycles.limit} offset={pageParams.cycles.offset} total={pageTotals.cycles} onPageChange={offset=>updatePage('cycles',pageParams.cycles.limit,offset)} onLimitChange={limit=>updatePage('cycles',limit,0)} />
     <div className="table-wrap"><table><thead><tr><th>Employee</th><th>Cycle</th><th>Reviewer</th><th>{t('people.workforce.status')}</th><th>Rating</th>{can('HR_PERFORMANCE_MANAGE')&&<th>Workflow</th>}</tr></thead><tbody>{reviews.map(r=><tr key={r.id}><td>{r.employee_name} <span className="small">{r.employee_number}</span></td><td>{r.cycle_name}</td><td>{r.reviewer_employee_id||'Unassigned'}</td><td><PeopleStatusBadge status={pretty(r.review_status)} /></td><td>{r.rating??'—'}</td>{can('HR_PERFORMANCE_MANAGE')&&<td>{r.review_status==='DRAFT'&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/performance/reviews/'+r.id,{action:'SUBMIT',selfAssessment:JSON.stringify({achievements:t('people.workforce.assessmentSubmitted'),development:t('people.workforce.reviewRequired')})},'PATCH')}>{t('people.workforce.submitAssessment')}</button>}{r.review_status==='SUBMITTED'&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/performance/reviews/'+r.id,{action:'APPROVE',managerAssessment:JSON.stringify({summary:'Reviewed'}) ,rating:3},'PATCH')}>Approve review</button>}</td>}</tr>)}</tbody></table>{!reviews.length&&<PeopleEmptyState title={t("people.workforce.noPerformanceReviews")} />}</div><PeoplePagination limit={pageParams.reviews.limit} offset={pageParams.reviews.offset} total={pageTotals.reviews} onPageChange={offset=>updatePage('reviews',pageParams.reviews.limit,offset)} onLimitChange={limit=>updatePage('reviews',limit,0)} />
    </>}
    {tab==='assets'&&<>{can('HR_ASSET_MANAGE')&&<div className="cards"><form className="card" onSubmit={createAsset}><h2>Add inventory asset</h2><label>Asset tag<input className="input" required value={asset.assetTag} onChange={e=>setAsset({...asset,assetTag:e.target.value})}/></label><label>Type<input className="input" required value={asset.assetType} onChange={e=>setAsset({...asset,assetType:e.target.value})}/></label><label>Description<input className="input" value={asset.description} onChange={e=>setAsset({...asset,description:e.target.value})}/></label><label>Serial number<input className="input" value={asset.serialNumber} onChange={e=>setAsset({...asset,serialNumber:e.target.value})}/></label><button className="primary" disabled={saving}>Add asset</button></form><form className="card" onSubmit={e=>{e.preventDefault();void mutate('/api/v1/hr/assets/'+assign.assetId+'/assign',{employeeId:Number(assign.employeeId),condition:assign.condition,note:assign.note,expectedReturnDate:assign.expectedReturnDate||null})}}><h2>Assign available asset</h2><label>Asset<select className="input" required value={assign.assetId} onChange={e=>setAssign({...assign,assetId:e.target.value})}><option value="">Choose asset</option>{assets.filter(a=>a.asset_status==='AVAILABLE').map(a=><option key={a.id} value={a.id}>{a.asset_tag} · {a.asset_type}</option>)}</select></label><label>Employee<select className="input" required value={assign.employeeId} onChange={e=>setAssign({...assign,employeeId:e.target.value})}><option value="">Choose employee</option>{employees.map(e=><option key={e.id} value={e.id}>{e.employee_number} · {e.display_name}</option>)}</select></label><label>Expected return date<input className="input" type="date" value={assign.expectedReturnDate} onChange={e=>setAssign({...assign,expectedReturnDate:e.target.value})}/></label><label>Condition<select className="input" value={assign.condition} onChange={e=>setAssign({...assign,condition:e.target.value})}>{['NEW','GOOD','FAIR','DAMAGED','UNKNOWN'].map(x=><option key={x}>{x}</option>)}</select></label><button className="primary" disabled={saving||!assign.assetId||!assign.employeeId}>Assign asset</button></form></div>}
     <div className="table-wrap"><table><thead><tr><th>Asset</th><th>Type / serial</th><th>Condition</th><th>{t('people.workforce.status')}</th><th>Current custodian</th>{can('HR_ASSET_MANAGE')&&<th>Action</th>}</tr></thead><tbody>{assets.map(a=><tr key={a.id}><td>{a.asset_tag}</td><td>{a.asset_type}<div className="small">{a.serial_number||t('people.workforce.noSerialRecorded')}</div></td><td><PeopleStatusBadge status={pretty(a.condition_status)} /></td><td><PeopleStatusBadge status={pretty(a.asset_status)} />{a.overdue&&<div className="error">Overdue · due {a.expected_return_date}</div>}</td><td>{a.employee_name||'—'}{a.expected_return_date&&<div className="small">Due {a.expected_return_date}</div>}</td>{can('HR_ASSET_MANAGE')&&<td style={{display:'flex',gap:5,flexWrap:'wrap'}}>{a.assignment_id&&<button className="secondary" onClick={()=>void mutate('/api/v1/hr/assets/assignments/'+a.assignment_id+'/return',{condition:'GOOD',note:t('people.workforce.returnedInspected')})}>Record return</button>}{!a.assignment_id&&a.asset_status!=='RETIRED'&&<><button className="secondary" onClick={()=>void mutate('/api/v1/hr/assets/'+a.id+'/status',{status:'MISSING',reason:t('people.workforce.assetMissing')},'PATCH')}>Mark missing</button><button className="secondary" onClick={()=>void mutate('/api/v1/hr/assets/'+a.id+'/status',{status:'RETIRED',reason:t('people.workforce.assetRetired')},'PATCH')}>Retire</button></>}</td>}</tr>)}</tbody></table>{!assets.length&&<PeopleEmptyState title={t("people.workforce.noInventoryAssets")} />}</div><PeoplePagination limit={pageParams.assets.limit} offset={pageParams.assets.offset} total={pageTotals.assets} onPageChange={offset=>updatePage('assets',pageParams.assets.limit,offset)} onLimitChange={limit=>updatePage('assets',limit,0)} />
    </>}
    {tab === 'exits' && (
      <>
        {can('HR_EXIT_MANAGE') && (
          <form className="card" onSubmit={startExit}>
            <h2>Initiate exit</h2>
            <div className="cards">
              <label>Employee
                <select className="input" required value={exit.employeeId} onChange={e => setExit({...exit, employeeId: e.target.value})}>
                  <option value="">Choose employee</option>
                  {employees.map(employee => <option key={employee.id} value={employee.id}>{employee.employee_number} · {employee.display_name}</option>)}
                </select>
              </label>
              <label>Exit type
                <select className="input" value={exit.exitType} onChange={e => setExit({...exit, exitType: e.target.value})}>
                  {['RESIGNATION', 'RETIREMENT', 'END_OF_CONTRACT', 'TERMINATION', 'OTHER'].map(type => <option key={type} value={type}>{pretty(type)}</option>)}
                </select>
              </label>
              <label>Requested last working date
                <input className="input" type="date" required min={today()} value={exit.requestedLastWorkingDate} onChange={e => setExit({...exit, requestedLastWorkingDate: e.target.value})} />
              </label>
              <label>Notice period (days)
                <input className="input" type="number" min="0" max="3650" value={exit.noticePeriodDays} onChange={e => setExit({...exit, noticePeriodDays: e.target.value})} />
              </label>
              <label>Reason
                <input className="input" value={exit.reason} onChange={e => setExit({...exit, reason: e.target.value})} />
              </label>
            </div>
            <button className="primary" disabled={saving || !exit.employeeId}>Start exit workflow</button>
          </form>
        )}
        <div className="table-wrap">
          <table>
            <thead>
              <tr><th>Employee</th><th>Exit type</th><th>Requested last day</th><th>Approved last day</th><th>Workflow</th><th>Final settlement</th>{can('HR_EXIT_MANAGE') && <th>Next action</th>}</tr>
            </thead>
            <tbody>
              {exits.map(item => (
                <tr key={item.id}>
                  <td>{item.display_name}<div className="small">{item.employee_number}</div></td>
                  <td>{pretty(item.exit_type)}</td>
                  <td>{item.requested_last_working_date}</td>
                  <td>{item.approved_last_working_date || t('people.workforce.notApproved')}</td>
                  <td><PeopleStatusBadge status={pretty(item.exit_status)} /></td>
                  <td><PeopleStatusBadge status={pretty(item.final_settlement_status)} /></td>
                  {can('HR_EXIT_MANAGE') && (
                    <td style={{display: 'flex', gap: 6, flexWrap: 'wrap'}}>
                      <button className="secondary" type="button" onClick={() => void openChecklist(item.id)}>Checklist</button>
                      {item.exit_status === 'UNDER_REVIEW' && (
                        <label className="small">Approved last working date
                          <input className="input" type="date" required min={today()} value={approvedExitDates[item.id] ?? String(item.requested_last_working_date || '').slice(0,10)} onChange={e => setApprovedExitDates(v => ({...v, [item.id]: e.target.value}))} />
                        </label>
                      )}
                      {exitNextStatus[item.exit_status] && (
                        <button className="secondary" type="button" disabled={item.exit_status === 'UNDER_REVIEW' && !(approvedExitDates[item.id] ?? String(item.requested_last_working_date || '').slice(0,10))} onClick={() => {
                          const approvedLastWorkingDate = item.exit_status === 'UNDER_REVIEW'
                            ? (approvedExitDates[item.id] ?? String(item.requested_last_working_date || '').slice(0,10))
                            : undefined;
                          void mutate('/api/v1/hr/exits/' + item.id + '/status', {
                            status: exitNextStatus[item.exit_status],
                            notes: t('people.workforce.reviewedInPeopleOps'),
                            ...(approvedLastWorkingDate ? {approvedLastWorkingDate} : {})
                          }, 'PATCH');
                        }}>Advance workflow</button>
                      )}
                      {item.exit_status === 'READY_FOR_SETTLEMENT' && item.final_settlement_status !== 'SETTLED' && (
                        <button className="secondary" type="button" onClick={() => void mutate('/api/v1/hr/exits/' + item.id + '/settlement', {status: 'SETTLED', reason: t('people.workforce.settlementConfirmed')}, 'PATCH')}>Record settlement</button>
                      )}
                      {item.exit_status === 'READY_FOR_SETTLEMENT' && item.final_settlement_status === 'SETTLED' && (
                        <button className="secondary" type="button" onClick={() => void mutate('/api/v1/hr/exits/' + item.id + '/status', {status: 'COMPLETED'}, 'PATCH')}>Complete exit</button>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          {!exits.length && <PeopleEmptyState title={t("people.workforce.noExitWorkflows")} />}
        <PeoplePagination limit={pageParams.exits.limit} offset={pageParams.exits.offset} total={pageTotals.exits} onPageChange={offset=>updatePage('exits',pageParams.exits.limit,offset)} onLimitChange={limit=>updatePage('exits',limit,0)} />
        </div>
        {selectedExitId !== null && (
          <div className="table-wrap">
            <h2>Exit checklist · #{selectedExitId}</h2>
            <table>
              <thead><tr><th>Item</th><th>Owner</th><th>{t('people.workforce.status')}</th><th>Action</th></tr></thead>
              <tbody>
                {checklistRows.map(item => (
                  <tr key={item.id}>
                    <td>{item.title}<div className="small">{item.notes || ''}</div></td>
                    <td>{pretty(item.owner_role)}</td>
                    <td><PeopleStatusBadge status={pretty(item.item_status)} /></td>
                    <td>
                      {!['CLEARED', 'WAIVED'].includes(item.item_status) && (
                        <div style={{display: 'flex', gap: 6}}>
                          <button className="secondary" type="button" disabled={saving} onClick={() => void updateChecklist(item.id, 'CLEARED')}>Clear</button>
                          <button className="secondary" type="button" disabled={saving} onClick={() => void updateChecklist(item.id, 'WAIVED')}>Waive</button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!checklistRows.length && <PeopleEmptyState title={t("people.workforce.noChecklistItems")} />}
          </div>
        )}
      </>
    )}
   </>}
  </section>
  <p className="small">Sensitive HR actions are checked by the API. Candidate records and employment records are distinct until an approved candidate is explicitly converted. Exit workflows do not independently disable accounts or change employee status. No bank transfer is initiated by this workspace.</p>
  </main>
 </div>
}
