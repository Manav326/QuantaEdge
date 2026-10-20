'use client';

import { apiFetch } from '../components/apiClient';
import { useCallback, useEffect, useMemo, useState, type FormEvent, type CSSProperties } from 'react';
import styles from './Finance.module.css';

type Institution = { id:number; tenant_code:string; display_name:string; status:string };
type LegalEntity = { id:number; entity_kind:string; institution_id?:number|null; institution_name?:string|null; legal_name:string; trade_name?:string|null; invoice_series_code?:string; status:string; active_gst_registrations?:number };
type Offer = { offer_id:number; institution_id:number; institution_name:string; course_code:string; course_name:string; price_paise:number; currency:string; offer_status:string; legal_entity_id?:number|null; legal_seller_name?:string|null; latest_tax_profile_id?:number|null; tax_profile_status?:string|null; tax_treatment?:string|null; latest_share_policy_status?:string|null };
type Registration = { id:number; legal_entity_id:number; legal_entity_name:string; gstin:string; state_code:string; status:string; effective_from:string };
type Catalog = { institutions:Institution[]; legalEntities:LegalEntity[]; offers:Offer[]; registrations:Registration[] };
type Policy = { id:number; institution_offer_id:number; policy_version:number; currency:string; status:string; institution_name:string; course_name:string; recipient_count:number; effective_from:string; effective_until?:string|null };
type PolicyRecipient = { institution_id:number; calculation_type:string; percentage_basis_points?:number|null; flat_amount_paise?:number|null; institution_name?:string };
type PolicyDetail = Policy & { recipients:PolicyRecipient[] };
type Template = { id:number; scope:string; legal_entity_id?:number|null; internal_name:string; template_version_id?:number|null; version_no?:number|null; version_status?:string; design_definition_json?:string|null; published_at?:string|null };
type FinancialDocument = { id:number; document_type:string; document_number:string; issued_at:string; currency:string; total_value_paise:number; tax_value_paise:number; source_type:string; source_record_key:string; pdf_available:boolean };
type Overview = { legalEntities:number; activeTaxProfiles:number; pendingTaxProfiles:number; approvedSharePolicies:number; pendingSharePolicies:number; issuedDocuments:number; allocatedPaise:number };
type Design = { primaryColor:string; accentColor:string; surfaceStyle:string; layout:string; showLogo:boolean; footerText:string };
type RecipientDraft = { institutionId:string; calculationType:'PERCENTAGE'|'FLAT'; percentage:string; flatRupees:string };
type TaxForm = { offerId:string; legalEntityId:string; serviceClassificationCode:string; taxTreatment:string; gstPercent:string; cessPercent:string; priceIncludesTax:boolean; placeOfSupplyRule:string; effectiveFrom:string };

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
function parseDesign(value?:string|null):Design{
  try { const raw=value?JSON.parse(value):{}; return {...DEFAULT_DESIGN,...raw}; } catch { return {...DEFAULT_DESIGN}; }
}

