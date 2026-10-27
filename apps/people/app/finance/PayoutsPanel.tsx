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
  refund_reserve_basis_points?:number|null;payoutReady?:boolean;webhookConfigured?:boolean;
  collection_arrangement_status?:string;collection_arrangement_reference?:string|null;
  payment_collection_legal_entity_id?:number|null;razorpayx_funding_legal_entity_id?:number|null;
  payment_collection_legal_name?:string|null;razorpayx_funding_legal_name?:string|null;
  canVerifyCollectionArrangement?:boolean;collectionArrangementVerified?:boolean
};
type Overview={
  beneficiariesAwaitingApproval:number;beneficiariesNeedingReview:number;unreconciledFees:number;
  batchesAwaitingApproval:number;payoutsInProgress:number;payoutsPaidPaise:number;
  payoutsReversedPaise:number;payoutsFailed:number;providerConfigured:boolean;payoutFeeReconciliationPending?:number
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
  provider_fee_paise?:number|null;provider_fee_tax_paise?:number|null;provider_fee_reference?:string|null;
  provider_fee_source_type?:string|null;provider_fee_confirmed_at?:string|null;
  recipient_name:string;beneficiary_name:string;bank_ifsc:string;bank_account_last4:string;
  beneficiary_type:string;institution_name?:string|null;company_name?:string|null;
  created_by_staff_id?:number|null;approved_by_staff_id?:number|null
};
type Batch={id:number;public_id:string;period_start:string;period_end:string;currency:string;status:string;
  beneficiary_count:number;total_amount_paise:number;provider_fee_total_paise?:number|null;provider_fee_tax_total_paise?:number|null;
  provider_fee_reconciled_at?:string|null;created_by_staff_id:number;approved_by_staff_id?:number|null;
  approved_at?:string|null;paid_count?:number;exception_count?:number;payouts?:Payout[]};
type BeneficiaryBalance={
  recipient_type:string;recipient_name:string;currency:string;beneficiary_status:string;
  bank_ifsc?:string|null;bank_account_last4?:string|null;earned_paise:number;refunded_paise:number;
  charges_paise:number;paid_paise:number;reversed_paise:number;reserved_paise:number;
  net_earned_paise:number;net_paid_paise:number;refund_reserve_paise:number;payable_paise:number;recovery_due_paise:number;
  payout_supported?:boolean;payout_support_reason?:string
};
type BalanceResponse={totals:{netEarnedPaise:number;refundReversalsPaise:number;confirmedChargesPaise:number;
  netPaidPaise:number;payoutReversalsPaise:number;reservedPaise:number;refundReservePaise:number;payablePaise:number;
  recoveryDuePaise:number;refundReserveBasisPoints:number};beneficiaries:BeneficiaryBalance[];unsupportedCurrencies?:string[]};
