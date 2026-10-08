import Link from 'next/link';

const subjects = [
  { icon: '∑', name: 'गणित', meta: '12 chapters', tone: 'violet' },
  { icon: '⚗', name: 'विज्ञान', meta: '10 chapters', tone: 'blue' },
];

const promises = [
  ['01', 'पहले समझेंगे', 'हर lesson बच्चे के current level से शुरू होता है।'],
  ['02', 'फिर अभ्यास', 'छोटे, interactive सवाल — तुरंत feedback के साथ।'],
  ['03', 'फिर आगे बढ़ेंगे', 'Mastery के बाद ही अगला concept recommend होगा।'],
];

export default function Home() {
  return (
    <main className="site">
      <header className="topbar">
        <Link href="/" className="brand" aria-label="QuantaEdge home">
          <span className="brand-mark">Q</span>
          <span><strong>Quanta</strong>Edge<small>LEARNING</small></span>
        </Link>
        <nav className="topnav" aria-label="मुख्य navigation">
          <a href="#how-it-works">कैसे काम करता है</a><a href="#subjects">पढ़ाई</a><a href="#parents">Parents</a>
        </nav>
        <div className="top-actions"><Link href="/login" className="text-link">Login</Link><Link href="/student" className="button button-dark button-small">मुफ़्त में शुरू करें</Link></div>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <div className="eyebrow"><span className="live-dot" /> कक्षा 6–8 · बिहार बोर्ड · हिन्दी में</div>
          <h1>पढ़ाई,<br /><em>बच्चे के हिसाब से।</em></h1>
          <p className="hero-lead">QuantaEdge बच्चे को सिर्फ answers नहीं देता। पहले समझता है कि कहाँ अटक रहा है, फिर उसी हिसाब से सिखाता और अभ्यास कराता है।</p>
          <div className="hero-actions"><Link href="/student" className="button button-dark">अपनी पढ़ाई शुरू करें <span>→</span></Link><Link href="/parent" className="button button-light">मैं Parent हूँ</Link></div>
          <div className="trust-row"><span>✓ कोई public ranking नहीं</span><span>✓ सीखने पर focus</span><span>✓ Parent visibility</span></div>
        </div>
        <div className="hero-device">
          <div className="device-glow" />
          <div className="student-card">
            <div className="student-head"><div><span className="muted-small">नमस्ते,</span><h3>आर्यन 👋</h3></div><div className="avatar">अ</div></div>
            <div className="mission-card"><div><span className="mission-label">आज का लक्ष्य</span><strong>25 min सीखना</strong><div className="progress-track"><span style={{width:'64%'}} /></div><small>16 / 25 min</small></div><span className="streak">🔥 4 दिन</span></div>
            <div className="section-title"><strong>आज की पढ़ाई</strong><span>3 tasks</span></div>
            <div className="task done"><span className="task-icon">↻</span><div><strong>Fractions · Revision</strong><small>5 min · पूरा हुआ</small></div><span className="check">✓</span></div>
            <div className="task active"><span className="task-icon">∑</span><div><strong>Algebra · नया lesson</strong><small>12 min · Maths</small></div><span className="arrow">→</span></div>
            <div className="task"><span className="task-icon">✦</span><div><strong>Practice</strong><small>10 questions · 8 min</small></div><span className="arrow">→</span></div>
            <Link href="/student/learn" className="device-cta">आज की पढ़ाई शुरू करें →</Link>
          </div>
        </div>
      </section>

      <section className="strip"><span>एक tutor जो हर बच्चे के लिए थोड़ा अलग पढ़ाता है</span><div><b>DIAGNOSE</b><i>→</i><b>LEARN</b><i>→</i><b>PRACTICE</b><i>→</i><b>MASTER</b></div></section>

      <section id="how-it-works" className="section">
        <div className="section-intro"><span className="eyebrow">हमारा learning loop</span><h2>सिर्फ content नहीं।<br /><em>एक सही अगला कदम।</em></h2><p>QuantaEdge हर session में बच्चे के answers और progress को देखकर अगला concept चुनता है।</p></div>
        <div className="promise-grid">{promises.map(([n,t,d]) => <article className="promise" key={n}><span>{n}</span><h3>{t}</h3><p>{d}</p></article>)}</div>
      </section>

      <section id="subjects" className="section section-soft">
        <div className="section-heading"><div><span className="eyebrow">अभी से शुरू करें</span><h2>आपकी पढ़ाई का पहला कदम</h2></div><Link href="/student" className="text-link">सभी देखें →</Link></div>
        <div className="subject-grid">{subjects.map(s => <Link href="/student" className="subject-card" key={s.name}><div className={`subject-icon ${s.tone}`}>{s.icon}</div><div><span>Class 6–8</span><h3>{s.name}</h3><p>{s.meta} · हिन्दी में</p></div><span className="card-arrow">↗</span></Link>)}</div>
      </section>

      <section id="parents" className="parent-section"><div className="parent-copy"><span className="eyebrow">Parents के लिए</span><h2>बच्चा कितना पढ़ा नहीं —<br /><em>क्या सीखा, वह दिखे।</em></h2><p>हर हफ्ते simple progress summary: study time, accuracy, मजबूत topics और जहाँ थोड़ी मदद चाहिए।</p><Link href="/parent" className="button button-dark">Parent view देखें →</Link></div><div className="report-card"><div className="report-top"><div><span>इस सप्ताह</span><strong>आर्यन की Progress</strong></div><span className="up">↑ 18%</span></div><div className="report-stat"><strong>82%</strong><span>Practice accuracy</span><div className="mini-bars"><i style={{height:'42%'}}/><i style={{height:'58%'}}/><i style={{height:'50%'}}/><i style={{height:'76%'}}/><i style={{height:'82%'}}/><i style={{height:'68%'}}/><i style={{height:'92%'}}/></div></div><div className="report-bottom"><span>मजबूत: Fractions</span><span>Focus: Algebra</span></div></div></section>

      <footer className="footer"><div className="brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></div><p>पढ़ाई, अब आपके बच्चे के हिसाब से।</p><span>© 2026 QuantaEdge Learning</span></footer>
    </main>
  );
}
