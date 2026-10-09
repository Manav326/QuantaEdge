import Link from 'next/link';
import QuantaEdgeBrand from './components/QuantaEdgeBrand';
import LandingAccountMenu from './components/LandingAccountMenu';

const subjects = [
  { icon: '∑', name: 'गणित', meta: 'Class 6–8 · Hindi medium', tone: 'violet' },
  { icon: '⚗', name: 'विज्ञान', meta: 'Class 6–8 · Hindi medium', tone: 'blue' },
];

const promises = [
  ['01', 'Pehle concept samjhenge', 'Har lesson mein prerequisite, simple explanation aur worked example se concept clear hota hai.'],
  ['02', 'Phir practice karenge', 'Short interactive questions, instant feedback aur saved learning progress.'],
  ['03', 'Phir next step lenge', 'Diagnostic, progress aur concept mastery ke basis par next step decide hota hai.'],
];

export default function Home() {
  return (
    <main className="site">
      <header className="topbar">
        <QuantaEdgeBrand variant="header" />

        <nav className="topnav" aria-label="Main navigation">
          <a href="#how-it-works">Kaise kaam karta hai</a>
          <a href="#subjects">Padhai</a>
          <a href="#parents">Parents</a>
        </nav>

        <div className="top-actions">
          <LandingAccountMenu />
        </div>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <div className="eyebrow"><span className="live-dot" /> Class 6–8 · Bihar Board · Hindi medium</div>
          <h1>Padhai,<br /><em>bachche ke hisaab se.</em></h1>
          <p className="hero-lead">
            QuantaEdge sirf answers nahi deta. Diagnostic, practice aur real learning records se bachche ka next learning step decide hota hai.
          </p>

          <div className="hero-actions">
            <Link href="/login/student" className="button button-dark">Apni padhai shuru karein <span>→</span></Link>
            <Link href="/login" className="button button-light">Main parent hoon</Link>
          </div>

          <div className="trust-row">
            <span>✓ Koi public ranking nahi</span>
            <span>✓ Learning par focus</span>
            <span>✓ Parent ko progress dikhegi</span>
          </div>
        </div>

        <div className="hero-device" aria-label="QuantaEdge learning flow preview">
          <div className="device-glow" />
          <div className="student-card">
            <div className="student-head">
              <div><span className="muted-small">Learning journey</span><h3>Aapke bachche ke liye</h3></div>
              <div className="avatar">Q</div>
            </div>

            <div className="mission-card">
              <div>
                <span className="mission-label">Aaj ki padhai</span>
                <strong>Ek concept · ek sahi next step</strong>
                <div className="progress-track"><span style={{ width: '58%' }} /></div>
                <small>Answers ke saath progress save hoti hai</small>
              </div>
              <span className="streak">LIVE</span>
            </div>

            <div className="section-title"><strong>Learning flow</strong><span>adaptive</span></div>

            <div className="task done">
              <span className="task-icon">↻</span>
              <div><strong>Samjhein</strong><small>Prerequisite · explanation · worked example</small></div>
              <span className="check">✓</span>
            </div>

            <div className="task active">
              <span className="task-icon">∑</span>
              <div><strong>Practice karein</strong><small>Interactive questions · instant feedback</small></div>
              <span className="arrow">→</span>
            </div>

            <div className="task">
              <span className="task-icon">✦</span>
              <div><strong>Mastery badhayein</strong><small>Concept mastery ke basis par next step</small></div>
              <span className="arrow">→</span>
            </div>

            <Link href="/login/student" className="device-cta">Apni learning journey shuru karein →</Link>
          </div>
        </div>
      </section>

      <section className="strip">
        <span>Ek learning system jo har bachche ke liye sahi next step choose karta hai</span>
        <div><b>DIAGNOSE</b><i>→</i><b>LEARN</b><i>→</i><b>PRACTICE</b><i>→</i><b>MASTER</b></div>
      </section>

      <section id="how-it-works" className="section">
        <div className="section-intro">
          <span className="eyebrow">Hamara learning loop</span>
          <h2>Sirf content nahi.<br /><em>Har baar ek sahi next step.</em></h2>
          <p>Progress, question attempts, sessions aur concept mastery save rehte hain aur next recommendation decide karne mein help karte hain.</p>
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
          <div><span className="eyebrow">Abhi shuru karein</span><h2>Aapki padhai ka pehla step</h2></div>
          <Link href="/login/student" className="text-link">Start karein →</Link>
        </div>

        <div className="subject-grid">
          {subjects.map(subject => (
            <Link href="/login/student" className="subject-card" key={subject.name}>
              <div className={`subject-icon ${subject.tone}`}>{subject.icon}</div>
              <div><span>Class 6–8</span><h3>{subject.name}</h3><p>{subject.meta}</p></div>
              <span className="card-arrow">↗</span>
            </Link>
          ))}
        </div>
      </section>

      <section id="parents" className="parent-section">
        <div className="parent-copy">
          <span className="eyebrow">Parents ke liye</span>
          <h2>Sirf kitna padha nahi —<br /><em>kya seekha, woh dekhein.</em></h2>
          <p>Aapke bachche ke actual lessons, attempts, sessions, completion aur concept mastery ka simple summary.</p>
          <Link href="/login" className="button button-dark">Parent view open karein →</Link>
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
        <QuantaEdgeBrand href={null} variant="footer" />
        <p>Padhai, ab aapke bachche ke hisaab se.</p>
        <div className="footer-links"><Link href="/privacy">Privacy</Link><Link href="/terms">Terms</Link></div>
        <span>© 2026 QuantaEdge Learning</span>
      </footer>
    </main>
  );
}
