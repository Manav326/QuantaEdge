'use client';

import Link from 'next/link';
import { UiText } from './i18n/useUiTranslation';
import { LanguageSwitcher, useLocale } from './components/LanguageProvider';
import { AppearanceModeButton } from './components/ColorThemeProvider';
import QuantaEdgeBrand from './components/QuantaEdgeBrand';
import { useTenantBranding } from './components/TenantBrandingProvider';
import LandingAccountMenu from './components/LandingAccountMenu';

const featureItems = [
  { id: 'paths', number: '01', icon: '↗', title: 'public.feature.paths.title', body: 'public.feature.paths.body' },
  { id: 'practice', number: '02', icon: '◎', title: 'public.feature.practice.title', body: 'public.feature.practice.body' },
  { id: 'tutor', number: '03', icon: '✦', title: 'public.feature.tutor.title', body: 'public.feature.tutor.body' },
  { id: 'progress', number: '04', icon: '⌁', title: 'public.feature.progress.title', body: 'public.feature.progress.body' },
  { id: 'review', number: '05', icon: '✓', title: 'public.feature.review.title', body: 'public.feature.review.body' },
  { id: 'offline', number: '06', icon: '◌', title: 'public.feature.offline.title', body: 'public.feature.offline.body' },
];

const audienceItems = [
  { id: 'school', icon: '▤', title: 'public.audience.school.title', body: 'public.audience.school.body' },
  { id: 'family', icon: '⌂', title: 'public.audience.family.title', body: 'public.audience.family.body' },
  { id: 'adult', icon: '↗', title: 'public.audience.adult.title', body: 'public.audience.adult.body' },
];

function DisplayControls() {
  return (
    <>
      <AppearanceModeButton />
      <LanguageSwitcher compact />
    </>
  );
}

