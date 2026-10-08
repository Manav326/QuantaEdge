import Link from 'next/link';

export const dynamic = 'force-dynamic';

async function getPreview() {
  const base = process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080';
  const response = await fetch(`${base}/api/v1/students/preview`, { cache: 'no-store' });
  if (!response.ok) throw new Error('Preview student unavailable');
  return response.json();
}

export default async function ProgressPage() {
  const data = await getPreview();
  const stats = data.lessonStats;
  const q = data.questionStats;
  return <main className="app-shell">
    <header className="app-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><Link href="/student" className="back">← आज</Link></header>
    <section className="progress-page"><div><span className="eyebrow">तुम्हारी progress</span><h1>जो सीखा, वह सच में दिख रहा है।</h1><p>यह screen hardcoded demo numbers नहीं दिखाती; सब कुछ curriculum और attempts से आता है।</p></div>
      <div className="progress-grid"><article><span>Lessons</span><strong>{stats.completed_lessons}/{stats.total_lessons}</strong><small>completed</small></article><article><span>Practice</span><strong>{q.accuracy_percent}%</strong><small>{q.attempts} attempts</small></article><article><span>Curriculum</span><strong>{stats.completion_percent}%</strong><small>published lessons complete</small></article></div>
      <div className="mastery-card"><div><span>Curriculum coverage</span><strong>Class {data.class_code} · Maths + Science</strong></div>
        {data.curriculum.map((item:any)=><div className="mastery-row" key={item.subject_code}><span>{item.subject_name}</span><i><b style={{width:`${item.lessons ? Math.round(item.completed*100/item.lessons) : 0}%`}}/></i><strong>{item.lessons ? Math.round(item.completed*100/item.lessons) : 0}%</strong></div>)}
      </div>
      <Link href="/student/learn" className="button button-dark">Recommended lesson खोलें →</Link>
    </section>
  </main>;
}
