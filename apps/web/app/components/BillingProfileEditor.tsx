'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { apiFetch } from './apiClient';
import { useUiTranslation } from '../i18n/useUiTranslation';
import styles from './BillingProfileEditor.module.css';

type Workspace='STUDENT'|'PARENT';
type AddressFields={
  line1?:string; line2?:string; city?:string; state?:string; postalCode?:string; country?:string;
};
export type BillingAddressRecord={
  id:number;
  label:string;
  billing_type:'INDIVIDUAL'|'BUSINESS'|string;
  legal_name:string;
  gstin?:string|null;
  gst_state_code?:string|null;
  billingAddress?:AddressFields|null;
  contact_email?:string|null;
  contact_phone?:string|null;
  is_default:boolean;
  version?:number;
};
type Draft={
  label:string; billingType:'INDIVIDUAL'|'BUSINESS'; legalName:string;
  gstin:string; gstStateCode:string; line1:string; line2:string; city:string;
  state:string; postalCode:string; country:string; contactEmail:string; contactPhone:string;
};
const EMPTY:Draft={label:'Home',billingType:'INDIVIDUAL',legalName:'',gstin:'',gstStateCode:'',
  line1:'',line2:'',city:'',state:'',postalCode:'',country:'India',contactEmail:'',contactPhone:''};

async function readJson(response:Response):Promise<any>{
  const data=await response.json().catch(()=>({}));
  if(!response.ok) throw new Error(String(data.message||data.detail||data.error||'Unable to complete the billing address request.'));
  return data;
}
function draftFromAddress(a:BillingAddressRecord):Draft{
  const address=a.billingAddress||{};
  return {label:a.label||'Billing address',billingType:a.billing_type==='BUSINESS'?'BUSINESS':'INDIVIDUAL',
    legalName:a.legal_name||'',gstin:a.gstin||'',gstStateCode:a.gst_state_code||'',
    line1:address.line1||'',line2:address.line2||'',city:address.city||'',state:address.state||'',
    postalCode:address.postalCode||'',country:address.country||'India',
    contactEmail:a.contact_email||'',contactPhone:a.contact_phone||''};
}
function addressLines(a:BillingAddressRecord){
  const address=a.billingAddress||{};
  return [
    address.line1,address.line2,
    [address.city,address.state].filter(Boolean).join(', '),
    [address.postalCode,address.country].filter(Boolean).join(' – ')
  ].filter(Boolean) as string[];
}