export default function Home() {
  const { brand, isResolving, institutionContextRequested } = useTenantBranding();
  const { locale } = useLocale();
  const english = locale === 'english';

  if (!brand && isResolving) return (
    <main className="site qe-public-site" aria-busy="true">
      <header className="topbar qe-public-topbar">
        <QuantaEdgeBrand variant="header" />
        <div className="top-actions"><DisplayControls /></div>
      </header>
      <section className="qe-public-hero qe-public-hero--loading">
        <div className="qe-public-hero-copy">
          <span className="eyebrow">{english ? 'QUALITY LEARNING' : 'Quality learning'}</span>
          <h1>{english ? 'Learning that grows with you.' : 'आपके साथ आगे बढ़ती learning।'}</h1>
          <p className="hero-lead">{english ? 'Explore courses, practise with purpose and follow your progress.' : 'Courses explore करें, purpose के साथ practice करें और progress देखें।'}</p>
        </div>
      </section>
    </main>
  );

  if (!brand && institutionContextRequested) return (
    <main className="site qe-public-site qe-institution-link-error">
      <header className="topbar qe-public-topbar">
        <QuantaEdgeBrand variant="header" />
        <div className="top-actions"><DisplayControls /></div>
      </header>
      <section className="qe-public-hero">
        <div className="qe-public-hero-copy">
          <span className="eyebrow">{english ? 'LEARNING SPACE' : 'Learning space'}</span>
          <h1>{english ? 'This learning link could not be verified.' : 'इस learning link की पुष्टि नहीं हो सकी।'}</h1>
          <p className="hero-lead">{english ? 'Please reopen the link shared by your institution or ask its administrator for a current link.' : 'कृपया अपने institution का shared link फिर खोलें या administrator से नया link लें।'}</p>
        </div>
      </section>
    </main>
  );

  if (brand) return (
    <main className="site qe-school-landing">
      <header className="topbar qe-public-topbar">
        <QuantaEdgeBrand variant="header" />
        <div className="top-actions">
          <DisplayControls />
          <LandingAccountMenu />
        </div>
      </header>
      <section className="qe-school-hero">
        <div className="qe-public-hero-copy">
          <span className="eyebrow">{english ? 'YOUR LEARNING SPACE' : 'आपकी learning space'}</span>
          <h1>{english ? 'Learn with' : 'सीखें'}<br /><em>{brand.displayName}</em></h1>
          <p className="hero-lead">{english ? 'Explore your available courses, build understanding through practice and follow your progress in one place.' : 'अपने उपलब्ध courses explore करें, practice से समझ बनाएँ और अपनी progress एक जगह देखें।'}</p>
          <div className="hero-actions">
            <Link href="/login/student" className="button button-dark">{english ? 'Student sign in' : 'विद्यार्थी लॉगिन'} <span>→</span></Link>
            <Link href="/login" className="button button-light">{english ? 'Parent or learner sign in' : 'Parent या learner साइन इन'}</Link>
            <Link href="/signup" className="text-link">{english ? 'Create account' : 'खाता बनाएँ'}</Link>
          </div>
        </div>
        <div className="qe-public-visual" aria-hidden="true">
          <div className="qe-visual-orbit qe-visual-orbit--one" />
          <div className="qe-visual-orbit qe-visual-orbit--two" />
          <div className="qe-visual-panel">
            <div className="qe-visual-panel-top"><span>YOUR NEXT STEP</span><span className="qe-visual-status">●</span></div>
            <strong>{english ? 'Learn · Practise · Grow' : 'सीखें · Practice करें · बढ़ें'}</strong>
            <div className="qe-visual-bars"><i /><i /><i /><i /><i /></div>
            <div className="qe-visual-panel-foot"><span>{english ? 'Keep moving forward' : 'आगे बढ़ते रहें'}</span><b>↗</b></div>
          </div>
        </div>
      </section>
      <section className="qe-school-value-strip">
        <span>{english ? 'Lessons and course resources' : 'Lessons और course resources'}</span>
        <span>{english ? 'Practice and assessments' : 'Practice और assessments'}</span>
        <span>{english ? 'Progress that is easy to follow' : 'समझने योग्य progress'}</span>
      </section>
      <footer className="footer qe-public-footer">
        <QuantaEdgeBrand href={null} variant="footer" />
        <span>{english ? 'Learning with ' + brand.displayName : brand.displayName + ' के साथ learning'}</span>
        <div className="footer-links"><Link href="/privacy"><UiText message="legacy.web.privacy.323f3a35" /></Link><Link href="/terms"><UiText message="legacy.web.terms.c9f30225" /></Link></div>
      </footer>
    </main>
  );

  return (
    <main className="site qe-public-site">
      <header className="topbar qe-public-topbar">
        <QuantaEdgeBrand variant="header" />
        <nav className="topnav qe-public-nav" aria-label={english ? 'Main navigation' : 'मुख्य navigation'}>
          <a href="#how-it-works"><UiText message="public.nav.howItWorks" /></a>
          <a href="#learning"><UiText message="public.nav.learning" /></a>
          <a href="#audiences"><UiText message="public.nav.forFamilies" /></a>
        </nav>
        <div className="top-actions qe-public-top-actions">
          <Link href="/login" className="text-link qe-public-sign-in"><UiText message="public.nav.signIn" /></Link>
          <DisplayControls />
          <LandingAccountMenu />
        </div>
      </header>

      <section className="qe-public-hero" aria-labelledby="qe-public-heading">
        <div className="qe-public-hero-copy">
          <span className="eyebrow"><span className="live-dot" /><UiText message="public.hero.eyebrow" /></span>
          <h1 id="qe-public-heading"><UiText message="public.hero.titleA" /><br /><em><UiText message="public.hero.titleB" /></em></h1>
          <p className="hero-lead"><UiText message="public.hero.copy" /></p>
          <div className="hero-actions">
            <Link href="/signup" className="button button-dark"><UiText message="public.hero.primaryCta" /><span>→</span></Link>
            <a href="#how-it-works" className="button button-light"><UiText message="public.hero.secondaryCta" /></a>
          </div>
          <div className="qe-public-proof">
            <span><i aria-hidden="true">✓</i><UiText message="public.proof.courses" /></span>
            <span><i aria-hidden="true">✓</i><UiText message="public.proof.guided" /></span>
            <span><i aria-hidden="true">✓</i><UiText message="public.proof.progress" /></span>
          </div>
        </div>

        <div className="qe-public-visual" aria-label={english ? 'Illustration of guided learning and progress' : 'Guided learning और progress का चित्र'}>
          <div className="qe-visual-orbit qe-visual-orbit--one" />
          <div className="qe-visual-orbit qe-visual-orbit--two" />
          <div className="qe-visual-node qe-visual-node--one">01</div>
          <div className="qe-visual-node qe-visual-node--two">02</div>
          <div className="qe-visual-panel">
            <div className="qe-visual-panel-top"><span>{english ? 'YOUR LEARNING JOURNEY' : 'आपकी learning journey'}</span><span className="qe-visual-status">●</span></div>
            <div className="qe-visual-course-icon">✦</div>
            <span className="qe-visual-kicker">{english ? 'NEXT STEP' : 'अगला step'}</span>
            <strong>{english ? 'Learn with purpose' : 'Purpose के साथ सीखें'}</strong>
            <span className="qe-visual-description">{english ? 'Small steps. Meaningful progress.' : 'छोटे steps। Meaningful progress।'}</span>
            <div className="qe-visual-progress"><i /></div>
            <div className="qe-visual-panel-foot"><span>{english ? 'Keep moving forward' : 'आगे बढ़ते रहें'}</span><b>↗</b></div>
          </div>
          <span className="qe-visual-spark qe-visual-spark--one">✧</span>
          <span className="qe-visual-spark qe-visual-spark--two">✦</span>
        </div>
      </section>

      <section className="qe-public-flow" aria-label={english ? 'Learning journey' : 'Learning journey'}>
        <span>{english ? 'A clear path for every goal' : 'हर goal के लिए एक साफ़ रास्ता'}</span>
        <div><b>{english ? 'DISCOVER' : 'खोजें'}</b><i>→</i><b>{english ? 'LEARN' : 'सीखें'}</b><i>→</i><b>{english ? 'PRACTISE' : 'Practice'}</b><i>→</i><b>{english ? 'IMPROVE' : 'सुधारें'}</b></div>
      </section>

      <section id="how-it-works" className="qe-public-section qe-public-features">
        <div className="qe-public-section-intro">
          <span className="eyebrow"><UiText message="public.features.eyebrow" /></span>
          <h2><UiText message="public.features.titleA" /><br /><em><UiText message="public.features.titleB" /></em></h2>
          <p><UiText message="public.features.copy" /></p>
        </div>
        <div id="learning" className="qe-feature-grid">
          {featureItems.map(item => (
            <article className={'qe-feature-card qe-feature-card--' + item.id} key={item.id}>
              <div className="qe-feature-card-top"><span className="qe-feature-icon" aria-hidden="true">{item.icon}</span><span className="qe-feature-number">{item.number}</span></div>
              <h3><UiText message={item.title} /></h3>
              <p><UiText message={item.body} /></p>
            </article>
          ))}
        </div>
      </section>

      <section id="audiences" className="qe-public-audiences">
        <div className="qe-public-section-intro">
          <span className="eyebrow"><UiText message="public.audience.eyebrow" /></span>
          <h2><UiText message="public.audience.title" /></h2>
        </div>
        <div className="qe-audience-grid">
          {audienceItems.map(item => (
            <article className="qe-audience-card" key={item.id}>
              <span className="qe-audience-icon" aria-hidden="true">{item.icon}</span>
              <h3><UiText message={item.title} /></h3>
              <p><UiText message={item.body} /></p>
            </article>
          ))}
        </div>
        <div className="qe-curriculum-note">
          <span className="qe-curriculum-note-icon" aria-hidden="true">i</span>
          <div><strong><UiText message="public.school.noteTitle" /></strong><p><UiText message="public.school.noteBody" /></p></div>
        </div>
      </section>

      <section className="qe-public-close">
        <div className="qe-public-close-mark" aria-hidden="true">Q</div>
        <div><h2><UiText message="public.bottom.title" /></h2><p><UiText message="public.bottom.body" /></p></div>
        <Link href="/signup" className="button button-dark"><UiText message="public.bottom.cta" /><span>→</span></Link>
      </section>

      <footer className="footer qe-public-footer">
        <div className="qe-public-footer-brand">
          <QuantaEdgeBrand href={null} variant="footer" />
          <span><UiText message="public.footer.tagline" /></span>
        </div>
        <div className="footer-links">
          <Link href="/privacy"><UiText message="legacy.web.privacy.323f3a35" /></Link>
          <Link href="/terms"><UiText message="legacy.web.terms.c9f30225" /></Link>
        </div>
        <span>© 2026 QuantaEdge</span>
      </footer>
    </main>
  );
}
