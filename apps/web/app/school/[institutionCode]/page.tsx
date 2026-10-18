'use client';

import Link from 'next/link';
import { useLocale } from '../../components/LanguageProvider';
import { useTenantBranding } from '../../components/TenantBrandingProvider';

export default function SchoolLearningEntryPage() {
  const { locale } = useLocale();
  const english = locale === 'english';
  const { brand, isResolving, institutionContextRequested } = useTenantBranding();

  if (isResolving) {
    return (
      <main className="qe-school-entry" aria-busy="true">
        <section className="qe-school-entry-panel">
          <div className="qe-school-entry-placeholder" aria-hidden="true" />
          <p>{english ? 'Opening your school learning space…' : 'आपके विद्यालय का learning space खुल रहा है…'}</p>
        </section>
      </main>
    );
  }

  if (!brand) {
    return (
      <main className="qe-school-entry" role="main">
        <section className="qe-school-entry-panel">
          <h1>{english ? 'This school link is unavailable' : 'विद्यालय का यह लिंक उपलब्ध नहीं है'}</h1>
          <p>{english ? 'The school link may have expired or been changed. Please ask your school for its current learning link.' : 'विद्यालय का लिंक समाप्त या बदला हुआ हो सकता है। अपने विद्यालय से नया learning link लें।'}</p>
          {institutionContextRequested ? <p role="status">{english ? 'This invitation opens only this school’s learning space. Ask your school for a new link if this one no longer works.' : 'यह आमंत्रण केवल इसी विद्यालय का learning space खोलता है। लिंक काम न करे तो विद्यालय से नया लिंक लें।'}</p> : null}
        </section>
      </main>
    );
  }

  return (
    <main className="qe-school-entry">
      <section className="qe-school-entry-panel">
        {brand.logoUrl
          ? <img className="qe-school-entry-logo" src={brand.logoUrl} alt="" aria-hidden="true" />
          : <span className="qe-school-entry-letter" aria-hidden="true">{brand.displayName.slice(0, 1).toLocaleUpperCase()}</span>}
        <span className="eyebrow">{english ? 'YOUR SCHOOL LEARNING SPACE' : 'आपके विद्यालय का सीखने का स्थान'}</span>
        <h1>{brand.displayName}</h1>
        <p>{english ? 'Sign in or create your learner account to continue with your school. The school invitation will link your account to this school after sign-in.' : 'अपने विद्यालय में सीखना जारी रखने के लिए साइन इन करें या विद्यार्थी खाता बनाएँ। साइन इन के बाद विद्यालय का आमंत्रण आपके खाते को इसी विद्यालय से जोड़ेगा।'}</p>
        <div className="qe-school-entry-actions">
          <Link href="/login/student" className="button button-dark">{english ? 'Student sign in' : 'विद्यार्थी लॉगिन'}</Link>
          <Link href="/signup" className="button button-light">{english ? 'Create learner account' : 'विद्यार्थी खाता बनाएँ'}</Link>
        </div>
        <p className="qe-school-entry-note">{english ? 'This learning space is for ' + brand.displayName + ' students.' : 'यह सीखने का स्थान ' + brand.displayName + ' के विद्यार्थियों के लिए है।'}</p>
      </section>
    </main>
  );
}
