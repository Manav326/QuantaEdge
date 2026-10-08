import Link from 'next/link';

export const dynamic = 'force-dynamic';

async function getPreview() {
  const base = process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080';
  const response = await fetch(`${base}/api/v1/students/preview`, { cache: 'no-store' });
  if (!response.ok) throw new Error('Preview student unavailable');
  return response.json();
}

export default async function StudentHome() {
  const data = await getPreview();
  const stats = data.lessonStats as { total_lessons:number; completed_lessons:number; completion_percent:number };
  const q = data.questionStats as { attempts:number; accuracy_percent:number };
  return <main className="app-shell">
    <header className="app-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><div className="student-profile"><span>{data.display_name}</span><span className="avatar">अ</span></div></header>
    <div className="app-layout">
      <aside className="side-nav"><Link className="side-active" href="/student">⌂ <span>आज</span></Link><Link href="/student/learn">▣ <span>पढ़ाई</span></Link><Link href="/student/practice">✦ <span>अभ्यास</span></Link><Link href="/student/progress">↗ <span>मेरी प्रगति</span></Link></aside>
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow">Local product preview · Class {data.class_code}</span><h1>नमस्ते, {data.display_name} 👋</h1><p>पूरा learning journey एक बार end-to-end पूरा किया जा चुका है।</p></div><div className="streak-card">✓ <strong>100%</strong><span>curriculum complete</span></div></div>
        <div className="goal-card"><div><span>Learning journey</span><strong>{stats.completed_lessons} / {stats.total_lessons} lessons</strong><small>{q.attempts} practice attempts · {q.accuracy_percent}% accuracy</small></div><div className="goal-ring">{Math.round(Number(stats.completion_percent))}%</div></div>
        <div className="content-heading"><h2>हर feature आज़माएँ</h2><span>real DB content</span></div>
        <div className="task-list">
          <Link href="/student/learn" className="app-task next"><span className="task-icon">∑</span><div><strong>Interactive lesson</strong><small>Explanation · challenge · AI help · summary</small></div><span className="task-action">→</span></Link>
          <Link href="/student/practice" className="app-task"><span className="task-icon">✦</span><div><strong>Practice</strong><small>Curriculum questions with instant feedback</small></div><span className="task-action">→</span></Link>
          <Link href="/student/progress" className="app-task"><span className="task-icon">↗</span><div><strong>मेरी प्रगति</strong><small>Completion and question accuracy from the database</small></div><span className="task-action">→</span></Link>
          <Link href="/parent" className="app-task"><span className="task-icon">◉</span><div><strong>Parent view</strong><small>Same student data, parent-friendly summary</small></div><span className="task-action">→</span></Link>
        </div>
        <div className="help-card"><div className="help-bubble">✓</div><div><strong>Preview data is intentionally local-only</strong><p>Production compose disables the preview seed, so no synthetic student enters production.</p></div></div>
      </section>
    </div>
    <nav className="mobile-nav"><Link className="side-active" href="/student">⌂<span>आज</span></Link><Link href="/student/learn">▣<span>पढ़ाई</span></Link><Link href="/student/practice">✦<span>अभ्यास</span></Link><Link href="/student/progress">↗<span>प्रगति</span></Link></nav>
  </main>;
}
