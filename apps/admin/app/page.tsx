import Link from 'next/link';

const curriculum = [
  ['Class 7', 'गणित', 'Algebra', 'Published', '92%'],
  ['Class 7', 'गणित', 'Fractions', 'Published', '100%'],
  ['Class 6', 'विज्ञान', 'Components of Food', 'Review', '76%'],
  ['Class 8', 'विज्ञान', 'Force & Pressure', 'Draft', '41%'],
];

export default function AdminHome() {
  return <main className="admin-shell">
    <aside className="admin-sidebar"><Link href="/" className="admin-brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>ADMIN</small></span></Link><nav><span className="nav-label">WORKSPACE</span><a className="active" href="#">▦ Dashboard</a><a href="#curriculum">◈ Curriculum</a><a href="#content">▤ Content</a><a href="#students">♙ Students</a><a href="#analytics">↗ Analytics</a><span className="nav-label">CONTROL</span><a href="#ai">✦ AI Review</a><a href="#settings">⚙ Settings</a></nav><div className="admin-user"><span className="avatar">M</span><div><strong>Admin</strong><small>QuantaEdge</small></div></div></aside>
    <section className="admin-main"><header className="admin-top"><div><span className="admin-kicker">QUANTAEDGE LEARNING</span><h1>Good evening, Admin.</h1></div><div className="top-status"><span className="status-dot"/> All systems healthy</div></header>
      <div className="admin-grid stats"><article><span>Active students</span><strong>248</strong><small>+18 this week</small></article><article><span>Learning sessions</span><strong>1,426</strong><small>+12.4% vs last week</small></article><article><span>Questions answered</span><strong>8,904</strong><small>82% average accuracy</small></article><article><span>AI help requests</span><strong>317</strong><small>94% resolved in lesson</small></article></div>
      <div className="admin-grid main-panels"><article className="admin-panel wide" id="curriculum"><div className="panel-head"><div><span>CURRICULUM</span><h2>Content health</h2></div><button>+ New content</button></div><div className="table"><div className="tr th"><span>Class</span><span>Subject</span><span>Concept</span><span>Status</span><span>Ready</span></div>{curriculum.map(row=><div className="tr" key={row.join('-')}><span>{row[0]}</span><span>{row[1]}</span><strong>{row[2]}</strong><span className={row[3].toLowerCase()}>{row[3]}</span><span>{row[4]}</span></div>)}</div></article>
        <article className="admin-panel" id="students"><div className="panel-head"><div><span>LEARNING</span><h2>Today</h2></div></div><div className="activity"><div><b>82%</b><span>students completed today&apos;s goal</span></div><div><b>64%</b><span>started their recommended lesson</span></div><div><b>18</b><span>students need revision support</span></div></div></article>
        <article className="admin-panel" id="ai"><div className="panel-head"><div><span>AI REVIEW</span><h2>Needs attention</h2></div><span className="count">7</span></div><div className="review-item"><span className="review-icon">?</span><div><strong>Algebra · Class 7</strong><small>3 AI help requests on same concept</small></div><a href="#">Review →</a></div><div className="review-item"><span className="review-icon">!</span><div><strong>Force &amp; Pressure · Class 8</strong><small>Lesson draft has 2 unanswered QA flags</small></div><a href="#">Review →</a></div></article>
      </div>
      <footer className="admin-footer">QuantaEdge Admin · Curriculum is controlled data · AI remains behind the learning gateway</footer>
    </section>
  </main>;
}