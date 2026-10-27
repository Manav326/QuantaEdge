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
  beneficiariesAwaitingApproval:number;beneficiariesNeedingReview:number;unreconciledFees:number;unreconciledFeesForNextBatch?:number;
  batchesAwaitingApproval:number;payoutsInProgress:number;payoutsPaidPaise:number;
  payoutsReversedPaise:number;payoutsFailed:number;providerConfigured:boolean;payoutFeeReconciliationPending?:number;
  pendingFeeAdjustments?:number;pendingFeeAdjustmentsForNextBatch?:number
};
type Beneficiary={
  id:number;public_id:string;beneficiary_type:string;institution_id?:number|null;legal_entity_id?:number|null;
  replaces_beneficiary_id?:number|null;beneficiary_name:string;recipient_name:string;bank_ifsc:string;bank_account_last4:string;status:string;
  created_by_staff_id:number;created_by_name?:string|null;canApprove?:boolean;
  approved_by_staff_id?:number|null;approved_at?:string|null;rejection_reason?:string|null
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
  platform_borne_overage_paise?:number|null;provider_fee_reconciled_at?:string|null;created_by_staff_id:number;created_by_name?:string|null;
  canApprove?:boolean;approved_by_staff_id?:number|null;approved_by_name?:string|null;approved_at?:string|null;
  paid_count?:number;exception_count?:number;payouts?:Payout[]};
type BeneficiaryBalance={
  recipient_type:string;recipient_name:string;institution_id?:number|null;legal_entity_id?:number|null;currency:string;recipient_status?:string;beneficiary_status:string;
  bank_ifsc?:string|null;bank_account_last4?:string|null;earned_paise:number;refunded_paise:number;
  charges_paise:number;paid_paise:number;reversed_paise:number;reserved_paise:number;
  net_earned_paise:number;net_paid_paise:number;refund_reserve_paise:number;payable_paise:number;recovery_due_paise:number;
  source_sale_count?:number;unreconciled_fee_order_count?:number;
  payout_readiness_status?:string;payout_readiness_reason?:string;
  payout_supported?:boolean;payout_support_reason?:string
};
type TaxOverview={
  currency:string;verifiedSalesPaise:number;verifiedOrderCount:number;recordedOutputTaxPaise:number;
  processedCreditNoteTaxPaise:number;netOutputTaxBeforeInputCreditsPaise:number;
  recordedProcessorFeeTaxPaise:number;approvedTaxProfiles:number;pendingTaxProfiles:number;
  gstTcsLedgerStatus:string;incomeTaxTdsLedgerStatus:string;finalGovernmentTaxPayableStatus:string;
  finalTaxAmountIncludesInputCredits:boolean;nonInrCurrencies?:string[]
};
type BalanceResponse={totals:{netEarnedPaise:number;refundReversalsPaise:number;confirmedChargesPaise:number;
  netPaidPaise:number;payoutReversalsPaise:number;reservedPaise:number;refundReservePaise:number;payablePaise:number;
  recoveryDuePaise:number;refundReserveBasisPoints:number};beneficiaries:BeneficiaryBalance[];unsupportedCurrencies?:string[]};
type ProviderEvent={provider_event_id:string;provider_payout_id:string;event_type:string;disposition:string;received_at:string;payload_sha256:string};
type ReconciliationCandidate={id:number;public_id:string;batch_id:number;period_start:string;period_end:string;amount_paise:number;
  currency:string;status:string;recipient_name:string;bank_ifsc:string;bank_account_last4:string};
type FeeAdjustmentCandidate={order_id:number;course_title:string;source_institution:string;provider_payment_id:string;currency:string;
  recorded_processing_fee_paise:number;recorded_fee_tax_paise:number;effective_processing_fee_paise:number;effective_fee_tax_paise:number;
  effective_reference:string;effective_source_type:string;pending_adjustment_id?:number|null;can_adjust:boolean};
type FeeAdjustmentRequest={id:number;order_id:number;previous_processing_fee_paise:number;previous_fee_tax_paise:number;
  requested_processing_fee_paise:number;requested_fee_tax_paise:number;settlement_reference:string;source_type:string;reason:string;
  requested_by_staff_id:number;requested_by_name?:string|null;created_at:string;course_title:string;provider_payment_id:string;currency:string;canReview?:boolean};
