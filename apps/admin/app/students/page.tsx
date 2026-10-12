'use client';

import Link from 'next/link';
import AdminSidebar from '../components/AdminSidebar';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function AdminStudents(){
  const router=useRouter(); const [rows,setRows]=useState<any[]>([]); const [error,setError]=useState('');
  const [query,setQuery]=useState(''); const [page,setPage]=useState(0); const [total,setTotal]=useState(0); const pageSize=25;
  useEffect(()=>{(async()=>{const params=new URLSearchParams({limit:String(pageSize),offset:String(page*pageSize)});if(query.trim())params.set('query',query.trim());const r=await fetch('/api/v1/admin/students?'+params.toString(),{cache:'no-store'});if(!r.ok){if(r.status===401||r.status===403){router.replace('/login');return}const b=await r.json().catch(()=>({}));throw new Error(b.message||'Unable to load students')}const body=await r.json();setRows(Array.isArray(body.items)?body.items:(Array.isArray(body)?body:[]));setTotal(Number(body.total??(Array.isArray(body)?body.length:0)))})().catch(e=>setError(e.message||'Unable to load students'))},[router,query,page]);
  return <main className="admin-shell"><AdminSidebar active="students" />
    <section className="admin-main"><header className="admin-top"><div><span className="admin-kicker">LEARNER OPERATIONS</span><h1>Students</h1><p>Real production student profiles and learning activity.</p></div><Link href="/" className="button button-dark">← Dashboard</Link></header>
      {error&&<div className="feedback">{error}</div>}
      <section className="admin-panel"><div className="panel-head"><div><span>STUDENT ACCOUNTS</span><h2>Production student profiles</h2></div><span className="count">{total} accounts</span></div>
        <label style={{display:'grid',gap:6,maxWidth:440,marginBottom:16}}><span>Search by name, class, or student ID</span><input value={query} onChange={e=>{setQuery(e.target.value);setPage(0)}} placeholder="Search student directory" style={{padding:'10px 12px',border:'1px solid var(--admin-border,#d8dce5)',borderRadius:10}} /></label>
        <div className="table"><div className="tr th"><span>Name</span><span>Class</span><span>Board / language</span><span>Status</span><span>Completed</span></div>
        {rows.map(s=><div className="tr" key={s.id}><strong>{s.display_name}</strong><span>Class {s.class_code}</span><span>{s.board} · {s.language}</span><span className={s.active?'published':'draft'}>{s.active?'Active':'Suspended'}</span><span>{s.completed_lessons}</span></div>)}</div>
        {!rows.length&&<div className="empty-state"><h2>No matching students</h2><p>Try another name, class, or student ID.</p></div>}
        <div style={{display:'flex',alignItems:'center',justifyContent:'space-between',gap:12,marginTop:18,flexWrap:'wrap'}}>
          <span>Showing {total===0?0:page*pageSize+1}–{Math.min(total,(page+1)*pageSize)} of {total}</span>
          <div style={{display:'flex',alignItems:'center',gap:8}}>
            <button type="button" className="button" disabled={page===0} onClick={()=>setPage(value=>Math.max(0,value-1))}>← Previous</button>
            <span>Page {total===0?0:page+1} of {Math.max(1,Math.ceil(total/pageSize))}</span>
            <button type="button" className="button" disabled={(page+1)*pageSize>=total} onClick={()=>setPage(value=>value+1)}>Next →</button>
          </div>
        </div>
      </section>
    </section></main>;
}
