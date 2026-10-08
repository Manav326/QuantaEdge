import Link from 'next/link';

const tasks = [
  { icon: '↻', title: 'Fractions · Revision', meta: '5 min · कल के concept को मजबूत करें', state: 'done' },
  { icon: '∑', title: 'Algebra · नया lesson', meta: '12 min · Maths', state: 'next' },
  { icon: '✦', title: 'Practice', meta: '10 questions · 8 min', state: 'open' },
];

export default function StudentHome() {
  return <main className="app-shell">
    <header className="app-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><div className="student-profile"><span>आर्यन</span><span className="avatar">अ</span></div></header>
    <div className="app-layout">
      <aside className="side-nav"><Link className="side-active" href="/student">⌂ <span>आज</span></Link><Link href="/student/learn">▣ <span>पढ़ाई</span></Link><Link href="/student/practice">✦ <span>अभ्यास</span></Link><Link href="/student/progress">↗ <span>मेरी प्रगति</span></Link></aside>
      <section className="app-content">
        <div className="welcome-row"><div><span className="eyebrow">Tuesday · 8 October</span><h1>नमस्ते, आर्यन 👋</h1><p>आज बस अगला सही कदम उठाते हैं।</p></div><div className="streak-card">🔥 <strong>4</strong><span>दिन की streak</span></div></div>
        <div className="goal-card"><div><span>आज का learning goal</span><strong>25 मिनट</strong><small>16 मिनट पूरे · बहुत अच्छा जा रहा है!</small></div><div className="goal-ring">64%</div></div>
        <div className="content-heading"><h2>आज की पढ़ाई</h2><span>3 tasks</span></div>
        <div className="task-list">{tasks.map(task=><Link href={task.state==='next'?'/student/learn':'/student/practice'} className={`app-task ${task.state}`} key={task.title}><span className="task-icon">{task.icon}</span><div><strong>{task.title}</strong><small>{task.meta}</small></div><span className="task-action">{task.state==='done'?'✓':'→'}</span></Link>)}</div>
        <div className="help-card"><div className="help-bubble">?</div><div><strong>कुछ समझ नहीं आया?</strong><p>Lesson के अंदर कभी भी “आसान भाषा में समझाओ” चुनें।</p></div><Link href="/student/learn">देखें →</Link></div>
      </section>
    </div>
    <nav className="mobile-nav"><Link className="side-active" href="/student">⌂<span>आज</span></Link><Link href="/student/learn">▣<span>पढ़ाई</span></Link><Link href="/student/practice">✦<span>अभ्यास</span></Link><Link href="/student/progress">↗<span>प्रगति</span></Link></nav>
  </main>;
}