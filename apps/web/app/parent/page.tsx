import Link from 'next/link';

export const dynamic = 'force-dynamic';

async function getPreview() {
  const base = process.env.BACKEND_INTERNAL_URL ?? 'http://localhost:8080';
  const response = await fetch(`${base}/api/v1/students/preview`, { cache: 'no-store' });
  if (!response.ok) throw new Error('Preview student unavailable');
  return response.json();
}

export default async function ParentPage() {
  const data = await getPreview();
  const stats = data.lessonStats;
  const q = data.questionStats;
  return <main className="parent-app"><header className="parent-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><span>Parent view · {data.display_name}</span></header>
    <section className="parent-dashboard"><div><span className="eyebrow">Learning summary</span><h1>{data.display_name} ने क्या सीखा?</h1><p>सारांश वही दिखाता है जो QuantaEdge के curriculum और learning records में मौजूद है।</p></div>
      <div className="parent-stats"><article><span>Lessons complete</span><strong>{stats.completed_lessons}</strong><small>of {stats.total_lessons} published lessons</small></article><article><span>Accuracy</span><strong>{q.accuracy_percent}%</strong><small>{q.attempts} answered questions</small></article><article><span>Curriculum</span><strong>{stats.completion_percent}%</strong><small>completion</small></article></div>
      <div className="parent-grid"><article className="report-panel"><div className="panel-title"><strong>Curriculum covered</strong><span>✓</span></div>{data.curriculum.map((item:any)=><div className="topic-line" key={item.subject_code}><b>{item.subject_name}</b><span className="good">{item.completed}/{item.lessons} lessons</span></div>)}</article><article className="report-panel"><div className="panel-title"><strong>What this means</strong><span>→</span></div><div className="topic-line"><b>Learning content</b><span className="good">Published</span></div><div className="topic-line"><b>Practice feedback</b><span className="good">Enabled</span></div><div className="topic-line"><b>AI help</b><span className="good">Lesson-level</span></div></article></div>
      <div className="parent-note"><span>💡</span><div><strong>Important</strong><p>यह local preview account है। Production में preview seed बंद है; वास्तविक parent consent और authentication के बिना child data create नहीं होगा.</p></div></div>
      <Link href="/" className="text-link">← QuantaEdge home</Link>
    </section>
  </main>;
}
