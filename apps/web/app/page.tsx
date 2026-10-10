import { LanguageSwitcher, LocaleText } from './components/LanguageProvider';
import Link from 'next/link';
import QuantaEdgeBrand from './components/QuantaEdgeBrand';
import LandingAccountMenu from './components/LandingAccountMenu';

const subjects = [
  { icon: '∑', name: 'गणित', meta: 'कक्षा 6–8 · हिन्दी माध्यम', tone: 'violet' },
  { icon: '⚗', name: 'विज्ञान', meta: 'कक्षा 6–8 · हिन्दी माध्यम', tone: 'blue' },
];

const promises = [
  ['01', 'पहले concept समझेंगे', 'First, understand the concept', 'हर lesson में prerequisite, simple explanation और worked example से concept clear होता है।', 'Each lesson builds understanding through prerequisites, a simple explanation, and a worked example.'],
  ['02', 'फिर practice करेंगे', 'Then, practise', 'छोटे interactive questions, instant feedback और saved learning progress के साथ।', 'Short interactive questions, instant feedback, and saved learning progress.'],
  ['03', 'फिर अगला step लेंगे', 'Then, take the next step', 'Diagnostic, progress और concept mastery के आधार पर अगला step तय होता है।', 'The next step is guided by diagnostics, progress, and concept mastery.'],
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
            <LocaleText hinglish="QuantaEdge सिर्फ answers नहीं देता। Diagnostic, practice और real learning records के आधार पर बच्चे का अगला learning step तय होता है।" english="QuantaEdge does more than provide answers. Diagnostics, practice, and real learning records help choose each child's next learning step." />
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
              <div><span className="muted-small"><LocaleText hinglish="Learning journey" english="Learning journey" /></span><h3><LocaleText hinglish="आपके बच्चे के लिए" english="For your child" /></h3></div>
              <div className="avatar">Q</div>
            </div>

            <div className="mission-card">
              <div>
                <span className="mission-label"><LocaleText hinglish="आज की पढ़ाई" english="Today's learning" /></span>
                <strong><LocaleText hinglish="एक concept · अगला सही step" english="One concept · one right next step" /></strong>
                <div className="progress-track"><span style={{ width: '58%' }} /></div>
                <small><LocaleText hinglish="Answers के साथ progress save होती है" english="Progress saves as you answer" /></small>
              </div>
              <span className="streak"><LocaleText hinglish="LIVE" english="LIVE" /></span>
            </div>

            <div className="section-title"><strong><LocaleText hinglish="Learning flow" english="Learning flow" /></strong><span><LocaleText hinglish="adaptive" english="adaptive" /></span></div>

            <div className="task done">
              <span className="task-icon">↻</span>
              <div><strong><LocaleText hinglish="समझें" english="Learn" /></strong><small><LocaleText hinglish="Prerequisite · explanation · worked example" english="Prerequisite · explanation · worked example" /></small></div>
              <span className="check">✓</span>
            </div>

            <div className="task active">
              <span className="task-icon">∑</span>
              <div><strong><LocaleText hinglish="Practice करें" english="Practise" /></strong><small><LocaleText hinglish="Interactive questions · instant feedback" english="Interactive questions · instant feedback" /></small></div>
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
          {promises.map(([n, titleHi, titleEn, descriptionHi, descriptionEn]) => (
            <article className="promise" key={n}>
              <span>{n}</span>
              <h3><LocaleText hinglish={titleHi} english={titleEn} /></h3>
              <p><LocaleText hinglish={descriptionHi} english={descriptionEn} /></p>
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
              <div><span><LocaleText hinglish="कक्षा 6–8" english="Classes 6–8" /></span><h3>{subject.name === 'गणित' ? <LocaleText hinglish="गणित" english="Mathematics" /> : <LocaleText hinglish="विज्ञान" english="Science" />}</h3><p><LocaleText hinglish={subject.meta} english="Classes 6–8 · Hindi medium" /></p></div>
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
            <div><span><LocaleText hinglish="Real learning records" english="Real learning records" /></span><strong><LocaleText hinglish="Parent report" english="Parent report" /></strong></div>
            <span className="up">LIVE</span>
          </div>
          <div className="report-stat">
            <strong><LocaleText hinglish="Progress" english="Progress" /></strong>
            <span><LocaleText hinglish="Lessons · attempts · mastery" english="Lessons · attempts · mastery" /></span>
            <div className="mini-bars">
              <i style={{ height: '30%' }} /><i style={{ height: '44%' }} /><i style={{ height: '54%' }} />
              <i style={{ height: '61%' }} /><i style={{ height: '72%' }} /><i style={{ height: '81%' }} />
              <i style={{ height: '88%' }} />
            </div>
          </div>
          <div className="report-bottom"><span><LocaleText hinglish="Strengths: mastery" english="Strengths: mastery" /></span><span><LocaleText hinglish="Support: जिन concepts में मदद चाहिए" english="Support: concepts needing help" /></span></div>
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
