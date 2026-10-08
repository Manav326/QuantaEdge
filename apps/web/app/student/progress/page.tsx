import Link from 'next/link';

export default function ProgressPage() {
  return <main className="app-shell">
    <header className="app-header"><Link href="/" className="brand compact"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></Link><Link href="/student" className="back">← आज</Link></header>
    <section className="progress-page"><div><span className="eyebrow">तुम्हारी progress</span><h1>जो सीखा, वह दिख रहा है।</h1><p>Rank नहीं — तुम्हारी अपनी growth.</p></div>
      <div className="progress-grid"><article><span>इस सप्ताह</span><strong>1h 48m</strong><small>learning time</small></article><article><span>Practice</span><strong>82%</strong><small>average accuracy</small></article><article><span>Mastery</span><strong>14</strong><small>concepts strong</small></article></div>
      <div className="mastery-card"><div><span>Concept mastery</span><strong>Maths · Class 7</strong></div><div className="mastery-row"><span>Fractions</span><i><b style={{width:'92%'}}/></i><strong>92%</strong></div><div className="mastery-row"><span>Algebra</span><i><b style={{width:'68%'}}/></i><strong>68%</strong></div><div className="mastery-row"><span>Integers</span><i><b style={{width:'81%'}}/></i><strong>81%</strong></div><div className="mastery-row"><span>Geometry</span><i><b style={{width:'44%'}}/></i><strong>44%</strong></div></div>
      <Link href="/student/learn" className="button button-dark">अगला recommended lesson →</Link>
    </section>
  </main>;
}