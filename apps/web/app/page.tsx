import { LanguageSwitcher, LocaleText } from './components/LanguageProvider';
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
          <a href="#how-it-works"><LocaleText hinglish="कैसे काम करता है" english="How it works" /></a>
          <a href="#subjects"><LocaleText hinglish="पढ़ाई" english="Learning" /></a>
          <a href="#parents"><LocaleText hinglish="Parents" english="Parents" /></a>
        </nav>

        <div className="top-actions"><LanguageSwitcher compact />
          <LandingAccountMenu />
        </div>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <div className="eyebrow"><span className="live-dot" /> <LocaleText hinglish="कक्षा 6–8 · Bihar Board · हिन्दी माध्यम" english="Classes 6–8 · Bihar Board · Hindi medium" /></div>
          <h1><LocaleText hinglish="पढ़ाई," english="Learning," /><br /><em><LocaleText hinglish="बच्चे के हिसाब से।" english="adapted to each child." /></em></h1>
          <p className="hero-lead">
            QuantaEdge sirf answers nahi deta. Diagnostic, practice aur real learning records se bachche ka next learning step decide hota hai.
          </p>

          <div className="hero-actions">
            <Link href="/login/student" className="button button-dark"><LocaleText hinglish="अपनी पढ़ाई शुरू करें" english="Start learning" /> <span>→</span></Link>
            <Link href="/login" className="button button-light"><LocaleText hinglish="मैं Parent हूँ" english="I'm a parent" /></Link>
          </div>

          <div className="trust-row">
            <span><LocaleText hinglish="✓ कोई public ranking नहीं" english="✓ No public rankings" /></span>
            <span><LocaleText hinglish="✓ Learning पर focus" english="✓ Focus on learning" /></span>
            <span><LocaleText hinglish="✓ Parent को progress दिखेगी" english="✓ Parents can see progress" /></span>
          </div>
        </div>

        <div className="hero-device" aria-label="QuantaEdge learning flow preview">
          <div className="device-glow" />
          <div className="student-card">
            <div className="student-head">
              <div><span className="muted-small">Learning journey</span><h3><LocaleText hinglish="आपके बच्चे के लिए" english="For your child" /></h3></div>
              <div className="avatar">Q</div>
            </div>

            <div className="mission-card">
              <div>
                <span className="mission-label"><LocaleText hinglish="आज की पढ़ाई" english="Today's learning" /></span>
                <strong><LocaleText hinglish="एक concept · अगला सही step" english="One concept · one right next step" /></strong>
                <div className="progress-track"><span style={{ width: '58%' }} /></div>
                <small><LocaleText hinglish="Answers के साथ progress save होती है" english="Progress saves as you answer" /></small>
              </div>
              <span className="streak">LIVE</span>
            </div>

            <div className="section-title"><strong>Learning flow</strong><span>adaptive</span></div>

            <div className="task done">
              <span className="task-icon">↻</span>
              <div><strong><LocaleText hinglish="समझें" english="Learn" /></strong><small>Prerequisite · explanation · worked example</small></div>
              <span className="check">✓</span>
            </div>

            <div className="task active">
              <span className="task-icon">∑</span>
              <div><strong><LocaleText hinglish="Practice करें" english="Practise" /></strong><small>Interactive questions · instant feedback</small></div>
              <span className="arrow">→</span>
            </div>

            <div className="task">
              <span className="task-icon">✦</span>
              <div><strong><LocaleText hinglish="Mastery बढ़ाएँ" english="Build mastery" /></strong><small><LocaleText hinglish="Concept mastery के आधार पर अगला step" english="Next step based on concept mastery" /></small></div>
              <span className="arrow">→</span>
            </div>

            <Link href="/login/student" className="device-cta"><LocaleText hinglish="अपनी learning journey शुरू करें →" english="Start your learning journey →" /></Link>
          </div>
        </div>
      </section>

      <section className="strip">
        <span><LocaleText hinglish="एक learning system जो हर बच्चे के लिए सही अगला step चुनता है" english="A learning system that chooses the right next step for every child" /></span>
        <div><b>DIAGNOSE</b><i>→</i><b>LEARN</b><i>→</i><b>PRACTICE</b><i>→</i><b>MASTER</b></div>
      </section>

      <section id="how-it-works" className="section">
        <div className="section-intro">
          <span className="eyebrow"><LocaleText hinglish="हमारा learning loop" english="Our learning loop" /></span>
          <h2><LocaleText hinglish="सिर्फ content नहीं।" english="More than content." /><br /><em><LocaleText hinglish="हर बार एक सही next step।" english="The right next step, every time." /></em></h2>
          <p><LocaleText hinglish="Progress, question attempts, sessions और concept mastery save रहते हैं और अगली recommendation तय करने में मदद करते हैं।" english="Progress, question attempts, sessions, and concept mastery inform the next recommendation." /></p>
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
          <div><span className="eyebrow"><LocaleText hinglish="अभी शुरू करें" english="Start now" /></span><h2><LocaleText hinglish="आपकी पढ़ाई का पहला step" english="Your first step in learning" /></h2></div>
          <Link href="/login/student" className="text-link"><LocaleText hinglish="शुरू करें →" english="Get started →" /></Link>
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
          <span className="eyebrow"><LocaleText hinglish="Parents के लिए" english="For parents" /></span>
          <h2><LocaleText hinglish="सिर्फ कितना पढ़ा नहीं —" english="Not just how much they studied—" /><br /><em><LocaleText hinglish="क्या सीखा, वह देखें।" english="see what they learned." /></em></h2>
          <p><LocaleText hinglish="आपके बच्चे के actual lessons, attempts, sessions, completion और concept mastery का simple summary।" english="A simple summary of your child’s actual lessons, attempts, sessions, completion, and concept mastery." /></p>
          <Link href="/login" className="button button-dark"><LocaleText hinglish="Parent view खोलें →" english="Open parent view →" /></Link>
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
          <div className="report-bottom"><span>Strengths: mastery</span><span><LocaleText hinglish="Support: जिन concepts में मदद चाहिए" english="Support: concepts needing help" /></span></div>
        </div>
      </section>

      <footer className="footer">
        <QuantaEdgeBrand href={null} variant="footer" />
        <p><LocaleText hinglish="पढ़ाई, अब आपके बच्चे के हिसाब से।" english="Learning tailored to your child." /></p>
        <div className="footer-links"><Link href="/privacy"><LocaleText hinglish="Privacy" english="Privacy" /></Link><Link href="/terms"><LocaleText hinglish="Terms" english="Terms" /></Link></div>
        <span>© 2026 QuantaEdge Learning</span>
      </footer>
    </main>
  );
}