export default function FinancePage(){
  const [tab,setTab]=useState<'overview'|'entities'|'tax'|'distribution'|'studio'|'documents'>('overview');
  const [catalog,setCatalog]=useState<Catalog|null>(null);
  const [overview,setOverview]=useState<Overview|null>(null);
  const [policies,setPolicies]=useState<Policy[]>([]);
  const [templates,setTemplates]=useState<Template[]>([]);
  const [documents,setDocuments]=useState<FinancialDocument[]>([]);
  const [seriesDrafts,setSeriesDrafts]=useState<Record<string,string>>({});
  const [downloadingDocumentId,setDownloadingDocumentId]=useState<number|null>(null);
  const [busy,setBusy]=useState(false);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const [selectedOfferId,setSelectedOfferId]=useState('');
  const [selectedPolicyId,setSelectedPolicyId]=useState('');
  const [policyDetail,setPolicyDetail]=useState<PolicyDetail|null>(null);
  const [recipients,setRecipients]=useState<RecipientDraft[]>([]);
  const [entityForm,setEntityForm]=useState({entityKind:'PLATFORM',institutionId:'',legalName:'',tradeName:'',contactEmail:'',addressLine:'',city:'',state:'',postalCode:'',invoiceSeriesCode:'QE'});
  const [gstEntityId,setGstEntityId]=useState('');
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

  const loadAll=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const [c,o,p,t,d]=await Promise.all([
        api<Catalog>(BASE+'/catalog'),api<Overview>(BASE+'/overview'),api<Policy[]>(BASE+'/revenue-share/policies'),
        api<Template[]>(BASE+'/invoice-templates'),api<FinancialDocument[]>(BASE+'/documents')
      ]);
      setCatalog(c);setOverview(o);setPolicies(p);setTemplates(t);setDocuments(d);
      if(!selectedOfferId&&c.offers.length) setSelectedOfferId(String(c.offers[0].offer_id));
      if(!taxForm.offerId&&c.offers.length) setTaxForm(v=>({...v,offerId:String(c.offers[0].offer_id)}));
      if(!taxForm.legalEntityId&&c.legalEntities.length) setTaxForm(v=>({...v,legalEntityId:String(c.legalEntities[0].id)}));
      if(!gstEntityId&&c.legalEntities.length) setGstEntityId(String(c.legalEntities[0].id));
      if(!selectedTemplateId&&t.length){
        const first=t[0];setSelectedTemplateId(String(first.id));setSelectedVersionNo(first.version_no||null);
        setTemplateScope(first.scope);setTemplateEntityId(first.legal_entity_id?String(first.legal_entity_id):'');
        setTemplateName(first.internal_name);setDesign(parseDesign(first.design_definition_json));
      }
    }catch(e){setError(e instanceof Error?e.message:'Finance workspace could not load.');}
    finally{setLoading(false);}
  },[selectedOfferId,taxForm.offerId,taxForm.legalEntityId,gstEntityId,selectedTemplateId]);
  useEffect(()=>{void loadAll();},[loadAll]);
  useEffect(()=>()=>{if(previewPdfUrl)URL.revokeObjectURL(previewPdfUrl);},[previewPdfUrl]);

  const action=async(work:()=>Promise<unknown>,success:string)=>{
    setBusy(true);setError('');setNotice('');
    try{await work();setNotice(success);await loadAll();}
    catch(e){setError(e instanceof Error?e.message:'The finance action failed.');}
    finally{setBusy(false);}
  };
  const loadPolicy=async(id:string)=>{
    setSelectedPolicyId(id);
    if(!id){setPolicyDetail(null);setRecipients([]);return;}
    try{
      const p=await api<PolicyDetail>(BASE+'/revenue-share/policies/'+id);
      setPolicyDetail(p);
      setSelectedOfferId(String(p.institution_offer_id));
      setRecipients((p.recipients||[]).map(r=>({
        institutionId:String(r.institution_id),calculationType:r.calculation_type as 'PERCENTAGE'|'FLAT',
        percentage:r.percentage_basis_points==null?'':String(r.percentage_basis_points/100),
        flatRupees:r.flat_amount_paise==null?'':String(r.flat_amount_paise/100)
      })));
    }catch(e){setError(e instanceof Error?e.message:'Policy detail could not load.');}
  };
  const selectedOffer=useMemo(()=>catalog?.offers.find(o=>String(o.offer_id)===selectedOfferId),[catalog,selectedOfferId]);
  const selectedTemplate=useMemo(()=>templates.find(t=>String(t.id)===selectedTemplateId),[templates,selectedTemplateId]);

  const createEntity=(event:FormEvent)=>{
    event.preventDefault();
    void action(async()=>api(BASE+'/legal-entities',{method:'POST',body:JSON.stringify({
      entityKind:entityForm.entityKind,institutionId:entityForm.entityKind==='INSTITUTION'?Number(entityForm.institutionId):null,
      legalName:entityForm.legalName,tradeName:entityForm.tradeName,contactEmail:entityForm.contactEmail,
      invoiceSeriesCode:entityForm.invoiceSeriesCode,
      registeredAddress:{line1:entityForm.addressLine,city:entityForm.city,state:entityForm.state,postalCode:entityForm.postalCode,country:'India'}
    })}), 'Legal entity saved as draft.');
  };
  const saveInvoiceSeries=(entity:LegalEntity)=>{
    const series=(seriesDrafts[String(entity.id)]??entity.invoice_series_code??'QE').trim().toUpperCase();
    if(!/^[A-Z0-9_-]{1,16}$/.test(series)){setError('Invoice series must be 1–16 uppercase letters, digits, underscores or hyphens.');return;}
    void action(()=>api(BASE+'/legal-entities/'+entity.id+'/invoice-series',{method:'PUT',body:JSON.stringify({invoiceSeriesCode:series})}),
      'Invoice series saved. Existing invoices keep their original numbers; future documents use this series.');
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
  const createRegistration=(event:FormEvent)=>{
    event.preventDefault();
    if(!gstEntityId){setError('Choose the legal entity that owns this GST registration.');return;}
    void action(async()=>api(BASE+'/legal-entities/'+gstEntityId+'/gst-registrations',{method:'POST',body:JSON.stringify({
      gstin:gstForm.gstin,legalName:gstForm.legalName,stateCode:gstForm.stateCode,effectiveFrom:gstForm.effectiveFrom,
      registeredAddress:{line1:gstForm.addressLine,city:gstForm.city,state:gstForm.state,postalCode:gstForm.postalCode,country:'India'}
    })}), 'GST registration saved pending verification.');
  };
  const createTaxProfile=(event:FormEvent)=>{
    event.preventDefault();
    if(!taxForm.offerId||!taxForm.legalEntityId){setError('Choose both the course offer and legal seller.');return;}
    const treatment=taxForm.taxTreatment;
    const gstBps=treatment==='TAXABLE'?Math.round(Number(taxForm.gstPercent)*100):0;
    const cessBps=treatment==='TAXABLE'?Math.round(Number(taxForm.cessPercent||0)*100):0;
    void action(async()=>{
      const result=await api<{id:number}>(BASE+'/offers/'+taxForm.offerId+'/tax-profiles',{method:'POST',body:JSON.stringify({
        legalEntityId:Number(taxForm.legalEntityId),serviceClassificationCode:taxForm.serviceClassificationCode||null,
        taxTreatment:treatment,gstRateBasisPoints:gstBps,cessRateBasisPoints:cessBps,
        priceIncludesTax:taxForm.priceIncludesTax,placeOfSupplyRule:taxForm.placeOfSupplyRule,
        effectiveFrom:asIso(taxForm.effectiveFrom),taxComponents:{}
      })});
      await api(BASE+'/tax-profiles/'+result.id+'/submit',{method:'POST'});
      setTaxProfiles(v=>({...v,[taxForm.offerId]:[...(v[taxForm.offerId]||[]),{id:result.id,status:'PENDING_APPROVAL'}]}));
    },'Tax profile submitted for HRMS approval.');
  };
  const loadTaxProfiles=async(offerId:string)=>{
    if(!offerId)return;
    try{const rows=await api<any[]>(BASE+'/offers/'+offerId+'/tax-profiles');setTaxProfiles(v=>({...v,[offerId]:rows}));}
    catch(e){setError(e instanceof Error?e.message:'Tax profiles could not load.');}
  };
  useEffect(()=>{if(taxForm.offerId)void loadTaxProfiles(taxForm.offerId);},[taxForm.offerId]);

  const createPolicy=()=>{
    if(!selectedOfferId){setError('Select a course offer first.');return;}
    void action(async()=>{
      const result=await api<{id:number}>(BASE+'/revenue-share/offers/'+selectedOfferId+'/policies',{method:'POST',body:JSON.stringify({currency:'INR',effectiveFrom:new Date().toISOString()})});
      setSelectedPolicyId(String(result.id));
      const detail=await api<PolicyDetail>(BASE+'/revenue-share/policies/'+result.id);
      setPolicyDetail(detail);setRecipients([]);
    },'Draft revenue-share policy created.');
  };
  const recipientPayload=()=>{
    if(!selectedPolicyId) throw new Error('Create or select a draft policy first.');
    const invalid=recipients.some(r=>!r.institutionId||(r.calculationType==='PERCENTAGE'?!(Number(r.percentage)>0&&Number(r.percentage)<=100):!(Number(r.flatRupees)>0)));
    if(invalid) throw new Error('Every recipient must have an institution and a valid positive percentage or flat amount.');
    return recipients.map(r=>r.calculationType==='PERCENTAGE'
      ?{institutionId:Number(r.institutionId),calculationType:r.calculationType,percentageBasisPoints:Math.round(Number(r.percentage)*100)}
      :{institutionId:Number(r.institutionId),calculationType:r.calculationType,flatAmountPaise:Math.round(Number(r.flatRupees)*100)});
  };
  const saveRecipients=()=>{
    void action(async()=>api(BASE+'/revenue-share/policies/'+selectedPolicyId+'/recipients',{method:'PUT',body:JSON.stringify({recipients:recipientPayload()})}), 'Revenue-share recipients saved.');
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
    ['overview','Overview','▦'],['entities','Legal entities & GST','⌂'],['tax','Tax profiles','%'],
    ['distribution','Revenue distribution','↗'],['studio','Invoice Design Studio','▤'],['documents','Issued documents','▣']
  ];
  return <main className={styles.page}>
    <header className={styles.header}>
      <div><span className={styles.eyebrow}>HRMS · FINANCE & BILLING</span><h1>Financial control centre</h1>
        <p>Seller and tax setup, approved institutional shares, invoice design and document audit—all controlled centrally by HRMS.</p></div>
      <button className={styles.secondary} onClick={()=>void loadAll()} disabled={busy||loading}>↻ Refresh</button>
    </header>
    {error&&<div className={styles.alertError} role="alert">{error}</div>}
    {notice&&<div className={styles.alertSuccess} role="status">{notice}</div>}
    <nav className={styles.tabs} aria-label="Finance workspace">
      {tabs.map(([key,label,icon])=><button key={key} className={tab===key?styles.tabActive:styles.tab} onClick={()=>setTab(key)}><span>{icon}</span>{label}</button>)}
    </nav>
    {loading&&!catalog?<section className={styles.panel}>Loading financial configuration…</section>:!catalog?<section className={styles.panel}>The Finance workspace is unavailable. Confirm HRMS owner access and check the API response.</section>:<>
      {tab==='overview'&&<section className={styles.stack}>
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
        <div className={styles.panel}><h2>Legal entities</h2><p>Create the real legal supplier record. It remains a draft until HRMS review.</p>
          <form className={styles.form} onSubmit={createEntity}>
            <label>Entity scope<select value={entityForm.entityKind} onChange={e=>setEntityForm(v=>({...v,entityKind:e.target.value}))}><option value="PLATFORM">Platform legal entity</option><option value="INSTITUTION">Institution legal entity</option></select></label>
            {entityForm.entityKind==='INSTITUTION'&&<label>Institution<select required value={entityForm.institutionId} onChange={e=>setEntityForm(v=>({...v,institutionId:e.target.value}))}><option value="">Select institution</option>{catalog.institutions.map(i=><option key={i.id} value={i.id}>{i.display_name} · {i.tenant_code}</option>)}</select></label>}
            <label>Legal name<input required value={entityForm.legalName} onChange={e=>setEntityForm(v=>({...v,legalName:e.target.value}))}/></label>
            <label>Trade name<input value={entityForm.tradeName} onChange={e=>setEntityForm(v=>({...v,tradeName:e.target.value}))}/></label>
            <label>Finance contact email<input type="email" value={entityForm.contactEmail} onChange={e=>setEntityForm(v=>({...v,contactEmail:e.target.value}))}/></label>
            <label>Invoice series prefix<input required minLength={1} maxLength={16} pattern="[A-Za-z0-9_-]{1,16}" value={entityForm.invoiceSeriesCode} onChange={e=>setEntityForm(v=>({...v,invoiceSeriesCode:e.target.value.toUpperCase()}))}/><small>Printed in the official invoice number, e.g. QE/2026-27/000001.</small></label>
            <label>Registered address line<input required value={entityForm.addressLine} onChange={e=>setEntityForm(v=>({...v,addressLine:e.target.value}))}/></label>
            <div className={styles.inputGrid}><label>City<input required value={entityForm.city} onChange={e=>setEntityForm(v=>({...v,city:e.target.value}))}/></label><label>State<input required value={entityForm.state} onChange={e=>setEntityForm(v=>({...v,state:e.target.value}))}/></label><label>PIN code<input required value={entityForm.postalCode} onChange={e=>setEntityForm(v=>({...v,postalCode:e.target.value}))}/></label></div>
            <button className={styles.primary} disabled={busy}>Save legal entity draft</button>
          </form>
          <div className={styles.entityList}>{catalog.legalEntities.map(e=><article key={e.id} className={styles.entityRow}>
            <div className={styles.entitySummary}><b>{e.legal_name}</b><span>{e.entity_kind==='INSTITUTION'?e.institution_name:'Platform'} · {e.status}</span><small>{e.active_gst_registrations||0} verified registration(s)</small>
              <label className={styles.seriesInline}>Invoice series prefix<input maxLength={16} value={seriesDrafts[String(e.id)]??e.invoice_series_code??'QE'} onChange={event=>setSeriesDrafts(old=>({...old,[String(e.id)]:event.target.value.toUpperCase()}))}/></label>
            </div>
            <div className={styles.entityActions}><button className={styles.secondarySmall} disabled={busy} onClick={()=>saveInvoiceSeries(e)}>Save series</button>
              {e.status==='DRAFT'&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>void action(()=>api(BASE+'/legal-entities/'+e.id+'/activate',{method:'POST'}),'Legal entity activated after HRMS review.')}>Activate</button>}
            </div>
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
            <label>Course offer<select required value={taxForm.offerId} onChange={e=>setTaxForm(v=>({...v,offerId:e.target.value}))}><option value="">Select offer</option>{catalog.offers.map(o=><option key={o.offer_id} value={o.offer_id}>{o.institution_name} · {o.course_name} · {o.offer_status}</option>)}</select></label>
            <label>Legal seller<select required value={taxForm.legalEntityId} onChange={e=>setTaxForm(v=>({...v,legalEntityId:e.target.value}))}><option value="">Select legal entity</option>{catalog.legalEntities.map(e=><option key={e.id} value={e.id}>{e.legal_name} · {e.status}</option>)}</select></label>
            <label>Service classification / SAC<input value={taxForm.serviceClassificationCode} onChange={e=>setTaxForm(v=>({...v,serviceClassificationCode:e.target.value}))} placeholder="Enter CA-approved code"/></label>
            <label>Tax treatment<select value={taxForm.taxTreatment} onChange={e=>setTaxForm(v=>({...v,taxTreatment:e.target.value}))}><option value="TAXABLE">Taxable</option><option value="EXEMPT">Exempt</option><option value="NIL_RATED">Nil-rated</option><option value="NON_GST">Non-GST</option><option value="COMPOSITION">Composition treatment</option></select></label>
            {taxForm.taxTreatment==='TAXABLE'&&<div className={styles.inputGrid}><label>GST rate (%)<input required type="number" min="0.01" max="100" step="0.01" value={taxForm.gstPercent} onChange={e=>setTaxForm(v=>({...v,gstPercent:e.target.value}))}/></label><label>Cess rate (%)<input type="number" min="0" max="100" step="0.01" value={taxForm.cessPercent} onChange={e=>setTaxForm(v=>({...v,cessPercent:e.target.value}))}/></label></div>}
            <label>Price basis<select value={taxForm.priceIncludesTax?'INCLUSIVE':'EXCLUSIVE'} onChange={e=>setTaxForm(v=>({...v,priceIncludesTax:e.target.value==='INCLUSIVE'}))}><option value="INCLUSIVE">Listed price includes tax</option><option value="EXCLUSIVE">Tax added to listed price</option></select></label>
            <label>Place-of-supply rule<select value={taxForm.placeOfSupplyRule} onChange={e=>setTaxForm(v=>({...v,placeOfSupplyRule:e.target.value}))}><option value="RECIPIENT_LOCATION">Recipient location</option><option value="SUPPLIER_LOCATION">Supplier location</option><option value="CA_APPROVED_CUSTOM">CA-approved custom rule</option></select></label>
            <label>Effective from<input type="datetime-local" required value={taxForm.effectiveFrom} onChange={e=>setTaxForm(v=>({...v,effectiveFrom:e.target.value}))}/></label>
            <button className={styles.primary} disabled={busy}>Save draft and submit for review</button>
          </form>
        </div>
        <div className={styles.panel}><h2>Saved tax profiles</h2><label>Course offer<select value={taxForm.offerId} onChange={e=>setTaxForm(v=>({...v,offerId:e.target.value}))}>{catalog.offers.map(o=><option key={o.offer_id} value={o.offer_id}>{o.institution_name} · {o.course_name}</option>)}</select></label>
          {(taxProfiles[taxForm.offerId]||[]).map(p=><article className={styles.policyRow} key={p.id}><div><b>Version {p.version} · {p.tax_treatment}</b><span>{p.status} · GST {Number(p.gst_rate_basis_points)/100}%</span></div>{p.status==='PENDING_APPROVAL'&&<button className={styles.secondarySmall} disabled={busy} onClick={()=>void action(()=>api(BASE+'/tax-profiles/'+p.id+'/approve',{method:'POST'}),'Tax profile approved.')}>Approve</button>}</article>)}
          {!taxProfiles[taxForm.offerId]?.length&&<p className={styles.empty}>No tax profile saved for this offer yet.</p>}
          <p className={styles.note}>Approval requires an active legal seller. Taxable treatment also requires an active, HRMS-verified GST registration for the effective date.</p>
        </div>
      </section>}
      {tab==='distribution'&&<section className={styles.stack}>
        <section className={styles.panel}><div className={styles.panelHead}><div><h2>Institution revenue-share policies</h2><p>Shares are calculated from actual captured consideration minus the invoice's approved output-tax amount—not list price.</p></div><button className={styles.primary} disabled={busy||!selectedOfferId} onClick={createPolicy}>＋ New policy</button></div>
          <label className={styles.wideLabel}>Course offer<select value={selectedOfferId} onChange={e=>setSelectedOfferId(e.target.value)}><option value="">Select offer</option>{catalog.offers.map(o=><option key={o.offer_id} value={o.offer_id}>{o.institution_name} · {o.course_name} ({o.offer_status})</option>)}</select></label>
          {policies.length?<div className={styles.policyTableWrap}><table className={styles.table}><thead><tr><th>Course / institution</th><th>Version</th><th>Status</th><th>Recipients</th><th>Action</th></tr></thead><tbody>{policies.map(p=><tr key={p.id}><td><b>{p.course_name}</b><small>{p.institution_name}</small></td><td>v{p.policy_version}</td><td><span className={styles.status}>{p.status}</span></td><td>{p.recipient_count}</td><td><button className={styles.secondarySmall} onClick={()=>void loadPolicy(String(p.id))}>Open</button></td></tr>)}</tbody></table></div>:<p className={styles.empty}>No revenue-share policies have been created.</p>}
        </section>
        {policyDetail&&<section className={styles.panel}><div className={styles.panelHead}><div><h2>Policy v{policyDetail.policy_version}: {policyDetail.course_name}</h2><p>Source offer: {policyDetail.institution_name} · Currency {policyDetail.currency} · Status {policyDetail.status}</p></div>{policyDetail.status==='DRAFT'&&<button className={styles.secondary} onClick={saveRecipients} disabled={busy}>Save recipients</button>}</div>
          {policyDetail.status==='DRAFT'?<><p>Each institution receives either a percentage of the net distributable pool or a fixed amount per eligible paid sale. Duplicate institutions are not permitted.</p>
            <div className={styles.recipientList}>{recipients.map((r,i)=><div className={styles.recipientEditor} key={i}>
              <label>Institution<select value={r.institutionId} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,institutionId:e.target.value}:x))}><option value="">Select institution</option>{catalog.institutions.filter(x=>x.status==='ACTIVE').map(x=><option key={x.id} value={x.id}>{x.display_name}</option>)}</select></label>
              <label>Share type<select value={r.calculationType} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,calculationType:e.target.value as RecipientDraft['calculationType']}:x))}><option value="PERCENTAGE">Percentage</option><option value="FLAT">Flat ₹ amount</option></select></label>
              {r.calculationType==='PERCENTAGE'?<label>Pool share (%)<input type="number" min=".01" max="100" step=".01" value={r.percentage} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,percentage:e.target.value}:x))}/></label>:<label>Flat amount per sale (₹)<input type="number" min=".01" step=".01" value={r.flatRupees} onChange={e=>setRecipients(old=>old.map((x,j)=>i===j?{...x,flatRupees:e.target.value}:x))}/></label>}
              <button className={styles.removeRow} type="button" aria-label="Remove institution" onClick={()=>setRecipients(old=>old.filter((_,j)=>j!==i))}>×</button>
            </div>)}</div>
            <button className={styles.secondary} onClick={()=>setRecipients(old=>[...old,{institutionId:'',calculationType:'PERCENTAGE',percentage:'',flatRupees:''}])}>＋ Add institution</button>
            <div className={styles.policyFooter}><span>Approval checks seller/tax readiness and effective-period conflicts. Actual pool limits are checked at payment allocation time.</span><button className={styles.primary} disabled={busy||!recipients.length} onClick={()=>void action(async()=>{await api(BASE+'/revenue-share/policies/'+selectedPolicyId+'/recipients',{method:'PUT',body:JSON.stringify({recipients:recipientPayload()})});await api(BASE+'/revenue-share/policies/'+selectedPolicyId+'/submit',{method:'POST'});},'Policy submitted for HRMS approval.')}>Submit policy</button></div>
          </>:policyDetail.status==='PENDING_APPROVAL'?<div className={styles.approvalBox}><p>This policy is waiting for approval. Confirm its parties, effective dates and contract references before activating it.</p><button className={styles.primary} disabled={busy} onClick={()=>void action(()=>api(BASE+'/revenue-share/policies/'+selectedPolicyId+'/approve',{method:'POST'}),'Revenue-share policy approved.')}>Approve policy</button></div>:<div className={styles.recipientList}>{(policyDetail.recipients||[]).map((r,i)=><article className={styles.policyRow} key={i}><b>{r.institution_name}</b><span>{r.calculation_type==='PERCENTAGE'?Number(r.percentage_basis_points)/100+'% of net pool':rupees(r.flat_amount_paise||0)+' per eligible sale'}</span></article>)}</div>}
        </section>}
      </section>}
      {tab==='studio'&&<section className={styles.studio}>
        <section className={styles.panel}><h2>Invoice Design Studio</h2><p>Make a new draft or create a version from a published design. The preview is a watermarked sample; it never issues an invoice or consumes a legal number.</p>
          <label>Existing template<select value={selectedTemplateId} onChange={e=>selectTemplate(e.target.value)}><option value="">Create a new template…</option>{templates.map(t=><option key={t.id} value={t.id}>{t.internal_name} · {t.scope} · v{t.version_no} ({t.version_status})</option>)}</select></label>
          {!selectedTemplateId&&<><label>Template name<input value={templateName} onChange={e=>setTemplateName(e.target.value)}/></label><label>Template scope<select value={templateScope} onChange={e=>setTemplateScope(e.target.value)}><option value="PLATFORM">Platform invoice design</option><option value="SELLER">Seller-specific invoice design</option></select></label>{templateScope==='SELLER'&&<label>Legal seller<select value={templateEntityId} onChange={e=>setTemplateEntityId(e.target.value)}><option value="">Select seller</option>{catalog.legalEntities.map(e=><option key={e.id} value={e.id}>{e.legal_name}</option>)}</select></label>}</>}
          {selectedTemplate&&<div className={styles.versionBanner}><span>Editing v{selectedVersionNo||selectedTemplate.version_no} · {selectedTemplate.version_status}</span>{['PUBLISHED','RETIRED'].includes(selectedTemplate.version_status||'')&&<button className={styles.secondarySmall} onClick={startTemplateVersion} disabled={busy}>＋ New draft version</button>}</div>}
          <div className={styles.designControls}><label>Primary colour<input type="color" disabled={!activeDraft} value={design.primaryColor} onChange={e=>setDesign(v=>({...v,primaryColor:e.target.value.toUpperCase()}))}/></label><label>Accent colour<input type="color" disabled={!activeDraft} value={design.accentColor} onChange={e=>setDesign(v=>({...v,accentColor:e.target.value.toUpperCase()}))}/></label></div>
          <label>Paper surface<select disabled={!activeDraft} value={design.surfaceStyle} onChange={e=>setDesign(v=>({...v,surfaceStyle:e.target.value}))}><option value="WARM_PAPER">Warm premium paper</option><option value="CLEAN_WHITE">Clean white</option><option value="SOFT_SLATE">Soft slate</option></select></label>
          <label>Layout<select disabled={!activeDraft} value={design.layout} onChange={e=>setDesign(v=>({...v,layout:e.target.value}))}><option value="EDITORIAL">Editorial / premium</option><option value="CLASSIC">Classic / formal</option></select></label>
          <label className={styles.checkLabel}><input type="checkbox" disabled={!activeDraft} checked={design.showLogo} onChange={e=>setDesign(v=>({...v,showLogo:e.target.checked}))}/> Show approved supplier logo where configured</label>
          <label>Footer copy<textarea disabled={!activeDraft} rows={3} maxLength={500} value={design.footerText} onChange={e=>setDesign(v=>({...v,footerText:e.target.value}))}/></label>
          <div className={styles.buttonStack}>
            {!selectedTemplate?<button className={styles.primary} disabled={busy} onClick={createTemplate}>Create draft template</button>:<>
              {selectedTemplate.version_status==='DRAFT'&&<><button className={styles.secondary} disabled={busy} onClick={saveTemplate}>Save draft</button><button className={styles.primary} disabled={busy} onClick={submitTemplate}>Submit for approval</button></>}
              {selectedTemplate.version_status==='PENDING_APPROVAL'&&<button className={styles.primary} disabled={busy} onClick={approveTemplate}>Approve version</button>}
              {selectedTemplate.version_status==='APPROVED'&&<button className={styles.primary} disabled={busy} onClick={publishTemplate}>Publish approved version</button>}
            </>}
          </div>
          <p className={styles.note}>Published template versions are immutable for the purpose of historical invoices. Actual statutory documents also snapshot seller, recipient, tax, line items and totals separately.</p>
        </section>
        <section className={styles.previewPanel}><div className={styles.previewHeader}><div><span className={styles.eyebrow}>LIVE PREVIEW</span><h2>Invoice document</h2></div><div className={styles.previewActions}><span className={styles.samplePill}>SAMPLE · NOT ISSUED</span><button type="button" className={styles.secondarySmall} disabled={previewPdfLoading} onClick={()=>void generatePdfPreview()}>{previewPdfLoading?'Rendering PDF…':'Open PDF preview'}</button></div></div>
          <div className={styles.paperWrap}><article className={[styles.paper,design.surfaceStyle==='CLEAN_WHITE'?styles.paperWhite:design.surfaceStyle==='SOFT_SLATE'?styles.paperSlate:styles.paperWarm].join(' ')} style={{'--invoice-primary':design.primaryColor,'--invoice-accent':design.accentColor} as CSSProperties}>
            <div className={styles.paperTopRule}/>
            <header className={styles.paperBrand}><div className={styles.mark} style={{background:design.primaryColor}}>{design.showLogo?'QE':'IN'}</div><div><strong>LEGAL SUPPLIER NAME</strong><span>Registered address · State and PIN</span><span>GSTIN: configured approved registration</span></div><div className={styles.documentTitle}><span>TAX INVOICE</span><b>QE-PREVIEW-0001</b><small>Preview date · sample only</small></div></header>
            <div className={styles.paperRule}/>
            <div className={styles.paperMeta}><div><small>BILL TO</small><b>Customer billing name</b><span>Billing address and state</span><span>GSTIN if applicable</span></div><div><small>COURSE / ORDER</small><b>Class 6 Mathematics</b><span>Course order reference · preview</span><span>Beneficiary: learner example</span></div></div>
            <table className={styles.paperTable}><thead><tr><th>DESCRIPTION</th><th>QTY</th><th>VALUE</th></tr></thead><tbody><tr><td><b>Course access</b><small>Illustrative line item · SAC configured by finance</small></td><td>1</td><td>₹1,000.00</td></tr><tr><td>Offer discount</td><td>—</td><td>− ₹100.00</td></tr></tbody></table>
            <div className={styles.paperBottom}><div className={styles.sampleTax}><span>Tax breakdown</span><p>Tax components and rates appear from the approved transaction snapshot—not from this sample.</p><span>Order / payment reference: preview only</span></div><div className={styles.paperTotals}><div><span>Discounted value</span><b>₹900.00</b></div><div><span>Applicable tax</span><b>Calculated per approved profile</b></div><div className={styles.grandTotal}><span>TOTAL</span><b>Calculated for actual transaction</b></div></div></div>
            <footer className={styles.paperFooter}><div><b>{design.footerText}</b><span>Supplier particulars, required declarations and authentication are rendered from the approved invoice snapshot.</span></div><div className={styles.previewSeal}>PREVIEW<br/>NOT A TAX INVOICE</div></footer>
          </article></div>
          <div className={styles.previewNote}><span>◎</span><p>This live design preview uses sample placeholders. It deliberately does not fabricate a GSTIN, legal invoice number, tax rate or real customer data.</p></div>
          {previewPdfUrl&&<section className={styles.pdfPreviewPanel}><div className={styles.pdfPreviewToolbar}><b>PDF output preview · sample only</b><div><a href={previewPdfUrl} download="quantaedge-invoice-preview.pdf">Download preview PDF</a><button type="button" className={styles.secondarySmall} onClick={()=>setPreviewPdfUrl(null)}>Close</button></div></div><iframe title="Invoice design PDF preview" src={previewPdfUrl}/></section>}
        </section>
      </section>}
      {tab==='documents'&&<section className={styles.panel}><div className={styles.panelHead}><div><h2>Issued fiscal documents</h2><p>HRMS audit view for issued documents. Download the stored, hash-verified PDF or open its transaction snapshot.</p></div><button className={styles.secondary} onClick={()=>void loadAll()} disabled={busy}>Refresh records</button></div>
        {documents.length?<div className={styles.policyTableWrap}><table className={styles.table}><thead><tr><th>Document</th><th>Issued</th><th>Tax</th><th>Total</th><th>PDF</th></tr></thead><tbody>{documents.map(d=><tr key={d.id}><td><b>{d.document_number}</b><small>{d.document_type} · {d.source_type}</small></td><td>{new Date(d.issued_at).toLocaleDateString('en-IN')}</td><td>{rupees(d.tax_value_paise,d.currency)}</td><td>{rupees(d.total_value_paise,d.currency)}</td><td>{d.pdf_available?<button type="button" className={styles.secondarySmall} disabled={downloadingDocumentId!==null} onClick={()=>void downloadDocument(d)}>{downloadingDocumentId===d.id?'Preparing…':'Download PDF'}</button>:'Not rendered'}</td></tr>)}</tbody></table></div>:<div className={styles.emptyLarge}><span>▤</span><b>No fiscal documents issued yet</b><p>Only invoices created by the captured-payment finalizer appear here. The live Studio preview never consumes a legal number or creates an issued invoice.</p></div>}
      </section>}
    </>}
  </main>;
}
