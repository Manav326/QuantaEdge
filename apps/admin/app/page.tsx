'use client';

import Link from 'next/link';
import AdminSidebar from './components/AdminSidebar';
import {useEffect,useMemo,useState} from 'react';
import {useRouter} from 'next/navigation';

type AnyRow=Record<string,any>;

export default function AdminHome(){
  const router=useRouter();
  const [me,setMe]=useState<AnyRow|null>(null);
  const [overview,setOverview]=useState<AnyRow|null>(null);
  const [analytics,setAnalytics]=useState<AnyRow|null>(null);
  const [tutorAnalytics,setTutorAnalytics]=useState<AnyRow|null>(null);
  const [error,setError]=useState('');
  const [loading,setLoading]=useState(true);

  async function logout(){
    await fetch('/api/v1/auth/logout',{method:'POST'});
    router.replace('/login');
  }

  useEffect(()=>{
    let mounted=true;
    (async()=>{
      const mr=await fetch('/api/v1/auth/me');
      if(!mr.ok){router.replace('/login');return;}
      const mb=await mr.json();
      if(mb.role!=='ADMIN'){
        const permissions=Array.isArray(mb.permissions)?mb.permissions:[];
        if(permissions.includes('CONTENT_VIEW'))router.replace('/content');
        else if(permissions.includes('AUDIT_VIEW'))router.replace('/employees?view=audit');
        else router.replace('/login');
        return;
      }
      if(!mounted)return;
      setMe(mb);
      const responses=await Promise.all([
        fetch('/api/v1/admin/overview'),
        fetch('/api/v1/admin/analytics'),
        fetch('/api/v1/admin/tutor/analytics')
      ]);
      const overviewBody=await responses[0].json();
      if(!responses[0].ok)throw new Error(overviewBody.message||'Admin data unavailable');
      if(!mounted)return;
      setOverview(overviewBody);
      if(responses[1].ok)setAnalytics(await responses[1].json());
      if(responses[2].ok)setTutorAnalytics(await responses[2].json());
    })().catch((e:any)=>{if(mounted)setError(e.message||'Admin data unavailable');})
      .finally(()=>{if(mounted)setLoading(false);});
    return ()=>{mounted=false;};
  },[router]);

  const summary=overview?.summary||{};
  const reviewRows:AnyRow[]=overview?.review||[];
  const sourceRows:AnyRow[]=overview?.sources||[];
  const assetRows:AnyRow[]=overview?.assets||[];
  const totalReview=useMemo(()=>reviewRows.reduce((n,row)=>n+Number(row.count||0),0),[reviewRows]);
  const activeReview=useMemo(()=>reviewRows.filter(row=>['APPROVED','PUBLISHED'].includes(String(row.review_status))).reduce((n,row)=>n+Number(row.count||0),0),[reviewRows]);

  if(error)return <main className="admin-shell"><section className="admin-main"><div className="admin-empty-state"><div className="admin-empty-mark">!</div><span className="admin-kicker">QUANTAEDGE OPERATIONS</span><h1>Dashboard unavailable</h1><p>{error}</p><button className="button button-dark" onClick={()=>window.location.reload()}>Try again</button></div></section></main>;
  if(loading||!me||!overview)return <main className="admin-shell"><AdminSidebar active="overview" variant="overview" /><section className="admin-main"><div className="admin-loading"><img className="admin-loading-mark" src="/branding/quantaedge-icon.png" alt="" aria-hidden="true" /><span className="admin-kicker">QUANTAEDGE LEARNING</span><h1>Preparing your workspace</h1><p>Connecting to current content and learning records…</p></div></section></main>;

  const pendingReview=reviewRows.filter(row=>!['APPROVED','PUBLISHED'].includes(String(row.review_status))).reduce((n,row)=>n+Number(row.count||0),0);
  const primaryStats=[
    {label:'Active classes',value:summary.classes??'—',helper:'curriculum tracks enabled',tone:'violet',icon:'▦'},
    {label:'Published chapters',value:summary.chapters??'—',helper:'ready to learn',tone:'mint',icon:'◈'},
    {label:'Published lessons',value:summary.published_lessons??'—',helper:'available in the curriculum',tone:'blue',icon:'▤'},
    {label:'Questions needing review',value:pendingReview,helper:'not yet approved or published',tone:'amber',icon:'✳'}
  ];
  const learningStats=[
    {label:'Active students · 30 days',value:analytics?.summary?.active_students_30d??'—'},
    {label:'Learning sessions',value:analytics?.summary?.sessions_30d??'—'},
    {label:'Average session',value:analytics?.summary?.avg_session_minutes==null?'—':`${analytics.summary.avg_session_minutes} min`},
    {label:'Answer accuracy',value:analytics?.learning?.accuracy_30d==null?'—':`${analytics.learning.accuracy_30d}%`}
  ];

  return <main className="admin-shell">
    <AdminSidebar active="overview" variant="overview" displayName={me.displayName||"Admin"} />
    <section className="admin-main" id="top">
      <header className="admin-top">
        <div><span className="admin-kicker">QUANTAEDGE LEARNING / OPERATIONS</span><h1>Good day, {me.displayName||'Admin'} <span className="admin-title-spark">✦</span></h1><p>Here’s your live view of the learning platform.</p></div>
        <div className="admin-top-actions"><span className="top-status"><span className="status-dot"/> Connected to live data</span><button className="admin-refresh" onClick={()=>window.location.reload()} title="Refresh dashboard">↻</button></div>
      </header>

      <nav className="admin-mobile-nav" aria-label="Admin navigation">
        <Link className="active" href="/">Overview</Link><Link href="/content">Content</Link><Link href="/students">Students</Link><Link href="/parents">Parents</Link>
      </nav>

      

      <div className="admin-section-title"><div><span className="admin-kicker">PLATFORM SNAPSHOT</span><h2>At a glance</h2></div><span className="admin-live-label"><i/> Live records</span></div>
      <div className="admin-grid stats admin-primary-stats">
        {primaryStats.map((stat,i)=><article className="admin-kpi" key={stat.label}><div className={`admin-kpi-icon ${stat.tone}`}>{stat.icon}</div><span>{stat.label}</span><strong>{String(stat.value)}</strong><small>{stat.helper}</small><span className="admin-kpi-index">0{i+1}</span></article>)}
      </div>

      <section className="admin-panel admin-learning-panel" id="analytics">
        <div className="panel-head"><div><span className="admin-kicker">LEARNING ANALYTICS</span><h2>Real usage, real progress</h2><p>Signals from actual student sessions and answer attempts.</p></div><Link href="/students" className="admin-inline-link">Inspect students <span>→</span></Link></div>
        <div className="admin-learning-metrics">{learningStats.map((stat,i)=><article key={stat.label}><span className="admin-metric-index">0{i+1}</span><small>{stat.label}</small><strong>{String(stat.value)}</strong></article>)}</div>
      </section>

      {tutorAnalytics&&<section className="admin-panel admin-learning-panel"><div className="panel-head"><div><span className="admin-kicker">AI TUTOR</span><h2>Tutor activity · 30 days</h2><p>Observed usage only; no synthetic activity is shown.</p></div></div><div className="admin-learning-metrics">{[
        {label:'Active students',value:tutorAnalytics.summary?.active_students_30d??'—'},
        {label:'Learner messages',value:tutorAnalytics.summary?.user_messages_30d??'—'},
        {label:'Tutor replies',value:tutorAnalytics.summary?.assistant_messages_30d??'—'},
        {label:'Output tokens',value:tutorAnalytics.summary?.output_tokens_30d??'—'}
      ].map((stat,i)=><article key={stat.label}><span className="admin-metric-index">0{i+1}</span><small>{stat.label}</small><strong>{String(stat.value)}</strong></article>)}</div></section>}

      <div className="admin-section-title admin-governance-title" id="governance"><div><span className="admin-kicker">QUALITY CONTROL</span><h2>Content governance</h2></div><Link href="/content" className="admin-inline-link">Open authoring tools <span>→</span></Link></div>
      <div className="admin-grid admin-governance-grid">
        <article className="admin-panel admin-review-panel"><div className="panel-head"><div><span className="admin-kicker">QUESTION WORKFLOW</span><h2>Review pipeline</h2><p>Track what is ready and what needs editorial attention.</p></div><span className="admin-panel-icon">✓</span></div>
          <div className="admin-review-summary"><div><strong>{activeReview}</strong><span>approved / published</span></div><div><strong>{Math.max(0,totalReview-activeReview)}</strong><span>other statuses</span></div></div>
          <div className="admin-review-list">{reviewRows.map((row,i)=>{const count=Number(row.count||0);const pct=totalReview?Math.round(count*100/totalReview):0;const status=String(row.review_status||'unknown');return <div className="admin-review-row" key={status}><div className="admin-review-label"><span className={`review-status-mark status-${status.toLowerCase().replace(/[^a-z]+/g,'-')}`}/><strong>{status.replaceAll('_',' ')}</strong><b>{count}</b></div><div className="admin-review-bar"><span style={{width:`${pct}%`}}/></div></div>})}{!reviewRows.length&&<p className="admin-no-data">No review records available.</p>}</div>
        </article>
        <article className="admin-panel admin-breakdown-panel"><div className="panel-head"><div><span className="admin-kicker">PROVENANCE</span><h2>Question sources</h2><p>Origin of recorded question content.</p></div><span className="admin-panel-icon">⌁</span></div>
          <div className="admin-breakdown-list">{sourceRows.map((row:any)=><div className="admin-breakdown-row" key={row.source_kind}><span className="admin-breakdown-dot"/><strong>{String(row.source_kind||'Unknown').replaceAll('_',' ')}</strong><span>{row.count}</span></div>)}{!sourceRows.length&&<p className="admin-no-data">No question-source records available.</p>}</div>
        </article>
        <article className="admin-panel admin-breakdown-panel"><div className="panel-head"><div><span className="admin-kicker">VISUAL CONTENT</span><h2>Asset readiness</h2><p>Editorial state of learning media.</p></div><span className="admin-panel-icon">▧</span></div>
          <div className="admin-breakdown-list">{assetRows.map((row:any)=><div className="admin-breakdown-row" key={row.asset_type+row.status}><span className="admin-breakdown-dot asset-dot"/><strong>{String(row.asset_type||'Asset')} · {String(row.status||'Unknown')}</strong><span>{row.count}</span></div>)}{!assetRows.length&&<p className="admin-no-data">No asset records available.</p>}</div>
        </article>
      </div>
      <footer className="admin-footer">QuantaEdge · Learning operations <span>Records reflect the connected database; this dashboard does not generate demo activity.</span></footer>
    </section>
  </main>;
}