type LedgerHistoryEvent={id:number;event_type:string;amount_paise:number;currency:string;payment_reference?:string|null;created_at:string;
  allocation_id:number;order_id:number;provider_payment_id?:string|null;course_title?:string|null;
  source_institution_id?:number|null;source_institution_name?:string|null;marketplace_institution_id?:number|null;marketplace_institution_name?:string|null;
  payout_id?:number|null;
  payout_public_id?:string|null;payout_status?:string|null;transfer_reference?:string|null;payout_batch_id?:number|null;
  payout_period_start?:string|null;fee_adjustment_id?:string|null;settlement_reference?:string|null;reason?:string|null};

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
  if(!/^\d{1,10}(?:\.\d{1,2})?$/.test(value))return null;
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
  const [taxOverview,setTaxOverview]=useState<TaxOverview|null>(null);
  const [recipientSearch,setRecipientSearch]=useState('');
  const [recipientReadinessFilter,setRecipientReadinessFilter]=useState('ALL');
  const [recipientCurrencyFilter,setRecipientCurrencyFilter]=useState('ALL');
  const [unmatchedEvents,setUnmatchedEvents]=useState<ProviderEvent[]>([]);
  const [reconciliationCandidates,setReconciliationCandidates]=useState<ReconciliationCandidate[]>([]);
  const [reconciliationTargets,setReconciliationTargets]=useState<Record<string,string>>({});
  const [payoutFeeDrafts,setPayoutFeeDrafts]=useState<Record<string,{fee:string;tax:string;reference:string;source:string}>>({});
  const [selectedBatch,setSelectedBatch]=useState<Batch|null>(null);
  const [loading,setLoading]=useState(true);
  const [busy,setBusy]=useState(false);
  const [loadIssues,setLoadIssues]=useState<{section:string;message:string}[]>([]);
  const [feeSearchDraft,setFeeSearchDraft]=useState('');
  const [feeSearch,setFeeSearch]=useState('');
  const [feeFromDraft,setFeeFromDraft]=useState('');
  const [feeThroughDraft,setFeeThroughDraft]=useState('');
  const [feeFrom,setFeeFrom]=useState('');
  const [feeThrough,setFeeThrough]=useState('');
  const [feeOffset,setFeeOffset]=useState(0);
  const [feeHasMore,setFeeHasMore]=useState(false);
  const [feeAdjustmentCandidates,setFeeAdjustmentCandidates]=useState<FeeAdjustmentCandidate[]>([]);
  const [feeAdjustmentQueueHasMore,setFeeAdjustmentQueueHasMore]=useState(false);
  const [feeAdjustmentSearchDraft,setFeeAdjustmentSearchDraft]=useState('');
  const [feeAdjustmentSearch,setFeeAdjustmentSearch]=useState('');
  const [feeAdjustmentOffset,setFeeAdjustmentOffset]=useState(0);
  const [pendingFeeAdjustments,setPendingFeeAdjustments]=useState<FeeAdjustmentRequest[]>([]);
  const [selectedFeeAdjustmentOrderId,setSelectedFeeAdjustmentOrderId]=useState<number|null>(null);
  const [feeAdjustmentDrafts,setFeeAdjustmentDrafts]=useState<Record<string,{fee:string;tax:string;reference:string;reason:string;source:string}>>({});
  const [feeAdjustmentReviewReasons,setFeeAdjustmentReviewReasons]=useState<Record<string,string>>({});
  const [approvalBatch,setApprovalBatch]=useState<Batch|null>(null);
  const [approvalConfirmed,setApprovalConfirmed]=useState(false);
  const [ledgerBalance,setLedgerBalance]=useState<BeneficiaryBalance|null>(null);
  const [ledgerRows,setLedgerRows]=useState<LedgerHistoryEvent[]>([]);
  const [ledgerLoading,setLedgerLoading]=useState(false);
  const [ledgerError,setLedgerError]=useState('');
  const [ledgerOffset,setLedgerOffset]=useState(0);
  const [ledgerHasMore,setLedgerHasMore]=useState(false);
  const [recoveryAccounts,setRecoveryAccounts]=useState<Record<string,string>>({});
  const [selectedReplacementId,setSelectedReplacementId]=useState<number|null>(null);
  const [replacementDrafts,setReplacementDrafts]=useState<Record<string,{holderName:string;accountNumber:string;ifsc:string;email:string;phone:string}>>({});
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
      const requests=[
        api<Catalog>(BASE+'/payout-catalog'),api<Settings>(BASE+'/payout-settings'),api<Overview>(BASE+'/payout-overview'),
        api<Beneficiary[]>(BASE+'/payout-beneficiaries'),
        api<FeeOrder[]>(BASE+'/fee-reconciliation?limit=51&offset='+feeOffset+'&q='+encodeURIComponent(feeSearch)+'&from='+encodeURIComponent(feeFrom)+'&through='+encodeURIComponent(feeThrough)),
        api<Batch[]>(BASE+'/payout-batches'),api<BalanceResponse>(BASE+'/payout-balances'),
        api<ProviderEvent[]>(BASE+'/payout-provider-events/unmatched'),
        api<ReconciliationCandidate[]>(BASE+'/payout-reconciliation-candidates'),
        api<FeeAdjustmentCandidate[]>(BASE+'/course-order-fee-adjustment-candidates?limit=51&offset='+feeAdjustmentOffset+'&q='+encodeURIComponent(feeAdjustmentSearch)),
        api<FeeAdjustmentRequest[]>(BASE+'/course-order-fee-adjustments/pending?limit=200'),
        api<TaxOverview>(BASE+'/payout-tax-overview')
      ];
      const sectionNames=['Payout catalog','Payout settings','Payout overview','Beneficiaries','Fee reconciliation queue','Payout batches','Beneficiary balances','Unmatched provider events','Payout reconciliation candidates','Fee-adjustment candidates','Pending fee corrections','Tax & compliance snapshot'];
      const results=await Promise.allSettled(requests);
      const read=(index:number):any=>results[index].status==='fulfilled'?(results[index] as PromiseFulfilledResult<any>).value:null;
      const issues=results.flatMap((result,index)=>result.status==='rejected'?[{
        section:sectionNames[index],message:result.reason instanceof Error?result.reason.message:'Request failed'
      }]:[]);
      const c=read(0) as Catalog|null,s=read(1) as Settings|null,o=read(2) as Overview|null;
      const b=read(3) as Beneficiary[]|null,f=read(4) as FeeOrder[]|null,bs=read(5) as Batch[]|null;
      const bal=read(6) as BalanceResponse|null,ue=read(7) as ProviderEvent[]|null,rc=read(8) as ReconciliationCandidate[]|null;
      const fc=read(9) as FeeAdjustmentCandidate[]|null,pfa=read(10) as FeeAdjustmentRequest[]|null;
      const tx=read(11) as TaxOverview|null;
      if(c){
        setCatalog(c);
        const companies=c.legalEntities.filter(e=>e.status==='ACTIVE'&&['PLATFORM','COMPANY'].includes(e.entity_kind));
        const institutions=c.institutions.filter(i=>i.status==='ACTIVE');
        setBeneficiaryForm(old=>{
          const options=old.type==='INSTITUTION'?institutions:companies;
          return {...old,recipientId:old.recipientId&&options.some(x=>String(x.id)===old.recipientId)?old.recipientId:String(options[0]?.id??'')};
        });
      }
      if(s){
        setSettings(s);
        setArrangementForm({collectionEntityId:s.payment_collection_legal_entity_id!=null?String(s.payment_collection_legal_entity_id):'',
          fundingEntityId:s.razorpayx_funding_legal_entity_id!=null?String(s.razorpayx_funding_legal_entity_id):'',
          reference:s.collection_arrangement_reference||'',status:s.collection_arrangement_status||'PENDING',confirm:false});
        setSettingsForm(old=>({
          remainderType:s.default_remainder_recipient_type||old.remainderType,
          remainderId:s.configured?String(s.default_remainder_recipient_type==='INSTITUTION'
            ?(s.default_remainder_institution_id??''):(s.default_remainder_legal_entity_id??'')):'',
          minimumRupees:s.payout_minimum_paise!=null?String(s.payout_minimum_paise/100):(s.configured?old.minimumRupees:''),
          reservePercent:s.refund_reserve_basis_points!=null?String(s.refund_reserve_basis_points/100):(s.configured?old.reservePercent:'')
        }));
      }
      if(o)setOverview(o);
      if(b)setBeneficiaries(Array.isArray(b)?b:[]);
      if(f){setFeeOrders(Array.isArray(f)?f.slice(0,50):[]);setFeeHasMore(f.length>50);}
      if(bs)setBatches(Array.isArray(bs)?bs:[]);
      if(bal)setBalances(bal);else setBalances(null);
      if(ue)setUnmatchedEvents(Array.isArray(ue)?ue:[]);
      if(rc)setReconciliationCandidates(Array.isArray(rc)?rc:[]);
      if(fc){setFeeAdjustmentCandidates(Array.isArray(fc)?fc.slice(0,50):[]);setFeeAdjustmentQueueHasMore(fc.length>50);}
      if(pfa)setPendingFeeAdjustments(Array.isArray(pfa)?pfa:[]);
      if(tx)setTaxOverview(tx);else setTaxOverview(null);
      setLoadIssues(issues);
    }
    finally{setLoading(false);}
  },[feeOffset,feeSearch,feeFrom,feeThrough,feeAdjustmentOffset,feeAdjustmentSearch]);
  useEffect(()=>{void load();},[load]);

  const action=async<T,>(work:()=>Promise<T>,success:string):Promise<T|null>=>{
    setBusy(true);setError('');setNotice('');
    try{const result=await work();setNotice(success);await load();return result;}
    catch(e){setError(e instanceof Error?e.message:'The payout action failed.');return null;}
    finally{setBusy(false);}
  };
  const activeInstitutions=catalog?.institutions.filter(i=>i.status==='ACTIVE')||[];
  const activeCompanies=catalog?.legalEntities.filter(e=>e.status==='ACTIVE'&&['PLATFORM','COMPANY'].includes(e.entity_kind))||[];
  const recipientCurrencies=Array.from(new Set((balances?.beneficiaries||[]).map(balance=>balance.currency))).sort();
  const filteredBalances=(balances?.beneficiaries||[]).filter(balance=>{
    const query=recipientSearch.trim().toLocaleLowerCase();
    const haystack=[balance.recipient_name,balance.recipient_type,balance.currency,balance.bank_ifsc||'',balance.payout_readiness_reason||'']
      .join(' ').toLocaleLowerCase();
    const matchesQuery=!query||haystack.includes(query);
    const status=balance.payout_readiness_status||'';
    const matchesStatus=recipientReadinessFilter==='ALL'
      ||(recipientReadinessFilter==='READY'&&status==='READY_FOR_BATCH_REVIEW')
      ||(recipientReadinessFilter==='BLOCKED'&&status==='BLOCKED')
      ||(recipientReadinessFilter==='MISSING_BANK'&&balance.beneficiary_status!=='ACTIVE')
      ||(recipientReadinessFilter==='RECOVERY_DUE'&&balance.recovery_due_paise>0)
      ||(recipientReadinessFilter==='IN_PROGRESS'&&balance.reserved_paise>0)
      ||status===recipientReadinessFilter;
    const matchesCurrency=recipientCurrencyFilter==='ALL'||balance.currency===recipientCurrencyFilter;
    return matchesQuery&&matchesStatus&&matchesCurrency;
  });
  const recipientOptions=beneficiaryForm.type==='INSTITUTION'
    ?activeInstitutions.map(i=>({id:i.id,label:i.display_name+' · '+i.tenant_code}))
    :activeCompanies.map(e=>({id:e.id,label:e.legal_name+' · '+e.entity_kind}));
  const payoutEligible=Boolean(settings?.configured&&settings?.payoutReady);
  const bankAccountsReady=Boolean(balances&&!beneficiaries.some(b=>['PROVISIONING','PENDING_APPROVAL','RECONCILIATION_REQUIRED'].includes(b.status))
    &&!balances.beneficiaries.some(b=>b.payout_supported!==false&&b.payable_paise>=(settings?.payout_minimum_paise??Number.MAX_SAFE_INTEGER)&&b.beneficiary_status!=='ACTIVE'));
  const feeReconciliationReady=Boolean(overview&&(overview.unreconciledFeesForNextBatch??0)===0
    &&(overview.payoutFeeReconciliationPending??0)===0&&(overview.pendingFeeAdjustmentsForNextBatch??0)===0);
  const savedRemainderId=String(settings?.default_remainder_recipient_type==='INSTITUTION'
    ?(settings?.default_remainder_institution_id??''):(settings?.default_remainder_legal_entity_id??''));
  const settingsDirty=!settings?.configured||settingsForm.remainderType!==(settings?.default_remainder_recipient_type||'COMPANY')
    ||settingsForm.remainderId!==savedRemainderId||parsePaise(settingsForm.minimumRupees,true)!==settings?.payout_minimum_paise
    ||!Number.isFinite(Number(settingsForm.reservePercent))
    ||Math.round(Number(settingsForm.reservePercent)*100)!==settings?.refund_reserve_basis_points;

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
    // Clear the sensitive value before awaiting the network and the following workspace refresh.
    setBeneficiaryForm(v=>({...v,accountNumber:''}));
    const result=await action(()=>api<{id:number;status:string}>(BASE+'/payout-beneficiaries',{method:'POST',body:JSON.stringify({
      beneficiaryType:form.type,beneficiaryId:Number(form.recipientId),accountHolderName:form.holderName,
      accountNumber:form.accountNumber,ifsc:form.ifsc,email:form.email||null,phone:form.phone||null
    })}),'Bank details submitted to RazorpayX. The beneficiary still needs independent approval.');
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
    const result=await action(()=>api<{platformBorneOveragePaise?:number}>(BASE+'/course-orders/'+order.order_id+'/processing-fees',{
      method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},
      body:JSON.stringify({processingFeePaise:fee,feeTaxPaise:tax,settlementReference:draft.reference.trim(),sourceType:draft.source})
    }),'Actual payment-processing charges recorded. Allocation adjustments are now reflected in payout balances.');
    const overage=Number(result?.platformBorneOveragePaise??0);
    if(result&&Number.isSafeInteger(overage)&&overage>0)
      setNotice('Actual collection charges were recorded in full. '+money(overage)+' is borne by the platform; beneficiary balances were not pushed below the approved share policy.');
  };


  const openBalanceLedger=async(balance:BeneficiaryBalance,offset=0)=>{
    const recipientId=balance.recipient_type==='INSTITUTION'?balance.institution_id:balance.legal_entity_id;
    if(!recipientId){setLedgerError('The ledger recipient identity is missing. Refresh balances before opening history.');return;}
    setLedgerBalance(balance);setLedgerLoading(true);setLedgerError('');
    try{
      const rows=await api<LedgerHistoryEvent[]>(BASE+'/payout-balances/ledger-history?recipientType='+encodeURIComponent(balance.recipient_type)
        +'&recipientId='+recipientId+'&currency='+encodeURIComponent(balance.currency)+'&limit=51&offset='+offset);
      setLedgerRows(rows.slice(0,50));setLedgerHasMore(rows.length>50);setLedgerOffset(offset);
    }catch(e){setLedgerRows([]);setLedgerHasMore(false);setLedgerError(e instanceof Error?e.message:'Ledger history could not load.');}
    finally{setLedgerLoading(false);}
  };
  const ledgerEventLabel=(type:string)=>({
    EARNED:'Earnings posted',PAYABLE:'Marked payable',REFUNDED:'Refund deducted',
    PROCESSING_FEE_DEDUCTED:'Processing fee deducted',PROCESSING_FEE_CREDITED:'Processing fee credited',
    PAID:'Transfer paid',REVERSED:'Ledger reversed',ON_HOLD:'Placed on hold',RELEASED:'Hold released'
  }[type]||type.replaceAll('_',' ').toLowerCase());

  const createBatch=async()=>{await action(()=>api(BASE+'/payout-batches',{method:'POST',body:JSON.stringify({})}),
    'Monthly payout batch prepared. No bank transfers are sent until a different authorized staff member approves it.');};
  const openBatch=async(id:number)=>{
    setError('');setNotice('');
    try{const detail=await api<Batch>(BASE+'/payout-batches/'+id);setSelectedBatch(detail);}
    catch(e){setError(e instanceof Error?e.message:'Payout batch detail could not load.');}
  };
  const openApprovalReview=async(batchId:number)=>{
    setError('');setNotice('');
    try{
      const detail=await api<Batch>(BASE+'/payout-batches/'+batchId);
      setSelectedBatch(detail);setApprovalBatch(detail);setApprovalConfirmed(false);
    }catch(e){setError(e instanceof Error?e.message:'Payout batch details could not load for approval.');}
  };
  const approveBatch=async(batch:Batch)=>{
    if(!approvalConfirmed){setError('Review the transfer list and confirm before sending any payout.');return;}
    const result=await action(()=>api<any>(BASE+'/payout-batches/'+batch.id+'/approve',{method:'POST',body:JSON.stringify({})}),
      'Approval request completed. Check each transfer status; only provider-confirmed processed transfers count as paid.');
    if(result){setApprovalBatch(null);setApprovalConfirmed(false);await openBatch(batch.id);}
  };
  const refreshPayout=async(payout:Payout)=>{
    const result=await action(()=>api<{refreshResult?:string;message?:string}>(BASE+'/payouts/'+payout.id+'/refresh',{method:'POST',body:JSON.stringify({})}),
      'Checking RazorpayX payout status…');
    if(result){
      setNotice(result.refreshResult==='REFRESHED'
        ?(result.message||'RazorpayX confirmed the latest payout status.')
        :(result.message||'A fresh provider status could not be confirmed. The saved status is not proof of payment.'));
      if(selectedBatch)await openBatch(selectedBatch.id);
    }
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

  const requestFeeAdjustment=async(candidate:FeeAdjustmentCandidate)=>{
    const key=String(candidate.order_id),draft=feeAdjustmentDrafts[key]||{fee:'',tax:'',reference:'',reason:'',source:'SETTLEMENT_REPORT'};
    const fee=parsePaise(draft.fee,true),tax=parsePaise(draft.tax,true);
    if(!candidate.can_adjust||candidate.pending_adjustment_id){setError('This order is not eligible for a new correction while a payout or another correction is unresolved.');return;}
    if(fee===null||tax===null){setError('Enter the corrected fee and fee tax in rupees. Zero is valid when confirmed by the statement.');return;}
    if(draft.reference.trim().length<3||draft.reference.trim().length>180){setError('Enter the provider settlement reference (3–180 characters).');return;}
    if(draft.reason.trim().length<5||draft.reason.trim().length>500){setError('Explain why the confirmed fee needs correction (5–500 characters).');return;}
    const result=await action(()=>api<{id:number;status:string;message:string}>(BASE+'/course-orders/'+candidate.order_id+'/fee-adjustments',{
      method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},
      body:JSON.stringify({processingFeePaise:fee,feeTaxPaise:tax,settlementReference:draft.reference.trim(),sourceType:draft.source,reason:draft.reason.trim()})
    }),'Fee correction request recorded for independent review. No fee or allocation was changed yet.');
    if(result){setSelectedFeeAdjustmentOrderId(null);setFeeAdjustmentDrafts(old=>({...old,[key]:{fee:'',tax:'',reference:'',reason:'',source:'SETTLEMENT_REPORT'}}));setNotice(result.message||'Correction awaits an independent review.');}
  };

  const reviewFeeAdjustment=async(adjustment:FeeAdjustmentRequest,decision:'APPROVED'|'REJECTED')=>{
    const key=String(adjustment.id),reason=(feeAdjustmentReviewReasons[key]||'').trim();
    if(reason.length<5||reason.length>500){setError('Enter the review rationale (5–500 characters).');return;}
    if(!adjustment.canReview){setError('The requester cannot review their own fee correction. Another authorized approver is required.');return;}
    const result=await action(()=>api<{id:number;status:string;message:string}>(BASE+'/course-order-fee-adjustments/'+adjustment.id+'/review',{
      method:'POST',body:JSON.stringify({decision,reason})
    }),decision==='APPROVED'?'Fee correction approved and appended to the existing allocation ledger.':'Fee correction rejected; original fee facts and allocation ledger remain unchanged.');
    if(result){setFeeAdjustmentReviewReasons(old=>({...old,[key]:''}));setNotice(result.message||'Fee correction review recorded.');}
  };

  const requestBankReplacement=async(beneficiary:Beneficiary)=>{
    const key=String(beneficiary.id),draft=replacementDrafts[key]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''};
    if(!/^\d{9,18}$/.test(draft.accountNumber)){setError('Enter the new 9–18 digit bank account number.');return;}
    if(!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(draft.ifsc.trim().toUpperCase())){setError('Enter a valid 11-character IFSC.');return;}
    if(!draft.email.trim()&&!draft.phone.trim()){setError('Provide an email address or phone number for RazorpayX.');return;}
    // Clear the sensitive value immediately; the request closes over the local draft only.
    setReplacementDrafts(old=>({...old,[key]:{...draft,accountNumber:''}}));
    const result=await action(()=>api<{id:number;status:string;message:string}>(BASE+'/payout-beneficiaries/'+beneficiary.id+'/replacement',{
      method:'POST',body:JSON.stringify({accountHolderName:draft.holderName,accountNumber:draft.accountNumber,ifsc:draft.ifsc.trim().toUpperCase(),email:draft.email.trim()||null,phone:draft.phone.trim()||null})
    }),'Replacement registration requested. The previous approved bank account remains active until independent approval.');
    if(result){setSelectedReplacementId(null);setNotice(result.message||'Replacement account is awaiting independent review.');}
  };

  const reconcileBeneficiaryProviderSetup=async(beneficiary:Beneficiary)=>{
    const key=String(beneficiary.id),accountNumber=(recoveryAccounts[key]||'').trim();
    if(!/^\d{9,18}$/.test(accountNumber)){setError('Re-enter the 9–18 digit bank account number so the server can match an existing provider record.');return;}
    // Clear the sensitive field before waiting on the provider lookup and subsequent panel refresh.
    setRecoveryAccounts(old=>({...old,[key]:''}));
    const result=await action(()=>api<{status:string;message:string}>(BASE+'/payout-beneficiaries/'+beneficiary.id+'/reconcile-provider-setup',{
      method:'POST',body:JSON.stringify({accountNumber})
    }),'Provider setup reconciliation checked.');
    if(result)setNotice(result.message||'Provider setup reconciliation completed.');
  };

  return <section className={styles.stack}>
    <section className={styles.payoutsIntro}>
      <div>
        <span className={styles.eyebrow}>HRMS · FINANCE OPERATIONS</span>
        <h2>Beneficiaries & payouts</h2>
        <p>Prepare monthly transfers from the existing course-revenue ledger. Bank details are submitted to RazorpayX and still require independent QuantaEdge approval before use.</p>
      </div>
      <button type="button" className={styles.secondary} onClick={()=>void load()} disabled={loading||busy}>Refresh</button>
    </section>

    {error&&<div className={styles.payoutAlertError} role="alert">{error}</div>}
    {notice&&<div className={styles.payoutAlertSuccess} role="status">{notice}</div>}
    {loadIssues.length>0&&<div className={styles.payoutAlertWarning} role="status"><b>Some finance panels did not refresh.</b><ul>{loadIssues.map(issue=><li key={issue.section}>{issue.section}: {issue.message}</li>)}</ul>Other panels remain available; refresh after resolving the listed request failures.</div>}
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
      {(balances?.totals.recoveryDuePaise??0)>0&&<p className={styles.payoutHint}>Recovery due means later refunds, reversals or reviewed fee corrections reduced a recipient’s net balance after a transfer. Future payable earnings offset that amount before another payout; QuantaEdge does not initiate a separate automatic bank debit.</p>}

      <div className={styles.payoutAlertWarning} role="status">
        <b>Tax review is not included in automated payout readiness.</b> The current ledger does not represent recipient-level GST TCS or income-tax TDS. “Payout checks pass” is not final clearance to send a transfer; confirm the legal seller, recipient tax treatment and withholding obligations in Finance &amp; Billing before approving a live batch.
      </div>

      <section className={styles.panel} id="payout-tax-compliance">
        <div className={styles.panelHead}><div><h2>Tax & compliance snapshot</h2>
          <p>Recorded figures from the existing verified-sale and credit-note records. This is a reconciliation snapshot, not the final amount payable to the Government.</p></div>
          <a className={styles.secondarySmall} href="/finance">Open Finance & Billing</a></div>
        {taxOverview?<>
          <div className={styles.payoutTaxSummaryGrid}>
            <article><span>Verified sales</span><strong>{money(taxOverview.verifiedSalesPaise,taxOverview.currency)}</strong><small>{taxOverview.verifiedOrderCount.toLocaleString('en-IN')} verified order(s)</small></article>
            <article><span>Recorded output tax</span><strong>{money(taxOverview.recordedOutputTaxPaise,taxOverview.currency)}</strong><small>Saved on verified course sales</small></article>
            <article><span>Processed credit-note tax</span><strong>{money(taxOverview.processedCreditNoteTaxPaise,taxOverview.currency)}</strong><small>Confirmed refund credit notes only</small></article>
            <article><span>Net output tax before input credits</span><strong>{money(taxOverview.netOutputTaxBeforeInputCreditsPaise,taxOverview.currency)}</strong><small>Not a final Government payable</small></article>
          </div>
          <div className={styles.payoutTaxCoverage}>
            <div><strong>GST tax on course sales</strong><span className={styles.payoutStatusNeutral}>Recorded</span><small>Tax amount on verified sales, less processed credit-note tax; input tax credits and returns are not included.</small></div>
            <div><strong>Tax on payment-provider fees</strong><span className={styles.payoutStatusNeutral}>Separate ledger</span><small>{money(taxOverview.recordedProcessorFeeTaxPaise,taxOverview.currency)} recorded as provider-fee tax; it is not course-sale GST.</small></div>
            <div><strong>GST TCS</strong><span className={styles.payoutStatusPending}>Not represented</span><small>No approved recipient-level TCS liability/remittance ledger exists yet. This must not be treated as ₹0.</small></div>
            <div><strong>Income-tax TDS</strong><span className={styles.payoutStatusPending}>Not represented</span><small>No approved recipient-level TDS liability/remittance ledger exists yet. This must not be treated as ₹0.</small></div>
          </div>
          {(taxOverview.nonInrCurrencies||[]).length>0&&<p className={styles.payoutAlertWarning} role="status">Non-INR sales use {taxOverview.nonInrCurrencies?.join(', ')} and are excluded from this INR-only tax snapshot. Reconcile each currency separately; no foreign-exchange conversion is assumed.</p>}
          <p className={styles.payoutHint}>Approved tax profiles: {taxOverview.approvedTaxProfiles.toLocaleString('en-IN')} · awaiting approval: {taxOverview.pendingTaxProfiles.toLocaleString('en-IN')}. Confirm the legal seller and applicability/rate/base with the tax adviser before treating any figure as a tax return liability or enabling withholding/remittance.</p>
        </>:<p className={styles.payoutEmpty}>Tax records could not be refreshed. Do not infer a zero tax amount; refresh or review Finance & Billing.</p>}
      </section>

      <nav className={styles.payoutReadiness} aria-label="Monthly payout workflow">
        <div><h3>Monthly payout workflow</h3><p>Use this checklist to move from setup to a reviewed batch. Transfers are sent only by an independent approver.</p></div>
        <div className={styles.payoutReadinessSteps}>
          <a href="#payout-rules" className={styles.payoutReadinessStep}><span>1</span><div><strong>Payout rules</strong><small>{settings?.configured?'Saved':'Needs setup'}</small></div><em className={settings?.configured?styles.payoutStatusSuccess:styles.payoutStatusPending}>{settings?.configured?'Ready':'Action needed'}</em></a>
          <a href="#payout-legal-review" className={styles.payoutReadinessStep}><span>2</span><div><strong>Legal & provider</strong><small>Seller, funding owner and webhook</small></div><em className={payoutEligible?styles.payoutStatusSuccess:styles.payoutStatusPending}>{payoutEligible?'Ready':'Gate pending'}</em></a>
          <a href="#payout-bank-accounts" className={styles.payoutReadinessStep}><span>3</span><div><strong>Bank accounts</strong><small>Independent approval and recovery</small></div><em className={bankAccountsReady?styles.payoutStatusSuccess:styles.payoutStatusPending}>{bankAccountsReady?'Reviewed':'Review needed'}</em></a>
          <a href="#payout-fees" className={styles.payoutReadinessStep}><span>4</span><div><strong>Fee reconciliation</strong><small>Actual confirmed provider charges</small></div><em className={feeReconciliationReady?styles.payoutStatusSuccess:styles.payoutStatusPending}>{feeReconciliationReady?'Clear':'Needs review'}</em></a>
          <a href="#payout-batches" className={styles.payoutReadinessStep}><span>5</span><div><strong>Monthly batch</strong><small>Preview all transfers before sending</small></div><em className={batches.some(b=>b.status==='PENDING_APPROVAL')?styles.payoutStatusPending:styles.payoutStatusNeutral}>{batches.some(b=>b.status==='PENDING_APPROVAL')?'Approval needed':'Review batches'}</em></a>
        </div>
      </nav>
      {!settings?.providerConfigured||!settings?.sourceAccountConfigured||!settings?.webhookConfigured?
        <div className={styles.payoutAlertWarning} role="status"><b>RazorpayX is not fully configured.</b> Configure the payout API credentials, source account identifier and payout webhook secret on the API server. Never paste secrets into this screen.</div>:null}
      {!settings?.collectionArrangementVerified&&<div className={styles.payoutAlertWarning} role="status"><b>Live transfers are blocked until legal review is recorded.</b> Select the entity receiving course-payment collections and the legal entity that owns the RazorpayX payout funding account. Only the platform owner can mark the arrangement verified.</div>}
      {(balances?.unsupportedCurrencies?.length??0)>0&&<div className={styles.payoutAlertWarning} role="alert"><b>Non-INR revenue is tracked separately.</b> Outstanding allocations use {balances?.unsupportedCurrencies?.join(', ')}. This RazorpayX workflow pays INR only, performs no currency conversion, and excludes non-INR amounts from INR totals and batches. Resolve those balances through a separately approved currency-compatible settlement route; they do not prevent an otherwise eligible INR batch.</div>}

      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>All institutions & companies</h2>
          <p>Active entities and any recipients with historical allocation records are listed, including zero-earning recipients. Historical balances remain visible if an entity is later suspended.</p></div>
          <button type="button" className={styles.secondary} onClick={()=>void load()} disabled={loading||busy}>Refresh balances</button></div>
        <div className={styles.payoutRecipientFilters}>
          <label>Search recipients<input aria-label="Search recipients" value={recipientSearch} onChange={e=>setRecipientSearch(e.target.value)}
            maxLength={160} placeholder="Institution, company, bank IFSC or blocker"/></label>
          <label>Transfer readiness<select aria-label="Filter by transfer readiness" value={recipientReadinessFilter} onChange={e=>setRecipientReadinessFilter(e.target.value)}>
            <option value="ALL">All recipients</option><option value="READY">Payout checks pass · review needed</option>
            <option value="BLOCKED">Blocked / action needed</option><option value="BELOW_MINIMUM">Below payout minimum</option>
            <option value="NO_PAYABLE_BALANCE">No payable balance</option><option value="MISSING_BANK">Bank account needs review</option>
            <option value="RECOVERY_DUE">Recovery due</option><option value="IN_PROGRESS">Reserved / transfer in progress</option>
          </select></label>
          <label>Currency<select aria-label="Filter recipients by currency" value={recipientCurrencyFilter} onChange={e=>setRecipientCurrencyFilter(e.target.value)}>
            <option value="ALL">All currencies</option>{recipientCurrencies.map(currency=><option key={currency} value={currency}>{currency}</option>)}
          </select></label>
          <span>{filteredBalances.length.toLocaleString('en-IN')} of {(balances?.beneficiaries||[]).length.toLocaleString('en-IN')} recipient balances</span>
        </div>
        {balances?.beneficiaries?.length?filteredBalances.length?<div className={styles.payoutRecipientGrid}>
          {filteredBalances.map((balance,i)=>{
            const readiness=balance.payout_readiness_status||'UNKNOWN';
            const readinessLabel=readiness==='READY_FOR_BATCH_REVIEW'?'Payout checks pass · tax review needed'
              :readiness==='NO_PAYABLE_BALANCE'?'No payable balance'
              :readiness==='BELOW_MINIMUM'?'Below minimum'
              :readiness==='BLOCKED'?'Blocked / action needed':'Status unavailable';
            const readinessClass=readiness==='NO_PAYABLE_BALANCE'?styles.payoutStatusNeutral:styles.payoutStatusPending;
            const accountLabel=balance.beneficiary_status==='MISSING'?'No approved bank account'
              :balance.bank_ifsc?balance.bank_ifsc+' · ****'+(balance.bank_account_last4||'????')
              :label(balance.beneficiary_status);
            return <article className={styles.payoutRecipientCard} key={balance.recipient_type+':'+balance.institution_id+':'+balance.legal_entity_id+':'+balance.currency+':'+i}>
              <div className={styles.payoutRecipientHead}>
                <div><strong>{balance.recipient_name}</strong><small>{balance.recipient_type==='INSTITUTION'?'Institution':'Company / platform entity'} · {balance.currency}{balance.recipient_status&&balance.recipient_status!=='ACTIVE'?' · '+balance.recipient_status+' — historical balance retained':''}</small></div>
                <span className={readinessClass}>{readinessLabel}</span>
              </div>
              <div className={styles.payoutRecipientMeta}>
                <span>{(balance.source_sale_count||0).toLocaleString('en-IN')} verified contributing sale(s)</span>
                <span className={balance.beneficiary_status==='ACTIVE'?styles.payoutStatusSuccess:styles.payoutStatusPending}>{accountLabel}</span>
              </div>
              <div className={styles.payoutRecipientFacts}>
                <div><span>Earned allocation</span><strong>{money(balance.earned_paise,balance.currency)}</strong></div>
                <div><span>Refunds</span><strong>{money(balance.refunded_paise,balance.currency)}</strong></div>
                <div><span>Confirmed charges</span><strong>{money(balance.charges_paise,balance.currency)}</strong></div>
                <div><span>Net earnings</span><strong>{money(balance.net_earned_paise,balance.currency)}</strong></div>
                <div><span>Already paid</span><strong>{money(balance.net_paid_paise,balance.currency)}</strong></div>
                <div><span>Reserved / holdback</span><strong>{money(balance.reserved_paise,balance.currency)} / {money(balance.refund_reserve_paise,balance.currency)}</strong></div>
                <div className={styles.payoutRecipientPayable}><span>Payable now</span><strong>{balance.payout_supported===false?'—':money(balance.payable_paise,balance.currency)}</strong></div>
                <div><span>Recovery due</span><strong className={balance.recovery_due_paise>0?styles.payoutStatusDanger:undefined}>{money(balance.recovery_due_paise,balance.currency)}</strong></div>
                <div><span>Fee-reconciliation sales</span><strong>{(balance.unreconciled_fee_order_count||0).toLocaleString('en-IN')}</strong></div>
              </div>
              <p className={styles.payoutRecipientReason}>{balance.payout_readiness_reason||balance.payout_support_reason||'Transfer-readiness detail is unavailable; refresh before taking action.'}</p>
              <div className={styles.payoutRecipientActions}>
                <button type="button" className={styles.secondarySmall} disabled={loading||busy} onClick={()=>void openBalanceLedger(balance)}>View source & ledger</button>
              </div>
            </article>;
          })}
        </div>:<p className={styles.payoutEmpty}>No recipients match these filters. Clear search or select “All recipients”.</p>
        :<p className={styles.payoutEmpty}>{loadIssues.some(issue=>issue.section==='Beneficiary balances')?'Balances could not be refreshed. Review the request failure above.':'No active institutions or company legal entities are available in the recipient catalogue.'}</p>}
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

      <section className={styles.panel} id="payout-rules">
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
            <button type="submit" className={styles.primary} disabled={busy||!settingsDirty}>Save payout rules</button></div>
        </form>
      </section>

      <section className={styles.panel} id="payout-legal-review">
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

      <section className={styles.panel} id="payout-bank-accounts">
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
          <label>Bank account number<input required type="password" autoComplete="off" inputMode="numeric" maxLength={18} value={beneficiaryForm.accountNumber} onChange={e=>setBeneficiaryForm(v=>({...v,accountNumber:e.target.value.replace(/\D/g,'').slice(0,18)}))}/><small>Used once for RazorpayX setup; never stored in the QuantaEdge database.</small></label>
          <label>IFSC<input required autoComplete="off" maxLength={11} value={beneficiaryForm.ifsc} onChange={e=>setBeneficiaryForm(v=>({...v,ifsc:e.target.value.toUpperCase().replace(/\s/g,'')}))} placeholder="e.g. ABCD0123456"/></label>
          <label>Contact email<input type="email" maxLength={320} value={beneficiaryForm.email} onChange={e=>setBeneficiaryForm(v=>({...v,email:e.target.value}))}/></label>
          <label>Contact phone<input type="tel" maxLength={20} value={beneficiaryForm.phone} onChange={e=>setBeneficiaryForm(v=>({...v,phone:e.target.value}))}/><small>Provide an email or phone number for the RazorpayX contact.</small></label>
          <div className={styles.payoutFormFooter}><span>New or changed bank accounts require independent approval before they can receive money.</span>
            <button type="submit" className={styles.primary} disabled={busy||!settings?.providerConfigured||!beneficiaryForm.recipientId||!beneficiaryForm.accountNumber}>Submit bank details</button></div>
        </form>
        <div className={styles.payoutList}>
          {beneficiaries.map(b=><article className={styles.payoutListRow} key={b.id}>
            <div className={styles.payoutRowMain}><strong>{b.recipient_name||b.beneficiary_name}</strong><span>{b.beneficiary_type} · {b.bank_ifsc} · account ending {b.bank_account_last4}</span>
              <small>{b.status==='PENDING_APPROVAL'?'Submitted by '+(b.created_by_name||'a staff member')+' · awaiting independent approval':b.status==='ACTIVE'?'Beneficiary is approved for payout':b.rejection_reason||'Provider status: '+label(b.status)}</small></div>
            <span className={statusClass(b.status)}>{label(b.status)}</span>
            <div className={styles.payoutRowActions}>
              {b.status==='PENDING_APPROVAL'&&b.canApprove&&<button type="button" className={styles.primarySmall} disabled={busy} onClick={()=>void action(()=>api(BASE+'/payout-beneficiaries/'+b.id+'/approve',{method:'POST',body:JSON.stringify({})}),'Beneficiary approved for future payout batches.')}>Approve</button>}
              {b.status==='PENDING_APPROVAL'&&!b.canApprove&&<small>Only a different authorized staff member with approval permission can review this account.</small>}
              {b.canApprove&&['PENDING_APPROVAL','PROVISIONING','RECONCILIATION_REQUIRED'].includes(b.status)&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>{setRejectingId(rejectingId===b.id?null:b.id);setRejectReason('');}}>Reject</button>}
            </div>
            {b.status==='ACTIVE'&&!beneficiaries.some(candidate=>candidate.replaces_beneficiary_id===b.id&&candidate.status!=='REJECTED')&&<button type="button" className={styles.secondarySmall} disabled={busy||!settings?.providerConfigured} onClick={()=>setSelectedReplacementId(selectedReplacementId===b.id?null:b.id)}>{selectedReplacementId===b.id?'Close replacement form':'Change bank account'}</button>}
            {b.status==='ACTIVE'&&beneficiaries.some(candidate=>candidate.replaces_beneficiary_id===b.id&&candidate.status!=='REJECTED')&&<small>A replacement request already exists for this account. Review that record instead of creating another.</small>}
            {selectedReplacementId===b.id&&b.status==='ACTIVE'&&<form className={styles.payoutReplacementForm} onSubmit={e=>{e.preventDefault();void requestBankReplacement(b);}}>
              <p>The existing approved account remains active while the new account is registered and reviewed by another authorized staff member.</p>
              <label>New account holder’s legal name<input required maxLength={240} value={(replacementDrafts[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}).holderName} onChange={e=>setReplacementDrafts(old=>({...old,[String(b.id)]:{...(old[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}),holderName:e.target.value}}))}/></label>
              <label>New bank account number<input required type="password" inputMode="numeric" autoComplete="off" maxLength={18} value={(replacementDrafts[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}).accountNumber} onChange={e=>setReplacementDrafts(old=>({...old,[String(b.id)]:{...(old[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}),accountNumber:e.target.value.replace(/\D/g,'').slice(0,18)}}))}/><small>Used for server-side RazorpayX setup only; not retained by QuantaEdge.</small></label>
              <label>New IFSC<input required maxLength={11} value={(replacementDrafts[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}).ifsc} onChange={e=>setReplacementDrafts(old=>({...old,[String(b.id)]:{...(old[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}),ifsc:e.target.value.toUpperCase().replace(/\s/g,'')}}))}/></label>
              <label>Contact email<input type="email" maxLength={320} value={(replacementDrafts[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}).email} onChange={e=>setReplacementDrafts(old=>({...old,[String(b.id)]:{...(old[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}),email:e.target.value}}))}/></label>
              <label>Contact phone<input type="tel" maxLength={20} value={(replacementDrafts[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}).phone} onChange={e=>setReplacementDrafts(old=>({...old,[String(b.id)]:{...(old[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}),phone:e.target.value}}))}/></label>
              <div className={styles.payoutFormFooter}><span>The new account will not be used until an independent approver accepts it. Any in-flight payout using the old account must resolve before the switch.</span><button type="submit" className={styles.primary} disabled={busy||!settings?.providerConfigured||!(replacementDrafts[String(b.id)]||{holderName:'',accountNumber:'',ifsc:'',email:'',phone:''}).accountNumber}>Submit replacement</button></div>
            </form>}
            {['PROVISIONING','RECONCILIATION_REQUIRED'].includes(b.status)&&<form className={styles.payoutRejectForm} onSubmit={e=>{e.preventDefault();void reconcileBeneficiaryProviderSetup(b);}}>
              <label>Re-enter bank account number to match an existing RazorpayX record
                <input type="password" inputMode="numeric" autoComplete="off" maxLength={18} value={recoveryAccounts[String(b.id)]||''} onChange={e=>setRecoveryAccounts(old=>({...old,[String(b.id)]:e.target.value.replace(/\D/g,'').slice(0,18)}))}/>
                <small>This is sent only to the API for an exact match. QuantaEdge will not create a duplicate fund account during reconciliation.</small>
              </label>
              <button type="submit" className={styles.secondarySmall} disabled={busy||!settings?.providerConfigured||!(recoveryAccounts[String(b.id)]||'')}>Find existing provider record</button>
            </form>}
            {rejectingId===b.id&&<form className={styles.payoutRejectForm} onSubmit={e=>{e.preventDefault();void rejectBeneficiary(b.id);}}>
              <label>Reason for rejection<input required minLength={5} maxLength={500} value={rejectReason} onChange={e=>setRejectReason(e.target.value)} placeholder="Explain what needs correcting"/></label>
              <button type="submit" className={styles.secondarySmall} disabled={busy}>Confirm rejection</button>
            </form>}
          </article>)}
          {!beneficiaries.length&&<p className={styles.payoutEmpty}>No payout bank accounts registered yet. Add an institution or company above.</p>}
        </div>
      </section>

      <section className={styles.panel} id="payout-fees">
        <div className={styles.panelHead}><div><h2>4. Confirm actual payment-processing charges</h2><p>Charges reduce the distributable pool before shares. Use the actual Razorpay settlement statement/API result—not an estimate. Until charges are reconciled, the sale cannot enter a payout batch.</p></div><span className={feeOrders.length?styles.payoutStatusPending:styles.payoutStatusSuccess}>{feeOrders.length} shown · {overview?.unreconciledFees??'—'} pending</span></div>
        <form className={styles.payoutQueueFilters} onSubmit={e=>{e.preventDefault();setFeeOffset(0);setFeeSearch(feeSearchDraft.trim());setFeeFrom(feeFromDraft);setFeeThrough(feeThroughDraft);}}>
          <label htmlFor="payout-fee-search">Search by order, course, institution or provider payment ID</label>
          <div><input id="payout-fee-search" value={feeSearchDraft} maxLength={100} onChange={e=>setFeeSearchDraft(e.target.value)} placeholder="e.g. order number or payment reference"/></div>
          <div className={styles.payoutQueueDates}><label>Verified from<input type="date" value={feeFromDraft} max={feeThroughDraft||undefined} onChange={e=>setFeeFromDraft(e.target.value)}/></label><label>Verified through<input type="date" value={feeThroughDraft} min={feeFromDraft||undefined} onChange={e=>setFeeThroughDraft(e.target.value)}/></label></div>
          <div><button type="submit" className={styles.secondarySmall} disabled={loading||busy}>Apply filters</button><button type="button" className={styles.secondarySmall} disabled={loading||busy||(!feeSearch&&!feeFrom&&!feeThrough&&!feeSearchDraft&&!feeFromDraft&&!feeThroughDraft)} onClick={()=>{setFeeSearchDraft('');setFeeThroughDraft('');setFeeFromDraft('');setFeeSearch('');setFeeFrom('');setFeeThrough('');setFeeOffset(0);}}>Clear filters</button></div>
        </form>
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
        {(feeOffset>0||feeHasMore)&&<div className={styles.payoutQueuePagination}><span>Page {Math.floor(feeOffset/50)+1} · 50 records per page</span><div><button type="button" className={styles.secondarySmall} disabled={loading||busy||feeOffset===0} onClick={()=>setFeeOffset(Math.max(0,feeOffset-50))}>Previous</button><button type="button" className={styles.secondarySmall} disabled={loading||busy||!feeHasMore} onClick={()=>setFeeOffset(feeOffset+50)}>Next</button></div></div>}
        {!feeOrders.length&&<p className={styles.payoutEmpty}>{loadIssues.some(issue=>issue.section==='Fee reconciliation queue')?'The fee queue could not be refreshed. The warning above contains the request failure.':'No outstanding fee confirmations were returned for this search. Refresh after new payments are captured.'}</p>}
      </section>


      <section className={styles.panel}>
        <div className={styles.panelHead}><div><h2>5. Reviewed corrections to confirmed fees</h2><p>Original provider fee records are immutable. A correction records the old and proposed values, a provider reference and a reason; only a different authorized approver can apply it to the same allocation ledger.</p></div><span className={pendingFeeAdjustments.length?styles.payoutStatusPending:styles.payoutStatusNeutral}>{pendingFeeAdjustments.length} awaiting review</span></div>
        {pendingFeeAdjustments.map(item=><article className={styles.payoutListRow} key={item.id}>
          <div className={styles.payoutRowMain}><strong>Order #{item.order_id} · {item.course_title}</strong><span>{item.provider_payment_id} · requested by {item.requested_by_name||('staff #'+item.requested_by_staff_id)}</span>
            <small>Previously {money(item.previous_processing_fee_paise)} + tax {money(item.previous_fee_tax_paise)} · proposed {money(item.requested_processing_fee_paise)} + tax {money(item.requested_fee_tax_paise)}</small>
            <small>Evidence: {item.settlement_reference} · {item.source_type} · {item.created_at?new Date(item.created_at).toLocaleString('en-IN'):'date unavailable'}</small><small>Reason: {item.reason}</small></div>
          {item.canReview?<div className={styles.payoutFeeAdjustmentReview}><label>Review rationale<textarea rows={2} minLength={5} maxLength={500} value={feeAdjustmentReviewReasons[String(item.id)]||''} onChange={e=>setFeeAdjustmentReviewReasons(old=>({...old,[String(item.id)]:e.target.value}))} placeholder="Explain why this correction is accepted or rejected"/></label><div className={styles.payoutRowActions}><button type="button" className={styles.primarySmall} disabled={busy} onClick={()=>void reviewFeeAdjustment(item,'APPROVED')}>Approve correction</button><button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>void reviewFeeAdjustment(item,'REJECTED')}>Reject correction</button></div></div>:<p className={styles.payoutHint}>The submitter cannot review their own correction. Another authorized approver must record a decision.</p>}
        </article>)}
        {!pendingFeeAdjustments.length&&<p className={styles.payoutEmpty}>{loadIssues.some(issue=>issue.section==='Pending fee corrections')?'Pending corrections could not be loaded. Review the request failure above.':'No provider-fee corrections are waiting for review.'}</p>}
        <div className={styles.payoutQueueFilters}><label htmlFor="fee-adjustment-search">Find an existing confirmed fee record</label><form onSubmit={e=>{e.preventDefault();setFeeAdjustmentOffset(0);setFeeAdjustmentSearch(feeAdjustmentSearchDraft.trim());}}><input id="fee-adjustment-search" value={feeAdjustmentSearchDraft} maxLength={100} onChange={e=>setFeeAdjustmentSearchDraft(e.target.value)} placeholder="Order, course, institution or payment reference"/><button type="submit" className={styles.secondarySmall} disabled={loading||busy}>Search</button></form></div>
        {feeAdjustmentCandidates.map(candidate=>{const key=String(candidate.order_id),draft=feeAdjustmentDrafts[key]||{fee:'',tax:'',reference:'',reason:'',source:'SETTLEMENT_REPORT'},selected=selectedFeeAdjustmentOrderId===candidate.order_id;return <article className={styles.payoutListRow} key={candidate.order_id}>
          <div className={styles.payoutRowMain}><strong>Order #{candidate.order_id} · {candidate.course_title}</strong><span>{candidate.source_institution} · {candidate.provider_payment_id} · {candidate.currency}</span><small>Current confirmed fee {money(candidate.effective_processing_fee_paise,candidate.currency)} + tax {money(candidate.effective_fee_tax_paise,candidate.currency)} · reference {candidate.effective_reference}</small>
            {candidate.pending_adjustment_id&&<small>A correction request for this order is already awaiting review.</small>}
            {!candidate.can_adjust&&!candidate.pending_adjustment_id&&<small>A payout is currently reserved/in progress; finish or resolve it before requesting a correction.</small>}
          </div>
          {candidate.can_adjust&&!candidate.pending_adjustment_id&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>setSelectedFeeAdjustmentOrderId(selected?null:candidate.order_id)}>{selected?'Close correction':'Request correction'}</button>}
          {selected&&<form className={styles.payoutFeeAdjustmentForm} onSubmit={e=>{e.preventDefault();void requestFeeAdjustment(candidate);}}>
            <p>Current: {money(candidate.effective_processing_fee_paise,candidate.currency)} + tax {money(candidate.effective_fee_tax_paise,candidate.currency)}. Enter the values from the corrected settlement statement; do not estimate.</p>
            <label>Corrected processing fee (₹)<input type="number" min="0" max="99999999.99" step="0.01" required value={draft.fee} onChange={e=>setFeeAdjustmentDrafts(old=>({...old,[key]:{...draft,fee:e.target.value}}))}/></label>
            <label>Corrected fee tax (₹)<input type="number" min="0" max="99999999.99" step="0.01" required value={draft.tax} onChange={e=>setFeeAdjustmentDrafts(old=>({...old,[key]:{...draft,tax:e.target.value}}))}/></label>
            <label>Corrected settlement / statement reference<input required minLength={3} maxLength={180} value={draft.reference} onChange={e=>setFeeAdjustmentDrafts(old=>({...old,[key]:{...draft,reference:e.target.value}}))}/></label>
            <label>Evidence source<select value={draft.source} onChange={e=>setFeeAdjustmentDrafts(old=>({...old,[key]:{...draft,source:e.target.value}}))}><option value="SETTLEMENT_REPORT">Settlement report</option><option value="PROVIDER_API">Provider API</option><option value="MANUAL_RECONCILIATION">Manual statement reconciliation</option></select></label>
            <label>Reason for correction<textarea required minLength={5} maxLength={500} rows={3} value={draft.reason} onChange={e=>setFeeAdjustmentDrafts(old=>({...old,[key]:{...draft,reason:e.target.value}}))} placeholder="e.g. The settlement report contains a corrected processing fee and tax"/></label>
            <div className={styles.payoutFormFooter}><span>The current ledger remains unchanged until another authorized staff member approves.</span><button type="submit" className={styles.primary} disabled={busy}>Submit for independent review</button></div>
          </form>}
        </article>})}
        {(feeAdjustmentOffset>0||feeAdjustmentQueueHasMore)&&<div className={styles.payoutQueuePagination}><span>Page {Math.floor(feeAdjustmentOffset/50)+1} · 50 records per page</span><div><button type="button" className={styles.secondarySmall} disabled={loading||busy||feeAdjustmentOffset===0} onClick={()=>setFeeAdjustmentOffset(Math.max(0,feeAdjustmentOffset-50))}>Previous</button><button type="button" className={styles.secondarySmall} disabled={loading||busy||!feeAdjustmentQueueHasMore} onClick={()=>setFeeAdjustmentOffset(feeAdjustmentOffset+50)}>Next</button></div></div>}
        {!feeAdjustmentCandidates.length&&<p className={styles.payoutEmpty}>{loadIssues.some(issue=>issue.section==='Fee-adjustment candidates')?'Confirmed fee records could not be loaded. Review the request failure above.':'No confirmed fee records match this search.'}</p>}
      </section>
      <section className={styles.panel} id="payout-batches">
        <div className={styles.panelHead}><div><h2>6. Monthly payout batches</h2><p>Prepare the previous completed calendar month. The amount is calculated from earned allocations, confirmed fee deductions, processed refunds, existing reservations, paid transfers and the configured refund reserve.</p></div>
          <button type="button" className={styles.primary} onClick={()=>void createBatch()} disabled={busy||loading||!payoutEligible||!settings?.configured||!overview||(overview.unreconciledFeesForNextBatch??0)>0||(overview.pendingFeeAdjustmentsForNextBatch??0)>0}>Prepare last month’s batch</button></div>
        {!payoutEligible&&<p className={styles.payoutHint}>Preparation is disabled until the RazorpayX API credentials and source account are configured on the API server.</p>}
        {feeOrders.length>0&&<p className={styles.payoutHint}>The queue shows outstanding fee confirmations across all periods. Use search and verified-date filters; only missing records affecting the month being prepared block that batch.</p>}
        {(overview?.payoutFeeReconciliationPending??0)>0&&<p className={styles.payoutHint}>There are {overview?.payoutFeeReconciliationPending} completed payout batch(es) with unconfirmed RazorpayX transfer charges. Open each batch, record its actual provider fee and fee tax from the settlement statement, and reconcile it before preparing another month.</p>}
        {(overview?.unreconciledFeesForNextBatch??0)>0&&<p className={styles.payoutHint}>{overview?.unreconciledFeesForNextBatch} missing payment-fee record(s) affect the period being prepared. Reconcile the listed payments using actual provider evidence first; later-period orders do not block this month.</p>}
        {(overview?.pendingFeeAdjustmentsForNextBatch??0)>0&&<p className={styles.payoutHint}>{overview?.pendingFeeAdjustmentsForNextBatch} fee-correction request(s) affect the period being prepared. Have an independent approver accept or reject them before preparing the batch. Other corrections for later periods do not block this month.</p>}
        <div className={styles.payoutList}>
          {batches.map(batch=><article className={styles.payoutListRow} key={batch.id}>
            <div className={styles.payoutRowMain}><strong>{new Date(batch.period_start+'T12:00:00').toLocaleDateString('en-IN',{month:'long',year:'numeric'})} · Batch #{batch.id}</strong>
              <span>{batch.beneficiary_count} beneficiaries · {money(batch.total_amount_paise)} total</span>
              <small>{batch.paid_count??0} transfers confirmed paid · {batch.exception_count??0} exceptions · prepared by {batch.created_by_name||'a staff member'}</small>
              <small>{batch.provider_fee_reconciled_at?('Payout charges reconciled: '+money(batch.provider_fee_total_paise)+' + fee tax '+money(batch.provider_fee_tax_total_paise)):'Payout transfer fees await confirmed provider values or statement reconciliation.'}</small>
              {(batch.platform_borne_overage_paise??0)>0&&<small>Platform-borne fee overage: {money(batch.platform_borne_overage_paise)}. Full provider charges are recorded without making beneficiary balances negative.</small>}</div>
            <span className={statusClass(batch.status)}>{label(batch.status)}</span>
            <div className={styles.payoutRowActions}>
              <button type="button" className={styles.secondarySmall} onClick={()=>void openBatch(batch.id)}>View details</button>
              {batch.status==='PENDING_APPROVAL'&&batch.canApprove&&<button type="button" className={styles.primarySmall} disabled={busy||!payoutEligible} onClick={()=>void openApprovalReview(batch.id)}>Review & approve</button>}
              {batch.status==='PENDING_APPROVAL'&&!batch.canApprove&&<small>Prepared by {batch.created_by_name||'a staff member'}. A different authorized approver must review and send these transfers.</small>}
            </div>
          </article>)}
          {!batches.length&&<p className={styles.payoutEmpty}>No payout batches have been prepared yet.</p>}
        </div>
        {selectedBatch&&<section className={styles.payoutBatchDetail}>
          <div className={styles.panelHead}><div><h3>Batch #{selectedBatch.id} · {selectedBatch.period_start} to {selectedBatch.period_end}</h3>
            <p>{money(selectedBatch.total_amount_paise)} · {selectedBatch.beneficiary_count} beneficiary transfers · {label(selectedBatch.status)}</p></div><button type="button" className={styles.secondarySmall} onClick={()=>setSelectedBatch(null)}>Close details</button></div>
          {(selectedBatch.platform_borne_overage_paise??0)>0&&<p className={styles.payoutAlertWarning} role="status">Platform-borne provider fee overage: {money(selectedBatch.platform_borne_overage_paise)}. The actual charge is recorded; this excess was not deducted from beneficiaries because their saved allocation weights could not safely absorb it.</p>}
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
              {(!['PROCESSED','REVERSED','FAILED','REJECTED'].includes(p.status)||(Boolean(p.provider_payout_id)&&Boolean(p.status_details?.startsWith('RazorpayX'))))&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>void refreshPayout(p)}>Refresh status</button>}
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
    {ledgerBalance&&<div className={styles.payoutConfirmOverlay} role="presentation"><section className={styles.payoutLedgerDialog} role="dialog" aria-modal="true" aria-labelledby="payout-ledger-title">
      <div className={styles.panelHead}><div><h2 id="payout-ledger-title">Recipient ledger history</h2><p>{ledgerBalance.recipient_name} · {ledgerBalance.currency}</p></div><button type="button" className={styles.secondarySmall} onClick={()=>{setLedgerBalance(null);setLedgerRows([]);setLedgerError('');}}>Close</button></div>
      {ledgerBalance.recovery_due_paise>0&&<p className={styles.payoutAlertWarning}>Recovery due: {money(ledgerBalance.recovery_due_paise,ledgerBalance.currency)}. Review refund, fee correction and transfer events below. Future net payable earnings offset this balance; this workflow does not initiate an automatic bank debit.</p>}
      {ledgerError&&<div className={styles.payoutAlertError} role="alert">{ledgerError}</div>}
      {ledgerLoading&&<p className={styles.payoutHint}>Loading ledger events…</p>}
      {!ledgerLoading&&!ledgerError&&!ledgerRows.length&&<p className={styles.payoutEmpty}>No allocation events were found for this recipient and currency.</p>}
      <div className={styles.payoutLedgerEventList}>{ledgerRows.map(event=><article key={event.id}>
        <div className={styles.payoutLedgerEventHead}><strong>{ledgerEventLabel(event.event_type)}</strong><b>{money(event.amount_paise,event.currency)}</b></div>
        <small>{event.created_at?new Date(event.created_at).toLocaleString('en-IN'):'Time unavailable'} · Order #{event.order_id} · Allocation #{event.allocation_id}</small>
        {event.course_title&&<span>{event.course_title}</span>}
        {event.source_institution_name&&<small>Course source institution: {event.source_institution_name}</small>}
        {event.marketplace_institution_name&&<small>Marketplace institution: {event.marketplace_institution_name}</small>}
        {(!event.source_institution_name||!event.marketplace_institution_name)&&<small>Institution attribution is unavailable for this event; inspect the order record before final reconciliation.</small>}
        {(event.provider_payment_id||event.payment_reference)&&<small>{event.provider_payment_id?'Payment '+event.provider_payment_id:''}{event.payment_reference?(event.provider_payment_id?' · ':'')+'Reference '+event.payment_reference:''}</small>}
        {event.payout_id&&<small>QuantaEdge transfer #{event.payout_id}{event.payout_batch_id?' · batch #'+event.payout_batch_id:''}{event.payout_period_start?' · period '+new Date(event.payout_period_start+'T12:00:00').toLocaleDateString('en-IN',{month:'short',year:'numeric'}):''}{event.payout_status?' · '+label(event.payout_status):''}{event.transfer_reference?' · UTR '+event.transfer_reference:''}</small>}
        {(event.fee_adjustment_id||event.settlement_reference||event.reason)&&<div className={styles.payoutLedgerEvidence}>{event.fee_adjustment_id&&<small>Fee correction #{event.fee_adjustment_id}</small>}{event.settlement_reference&&<small>Evidence: {event.settlement_reference}</small>}{event.reason&&<small>Reason: {event.reason}</small>}</div>}
      </article>)}</div>
      {(ledgerOffset>0||ledgerHasMore)&&<div className={styles.payoutQueuePagination}><span>{Math.floor(ledgerOffset/50)+1} · 50 events per page</span><div><button type="button" className={styles.secondarySmall} disabled={ledgerLoading||ledgerOffset===0} onClick={()=>void openBalanceLedger(ledgerBalance,Math.max(0,ledgerOffset-50))}>Previous</button><button type="button" className={styles.secondarySmall} disabled={ledgerLoading||!ledgerHasMore} onClick={()=>void openBalanceLedger(ledgerBalance,ledgerOffset+50)}>Next</button></div></div>}
    </section></div>}
    {approvalBatch&&<div className={styles.payoutConfirmOverlay} role="presentation"><section className={styles.payoutApprovalDialog} role="dialog" aria-modal="true" aria-labelledby="payout-approval-title">
      <div className={styles.panelHead}><div><h2 id="payout-approval-title">Review monthly transfer batch</h2><p>This approval sends the listed bank transfers to RazorpayX. It cannot be treated as a draft save.</p></div><button type="button" className={styles.secondarySmall} onClick={()=>{setApprovalBatch(null);setApprovalConfirmed(false);}}>Cancel</button></div>
      <dl className={styles.payoutApprovalSummary}><div><dt>Period</dt><dd>{new Date(approvalBatch.period_start+'T12:00:00').toLocaleDateString('en-IN',{month:'long',year:'numeric'})}</dd></div><div><dt>Total to send</dt><dd>{money(approvalBatch.total_amount_paise,approvalBatch.currency)}</dd></div><div><dt>Transfers</dt><dd>{approvalBatch.payouts?.length??approvalBatch.beneficiary_count}</dd></div><div><dt>Exceptions</dt><dd>{approvalBatch.exception_count??0}</dd></div><div><dt>Prepared by</dt><dd>{approvalBatch.created_by_name||'Finance staff'}</dd></div><div><dt>Legal / provider readiness</dt><dd>{payoutEligible?'Ready':'Blocked'}</dd></div></dl>
      <div className={styles.payoutApprovalTransfers}>{(approvalBatch.payouts||[]).map(p=><div key={p.id}><span><strong>{p.recipient_name||p.beneficiary_name}</strong><small>{p.bank_ifsc} · account ending {p.bank_account_last4} · {label(p.status)}</small></span><strong>{money(p.amount_paise,p.currency)}</strong></div>)}</div>
      {(!approvalBatch.payouts||approvalBatch.payouts.length===0)&&<p className={styles.payoutHint}>Transfer details have not loaded. Cancel and reopen the batch; no approval can be confirmed without its full transfer list.</p>}
      <label className={styles.payoutConfirmCheck}><input type="checkbox" checked={approvalConfirmed} onChange={e=>setApprovalConfirmed(e.target.checked)}/>I checked the period, total, recipients, bank-account last four digits and every transfer. I understand that confirming sends these transfers for processing.</label>
      <div className={styles.payoutFormFooter}><span>Prepared by {approvalBatch.created_by_name||'another staff member'} · maker/checker rules still apply on the server.</span><button type="button" className={styles.primary} disabled={busy||!payoutEligible||!approvalBatch.canApprove||!approvalConfirmed||!(approvalBatch.payouts?.length)} onClick={()=>void approveBatch(approvalBatch)}>Approve & send {approvalBatch.payouts?.length??0} transfers</button></div>
    </section></div>}
  </section>;
}