export default function BillingProfileEditor({
  workspace,english=true,placement='marketplace',onAddressesChange
}:{
  workspace:Workspace;
  english?:boolean;
  placement?:'marketplace'|'profile';
  onAddressesChange?:(addresses:BillingAddressRecord[],loading:boolean)=>void;
}){
  const t=useUiTranslation();
  const text=useCallback((key:string)=>t(key),[t]);
  const [addresses,setAddresses]=useState<BillingAddressRecord[]>([]);
  const [loading,setLoading]=useState(true);
  const [saving,setSaving]=useState(false);
  const [formOpen,setFormOpen]=useState(false);
  const [editingId,setEditingId]=useState<number|null>(null);
  const [draft,setDraft]=useState<Draft>({...EMPTY});
  const [error,setError]=useState('');
  const [notice,setNotice]=useState('');
  const endpoint=workspace==='PARENT'
    ? '/api/v1/marketplace/family/billing-addresses'
    : '/api/v1/marketplace/billing-addresses';
  const profileHref=workspace==='PARENT'?'/parent/profile':'/student/profile';

  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try{
      const rows=await readJson(await apiFetch(endpoint,{
        method:'GET',credentials:'same-origin',cache:'no-store',
        headers:{'Accept':'application/json','X-QE-Workspace':workspace}
      }));
      if(!Array.isArray(rows)) throw new Error(text('billing.loadError'));
      setAddresses(rows as BillingAddressRecord[]);
    }catch(e){
      setError(e instanceof Error?e.message:text('billing.loadError'));
    }finally{setLoading(false);}
  // Locale is intentionally read through the stable catalogue translator.
  },[endpoint,workspace,text]);
  useEffect(()=>{void load();},[load]);
  useEffect(()=>{onAddressesChange?.(addresses,loading);},[addresses,loading,onAddressesChange]);

  const setField=(key:keyof Draft,value:string)=>setDraft(current=>({...current,[key]:value}));
  function startNew(){
    setDraft({...EMPTY});
    setEditingId(null);
    setFormOpen(true);
    setError('');setNotice('');
  }
  function startEdit(address:BillingAddressRecord){
    setDraft(draftFromAddress(address));
    setEditingId(address.id);
    setFormOpen(true);
    setError('');setNotice('');
  }
  function cancelEdit(){
    setFormOpen(false);setEditingId(null);setError('');
    if(addresses.length===0&&placement==='marketplace') setFormOpen(true);
  }
  async function submit(event:FormEvent<HTMLFormElement>){
    event.preventDefault();
    if(!draft.legalName.trim()) {setError(text('billing.legalNameRequired'));return;}
    setSaving(true);setError('');setNotice('');
    try{
      const body={
        label:draft.label.trim()||text('billing.addressLabelDefault'),
        billingType:draft.billingType,legalName:draft.legalName.trim(),
        gstin:draft.gstin.trim()||null,gstStateCode:draft.gstStateCode.trim()||null,
        billingAddress:{line1:draft.line1.trim(),line2:draft.line2.trim(),city:draft.city.trim(),
          state:draft.state.trim(),postalCode:draft.postalCode.trim(),country:draft.country.trim()||'India'},
        contactEmail:draft.contactEmail.trim()||null,contactPhone:draft.contactPhone.trim()||null,
        isDefault:editingId!==null
          ? Boolean(addresses.find(a=>a.id===editingId)?.is_default)
          : addresses.length===0
      };
      const response=await apiFetch(editingId===null?endpoint:endpoint+'/'+editingId,{
        method:editingId===null?'POST':'PUT',credentials:'same-origin',cache:'no-store',
        headers:{'X-QE-Workspace':workspace,'Content-Type':'application/json','Accept':'application/json'},
        body:JSON.stringify(body)
      });
      const rows=await readJson(response);
      if(!Array.isArray(rows)) throw new Error(text('billing.saveError'));
      setAddresses(rows as BillingAddressRecord[]);
      setFormOpen(false);setEditingId(null);
      setNotice(text('billing.saveSuccess'));
    }catch(e){setError(e instanceof Error?e.message:text('billing.saveError'));}
    finally{setSaving(false);}
  }
  async function makeDefault(addressId:number){
    setError('');setNotice('');
    try{
      const rows=await readJson(await apiFetch(endpoint+'/'+addressId+'/default',{
        method:'PUT',credentials:'same-origin',cache:'no-store',
        headers:{'X-QE-Workspace':workspace,'Accept':'application/json'}
      }));
      if(!Array.isArray(rows)) throw new Error(text('billing.defaultError'));
      setAddresses(rows as BillingAddressRecord[]);
      setNotice(text('billing.defaultSuccess'));
    }catch(e){setError(e instanceof Error?e.message:text('billing.defaultError'));}
  }
  async function removeAddress(address:BillingAddressRecord){
    if(!window.confirm(text('billing.deleteConfirm').replace('{label}',address.label||address.legal_name))) return;
    setError('');setNotice('');
    try{
      const rows=await readJson(await apiFetch(endpoint+'/'+address.id,{
        method:'DELETE',credentials:'same-origin',cache:'no-store',
        headers:{'X-QE-Workspace':workspace,'Accept':'application/json'}
      }));
      if(!Array.isArray(rows)) throw new Error(text('billing.deleteError'));
      publish(rows as BillingAddressRecord[]);
      if(rows.length===0&&placement==='marketplace'){setDraft({...EMPTY});setEditingId(null);setFormOpen(true);}
      setNotice(text('billing.deleteSuccess'));
    }catch(e){setError(e instanceof Error?e.message:text('billing.deleteError'));}
  }

  const selected=addresses.find(a=>a.is_default)||addresses[0]||null;
  const heading=placement==='profile'?text('billing.profileHeading'):text('billing.heading');
  const description=placement==='profile'?text('billing.profileDescription'):text('billing.marketplaceDescription');

  return <section className={styles.addressBook} data-placement={placement} aria-labelledby={'billing-address-heading-'+workspace.toLowerCase()+'-'+placement}>
    <div className={styles.addressBookHeading}>
      <div><h2 id={'billing-address-heading-'+workspace.toLowerCase()+'-'+placement}>{heading}</h2>
        <p>{description}</p></div>
      {placement==='profile'&&<button type="button" className={styles.addAddressButton} onClick={startNew} disabled={loading||saving}>
        <span aria-hidden="true">＋</span> {text('billing.addAddress')}
      </button>}
    </div>
    {error&&<div className={styles.error} role="alert">{error}</div>}
    {notice&&<div className={styles.success} role="status">{notice}</div>}
    {loading?<p className={styles.loading}>{text('billing.loading')}</p>:placement==='marketplace'?(
      selected?<article className={styles.marketplaceAddress}>
        <div className={styles.addressHeader}>
          <div className={styles.addressIdentity}>
            <span className={styles.addressLabel}>{selected.label||text('billing.addressLabelDefault')}</span>
            <strong>{selected.legal_name}</strong>
            {selected.is_default&&<span className={styles.defaultTag}>{text('billing.default')}</span>}
          </div>
          <button type="button" className={styles.editAddressButton} onClick={()=>startEdit(selected)}
            aria-label={text('billing.editAddress')} title={text('billing.editAddress')}>✎</button>
        </div>
        <address className={styles.postalAddress}>
          {addressLines(selected).map((line,index)=><span key={index}>{line}</span>)}
          {selected.gstin&&<span>{text('billing.gstinShort')}: {selected.gstin}</span>}
          {selected.contact_email&&<span>{selected.contact_email}</span>}
          {selected.contact_phone&&<span>{selected.contact_phone}</span>}
        </address>
        {addresses.length>1&&<p className={styles.manageHint}>{text('billing.multipleHint').replace('{count}',String(addresses.length))}</p>}
        <Link className={styles.profileLink} href={profileHref}>{text('billing.manageInProfile')}</Link>
      </article>:<div className={styles.emptyAddress}>
        <strong>{text('billing.noAddressTitle')}</strong><p>{text('billing.noAddressDescription')}</p>
      </div>
    ):addresses.length===0?<div className={styles.emptyAddress}>
      <strong>{text('billing.noSavedAddresses')}</strong><p>{text('billing.profileNoAddressDescription')}</p>
    </div>:<div className={styles.addressList}>
      {addresses.map(address=><article className={styles.profileAddressCard} key={address.id}>
        <div className={styles.addressHeader}><div className={styles.addressIdentity}>
          <span className={styles.addressLabel}>{address.label||text('billing.addressLabelDefault')}</span>
          <strong>{address.legal_name}</strong>
          {address.is_default&&<span className={styles.defaultTag}>{text('billing.default')}</span>}
        </div>
          <button type="button" className={styles.editAddressButton} onClick={()=>startEdit(address)}
            aria-label={text('billing.editAddress')} title={text('billing.editAddress')}>✎</button>
        </div>
        <address className={styles.postalAddress}>
          {addressLines(address).map((line,index)=><span key={index}>{line}</span>)}
          {address.gstin&&<span>{text('billing.gstinShort')}: {address.gstin}</span>}
          {address.contact_email&&<span>{address.contact_email}</span>}
          {address.contact_phone&&<span>{address.contact_phone}</span>}
        </address>
        <div className={styles.addressActions}>
          {!address.is_default&&<button type="button" onClick={()=>void makeDefault(address.id)}>{text('billing.makeDefault')}</button>}
          <button type="button" className={styles.deleteAddressButton} onClick={()=>void removeAddress(address)}>{text('billing.deleteAddress')}</button>
        </div>
      </article>)}
    </div>}
    {(formOpen||placement==='marketplace'&&addresses.length===0&&!loading)&&<form className={styles.form} onSubmit={submit}>
      <div className={styles.formHeading}><strong>{editingId===null?text('billing.formAddHeading'):text('billing.formEditHeading')}</strong>
        {!(placement==='marketplace'&&addresses.length===0)&&<button type="button" className={styles.closeFormButton} onClick={cancelEdit} disabled={saving} aria-label={text('billing.cancel')}>×</button>}
      </div>
      <label>{text('billing.addressLabel')}<input maxLength={80} value={draft.label} onChange={e=>setField('label',e.target.value)} disabled={saving} placeholder={text('billing.addressLabelPlaceholder')}/></label>
      <label>{text('billing.billingType')}<select value={draft.billingType} onChange={e=>setField('billingType',e.target.value)} disabled={saving}>
        <option value="INDIVIDUAL">{text('billing.individual')}</option><option value="BUSINESS">{text('billing.business')}</option>
      </select></label>
      <label>{text('billing.legalName')}<input required maxLength={240} value={draft.legalName} onChange={e=>setField('legalName',e.target.value)} disabled={saving} autoComplete="name"/></label>
      <div className={styles.grid}>
        <label>{text('billing.gstin')}<input maxLength={15} value={draft.gstin} onChange={e=>setField('gstin',e.target.value.toUpperCase())} disabled={saving} autoComplete="off"/></label>
        <label>{text('billing.gstStateCode')}<input maxLength={2} inputMode="numeric" pattern="[0-9]{2}" value={draft.gstStateCode} onChange={e=>setField('gstStateCode',e.target.value)} disabled={saving} autoComplete="off"/></label>
      </div>
      <label>{text('billing.addressLine1')}<input required maxLength={180} value={draft.line1} onChange={e=>setField('line1',e.target.value)} disabled={saving} autoComplete="address-line1"/></label>
      <label>{text('billing.addressLine2')}<input maxLength={180} value={draft.line2} onChange={e=>setField('line2',e.target.value)} disabled={saving} autoComplete="address-line2"/></label>
      <div className={styles.grid}>
        <label>{text('billing.city')}<input required maxLength={80} value={draft.city} onChange={e=>setField('city',e.target.value)} disabled={saving} autoComplete="address-level2"/></label>
        <label>{text('billing.state')}<input required maxLength={80} value={draft.state} onChange={e=>setField('state',e.target.value)} disabled={saving} autoComplete="address-level1"/></label>
      </div>
      <div className={styles.grid}>
        <label>{text('billing.postalCode')}<input required maxLength={16} value={draft.postalCode} onChange={e=>setField('postalCode',e.target.value)} disabled={saving} autoComplete="postal-code"/></label>
        <label>{text('billing.country')}<input required maxLength={80} value={draft.country} onChange={e=>setField('country',e.target.value)} disabled={saving} autoComplete="country-name"/></label>
      </div>
      <div className={styles.grid}>
        <label>{text('billing.email')}<input type="email" maxLength={320} value={draft.contactEmail} onChange={e=>setField('contactEmail',e.target.value)} disabled={saving} autoComplete="email"/></label>
        <label>{text('billing.phone')}<input type="tel" maxLength={40} value={draft.contactPhone} onChange={e=>setField('contactPhone',e.target.value)} disabled={saving} autoComplete="tel"/></label>
      </div>
      <div className={styles.footer}>
        {placement==='marketplace'&&addresses.length===0&&<small>{text('billing.requiredForCheckout')}</small>}
        <button type="submit" disabled={saving||loading||!draft.legalName.trim()}>{saving?text('billing.saving'):editingId===null?text('billing.saveAddress'):text('billing.updateAddress')}</button>
      </div>
    </form>}
    {placement==='profile'&&formOpen&&addresses.length>0&&<p className={styles.formNote}>{text('billing.addressChangesApplyToFutureOrders')}</p>}
  </section>;
}

