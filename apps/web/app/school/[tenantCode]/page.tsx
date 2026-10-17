'use client';

import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useLocale, LanguageSwitcher } from '../../components/LanguageProvider';
import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import LandingAccountMenu from '../../components/LandingAccountMenu';
import { useTenantBranding } from '../../components/TenantBrandingProvider';

export default function SchoolEntryPage() {
  const params = useParams<{ tenantCode: string }>();
  const { locale } = useLocale();
  const { brand, isResolving, institutionContextRequested, resolveInstitutionCode } = useTenantBranding();
  const english = locale === 'english';
  const code = String(params.tenantCode || '').trim().toUpperCase();

  if (!brand && (isResolving || institutionContextRequested)) {
    return (
      <main className="site qe-tenant-landing" aria-busy="true">
        <section className="hero">
          <div className="hero-copy">
            <div className="eyebrow">{english ? 'YOUR SCHOOL LEARNING SPACE' : 'आपके विद्यालय का सीखने का स्थान'}</div>
            <h1>{english ? 'Preparing your' : 'आपके'}<br /><em>{english ? 'school learning space' : 'विद्यालय का सीखने का स्थान'}</em></h1>
            <p className="hero-lead">{english ? 'Verifying your school link…' : 'विद्यालय के लिंक की पुष्टि हो रही है…'}</p>
          </div>
        </section>
      </main>
    );
  }

  if (!brand) {
    return (
      <main className="site qe-tenant-landing">
        <section className="hero">
          <div className="hero-copy">
            <div className="eyebrow">{english ? 'SCHOOL LINK' : 'विद्यालय लिंक'}</div>
            <h1>{english ? 'This school link could not be verified.' : 'इस विद्यालय लिंक की पुष्टि नहीं हो सकी।'}</h1>
            <p className="hero-lead">{english ? 'Please reopen the link shared by your school or ask the school administrator for a current link.' : 'कृपया विद्यालय द्वारा साझा किया गया लिंक फिर खोलें या विद्यालय प्रशासक से नया लिंक लें।'}</p>
            <div className="hero-actions">
              <button type="button" className="button button-dark" onClick={() => void resolveInstitutionCode(code)} disabled={!code || isResolving}>
                {isResolving ? (english ? 'Checking…' : 'जाँच हो रही है…') : (english ? 'Try again' : 'फिर कोशिश करें')}
              </button>
            </div>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="site qe-tenant-landing">
      <header className="topbar">
        <QuantaEdgeBrand href={null} variant="header" />
        <div className="top-actions">
          <LanguageSwitcher compact />
          <LandingAccountMenu />
        </div>
      </header>
      <section className="hero">
        <div className="hero-copy">
          <div className="eyebrow">{english ? 'YOUR SCHOOL LEARNING SPACE' : 'आपके विद्यालय का सीखने का स्थान'}</div>
          <h1>{english ? 'Learn with' : 'सीखें'}<br /><em>{brand.displayName}</em></h1>
          <p className="hero-lead">{english
            ? 'Sign in to continue your school lessons, practise concepts, take tests and track your progress.'
            : 'विद्यालय के पाठ पढ़ने, अभ्यास करने, परीक्षा देने और अपनी प्रगति देखने के लिए साइन इन करें।'}</p>
          <div className="hero-actions">
            <Link href="/login/student" className="button button-dark">{english ? 'Student sign in' : 'विद्यार्थी लॉगिन'} <span>→</span></Link>
            <Link href="/login" className="button button-light">{english ? 'Parent sign in' : 'अभिभावक लॉगिन'}</Link>
            <Link href="/signup" className="text-link">{english ? 'Create account' : 'खाता बनाएँ'}</Link>
          </div>
        </div>
        <div className="hero-device" aria-hidden="true">
          <div className="device-glow" />
          <div className="student-card">
            <div className="student-head">
              <div><span className="muted-small">{english ? 'YOUR LEARNING JOURNEY' : 'आपकी सीखने की यात्रा'}</span><h3>{english ? 'Ready to continue?' : 'आगे बढ़ें?'}</h3></div>
              <span className="avatar">{brand.displayName.slice(0, 1).toUpperCase()}</span>
            </div>
            <div className="mission-card"><div><span className="mission-label">{english ? 'NEXT STEP' : 'अगला चरण'}</span><strong>{english ? 'Learn · Practise · Grow' : 'सीखें · अभ्यास करें · बढ़ें'}</strong><small>{english ? 'Your progress stays with your account.' : 'आपकी प्रगति आपके खाते में सुरक्षित रहेगी।'}</small></div></div>
          </div>
        </div>
      </section>
      <footer className="footer">
        <QuantaEdgeBrand href={null} variant="footer" />
        <span>{english ? 'Learning with ' + brand.displayName : brand.displayName + ' के साथ सीखें'}</span>
        <div className="footer-links"><Link href="/privacy">{english ? 'Privacy' : 'गोपनीयता'}</Link><Link href="/terms">{english ? 'Terms' : 'नियम'}</Link></div>
      </footer>
    </main>
  );
}
