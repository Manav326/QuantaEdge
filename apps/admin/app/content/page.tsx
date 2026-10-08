'use client';

import Link from 'next/link';
import {useEffect,useState} from 'react';
import {useRouter} from 'next/navigation';

export default function ContentPage(){
  const router=useRouter(); const [lessons,setLessons]=useState<any[]>([]); const [questions,setQuestions]=useState<any[]>([]);
  const [selected,setSelected]=useState<any>(null); const [loading,setLoading]=useState(true); const [error,setError]=useState('');
  async function loadLessons(){
    const r=await fetch('/api/v1/admin/lessons'); if(!r.ok){router.replace('/login');return}
    setLessons(await r.json()); setLoading(false);
  }
  async function openLesson(id:number){
    const r=await fetch('/api/v1/admin/lessons/'+id); const b=await r.json();
    if(!r.ok){setError(b.message||'Lesson unavailable');return}
    setSelected(b); setQuestions(b.questions||[]);
  }
  async function publishLesson(id:number,status:string){
    const r=await fetch('/api/v1/admin/lessons/'+id+'/status',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({status})});
    const b=await r.json(); if(!r.ok){setError(b.message||'Status update failed');return}
    setLessons(prev=>prev.map(x=>x.id===id?{...x,status:b.status}:x));
    if(selected?.lesson?.id===id) setSelected({...selected,lesson:{...selected.lesson,status:b.status}});
  }
  async function updateQuestion(q:any,patch:any){
    const body={...q,...patch,sourceKind:q.source_kind||q.sourceKind||'AUTHOR_CREATED',sourceYear:q.source_year||q.sourceYear||null,sourceId:q.source_id||q.sourceId||null,sourceRef:q.source_ref||q.sourceRef||null,sourceTitle:q.source_title||q.sourceTitle||null,reviewStatus:patch.reviewStatus||q.review_status||'DRAFT',examFormat:q.exam_format||null};
    const r=await fetch('/api/v1/admin/questions/'+q.id,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});
    if(!r.ok){const b=await r.json();setError(b.message||'Question update failed');return}
    setQuestions(prev=>prev.map(x=>x.id===q.id?{...x,review_status:body.reviewStatus}:x));
  }
  useEffect(()=>{loadLessons().catch(e=>setError(e.message||'Unable to load'))},[]);
  if(loading)return <main className="admin-shell"><aside className="admin-sidebar"><Link href="/" className="admin-brand">QuantaEdge</Link></aside><section className="admin-main"><div className="admin-top"><div><span className="admin-kicker">CONTENT OPERATIONS</span><h1>Loading…</h1></div></div></section></main>;
  return <main className="admin-shell"><aside className="admin-sidebar"><Link href="/"><span className="brand-mark">Q</span></Link><nav><Link href="/">Dashboard</Link><Link href="/content" className="active">Content Studio</Link></nav></aside>
    <section className="admin-main"><header className="admin-top"><div><span className="admin-kicker">CONTENT OPERATIONS</span><h1>Content Studio</h1><p>Curriculum, lesson lifecycle, question review and provenance.</p></div><Link href="/" className="button button-dark">← Dashboard</Link></header>
      {error&&<div className="feedback">{error}</div>}
      <div className="studio-layout">
        <section className="admin-panel"><div className="panel-head"><div><span>LESSONS</span><h2>Publishing pipeline</h2></div><span className="count">{lessons.length}</span></div>
          <div className="studio-list">{lessons.map(l=><button key={l.id} className={'studio-item '+(selected?.lesson?.id===l.id?'selected':'')} onClick={()=>openLesson(l.id)}>
            <span><b>{l.title}</b><small>Class {l.class_code} · {l.subject_code} · {l.chapter_name}</small></span><em className={String(l.status).toLowerCase()}>{l.status}</em>
          </button>)}</div>
        </section>
        <section className="admin-panel">
          {!selected?<div className="empty-state"><span>◈</span><h2>Select a lesson</h2><p>Review blocks, question provenance and publish state.</p></div>:
          <><div className="panel-head"><div><span>{selected.lesson.chapter_name}</span><h2>{selected.lesson.title}</h2></div><div className="studio-actions"><button onClick={()=>publishLesson(selected.lesson.id,'REVIEW')}>Review</button><button onClick={()=>publishLesson(selected.lesson.id,'PUBLISHED')}>Publish</button></div></div>
            <div className="studio-section"><h3>Lesson blocks</h3>{selected.blocks.map((b:any)=><div className="studio-row" key={b.id}><span>{b.sequence_no}. {b.block_type}</span><small>{b.asset_url?'Visual asset attached':'Text/content block'}</small></div>)}</div>
            <div className="studio-section"><h3>Questions · provenance</h3>{questions.map((q:any)=><div className="studio-row question-row" key={q.id}><div><b>{q.question_type}</b><small>{q.prompt}</small><div className="source-fields"><select value={q.source_kind||'AUTHOR_CREATED'} onChange={e=>setQuestions(prev=>prev.map(x=>x.id===q.id?{...x,source_kind:e.target.value}:x))}><option>AUTHOR_CREATED</option><option>STATE_TEXTBOOK</option><option>BOARD_PAST_PAPER</option><option>TEXTBOOK_DERIVED</option></select><input type="number" placeholder="Year" value={q.source_year||''} onChange={e=>setQuestions(prev=>prev.map(x=>x.id===q.id?{...x,source_year:e.target.value}:x))}/><input type="number" placeholder="Source ID" value={q.source_id||''} onChange={e=>setQuestions(prev=>prev.map(x=>x.id===q.id?{...x,source_id:e.target.value}:x))}/><input placeholder="Source ref" value={q.source_ref||''} onChange={e=>setQuestions(prev=>prev.map(x=>x.id===q.id?{...x,source_ref:e.target.value}:x))}/></div></div><div className="studio-actions"><select value={q.review_status||'DRAFT'} onChange={e=>updateQuestion(q,{reviewStatus:e.target.value})}><option>DRAFT</option><option>REVIEW</option><option>APPROVED</option><option>PUBLISHED</option><option>REJECTED</option></select><button onClick={()=>updateQuestion(q,{})}>Save</button></div></div>)}</div>
          </>}
        </section>
      </div>
    </section>
  </main>;
}