export function BillingAddressSelection({
  addresses,selectedAddressId,onSelect,onConfirm,onCancel,english=true
}:{
  addresses:BillingAddressRecord[];
  selectedAddressId:number|null;
  onSelect:(id:number)=>void;
  onConfirm:()=>void;
  onCancel:()=>void;
  english?:boolean;
}){
  const t=useUiTranslation();
  const text=(key:string)=>t(key);
  return <div className={styles.pickerOverlay} role="presentation">
    <section className={styles.pickerDialog} role="dialog" aria-modal="true" aria-labelledby="billing-address-picker-title">
      <div className={styles.pickerHeading}><div><h2 id="billing-address-picker-title">{text('billing.checkoutTitle')}</h2>
        <p>{text('billing.checkoutPrompt')}</p></div>
        <button type="button" className={styles.closeFormButton} onClick={onCancel} aria-label={text('billing.cancel')}>×</button>
      </div>
      <div className={styles.pickerList}>
        {addresses.map(address=><label className={styles.pickerOption} key={address.id}>
          <input type="radio" name="qe-checkout-billing-address" checked={selectedAddressId===address.id} onChange={()=>onSelect(address.id)}/>
          <span className={styles.pickerOptionContent}><span className={styles.pickerOptionTop}><strong>{address.legal_name}</strong>
            {address.is_default&&<span className={styles.defaultTag}>{text('billing.default')}</span>}</span>
            <small>{address.label}</small>
            <address className={styles.postalAddress}>{addressLines(address).map((line,index)=><span key={index}>{line}</span>)}</address>
            {address.gstin&&<small>{text('billing.gstinShort')}: {address.gstin}</small>}
          </span>
        </label>)}
      </div>
      <div className={styles.pickerFooter}><button type="button" className={styles.cancelButton} onClick={onCancel}>{text('billing.cancel')}</button>
        <button type="button" className={styles.addAddressButton} onClick={onConfirm} disabled={!addresses.some(a=>a.id===selectedAddressId)}>{text('billing.continueCheckout')}</button></div>
      <p className={styles.pickerNote}>{text('billing.manageCheckoutAddresses')} <Link href={profileHref}>{text('billing.profileLink')}</Link></p>
    </section>
  </div>;
}
