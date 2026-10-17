'use client';

import Link from 'next/link';
import AdminSidebar from './components/AdminSidebar';
import { useUiTranslation } from './i18n/useUiTranslation';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

type AnyRow=Record<string,any>;

export default function AdminHome(){
  const router=useRouter();
  const t=useUiTranslation();
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
  const countQuestionStatus=(statuses:string[],activeOnly=true)=>reviewRows
    .filter(row=>statuses.includes(String(row.review_status||'DRAFT').toUpperCase())&&(!activeOnly||row.active===true))
    .reduce((n,row)=>n+Number(row.count||0),0);
  const totalReview=reviewRows.reduce((n,row)=>n+Number(row.count||0),0);
  const activeReview=countQuestionStatus(['APPROVED','PUBLISHED']);
  const activeQuestions=countQuestionStatus(['DRAFT','REVIEW','REJECTED','APPROVED','PUBLISHED']);
  const inactiveQuestions=reviewRows.filter(row=>row.active===false).reduce((n,row)=>n+Number(row.count||0),0);

  if(error)return <main className="admin-shell"><section className="admin-main"><div className="admin-empty-state"><div className="admin-empty-mark">!</div><span className="admin-kicker">{t('admin.dashboard.operations')}</span><h1>{t('admin.dashboard.unavailable')}</h1><p>{error}</p><button className="button button-dark" onClick={()=>window.location.reload()}>{t('admin.dashboard.tryAgain')}</button></div></section></main>;
  if(loading||!me||!overview)return <main className="admin-shell"><AdminSidebar active="overview" variant="overview" /><section className="admin-main"><div className="admin-loading"><img className="admin-loading-mark" src="/branding/quantaedge-icon.png" alt="" aria-hidden="true" /><span className="admin-kicker">{t('admin.dashboard.learning')}</span><h1>{t('admin.dashboard.preparingWorkspace')}</h1><p>{t('admin.dashboard.connectingRecords')}</p></div></section></main>;

  const pendingReview=countQuestionStatus(['REVIEW']);
  const primaryStats=[
    {label:t('admin.dashboard.stat.activeClasses'),value:summary.classes??'—',helper:t('admin.dashboard.stat.curriculumTracksEnabled'),tone:'violet',icon:'▦'},
    {label:t('admin.dashboard.stat.publishedChapters'),value:summary.chapters??'—',helper:t('admin.dashboard.stat.readyToLearn'),tone:'mint',icon:'◈'},
    {label:t('admin.dashboard.stat.publishedLessons'),value:summary.published_lessons??'—',helper:t('admin.dashboard.stat.availableInCurriculum'),tone:'blue',icon:'▤'},
    {label:t('admin.dashboard.stat.questionsAwaitingReview'),value:pendingReview,helper:t('admin.dashboard.stat.submittedForReview'),tone:'amber',icon:'✳'}
  ];
  const learningStats=[
    {label:t('admin.dashboard.metric.activeStudents30d'),value:analytics?.summary?.active_students_30d??'—'},
    {label:t('admin.dashboard.metric.learningSessions'),value:analytics?.summary?.sessions_30d??'—'},
    {label:t('admin.dashboard.metric.averageSession'),value:analytics?.summary?.avg_session_minutes==null?'—':`${analytics.summary.avg_session_minutes} min`},
    {label:t('admin.dashboard.metric.answerAccuracy'),value:analytics?.learning?.accuracy_30d==null?'—':`${analytics.learning.accuracy_30d}%`}
  ];

  return <main className="admin-shell">
    <AdminSidebar active="overview" variant="overview" displayName={me.displayName||"Admin"} />
    <section className="admin-main" id="top">
      <header className="admin-top">
        <div><span className="admin-kicker">{t('admin.dashboard.hero.operations')}</span><h1>{t('admin.dashboard.hero.greeting')}, {me.displayName||'Admin'} <span className="admin-title-spark">✦</span></h1><p>{t('admin.dashboard.hero.liveView')}</p></div>
        <div className="admin-top-actions"><span className="top-status"><span className="status-dot"/> {t('admin.dashboard.hero.connected')}</span><button className="admin-refresh" onClick={()=>window.location.reload()} title={t('admin.dashboard.hero.refresh')}>↻</button></div>
      </header>

      <nav className="admin-mobile-nav" aria-label={t('admin.navigation.label')}>
        <Link className="active" href="/">{t('admin.navigation.overview')}</Link><Link href="/content">{t('admin.navigation.short.content')}</Link><Link href="/translations">{t('admin.navigation.languageReview')}</Link><Link href="/questions">{t('admin.navigation.questionReview')}</Link><Link href="/students">{t('admin.navigation.students')}</Link><Link href="/parents">{t('admin.navigation.parentsFamilies')}</Link>
      </nav>

      

      <div className="admin-section-title"><div><span className="admin-kicker">{t('admin.dashboard.snapshot')}</span><h2>{t('admin.dashboard.atAGlance')}</h2></div><span className="admin-live-label"><i/> {t('admin.dashboard.liveRecords')}</span></div>
      <div className="admin-grid stats admin-primary-stats">
        {primaryStats.map((stat,i)=><article className="admin-kpi" key={stat.label}><div className={`admin-kpi-icon ${stat.tone}`}>{stat.icon}</div><span>{stat.label}</span><strong>{String(stat.value)}</strong><small>{stat.helper}</small><span className="admin-kpi-index">0{i+1}</span></article>)}
      </div>

      <section className="admin-panel admin-learning-panel" id="analytics">
        <div className="panel-head"><div><span className="admin-kicker">{t('admin.dashboard.learningAnalytics')}</span><h2>{t('admin.dashboard.realUsageProgress')}</h2><p>{t('admin.dashboard.learningSignals')}</p></div><Link href="/students" className="admin-inline-link">{t('admin.dashboard.inspectStudents')} <span>→</span></Link></div>
        <div className="admin-learning-metrics">{learningStats.map((stat,i)=><article key={stat.label}><span className="admin-metric-index">0{i+1}</span><small>{stat.label}</small><strong>{String(stat.value)}</strong></article>)}</div>
      </section>

      {tutorAnalytics&&<section className="admin-panel admin-learning-panel"><div className="panel-head"><div><span className="admin-kicker">{t('admin.dashboard.tutorEyebrow')}</span><h2>{t('admin.dashboard.tutorActivity')}</h2><p>{t('admin.dashboard.tutorObservedOnly')}</p></div></div><div className="admin-learning-metrics">{[
        {label:t('admin.dashboard.activeStudents'),value:tutorAnalytics.summary?.active_students_30d??'—'},
        {label:t('admin.dashboard.learnerMessages'),value:tutorAnalytics.summary?.user_messages_30d??'—'},
        {label:t('admin.dashboard.tutorReplies'),value:tutorAnalytics.summary?.assistant_messages_30d??'—'},
        {label:t('admin.dashboard.outputTokens'),value:tutorAnalytics.summary?.output_tokens_30d??'—'}
      ].map((stat,i)=><article key={stat.label}><span className="admin-metric-index">0{i+1}</span><small>{stat.label}</small><strong>{String(stat.value)}</strong></article>)}</div>
        {Array.isArray(tutorAnalytics.providers)&&tutorAnalytics.providers.length>0&&<div className="admin-learning-metrics" style={{marginTop:16}}>{tutorAnalytics.providers.map((provider:AnyRow,i:number)=><article key={String(provider.model)}><span className="admin-metric-index">P{i+1}</span><small>{String(provider.model)}</small><strong>{String(provider.replies)} {t('admin.dashboard.replies')}</strong><small>{String(provider.input_tokens)} {t('admin.dashboard.inputTokens')} · {String(provider.output_tokens)} {t('admin.dashboard.outputTokensLower')}</small></article>)}</div>}
      </section>}

      <div className="admin-section-title admin-governance-title" id="governance"><div><span className="admin-kicker">{t('admin.dashboard.qualityControl')}</span><h2>{t('admin.dashboard.contentGovernance')}</h2></div><Link href="/translations" className="admin-inline-link">{t('admin.dashboard.reviewTranslations')} <span>→</span></Link></div>
      <div className="admin-grid admin-governance-grid">
        <article className="admin-panel admin-review-panel"><div className="panel-head"><div><span className="admin-kicker">{t('admin.dashboard.questionWorkflow')}</span><h2>{t('admin.dashboard.reviewPipeline')}</h2><p>{t('admin.dashboard.reviewAttention')}</p></div><span className="admin-panel-icon">✓</span></div>
          <div className="admin-review-summary">
            <div><strong>{countQuestionStatus(['DRAFT'])}</strong><span>{t('admin.dashboard.activeDrafts')}</span></div>
            <div><strong>{countQuestionStatus(['REVIEW'])}</strong><span>{t('admin.dashboard.activeAwaitingReview')}</span></div>
            <div><strong>{countQuestionStatus(['REJECTED'])}</strong><span>{t('admin.dashboard.activeRejected')}</span></div>
            <div><strong>{activeReview}</strong><span>{t('admin.dashboard.activeApprovedPublished')}</span></div>
          </div>
          <p className="admin-review-footnote">{t('admin.dashboard.questionRecordsSummary',{total:totalReview,active:activeQuestions,inactive:inactiveQuestions})}</p>
          <div className="admin-review-list">{reviewRows.map((row)=>{const count=Number(row.count||0);const pct=totalReview?Math.round(count*100/totalReview):0;const status=String(row.review_status||'DRAFT');const active=row.active===true;return <div className="admin-review-row" key={status+'-'+String(active)}><div className="admin-review-label"><span className={`review-status-mark status-${status.toLowerCase().replace(/[^a-z]+/g,'-')}`}/><strong>{t('admin.dashboard.status.' + status.toLowerCase())} · {t(active?'admin.dashboard.status.active':'admin.dashboard.status.inactive')}</strong><b>{count}</b></div><div className="admin-review-bar"><span style={{width:`${pct}%`}}/></div></div>})}{!reviewRows.length&&<p className="admin-no-data">{t('admin.dashboard.noQuestionRecords')}</p>}</div>
        </article>
        <article className="admin-panel admin-breakdown-panel"><div className="panel-head"><div><span className="admin-kicker">{t('admin.dashboard.provenance')}</span><h2>{t('admin.dashboard.questionSources')}</h2><p>{t('admin.dashboard.questionOrigin')}</p></div><span className="admin-panel-icon">⌁</span></div>
          <div className="admin-breakdown-list">{sourceRows.map((row:any)=><div className="admin-breakdown-row" key={row.source_kind}><span className="admin-breakdown-dot"/><strong>{String(row.source_kind||'Unknown').replaceAll('_',' ')}</strong><span>{row.count}</span></div>)}{!sourceRows.length&&<p className="admin-no-data">{t('admin.dashboard.noQuestionSources')}</p>}</div>
        </article>
        <article className="admin-panel admin-breakdown-panel"><div className="panel-head"><div><span className="admin-kicker">{t('admin.dashboard.visualContent')}</span><h2>{t('admin.dashboard.assetReadiness')}</h2><p>{t('admin.dashboard.mediaEditorialState')}</p></div><span className="admin-panel-icon">▧</span></div>
          <div className="admin-breakdown-list">{assetRows.map((row:any)=><div className="admin-breakdown-row" key={row.asset_type+row.status}><span className="admin-breakdown-dot asset-dot"/><strong>{String(row.asset_type||t('admin.dashboard.asset'))} · {String(row.status||t('admin.dashboard.unknown'))}</strong><span>{row.count}</span></div>)}{!assetRows.length&&<p className="admin-no-data">{t('admin.dashboard.noAssetRecords')}</p>}</div>
        </article>
      </div>
      <footer className="admin-footer">{t('admin.dashboard.footer')} <span>{t('admin.dashboard.footerNote')}</span></footer>
    </section>
  </main>;
}
