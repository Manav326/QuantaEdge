'use client';

import { apiFetch } from '../components/apiClient';
import { notifyUser } from '../components/GlobalNotificationHost';
import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent, type CSSProperties } from 'react';
import styles from './Finance.module.css';
import PayoutsPanel from './PayoutsPanel';

type Institution = { id:number; tenant_code:string; display_name:string; status:string };
type LegalEntity = { id:number; entity_kind:string; institution_id?:number|null; institution_name?:string|null; legal_name:string; trade_name?:string|null; contact_email?:string|null; contact_phone?:string|null; registered_address_json?:string|null; invoice_series_code?:string; einvoice_applicability?:'UNREVIEWED'|'NOT_REQUIRED'|'REQUIRED'; einvoice_applicability_reviewed_at?:string|null; einvoice_applicability_review_note?:string|null; status:string; active_gst_registrations?:number };
type Offer = { offer_id:number; institution_id:number; institution_name:string; course_identity_public_id:string; stable_course_key:string; course_code:string; course_name:string; content_version_no:number; assignment_id:number; assignment_source:string; release_id:number; release_version:number; release_state:string; current_candidate:boolean; publicly_sellable:boolean; price_paise:number; currency:string; offer_status:string; legal_entity_id?:number|null; legal_seller_name?:string|null; latest_tax_profile_id?:number|null; tax_profile_status?:string|null; tax_treatment?:string|null; latest_share_policy_status?:string|null };
type Registration = { id:number; legal_entity_id:number; legal_entity_name:string; gstin:string; state_code:string; status:string; effective_from:string };
type Catalog = { institutions:Institution[]; legalEntities:LegalEntity[]; offers:Offer[]; registrations:Registration[] };
function offerLifecycleLabel(offer:Offer):string {
  if (offer.publicly_sellable && offer.current_candidate) return 'CURRENT · LIVE';
  if (offer.publicly_sellable) return 'LIVE · REPLACEMENT PENDING';
  if (offer.current_candidate) return 'CURRENT CANDIDATE';
  return 'OBSOLETE — DO NOT CONFIGURE';
}
type Policy = { id:number; institution_offer_id:number; policy_version:number; currency:string; status:string; institution_name:string; course_name:string; recipient_count:number; effective_from:string; effective_until?:string|null };
type PolicyRecipient = { recipient_type?:string; institution_id?:number|null; legal_entity_id?:number|null; recipient_id?:number; recipient_name?:string; calculation_type:string; percentage_basis_points?:number|null; flat_amount_paise?:number|null };
type DiscountOffer = { id:number; institution_offer_id:number; code:string; version:number; currency:string; discount_type:string; percentage_basis_points?:number|null; flat_amount_paise?:number|null; max_discount_paise?:number|null; min_listed_price_paise:number; max_redemptions?:number|null; max_redemptions_per_purchaser?:number|null; effective_from:string; effective_until?:string|null; status:string; institution_name:string; course_name:string; redemption_count:number; offer_price_paise:number };
type PolicyDetail = Policy & { recipients:PolicyRecipient[]; remainder_recipient_type?:string; remainder_institution_id?:number|null; remainder_legal_entity_id?:number|null; remainder_recipient_name?:string };
type Template = { id:number; scope:string; legal_entity_id?:number|null; internal_name:string; template_version_id?:number|null; version_no?:number|null; version_status?:string; design_definition_json?:string|null; published_at?:string|null };
type FinancialDocument = { id:number; document_type:string; document_number:string; issued_at:string; currency:string; total_value_paise:number; tax_value_paise:number; source_type:string; source_record_key:string; pdf_available:boolean };
type RefundRecord = { id:number; public_id:string; course_payment_order_id:number; requested_amount_paise:number; currency:string; reason:string; status:string; provider_status?:string|null; provider_refund_id?:string|null; requested_at:string; settled_at?:string|null; credit_note_document_id?:number|null; credit_note_number?:string|null; message?:string };

type CoursePaymentOrder = {
  local_order_id:number; institution_id:number; institution_name:string; course_id:number; course_title:string;
  student_id:number; learner_name?:string|null; purchaser_user_id?:number|null; target_student_id?:number|null;
  recipient_type:string; status:string; failure_code?:string|null; currency:string;
  listed_price_paise?:number|null; discount_amount_paise?:number|null; discount_reference?:string|null;
  offer_price_paise:number; captured_amount_paise?:number|null; captured_at?:string|null;
  provider_order_id?:string|null; provider_payment_id?:string|null; created_at:string; updated_at:string;
  verified_at?:string|null; fiscal_document_id?:number|null; invoice_number?:string|null;
  last_operation_event?:string|null; last_resolution_code?:string|null; last_operation_message?:string|null;
  last_operation_at?:string|null; resolution_code:string; recommended_action:string; resolution_message:string;
};
type PaymentOperationEvent = {
  id:number; event_type:string; resolution_code:string; message:string; provider_order_id?:string|null;
  provider_status?:string|null; details_json?:string|null; actor_staff_id:number; created_at:string;
};
type ProviderEventRecord = {
  id:number; provider_event_id:string; event_type:string; local_order_id?:number|null; course_payment_refund_id?:number|null;
  provider_order_id?:string|null; provider_payment_id?:string|null; provider_refund_id?:string|null; payload_sha256:string;
  processing_status:string; status_message:string; details_json?:string|null; received_at:string; processed_at?:string|null;
};
type PaymentReconciliationResult = {
  status:string; message?:string; resolutionCode?:string; resolutionMessage?:string; canRetryCheckout?:boolean;
  entitled?:boolean; invoiceNumber?:string; providerOrderId?:string; providerOrderStatus?:string;
  providerAmountPaidPaise?:number; providerAttemptCount?:number;
  paymentAttempts?:Array<{paymentId:string;status:string;amountPaise:number;currency:string;matchesExpectedAmountAndCurrency:boolean;
    method?:string;errorCode?:string;errorReason?:string;errorDescription?:string}>;
};
type PaymentTraceStep = {
  key:string; title:string; status:'COMPLETE'|'BLOCKED'|'PENDING'|'UNKNOWN'|'NOT_REACHED';
  summary:string; details?:string[]; occurredAt?:string|null;
};
type PaymentStatusTrace = {
  orderId:number; courseId:number; courseTitle:string; institutionName:string; localOrderStatus:string;
  createdAt?:string|null; checkedAt:string; overallStatus:string; readOnly:boolean; message:string;
  steps:PaymentTraceStep[]; blockers:PaymentTraceStep[]; pendingSteps:PaymentTraceStep[];
  provider?:{
    providerQueryAvailable?:boolean; providerStatus?:string; configuredProviderMode?:string; matchedProviderMode?:string;
    providerOrderStatus?:string; providerAmountPaidPaise?:number; providerAttemptCount?:number;
    providerAmountCurrencyMatch?:boolean; message?:string; credentialModesTried?:string[];
    paymentAttempts?:Array<{paymentId:string;status:string;amountPaise:number;currency:string;matchesExpectedAmountAndCurrency:boolean;
      method?:string;errorCode?:string;errorReason?:string;errorDescription?:string}>;
  };
  financialFacts?:{listedPricePaise:number;discountAmountPaise:number;payablePaise:number;capturedAmountPaise:number;
    currency:string;financialSnapshotsPresent:boolean;buyerSnapshotPresent:boolean};
};
type Overview = {
  legalEntities:number; activeTaxProfiles:number; pendingTaxProfiles:number; approvedSharePolicies:number;
  pendingSharePolicies:number; issuedDocuments:number; allocatedPaise:number;
  capturedSalesPaise:number;processedRefundsPaise:number;pendingRefundsPaise:number;pendingRefundCount:number;
  netCollectedAfterRefundsPaise:number;invoicedOutputTaxPaise:number;processedCreditNoteTaxPaise:number;
  netOutputTaxBeforeInputCreditsPaise:number;confirmedProcessorFeesPaise:number;confirmedProcessorFeeTaxPaise:number;
  netPoolAfterTaxRefundsAndFeesPaise:number;paymentFeeReconciliationPending:number
};
type Design = { primaryColor:string; accentColor:string; surfaceStyle:string; layout:string; showLogo:boolean; footerText:string };
type RecipientDraft = { recipientType:'INSTITUTION'|'COMPANY'; recipientId:string; calculationType:'PERCENTAGE'|'FLAT'; percentage:string; flatRupees:string };
type TaxForm = { offerId:string; legalEntityId:string; serviceClassificationCode:string; taxTreatment:string; gstPercent:string; cessPercent:string; priceIncludesTax:boolean; placeOfSupplyRule:string; effectiveFrom:string };
type FinanceTab = 'overview'|'entities'|'tax'|'distribution'|'discounts'|'studio'|'documents'|'payments'|'payouts';
const FINANCE_TABS:FinanceTab[]=['overview','entities','tax','distribution','discounts','studio','documents','payments','payouts'];

const BASE='/api/v1/hrms/platform/finance';
const DEFAULT_DESIGN:Design={primaryColor:'#F97316',accentColor:'#0F766E',surfaceStyle:'WARM_PAPER',layout:'EDITORIAL',showLogo:true,footerText:'Thank you for learning with us.'};
async function api<T>(path:string, init:RequestInit={}):Promise<T>{
  const headers=new Headers(init.headers);
  headers.set('X-QE-Workspace','HRMS'); headers.set('Accept','application/json');
  if(init.body&&!headers.has('Content-Type')) headers.set('Content-Type','application/json');
  const response=await apiFetch(path,{...init,headers,credentials:'same-origin',cache:'no-store'});
  const raw=await response.text(); let data:any={};
  try{data=raw?JSON.parse(raw):{};}catch{data={message:raw};}
  if(!response.ok) throw new Error(String(data.message||data.detail||data.error||'Finance request failed ('+response.status+')'));
  return data as T;
}
const rupees=(paise:number|undefined,currency='INR')=>typeof paise!=='number'?'—':new Intl.NumberFormat('en-IN',{style:'currency',currency}).format(paise/100);
const asIso=(value:string)=>new Date(value).toISOString();
function parseRupeesPaise(raw:string):number|null{
  const value=raw.trim();
  if(!/^\\d{1,9}(?:\\.\\d{1,2})?$/.test(value))return null;
  const parts=value.split('.');
  const paise=Number(parts[0])*100+Number(((parts[1]||'')+'00').slice(0,2));
  return Number.isSafeInteger(paise)&&paise>=100?paise:null;
}
function parseDesign(value?:string|null):Design{
  try { const raw=value?JSON.parse(value):{}; return {...DEFAULT_DESIGN,...raw}; } catch { return {...DEFAULT_DESIGN}; }
}
const parseAddress=(value?:string|null):Record<string,string>=>{try{const raw=value?JSON.parse(value):{};return raw&&typeof raw==='object'&&!Array.isArray(raw)?raw as Record<string,string>:{};}catch{return {};}};
const addressText=(value?:string|null)=>{const a=parseAddress(value);return [a.line1,a.city,a.state,a.postalCode].filter(Boolean).join(', ')};

