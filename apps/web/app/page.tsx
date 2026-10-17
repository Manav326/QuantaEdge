import { UiText } from './i18n/useUiTranslation';
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
          <a href="#how-it-works"><UiText message="legacy.web.how.it.works.5f2a2c5d" /></a>
          <a href="#subjects"><UiText message="legacy.web.learning.9e92d931" /></a>
          <a href="#parents"><UiText message="legacy.web.parents.a532bfe5" /></a>
        </nav>

        <div className="top-actions"><LanguageSwitcher compact />
          <LandingAccountMenu />
        </div>
      </header>

      <section className="hero">
        <div className="hero-copy">
          <div className="eyebrow"><span className="live-dot" /> <UiText message="legacy.web.classes.6.8.bihar.board.hindi.medium.4009abd6" /></div>
          <h1><UiText message="legacy.web.learning.5028b955" /><br /><em><UiText message="legacy.web.adapted.to.each.child.291c43fd" /></em></h1>
          <p className="hero-lead">
            <UiText message="legacy.web.quantaedge.does.more.than.provide.answ.41cfb954" />
          </p>

          <div className="hero-actions">
            <Link href="/login/student" className="button button-dark"><UiText message="legacy.web.start.learning.c0c87037" /> <span>→</span></Link>
            <Link href="/login" className="button button-light"><UiText message="legacy.web.i.m.a.parent.21661703" /></Link>
          </div>

          <div className="trust-row">
            <span><UiText message="legacy.web.no.public.rankings.ff0693c0" /></span>
            <span><UiText message="legacy.web.focus.on.learning.49160904" /></span>
            <span><UiText message="legacy.web.parents.can.see.progress.bad0199a" /></span>
          </div>
        </div>

        <div className="hero-device" aria-label="QuantaEdge learning flow preview">
          <div className="device-glow" />
          <div className="student-card">
            <div className="student-head">
              <div><span className="muted-small"><UiText message="legacy.web.learning.journey.8c1c2635" /></span><h3><UiText message="legacy.web.for.your.child.60a0d8db" /></h3></div>
              <div className="avatar">Q</div>
            </div>

            <div className="mission-card">
              <div>
                <span className="mission-label"><UiText message="legacy.web.today.s.learning.f636afdf" /></span>
                <strong><UiText message="legacy.web.one.concept.one.right.next.step.ee2df317" /></strong>
                <div className="progress-track"><span style={{ width: '58%' }} /></div>
                <small><UiText message="legacy.web.progress.saves.as.you.answer.7f4dc282" /></small>
              </div>
              <span className="streak"><UiText message="legacy.web.live.0fa74861" /></span>
            </div>

            <div className="section-title"><strong><UiText message="legacy.web.learning.flow.ddb1d3cd" /></strong><span><UiText message="legacy.web.adaptive.716d0f79" /></span></div>

            <div className="task done">
              <span className="task-icon">↻</span>
              <div><strong><UiText message="legacy.web.learn.e055cff7" /></strong><small><UiText message="legacy.web.prerequisite.explanation.worked.exampl.cc9f4ed1" /></small></div>
              <span className="check">✓</span>
            </div>

            <div className="task active">
              <span className="task-icon">∑</span>
              <div><strong><UiText message="legacy.web.practise.ea71f373" /></strong><small><UiText message="legacy.web.interactive.questions.instant.feedback.8e04e36d" /></small></div>
              <span className="arrow">→</span>
            </div>

            <div className="task">
              <span className="task-icon">✦</span>
              <div><strong><UiText message="legacy.web.build.mastery.4877ba09" /></strong><small><UiText message="legacy.web.next.step.based.on.concept.mastery.3c4e876f" /></small></div>
              <span className="arrow">→</span>
            </div>

            <Link href="/login/student" className="device-cta"><UiText message="legacy.web.start.your.learning.journey.0d258640" /></Link>
          </div>
        </div>
      </section>

      <section className="strip">
        <span><UiText message="legacy.web.a.learning.system.that.chooses.the.rig.16c2fefa" /></span>
        <div><b>DIAGNOSE</b><i>→</i><b>LEARN</b><i>→</i><b>PRACTICE</b><i>→</i><b>MASTER</b></div>
      </section>

      <section id="how-it-works" className="section">
        <div className="section-intro">
          <span className="eyebrow"><UiText message="legacy.web.our.learning.loop.fea94a66" /></span>
          <h2><UiText message="legacy.web.more.than.content.76733b95" /><br /><em><UiText message="legacy.web.the.right.next.step.every.time.3f3b50f8" /></em></h2>
          <p><UiText message="legacy.web.progress.question.attempts.sessions.an.70713d1a" /></p>
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
          <div><span className="eyebrow"><UiText message="legacy.web.start.now.88b76ad2" /></span><h2><UiText message="legacy.web.your.first.step.in.learning.ddd4ba00" /></h2></div>
          <Link href="/login/student" className="text-link"><UiText message="legacy.web.get.started.cd90a839" /></Link>
        </div>

        <div className="subject-grid">
          {subjects.map(subject => (
            <Link href="/login/student" className="subject-card" key={subject.name}>
              <div className={`subject-icon ${subject.tone}`}>{subject.icon}</div>
              <div><span><UiText message="legacy.web.classes.6.8.9af66485" /></span><h3>{subject.name === 'गणित' ? <UiText message="legacy.web.mathematics.4cf6be1a" /> : <UiText message="legacy.web.science.f85a10aa" />}</h3><p><LocaleText hinglish={subject.meta} english="Classes 6–8 · Hindi medium" /></p></div>
              <span className="card-arrow">↗</span>
            </Link>
          ))}
        </div>
      </section>

      <section id="parents" className="parent-section">
        <div className="parent-copy">
          <span className="eyebrow"><UiText message="legacy.web.for.parents.f4a58e74" /></span>
          <h2><UiText message="legacy.web.not.just.how.much.they.studied.bf5abaa9" /><br /><em><UiText message="legacy.web.see.what.they.learned.7c690630" /></em></h2>
          <p><UiText message="legacy.web.a.simple.summary.of.your.child.s.actua.45407e46" /></p>
          <Link href="/login" className="button button-dark"><UiText message="legacy.web.open.parent.view.5d942b13" /></Link>
        </div>

        <div className="report-card">
          <div className="report-top">
            <div><span><UiText message="legacy.web.real.learning.records.8345bc3d" /></span><strong><UiText message="legacy.web.parent.report.82e13ce5" /></strong></div>
            <span className="up">LIVE</span>
          </div>
          <div className="report-stat">
            <strong><UiText message="legacy.web.progress.953da4d3" /></strong>
            <span><UiText message="legacy.web.lessons.attempts.mastery.abc38175" /></span>
            <div className="mini-bars">
              <i style={{ height: '30%' }} /><i style={{ height: '44%' }} /><i style={{ height: '54%' }} />
              <i style={{ height: '61%' }} /><i style={{ height: '72%' }} /><i style={{ height: '81%' }} />
              <i style={{ height: '88%' }} />
            </div>
          </div>
          <div className="report-bottom"><span><UiText message="legacy.web.strengths.mastery.fd7b51ef" /></span><span><UiText message="legacy.web.support.concepts.needing.help.9f502667" /></span></div>
        </div>
      </section>

      <footer className="footer">
        <QuantaEdgeBrand href={null} variant="footer" />
        <p><UiText message="legacy.web.learning.tailored.to.your.child.ce21e04d" /></p>
        <div className="footer-links"><Link href="/privacy"><UiText message="legacy.web.privacy.323f3a35" /></Link><Link href="/terms"><UiText message="legacy.web.terms.c9f30225" /></Link></div>
        <span>© 2026 QuantaEdge Learning</span>
      </footer>
    </main>
  );
}
