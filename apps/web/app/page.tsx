import Link from 'next/link';

const subjects = [
  { icon: '∑', name: 'गणित', meta: 'कक्षा 6–8 · हिन्दी', tone: 'violet' },
  { icon: '⚗', name: 'विज्ञान', meta: 'कक्षा 6–8 · हिन्दी', tone: 'blue' },
];

const promises = [
  ['01', 'पहले समझेंगे', 'हर lesson prerequisite, explanation और worked example से concept की नींव बनाता है।'],
  ['02', 'फिर अभ्यास', 'छोटे interactive सवाल, instant feedback और persistent learning records।'],
  ['03', 'फिर आगे बढ़ेंगे', 'Diagnostic, progress और concept mastery के आधार पर अगला कदम तय होता है।'],
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
          <a href="#how-it-works">कैसे काम करता है</a>
          <a href="#subjects">पढ़ाई</a>
          <a href="#parents">Parents</a>
        </nav>

        <div className="top-actions">
          <Link href="/login" className="text-link">Login</Link>
          <Link href="/login" className="button button-dark button-small">मुफ़्त में शुरू करें</Link>
        </div>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <div className="eyebrow"><span className="live-dot" /> कक्षा 6–8 · बिहार बोर्ड · हिन्दी में</div>
          <h1>पढ़ाई,<br /><em>बच्चे के हिसाब से।</em></h1>
          <p className="hero-lead">
            QuantaEdge बच्चे को सिर्फ answers नहीं देता। Diagnostic, practice और real learning records के आधार पर अगला learning step तय करता है।
          </p>

          <div className="hero-actions">
            <Link href="/login" className="button button-dark">अपनी पढ़ाई शुरू करें <span>→</span></Link>
            <Link href="/parent" className="button button-light">मैं Parent हूँ</Link>
          </div>

          <div className="trust-row">
            <span>✓ कोई public ranking नहीं</span>
            <span>✓ सीखने पर focus</span>
            <span>✓ Parent visibility</span>
          </div>
        </div>

        <div className="hero-device" aria-label="QuantaEdge learning flow preview">
          <div className="device-glow" />
          <div className="student-card">
            <div className="student-head">
              <div><span className="muted-small">Learning journey</span><h3>आपके बच्चे के लिए</h3></div>
              <div className="avatar">Q</div>
            </div>

            <div className="mission-card">
              <div>
                <span className="mission-label">आज की पढ़ाई</span>
                <strong>एक concept · एक सही अगला कदम</strong>
                <div className="progress-track"><span style={{ width: '58%' }} /></div>
                <small>Progress answers के साथ save होती है</small>
              </div>
              <span className="streak">LIVE</span>
            </div>

            <div className="section-title"><strong>Learning flow</strong><span>adaptive</span></div>

            <div className="task done">
              <span className="task-icon">↻</span>
              <div><strong>समझें</strong><small>Prerequisite · explanation · worked example</small></div>
              <span className="check">✓</span>
            </div>

            <div className="task active">
              <span className="task-icon">∑</span>
              <div><strong>अभ्यास करें</strong><small>Interactive questions · instant feedback</small></div>
              <span className="arrow">→</span>
            </div>

            <div className="task">
              <span className="task-icon">✦</span>
              <div><strong>Mastery बढ़ाएँ</strong><small>Concept mastery के आधार पर next step</small></div>
              <span className="arrow">→</span>
            </div>

            <Link href="/login" className="device-cta">अपनी learning journey शुरू करें →</Link>
          </div>
        </div>
      </section>

      <section className="strip">
        <span>एक learning system जो हर बच्चे के लिए अगला सही कदम चुनता है</span>
        <div><b>DIAGNOSE</b><i>→</i><b>LEARN</b><i>→</i><b>PRACTICE</b><i>→</i><b>MASTER</b></div>
      </section>

      <section id="how-it-works" className="section">
        <div className="section-intro">
          <span className="eyebrow">हमारा learning loop</span>
          <h2>सिर्फ content नहीं।<br /><em>एक सही अगला कदम।</em></h2>
          <p>Progress, question attempts, sessions और concept mastery एक ही learning state में persist होते हैं और recommendation को drive करते हैं।</p>
        </div>

        <div className="promise-grid">
          {promises.map(([n, title, description]) => (
            <article className="promise" key={n}>
              <span>{n}</span>
              <h3>{title}</h3>
              <p>{description}</p>
            </article>
          ))}
        </div>
      </section>

      <section id="subjects" className="section section-soft">
        <div className="section-heading">
          <div><span className="eyebrow">अभी से शुरू करें</span><h2>आपकी पढ़ाई का पहला कदम</h2></div>
          <Link href="/login" className="text-link">शुरू करें →</Link>
        </div>

        <div className="subject-grid">
          {subjects.map(subject => (
            <Link href="/login" className="subject-card" key={subject.name}>
              <div className={`subject-icon ${subject.tone}`}>{subject.icon}</div>
              <div><span>Class 6–8</span><h3>{subject.name}</h3><p>{subject.meta}</p></div>
              <span className="card-arrow">↗</span>
            </Link>
          ))}
        </div>
      </section>

      <section id="parents" className="parent-section">
        <div className="parent-copy">
          <span className="eyebrow">Parents के लिए</span>
          <h2>बच्चा कितना पढ़ा नहीं —<br /><em>क्या सीखा, वह दिखे।</em></h2>
          <p>Linked child के वास्तविक lessons, attempts, sessions, completion और concept mastery का simple summary।</p>
          <Link href="/parent" className="button button-dark">Parent view खोलें →</Link>
        </div>

        <div className="report-card">
          <div className="report-top">
            <div><span>Real learning records</span><strong>Parent report</strong></div>
            <span className="up">LIVE</span>
          </div>
          <div className="report-stat">
            <strong>Progress</strong>
            <span>Lessons · attempts · mastery</span>
            <div className="mini-bars">
              <i style={{ height: '30%' }} /><i style={{ height: '44%' }} /><i style={{ height: '54%' }} />
              <i style={{ height: '61%' }} /><i style={{ height: '72%' }} /><i style={{ height: '81%' }} />
              <i style={{ height: '88%' }} />
            </div>
          </div>
          <div className="report-bottom"><span>Strengths: mastery</span><span>Support: weak concepts</span></div>
        </div>
      </section>

      <footer className="footer">
        <div className="brand"><span className="brand-mark">Q</span><span><strong>Quanta</strong>Edge<small>LEARNING</small></span></div>
        <p>पढ़ाई, अब आपके बच्चे के हिसाब से।</p>
        <div className="footer-links"><Link href="/privacy">Privacy</Link><Link href="/terms">Terms</Link></div>
        <span>© 2026 QuantaEdge Learning</span>
      </footer>
    </main>
  );
}
