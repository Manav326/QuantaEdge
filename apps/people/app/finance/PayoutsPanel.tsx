'use client';

import { apiFetch } from '../components/apiClient';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import styles from './Finance.module.css';

type Institution={id:number;display_name:string;tenant_code:string;status:string};
type LegalEntity={id:number;entity_kind:string;legal_name:string;trade_name?:string|null;status:string};
type Catalog={institutions:Institution[];legalEntities:LegalEntity[]};
type Settings={
  configured:boolean;providerConfigured:boolean;sourceAccountConfigured:boolean;provider:string;
  default_remainder_recipient_type?:string;default_remainder_institution_id?:number|null;
  default_remainder_legal_entity_id?:number|null;default_institution_name?:string|null;
  default_company_name?:string|null;payout_frequency?:string;payout_minimum_paise?:number|null;
  refund_reserve_basis_points?:number|null
};
type Overview={
  beneficiariesAwaitingApproval:number;beneficiariesNeedingReview:number;unreconciledFees:number;
  batchesAwaitingApproval:number;payoutsInProgress:number;payoutsPaidPaise:number;
  payoutsReversedPaise:number;payoutsFailed:number;providerConfigured:boolean
};
type Beneficiary={
  id:number;public_id:string;beneficiary_type:string;institution_id?:number|null;legal_entity_id?:number|null;
  beneficiary_name:string;recipient_name:string;bank_ifsc:string;bank_account_last4:string;status:string;
  created_by_staff_id:number;approved_by_staff_id?:number|null;approved_at?:string|null;rejection_reason?:string|null
};
type FeeOrder={order_id:number;course_title:string;source_institution:string;provider_payment_id:string;
  captured_amount_paise:number;output_tax_paise:number;verified_at:string;currency:string};
type Payout={
  id:number;public_id:string;beneficiary_id:number;amount_paise:number;currency:string;status:string;
  provider_status?:string|null;provider_payout_id?:string|null;transfer_reference?:string|null;status_details?:string|null;
  recipient_name:string;beneficiary_name:string;bank_ifsc:string;bank_account_last4:string;
  beneficiary_type:string;institution_name?:string|null;company_name?:string|null;
  created_by_staff_id?:number|null;approved_by_staff_id?:number|null
};
type Batch={id:number;public_id:string;period_start:string;period_end:string;currency:string;status:string;
  beneficiary_count:number;total_amount_paise:number;created_by_staff_id:number;approved_by_staff_id?:number|null;
  approved_at?:string|null;paid_count?:number;exception_count?:number;payouts?:Payout[]};

const BASE='/api/v1/hrms/platform/finance';
async function api<T>(path:string,init:RequestInit={}):Promise<T>{
  const headers=new Headers(init.headers);
  headers.set('X-QE-Workspace','HRMS');headers.set('Accept','application/json');
  if(init.body&&!headers.has('Content-Type'))headers.set('Content-Type','application/json');
  const response=await apiFetch(path,{...init,headers,credentials:'same-origin',cache:'no-store'},{notificationMode:'caller'});
  const raw=await response.text();let payload:any={};
  try{payload=raw?JSON.parse(raw):{};}catch{payload={message:raw};}
  if(!response.ok)throw new Error(String(payload.message||payload.detail||payload.error||'Finance request failed ('+response.status+')'));
  return payload as T;
}
const money=(paise:number|undefined,currency='INR')=>typeof paise!=='number'?'—':new Intl.NumberFormat('en-IN',{style:'currency',currency,maximumFractionDigits:2}).format(paise/100);
function parsePaise(raw:string,allowZero=false):number|null{
  const value=raw.trim();
  if(!/^\\d{1,10}(?:\\.\\d{1,2})?$/.test(value))return null;
  const [rupees,decimal='']=value.split('.');
  const amount=Number(rupees)*100+Number((decimal+'00').slice(0,2));
  return Number.isSafeInteger(amount)&&(allowZero?amount>=0:amount>=100)?amount:null;
}
const label=(status:string)=>({
  PROVISIONING:'Setup in progress',PENDING_APPROVAL:'Needs approval',ACTIVE:'Approved for payouts',
  SUSPENDED:'Suspended',REJECTED:'Rejected',RECONCILIATION_REQUIRED:'Needs provider review',
  DRAFT:'Draft',APPROVED:'Approved',SUBMITTING:'Submitting',PENDING:'Waiting for provider',
  QUEUED:'Queued by provider',PROCESSING:'Transfer in progress',PROCESSED:'Paid',REVERSED:'Reversed',
  FAILED:'Failed',PARTIAL:'Partially completed',COMPLETED:'Completed',CANCELLED:'Cancelled'
}[status]||status.replaceAll('_',' ').toLowerCase());
function statusClass(status:string){
  if(['ACTIVE','PROCESSED','COMPLETED'].includes(status))return styles.payoutStatusSuccess;
  if(['FAILED','REJECTED','REVERSED','RECONCILIATION_REQUIRED'].includes(status))return styles.payoutStatusDanger;
  if(['PENDING_APPROVAL','APPROVED','PROCESSING','SUBMITTING','PENDING','QUEUED','PARTIAL'].includes(status))return styles.payoutStatusPending;
  return styles.payoutStatusNeutral;
}

