'use client';

import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { apiFetch } from './apiClient';
import styles from './BillingProfileEditor.module.css';

type Workspace='STUDENT'|'PARENT';
type Profile={
  legal_name?:string|null;
  billing_type?:'INDIVIDUAL'|'BUSINESS'|string;
  gstin?:string|null;
  gst_state_code?:string|null;
  billingAddress?:Record<string,string>|null;
  contact_email?:string|null;
  contact_phone?:string|null;
  profile_version?:number;
  saved?:boolean;
};
type Draft={
  billingType:'INDIVIDUAL'|'BUSINESS';
  legalName:string;
  gstin:string;
  gstStateCode:string;
  line1:string;
  line2:string;
  city:string;
  state:string;
  postalCode:string;
  country:string;
  contactEmail:string;
  contactPhone:string;
};
const EMPTY:Draft={billingType:'INDIVIDUAL',legalName:'',gstin:'',gstStateCode:'',line1:'',line2:'',city:'',state:'',postalCode:'',country:'India',contactEmail:'',contactPhone:''};

export default function BillingProfileEditor({workspace,english=true}:{workspace:Workspace;english?:boolean}){
  const [draft,setDraft]=useState<Draft>({...EMPTY});
  const [expanded,setExpanded]=useState(false);
  const [loading,setLoading]=useState(true);
  const [saving,setSaving]=useState(false);
  const [saved,setSaved]=useState(false);
  const [profileVersion,setProfileVersion]=useState(0);
  const [error,setError]=useState('');
  const [message,setMessage]=useState('');
  const endpoint=workspace==='PARENT'?'/api/v1/marketplace/family/billing-profile':'/api/v1/marketplace/billing-profile';
  const t=(en:string,hi:string)=>english?en:hi;
  const readJson=async(response:Response)=>{
    const raw=await response.text();let data:any={};
    try{data=raw?JSON.parse(raw):{};}catch{data={message:raw};}
    if(!response.ok)throw new Error(String(data.message||data.detail||data.error||'Billing details could not be loaded.'));
    return data as Profile;
  };
  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const p=await readJson(await apiFetch(endpoint,{method:'GET',credentials:'same-origin',headers:{'X-QE-Workspace':workspace,'Accept':'application/json'},cache:'no-store'}));
      const a=p.billingAddress||{};
      setDraft({
        billingType:p.billing_type==='BUSINESS'?'BUSINESS':'INDIVIDUAL',
        legalName:p.legal_name||'',
        gstin:p.gstin||'',
        gstStateCode:p.gst_state_code||'',
        line1:a.line1||'',line2:a.line2||'',city:a.city||'',state:a.state||'',
        postalCode:a.postalCode||'',country:a.country||'India',
        contactEmail:p.contact_email||'',contactPhone:p.contact_phone||''
      });
      setSaved(Boolean(p.saved));setProfileVersion(Number(p.profile_version||0));
    }catch(e){setError(e instanceof Error?e.message:t('Billing details could not be loaded. You can still continue browsing courses.','बिलिंग विवरण लोड नहीं हुए। आप कोर्स देखना जारी रख सकते हैं।'));}
    finally{setLoading(false);}
  },[endpoint,workspace,english]);
  useEffect(()=>{void load();},[load]);
  const update=(key:keyof Draft,value:string)=>setDraft(d=>({...d,[key]:value}));
  const submit=async(event:FormEvent)=>{
    event.preventDefault();setSaving(true);setError('');setMessage('');
    try{
      const body={
        billingType:draft.billingType,legalName:draft.legalName.trim(),gstin:draft.gstin.trim()||null,
        gstStateCode:draft.gstStateCode.trim()||null,
        billingAddress:{line1:draft.line1.trim(),line2:draft.line2.trim(),city:draft.city.trim(),
          state:draft.state.trim(),postalCode:draft.postalCode.trim(),country:draft.country.trim()||'India'},
        contactEmail:draft.contactEmail.trim()||null,contactPhone:draft.contactPhone.trim()||null
      };
      const response=await apiFetch(endpoint,{method:'PUT',credentials:'same-origin',
        headers:{'X-QE-Workspace':workspace,'Content-Type':'application/json','Accept':'application/json'},
        cache:'no-store',body:JSON.stringify(body)});
      const profile=await readJson(response);
      const a=profile.billingAddress||{};
      setDraft(d=>({...d,legalName:profile.legal_name||d.legalName,billingType:profile.billing_type==='BUSINESS'?'BUSINESS':'INDIVIDUAL',
        gstin:profile.gstin||'',gstStateCode:profile.gst_state_code||'',line1:a.line1||'',line2:a.line2||'',
        city:a.city||'',state:a.state||'',postalCode:a.postalCode||'',country:a.country||'India',
        contactEmail:profile.contact_email||'',contactPhone:profile.contact_phone||''}));
      setSaved(Boolean(profile.saved));setProfileVersion(Number(profile.profile_version||0));
      setMessage(t('Billing details saved. Future orders use a snapshot of these details.','बिलिंग विवरण सहेजे गए। आगे के ऑर्डर में इन्हीं विवरणों का स्नैपशॉट रहेगा।'));
    }catch(e){setError(e instanceof Error?e.message:t('Billing details could not be saved.','बिलिंग विवरण सहेजे नहीं जा सके।'));}
    finally{setSaving(false);}
  };
  const label=(en:string,hi:string)=>t(en,hi);
  return <details className={styles.shell} open={expanded} onToggle={event=>setExpanded((event.currentTarget as HTMLDetailsElement).open)}>
    <summary className={styles.summary}>
      <span className={styles.summaryIcon} aria-hidden="true">▤</span>
      <span className={styles.summaryText}><strong>{label('Billing details','बिलिंग विवरण')}</strong><small>{label('Used for receipts and invoices for purchases made from this account.','इस खाते से की गई खरीद की रसीद और इनवॉइस के लिए।')}</small></span>
      <span className={saved?styles.saved:styles.unsaved}>{loading?label('Loading','लोड हो रहा है'):saved?label('Saved','सहेजा गया'):label('Review details','विवरण देखें')}</span>
      <span className={styles.chevron} aria-hidden="true">⌄</span>
    </summary>
    {expanded&&<div className={styles.body}>
      <div className={styles.explainer}>{label(
        'The person paying is the billing customer. If you buy for a child, the child is recorded separately as the course beneficiary. Existing orders retain their own snapshot.',
        'भुगतान करने वाला व्यक्ति बिलिंग ग्राहक है। बच्चे के लिए खरीदने पर बच्चे का नाम अलग beneficiary के रूप में रहेगा। पुराने ऑर्डर अपने विवरण सुरक्षित रखते हैं।')}</div>
      {error&&<div className={styles.error} role="alert">{error}</div>}
      {message&&<div className={styles.success} role="status">{message}</div>}
      <form className={styles.form} onSubmit={submit}>
        <label>{label('Billing type','बिलिंग प्रकार')}
          <select value={draft.billingType} onChange={e=>update('billingType',e.target.value)} disabled={saving||loading}>
            <option value="INDIVIDUAL">{label('Individual','व्यक्तिगत')}</option>
            <option value="BUSINESS">{label('Business / Institution','व्यवसाय / संस्था')}</option>
          </select>
        </label>
        <label>{label('Legal / billing name','कानूनी / बिलिंग नाम')}<input required maxLength={240} value={draft.legalName} onChange={e=>update('legalName',e.target.value)} disabled={saving||loading} autoComplete="name"/></label>
        <div className={styles.grid}>
          <label>{label('GSTIN (optional)','GSTIN (वैकल्पिक)')}<input maxLength={15} value={draft.gstin} onChange={e=>update('gstin',e.target.value.toUpperCase())} disabled={saving||loading} autoComplete="off"/></label>
          <label>{label('GST state code','GST राज्य कोड')}<input maxLength={2} inputMode="numeric" pattern="[0-9]{2}" value={draft.gstStateCode} onChange={e=>update('gstStateCode',e.target.value)} disabled={saving||loading} autoComplete="off"/></label>
        </div>
        <label>{label('Address line 1','पता, पंक्ति 1')}<input maxLength={180} required={draft.billingType==='BUSINESS'} value={draft.line1} onChange={e=>update('line1',e.target.value)} disabled={saving||loading} autoComplete="address-line1"/></label>
        <label>{label('Address line 2','पता, पंक्ति 2')}<input maxLength={180} value={draft.line2} onChange={e=>update('line2',e.target.value)} disabled={saving||loading} autoComplete="address-line2"/></label>
        <div className={styles.grid}>
          <label>{label('City','शहर')}<input required={draft.billingType==='BUSINESS'} maxLength={80} value={draft.city} onChange={e=>update('city',e.target.value)} disabled={saving||loading} autoComplete="address-level2"/></label>
          <label>{label('State','राज्य')}<input required={draft.billingType==='BUSINESS'} maxLength={80} value={draft.state} onChange={e=>update('state',e.target.value)} disabled={saving||loading} autoComplete="address-level1"/></label>
        </div>
        <div className={styles.grid}>
          <label>{label('PIN / postal code','पिन / पोस्टल कोड')}<input required={draft.billingType==='BUSINESS'} maxLength={16} value={draft.postalCode} onChange={e=>update('postalCode',e.target.value)} disabled={saving||loading} autoComplete="postal-code"/></label>
          <label>{label('Country','देश')}<input maxLength={80} value={draft.country} onChange={e=>update('country',e.target.value)} disabled={saving||loading} autoComplete="country-name"/></label>
        </div>
        <div className={styles.grid}>
          <label>{label('Billing email','बिलिंग ईमेल')}<input type="email" maxLength={320} value={draft.contactEmail} onChange={e=>update('contactEmail',e.target.value)} disabled={saving||loading} autoComplete="email"/></label>
          <label>{label('Billing phone','बिलिंग फ़ोन')}<input type="tel" maxLength={40} value={draft.contactPhone} onChange={e=>update('contactPhone',e.target.value)} disabled={saving||loading} autoComplete="tel"/></label>
        </div>
        <div className={styles.footer}><small>{profileVersion?label('Profile version ','प्रोफ़ाइल संस्करण ')+profileVersion:label('Not saved yet','अभी सहेजा नहीं गया')}</small><button type="submit" disabled={saving||loading||!draft.legalName.trim()}>{saving?label('Saving…','सहेजा जा रहा है…'):label('Save billing details','बिलिंग विवरण सहेजें')}</button></div>
      </form>
    </div>}
  </details>;
}
