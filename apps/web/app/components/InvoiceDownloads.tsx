'use client';

import { useCallback, useEffect, useState } from 'react';
import { apiFetch } from './apiClient';
import styles from './InvoiceDownloads.module.css';

type Workspace='STUDENT'|'PARENT';
type Invoice={
  course_order_id:number;
  invoice_document_id:number;
  document_number:string;
  document_type:string;
  issued_at:string;
  currency:string;
  taxable_value_paise:number;
  discount_value_paise:number;
  tax_value_paise:number;
  total_value_paise:number;
  amount_paid_paise:number;
  course_title:string;
  beneficiary_name?:string|null;
  pdf_available:boolean;
};

export default function InvoiceDownloads({workspace,english=true}:{workspace:Workspace;english?:boolean}) {
  const [invoices,setInvoices]=useState<Invoice[]>([]);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const t=(en:string,hi:string)=>english?en:hi;
  const load=useCallback(async()=>{
    setLoading(true);setError('');
    try {
      const response=await apiFetch(workspace==='PARENT'?'/api/v1/marketplace/family/invoices':'/api/v1/marketplace/invoices',{
        method:'GET',credentials:'same-origin',cache:'no-store',
        headers:{'X-QE-Workspace':workspace,'Accept':'application/json'}
      });
      const raw=await response.text();let data:any=[];
      try{data=raw?JSON.parse(raw):[];}catch{data=[];}
      if(!response.ok)throw new Error(String(data.message||data.detail||data.error||'Invoices could not be loaded.'));
      setInvoices(Array.isArray(data)?data:[]);
    } catch(e) {
      setError(e instanceof Error?e.message:t('Invoice history is temporarily unavailable.','इनवॉइस इतिहास अभी उपलब्ध नहीं है।'));
    } finally {setLoading(false);}
  },[workspace]);
  useEffect(()=>{void load();},[load]);

  const money=(paise:number,currency:string)=>new Intl.NumberFormat(english?'en-IN':'hi-IN',{
    style:'currency',currency:currency||'INR',maximumFractionDigits:2
  }).format((Number(paise)||0)/100);
  const download=(id:number)=>workspace==='PARENT'
    ?'/api/v1/marketplace/family/orders/'+id+'/invoice.pdf'
    :'/api/v1/marketplace/orders/'+id+'/invoice.pdf';

  return <section className={styles.shell} aria-labelledby={workspace==='PARENT'?'qe-parent-invoices':'qe-student-invoices'}>
    <div className={styles.heading}>
      <div className={styles.icon} aria-hidden="true">▤</div>
      <div className={styles.headingText}>
        <h2 id={workspace==='PARENT'?'qe-parent-invoices':'qe-student-invoices'}>{t('Invoices & payments','इनवॉइस और भुगतान')}</h2>
        <p>{t('Download the PDF for each verified course purchase. Parent purchases remain under the payer’s account.','हर सत्यापित कोर्स खरीद का PDF डाउनलोड करें। अभिभावक की खरीद भुगतान करने वाले के खाते में रहेगी।')}</p>
      </div>
      <button className={styles.refresh} type="button" disabled={loading} onClick={()=>void load()} aria-label={t('Refresh invoice list','इनवॉइस सूची रीफ़्रेश करें')}>↻</button>
    </div>
    {error&&<div className={styles.error} role="alert">{error}</div>}
    {loading?<p className={styles.empty}>{t('Loading invoices…','इनवॉइस लोड हो रहे हैं…')}</p>
      :invoices.length===0?<div className={styles.emptyState}>
        <span aria-hidden="true">▤</span>
        <strong>{t('No invoices yet','अभी कोई इनवॉइस नहीं है')}</strong>
        <p>{t('Once your course payment is verified, the issued invoice and tax breakdown will appear here.','कोर्स भुगतान सत्यापित होने पर जारी इनवॉइस और टैक्स विवरण यहाँ दिखाई देंगे।')}</p>
      </div>
      :<div className={styles.list}>
        {invoices.map(invoice=><article className={styles.row} key={invoice.invoice_document_id}>
          <div className={styles.documentIcon} aria-hidden="true">PDF</div>
          <div className={styles.details}>
            <strong>{invoice.document_number}</strong>
            <span>{invoice.course_title||t('Course purchase','कोर्स खरीद')}</span>
            {workspace==='PARENT'&&invoice.beneficiary_name&&<small>{t('Learner: ','विद्यार्थी: ')+invoice.beneficiary_name}</small>}
            <small>{new Date(invoice.issued_at).toLocaleDateString(english?'en-IN':'hi-IN')} · {t('Tax ','टैक्स ')}{money(invoice.tax_value_paise,invoice.currency)}</small>
          </div>
          <div className={styles.amount}>
            <strong>{money(invoice.total_value_paise,invoice.currency)}</strong>
            <span>{t('Paid','भुगतान हुआ')}</span>
          </div>
          <a className={styles.download} href={download(invoice.course_order_id)} download>{t('Download PDF','PDF डाउनलोड करें')} <span aria-hidden="true">↓</span></a>
        </article>)}
      </div>}
  </section>;
}