export default function FinancePage(){
  const [tab,setTab]=useState<FinanceTab>('overview');
  const launchOfferIdFromUrl=useRef<string|null>(null);
  const marketplaceSetupFromUrl=useRef(false);
  const [marketplaceSetupContext,setMarketplaceSetupContext]=useState<{institutionId:number|null;courseName:string;offerId:string}|null>(null);
  const [catalog,setCatalog]=useState<Catalog|null>(null);
  const [overview,setOverview]=useState<Overview|null>(null);
  const [policies,setPolicies]=useState<Policy[]>([]);
  const [discounts,setDiscounts]=useState<DiscountOffer[]>([]);
  const [templates,setTemplates]=useState<Template[]>([]);
  const [documents,setDocuments]=useState<FinancialDocument[]>([]);
  const [seriesDrafts,setSeriesDrafts]=useState<Record<string,string>>({});
  const [einvoiceDrafts,setEinvoiceDrafts]=useState<Record<string,string>>({});
  const [einvoiceNotes,setEinvoiceNotes]=useState<Record<string,string>>({});
  const [downloadingDocumentId,setDownloadingDocumentId]=useState<number|null>(null);
  const [selectedRefundDocId,setSelectedRefundDocId]=useState<number|null>(null);
  const [refundDraft,setRefundDraft]=useState({amountRupees:'',reason:''});
  const [refundIdempotencyKey,setRefundIdempotencyKey]=useState('');
  const [refundHistory,setRefundHistory]=useState<RefundRecord[]>([]);
  const [refundResult,setRefundResult]=useState<RefundRecord|null>(null);
  const [refundBusy,setRefundBusy]=useState(false);
  const [refundHistoryLoading,setRefundHistoryLoading]=useState(false);
  const [paymentOrders,setPaymentOrders]=useState<CoursePaymentOrder[]>([]);
  const [paymentTotal,setPaymentTotal]=useState(0);
  const [paymentSearch,setPaymentSearch]=useState('');
  const [paymentStatusFilter,setPaymentStatusFilter]=useState('');
  const [paymentOffset,setPaymentOffset]=useState(0);
  const [paymentLoading,setPaymentLoading]=useState(false);
  const [selectedPaymentOrderId,setSelectedPaymentOrderId]=useState<number|null>(null);
  const [paymentEvents,setPaymentEvents]=useState<PaymentOperationEvent[]>([]);
  const [paymentEventsLoading,setPaymentEventsLoading]=useState(false);
  const [paymentActionLoadingId,setPaymentActionLoadingId]=useState<number|null>(null);
  const [paymentActionResult,setPaymentActionResult]=useState<PaymentReconciliationResult|null>(null);
  const [statusTrace,setStatusTrace]=useState<PaymentStatusTrace|null>(null);
  const [statusTraceLoadingId,setStatusTraceLoadingId]=useState<number|null>(null);
  const [statusTraceError,setStatusTraceError]=useState('');
  const [providerEvents,setProviderEvents]=useState<ProviderEventRecord[]>([]);
  const [providerEventTotal,setProviderEventTotal]=useState(0);
  const [providerEventSearch,setProviderEventSearch]=useState('');
  const [providerEventStatusFilter,setProviderEventStatusFilter]=useState('');
  const [providerEventOffset,setProviderEventOffset]=useState(0);
  const [providerEventLoading,setProviderEventLoading]=useState(false);
  const [providerEventWarning,setProviderEventWarning]=useState('');
  const [selectedProviderEvents,setSelectedProviderEvents]=useState<ProviderEventRecord[]>([]);
  const [selectedProviderEventsLoading,setSelectedProviderEventsLoading]=useState(false);
  const [selectedProviderEventsWarning,setSelectedProviderEventsWarning]=useState('');
  const [busy,setBusy]=useState(false);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [selectedOfferId,setSelectedOfferId]=useState('');
  const [selectedPolicyId,setSelectedPolicyId]=useState('');
  const [policyDetail,setPolicyDetail]=useState<PolicyDetail|null>(null);
  const [recipients,setRecipients]=useState<RecipientDraft[]>([]);
  const [policyRemainderType,setPolicyRemainderType]=useState<'INSTITUTION'|'COMPANY'>('COMPANY');
  const [policyRemainderId,setPolicyRemainderId]=useState('');
  const [entityForm,setEntityForm]=useState({entityKind:'PLATFORM',institutionId:'',legalName:'',tradeName:'',contactEmail:'',contactPhone:'',addressLine:'',city:'',state:'',postalCode:'',invoiceSeriesCode:'QE'});
  const [editingCoreEntityId,setEditingCoreEntityId]=useState<number|null>(null);
  const [gstEntityId,setGstEntityId]=useState('');
  const [editingEntityId,setEditingEntityId]=useState<number|null>(null);
  const [gstForm,setGstForm]=useState({gstin:'',legalName:'',stateCode:'',effectiveFrom:new Date().toISOString().slice(0,10),addressLine:'',city:'',state:'',postalCode:''});
  const [taxForm,setTaxForm]=useState<TaxForm>({offerId:'',legalEntityId:'',serviceClassificationCode:'',taxTreatment:'TAXABLE',gstPercent:'',cessPercent:'',priceIncludesTax:true,placeOfSupplyRule:'RECIPIENT_LOCATION',effectiveFrom:new Date(Date.now()-60000).toISOString().slice(0,16)});
  const [taxProfiles,setTaxProfiles]=useState<Record<string,any[]>>({});
  const [selectedTemplateId,setSelectedTemplateId]=useState('');
  const [previewPdfUrl,setPreviewPdfUrl]=useState<string|null>(null);
  const [previewPdfLoading,setPreviewPdfLoading]=useState(false);
  const [selectedVersionNo,setSelectedVersionNo]=useState<number|null>(null);
  const [templateScope,setTemplateScope]=useState('PLATFORM');
  const [templateEntityId,setTemplateEntityId]=useState('');
  const [design,setDesign]=useState<Design>({...DEFAULT_DESIGN});
  const [templateName,setTemplateName]=useState('QuantaEdge Course Invoice');
  const [discountForm,setDiscountForm]=useState({
    offerId:'',code:'',discountType:'PERCENTAGE',percentage:'10',flatAmountRupees:'100',
    maxDiscountRupees:'',minListedPriceRupees:'0',maxRedemptions:'',maxRedemptionsPerPurchaser:'',
    effectiveFrom:new Date().toISOString().slice(0,16),effectiveUntil:''
  });

  const loadAll=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const [c,o,p,disc,t,d]=await Promise.all([
        api<Catalog>(BASE+'/catalog'),api<Overview>(BASE+'/overview'),api<Policy[]>(BASE+'/revenue-share/policies'),
        api<DiscountOffer[]>(BASE+'/discounts'),api<Template[]>(BASE+'/invoice-templates'),api<FinancialDocument[]>(BASE+'/documents')
      ]);
      setCatalog(c);setOverview(o);setPolicies(p);setDiscounts(disc);setTemplates(t);setDocuments(d);
      const requestedOfferId=launchOfferIdFromUrl.current;
      const launchSetup=marketplaceSetupFromUrl.current;
      const requestedOffer=c.offers.find(o=>String(o.offer_id)===requestedOfferId);
      const requestedOfferExists=Boolean(requestedOfferId&&requestedOffer);
      if(requestedOfferId&&!requestedOfferExists){
        setError('The course offer linked from Institution Marketplace Control was not found in Finance. Refresh the launch checklist. No other course offer is selected automatically.');
        setSelectedOfferId('');
        setTaxForm(v=>({...v,offerId:'',legalEntityId:''}));
        setDiscountForm(v=>({...v,offerId:''}));
        launchOfferIdFromUrl.current=null;
        marketplaceSetupFromUrl.current=false;
        setMarketplaceSetupContext(null);
      } else if(requestedOfferExists && requestedOfferId) {
        const linkedSeller=(requestedOffer?.legal_entity_id
          ? c.legalEntities.find(e=>e.id===requestedOffer.legal_entity_id&&e.status==='ACTIVE')
          : undefined)
          || c.legalEntities.find(e=>e.status==='ACTIVE'&&e.entity_kind==='INSTITUTION'&&e.institution_id===requestedOffer?.institution_id)
          || c.legalEntities.find(e=>e.status==='ACTIVE'&&e.entity_kind==='PLATFORM');
        setSelectedOfferId(requestedOfferId);
        setTaxForm(v=>({...v,offerId:requestedOfferId,legalEntityId:linkedSeller?String(linkedSeller.id):''}));
        setDiscountForm(v=>({...v,offerId:requestedOfferId}));
      }
    }catch(e){setError(e instanceof Error?e.message:'Finance workspace could not load.');}
    finally{setLoading(false);}
  },[selectedOfferId,taxForm.offerId,taxForm.legalEntityId,gstEntityId,selectedTemplateId,discountForm.offerId]);
  useEffect(()=>{
    const params=new URLSearchParams(window.location.search);
    const requestedTab=params.get('tab');
    const validTab=Boolean(requestedTab&&FINANCE_TABS.includes(requestedTab as FinanceTab));
    if(validTab)setTab(requestedTab as FinanceTab);
    const rawOfferId=params.get('offerId');
    const offerId=rawOfferId&&Number.isSafeInteger(Number(rawOfferId))&&Number(rawOfferId)>0?rawOfferId:'';
    if(offerId){
      launchOfferIdFromUrl.current=offerId;
      setSelectedOfferId(offerId);
    }
    if(params.get('setup')==='marketplace'){
      marketplaceSetupFromUrl.current=true;
      const rawInstitutionId=params.get('institutionId');
      setMarketplaceSetupContext({
        institutionId:rawInstitutionId&&Number.isSafeInteger(Number(rawInstitutionId))&&Number(rawInstitutionId)>0?Number(rawInstitutionId):null,
        courseName:(params.get('courseName')||'').trim(),
        offerId
      });
      if(!validTab)setTab('studio');
    }
  },[]);
  useEffect(()=>{void loadAll();},[loadAll]);
  useEffect(()=>()=>{if(previewPdfUrl)URL.revokeObjectURL(previewPdfUrl);},[previewPdfUrl]);

  const action=async(work:()=>Promise<unknown>,success:string):Promise<boolean>=>{
    setBusy(true);setError('');setNotice('');
    try{await work();setNotice('');notifyUser(success,'success');await loadAll();return true;}
    catch(e){setError(e instanceof Error?e.message:'The finance action failed.');return false;}
    finally{setBusy(false);}
  };
  const loadPolicy=async(id:string)=>{
    setSelectedPolicyId(id);
    if(!id){setPolicyDetail(null);setRecipients([]);return;}
    try{
      const p=await api<PolicyDetail>(BASE+'/revenue-share/policies/'+id);
      setPolicyDetail(p);
      const offerId=String(p.institution_offer_id);selectWorkflowOffer(offerId,true);
      const remainderType=p.remainder_recipient_type==='INSTITUTION'?'INSTITUTION':'COMPANY';
      setPolicyRemainderType(remainderType);
      const remainderId=remainderType==='INSTITUTION'?p.remainder_institution_id:p.remainder_legal_entity_id;
      setPolicyRemainderId(remainderId==null?'':String(remainderId));
      setRecipients((p.recipients||[]).map(r=>({
        recipientType:r.recipient_type==='COMPANY'?'COMPANY':'INSTITUTION',
        recipientId:String(r.recipient_type==='COMPANY'?r.legal_entity_id:r.institution_id),
        calculationType:r.calculation_type as 'PERCENTAGE'|'FLAT',
        percentage:r.percentage_basis_points==null?'':String(r.percentage_basis_points/100),
        flatRupees:r.flat_amount_paise==null?'':String(r.flat_amount_paise/100)
      })));
    }catch(e){setError(e instanceof Error?e.message:'Policy detail could not load.');}
  };
  const selectedOffer=useMemo(()=>catalog?.offers.find(o=>String(o.offer_id)===selectedOfferId),[catalog,selectedOfferId]);
  const contextualOffer=selectedOffer;
  const selectWorkflowOffer=(offerId:string,preservePolicy=false)=>{
    const offer=catalog?.offers.find(row=>String(row.offer_id)===offerId);
    const seller=!offer?undefined:offer.legal_entity_id!=null
      ?catalog?.legalEntities.find(e=>e.id===offer.legal_entity_id)
      :catalog?.legalEntities.find(e=>e.status==='ACTIVE'&&e.entity_kind==='INSTITUTION'&&e.institution_id===offer.institution_id)
        ||catalog?.legalEntities.find(e=>e.status==='ACTIVE'&&e.entity_kind==='PLATFORM');
    const sellerId=seller?String(seller.id):'';
    setSelectedOfferId(offerId);setTaxForm(v=>({...v,offerId,legalEntityId:sellerId,serviceClassificationCode:v.offerId===offerId?v.serviceClassificationCode:'',gstPercent:v.offerId===offerId?v.gstPercent:'',cessPercent:v.offerId===offerId?v.cessPercent:''}));
    setDiscountForm(v=>({...v,offerId,code:v.offerId===offerId?v.code:''}));setGstEntityId(sellerId);
    if(templateScope==='SELLER')setTemplateEntityId(sellerId);
    if(!preservePolicy){setSelectedPolicyId('');setPolicyDetail(null);setRecipients([]);}
  };
  const selectedTemplate=useMemo(()=>templates.find(t=>String(t.id)===selectedTemplateId),[templates,selectedTemplateId]);
  const designUnchanged=Boolean(selectedTemplate)&&JSON.stringify(design)===JSON.stringify(parseDesign(selectedTemplate?.design_definition_json));

  const saveEntity=async(event:FormEvent)=>{
    event.preventDefault();
    const body={legalName:entityForm.legalName,tradeName:entityForm.tradeName,contactEmail:entityForm.contactEmail,contactPhone:entityForm.contactPhone,
      invoiceSeriesCode:entityForm.invoiceSeriesCode,registeredAddress:{line1:entityForm.addressLine,city:entityForm.city,state:entityForm.state,postalCode:entityForm.postalCode,country:'India'}};
    const editing=editingCoreEntityId;
    const saved=await action(()=>editing?api(BASE+'/legal-entities/'+editing,{method:'PUT',body:JSON.stringify(body)}):api(BASE+'/legal-entities',{method:'POST',body:JSON.stringify({...body,entityKind:entityForm.entityKind,institutionId:entityForm.entityKind==='INSTITUTION'?Number(entityForm.institutionId):null})}),editing?'Legal entity details updated.':'Legal entity saved as draft.');
    if(saved){setEditingCoreEntityId(null);setEntityForm({entityKind:'PLATFORM',institutionId:'',legalName:'',tradeName:'',contactEmail:'',contactPhone:'',addressLine:'',city:'',state:'',postalCode:'',invoiceSeriesCode:'QE'});}
  };
  const editEntityDetails=(e:LegalEntity)=>{const a=parseAddress(e.registered_address_json);setEntityForm({entityKind:e.entity_kind,institutionId:e.institution_id?String(e.institution_id):'',legalName:e.legal_name,tradeName:e.trade_name||'',contactEmail:e.contact_email||'',contactPhone:e.contact_phone||'',addressLine:a.line1||'',city:a.city||'',state:a.state||'',postalCode:a.postalCode||'',invoiceSeriesCode:e.invoice_series_code||'QE'});setEditingCoreEntityId(e.id);setEditingEntityId(null);setError('');setNotice('');};
  const saveInvoiceSeries=(entity:LegalEntity)=>{
    const series=(seriesDrafts[String(entity.id)]??entity.invoice_series_code??'QE').trim().toUpperCase();
    if(!/^[A-Z0-9_-]{1,3}$/.test(series)){setError('Invoice series prefix must be 1–3 uppercase letters, digits, underscores or hyphens.');return;}
    void action(()=>api(BASE+'/legal-entities/'+entity.id+'/invoice-series',{method:'PUT',body:JSON.stringify({invoiceSeriesCode:series})}),
      'Invoice series saved. Existing invoices keep their original numbers; future documents use this series.');
  };
  const saveEinvoiceApplicability=(entity:LegalEntity)=>{
    const applicability=einvoiceDrafts[String(entity.id)]??entity.einvoice_applicability??'UNREVIEWED';
    const reviewNote=(einvoiceNotes[String(entity.id)]??entity.einvoice_applicability_review_note??'').trim();
    if(applicability!=='NOT_REQUIRED'&&applicability!=='REQUIRED'){
      setError('Choose NOT_REQUIRED or REQUIRED only after reviewing the seller with the responsible tax adviser.');return;
    }
    if(reviewNote.length<20||reviewNote.length>1000){
      setError('Add a review rationale or tax-adviser reference between 20 and 1,000 characters.');return;
    }
    const message=applicability==='REQUIRED'
      ?'Review recorded. Checkout remains blocked until e-invoice integration is enabled.'
      :'Review recorded. Other checkout gates still apply; this record is not legal certification.';
    void action(()=>api(BASE+'/legal-entities/'+entity.id+'/einvoice-applicability',{
      method:'PUT',body:JSON.stringify({applicability,reviewNote})
    }),message);
  };
  const downloadDocument=async(fiscalDoc:FinancialDocument)=>{
    setDownloadingDocumentId(fiscalDoc.id);setError('');setNotice('');
    try{
      const response=await apiFetch(BASE+'/documents/'+fiscalDoc.id+'/download',{
        method:'GET',credentials:'same-origin',cache:'no-store',
        headers:{'X-QE-Workspace':'HRMS','Accept':'application/pdf'}
      });
      if(!response.ok){
        const raw=await response.text();let detail:any={};
        try{detail=raw?JSON.parse(raw):{};}catch{detail={message:raw};}
        throw new Error(String(detail.message||detail.detail||detail.error||'Invoice PDF could not be downloaded.'));
      }
      const blob=await response.blob();
      if(blob.size===0||!blob.type.toLowerCase().includes('pdf'))throw new Error('The server did not return a valid PDF.');
      const url=URL.createObjectURL(blob),anchor=document.createElement('a');
      anchor.href=url;anchor.download=fiscalDoc.document_number.replace(/[^A-Za-z0-9_-]/g,'-')+'.pdf';
      document.body.appendChild(anchor);anchor.click();anchor.remove();
      window.setTimeout(()=>URL.revokeObjectURL(url),1000);
      setNotice('Invoice PDF downloaded.');
    }catch(e){setError(e instanceof Error?e.message:'Invoice PDF could not be downloaded.');}
    finally{setDownloadingDocumentId(null);}
  };
  const selectedRefundDocument=documents.find(d=>d.id===selectedRefundDocId)??null;
  const loadRefundHistory=async(orderId:number)=>{
    setRefundHistoryLoading(true);
    try{
      const rows=await api<RefundRecord[]>(BASE+'/orders/'+orderId+'/refunds');
      setRefundHistory(Array.isArray(rows)?rows:[]);
    }catch(e){setError(e instanceof Error?e.message:'Refund history could not be loaded.');}
    finally{setRefundHistoryLoading(false);}
  };
  const selectRefundDocument=(doc:FinancialDocument)=>{
    if(doc.source_type!=='COURSE_ORDER'||!['TAX_INVOICE','BILL_OF_SUPPLY'].includes(doc.document_type)){
      setError('Refunds can only be started from an original course-sale invoice.');return;
    }
    const orderId=Number(doc.source_record_key);
    if(!Number.isSafeInteger(orderId)||orderId<=0){setError('The invoice does not contain a valid course order reference.');return;}
    setSelectedRefundDocId(doc.id);
    setRefundDraft({amountRupees:'',reason:''});
    setRefundIdempotencyKey(crypto.randomUUID());
    setRefundResult(null);setError('');setNotice('');
    void loadRefundHistory(orderId);
  };
  const submitRefund=async()=>{
    if(!selectedRefundDocument)return;
    const orderId=Number(selectedRefundDocument.source_record_key);
    const amountPaise=parseRupeesPaise(refundDraft.amountRupees);
    const reason=refundDraft.reason.trim();
    if(amountPaise===null){setError('Enter a refund amount of at least ₹1.00, with no more than two decimal places.');return;}
    if(reason.length<10||reason.length>500){setError('Refund reason must contain 10–500 characters.');return;}
    const key=refundIdempotencyKey||crypto.randomUUID();
    setRefundIdempotencyKey(key);setRefundBusy(true);setError('');setNotice('');
    try{
      const result=await api<RefundRecord>(BASE+'/orders/'+orderId+'/refunds',{
        method:'POST',headers:{'Idempotency-Key':key},
        body:JSON.stringify({amountPaise,reason})
      });
      setRefundResult(result);
      const status=String(result.status||'').toUpperCase();
      if(status==='PROCESSED'){
        setNotice('Refund processed. Credit note '+String(result.credit_note_number||'')+' has been issued and institutional allocations reversed.');
        await loadAll();
      }else if(status==='FAILED'){
        setNotice('The provider reports that this refund failed. Start a new request only after reviewing the saved status.');
        setRefundIdempotencyKey(crypto.randomUUID());
      }else{
        setNotice('Refund is not yet confirmed as processed. Its balance remains reserved; use the same request to retry or reconcile.');
      }
      await loadRefundHistory(orderId);
    }catch(e){
      setError((e instanceof Error?e.message:'Refund request could not be confirmed.')+' Keep the same amount/reason and retry; the server/provider idempotency key prevents a duplicate refund.');
    }finally{setRefundBusy(false);}
  };
  const refreshRefund=async(refund:RefundRecord)=>{
    if(!selectedRefundDocument)return;
    const orderId=Number(selectedRefundDocument.source_record_key);
    setRefundBusy(true);setError('');
    try{
      const result=await api<RefundRecord>(BASE+'/orders/'+orderId+'/refunds/'+refund.id);
      setRefundResult(result);
      if(String(result.status).toUpperCase()==='PROCESSED')await loadAll();
      await loadRefundHistory(orderId);
    }catch(e){setError(e instanceof Error?e.message:'Provider refund status could not be refreshed.');}
    finally{setRefundBusy(false);}
  };
  const retryRefund=async(refund:RefundRecord)=>{
    if(!selectedRefundDocument)return;
    const orderId=Number(selectedRefundDocument.source_record_key);
    setRefundBusy(true);setError('');
    try{
      const result=await api<RefundRecord>(BASE+'/orders/'+orderId+'/refunds/'+refund.id+'/retry',{method:'POST'});
      setRefundResult(result);
      const status=String(result.status||'').toUpperCase();
      setNotice(status==='PROCESSED'
        ?'Refund processed and credit note issued: '+String(result.credit_note_number||'')
        :'Provider still has not confirmed the refund as processed. The same saved provider idempotency key was reused.');
      if(status==='PROCESSED')await loadAll();
      await loadRefundHistory(orderId);
    }catch(e){setError(e instanceof Error?e.message:'Refund retry could not be confirmed.');}
    finally{setRefundBusy(false);}
  };
  const loadPaymentOrders=async(offsetOverride?:number)=>{
    const offset=offsetOverride??paymentOffset;
    setPaymentLoading(true);setError('');
    try{
      const query=new URLSearchParams({limit:'50',offset:String(offset)});
      if(paymentStatusFilter)query.set('status',paymentStatusFilter);
      if(paymentSearch.trim())query.set('search',paymentSearch.trim());
      const response=await api<{items:CoursePaymentOrder[];total:number;limit:number;offset:number}>('/api/v1/hrms/platform/finance/course-orders?'+query.toString());
      setPaymentOrders(Array.isArray(response.items)?response.items:[]);
      setPaymentTotal(Number(response.total||0));
      setPaymentOffset(Number(response.offset||0));
    }catch(e){setError(e instanceof Error?e.message:'Course payment orders could not be loaded.');}
    finally{setPaymentLoading(false);}
  };
  const loadPaymentEvents=async(orderId:number)=>{
    setPaymentEventsLoading(true);
    try{
      const response=await api<PaymentOperationEvent[]>('/api/v1/hrms/platform/finance/course-orders/'+orderId+'/events');
      setPaymentEvents(Array.isArray(response)?response:[]);
    }catch(e){setError(e instanceof Error?e.message:'Payment operation history could not be loaded.');}
    finally{setPaymentEventsLoading(false);}
  };
  const loadProviderEvents=async(offsetOverride?:number)=>{
    const offset=offsetOverride??providerEventOffset;
    setProviderEventLoading(true);
    setProviderEventWarning('');
    try{
      const query=new URLSearchParams({limit:'50',offset:String(offset)});
      if(providerEventStatusFilter)query.set('status',providerEventStatusFilter);
      if(providerEventSearch.trim())query.set('search',providerEventSearch.trim());
      const response=await api<{items:ProviderEventRecord[];total:number;limit:number;offset:number;schemaReady?:boolean;warning?:string}>('/api/v1/hrms/platform/finance/course-orders/provider-events?'+query.toString());
      setProviderEvents(Array.isArray(response.items)?response.items:[]);
      setProviderEventTotal(Number(response.total||0));
      setProviderEventWarning(response.schemaReady===false
        ?(response.warning||'Razorpay provider-event storage is unavailable. Check the local API database migration.')
        :'');
      setProviderEventOffset(Number(response.offset||0));
    }catch(e){
      setProviderEventWarning(e instanceof Error?e.message:'Razorpay provider event inbox could not be loaded.');
      setError(e instanceof Error?e.message:'Razorpay provider event inbox could not be loaded.');
    }
    finally{setProviderEventLoading(false);}
  };
  const loadSelectedProviderEvents=async(orderId:number)=>{
    setSelectedProviderEventsLoading(true);
    try{
      const query=new URLSearchParams({orderId:String(orderId),limit:'100',offset:'0'});
      const response=await api<{items:ProviderEventRecord[];total:number;schemaReady?:boolean;warning?:string}>('/api/v1/hrms/platform/finance/course-orders/provider-events?'+query.toString());
      setSelectedProviderEvents(Array.isArray(response.items)?response.items:[]);
      setSelectedProviderEventsWarning(response.schemaReady===false
        ?(response.warning||'Razorpay provider-event storage is not available in this database.')
        :'');
    }catch(e){
      setSelectedProviderEvents([]);
      setSelectedProviderEventsWarning(e instanceof Error?e.message:'Provider events for this order could not be loaded.');
      setError(e instanceof Error?e.message:'Provider events for this order could not be loaded.');
    }
    finally{setSelectedProviderEventsLoading(false);}
  };
  const inspectPaymentOrderId=(orderId:number)=>{
    setSelectedPaymentOrderId(orderId);setPaymentActionResult(null);setStatusTrace(null);setStatusTraceError('');
    void Promise.all([loadPaymentEvents(orderId),loadSelectedProviderEvents(orderId)]);
  };
  const selectPaymentOrder=(order:CoursePaymentOrder)=>{
    setSelectedPaymentOrderId(order.local_order_id);setPaymentActionResult(null);setStatusTraceError('');
    if(statusTrace?.orderId!==order.local_order_id)setStatusTrace(null);
    void Promise.all([loadPaymentEvents(order.local_order_id),loadSelectedProviderEvents(order.local_order_id)]);
  };
  const checkCurrentStatus=async(orderId:number)=>{
    setSelectedPaymentOrderId(orderId);setPaymentActionResult(null);setStatusTrace(null);setStatusTraceError('');
    setStatusTraceLoadingId(orderId);
    void Promise.all([loadPaymentEvents(orderId),loadSelectedProviderEvents(orderId)]);
    try{
      const trace=await api<PaymentStatusTrace>('/api/v1/hrms/platform/finance/course-orders/'+orderId+'/status-trace');
      setStatusTrace(trace);
    }catch(e){
      setStatusTraceError(e instanceof Error?e.message:'The transaction delivery chain could not be loaded.');
    }finally{
      setStatusTraceLoadingId(current=>current===orderId?null:current);
    }
  };
  const reconcilePaymentOrder=async(order:CoursePaymentOrder)=>{
    if(!['PENDING','EXPIRED','VERIFIED'].includes(String(order.status).toUpperCase()))return;
    setPaymentActionLoadingId(order.local_order_id);setError('');setNotice('');setPaymentActionResult(null);
    setSelectedPaymentOrderId(order.local_order_id);
    try{
      const result=await api<PaymentReconciliationResult>('/api/v1/hrms/platform/finance/course-orders/'+order.local_order_id+'/reconcile',{method:'POST'});
      setPaymentActionResult(result);
      const outcome=String(result.status||'').toUpperCase();
      const resultMessage=result.resolutionMessage||result.message||'Provider reconciliation finished. Review the result and saved action history.';
      if(outcome==='RECONCILIATION_REQUIRED'||outcome==='ACTION_BLOCKED') {
        setError(resultMessage);
        setNotice('');
      } else {
        setNotice(resultMessage);
      }
      await Promise.all([loadPaymentOrders(),loadPaymentEvents(order.local_order_id),loadSelectedProviderEvents(order.local_order_id),loadProviderEvents()]);
    }catch(e){
      setError(e instanceof Error?e.message:'Payment reconciliation could not be completed. Do not ask the learner to pay again until provider status is checked.');
      await loadPaymentEvents(order.local_order_id);
    }finally{setPaymentActionLoadingId(null);}
  };
  const createRegistration=async(event:FormEvent)=>{
    event.preventDefault();
    if(!gstEntityId){setError('Choose the legal entity that owns this GST registration.');return;}
    const saved=await action(async()=>api(BASE+'/legal-entities/'+gstEntityId+'/gst-registrations',{method:'POST',body:JSON.stringify({
      gstin:gstForm.gstin,legalName:gstForm.legalName,stateCode:gstForm.stateCode,effectiveFrom:gstForm.effectiveFrom,
      registeredAddress:{line1:gstForm.addressLine,city:gstForm.city,state:gstForm.state,postalCode:gstForm.postalCode,country:'India'}
    })}), 'GST registration saved pending verification.');
    if(saved)setGstForm({gstin:'',legalName:'',stateCode:'',effectiveFrom:new Date().toISOString().slice(0,10),addressLine:'',city:'',state:'',postalCode:''});
  };
  const createTaxProfile=async(event:FormEvent)=>{
    event.preventDefault();
    if(!taxForm.offerId||!taxForm.legalEntityId){setError('Choose both the course offer and legal seller.');return;}
    const offerId=taxForm.offerId;
    const treatment=taxForm.taxTreatment;
    const gstBps=treatment==='TAXABLE'?Math.round(Number(taxForm.gstPercent)*100):0;
    const cessBps=treatment==='TAXABLE'?Math.round(Number(taxForm.cessPercent||0)*100):0;
    setBusy(true);setError('');setNotice('');
    try{
      const created=await api<{id:number}>(BASE+'/offers/'+offerId+'/tax-profiles',{method:'POST',body:JSON.stringify({
        legalEntityId:Number(taxForm.legalEntityId),serviceClassificationCode:taxForm.serviceClassificationCode.trim()||null,
        taxTreatment:treatment,gstRateBasisPoints:gstBps,cessRateBasisPoints:cessBps,
        priceIncludesTax:taxForm.priceIncludesTax,placeOfSupplyRule:taxForm.placeOfSupplyRule,
        effectiveFrom:asIso(taxForm.effectiveFrom),taxComponents:{}
      })});
      // The draft now exists even if the separate submit step later fails.
      setTaxForm(v=>({...v,serviceClassificationCode:'',gstPercent:'',cessPercent:''}));
      await loadTaxProfiles(offerId);
      let submitFailure:unknown=null;
      try{await api(BASE+'/tax-profiles/'+created.id+'/submit',{method:'POST'});}
      catch(cause){submitFailure=cause;}
      const refreshed=await loadTaxProfiles(offerId);
      const savedStatus=refreshed.find(row=>Number(row.id)===created.id)?.status;
      if(!submitFailure||savedStatus==='PENDING_APPROVAL'){
        setError('');notifyUser('Tax profile submitted for HRMS approval.','success');
      }else{
        const detail=submitFailure instanceof Error?submitFailure.message:'Submission could not be confirmed.';
        const message='Tax profile draft #'+created.id+' was saved, but submission could not be confirmed. Refresh Saved tax profiles and use Submit if it is still a draft. Do not create it again. '+detail;
        setError(message);notifyUser(message,'warning');
      }
    }catch(cause){
      setError(cause instanceof Error?cause.message:'Tax profile draft could not be created.');
    }finally{setBusy(false);}
  };
  const loadTaxProfiles=async(offerId:string):Promise<any[]>=>{
    if(!offerId)return [];
    try{const rows=await api<any[]>(BASE+'/offers/'+offerId+'/tax-profiles');setTaxProfiles(v=>({...v,[offerId]:rows}));return rows;}
    catch(e){setError(e instanceof Error?e.message:'Tax profiles could not load.');return [];}
  };
  const approveTaxProfile=async(id:number,offerId:string)=>{
    setError('');
    try{
      const latest=await api<any[]>(BASE+'/offers/'+offerId+'/tax-profiles');
      setTaxProfiles(v=>({...v,[offerId]:latest}));
      const profile=latest.find(row=>Number(row.id)===id);
      const status=String(profile?.status||'NOT_FOUND').trim().toUpperCase();
      if(!profile||status!=='PENDING_APPROVAL'){
        setError('Tax profile #'+id+' is currently '+status+'. Refresh saved profiles and use the action matching its current status.');return;
      }
      const ok=await action(()=>api(BASE+'/tax-profiles/'+id+'/approve',{method:'POST'}),'Tax profile approved.');
      if(ok)await loadTaxProfiles(offerId);
    }catch(error){setError(error instanceof Error?error.message:'Tax profile status could not be refreshed before approval.');}
  };
  const submitTaxProfile=async(id:number,offerId:string)=>{
    const ok=await action(()=>api(BASE+'/tax-profiles/'+id+'/submit',{method:'POST'}),'Tax profile submitted for HRMS approval.');
    if(ok)await loadTaxProfiles(offerId);
  };
  useEffect(()=>{if(taxForm.offerId)void loadTaxProfiles(taxForm.offerId);},[taxForm.offerId]);

  const createDiscount=async()=>{
    if(!discountForm.offerId){setError('Select a course offer for this discount code.');return;}
    if(!/^[A-Za-z0-9][A-Za-z0-9_-]{2,39}$/.test(discountForm.code.trim())){setError('Offer codes must be 3–40 letters, digits, hyphens or underscores.');return;}
    if(discountForm.discountType==='PERCENTAGE'&&(!(Number(discountForm.percentage)>0)||Number(discountForm.percentage)>100)){setError('Discount percentage must be greater than 0 and no more than 100.');return;}
    if(discountForm.discountType==='FLAT'&&!(Number(discountForm.flatAmountRupees)>0)){setError('Enter a positive flat discount amount.');return;}
    if(discountForm.maxDiscountRupees!==''&&!(Number(discountForm.maxDiscountRupees)>0)){setError('Maximum discount cap must be positive when configured.');return;}
    if(discountForm.minListedPriceRupees!==''&&Number(discountForm.minListedPriceRupees)<0){setError('Minimum listed price cannot be negative.');return;}
    if(discountForm.maxRedemptions!==''&&(!Number.isSafeInteger(Number(discountForm.maxRedemptions))||Number(discountForm.maxRedemptions)<=0)){setError('Maximum redemptions must be a positive whole number.');return;}
    if(discountForm.maxRedemptionsPerPurchaser!==''&&(!Number.isSafeInteger(Number(discountForm.maxRedemptionsPerPurchaser))||Number(discountForm.maxRedemptionsPerPurchaser)<=0)){setError('Per-purchaser limit must be a positive whole number.');return;}
    if(discountForm.effectiveUntil&&new Date(discountForm.effectiveUntil)<=new Date(discountForm.effectiveFrom)){setError('Offer end must be after its start.');return;}
    const submitted=await action(async()=>{
      const created=await api<{id:number;code:string;version:number}>(BASE+'/discounts',{method:'POST',body:JSON.stringify({
        institutionOfferId:Number(discountForm.offerId),code:discountForm.code.trim().toUpperCase(),
        currency:'INR',discountType:discountForm.discountType,
        percentage:discountForm.discountType==='PERCENTAGE'?discountForm.percentage:null,
        flatAmountRupees:discountForm.discountType==='FLAT'?discountForm.flatAmountRupees:null,
        maxDiscountRupees:discountForm.maxDiscountRupees||null,
        minListedPriceRupees:discountForm.minListedPriceRupees||'0',
        maxRedemptions:discountForm.maxRedemptions||null,
        maxRedemptionsPerPurchaser:discountForm.maxRedemptionsPerPurchaser||null,
        effectiveFrom:asIso(discountForm.effectiveFrom),
        effectiveUntil:discountForm.effectiveUntil?asIso(discountForm.effectiveUntil):null
      })});
      // Clear the creation draft only after the server has created its durable record.
      setDiscountForm(v=>({...v,code:'',percentage:'10',flatAmountRupees:'100',maxDiscountRupees:'',minListedPriceRupees:'0',maxRedemptions:'',maxRedemptionsPerPurchaser:'',effectiveFrom:new Date().toISOString().slice(0,16),effectiveUntil:''}));
      try{await api(BASE+'/discounts/'+created.id+'/submit',{method:'POST'});}
      catch(cause){
        // A draft already exists; never make the user create it a second time to retry submission.
        const latest=await api<DiscountOffer[]>(BASE+'/discounts').catch(()=>[]);
        if(latest.length)setDiscounts(latest);
        const saved=latest.find(row=>row.id===created.id);
        if(saved?.status!=='PENDING_APPROVAL'){
          const detail=cause instanceof Error?cause.message:'Submission could not be confirmed.';
          throw new Error('Discount draft '+created.code+' was created, but submission could not be confirmed. Use its Submit action in the discount list; do not create it again. '+detail);
        }
      }
    },'Discount created and submitted for independent HRMS approval. It cannot be used until approved.');
    if(submitted)setDiscountForm(v=>({...v,code:'',percentage:'10',flatAmountRupees:'100',maxDiscountRupees:'',minListedPriceRupees:'0',maxRedemptions:'',maxRedemptionsPerPurchaser:'',effectiveFrom:new Date().toISOString().slice(0,16),effectiveUntil:''}));
  };
  const submitDiscount=(id:number)=>void action(()=>api(BASE+'/discounts/'+id+'/submit',{method:'POST'}),'Discount submitted for HRMS approval.');
  const approveDiscount=(id:number)=>void action(()=>api(BASE+'/discounts/'+id+'/approve',{method:'POST'}),'Discount approved. The server will validate eligibility and enforce usage limits.');
  const retireDiscount=(id:number)=>void action(()=>api(BASE+'/discounts/'+id+'/retire',{method:'POST'}),'Discount retired. Existing order snapshots remain unchanged.');

  const createPolicy=()=>{
    if(!selectedOfferId){setError('Select a course offer first.');return;}
    void action(async()=>{
      const result=await api<{id:number}>(BASE+'/revenue-share/offers/'+selectedOfferId+'/policies',{method:'POST',body:JSON.stringify({currency:'INR',effectiveFrom:new Date().toISOString()})});
      setSelectedPolicyId(String(result.id));
      const detail=await api<PolicyDetail>(BASE+'/revenue-share/policies/'+result.id);
      setPolicyDetail(detail);
      const remainderType=detail.remainder_recipient_type==='INSTITUTION'?'INSTITUTION':'COMPANY';
      setPolicyRemainderType(remainderType);
      const remainderId=remainderType==='INSTITUTION'?detail.remainder_institution_id:detail.remainder_legal_entity_id;
      setPolicyRemainderId(remainderId==null?'':String(remainderId));
      setRecipients([]);
    },'Draft revenue-share policy created.');
  };
  const recipientPayload=()=>{
    if(!selectedPolicyId) throw new Error('Create or select a draft policy first.');
    const invalid=recipients.some(r=>!r.recipientId||(r.calculationType==='PERCENTAGE'?!(Number(r.percentage)>0&&Number(r.percentage)<=100):!(Number(r.flatRupees)>0)));
    if(invalid) throw new Error('Each institution or company needs a positive percentage or flat share.');
    if(!policyRemainderId)throw new Error('Choose the institution or company that receives the remaining balance.');
    return recipients.map(r=>r.calculationType==='PERCENTAGE'
      ?{recipientType:r.recipientType,recipientId:Number(r.recipientId),calculationType:r.calculationType,percentageBasisPoints:Math.round(Number(r.percentage)*100)}
      :{recipientType:r.recipientType,recipientId:Number(r.recipientId),calculationType:r.calculationType,flatAmountPaise:Math.round(Number(r.flatRupees)*100)});
  };
  const recipientSnapshot=(rows:any[])=>JSON.stringify(rows.map(row=>({
    recipientType:String(row.recipientType??row.recipient_type??'INSTITUTION'),
    recipientId:Number(row.recipientId??row.recipient_id??(String(row.recipient_type)==='COMPANY'?row.legal_entity_id:row.institution_id)),
    calculationType:String(row.calculationType??row.calculation_type),
    percentageBasisPoints:row.percentageBasisPoints??row.percentage_basis_points??null,
    flatAmountPaise:row.flatAmountPaise??row.flat_amount_paise??null
  })).sort((a,b)=>a.recipientType.localeCompare(b.recipientType)||a.recipientId-b.recipientId));
  const recipientsUnchanged=(()=>{
    if(!policyDetail||!selectedPolicyId)return false;
    try{return recipientSnapshot(recipientPayload())===recipientSnapshot(policyDetail.recipients||[])
      &&policyRemainderType===(policyDetail.remainder_recipient_type==='INSTITUTION'?'INSTITUTION':'COMPANY')
      &&Number(policyRemainderId)===(policyRemainderType==='INSTITUTION'?Number(policyDetail.remainder_institution_id):Number(policyDetail.remainder_legal_entity_id));}
    catch{return false;}
  })();
  const saveRecipients=async()=>{
    if(!selectedPolicyId||recipientsUnchanged)return;
    const policyId=selectedPolicyId;
    const saved=await action(async()=>api(BASE+'/revenue-share/policies/'+policyId+'/recipients',{method:'PUT',body:JSON.stringify({recipients:recipientPayload(),remainderRecipientType:policyRemainderType,remainderRecipientId:Number(policyRemainderId)})}), 'Revenue-share recipients saved.');
    if(saved)await loadPolicy(policyId);
  };
  const createTemplate=()=>{
    void action(async()=>{
      const body=await api<{id:number;versionNo:number}>(BASE+'/invoice-templates',{method:'POST',body:JSON.stringify({
        scope:templateScope,legalEntityId:templateScope==='SELLER'?Number(templateEntityId):null,
        internalName:templateName,designDefinition:design
      })});
      setSelectedTemplateId(String(body.id));setSelectedVersionNo(body.versionNo);
    },'Invoice template draft created. Preview is not a legal invoice.');
  };
  const startTemplateVersion=()=>{
    if(!selectedTemplateId)return;
    void action(async()=>{
      const v=await api<{versionNo:number}>(BASE+'/invoice-templates/'+selectedTemplateId+'/versions',{method:'POST'});
      setSelectedVersionNo(v.versionNo);
    },'New invoice template draft created.');
  };
  const saveTemplate=()=>{
    if(!selectedTemplateId||!selectedVersionNo){setError('Create a template or start a draft version first.');return;}
    if(designUnchanged)return;
    void action(async()=>api(BASE+'/invoice-templates/'+selectedTemplateId+'/versions/'+selectedVersionNo,{method:'PUT',body:JSON.stringify({designDefinition:design})}),
      'Invoice template draft saved. The preview reflects the saved design.');
  };
  const generatePdfPreview=async()=>{
    setPreviewPdfLoading(true);setError('');
    try{
      const response=await apiFetch(BASE+'/invoice-template-preview.pdf',{
        method:'POST',
        headers:{'X-QE-Workspace':'HRMS','Accept':'application/pdf','Content-Type':'application/json'},
        credentials:'same-origin',cache:'no-store',body:JSON.stringify({designDefinition:design})
      });
      if(!response.ok){
        const raw=await response.text();let detail:any={};
        try{detail=raw?JSON.parse(raw):{};}catch{detail={message:raw};}
        throw new Error(String(detail.message||detail.detail||detail.error||'PDF preview could not be generated.'));
      }
      const blob=await response.blob();
      if(blob.size===0||(!blob.type.includes('pdf')&&blob.type!=='application/octet-stream')) throw new Error('The server did not return a valid PDF preview.');
      setPreviewPdfUrl(URL.createObjectURL(blob));
    }catch(e){setError(e instanceof Error?e.message:'PDF preview could not be generated.');}
    finally{setPreviewPdfLoading(false);}
  };
  const submitTemplate=()=>{
    if(!selectedTemplateId||!selectedVersionNo){setError('Select a template draft first.');return;}
    void action(async()=>api(BASE+'/invoice-templates/'+selectedTemplateId+'/versions/'+selectedVersionNo+'/submit',{method:'POST'}),
      'Invoice template submitted for approval.');
  };
  const approveTemplate=()=>{
    if(!selectedTemplateId||!selectedVersionNo){setError('Select a pending template version first.');return;}
    void action(async()=>api(BASE+'/invoice-templates/'+selectedTemplateId+'/versions/'+selectedVersionNo+'/approve',{method:'POST'}),
      'Invoice template approved. Publishing is a separate step.');
  };
  const publishTemplate=()=>{
    if(!selectedTemplateId||!selectedVersionNo){setError('Select a template draft first.');return;}
    void action(async()=>api(BASE+'/invoice-templates/'+selectedTemplateId+'/versions/'+selectedVersionNo+'/publish',{method:'POST'}),
      'Invoice template version published. Existing issued documents remain unchanged.');
  };
  const selectTemplate=(id:string)=>{
    setSelectedTemplateId(id);
    const template=templates.find(t=>String(t.id)===id);
    if(template){setTemplateScope(template.scope);setTemplateEntityId(template.legal_entity_id?String(template.legal_entity_id):'');setTemplateName(template.internal_name);setSelectedVersionNo(template.version_no||null);setDesign(parseDesign(template.design_definition_json));}
    else{setSelectedVersionNo(null);setDesign({...DEFAULT_DESIGN});}
  };
  const activeDraft=selectedTemplate?.version_status==='DRAFT' || !selectedTemplate;
  const tabs:[typeof tab,string,string][]=[
    ['overview','Overview','▦'],['entities','Seller & GST','⌂'],['tax','Tax profile','%'],
    ['distribution','Revenue share','↗'],['studio','Invoice design','▤'],['discounts','Discount codes','%'],
    ['payments','Payments & reconciliation','↻'],['documents','Issued documents','▣'],['payouts','Payouts & settlements','⇢']
  ];
  // Render the same selected design on two workspace surfaces. The design and PDF
  // are independent of HRMS appearance and retain their explicitly authored colours.
  const invoiceSample = (
<article data-qe-invoice-document="true" className={[styles.paper,design.surfaceStyle==='CLEAN_WHITE'?styles.paperWhite:design.surfaceStyle==='SOFT_SLATE'?styles.paperSlate:styles.paperWarm].join(' ')} style={{'--invoice-primary':design.primaryColor,'--invoice-accent':design.accentColor} as CSSProperties}>
            <div className={styles.paperTopRule}/>
            <header className={styles.paperBrand}><div className={styles.mark} style={{background:design.primaryColor}}>{design.showLogo?'QE':'IN'}</div><div><strong>LEGAL SUPPLIER NAME</strong><span>Registered address · State and PIN</span><span>GSTIN: configured approved registration</span></div><div className={styles.documentTitle}><span>TAX INVOICE</span><b>QE-PREVIEW-0001</b><small>Preview date · sample only</small></div></header>
            <div className={styles.paperRule}/>
            <div className={styles.paperMeta}><div><small>BILL TO</small><b>Customer billing name</b><span>Billing address and state</span><span>GSTIN if applicable</span></div><div><small>COURSE / ORDER</small><b>Class 6 Mathematics</b><span>Course order reference · preview</span><span>Beneficiary: learner example</span></div></div>
            <table className={styles.paperTable}><thead><tr><th>DESCRIPTION</th><th>QTY</th><th>VALUE</th></tr></thead><tbody><tr><td><b>Course access</b><small>Illustrative line item · SAC configured by finance</small></td><td>1</td><td>₹1,000.00</td></tr><tr><td>Offer discount</td><td>—</td><td>− ₹100.00</td></tr></tbody></table>
            <div className={styles.paperBottom}><div className={styles.sampleTax}><span>Tax breakdown</span><p>Tax components and rates appear from the approved transaction snapshot—not from this sample.</p><span>Order / payment reference: preview only</span></div><div className={styles.paperTotals}><div><span>Discounted value</span><b>₹900.00</b></div><div><span>Applicable tax</span><b>Calculated per approved profile</b></div><div className={styles.grandTotal}><span>TOTAL</span><b>Calculated for actual transaction</b></div></div></div>
            <footer className={styles.paperFooter}><div><b>{design.footerText}</b><span>Supplier particulars, required declarations and authentication are rendered from the approved invoice snapshot.</span></div><div className={styles.previewSeal}>PREVIEW<br/>NOT A TAX INVOICE</div></footer>
          </article>
  );

  return <main className={styles.page}>
    <header className={styles.header}>
      <div><span className={styles.eyebrow}>HRMS · FINANCE & BILLING</span><h1>Financial control centre</h1>
        <p>Seller and tax setup, approved institutional shares, invoice design and document audit—all controlled centrally by HRMS.</p></div>
      <button className={styles.secondary} onClick={()=>void loadAll()} disabled={busy||loading}>↻ Refresh</button>
    </header>
    {error&&<div className={styles.alertError} role="alert">{error}</div>}
    {notice&&<div className={styles.alertSuccess} role="status">{notice}</div>}
    {catalog&&(marketplaceSetupContext||['overview','entities','tax','distribution','discounts','studio'].includes(tab))&&<section className={styles.marketplaceSetupGuide} aria-labelledby="marketplace-finance-setup-heading">
      <div className={styles.marketplaceSetupHeader}><div><span className={styles.eyebrow}>HRMS · COURSE FINANCE</span>
        <h2 id="marketplace-finance-setup-heading">{marketplaceSetupContext?'Finish course launch setup':'Configure course launch finance'}</h2>
        <p>{marketplaceSetupContext?(selectedOffer?<>Configuring <strong>{selectedOffer.course_name}</strong> for <strong>{selectedOffer.institution_name}</strong>.</>:<>Select the course offer to continue setup for <strong>{marketplaceSetupContext.courseName||'this course'}</strong>{marketplaceSetupContext.institutionId?<> at institution #{marketplaceSetupContext.institutionId}</>:null}.</>):<>Select the exact course once, then follow the steps in order. Settings stay bound to this offer.</>}</p></div>
        <span className={styles.marketplaceSetupOfferTag}>{contextualOffer?contextualOffer.course_code+' · v'+contextualOffer.content_version_no:'Select an offer'}</span></div>
      <div className={styles.marketplaceSetupOfferSelector}><label>Course offer<select value={selectedOfferId} onChange={event=>selectWorkflowOffer(event.target.value)}>
        <option value="">Select a current course offer</option>{catalog.offers.filter(o=>o.current_candidate||o.publicly_sellable).map(o=><option key={o.offer_id} value={String(o.offer_id)}>{o.institution_name} · {o.course_name} · v{o.content_version_no}</option>)}
      </select></label><p>{selectedOffer?<>Selected: <strong>{selectedOffer.course_name}</strong> · {selectedOffer.institution_name} · {selectedOffer.price_paise>0?rupees(selectedOffer.price_paise,selectedOffer.currency):'Free course'}.</>:'No course is preselected.'}</p></div>
      <div className={styles.marketplaceSetupSteps}>
        <a className={styles.marketplaceSetupStep} href="/institutions#hrms-integrations-heading"><span className={styles.marketplaceSetupStepNumber}>01</span><span className={styles.marketplaceSetupStepCopy}><strong>Payments</strong><small>Razorpay / webhook for paid courses</small></span></a>
        <button type="button" className={tab==='entities'?styles.marketplaceSetupStepActive:styles.marketplaceSetupStep} onClick={()=>setTab('entities')}><span className={styles.marketplaceSetupStepNumber}>02</span><span className={styles.marketplaceSetupStepCopy}><strong>Seller &amp; GST</strong><small>Legal details, GST and e-invoice review</small></span></button>
        <button type="button" className={tab==='tax'?styles.marketplaceSetupStepActive:styles.marketplaceSetupStep} onClick={()=>setTab('tax')}><span className={styles.marketplaceSetupStepNumber}>03</span><span className={styles.marketplaceSetupStepCopy}><strong>Tax profile</strong><small>Classification and effective rates</small></span></button>
        <button type="button" className={tab==='distribution'?styles.marketplaceSetupStepActive:styles.marketplaceSetupStep} onClick={()=>setTab('distribution')}><span className={styles.marketplaceSetupStepNumber}>04</span><span className={styles.marketplaceSetupStepCopy}><strong>Revenue share</strong><small>Approve after-tax allocation</small></span></button>
        <button type="button" className={tab==='studio'?styles.marketplaceSetupStepActive:styles.marketplaceSetupStep} onClick={()=>setTab('studio')}><span className={styles.marketplaceSetupStepNumber}>05</span><span className={styles.marketplaceSetupStepCopy}><strong>Invoice design</strong><small>Approve and publish a template</small></span></button>
        <button type="button" className={tab==='discounts'?styles.marketplaceSetupStepActive:styles.marketplaceSetupStep} onClick={()=>setTab('discounts')}><span className={styles.marketplaceSetupStepNumber}>06</span><span className={styles.marketplaceSetupStepCopy}><strong>Discounts · optional</strong><small>Approve codes before use</small></span></button>
      </div>
      <p className={styles.marketplaceSetupNote}>HRMS rechecks saved finance state for launch readiness. Finance setup does not publish the course.</p>
    </section>}
    <nav className={styles.tabs} aria-label="Finance workspace">
      {tabs.map(([key,label,icon])=><button key={key} className={tab===key?styles.tabActive:styles.tab}
        onClick={()=>{setTab(key);if(key==='payments')void Promise.all([loadPaymentOrders(),loadProviderEvents()]);}}><span>{icon}</span>{label}</button>)}
    </nav>
    {loading&&!catalog?<section className={styles.panel}>Loading financial configuration…</section>:!catalog?<section className={styles.panel}>The Finance workspace is unavailable. Confirm HRMS owner access and check the API response.</section>:<>
      {tab==='overview'&&<section className={styles.stack}>
        <div className={styles.panel}>
          <div className={styles.panelHead}><div><h2>Financial position</h2>
            <p>Figures come from verified course orders, provider-confirmed refunds, approved invoices and recorded processor charges. Amounts below are in INR.</p></div></div>
          <div className={styles.metrics}>
            <article><span>Verified course sales</span><strong>{rupees(overview?.capturedSalesPaise)}</strong><small>Payments confirmed as captured; excludes pending checkouts</small></article>
            <article><span>Refunds processed</span><strong>{rupees(overview?.processedRefundsPaise)}</strong><small>Razorpay-confirmed refunds only</small></article>
            <article><span>Refunds awaiting confirmation</span><strong>{rupees(overview?.pendingRefundsPaise)}</strong><small>{overview?.pendingRefundCount??0} refund request(s); not yet treated as repaid</small></article>
            <article><span>Net collections after refunds</span><strong>{rupees(overview?.netCollectedAfterRefundsPaise)}</strong><small>Captured money less provider-confirmed refunds</small></article>
            <article><span>Output tax on invoices</span><strong>{rupees(overview?.invoicedOutputTaxPaise)}</strong><small>Tax recorded on verified course sales</small></article>
            <article><span>Tax on processed credit notes</span><strong>{rupees(overview?.processedCreditNoteTaxPaise)}</strong><small>Tax credits recorded on refund credit notes</small></article>
            <article><span>Net output tax</span><strong>{rupees(overview?.netOutputTaxBeforeInputCreditsPaise)}</strong><small>Before input-tax credits or other statutory adjustments</small></article>
            <article><span>Confirmed payment-processing charges</span><strong>{rupees((overview?.confirmedProcessorFeesPaise??0)+(overview?.confirmedProcessorFeeTaxPaise??0))}</strong><small>Provider fee plus tax; missing actual charges are shown below</small></article>
            <article><span>Pool before revenue shares</span><strong>{rupees(overview?.netPoolAfterTaxRefundsAndFeesPaise)}</strong><small>Net collections less net output tax and confirmed processor charges; may be negative while refunds/fees settle</small></article>
            <article><span>Payment fees still unconfirmed</span><strong>{overview?.paymentFeeReconciliationPending??'—'}</strong><small>Sales blocked from payout preparation until actual fees are recorded</small></article>
          </div>
          <p className={styles.note}>The net output-tax figure is an operational reconciliation, not a final GST return or tax-advice calculation. Input-tax credits, GST adjustments, withholding/TDS and any other statutory obligations must still be handled by the responsible finance/tax adviser.</p>
        </div>
        <div className={styles.metrics}>
          <article><span>Legal entities</span><strong>{overview?.legalEntities??'—'}</strong><small>Configured suppliers</small></article>
          <article><span>Tax profiles pending</span><strong>{overview?.pendingTaxProfiles??'—'}</strong><small>Requires HRMS review</small></article>
          <article><span>Share policies pending</span><strong>{overview?.pendingSharePolicies??'—'}</strong><small>Requires approval</small></article>
          <article><span>Issued fiscal documents</span><strong>{overview?.issuedDocuments??'—'}</strong><small>From the immutable ledger</small></article>
        </div>
        <div className={styles.twoCol}>
          <section className={styles.panel}><h2>Recommended workflow</h2><ol className={styles.steps}>
            <li><b>Verify the legal seller</b><span>Activate the legal identity and review its GST registration.</span></li>
            <li><b>Approve the offer tax profile</b><span>Use CA-approved classification and effective dates; never guess a rate.</span></li>
            <li><b>Approve institutional distribution</b><span>Set percentage or flat shares and verify the combined allocation fits the net pool.</span></li>
            <li><b>Preview and publish the invoice template</b><span>Published design versions apply to new invoices only.</span></li>
          </ol></section>
          <section className={styles.panel}><h2>Control boundary</h2><p>These actions require the explicit <code>QE_PLATFORM_OWNER</code> capability in the HRMS workspace. An education-admin role, job title or tenant login is not enough.</p><p className={styles.note}>Production issuing and actual payout execution are deliberately separate release gates. This workspace does not create a charge or payout just by saving a policy.</p></section>
        </div>
      </section>}
      {tab==='entities'&&<section className={styles.twoCol}>
        <div className={styles.panel}><h2>{editingCoreEntityId?'Edit legal entity':'Legal entities'}</h2><p>{editingCoreEntityId?'Edit draft details before activating the supplier. Core details lock after activation.':'Create the legal supplier record, then verify its details before activation.'}</p>
          <form className={styles.form} onSubmit={saveEntity}>
            <label>Entity scope<select disabled={editingCoreEntityId!==null} value={entityForm.entityKind} onChange={e=>setEntityForm(v=>({...v,entityKind:e.target.value}))}><option value="PLATFORM">Platform legal entity (PureDeal)</option><option value="COMPANY">Other company</option><option value="INSTITUTION">Institution legal entity</option></select></label>
            {entityForm.entityKind==='INSTITUTION'&&<label>Institution<select disabled={editingCoreEntityId!==null} required value={entityForm.institutionId} onChange={e=>setEntityForm(v=>({...v,institutionId:e.target.value}))}><option value="">Select institution</option>{catalog.institutions.map(i=><option key={i.id} value={i.id}>{i.display_name} · {i.tenant_code}</option>)}</select></label>}
            <label>Legal name<input required value={entityForm.legalName} onChange={e=>setEntityForm(v=>({...v,legalName:e.target.value}))}/></label>
            <label>Trade name<input value={entityForm.tradeName} onChange={e=>setEntityForm(v=>({...v,tradeName:e.target.value}))}/></label>
            <label>Finance contact email<input type="email" value={entityForm.contactEmail} onChange={e=>setEntityForm(v=>({...v,contactEmail:e.target.value}))}/></label>
            <label>Finance contact phone<input type="tel" value={entityForm.contactPhone} onChange={e=>setEntityForm(v=>({...v,contactPhone:e.target.value}))}/></label>
            <label>Invoice series prefix<input required minLength={1} maxLength={3} pattern="[A-Za-z0-9_-]{1,3}" value={entityForm.invoiceSeriesCode} onChange={e=>setEntityForm(v=>({...v,invoiceSeriesCode:e.target.value.toUpperCase()}))}/><small>Printed in the official invoice number, e.g. QE/26-27/000001 (max 16 characters).</small></label>
            <label>Registered address line<input required value={entityForm.addressLine} onChange={e=>setEntityForm(v=>({...v,addressLine:e.target.value}))}/></label>
            <div className={styles.inputGrid}><label>City<input required value={entityForm.city} onChange={e=>setEntityForm(v=>({...v,city:e.target.value}))}/></label><label>State<input required value={entityForm.state} onChange={e=>setEntityForm(v=>({...v,state:e.target.value}))}/></label><label>PIN code<input required value={entityForm.postalCode} onChange={e=>setEntityForm(v=>({...v,postalCode:e.target.value}))}/></label></div>
            <div className={styles.buttonStack}><button className={styles.primary} disabled={busy}>{editingCoreEntityId?'Save legal entity changes':'Save legal entity draft'}</button>{editingCoreEntityId!==null&&<button type="button" className={styles.secondary} onClick={()=>{setEditingCoreEntityId(null);setEntityForm({entityKind:'PLATFORM',institutionId:'',legalName:'',tradeName:'',contactEmail:'',contactPhone:'',addressLine:'',city:'',state:'',postalCode:'',invoiceSeriesCode:'QE'});}}>Cancel</button>}</div>
          </form>
          <div className={styles.entityList}>{catalog.legalEntities.map(e=><article key={e.id} className={styles.entityRow}>
            <div className={styles.entitySummary}>
              <b>{e.legal_name}</b><span>{e.entity_kind==='INSTITUTION'?e.institution_name:'Platform legal entity'} · {e.status}</span>
              {e.trade_name&&<small>Trade name: {e.trade_name}</small>}{e.contact_email&&<small>Finance email: {e.contact_email}</small>}{e.contact_phone&&<small>Finance phone: {e.contact_phone}</small>}
              <small>Registered address: {addressText(e.registered_address_json)||'Not recorded'}</small><small>{e.active_gst_registrations||0} verified GST registration(s)</small>
              <small>Invoice series: <b>{e.invoice_series_code||'QE'}</b></small>
              <small>e-Invoice: <b>{e.einvoice_applicability||'UNREVIEWED'}</b>{e.einvoice_applicability_reviewed_at ? ' · reviewed '+new Date(e.einvoice_applicability_reviewed_at).toLocaleString() : ''}</small>
            </div>
            <div className={styles.entityActions}>
              {editingEntityId===e.id ? <>
                <button className={styles.secondarySmall} disabled={busy||(seriesDrafts[String(e.id)]??e.invoice_series_code??'QE').trim().toUpperCase()===(e.invoice_series_code||'QE')} onClick={()=>void saveInvoiceSeries(e)}>Save series</button>
                <button className={styles.secondarySmall} disabled={busy||((einvoiceDrafts[String(e.id)]??e.einvoice_applicability??'UNREVIEWED')===(e.einvoice_applicability??'UNREVIEWED')&&(einvoiceNotes[String(e.id)]??e.einvoice_applicability_review_note??'').trim()===(e.einvoice_applicability_review_note??'').trim())} onClick={()=>saveEinvoiceApplicability(e)}>Save e-Invoice review</button>
                <button className={styles.secondarySmall} disabled={busy} onClick={()=>setEditingEntityId(null)}>Done</button>
              </> : <>
                {e.status==='DRAFT'&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>editEntityDetails(e)}>Edit details</button>}
                <button className={styles.secondarySmall} disabled={busy} onClick={()=>{setEditingEntityId(e.id);setSeriesDrafts(v=>({...v,[String(e.id)]:e.invoice_series_code||'QE'}));setEinvoiceDrafts(v=>({...v,[String(e.id)]:e.einvoice_applicability||'UNREVIEWED'}));setEinvoiceNotes(v=>({...v,[String(e.id)]:e.einvoice_applicability_review_note||''}));}}>Edit settings</button>
                {e.status==='DRAFT'&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>void action(()=>api(BASE+'/legal-entities/'+e.id+'/activate',{method:'POST'}),'Legal entity activated after HRMS review.')}>Activate</button>}
              </>}
            </div>
            {editingEntityId===e.id && <div className={styles.einvoiceReview}>
              <label>Invoice series prefix<input maxLength={3} value={seriesDrafts[String(e.id)]??e.invoice_series_code??'QE'} onChange={event=>setSeriesDrafts(old=>({...old,[String(e.id)]:event.target.value.toUpperCase()}))}/></label>
              <label>Reviewed applicability
                <select value={einvoiceDrafts[String(e.id)]??e.einvoice_applicability??'UNREVIEWED'} onChange={event=>setEinvoiceDrafts(old=>({...old,[String(e.id)]:event.target.value}))}>
                  <option value="UNREVIEWED" disabled>Not reviewed</option>
                  <option value="NOT_REQUIRED">Not required — reviewed</option>
                  <option value="REQUIRED">Required — block checkout</option>
                </select>
              </label>
              <label>Review rationale / tax-adviser reference
                <input maxLength={1000} value={einvoiceNotes[String(e.id)]??e.einvoice_applicability_review_note??''} placeholder="Reference and reason for the seller's applicability decision" onChange={event=>setEinvoiceNotes(old=>({...old,[String(e.id)]:event.target.value}))}/>
              </label>
              <small className={styles.einvoiceHelp}>Human review only; this does not validate against the GST portal. REQUIRED keeps checkout blocked until IRP/IRN and signed-QR verification are integrated.</small>
            </div>}
          </article>)}</div>
        </div>
        <div className={styles.panel}><h2>GST registrations</h2><p>Enter registration details, then independently review and mark them verified. This action records an HRMS review; it does not claim an automated GST-portal validation.</p>
          <form className={styles.form} onSubmit={createRegistration}>
            <label>Legal entity<select required value={gstEntityId} onChange={e=>setGstEntityId(e.target.value)}><option value="">Select legal entity</option>{catalog.legalEntities.map(e=><option key={e.id} value={e.id}>{e.legal_name} · {e.status}</option>)}</select></label>
            <label>GSTIN<input required maxLength={15} minLength={15} value={gstForm.gstin} onChange={e=>setGstForm(v=>({...v,gstin:e.target.value.toUpperCase()}))}/></label>
            <label>Name on GST registration<input required value={gstForm.legalName} onChange={e=>setGstForm(v=>({...v,legalName:e.target.value}))}/></label>
            <div className={styles.inputGrid}><label>GST state code<input required maxLength={2} value={gstForm.stateCode} onChange={e=>setGstForm(v=>({...v,stateCode:e.target.value}))}/></label><label>Effective from<input type="date" required value={gstForm.effectiveFrom} onChange={e=>setGstForm(v=>({...v,effectiveFrom:e.target.value}))}/></label></div>
            <label>Registered address<input required value={gstForm.addressLine} onChange={e=>setGstForm(v=>({...v,addressLine:e.target.value}))}/></label>
            <div className={styles.inputGrid}><label>City<input required value={gstForm.city} onChange={e=>setGstForm(v=>({...v,city:e.target.value}))}/></label><label>State<input required value={gstForm.state} onChange={e=>setGstForm(v=>({...v,state:e.target.value}))}/></label><label>PIN code<input required value={gstForm.postalCode} onChange={e=>setGstForm(v=>({...v,postalCode:e.target.value}))}/></label></div>
            <button className={styles.primary} disabled={busy}>Save pending registration</button>
          </form>
          <div className={styles.entityList}>{catalog.registrations.map(r=><article className={styles.entityRow} key={r.id}>
            <div><b>{r.legal_entity_name}</b><span>••••••••{r.gstin.slice(-4)} · State {r.state_code}</span><small>{r.status} · From {String(r.effective_from).slice(0,10)}</small></div>
            {r.status==='PENDING'&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>void action(()=>api(BASE+'/gst-registrations/'+r.id+'/verify',{method:'POST'}),'GST registration marked verified by HRMS.')}>Mark verified</button>}
          </article>)}</div>
        </div>
      </section>}
      {tab==='tax'&&<section className={styles.twoCol}>
        <div className={styles.panel}><h2>Offer tax profile</h2><p>Enter only the service classification and rates approved for this exact seller and sale. No default GST rate is preselected.</p>
          <form className={styles.form} onSubmit={createTaxProfile}>
            <label>Course offer<select required value={taxForm.offerId} onChange={e=>selectWorkflowOffer(e.target.value)}><option value="">Select offer</option>{catalog.offers.filter(o=>o.current_candidate||o.publicly_sellable).map(o=><option key={o.offer_id} value={o.offer_id}>{o.institution_name} · {o.course_code} · v{o.content_version_no} · release {o.release_version}</option>)}</select></label>
            {catalog.offers.length===0&&<p className={styles.empty}>No current course offer exists yet. <a href="/institutions">Open HRMS Course launch</a>, complete content and source approval, then create the listing draft with its title, description and INR price. The offer will appear here once that draft exists. Obsolete legacy offers are intentionally not selectable.</p>}
            <label>Legal seller<select required value={taxForm.legalEntityId} onChange={e=>setTaxForm(v=>({...v,legalEntityId:e.target.value}))}><option value="">Select legal seller</option>{catalog.legalEntities.filter(e=>e.entity_kind==='PLATFORM'||(e.entity_kind==='INSTITUTION'&&e.institution_id===selectedOffer?.institution_id)).map(e=><option key={e.id} value={e.id}>{e.legal_name} · {e.status}</option>)}</select></label>
            <label>Service classification / SAC<input value={taxForm.serviceClassificationCode} onChange={e=>setTaxForm(v=>({...v,serviceClassificationCode:e.target.value}))} placeholder="Enter CA-approved code"/></label>
            <label>Tax treatment<select value={taxForm.taxTreatment} onChange={e=>setTaxForm(v=>({...v,taxTreatment:e.target.value}))}><option value="TAXABLE">Taxable</option><option value="EXEMPT">Exempt</option><option value="NIL_RATED">Nil-rated</option><option value="NON_GST">Non-GST</option><option value="COMPOSITION">Composition treatment</option></select></label>
            {taxForm.taxTreatment==='TAXABLE'&&<div className={styles.inputGrid}><label>GST rate (%)<input required type="number" min="0.01" max="100" step="0.01" value={taxForm.gstPercent} onChange={e=>setTaxForm(v=>({...v,gstPercent:e.target.value}))}/></label><label>Cess rate (%)<input type="number" min="0" max="100" step="0.01" value={taxForm.cessPercent} onChange={e=>setTaxForm(v=>({...v,cessPercent:e.target.value}))}/></label></div>}
            <label>Price basis<select value={taxForm.priceIncludesTax?'INCLUSIVE':'EXCLUSIVE'} onChange={e=>setTaxForm(v=>({...v,priceIncludesTax:e.target.value==='INCLUSIVE'}))}><option value="INCLUSIVE">Listed price includes tax</option><option value="EXCLUSIVE">Tax added to listed price</option></select></label>
            <label>Place-of-supply rule<select value={taxForm.placeOfSupplyRule} onChange={e=>setTaxForm(v=>({...v,placeOfSupplyRule:e.target.value}))}><option value="RECIPIENT_LOCATION">Recipient location</option><option value="SUPPLIER_LOCATION">Supplier location</option><option value="CA_APPROVED_CUSTOM">CA-approved custom rule</option></select></label>
            <label>Effective from<input type="datetime-local" required value={taxForm.effectiveFrom} onChange={e=>setTaxForm(v=>({...v,effectiveFrom:e.target.value}))}/></label>
            <button className={styles.primary} disabled={busy}>Save draft and submit for review</button>
          </form>
        </div>
        <div className={styles.panel}><h2>Saved tax profiles</h2><label>Course offer<select value={taxForm.offerId} onChange={e=>selectWorkflowOffer(e.target.value)}><option value="">Select offer</option>{catalog.offers.filter(o=>o.current_candidate||o.publicly_sellable).map(o=><option key={o.offer_id} value={o.offer_id}>{o.institution_name} · {o.course_code} · v{o.content_version_no} · release {o.release_version}</option>)}</select></label>
          {(taxProfiles[taxForm.offerId]||[]).map(p=><article className={styles.policyRow} key={p.id}><div><b>Version {p.version} · {p.tax_treatment}</b><span>{p.status} · GST {Number(p.gst_rate_basis_points)/100}%</span><small>Linked seller: {p.legal_entity_id ? '#'+p.legal_entity_id+' · ' : ''}{p.legal_seller_name||'Missing legal entity'} · {p.legal_seller_status||'UNKNOWN'}</small><small>Effective: {p.effective_from?new Date(p.effective_from).toLocaleString('en-IN'):'Not recorded'} – {p.effective_until?new Date(p.effective_until).toLocaleString('en-IN'):'No end date'}</small></div>{p.status==='DRAFT'&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>void submitTaxProfile(Number(p.id),taxForm.offerId)}>Submit</button>}{p.status==='PENDING_APPROVAL'&&<button type="button" className={styles.secondarySmall} disabled={busy} onClick={()=>void approveTaxProfile(Number(p.id),taxForm.offerId)}>Approve</button>}</article>)}
          {!taxProfiles[taxForm.offerId]?.length&&<p className={styles.empty}>No tax profile saved for this offer yet. An active legal entity and GST registration alone do not link a seller to this course offer; create a tax profile for this offer using the intended seller, then submit and approve it.</p>}
          <p className={styles.note}>For revenue-share approval, the exact offer needs one approved tax profile effective on the policy start date, linked to an active seller. TAXABLE treatment also requires an active, HRMS-verified GST registration covering that date. These finance steps do not publish the course.</p>
        </div>
      </section>}
      {tab==='distribution'&&<section className={styles.stack}>
        <section className={styles.panel}><div className={styles.panelHead}><div><h2>Institution revenue-share policies</h2><p>Shares are calculated from actual captured consideration minus the invoice's approved output-tax amount—not list price.</p></div><button className={styles.primary} disabled={busy||!selectedOfferId} onClick={createPolicy}>＋ New policy</button></div>
          <label className={styles.wideLabel}>Course offer<select value={selectedOfferId} onChange={e=>selectWorkflowOffer(e.target.value)}><option value="">Select offer</option>{catalog.offers.filter(o=>o.current_candidate||o.publicly_sellable).map(o=><option key={o.offer_id} value={o.offer_id}>{o.institution_name} · {o.course_name}</option>)}</select></label>
          {catalog.offers.length===0&&<p className={styles.empty}><a href="/institutions">Open HRMS Course launch</a> and create the course’s listing draft first. Revenue-share policies bind to an exact offer revision, never to an unlaunched course identity.</p>}
          {policies.length?<div className={styles.policyTableWrap}><table className={styles.table}><thead><tr><th>Course / institution</th><th>Version</th><th>Status</th><th>Recipients</th><th>Action</th></tr></thead><tbody>{policies.map(p=><tr key={p.id}><td><b>{p.course_name}</b><small>{p.institution_name}</small></td><td>v{p.policy_version}</td><td><span className={styles.status}>{p.status}</span></td><td>{p.recipient_count}</td><td><button className={styles.secondarySmall} onClick={()=>void loadPolicy(String(p.id))}>Open</button></td></tr>)}</tbody></table></div>:<p className={styles.empty}>No revenue-share policies have been created.</p>}
        </section>
        {policyDetail&&<section className={styles.panel}><div className={styles.panelHead}><div><h2>Policy v{policyDetail.policy_version}: {policyDetail.course_name}</h2><p>Source offer: {policyDetail.institution_name} · Currency {policyDetail.currency} · Status {policyDetail.status}</p></div>{policyDetail.status==='DRAFT'&&<button className={styles.secondary} onClick={()=>void saveRecipients()} disabled={busy||recipientsUnchanged}>Save recipients</button>}</div>
          {policyDetail.status==='DRAFT'?<><p>Each institution receives either a percentage of the net distributable pool or a fixed amount per eligible paid sale. Duplicate institutions are not permitted.</p>
            <p>Shares are calculated after tax and confirmed payment-processing charges. The selected remainder recipient receives what remains after all explicit shares; a company may receive both amounts.</p>
            <div className={styles.form}>
              <label>Who receives the remaining balance?<select value={policyRemainderType+':'+policyRemainderId} onChange={e=>{const [type,id]=e.target.value.split(':');setPolicyRemainderType(type as 'INSTITUTION'|'COMPANY');setPolicyRemainderId(id||'');}}>
                <option value="">Choose remainder recipient</option>
                {catalog.institutions.filter(x=>x.status==='ACTIVE').map(x=><option key={'I'+x.id} value={'INSTITUTION:'+x.id}>Institution · {x.display_name}</option>)}
                {catalog.legalEntities.filter(x=>x.status==='ACTIVE'&&['PLATFORM','COMPANY'].includes(x.entity_kind)).map(x=><option key={'C'+x.id} value={'COMPANY:'+x.id}>Company · {x.legal_name}</option>)}
              </select></label>
            </div>
            <div className={styles.recipientList}>{recipients.map((r,i)=><div className={styles.recipientEditor} key={i}>
              <label>Beneficiary type<select value={r.recipientType} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,recipientType:e.target.value as RecipientDraft['recipientType'],recipientId:''}:x))}><option value="INSTITUTION">Institution</option><option value="COMPANY">Company</option></select></label>
              <label>Beneficiary<select value={r.recipientId} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,recipientId:e.target.value}:x))}><option value="">Select beneficiary</option>
                {r.recipientType==='INSTITUTION'?catalog.institutions.filter(x=>x.status==='ACTIVE').map(x=><option key={x.id} value={x.id}>{x.display_name}</option>)
                  :catalog.legalEntities.filter(x=>x.status==='ACTIVE'&&['PLATFORM','COMPANY'].includes(x.entity_kind)).map(x=><option key={x.id} value={x.id}>{x.legal_name}</option>)}
              </select></label>
              <label>Share type<select value={r.calculationType} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,calculationType:e.target.value as RecipientDraft['calculationType']}:x))}><option value="PERCENTAGE">Percentage</option><option value="FLAT">Flat ₹ amount</option></select></label>
              {r.calculationType==='PERCENTAGE'?<label>Pool share (%)<input type="number" min=".01" max="100" step=".01" value={r.percentage} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,percentage:e.target.value}:x))}/></label>:<label>Flat amount per sale (₹)<input type="number" min=".01" step=".01" value={r.flatRupees} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,flatRupees:e.target.value}:x))}/></label>}
              <button className={styles.removeRow} type="button" aria-label="Remove beneficiary" onClick={()=>setRecipients(old=>old.filter((_,j)=>j!==i))}>×</button>
            </div>)}</div>
            <button className={styles.secondary} onClick={()=>setRecipients(old=>[...old,{recipientType:'INSTITUTION',recipientId:'',calculationType:'PERCENTAGE',percentage:'',flatRupees:''}])}>＋ Add beneficiary</button>
            <div className={styles.policyFooter}><span>HRMS checks that approved shares fit the pool at checkout. A missing/inactive bank account blocks payout, not the historical allocation.</span><button className={styles.primary} disabled={busy||!recipients.length||!policyRemainderId} onClick={()=>void action(async()=>{await api(BASE+'/revenue-share/policies/'+selectedPolicyId+'/recipients',{method:'PUT',body:JSON.stringify({recipients:recipientPayload(),remainderRecipientType:policyRemainderType,remainderRecipientId:Number(policyRemainderId)})});await api(BASE+'/revenue-share/policies/'+selectedPolicyId+'/submit',{method:'POST'});},'Policy submitted for HRMS approval.')}>Submit policy</button></div>
          </>:policyDetail.status==='PENDING_APPROVAL'?<div className={styles.approvalBox}><p>This policy is waiting for approval. Confirm its parties, effective dates and contract references before activating it.</p><button className={styles.primary} disabled={busy} onClick={()=>void action(()=>api(BASE+'/revenue-share/policies/'+selectedPolicyId+'/approve',{method:'POST'}),'Revenue-share policy approved.')}>Approve policy</button></div>:<div className={styles.recipientList}>{(policyDetail.recipients||[]).map((r,i)=><article className={styles.policyRow} key={i}><b>{r.recipient_name||(r.recipient_type==='COMPANY'?'Company':'Institution')+' #'+(r.recipient_type==='COMPANY'?r.legal_entity_id:r.institution_id)}</b><span>{r.calculation_type==='PERCENTAGE'?Number(r.percentage_basis_points)/100+'% of distributable pool':rupees(r.flat_amount_paise||0)+' per eligible sale'}</span></article>)}</div>}
        </section>}
      </section>}
      {tab==='discounts'&&<section className={styles.stack}>
        <section className={styles.panel}>
          <div className={styles.panelHead}><div><h2>Course offer discount codes</h2><p>HRMS approves a versioned percentage or flat discount for one exact institution offer. Uses are reserved before checkout and become redeemed only after verified payment.</p></div></div>
          <form className={styles.form} onSubmit={event=>{event.preventDefault();createDiscount();}}>
            <label>Institution course offer<select required value={discountForm.offerId} onChange={event=>selectWorkflowOffer(event.target.value)}><option value="">Select course offer</option>{catalog.offers.filter(o=>o.currency==='INR'&&(o.current_candidate||o.publicly_sellable)).map(o=><option key={o.offer_id} value={o.offer_id}>{o.institution_name} · {o.course_name} · {rupees(o.price_paise,o.currency)}</option>)}</select></label>
            <div className={styles.inputGrid}><label>Offer code<input required minLength={3} maxLength={40} pattern="[A-Za-z0-9][A-Za-z0-9_-]{2,39}" value={discountForm.code} onChange={event=>setDiscountForm(v=>({...v,code:event.target.value.toUpperCase()}))} placeholder="WELCOME10"/></label><label>Discount type<select value={discountForm.discountType} onChange={event=>setDiscountForm(v=>({...v,discountType:event.target.value}))}><option value="PERCENTAGE">Percentage</option><option value="FLAT">Flat amount</option></select></label>{discountForm.discountType==='PERCENTAGE'?<label>Percentage (%)<input type="number" required min="0.01" max="100" step="0.01" value={discountForm.percentage} onChange={event=>setDiscountForm(v=>({...v,percentage:event.target.value}))}/></label>:<label>Flat discount (₹)<input type="number" required min="0.01" step="0.01" value={discountForm.flatAmountRupees} onChange={event=>setDiscountForm(v=>({...v,flatAmountRupees:event.target.value}))}/></label>}</div>
            <div className={styles.inputGrid}><label>Maximum discount cap (₹, optional)<input type="number" min="0.01" step="0.01" value={discountForm.maxDiscountRupees} onChange={event=>setDiscountForm(v=>({...v,maxDiscountRupees:event.target.value}))} placeholder="No extra cap"/></label><label>Minimum listed price (₹)<input type="number" min="0" step="0.01" value={discountForm.minListedPriceRupees} onChange={event=>setDiscountForm(v=>({...v,minListedPriceRupees:event.target.value}))}/></label><label>Total redemption limit (optional)<input type="number" min="1" step="1" value={discountForm.maxRedemptions} onChange={event=>setDiscountForm(v=>({...v,maxRedemptions:event.target.value}))} placeholder="Unlimited"/></label></div>
            <div className={styles.inputGrid}><label>Uses per purchaser (optional)<input type="number" min="1" step="1" value={discountForm.maxRedemptionsPerPurchaser} onChange={event=>setDiscountForm(v=>({...v,maxRedemptionsPerPurchaser:event.target.value}))} placeholder="Unlimited"/></label><label>Starts at<input required type="datetime-local" value={discountForm.effectiveFrom} onChange={event=>setDiscountForm(v=>({...v,effectiveFrom:event.target.value}))}/></label><label>Ends at (optional)<input type="datetime-local" value={discountForm.effectiveUntil} onChange={event=>setDiscountForm(v=>({...v,effectiveUntil:event.target.value}))}/></label></div>
            <p className={styles.note}>Saving creates a new immutable draft version and submits it for approval. Approval rejects overlapping active versions of the same code for the same course. No code is usable before approval.</p>
            <button className={styles.primary} disabled={busy||!catalog.offers.length}>Create & submit discount for approval</button>
          </form>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHead}><div><h2>Discount policy ledger</h2><p>Version history, approval status and redemption counts are visible to the platform finance owner.</p></div><button className={styles.secondary} type="button" disabled={busy} onClick={()=>void loadAll()}>Refresh</button></div>
          {discounts.length?<div className={styles.policyTableWrap}><table className={styles.table}><thead><tr><th>Code / course</th><th>Rule</th><th>Validity</th><th>Use limits</th><th>Status / action</th></tr></thead><tbody>{discounts.map(d=><tr key={d.id}>
            <td><b>{d.code} · v{d.version}</b><small>{d.institution_name} · {d.course_name}</small></td>
            <td>{d.discount_type==='PERCENTAGE'?Number(d.percentage_basis_points||0)/100+'%':rupees(d.flat_amount_paise||0,d.currency)}{d.max_discount_paise ? ' · cap '+rupees(d.max_discount_paise,d.currency) : ''}<small>Minimum {rupees(d.min_listed_price_paise,d.currency)}</small></td>
            <td>{new Date(d.effective_from).toLocaleString()}<small>{d.effective_until?'Until '+new Date(d.effective_until).toLocaleString():'No end date'}</small></td>
            <td>{d.redemption_count}/{d.max_redemptions??'∞'}<small>Per purchaser: {d.max_redemptions_per_purchaser??'∞'}</small></td>
            <td><span className={styles.status}>{d.status}</span><div className={styles.discountActions}>
              {d.status==='DRAFT'&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>submitDiscount(d.id)}>Submit</button>}
              {d.status==='PENDING_APPROVAL'&&<button className={styles.primarySmall} disabled={busy} onClick={()=>approveDiscount(d.id)}>Approve</button>}
              {['DRAFT','PENDING_APPROVAL','APPROVED'].includes(d.status)&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>retireDiscount(d.id)}>Retire</button>}
            </div></td>
          </tr>)}</tbody></table></div>:<div className={styles.emptyLarge}><span>%</span><b>No discount codes configured</b><p>Create a draft and approve it in HRMS. No demo coupons are seeded.</p></div>}
        </section>
      </section>}
      {tab==='studio'&&<section className={styles.studio}>
        <section className={styles.panel}><h2>Invoice Design Studio</h2><p>Make a new draft or create a version from a published design. The preview is a watermarked sample; it never issues an invoice or consumes a legal number.</p>
          <label>Existing template<select value={selectedTemplateId} onChange={e=>selectTemplate(e.target.value)}><option value="">{marketplaceSetupContext?'Choose an existing template or create new…':'Create a new template…'}</option>{templates.map(t=><option key={t.id} value={t.id}>{t.internal_name} · {t.scope} · v{t.version_no} ({t.version_status})</option>)}</select></label>
          {!selectedTemplateId&&<><label>Template name<input value={templateName} onChange={e=>setTemplateName(e.target.value)}/></label><label>Template scope<select value={templateScope} onChange={e=>{const nextScope=e.target.value;setTemplateScope(nextScope);if(nextScope==='SELLER'&&selectedOffer?.legal_entity_id)setTemplateEntityId(String(selectedOffer.legal_entity_id));}}><option value="PLATFORM">Platform-wide invoice design</option><option value="SELLER">Seller-specific invoice design</option></select></label>{marketplaceSetupContext&&<p className={styles.marketplaceSetupInlineHint}>Platform-wide templates can serve multiple sellers. Choose seller-specific when the selected course uses a particular legal seller.</p>}{templateScope==='SELLER'&&<label>Legal seller<select value={templateEntityId} onChange={e=>setTemplateEntityId(e.target.value)}><option value="">Select seller</option>{catalog.legalEntities.map(e=><option key={e.id} value={e.id}>{e.legal_name}</option>)}</select></label>}</>}
          {selectedTemplate&&<div className={styles.versionBanner}><span>Editing v{selectedVersionNo||selectedTemplate.version_no} · {selectedTemplate.version_status}</span>{['PUBLISHED','RETIRED'].includes(selectedTemplate.version_status||'')&&<button className={styles.secondarySmall} onClick={startTemplateVersion} disabled={busy}>＋ New draft version</button>}</div>}
          <div className={styles.designControls}><label>Primary colour<input type="color" disabled={!activeDraft} value={design.primaryColor} onChange={e=>setDesign(v=>({...v,primaryColor:e.target.value.toUpperCase()}))}/></label><label>Accent colour<input type="color" disabled={!activeDraft} value={design.accentColor} onChange={e=>setDesign(v=>({...v,accentColor:e.target.value.toUpperCase()}))}/></label></div>
          <label>Paper surface<select disabled={!activeDraft} value={design.surfaceStyle} onChange={e=>setDesign(v=>({...v,surfaceStyle:e.target.value}))}><option value="WARM_PAPER">Warm premium paper</option><option value="CLEAN_WHITE">Clean white</option><option value="SOFT_SLATE">Soft slate</option></select></label>
          <label>Layout<select disabled={!activeDraft} value={design.layout} onChange={e=>setDesign(v=>({...v,layout:e.target.value}))}><option value="EDITORIAL">Editorial / premium</option><option value="CLASSIC">Classic / formal</option></select></label>
          <label className={styles.checkLabel}><input type="checkbox" disabled={!activeDraft} checked={design.showLogo} onChange={e=>setDesign(v=>({...v,showLogo:e.target.checked}))}/> Show approved supplier logo where configured</label>
          <label>Footer copy<textarea disabled={!activeDraft} rows={3} maxLength={500} value={design.footerText} onChange={e=>setDesign(v=>({...v,footerText:e.target.value}))}/></label>
          <div className={styles.buttonStack}>
            {!selectedTemplate?<button className={styles.primary} disabled={busy||!templateName.trim()||(templateScope==='SELLER'&&!templateEntityId)} onClick={createTemplate}>Create draft template</button>:<>
              {selectedTemplate.version_status==='DRAFT'&&<><button className={styles.secondary} disabled={busy||designUnchanged} onClick={saveTemplate}>Save draft</button><button className={styles.primary} disabled={busy} onClick={submitTemplate}>Submit for approval</button></>}
              {selectedTemplate.version_status==='PENDING_APPROVAL'&&<button className={styles.primary} disabled={busy} onClick={approveTemplate}>Approve version</button>}
              {selectedTemplate.version_status==='APPROVED'&&<button className={styles.primary} disabled={busy} onClick={publishTemplate}>Publish approved version</button>}
            </>}
          </div>
          <p className={styles.note}>Published template versions are immutable for the purpose of historical invoices. Actual statutory documents also snapshot seller, recipient, tax, line items and totals separately.</p>
        </section>
        <section className={styles.previewPanel}><div className={styles.previewHeader}><div><span className={styles.eyebrow}>LIVE PREVIEW</span><h2>Invoice document</h2></div><div className={styles.previewActions}><span className={styles.samplePill}>SAMPLE · NOT ISSUED</span><button type="button" className={styles.secondarySmall} disabled={previewPdfLoading} onClick={()=>void generatePdfPreview()}>{previewPdfLoading?'Rendering PDF…':'Open PDF preview'}</button></div></div>
          <div className={styles.invoicePreviewGrid} aria-label="Same selected invoice design in light and dark workspace themes">
            <section className={styles.invoiceThemeFrame} data-preview-theme="light">
              <div className={styles.invoiceThemeLabel}>Light workspace</div>
              <div className={styles.paperWrap}>{invoiceSample}</div>
            </section>
            <section className={styles.invoiceThemeFrame} data-preview-theme="dark">
              <div className={styles.invoiceThemeLabel}>Dark workspace</div>
              <div className={styles.paperWrap}>{invoiceSample}</div>
            </section>
          </div>
          <div className={styles.previewNote}><span>◎</span><p>Both workspace previews show the same explicitly selected invoice design. Light/dark HRMS appearance never recolours the document; the generated PDF follows this design. This sample does not fabricate a GSTIN, legal invoice number, tax rate or real customer data.</p></div>
          {previewPdfUrl&&<section className={styles.pdfPreviewPanel}><div className={styles.pdfPreviewToolbar}><b>PDF output preview · sample only</b><div><a href={previewPdfUrl} download="quantaedge-invoice-preview.pdf">Download preview PDF</a><button type="button" className={styles.secondarySmall} onClick={()=>setPreviewPdfUrl(null)}>Close</button></div></div><iframe title="Invoice design PDF preview" src={previewPdfUrl}/></section>}
        </section>
      </section>}
      {tab==='documents'&&<section className={styles.panel}><div className={styles.panelHead}><div><h2>Issued fiscal documents</h2><p>HRMS audit view for issued documents. Download the stored, hash-verified PDF or open its transaction snapshot.</p></div><button className={styles.secondary} onClick={()=>void loadAll()} disabled={busy}>Refresh records</button></div>
        {documents.length?<div className={styles.policyTableWrap}><table className={styles.table}><thead><tr><th>Document</th><th>Issued</th><th>Tax</th><th>Total</th><th>Actions</th></tr></thead><tbody>{documents.map(d=><tr key={d.id}><td><b>{d.document_number}</b><small>{d.document_type} · {d.source_type}</small></td><td>{new Date(d.issued_at).toLocaleDateString('en-IN')}</td><td>{rupees(d.tax_value_paise,d.currency)}</td><td>{rupees(d.total_value_paise,d.currency)}</td><td><div className={styles.documentActions}>{d.pdf_available?<button type="button" className={styles.secondarySmall} disabled={downloadingDocumentId!==null} onClick={()=>void downloadDocument(d)}>{downloadingDocumentId===d.id?'Preparing…':'Download PDF'}</button>:'Not rendered'}{d.source_type==='COURSE_ORDER'&&['TAX_INVOICE','BILL_OF_SUPPLY'].includes(d.document_type)&&<button type="button" className={styles.secondarySmall} onClick={()=>selectRefundDocument(d)}>Refund / credit note</button>}</div></td></tr>)}</tbody></table></div>:<div className={styles.emptyLarge}><span>▤</span><b>No fiscal documents issued yet</b><p>Only invoices created by the captured-payment finalizer appear here. The live Studio preview never consumes a legal number or creates an issued invoice.</p></div>}
        {selectedRefundDocument&&<section className={styles.refundPanel}>
          <div className={styles.panelHead}><div><h2>Refund & credit note</h2><p>{selectedRefundDocument.document_number} · order {selectedRefundDocument.source_record_key}. Only a provider-confirmed processed refund creates a credit note.</p></div><button type="button" className={styles.secondarySmall} onClick={()=>{setSelectedRefundDocId(null);setRefundHistory([]);setRefundResult(null);}} disabled={refundBusy}>Close</button></div>
          <form className={styles.refundForm} onSubmit={event=>{event.preventDefault();void submitRefund();}}>
            <label>Refund amount (₹)<input inputMode="decimal" autoComplete="off" maxLength={12} value={refundDraft.amountRupees} onChange={event=>setRefundDraft(v=>({...v,amountRupees:event.target.value}))} placeholder="e.g. 499.00" required disabled={refundBusy}/></label>
            <label>Reason for refund<input maxLength={500} value={refundDraft.reason} onChange={event=>setRefundDraft(v=>({...v,reason:event.target.value}))} placeholder="Explain the approved refund reason" required disabled={refundBusy}/></label>
            <button type="submit" className={styles.primary} disabled={refundBusy}>{refundBusy?'Submitting…':'Submit / retry refund'}</button>
          </form>
          {refundResult&&<p className={styles.refundNote} role="status">Latest result: <b>{refundResult.status}</b>{refundResult.provider_status?' · Provider: '+refundResult.provider_status:''}{refundResult.credit_note_number?' · Credit note '+refundResult.credit_note_number:''}{refundResult.message?' · '+refundResult.message:''}</p>}
          <div className={styles.panelHead}><div><h3>Refund history</h3><p>Amounts in this list remain reserved until processed or conclusively failed.</p></div><button type="button" className={styles.secondarySmall} onClick={()=>void loadRefundHistory(Number(selectedRefundDocument.source_record_key))} disabled={refundHistoryLoading||refundBusy}>{refundHistoryLoading?'Refreshing…':'Refresh history'}</button></div>
          {refundHistory.length?<div className={styles.policyTableWrap}><table className={styles.table}><thead><tr><th>Requested</th><th>Amount</th><th>Status</th><th>Credit note</th><th>Recovery</th></tr></thead><tbody>{refundHistory.map(refund=><tr key={refund.id}><td>{new Date(refund.requested_at).toLocaleString('en-IN')}<small>{refund.reason}</small></td><td>{rupees(refund.requested_amount_paise,refund.currency)}</td><td><b>{refund.status}</b><small>{refund.provider_status||'Provider state not yet confirmed'}</small></td><td>{refund.credit_note_number||'Not issued'}</td><td><div className={styles.documentActions}>{['PROCESSING','PENDING','RECONCILIATION_REQUIRED'].includes(String(refund.status).toUpperCase())&&<><button type="button" className={styles.secondarySmall} onClick={()=>void refreshRefund(refund)} disabled={refundBusy}>Refresh status</button><button type="button" className={styles.secondarySmall} onClick={()=>void retryRefund(refund)} disabled={refundBusy}>Retry safely</button></>}</div></td></tr>)}</tbody></table></div>:<p className={styles.empty}>No refund requests for this order yet.</p>}
        </section>}
      </section>}
      {tab==='payouts'&&<PayoutsPanel/>}
      {tab==='payments'&&<section className={styles.stack}>
        <section className={styles.panel}>
          <div className={styles.panelHead}><div><h2>Course payments & reconciliation</h2>
            <p>Inspect saved course orders, provider references, discounts, invoices and the immutable HRMS recovery history.</p></div>
            <button type="button" className={styles.secondary} onClick={()=>void loadPaymentOrders()} disabled={paymentLoading}>↻ Refresh</button></div>
          <p className={styles.note}>Never manually mark an order as paid. Reconciliation checks the saved Razorpay order and only completes a captured, matching payment. If status is uncertain, do not ask the learner to pay again.</p>
          <div className={styles.paymentFilters}>
            <label>Order status<select value={paymentStatusFilter} onChange={e=>{setPaymentStatusFilter(e.target.value);setPaymentOffset(0);}}>
              <option value="">All statuses</option><option value="CREATING">Creating</option><option value="PENDING">Pending</option>
              <option value="VERIFIED">Verified</option><option value="FAILED">Failed</option><option value="EXPIRED">Expired</option></select></label>
            <label>Search orders<input value={paymentSearch} onChange={e=>setPaymentSearch(e.target.value)} maxLength={120}
              placeholder="Order ID, school, course, learner, Razorpay ID or failure code"/></label>
            <button type="button" className={styles.secondary} onClick={()=>{setPaymentOffset(0);void loadPaymentOrders(0);}} disabled={paymentLoading}>Search</button>
          </div>
          <div className={styles.paymentResultsMeta}>
            <span>{paymentTotal.toLocaleString('en-IN')} matching orders</span>
            <span>Showing {paymentOrders.length?paymentOffset+1:0}–{paymentOffset+paymentOrders.length}</span>
          </div>
          {paymentLoading?<p className={styles.empty}>Loading course payment orders…</p>
          :paymentOrders.length?<div className={styles.policyTableWrap}><table className={styles.table}>
            <thead><tr><th>Order / course</th><th>Institution / learner</th><th>Payment state</th><th>Payable / captured</th><th>Invoice</th><th>Resolution</th></tr></thead>
            <tbody>{paymentOrders.map(order=>{
              const status=String(order.status||'').toUpperCase();
              const canReconcile=['PENDING','EXPIRED'].includes(status);
              return <tr key={order.local_order_id}>
                <td><b>QE-ORDER-{order.local_order_id}</b><small>{order.course_title||('Course #'+order.course_id)}</small><small>{order.provider_order_id||'No Razorpay order reference'}</small></td>
                <td><b>{order.institution_name}</b><small>{order.learner_name||('Learner #'+order.student_id)} · {order.recipient_type||'STUDENT'}</small><small>{order.purchaser_user_id?'Purchaser #'+order.purchaser_user_id:'Student purchase'}</small></td>
                <td><span className={styles.status}>{status}</span><small>{order.failure_code||'No saved failure code'}</small><small>{order.provider_payment_id||'No payment ID recorded'}</small></td>
                <td><b>{rupees(order.offer_price_paise,order.currency)}</b><small>Discount: {rupees(order.discount_amount_paise||0,order.currency)} {order.discount_reference||''}</small><small>Captured: {order.captured_amount_paise==null?'Not recorded':rupees(order.captured_amount_paise,order.currency)}</small></td>
                <td><b>{order.invoice_number||'Not issued / linked'}</b><small>{order.fiscal_document_id?'Document #'+order.fiscal_document_id:'No invoice reference'}</small></td>
                <td><div className={styles.paymentRowActions}>
                  <button type="button" className={styles.secondarySmall} onClick={()=>selectPaymentOrder(order)}>Inspect</button>
                  <button type="button" className={styles.secondarySmall}
                    disabled={statusTraceLoadingId!==null}
                    onClick={()=>void checkCurrentStatus(order.local_order_id)}>
                    {statusTraceLoadingId===order.local_order_id?'Tracing…':'Check current status'}
                  </button>
                  {canReconcile&&<button type="button" className={styles.primarySmall} disabled={paymentActionLoadingId!==null}
                    onClick={()=>void reconcilePaymentOrder(order)}>{paymentActionLoadingId===order.local_order_id?'Checking…':'Check & reconcile'}</button>}
                  {!canReconcile&&<small>{order.recommended_action}</small>}
                </div><small>{order.last_resolution_code||order.resolution_code}</small><small>{order.last_operation_message||order.resolution_message}</small></td>
              </tr>;
            })}</tbody>
          </table></div>:<div className={styles.emptyLarge}><span>↻</span><b>No course payment orders found</b><p>Adjust the filter or search. This workspace only shows recorded marketplace orders; it does not create charges.</p></div>}
          <div className={styles.paymentPagination}>
            <button type="button" className={styles.secondarySmall} disabled={paymentLoading||paymentOffset===0}
              onClick={()=>void loadPaymentOrders(Math.max(0,paymentOffset-50))}>Previous</button>
            <span>Page {Math.floor(paymentOffset/50)+1} · {Math.max(1,Math.ceil(paymentTotal/50))}</span>
            <button type="button" className={styles.secondarySmall} disabled={paymentLoading||paymentOffset+paymentOrders.length>=paymentTotal||paymentOrders.length===0}
              onClick={()=>void loadPaymentOrders(paymentOffset+50)}>Next</button>
          </div>
        </section>
        <section className={styles.panel}>
          <div className={styles.panelHead}><div><h2>Razorpay provider-event inbox</h2>
            <p>Signature-verified webhook events are deduplicated by provider event ID. Unmatched or unresolved events need explicit finance investigation.</p></div>
            <button type="button" className={styles.secondary} onClick={()=>void loadProviderEvents()} disabled={providerEventLoading}>↻ Refresh inbox</button></div>
          {providerEventWarning&&<div className={styles.alertError} role="alert">{providerEventWarning}</div>}
          <div className={styles.paymentFilters}>
            <label>Event outcome<select value={providerEventStatusFilter} onChange={e=>{setProviderEventStatusFilter(e.target.value);setProviderEventOffset(0);}}>
              <option value="">All outcomes</option><option value="RECONCILIATION_REQUIRED">Reconciliation required</option>
              <option value="UNMATCHED">Unmatched</option><option value="IGNORED">Ignored / informational</option>
              <option value="PROCESSED">Processed</option><option value="PROCESSING">Processing</option></select></label>
            <label>Search provider events<input value={providerEventSearch} onChange={e=>{setProviderEventSearch(e.target.value);}}
              maxLength={160} placeholder="Event ID, Razorpay order/payment/refund ID or message"/></label>
            <button type="button" className={styles.secondary} onClick={()=>{setProviderEventOffset(0);void loadProviderEvents(0);}} disabled={providerEventLoading}>Search</button>
          </div>
          <div className={styles.paymentResultsMeta}><span>{providerEventTotal.toLocaleString('en-IN')} matching provider events</span>
            <span>Showing {providerEvents.length?providerEventOffset+1:0}–{providerEventOffset+providerEvents.length}</span></div>
          {providerEventLoading?<p className={styles.empty}>Loading signed provider events…</p>
          :providerEvents.length?<div className={styles.policyTableWrap}><table className={styles.table}>
            <thead><tr><th>Event / received</th><th>Outcome</th><th>Provider references</th><th>Resolution</th><th>Action</th></tr></thead>
            <tbody>{providerEvents.map(event=><tr key={event.id}>
              <td><b>{event.event_type}</b><small>{event.provider_event_id}</small><small>{new Date(event.received_at).toLocaleString('en-IN')}</small></td>
              <td><span className={styles.status}>{event.processing_status}</span><small>{event.processed_at?'Processed '+new Date(event.processed_at).toLocaleTimeString('en-IN'):'Still processing'}</small></td>
              <td><small>Order: {event.provider_order_id||'—'}</small><small>Payment: {event.provider_payment_id||'—'}</small><small>Refund: {event.provider_refund_id||'—'}</small><small>Payload SHA-256: {event.payload_sha256?.slice(0,12)||'—'}…</small></td>
              <td><p className={styles.providerMessage}>{event.status_message}</p>{event.details_json&&event.details_json!=='{}'&&<details><summary>Safe event details</summary><pre>{event.details_json}</pre></details>}</td>
              <td>{event.local_order_id?<button type="button" className={styles.secondarySmall} onClick={()=>inspectPaymentOrderId(Number(event.local_order_id))}>Inspect order #{event.local_order_id}</button>:<small>Unmatched: investigate provider reference</small>}</td>
            </tr>)}</tbody></table></div>
          :<div className={styles.emptyLarge}><span>↻</span><b>{providerEventWarning?'Provider-event inbox unavailable':'No provider events found'}</b><p>{providerEventWarning?'The event list could not be confirmed. Do not infer that this order has no provider events.':'Configure the Razorpay webhook URL and signing secret to receive events. Events are not generated from browser callbacks.'}</p></div>}
          <div className={styles.paymentPagination}>
            <button type="button" className={styles.secondarySmall} disabled={providerEventLoading||providerEventOffset===0}
              onClick={()=>void loadProviderEvents(Math.max(0,providerEventOffset-50))}>Previous</button>
            <span>Page {Math.floor(providerEventOffset/50)+1} · {Math.max(1,Math.ceil(providerEventTotal/50))}</span>
            <button type="button" className={styles.secondarySmall} disabled={providerEventLoading||providerEventOffset+providerEvents.length>=providerEventTotal||providerEvents.length===0}
              onClick={()=>void loadProviderEvents(providerEventOffset+50)}>Next</button>
          </div>
        </section>
        {selectedPaymentOrderId!==null&&<section className={styles.panel}>
          <div className={styles.panelHead}><div><h2>Order #{selectedPaymentOrderId} · resolution history</h2>
            <p>Provider checks and staff actions are recorded as immutable events.</p></div>
            <button type="button" className={styles.secondarySmall} onClick={()=>void Promise.all([loadPaymentEvents(selectedPaymentOrderId),loadSelectedProviderEvents(selectedPaymentOrderId)])} disabled={paymentEventsLoading||selectedProviderEventsLoading}>Refresh history</button></div>
          {statusTraceLoadingId===selectedPaymentOrderId&&<div className={styles.note} role="status">Checking the saved transaction path and querying Razorpay without changing this order…</div>}
          {statusTraceError&&<div className={styles.alertError} role="alert">{statusTraceError}</div>}
          {statusTrace&&statusTrace.orderId===selectedPaymentOrderId&&<section className={styles.statusTrace} aria-label="Transaction delivery chain">
            <div className={styles.statusTraceHeader}>
              <div><h3>Transaction delivery chain</h3>
                <p>Read-only inspection from checkout initiation through invoice PDF storage. No payment, order, financial snapshot, entitlement or invoice is changed.</p>
                <small>Order QE-ORDER-{statusTrace.orderId} · Checked {new Date(statusTrace.checkedAt).toLocaleString('en-IN')}</small>
              </div>
              <span className={statusTrace.overallStatus==='COMPLETE'?styles.traceOverallComplete:statusTrace.overallStatus==='BLOCKED'?styles.traceOverallBlocked:statusTrace.overallStatus==='PENDING'?styles.traceOverallPending:styles.traceOverallUnknown}>
                {statusTrace.overallStatus}
              </span>
            </div>
            {statusTrace.provider&&<div className={styles.traceProviderSummary}>
              <b>Razorpay · {statusTrace.provider.matchedProviderMode||statusTrace.provider.configuredProviderMode||'mode unknown'}</b>
              <span>{statusTrace.provider.providerQueryAvailable
                ?'Provider order: '+String(statusTrace.provider.providerOrderStatus||'unknown')+' · Result: '+String(statusTrace.provider.providerStatus||'unknown')
                :'Provider state unconfirmed'}</span>
              <p>{statusTrace.provider.message||'No provider diagnostic message was returned.'}</p>
              {statusTrace.provider.providerAmountPaidPaise!==undefined&&<small>Amount paid at provider: {rupees(statusTrace.provider.providerAmountPaidPaise,'INR')} · attempts: {statusTrace.provider.providerAttemptCount??'—'}</small>}
              {!statusTrace.provider.providerQueryAvailable&&<small>Credentials checked: {(statusTrace.provider.credentialModesTried||[]).join(', ')||'none configured'}. Local database stages remain available even when Razorpay cannot be queried.</small>}
            </div>}
            <div className={styles.traceStageList}>
              {statusTrace.steps.map((step,index)=>{
                const tone=step.status==='COMPLETE'?styles.traceStageComplete
                  :step.status==='BLOCKED'?styles.traceStageBlocked
                  :step.status==='PENDING'?styles.traceStagePending
                  :step.status==='UNKNOWN'?styles.traceStageUnknown:styles.traceStageNotReached;
                const badge=step.status==='COMPLETE'?styles.traceBadgeComplete
                  :step.status==='BLOCKED'?styles.traceBadgeBlocked
                  :step.status==='PENDING'?styles.traceBadgePending
                  :step.status==='UNKNOWN'?styles.traceBadgeUnknown:styles.traceBadgeNotReached;
                return <article className={styles.traceStage+' '+tone} key={step.key}>
                  <span className={styles.traceStageNumber}>{index+1}</span>
                  <div className={styles.traceStageBody}>
                    <div className={styles.traceStageHeading}><b>{step.title}</b><span className={badge}>{step.status.replaceAll('_',' ')}</span></div>
                    <p>{step.summary}</p>
                    {step.occurredAt&&<small>Recorded: {new Date(step.occurredAt).toLocaleString('en-IN')}</small>}
                    {!!step.details?.length&&<ul>{step.details.map((detail,i)=><li key={step.key+'-'+i}>{detail}</li>)}</ul>}
                  </div>
                </article>;
              })}
            </div>
            {statusTrace.provider?.paymentAttempts&&statusTrace.provider.paymentAttempts.length>0&&<div>
              <h4>Razorpay payment attempts</h4>
              <div className={styles.policyTableWrap}><table className={styles.table}>
                <thead><tr><th>Payment</th><th>Status</th><th>Amount</th><th>Provider details</th></tr></thead>
                <tbody>{statusTrace.provider.paymentAttempts.map((attempt,i)=><tr key={attempt.paymentId+'-trace-'+i}>
                  <td><b>{attempt.paymentId}</b><small>{attempt.method||'Method not reported'}</small></td>
                  <td><span className={styles.status}>{attempt.status}</span><small>{attempt.matchesExpectedAmountAndCurrency?'Amount/currency match':'Amount/currency mismatch'}</small></td>
                  <td>{rupees(attempt.amountPaise,attempt.currency||'INR')}</td>
                  <td><b>{attempt.errorCode||attempt.errorReason||'No failure code'}</b><small>{attempt.errorDescription||'No provider failure description'}</small></td>
                </tr>)}</tbody>
              </table></div>
            </div>}
            <p className={styles.traceReadOnlyNote}>{statusTrace.message}</p>
          </section>}
          {paymentActionResult&&<div className={paymentActionResult.status==='VERIFIED'?styles.alertSuccess:styles.note} role="status">
            <b>{paymentActionResult.resolutionCode||paymentActionResult.status}</b>
            <p>{paymentActionResult.resolutionMessage||paymentActionResult.message||'No provider message was returned.'}</p>
            {paymentActionResult.invoiceNumber&&<p>Invoice: {paymentActionResult.invoiceNumber}</p>}
            {paymentActionResult.providerOrderStatus&&<p>Razorpay: {paymentActionResult.providerOrderStatus} · attempts: {paymentActionResult.providerAttemptCount??'—'} · amount paid: {paymentActionResult.providerAmountPaidPaise==null?'—':rupees(paymentActionResult.providerAmountPaidPaise,'INR')}</p>}
            {paymentActionResult.paymentAttempts&&paymentActionResult.paymentAttempts.length>0&&<div className={styles.policyTableWrap}><table className={styles.table}>
              <thead><tr><th>Provider payment</th><th>Status</th><th>Amount</th><th>Failure details</th></tr></thead>
              <tbody>{paymentActionResult.paymentAttempts.map((attempt,i)=><tr key={attempt.paymentId+'-'+i}>
                <td><b>{attempt.paymentId}</b><small>{attempt.method||'Method not reported'}</small></td>
                <td><span className={styles.status}>{attempt.status}</span><small>{attempt.matchesExpectedAmountAndCurrency?'Order / amount match':'Amount or currency mismatch'}</small></td>
                <td>{rupees(attempt.amountPaise,attempt.currency||'INR')}</td>
                <td><b>{attempt.errorCode||attempt.errorReason||'No provider failure code'}</b><small>{attempt.errorDescription||'No provider error description was returned.'}</small></td>
              </tr>)}</tbody>
            </table></div>}
            {paymentActionResult.canRetryCheckout===true&&<p>Safe next step: learner may return to the marketplace and restart checkout. HRMS did not create a second payment.</p>}
          </div>}
          <div className={styles.panelHead}><div><h3>Provider webhook events for this order</h3><p>Raw payloads are never retained; the inbox stores signed-event references, hashes and safe diagnostic fields.</p></div>
            <button type="button" className={styles.secondarySmall} onClick={()=>void loadSelectedProviderEvents(selectedPaymentOrderId)} disabled={selectedProviderEventsLoading}>Refresh provider events</button></div>
          {selectedProviderEventsLoading?<p className={styles.empty}>Loading provider webhook events…</p>
          :selectedProviderEventsWarning?<p className={styles.note} role="status">{selectedProviderEventsWarning}</p>
          :selectedProviderEvents.length?<div className={styles.paymentEventList}>{selectedProviderEvents.map(event=><article key={event.id}>
            <div><b>{event.event_type}</b><span className={styles.status}>{event.processing_status}</span></div>
            <p>{event.status_message}</p><small>{event.provider_event_id} · {new Date(event.received_at).toLocaleString('en-IN')}{event.processed_at?' · processed '+new Date(event.processed_at).toLocaleString('en-IN'):''}</small>
            {event.details_json&&event.details_json!=='{}'&&<details><summary>Safe event details</summary><pre>{event.details_json}</pre></details>}
          </article>)}</div>:<p className={styles.empty}>No Razorpay webhooks are recorded for this order. Use Check & reconcile when payment status is uncertain.</p>}
          {paymentEventsLoading?<p className={styles.empty}>Loading payment operation history…</p>
          :paymentEvents.length?<div className={styles.paymentEventList}>{paymentEvents.map(event=><article key={event.id}>
            <div><b>{event.event_type.replaceAll('_',' ')}</b><span className={styles.status}>{event.resolution_code}</span></div>
            <p>{event.message}</p><small>{new Date(event.created_at).toLocaleString('en-IN')} · HRMS staff #{event.actor_staff_id}{event.provider_order_id?' · '+event.provider_order_id:''}{event.provider_status?' · Provider '+event.provider_status:''}</small>
            {event.details_json&&event.details_json!=='{}'&&<details><summary>Technical diagnostic details</summary><pre>{event.details_json}</pre></details>}
          </article>)}</div>:<p className={styles.empty}>No HRMS reconciliation actions have been recorded for this order yet. Select Check & reconcile only for an eligible order.</p>}
        </section>}
      </section>}
    </>}
  </main>;
}