export default function PayoutsPanel(){
  const [catalog,setCatalog]=useState<Catalog|null>(null);
  const [settings,setSettings]=useState<Settings|null>(null);
  const [overview,setOverview]=useState<Overview|null>(null);
  const [beneficiaries,setBeneficiaries]=useState<Beneficiary[]>([]);
  const [feeOrders,setFeeOrders]=useState<FeeOrder[]>([]);
  const [batches,setBatches]=useState<Batch[]>([]);
  const [selectedBatch,setSelectedBatch]=useState<Batch|null>(null);
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [settingsForm,setSettingsForm]=useState({remainderType:'COMPANY',remainderId:'',minimumRupees:'',reservePercent:''});
  const [beneficiaryForm,setBeneficiaryForm]=useState({type:'INSTITUTION',recipientId:'',holderName:'',accountNumber:'',ifsc:'',email:'',phone:''});
  const [rejectingId,setRejectingId]=useState<number|null>(null);
  const [rejectReason,setRejectReason]=useState('');
  const [feeDrafts,setFeeDrafts]=useState<Record<string,{fee:string;tax:string;reference:string;source:string}>>({});

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const [c,s,o,b,f,bs]=await Promise.all([
        api<Catalog>(BASE+'/payout-catalog'),api<Settings>(BASE+'/payout-settings'),api<Overview>(BASE+'/payout-overview'),
        api<Beneficiary[]>(BASE+'/payout-beneficiaries'),api<FeeOrder[]>(BASE+'/fee-reconciliation?limit=200'),
        api<Batch[]>(BASE+'/payout-batches')
      ]);
      setCatalog(c);setSettings(s);setOverview(o);setBeneficiaries(Array.isArray(b)?b:[]);
      setFeeOrders(Array.isArray(f)?f:[]);setBatches(Array.isArray(bs)?bs:[]);
      const companies=c.legalEntities.filter(e=>e.status==='ACTIVE'&&['PLATFORM','COMPANY'].includes(e.entity_kind));
      const institutions=c.institutions.filter(i=>i.status==='ACTIVE');
      setSettingsForm(old=>({
        remainderType:s.default_remainder_recipient_type||old.remainderType,
        remainderId:s.configured?String(s.default_remainder_recipient_type==='INSTITUTION'
          ?(s.default_remainder_institution_id??''):(s.default_remainder_legal_entity_id??'')):'',
        minimumRupees:s.payout_minimum_paise!=null?String(s.payout_minimum_paise/100):(s.configured?old.minimumRupees:''),
        reservePercent:s.refund_reserve_basis_points!=null?String(s.refund_reserve_basis_points/100):(s.configured?old.reservePercent:'')
      }));
      setBeneficiaryForm(old=>{
        const options=old.type==='INSTITUTION'?institutions:companies;
        return {...old,recipientId:old.recipientId&&options.some(x=>String(x.id)===old.recipientId)?old.recipientId:String(options[0]?.id??'')};
      });
    }catch(e){setError(e instanceof Error?e.message:'Payout workspace could not load.');}
    finally{setLoading(false);}
  },[]);
  useEffect(()=>{void load();},[load]);

  const action=async(work:()=>Promise<unknown>,success:string)=>{
    setBusy(true);setError('');setNotice('');
    try{const result=await work();setNotice(success);await load();return result;}
    catch(e){setError(e instanceof Error?e.message:'The payout action failed.');return null;}
    finally{setBusy(false);}
  };
  const activeInstitutions=catalog?.institutions.filter(i=>i.status==='ACTIVE')||[];
  const activeCompanies=catalog?.legalEntities.filter(e=>e.status==='ACTIVE'&&['PLATFORM','COMPANY'].includes(e.entity_kind))||[];
  const recipientOptions=beneficiaryForm.type==='INSTITUTION'
    ?activeInstitutions.map(i=>({id:i.id,label:i.display_name+' · '+i.tenant_code}))
    :activeCompanies.map(e=>({id:e.id,label:e.legal_name+' · '+e.entity_kind}));
  const payoutEligible=Boolean(settings?.configured&&settings?.providerConfigured&&settings?.sourceAccountConfigured);

  const saveSettings=async(event:FormEvent)=>{
    event.preventDefault();
    const minimum=parsePaise(settingsForm.minimumRupees);
    const reserve=Number(settingsForm.reservePercent);
    if(minimum===null){setError('Enter a minimum payout of at least ₹1.00.');return;}
    if(!Number.isFinite(reserve)||reserve<0||reserve>100){setError('Enter a refund reserve from 0% to 100%.');return;}
    if(!settingsForm.remainderId){setError('Choose who receives the remaining balance.');return;}
    await action(()=>api(BASE+'/payout-settings',{method:'PUT',body:JSON.stringify({
      defaultRemainderRecipientType:settingsForm.remainderType,
      defaultRemainderRecipientId:Number(settingsForm.remainderId),
      payoutMinimumPaise:minimum,
      refundReserveBasisPoints:Math.round(reserve*100)
    })}),'Payout settings saved. New revenue-share policies will use this remainder recipient by default.');
  };

  const submitBeneficiary=async(event:FormEvent)=>{
    event.preventDefault();
    if(!beneficiaryForm.recipientId){setError('Select the institution or company first.');return;}
    if(!beneficiaryForm.accountNumber.trim()){setError('Enter the bank account number.');return;}
    const form={...beneficiaryForm};
    const result=await action(()=>api<{id:number;status:string}>(BASE+'/payout-beneficiaries',{method:'POST',body:JSON.stringify({
      beneficiaryType:form.type,beneficiaryId:Number(form.recipientId),accountHolderName:form.holderName,
      accountNumber:form.accountNumber,ifsc:form.ifsc,email:form.email||null,phone:form.phone||null
    })}),'Bank details submitted to RazorpayX. The beneficiary still needs independent approval.');
    // Never keep the full bank account number in the client form after the request returns.
    setBeneficiaryForm(v=>({...v,accountNumber:''}));
    if(result)setNotice((result as any).message||'Beneficiary profile submitted for approval.');
  };

  const submitFees=async(order:FeeOrder)=>{
    const draft=feeDrafts[String(order.order_id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'};
    const fee=parsePaise(draft.fee,true),tax=parsePaise(draft.tax,true);
    if(fee===null||tax===null){setError('Enter the actual provider fee and fee tax in rupees. Zero is allowed; guesses are not.');return;}
    if(draft.reference.trim().length<3){setError('Enter the settlement reference from the actual provider statement.');return;}
    await action(()=>api(BASE+'/course-orders/'+order.order_id+'/processing-fees',{
      method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},
      body:JSON.stringify({processingFeePaise:fee,feeTaxPaise:tax,settlementReference:draft.reference.trim(),sourceType:draft.source})
    }),'Actual payment-processing charges recorded. Allocation adjustments are now reflected in payout balances.');
  };

  const createBatch=async()=>{await action(()=>api(BASE+'/payout-batches',{method:'POST',body:JSON.stringify({})}),
    'Monthly payout batch prepared. No bank transfers are sent until a different authorized staff member approves it.');};
  const openBatch=async(id:number)=>{
    setError('');setNotice('');
    try{const detail=await api<Batch>(BASE+'/payout-batches/'+id);setSelectedBatch(detail);}
    catch(e){setError(e instanceof Error?e.message:'Payout batch detail could not load.');}
  };
  const approveBatch=async(batch:Batch)=>{
    const result=await action(()=>api<any>(BASE+'/payout-batches/'+batch.id+'/approve',{method:'POST',body:JSON.stringify({})}),
      'Approval recorded. Review each transfer status; pending or uncertain transfers are not counted as paid.');
    if(result)await openBatch(batch.id);
  };
  const refreshPayout=async(payout:Payout)=>{
    const result=await action(()=>api<any>(BASE+'/payouts/'+payout.id+'/refresh'),
      'RazorpayX payout status refreshed.');
    if(result&&selectedBatch)await openBatch(selectedBatch.id);
  };
  const retryPayout=async(payout:Payout)=>{
    const result=await action(()=>api<any>(BASE+'/payouts/'+payout.id+'/retry-submission',{method:'POST',body:JSON.stringify({})}),
      'Safe retry requested using the existing payout idempotency key.');
    if(result&&selectedBatch)await openBatch(selectedBatch.id);
  };
  const rejectBeneficiary=async(id:number)=>{
    const reason=rejectReason.trim();
    if(reason.length<5){setError('Write a clear rejection reason (at least 5 characters).');return;}
    const result=await action(()=>api(BASE+'/payout-beneficiaries/'+id+'/reject',{method:'POST',body:JSON.stringify({reason})}),
      'Beneficiary rejected and the decision was recorded.');
    if(result){setRejectingId(null);setRejectReason('');}
  };

  return <section className={styles.stack}>
    <section className={styles.payoutsIntro}>
      <div>
        <span className={styles.eyebrow}>HRMS · FINANCE OPERATIONS</span>
        <h2>Beneficiaries & payouts</h2>
        <p>Prepare monthly transfers from the existing course-revenue ledger. Bank details are verified by RazorpayX, and a second authorized staff member must approve every beneficiary and payout batch.</p>
      </div>
      <button type="button" className={styles.secondary} onClick={()=>void load()} disabled={loading||busy}>Refresh</button>
    </section>

    {error&&<div className={styles.payoutAlertError} role="alert">{error}</div>}
    {notice&&<div className={styles.payoutAlertSuccess} role="status">{notice}</div>}
    {loading&&!catalog?<section className={styles.panel}>Loading payout settings and balances…</section>:<>
      <section className={styles.payoutMetricGrid}>
        <article className={styles.payoutMetricCard}><span>Beneficiaries awaiting review</span><strong>{overview?.beneficiariesAwaitingApproval??'—'}</strong><small>Bank profile requires a second person’s approval</small></article>
        <article className={styles.metric}><span>Payments missing fee confirmation</span><strong>{overview?.unreconciledFees??'—'}</strong><small>Payout batches stay blocked until actual fees are recorded</small></article>
        <article className={styles.metric}><span>Transfers in progress</span><strong>{overview?.payoutsInProgress??'—'}</strong><small>Not counted as paid until RazorpayX confirms completion</small></article>
        <article className={styles.metric}><span>Confirmed transfers</span><strong>{money(overview?.payoutsPaidPaise)}</strong><small>Provider-confirmed processed payouts</small></article>
      </section>

      {!settings?.providerConfigured||!settings?.sourceAccountConfigured?
        <div className={styles.payoutAlertWarning} role="status"><b>Transfers are not enabled yet.</b> Configure the RazorpayX payout API credentials and source account number on the API server. Do not paste secrets into this screen. Confirm the legal seller and collection-account arrangement with Razorpay before enabling live payouts.</div>:null}

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>1. Payout rules</h2><p>Choose who receives the remaining balance and how much must stay available for refunds.</p></div>
          <span className={styles.payoutStatusNeutral}>Monthly</span></div>
        <form className={styles.payoutFormGrid} onSubmit={saveSettings}>
          <label>Who receives the remaining balance?
            <select required value={settingsForm.remainderType} onChange={e=>setSettingsForm(v=>({...v,remainderType:e.target.value,remainderId:''}))}>
              <option value="COMPANY">Company (PureDeal or another company)</option><option value="INSTITUTION">Institution</option>
            </select>
            <small>The company/institution is an explicit recipient. If it also has a configured share, both amounts are combined into one payable balance.</small>
          </label>
          <label>Remainder recipient
            <select required value={settingsForm.remainderId} onChange={e=>setSettingsForm(v=>({...v,remainderId:e.target.value}))}>
              <option value="">Choose beneficiary</option>
              {(settingsForm.remainderType==='INSTITUTION'?activeInstitutions.map(i=>({id:i.id,label:i.display_name})):activeCompanies.map(e=>({id:e.id,label:e.legal_name}))).map(x=><option key={x.id} value={x.id}>{x.label}</option>)}
            </select>
          </label>
          <label>Minimum payout (₹)
            <input inputMode="decimal" type="number" min="1" step="0.01" required value={settingsForm.minimumRupees} onChange={e=>setSettingsForm(v=>({...v,minimumRupees:e.target.value}))}/>
            <small>Balances below this amount carry forward to the next month.</small>
          </label>
          <label>Refund reserve (%)
            <input type="number" min="0" max="100" step="0.5" required value={settingsForm.reservePercent} onChange={e=>setSettingsForm(v=>({...v,reservePercent:e.target.value}))}/>
            <small>This portion remains unpaid to help cover future refunds; it remains a liability, not company revenue.</small>
          </label>
          <div className={styles.payoutFormFooter}><span>{settings?.configured?'Saved payout rules are active.':'Save these rules before creating a payout batch.'}</span>
            <button type="submit" className={styles.primary} disabled={busy}>Save payout rules</button></div>
        </form>
      </section>

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>2. Bank accounts for recipients</h2><p>Register the beneficiary through RazorpayX. Only the last four digits are kept in QuantaEdge. The submitter cannot approve their own account.</p></div></div>
        <form className={styles.payoutFormGrid} onSubmit={submitBeneficiary}>
          <label>Recipient type
            <select value={beneficiaryForm.type} onChange={e=>{
              const type=e.target.value;
              const options=type==='INSTITUTION'?activeInstitutions:activeCompanies;
              setBeneficiaryForm(v=>({...v,type,recipientId:String(options[0]?.id??''),accountNumber:''}));
            }}><option value="INSTITUTION">Institution</option><option value="COMPANY">Company</option></select>
          </label>
          <label>Beneficiary
            <select required value={beneficiaryForm.recipientId} onChange={e=>setBeneficiaryForm(v=>({...v,recipientId:e.target.value}))}>
              <option value="">Select recipient</option>{recipientOptions.map(o=><option key={o.id} value={o.id}>{o.label}</option>)}
            </select>
          </label>
          <label>Account holder’s legal name<input required maxLength={240} value={beneficiaryForm.holderName} onChange={e=>setBeneficiaryForm(v=>({...v,holderName:e.target.value}))}/></label>
          <label>Bank account number<input required type="password" autoComplete="off" inputMode="numeric" maxLength={18} value={beneficiaryForm.accountNumber} onChange={e=>setBeneficiaryForm(v=>({...v,accountNumber:e.target.value.replace(/\\D/g,'').slice(0,18)}))}/><small>Used once for RazorpayX setup; never stored in the QuantaEdge database.</small></label>
          <label>IFSC<input required autoComplete="off" maxLength={11} value={beneficiaryForm.ifsc} onChange={e=>setBeneficiaryForm(v=>({...v,ifsc:e.target.value.toUpperCase().replace(/\\s/g,'')}))} placeholder="e.g. ABCD0123456"/></label>
          <label>Contact email<input type="email" maxLength={320} value={beneficiaryForm.email} onChange={e=>setBeneficiaryForm(v=>({...v,email:e.target.value}))}/></label>
          <label>Contact phone<input type="tel" maxLength={20} value={beneficiaryForm.phone} onChange={e=>setBeneficiaryForm(v=>({...v,phone:e.target.value}))}/><small>Provide an email or phone number for the RazorpayX contact.</small></label>
          <div className={styles.payoutFormFooter}><span>New or changed bank accounts require independent approval before they can receive money.</span>
            <button type="submit" className={styles.primary} disabled={busy||!payoutEligible||!beneficiaryForm.recipientId||!beneficiaryForm.accountNumber}>Submit bank details</button></div>
        </form>
        <div className={styles.payoutList}>
          {beneficiaries.map(b=><article className={styles.payoutListRow} key={b.id}>
            <div className={styles.payoutRowMain}><strong>{b.recipient_name||b.beneficiary_name}</strong><span>{b.beneficiary_type} · {b.bank_ifsc} · account ending {b.bank_account_last4}</span>
              <small>{b.status==='PENDING_APPROVAL'?'Submitted by staff #'+b.created_by_staff_id+' · awaiting independent approval':b.status==='ACTIVE'?'Beneficiary is approved for payout':b.rejection_reason||'Provider status: '+label(b.status)}</small></div>
            <span className={statusClass(b.status)}>{label(b.status)}</span>
            <div className={styles.payoutRowActions}>
              {b.status==='PENDING_APPROVAL'&&<button type="button" className={styles.primarySmall} disabled={busy} onClick={()=>void action(()=>api(BASE+'/payout-beneficiaries/'+b.id+'/approve',{method:'POST',body:JSON.stringify({})}),'Beneficiary approved for future payout batches.')}>Approve</button>}
              {['PENDING_APPROVAL','PROVISIONING','RECONCILIATION_REQUIRED'].includes(b.status)&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>{setRejectingId(rejectingId===b.id?null:b.id);setRejectReason('');}}>Reject</button>}
            </div>
            {rejectingId===b.id&&<form className={styles.payoutRejectForm} onSubmit={e=>{e.preventDefault();void rejectBeneficiary(b.id);}}>
              <label>Reason for rejection<input required minLength={5} maxLength={500} value={rejectReason} onChange={e=>setRejectReason(e.target.value)} placeholder="Explain what needs correcting"/></label>
              <button type="submit" className={styles.secondarySmall} disabled={busy}>Confirm rejection</button>
            </form>}
          </article>)}
          {!beneficiaries.length&&<p className={styles.payoutEmpty}>No payout bank accounts registered yet. Add an institution or company above.</p>}
        </div>
      </section>

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>3. Confirm actual payment-processing charges</h2><p>Charges reduce the distributable pool before shares. Use the actual Razorpay settlement statement/API result—not an estimate. Until charges are reconciled, the sale cannot enter a payout batch.</p></div><span className={feeOrders.length?styles.payoutStatusPending:styles.payoutStatusSuccess}>{feeOrders.length} awaiting review</span></div>
        {feeOrders.map(order=>{
          const key=String(order.order_id);
          const draft=feeDrafts[key]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'};
          return <form className={styles.feeReviewRow} key={order.order_id} onSubmit={e=>{e.preventDefault();void submitFees(order);}}>
            <div className={styles.feeReviewIdentity}><strong>Order #{order.order_id} · {order.course_title}</strong><span>{order.source_institution} · {order.provider_payment_id}</span><small>Captured {money(order.captured_amount_paise)} · output tax {money(order.output_tax_paise)} · {order.verified_at?new Date(order.verified_at).toLocaleDateString('en-IN'):''}</small></div>
            <label>Provider processing fee (₹)<input type="number" min="0" step="0.01" required value={draft.fee} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,fee:e.target.value}}))}/></label>
            <label>Tax on provider fee (₹)<input type="number" min="0" step="0.01" required value={draft.tax} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,tax:e.target.value}}))}/></label>
            <label>Settlement / statement reference<input required minLength={3} maxLength={180} value={draft.reference} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,reference:e.target.value}}))} placeholder="Reference from provider settlement"/></label>
            <label>Evidence source<select value={draft.source} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,source:e.target.value}}))}><option value="SETTLEMENT_REPORT">Settlement report</option><option value="PROVIDER_API">Provider API</option><option value="MANUAL_RECONCILIATION">Manual reconciliation</option></select></label>
            <div className={styles.feeReviewFooter}><span>Entered values become an immutable finance record. Corrections need a reviewed adjustment.</span><button className={styles.secondarySmall} type="submit" disabled={busy}>Record confirmed charges</button></div>
          </form>;
        })}
        {!feeOrders.length&&<p className={styles.payoutEmpty}>No outstanding fee confirmations were returned. Refresh after new payments are captured.</p>}
      </section>

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>4. Monthly payout batches</h2><p>Prepare the previous completed calendar month. The amount is calculated from earned allocations, confirmed fee deductions, processed refunds, existing reservations, paid transfers and the configured refund reserve.</p></div>
          <button type="button" className={styles.primary} onClick={()=>void createBatch()} disabled={busy||loading||!payoutEligible||!settings?.configured||feeOrders.length>0}>Prepare last month’s batch</button></div>
        {!payoutEligible&&<p className={styles.payoutHint}>Preparation is disabled until the RazorpayX API credentials and source account are configured on the API server.</p>}
        {feeOrders.length>0&&<p className={styles.payoutHint}>Reconcile the {feeOrders.length} outstanding fee record(s) above first. A payout batch will not be prepared with estimated or missing processor charges.</p>}
        <div className={styles.payoutList}>
          {batches.map(batch=><article className={styles.payoutListRow} key={batch.id}>
            <div className={styles.payoutRowMain}><strong>{new Date(batch.period_start+'T12:00:00').toLocaleDateString('en-IN',{month:'long',year:'numeric'})} · Batch #{batch.id}</strong>
              <span>{batch.beneficiary_count} beneficiaries · {money(batch.total_amount_paise)} total</span>
              <small>{batch.paid_count??0} transfers confirmed paid · {batch.exception_count??0} exceptions · created by staff #{batch.created_by_staff_id}</small></div>
            <span className={statusClass(batch.status)}>{label(batch.status)}</span>
            <div className={styles.payoutRowActions}>
              <button type="button" className={styles.secondarySmall} onClick={()=>void openBatch(batch.id)}>View details</button>
              {batch.status==='PENDING_APPROVAL'&&<button type="button" className={styles.primarySmall} disabled={busy||!payoutEligible} onClick={()=>void approveBatch(batch)}>Approve & send</button>}
            </div>
          </article>)}
          {!batches.length&&<p className={styles.payoutEmpty}>No payout batches have been prepared yet.</p>}
        </div>
        {selectedBatch&&<section className={styles.payoutBatchDetail}>
          <div className={styles.panelHead}><div><h3>Batch #{selectedBatch.id} · {selectedBatch.period_start} to {selectedBatch.period_end}</h3>
            <p>{money(selectedBatch.total_amount_paise)} · {selectedBatch.beneficiary_count} beneficiary transfers · {label(selectedBatch.status)}</p></div><button type="button" className={styles.secondarySmall} onClick={()=>setSelectedBatch(null)}>Close details</button></div>
          {selectedBatch.status==='PENDING_APPROVAL'&&<p className={styles.payoutApprovalNote}>Approval sends the listed bank transfers to RazorpayX. The preparer cannot approve their own batch. Once approved, each payout is tracked separately and only marked paid when the provider confirms a processed result.</p>}
          {(selectedBatch.payouts||[]).map(p=><article className={styles.payoutListRow} key={p.id}>
            <div className={styles.payoutRowMain}><strong>{p.recipient_name||p.beneficiary_name} · {money(p.amount_paise,p.currency)}</strong>
              <span>{p.bank_ifsc} · account ending {p.bank_account_last4} · {p.beneficiary_type}</span>
              <small>{p.provider_payout_id?'RazorpayX '+p.provider_payout_id:'Provider payout ID not yet confirmed'}{p.transfer_reference?' · UTR '+p.transfer_reference:''}</small>
              {p.status_details&&<small>{p.status_details}</small>}</div>
            <span className={statusClass(p.status)}>{label(p.status)}</span>
            <div className={styles.payoutRowActions}>
              {!['PROCESSED','REVERSED','FAILED','REJECTED'].includes(p.status)&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>void refreshPayout(p)}>Refresh status</button>}
              {p.status==='RECONCILIATION_REQUIRED'&&!p.provider_payout_id&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>void retryPayout(p)}>Retry same request</button>}
            </div>
          </article>)}
        </section>}
      </section>
    </>}
  </section>;
}
