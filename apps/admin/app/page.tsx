'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function AdminHome(){
  const router=useRouter(); const [me,setMe]=useState<any>(null); const [overview,setOverview]=useState<any>(null); const [error,setError]=useState('');
  useEffect(()=>{(async()=>{const mr=await fetch('/api/v1/auth/me');if(!mr.ok){router.replace('/login');return}const mb=await mr.json();if(mb.role!=='ADMIN'){router.replace('/login');return}setMe(mb);const r=await fetch('/api/v1/admin/overview');const b=await r.json();if(!r.ok)throw new Error(b.message||'Admin data unavailable');setOverview(b)})().catch((e)=>setError(e.message||'Admin data unavailable'))},[router]);
  if(error)return <main className="admin-shell"><section className="admin-main"><div className="auth-card"><h1>Admin unavailable</h1><p>{error}</p></div></section></main>;
  if(!me||!overview)return <main className="admin-shell"><section className="admin-main"><div className="admin-kicker">QUANTAEDGE LEARNING</div><h1>Loading operations…</h1></section></main>;
  return <main className="admin-shell"><aside className="admin-sidebar"><Link href="/" className="admin-brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>ADMIN</small></span></Link><nav><Link className="active" href="#top">▦ Dashboard</Link><Link href="/content">◈ Content Studio</Link><Link href="/students">◉ Students</Link><a href="#review">Review</a><a href="#sources">Provenance</a><a href="#assets">Assets</a></nav><div className="admin-user"><span className="avatar">A</span><div><strong>{me.displayName||'Admin'}</strong><small>Authorized operator</small></div></div></aside>
    <section className="admin-main" id="top"><header className="admin-top"><div><span className="admin-kicker">QUANTAEDGE LEARNING</span><h1>Content operations</h1></div><div className="top-status"><span className="status-dot"/> Authenticated admin</div></header>
      <div className="admin-grid stats">{Object.entries(overview.summary||{}).map(([k,v])=><article key={k}><span>{k.replaceAll('_',' ')}</span><strong>{String(v)}</strong><small>live database</small></article>)}</div>
      <div className="admin-grid main-panels"><article className="admin-panel wide" id="review"><div className="panel-head"><div><span>CONTENT GOVERNANCE</span><h2>Question review</h2></div></div>{overview.review.map((x:any)=><div className="activity" key={x.review_status}><div><b>{x.count}</b><span>{x.review_status}</span></div></div>)}</article>
        <article className="admin-panel" id="sources"><div className="panel-head"><div><span>PROVENANCE</span><h2>Question sources</h2></div></div>{overview.sources.map((x:any)=><div className="topic-line" key={x.source_kind}><b>{x.source_kind}</b><span>{x.count}</span></div>)}</article>
        <article className="admin-panel" id="assets"><div className="panel-head"><div><span>VISUAL CONTENT</span><h2>Asset readiness</h2></div></div>{overview.assets.map((x:any)=><div className="topic-line" key={x.asset_type+x.status}><b>{x.asset_type}</b><span>{x.status} · {x.count}</span></div>)}</article>
      </div>
    </section></main>;
}
