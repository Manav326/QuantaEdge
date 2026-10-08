import Link from 'next/link';

export default function ParentPage() {
  return <main className="parent-app"><header className="parent-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><span>Parent view · आर्यन</span></header>
    <section className="parent-dashboard"><div><span className="eyebrow">Weekly learning summary</span><h1>आर्यन इस हफ्ते कैसा कर रहा है?</h1><p>एक नज़र में वह क्या सीख रहा है और कहाँ थोड़ी मदद चाहिए।</p></div>
      <div className="parent-stats"><article><span>Study time</span><strong>1h 48m</strong><small>↑ 22 min vs last week</small></article><article><span>Accuracy</span><strong>82%</strong><small>↑ 8% improvement</small></article><article><span>Streak</span><strong>4 days 🔥</strong><small>best: 7 days</small></article></div>
      <div className="parent-grid"><article className="report-panel"><div className="panel-title"><strong>क्या अच्छा चल रहा है</strong><span>✓</span></div><div className="topic-line"><b>Fractions</b><span className="good">Strong · 92%</span></div><div className="topic-line"><b>Integers</b><span className="good">Good · 81%</span></div></article><article className="report-panel"><div className="panel-title"><strong>थोड़ा focus चाहिए</strong><span>→</span></div><div className="topic-line"><b>Algebra</b><span className="focus">Practice · 68%</span></div><div className="topic-line"><b>Geometry</b><span className="focus">Revision · 44%</span></div></article></div>
      <div className="parent-note"><span>💡</span><div><strong>इस हफ्ते का सुझाव</strong><p>रविवार को 15 मिनट Geometry revision के लिए encourage करें। बाकी learning QuantaEdge अपने आप schedule कर रहा है।</p></div></div>
      <Link href="/" className="text-link">← QuantaEdge home</Link>
    </section>
  </main>;
}