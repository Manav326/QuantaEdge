'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function AdminStudents(){
  const router=useRouter(); const [rows,setRows]=useState<any[]>([]); const [error,setError]=useState('');
  useEffect(()=>{(async()=>{const r=await fetch('/api/v1/admin/students');if(!r.ok){router.replace('/login');return}setRows(await r.json())})().catch(e=>setError(e.message||'Unable to load students'))},[router]);
  return <main className="admin-shell"><aside className="admin-sidebar"><Link href="/" className="admin-brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>ADMIN</small></span></Link><nav><Link href="/">Dashboard</Link><Link href="/content">Content Studio</Link><Link href="/students" className="active">Students</Link></nav></aside>
    <section className="admin-main"><header className="admin-top"><div><span className="admin-kicker">LEARNER OPERATIONS</span><h1>Students</h1><p>Real production student profiles and learning activity.</p></div><Link href="/" className="button button-dark">← Dashboard</Link></header>
      {error&&<div className="feedback">{error}</div>}
      <section className="admin-panel"><div className="panel-head"><div><span>STUDENT ACCOUNTS</span><h2>Active profiles</h2></div><span className="count">{rows.length}</span></div>
        <div className="table"><div className="tr th"><span>Name</span><span>Class</span><span>Board / language</span><span>Environment</span><span>Completed</span></div>
        {rows.map(s=><div className="tr" key={s.id}><strong>{s.display_name}</strong><span>Class {s.class_code}</span><span>{s.board} · {s.language}</span><span className={s.active?'published':'draft'}>{s.environment}</span><span>{s.completed_lessons}</span></div>)}</div>
      </section>
    </section></main>;
}
