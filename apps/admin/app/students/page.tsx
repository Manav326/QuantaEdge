'use client';

import Link from 'next/link';
import AdminSidebar from '../components/AdminSidebar';
import { useUiTranslation } from '../i18n/useUiTranslation';
import PremiumPagination from '../components/PremiumPagination';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function AdminStudents(){
  const router=useRouter();
  const t=useUiTranslation(); const [rows,setRows]=useState<any[]>([]); const [error,setError]=useState('');
  const [query,setQuery]=useState(''); const [page,setPage]=useState(0); const [total,setTotal]=useState(0); const [pageSize,setPageSize]=useState(10);
  useEffect(()=>{(async()=>{const params=new URLSearchParams({limit:String(pageSize),offset:String(page*pageSize)});if(query.trim())params.set('query',query.trim());const r=await fetch('/api/v1/admin/students?'+params.toString(),{cache:'no-store'});if(!r.ok){if(r.status===401||r.status===403){router.replace('/login');return}const b=await r.json().catch(()=>({}));throw new Error(b.message||t('admin.students.loadFailed'))}const body=await r.json();setRows(Array.isArray(body.items)?body.items:(Array.isArray(body)?body:[]));setTotal(Number(body.total??(Array.isArray(body)?body.length:0)))})().catch(e=>setError(e.message||t('admin.students.loadFailed')))},[router,query,page,pageSize,t]);
  return <main className="admin-shell"><AdminSidebar active="students" />
    <section className="admin-main"><header className="admin-top"><div><span className="admin-kicker">{t('admin.students.eyebrow')}</span><h1>{t('admin.students.title')}</h1><p>{t('admin.students.description')}</p></div><Link href="/" className="button button-dark">{t('admin.students.dashboard')}</Link></header>
      {error&&<div className="feedback">{error}</div>}
      <section className="admin-panel"><div className="panel-head"><div><span>{t('admin.students.studentAccounts')}</span><h2>{t('admin.students.productionProfiles')}</h2></div><span className="count">{t('admin.students.accountCount',{count:total})}</span></div>
        <label style={{display:'grid',gap:6,maxWidth:440,marginBottom:16}}><span>{t('admin.students.searchLabel')}</span><input value={query} onChange={e=>{setQuery(e.target.value);setPage(0)}} placeholder={t("admin.students.searchPlaceholder")} style={{padding:'10px 12px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:10}} /></label>
        <div className="table"><div className="tr th"><span>{t('admin.students.name')}</span><span>{t('admin.students.class')}</span><span>{t('admin.students.boardLanguage')}</span><span>{t('admin.students.status')}</span><span>{t('admin.students.completed')}</span></div>
        {rows.map(s=><div className="tr" key={s.id}><strong>{s.display_name}</strong><span>{t('admin.students.classCode',{code:s.class_code})}</span><span>{s.board} · {s.language}</span><span className={s.active?'published':'draft'}>{s.active?t('admin.students.active'):t('admin.students.suspended')}</span><span>{s.completed_lessons}</span></div>)}</div>
        {!rows.length&&<div className="empty-state"><h2>{t('admin.students.emptyTitle')}</h2><p>{t('admin.students.emptyDescription')}</p></div>}
        <PremiumPagination page={page} total={total} pageSize={pageSize} onPageChange={setPage}
          onPageSizeChange={size=>{setPageSize(size);setPage(0);}} itemLabel={t("admin.students.itemLabel")} />
      </section>
    </section></main>;
}
