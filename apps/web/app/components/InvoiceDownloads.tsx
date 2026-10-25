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
  course_code?:string|null;
  course_type_code?:string|null;
  beneficiary_name?:string|null;
  pdf_available:boolean;
};

export default function InvoiceDownloads({workspace,english=true}:{workspace:Workspace;english?:boolean}) {
  const [invoices,setInvoices]=useState<Invoice[]>([]);
  const [loading,setLoading]=useState(true);
  const [error,setError]=useState('');
  const [downloading,setDownloading]=useState<number|null>(null);
  const t=(en:string,hi:string)=>english?en:hi;
  const courseTypeName=(code?:string|null)=>{
    switch(String(code||'').trim().toUpperCase()){
      case 'K12': return t('K–12 learning','विद्यालयी पढ़ाई');
      case 'COMPETITIVE': return t('Competitive preparation','प्रतियोगी परीक्षा तैयारी');
      case 'ASSESSMENT': return t('Assessment & tests','परीक्षा एवं टेस्ट');
      default: return '';
    }
  };
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
  const download=async(orderId:number,documentId:number,documentNumber:string)=>{
    setDownloading(documentId);setError('');
    try{
      const path=workspace==='PARENT'
        ?'/api/v1/marketplace/family/orders/'+orderId+'/documents/'+documentId+'.pdf'
        :'/api/v1/marketplace/orders/'+orderId+'/documents/'+documentId+'.pdf';
      const response=await apiFetch(path,{
        method:'GET',credentials:'same-origin',cache:'no-store',
        headers:{'X-QE-Workspace':workspace,'Accept':'application/pdf'}
      });
      if(!response.ok){
        const raw=await response.text();let data:any={};
        try{data=raw?JSON.parse(raw):{};}catch{data={message:raw};}
        throw new Error(String(data.message||data.detail||data.error||'The invoice PDF could not be downloaded.'));
      }
      const blob=await response.blob();
      if(blob.size===0||!blob.type.toLowerCase().includes('pdf'))throw new Error('The server did not return a valid invoice PDF.');
      const objectUrl=URL.createObjectURL(blob);
      const anchor=document.createElement('a');
      anchor.href=objectUrl;
      anchor.download=documentNumber.replace(/[^A-Za-z0-9_-]/g,'-')+'.pdf';
      document.body.appendChild(anchor);anchor.click();anchor.remove();
      window.setTimeout(()=>URL.revokeObjectURL(objectUrl),1000);
    }catch(e){setError(e instanceof Error?e.message:t('The invoice PDF could not be downloaded.','इनवॉइस PDF डाउनलोड नहीं हो सका।'));}
    finally{setDownloading(null);}
  };

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
            {(courseTypeName(invoice.course_type_code)||invoice.course_code)&&<small>{[courseTypeName(invoice.course_type_code),invoice.course_code].filter(Boolean).join(' · ')}</small>}
            {workspace==='PARENT'&&invoice.beneficiary_name&&<small>{t('Learner: ','विद्यार्थी: ')+invoice.beneficiary_name}</small>}
            <small>{new Date(invoice.issued_at).toLocaleDateString(english?'en-IN':'hi-IN')} · {t('Tax ','टैक्स ')}{money(invoice.tax_value_paise,invoice.currency)}</small>
          </div>
          <div className={styles.amount}>
            <strong>{money(invoice.total_value_paise,invoice.currency)}</strong>
            <span>{invoice.document_type==='CREDIT_NOTE'?t('Credit note','क्रेडिट नोट'):invoice.document_type==='DEBIT_NOTE'?t('Debit note','डेबिट नोट'):t('Paid','भुगतान हुआ')}</span>
          </div>
          <button className={styles.download} type="button" disabled={downloading!==null} onClick={()=>void download(invoice.course_order_id,invoice.invoice_document_id,invoice.document_number)}>{downloading===invoice.invoice_document_id?t('Preparing…','तैयार हो रहा है…'):t('Download PDF','PDF डाउनलोड करें')} <span aria-hidden="true">↓</span></button>
        </article>)}
      </div>}
  </section>;
}