type ProviderEvent={provider_event_id:string;provider_payout_id:string;event_type:string;disposition:string;received_at:string;payload_sha256:string};
type ReconciliationCandidate={id:number;public_id:string;batch_id:number;period_start:string;period_end:string;amount_paise:number;
  currency:string;status:string;recipient_name:string;bank_ifsc:string;bank_account_last4:string};

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
const money=(paise:number|null|undefined,currency='INR')=>typeof paise!=='number'?'—':new Intl.NumberFormat('en-IN',{style:'currency',currency,maximumFractionDigits:2}).format(paise/100);
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
  const [balances,setBalances]=useState<BalanceResponse|null>(null);
  const [unmatchedEvents,setUnmatchedEvents]=useState<ProviderEvent[]>([]);
  const [reconciliationCandidates,setReconciliationCandidates]=useState<ReconciliationCandidate[]>([]);
  const [reconciliationTargets,setReconciliationTargets]=useState<Record<string,string>>({});
  const [payoutFeeDrafts,setPayoutFeeDrafts]=useState<Record<string,{fee:string;tax:string;reference:string;source:string}>>({});
  const [selectedBatch,setSelectedBatch]=useState<Batch|null>(null);
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState(false);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [settingsForm,setSettingsForm]=useState({remainderType:'COMPANY',remainderId:'',minimumRupees:'',reservePercent:''});
  const [arrangementForm,setArrangementForm]=useState({collectionEntityId:'',fundingEntityId:'',reference:'',status:'PENDING',confirm:false});
  const [beneficiaryForm,setBeneficiaryForm]=useState({type:'INSTITUTION',recipientId:'',holderName:'',accountNumber:'',ifsc:'',email:'',phone:''});
  const [rejectingId,setRejectingId]=useState<number|null>(null);
  const [rejectReason,setRejectReason]=useState('');
  const [feeDrafts,setFeeDrafts]=useState<Record<string,{fee:string;tax:string;reference:string;source:string}>>({});

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const [c,s,o,b,f,bs,bal,ue,rc]=await Promise.all([
        api<Catalog>(BASE+'/payout-catalog'),api<Settings>(BASE+'/payout-settings'),api<Overview>(BASE+'/payout-overview'),
        api<Beneficiary[]>(BASE+'/payout-beneficiaries'),api<FeeOrder[]>(BASE+'/fee-reconciliation?limit=200'),
        api<Batch[]>(BASE+'/payout-batches'),api<BalanceResponse>(BASE+'/payout-balances'),
        api<ProviderEvent[]>(BASE+'/payout-provider-events/unmatched'),
        api<ReconciliationCandidate[]>(BASE+'/payout-reconciliation-candidates')
      ]);
      setCatalog(c);setSettings(s);setOverview(o);setBeneficiaries(Array.isArray(b)?b:[]);
      setFeeOrders(Array.isArray(f)?f:[]);setBatches(Array.isArray(bs)?bs:[]);setBalances(bal);
      setUnmatchedEvents(Array.isArray(ue)?ue:[]);setReconciliationCandidates(Array.isArray(rc)?rc:[]);
      const companies=c.legalEntities.filter(e=>e.status==='ACTIVE'&&['PLATFORM','COMPANY'].includes(e.entity_kind));
      const institutions=c.institutions.filter(i=>i.status==='ACTIVE');
      setArrangementForm(old=>({
        collectionEntityId:s.payment_collection_legal_entity_id!=null?String(s.payment_collection_legal_entity_id):'',
        fundingEntityId:s.razorpayx_funding_legal_entity_id!=null?String(s.razorpayx_funding_legal_entity_id):'',
        reference:s.collection_arrangement_reference||'',
        status:s.collection_arrangement_status||'PENDING',
        confirm:false
      }));
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
  const payoutEligible=Boolean(settings?.configured&&settings?.payoutReady);

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

  const submitArrangement=async(event:FormEvent)=>{
    event.preventDefault();
    if(!settings?.configured){
      setError('Save the payout rules first. The legal/collection review is linked to those saved finance settings.');
      return;
    }
    if(!settings?.canVerifyCollectionArrangement){
      setError('Only an authorized platform owner can verify the legal seller and collection-account arrangement.');
      return;
    }
    if(!arrangementForm.collectionEntityId||!arrangementForm.fundingEntityId){
      setError('Select both the payment-collection legal entity and the RazorpayX payout-funding owner.');
      return;
    }
    if(arrangementForm.status==='VERIFIED'&&(!arrangementForm.confirm||arrangementForm.reference.trim().length<3)){
      setError('Confirm the legal review and enter its agreement/provider reference before marking this arrangement verified.');
      return;
    }
    const result=await action(()=>api(BASE+'/payout-settings/collection-arrangement',{method:'POST',body:JSON.stringify({
      status:arrangementForm.status,
      paymentCollectionLegalEntityId:Number(arrangementForm.collectionEntityId),
      razorpayxFundingLegalEntityId:Number(arrangementForm.fundingEntityId),
      evidenceReference:arrangementForm.reference.trim()||null,
      confirm:arrangementForm.confirm
    })}),arrangementForm.status==='VERIFIED'?'Legal and collection-account arrangement verified. Payouts are enabled only if RazorpayX credentials and webhook are also ready.':'Collection arrangement review saved.');
    if(result)setArrangementForm(v=>({...v,confirm:false}));
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
    if(order.currency?.trim().toUpperCase()!=='INR'){
      setError('This fee form records integer paise and supports INR only. Do not enter a non-INR amount here; use the approved currency-compatible settlement route.');
      return;
    }
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
    const result=await action(()=>api<any>(BASE+'/payouts/'+payout.id+'/refresh',{method:'POST',body:JSON.stringify({})}),
      'RazorpayX payout status refreshed.');
    if(result&&selectedBatch)await openBatch(selectedBatch.id);
  };
  const retryPayout=async(payout:Payout)=>{
    const result=await action(()=>api<any>(BASE+'/payouts/'+payout.id+'/retry-submission',{method:'POST',body:JSON.stringify({})}),
      'Safe retry requested using the existing payout idempotency key.');
    if(result&&selectedBatch)await openBatch(selectedBatch.id);
  };
  const reconcileProviderEvent=async(event:ProviderEvent)=>{
    const target=reconciliationTargets[event.provider_event_id];
    if(!target||!Number.isSafeInteger(Number(target))||Number(target)<=0){
      setError('Select an existing payout before reconciling this provider event.');
      return;
    }
    const result=await action(()=>api(BASE+'/payout-provider-events/'+encodeURIComponent(event.provider_event_id)+'/reconcile',{
      method:'POST',body:JSON.stringify({payoutId:Number(target)})
    }),'Provider event linked after server-side amount, currency and beneficiary-account checks. Review the payout status before considering it settled.');
    if(result)setReconciliationTargets(old=>({...old,[event.provider_event_id]:''}));
  };

  const submitPayoutFees=async(payout:Payout)=>{
    const draft=payoutFeeDrafts[String(payout.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'};
    const fee=parsePaise(draft.fee,true),tax=parsePaise(draft.tax,true);
    if(fee===null||tax===null){setError('Enter the actual RazorpayX payout fee and fee tax in rupees. Zero is valid if confirmed by the provider statement.');return;}
    if(draft.reference.trim().length<3){setError('Enter the actual payout settlement/statement reference.');return;}
    const result=await action(()=>api<Payout>(BASE+'/payouts/'+payout.id+'/fees',{
      method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},
      body:JSON.stringify({processingFeePaise:fee,feeTaxPaise:tax,settlementReference:draft.reference.trim(),sourceType:draft.source})
    }),'Actual RazorpayX payout charges recorded. Batch and beneficiary balances now include the confirmed charge allocation.');
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
        <article className={styles.payoutMetricCard}><span>Payments missing fee confirmation</span><strong>{overview?.unreconciledFees??'—'}</strong><small>Payout batches stay blocked until actual fees are recorded</small></article>
        <article className={styles.payoutMetricCard}><span>Completed batches awaiting fee reconciliation</span><strong>{overview?.payoutFeeReconciliationPending??'—'}</strong><small>Confirm provider payout fees before preparing the next month</small></article>
        <article className={styles.payoutMetricCard}><span>Transfers in progress</span><strong>{overview?.payoutsInProgress??'—'}</strong><small>Not counted as paid until RazorpayX confirms completion</small></article>
        <article className={styles.payoutMetricCard}><span>Confirmed transfers</span><strong>{money(overview?.payoutsPaidPaise)}</strong><small>Provider-confirmed processed payouts</small></article>
        <article className={styles.payoutMetricCard}><span>INR available after reserve</span><strong>{money(balances?.totals.payablePaise)}</strong><small>INR-only total after charges, refunds, reservations and holdback</small></article>
        <article className={styles.payoutMetricCard}><span>Recovery balances</span><strong>{money(balances?.totals.recoveryDuePaise)}</strong><small>Amounts owed back where a beneficiary was already paid</small></article>
      </section>

      {!settings?.providerConfigured||!settings?.sourceAccountConfigured||!settings?.webhookConfigured?
        <div className={styles.payoutAlertWarning} role="status"><b>RazorpayX is not fully configured.</b> Configure the payout API credentials, source account identifier and payout webhook secret on the API server. Never paste secrets into this screen.</div>:null}
      {!settings?.collectionArrangementVerified&&<div className={styles.payoutAlertWarning} role="status"><b>Live transfers are blocked until legal review is recorded.</b> Select the entity receiving course-payment collections and the legal entity that owns the RazorpayX payout funding account. Only the platform owner can mark the arrangement verified.</div>}
      {(balances?.unsupportedCurrencies?.length??0)>0&&<div className={styles.payoutAlertWarning} role="alert"><b>Non-INR revenue needs a separate settlement route.</b> Outstanding allocations use {balances?.unsupportedCurrencies?.join(', ')}. This RazorpayX workflow is INR-only and performs no currency conversion; these balances are excluded from INR totals and monthly batches remain blocked until the non-INR balance is resolved.</div>}

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>Beneficiary balances</h2>
          <p>Balances come from the existing allocation and refund ledgers. Payable amounts are net of recorded refunds, confirmed charges, previous transfers, active payout reservations and the configured refund reserve.</p></div>
          <button type="button" className={styles.secondary} onClick={()=>void load()} disabled={loading||busy}>Refresh balances</button></div>
        {balances?.beneficiaries?.length?<div className={styles.payoutBalanceWrap}><table className={styles.payoutBalanceTable}>
          <thead><tr><th>Recipient</th><th>Net earned</th><th>Refund reversals</th><th>Charges</th><th>Paid</th><th>Held / reserved</th><th>Payable</th><th>Recovery due</th></tr></thead>
          <tbody>{balances.beneficiaries.map((balance,i)=><tr key={balance.recipient_type+':'+balance.recipient_name+':'+i}>
            <td><strong>{balance.recipient_name}</strong><small>{balance.recipient_type} · {balance.payout_supported===false?'Unsupported currency for payouts':balance.beneficiary_status==='MISSING'?'Bank account not approved':balance.bank_ifsc?balance.bank_ifsc+' · ****'+balance.bank_account_last4:label(balance.beneficiary_status)}</small></td>
            <td>{balance.payout_supported===false?'—':money(balance.net_earned_paise,balance.currency)}</td><td>{balance.payout_supported===false?'—':money(balance.refunded_paise,balance.currency)}</td>
            <td>{balance.payout_supported===false?'—':money(balance.charges_paise,balance.currency)}</td><td>{balance.payout_supported===false?'—':money(balance.net_paid_paise,balance.currency)}</td>
            <td>{balance.payout_supported===false?'—':money(balance.reserved_paise+balance.refund_reserve_paise,balance.currency)}</td>
            <td><strong>{balance.payout_supported===false?'—':money(balance.payable_paise,balance.currency)}</strong></td>
            <td>{balance.payout_supported===false?'—':balance.recovery_due_paise>0?<span className={styles.payoutStatusDanger}>{money(balance.recovery_due_paise,balance.currency)}</span>:money(0,balance.currency)}</td>
          </tr>)}</tbody>
        </table></div>:<p className={styles.payoutEmpty}>No verified course-sale allocations have been posted yet. Captured sales appear after the existing financial finalizer adds ledger entries.</p>}
      </section>

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>Unmatched RazorpayX webhook events</h2>
          <p>Signed provider events that could not automatically be tied to a local payout. Choose a candidate only after reviewing the RazorpayX record; the server independently checks the amount, currency, fund account and any supplied QuantaEdge references.</p></div>
          <span className={unmatchedEvents.length?styles.payoutStatusPending:styles.payoutStatusNeutral}>{unmatchedEvents.length} need review</span></div>
        {unmatchedEvents.length?<div className={styles.payoutList}>
          {unmatchedEvents.map(event=><article className={styles.payoutListRow} key={event.provider_event_id}>
            <div className={styles.payoutRowMain}><strong>{event.event_type} · {event.provider_payout_id}</strong>
              <span>Provider event {event.provider_event_id} · received {event.received_at?new Date(event.received_at).toLocaleString('en-IN'):'time unavailable'}</span>
              <small>Payload fingerprint {event.payload_sha256?.slice(0,16)||'not available'} · {event.disposition}</small></div>
            <label className={styles.payoutReconcileSelect}>Existing approved payout
              <select value={reconciliationTargets[event.provider_event_id]||''} onChange={e=>setReconciliationTargets(old=>({...old,[event.provider_event_id]:e.target.value}))}>
                <option value="">Choose payout to validate</option>
                {reconciliationCandidates.map(p=><option key={p.id} value={p.id}>Payout #{p.id} · {p.recipient_name} · {money(p.amount_paise,p.currency)} · {new Date(p.period_start+'T12:00:00').toLocaleDateString('en-IN',{month:'short',year:'numeric'})} · {label(p.status)}</option>)}
              </select>
            </label>
            <div className={styles.payoutRowActions}><button type="button" className={styles.primarySmall} disabled={busy||!reconciliationTargets[event.provider_event_id]||!reconciliationCandidates.length} onClick={()=>void reconcileProviderEvent(event)}>Validate & link event</button></div>
          </article>)}
        </div>:<p className={styles.payoutEmpty}>No unmatched signed webhook events. Any event that cannot be linked will remain visible here until an authorized reviewer validates the matching payout.</p>}
        {!reconciliationCandidates.length&&unmatchedEvents.length>0&&<p className={styles.payoutHint}>There are no approved, unlinked payout candidates. Do not force a match. First locate or recover the original QuantaEdge payout, then return here to validate the provider event.</p>}
      </section>

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
        <div className={styles.panelHead}><div><h2>2. Legal seller & collection-account review</h2>
          <p>This is a launch gate, not a legal opinion. Verify the seller/collection arrangement with Razorpay and the responsible legal/tax adviser before any transfer can be sent.</p></div>
          <span className={statusClass(settings?.collection_arrangement_status||'PENDING')}>{label(settings?.collection_arrangement_status||'PENDING')}</span></div>
        {settings?.canVerifyCollectionArrangement?<form className={styles.payoutFormGrid} onSubmit={submitArrangement}>
          <label>Legal entity receiving course-payment collections
            <select required value={arrangementForm.collectionEntityId} onChange={e=>setArrangementForm(v=>({...v,collectionEntityId:e.target.value}))}>
              <option value="">Choose legal entity</option>{catalog?.legalEntities.filter(e=>e.status==='ACTIVE').map(e=><option key={e.id} value={e.id}>{e.legal_name} · {e.entity_kind}</option>)}
            </select>
            <small>This is the legally reviewed collection/seller arrangement for incoming course payments.</small>
          </label>
          <label>Legal owner of RazorpayX payout funding account
            <select required value={arrangementForm.fundingEntityId} onChange={e=>setArrangementForm(v=>({...v,fundingEntityId:e.target.value}))}>
              <option value="">Choose funding owner</option>{activeCompanies.map(e=><option key={e.id} value={e.id}>{e.legal_name} · {e.entity_kind}</option>)}
            </select>
            <small>Must match the verified legal owner of the RazorpayX funding account configured on the API server.</small>
          </label>
          <label>Agreement or provider review reference
            <input required={arrangementForm.status==='VERIFIED'} maxLength={180} value={arrangementForm.reference} onChange={e=>setArrangementForm(v=>({...v,reference:e.target.value}))} placeholder="Agreement ID or Razorpay support case"/>
          </label>
          <label>Review status
            <select value={arrangementForm.status} onChange={e=>setArrangementForm(v=>({...v,status:e.target.value,confirm:false}))}>
              <option value="PENDING">Pending review</option><option value="VERIFIED">Verified — allow payout after setup</option><option value="BLOCKED">Blocked — do not transfer</option>
            </select>
          </label>
          {arrangementForm.status==='VERIFIED'&&<label className={styles.payoutConfirmCheck}><input type="checkbox" checked={arrangementForm.confirm} onChange={e=>setArrangementForm(v=>({...v,confirm:e.target.checked}))}/>I confirm the legal seller, payment collection arrangement and RazorpayX funding ownership have been reviewed and approved.</label>}
          <div className={styles.payoutFormFooter}><span>Changing this review status does not alter a sale's saved legal seller or tax snapshot.</span><button className={styles.primary} type="submit" disabled={busy||!settings?.configured}>{arrangementForm.status==='VERIFIED'?'Confirm legal review':'Save review status'}</button></div>
        </form>:<div className={styles.payoutReadOnly}>
          <p><b>Collection entity:</b> {settings?.payment_collection_legal_name||'Not selected'}</p>
          <p><b>RazorpayX funding owner:</b> {settings?.razorpayx_funding_legal_name||'Not selected'}</p>
          <p><b>Evidence reference:</b> {settings?.collection_arrangement_reference||'Not recorded'}</p>
          <p>Only a staff member with the platform-owner permission can record this legal review.</p>
        </div>}
      </section>

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>3. Bank accounts for recipients</h2><p>Register the beneficiary through RazorpayX. Only the last four digits are kept in QuantaEdge. The submitter cannot approve their own account.</p></div></div>
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
            <button type="submit" className={styles.primary} disabled={busy||!settings?.providerConfigured||!beneficiaryForm.recipientId||!beneficiaryForm.accountNumber}>Submit bank details</button></div>
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
        <div className={styles.panelHead}><div><h2>4. Confirm actual payment-processing charges</h2><p>Charges reduce the distributable pool before shares. Use the actual Razorpay settlement statement/API result—not an estimate. Until charges are reconciled, the sale cannot enter a payout batch.</p></div><span className={feeOrders.length?styles.payoutStatusPending:styles.payoutStatusSuccess}>{feeOrders.length} awaiting review</span></div>
        {feeOrders.map(order=>{
          const key=String(order.order_id);
          const draft=feeDrafts[key]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'};
          const supportedCurrency=order.currency?.trim().toUpperCase()==='INR';
          return <form className={styles.feeReviewRow} key={order.order_id} onSubmit={e=>{e.preventDefault();void submitFees(order);}}>
            <div className={styles.feeReviewIdentity}><strong>Order #{order.order_id} · {order.course_title}</strong><span>{order.source_institution} · {order.provider_payment_id} · {order.currency}</span><small>{supportedCurrency?<>Captured {money(order.captured_amount_paise,order.currency)} · output tax {money(order.output_tax_paise,order.currency)}</>:'Amount display withheld because this flow stores integer paise for INR only'} · {order.verified_at?new Date(order.verified_at).toLocaleDateString('en-IN'):''}</small></div>
            {!supportedCurrency?<p className={styles.payoutHint}>Fee reconciliation is blocked for {order.currency||'unknown currency'} because this form records integer paise for INR only. Do not reinterpret or convert these amounts as rupees; use the approved currency-compatible settlement route.</p>:<>
              <label>Provider processing fee (₹)<input type="number" min="0" step="0.01" required value={draft.fee} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,fee:e.target.value}}))}/></label>
              <label>Tax on provider fee (₹)<input type="number" min="0" step="0.01" required value={draft.tax} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,tax:e.target.value}}))}/></label>
              <label>Settlement / statement reference<input required minLength={3} maxLength={180} value={draft.reference} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,reference:e.target.value}}))} placeholder="Reference from provider settlement"/></label>
              <label>Evidence source<select value={draft.source} onChange={e=>setFeeDrafts(v=>({...v,[key]:{...draft,source:e.target.value}}))}><option value="SETTLEMENT_REPORT">Settlement report</option><option value="PROVIDER_API">Provider API</option><option value="MANUAL_RECONCILIATION">Manual reconciliation</option></select></label>
              <div className={styles.feeReviewFooter}><span>Entered values become an immutable finance record. Corrections need a reviewed adjustment.</span><button className={styles.secondarySmall} type="submit" disabled={busy}>Record confirmed charges</button></div>
            </>}
          </form>;
        })}
        {!feeOrders.length&&<p className={styles.payoutEmpty}>No outstanding fee confirmations were returned. Refresh after new payments are captured.</p>}
      </section>

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>5. Monthly payout batches</h2><p>Prepare the previous completed calendar month. The amount is calculated from earned allocations, confirmed fee deductions, processed refunds, existing reservations, paid transfers and the configured refund reserve.</p></div>
          <button type="button" className={styles.primary} onClick={()=>void createBatch()} disabled={busy||loading||!payoutEligible||!settings?.configured||feeOrders.length>0||(balances?.unsupportedCurrencies?.length??0)>0}>Prepare last month’s batch</button></div>
        {!payoutEligible&&<p className={styles.payoutHint}>Preparation is disabled until the RazorpayX API credentials and source account are configured on the API server.</p>}
        {feeOrders.length>0&&<p className={styles.payoutHint}>Reconcile the {feeOrders.length} outstanding fee record(s) above first. A payout batch will not be prepared with estimated or missing processor charges.</p>}
        {(overview?.payoutFeeReconciliationPending??0)>0&&<p className={styles.payoutHint}>There are {overview?.payoutFeeReconciliationPending} completed payout batch(es) with unconfirmed RazorpayX transfer charges. Open each batch, record its actual provider fee and fee tax from the settlement statement, and reconcile it before preparing another month.</p>}
        <div className={styles.payoutList}>
          {batches.map(batch=><article className={styles.payoutListRow} key={batch.id}>
            <div className={styles.payoutRowMain}><strong>{new Date(batch.period_start+'T12:00:00').toLocaleDateString('en-IN',{month:'long',year:'numeric'})} · Batch #{batch.id}</strong>
              <span>{batch.beneficiary_count} beneficiaries · {money(batch.total_amount_paise)} total</span>
              <small>{batch.paid_count??0} transfers confirmed paid · {batch.exception_count??0} exceptions · created by staff #{batch.created_by_staff_id}</small>
              <small>{batch.provider_fee_reconciled_at?('Payout charges reconciled: '+money(batch.provider_fee_total_paise)+' + fee tax '+money(batch.provider_fee_tax_total_paise)):'Payout transfer fees await confirmed provider values or statement reconciliation.'}</small></div>
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
          {!selectedBatch.provider_fee_reconciled_at&&<p className={styles.payoutHint}>Record actual RazorpayX payout fees from the provider response or settlement statement. The charge adjustment is posted to the same allocation ledger and reduces future balances; do not treat unreconciled charges as platform revenue.</p>}
          {(selectedBatch.payouts||[]).map(p=><article className={styles.payoutListRow} key={p.id}>
            <div className={styles.payoutRowMain}><strong>{p.recipient_name||p.beneficiary_name} · {money(p.amount_paise,p.currency)}</strong>
              <span>{p.bank_ifsc} · account ending {p.bank_account_last4} · {p.beneficiary_type}</span>
              <small>{p.provider_payout_id?'RazorpayX '+p.provider_payout_id:'Provider payout ID not yet confirmed'}{p.transfer_reference?' · UTR '+p.transfer_reference:''}</small>
              {p.provider_fee_paise!=null&&p.provider_fee_tax_paise!=null&&<small>Provider payout charge: {money(p.provider_fee_paise)} + fee tax {money(p.provider_fee_tax_paise)}{p.provider_fee_source_type?' · '+p.provider_fee_source_type:''}</small>}
              {p.status_details&&<small>{p.status_details}</small>}</div>
            <span className={statusClass(p.status)}>{label(p.status)}</span>
            <div className={styles.payoutRowActions}>
              {!['PROCESSED','REVERSED','FAILED','REJECTED'].includes(p.status)&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>void refreshPayout(p)}>Refresh status</button>}
              {!p.provider_payout_id&&(['RECONCILIATION_REQUIRED','SUBMITTING','APPROVED'].includes(p.status)||(p.status==='PENDING_APPROVAL'&&['APPROVED','PROCESSING','PARTIAL'].includes(selectedBatch.status)))&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>void retryPayout(p)}>{p.status==='APPROVED'||p.status==='PENDING_APPROVAL'?'Resume submission':'Retry same request'}</button>}
            </div>
            {!selectedBatch.provider_fee_reconciled_at&&['PROCESSED','FAILED','REJECTED','REVERSED'].includes(p.status)&&(p.provider_fee_paise==null||p.provider_fee_tax_paise==null)&&<form className={styles.payoutFeeForm} onSubmit={e=>{e.preventDefault();void submitPayoutFees(p);}}>
              <label>Confirmed payout fee (₹)<input type="number" min="0" step="0.01" required value={(payoutFeeDrafts[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}).fee} onChange={e=>setPayoutFeeDrafts(old=>({...old,[String(p.id)]:{...(old[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}),fee:e.target.value}}))}/></label>
              <label>Tax on payout fee (₹)<input type="number" min="0" step="0.01" required value={(payoutFeeDrafts[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}).tax} onChange={e=>setPayoutFeeDrafts(old=>({...old,[String(p.id)]:{...(old[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}),tax:e.target.value}}))}/></label>
              <label>Settlement reference<input required minLength={3} maxLength={180} value={(payoutFeeDrafts[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}).reference} onChange={e=>setPayoutFeeDrafts(old=>({...old,[String(p.id)]:{...(old[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}),reference:e.target.value}}))}/></label>
              <label>Evidence source<select value={(payoutFeeDrafts[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}).source} onChange={e=>setPayoutFeeDrafts(old=>({...old,[String(p.id)]:{...(old[String(p.id)]||{fee:'',tax:'',reference:'',source:'SETTLEMENT_REPORT'}),source:e.target.value}}))}><option value="SETTLEMENT_REPORT">Provider settlement report</option><option value="MANUAL_RECONCILIATION">Manual statement reconciliation</option></select></label>
              <div className={styles.payoutFormFooter}><span>These are actual charges, not estimates. They are shared across the batch's allocation ledger and carried into the next payable calculation as required.</span><button type="submit" className={styles.secondarySmall} disabled={busy}>Record confirmed payout charges</button></div>
            </form>}
          </article>)}
        </section>}
      </section>
    </>}
  </section>;
}
