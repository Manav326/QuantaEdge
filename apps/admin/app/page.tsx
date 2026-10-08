import Link from 'next/link';

export const dynamic = 'force-dynamic';

type CurriculumRow = { class_code:string; class_name:string; subject_code:string; subject_name:string; chapter_code:string; chapter_name:string };

async function getCurriculum() {
  const base = process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080';
  const response = await fetch(`${base}/api/v1/curriculum`, { cache: 'no-store' });
  if (!response.ok) throw new Error('Curriculum API unavailable');
  return response.json() as Promise<CurriculumRow[]>;
}

async function getLessons(classCode:string, subjectCode:string) {
  const base = process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080';
  const response = await fetch(`${base}/api/v1/learning/lessons?classCode=${classCode}&subjectCode=${subjectCode}`, { cache: 'no-store' });
  if (!response.ok) return [];
  return response.json() as Promise<any[]>;
}

export default async function AdminHome() {
  const curriculum = await getCurriculum();
  const pairs = [...new Map(curriculum.map(x => [`${x.class_code}-${x.subject_code}`, x])).values()];
  const lessonGroups = await Promise.all(pairs.map(async x => ({...x, lessons: await getLessons(x.class_code,x.subject_code)})));
  const classCount = new Set(curriculum.map(x => x.class_code)).size;
  const subjectCount = new Set(curriculum.map(x => `${x.class_code}|${x.subject_code}`)).size;
  const chapters = new Set(curriculum.map(x => `${x.class_code}|${x.subject_code}|${x.chapter_code}`)).size;
  const lessons = lessonGroups.reduce((n,x)=>n+x.lessons.length,0);
  const published = lessonGroups.reduce((n,x)=>n+x.lessons.filter(l=>l.status==='PUBLISHED').length,0);

  return <main className="admin-shell">
    <aside className="admin-sidebar"><Link href="/" className="admin-brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>ADMIN</small></span></Link><nav><span className="nav-label">WORKSPACE</span><a className="active" href="#top">▦ Dashboard</a><a href="#curriculum">◈ Curriculum</a><a href="#content">▤ Content</a><a href="#students">♙ Students</a><a href="#analytics">↗ Analytics</a><span className="nav-label">CONTROL</span><a href="#ai">✦ AI Review</a><a href="#settings">⚙ Settings</a></nav><div className="admin-user"><span className="avatar">M</span><div><strong>Local operator</strong><small>QuantaEdge</small></div></div></aside>
    <section className="admin-main" id="top"><header className="admin-top"><div><span className="admin-kicker">QUANTAEDGE LEARNING</span><h1>Curriculum operations</h1></div><div className="top-status"><span className="status-dot"/> API-backed data</div></header>
      <div className="admin-grid stats">
        <article><span>Classes</span><strong>{classCount}</strong><small>curriculum classes</small></article>
        <article><span>Subjects</span><strong>{subjectCount}</strong><small>class-subject mappings</small></article>
        <article><span>Chapters</span><strong>{chapters}</strong><small>mapped to curriculum</small></article>
        <article><span>Published lessons</span><strong>{published}</strong><small>{lessons} lessons loaded</small></article>
      </div>
      <div className="admin-grid main-panels">
        <article className="admin-panel wide" id="curriculum"><div className="panel-head"><div><span>CURRICULUM</span><h2>Published content</h2></div></div><div className="table"><div className="tr th"><span>Class</span><span>Subject</span><span>Chapters</span><span>Lessons</span><span>Status</span></div>{lessonGroups.map(row=><div className="tr" key={`${row.class_code}-${row.subject_code}`}><span>{row.class_name}</span><span>{row.subject_name}</span><strong>{new Set(curriculum.filter(x=>x.class_code===row.class_code&&x.subject_code===row.subject_code).map(x=>x.chapter_code)).size}</strong><span>{row.lessons.length}</span><span className="published">Published</span></div>)}</div></article>
        <article className="admin-panel" id="students"><div className="panel-head"><div><span>LOCAL PREVIEW</span><h2>Student fixture</h2></div></div><div className="activity"><div><b>1</b><span>controlled preview student</span></div><div><b>100%</b><span>published learning completed</span></div><div><b>100%</b><span>preview question accuracy</span></div></div></article>
        <article className="admin-panel" id="ai"><div className="panel-head"><div><span>AI REVIEW</span><h2>Gateway status</h2></div></div><div className="review-item"><span className="review-icon">✦</span><div><strong>Lesson-level help</strong><small>AI actions are represented in published lesson blocks; provider credentials remain external configuration.</small></div></div><div className="review-item"><span className="review-icon">✓</span><div><strong>Curriculum controlled</strong><small>AI does not own the curriculum source of truth.</small></div></div></article>
      </div>
      <footer className="admin-footer">QuantaEdge Admin · Curriculum is controlled data · Preview student is local-only</footer>
    </section>
  </main>;
}
