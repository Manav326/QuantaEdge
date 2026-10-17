'use client';

import { useCallback, useEffect, useState } from 'react';
import { useLocale } from '../../components/LanguageProvider';
import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import { useTenantBranding } from '../../components/TenantBrandingProvider';

type InstitutionMembership = {
  tenant_code?: string;
  institution_name?: string;
  institution_status?: string;
  membership_status?: string;
  joined_at?: string;
  authorized_courses?: number;
};

async function loadLearnerMemberships(institutionCode: string): Promise<InstitutionMembership[]> {
  const response = await fetch('/api/v1/institution/memberships/me?institutionCode=' + encodeURIComponent(institutionCode), {
    credentials: 'same-origin',
    cache: 'no-store',
    headers: { 'X-QE-Workspace': 'STUDENT', 'Accept': 'application/json' },
  });
  const raw = await response.text();
  let body: { institutions?: InstitutionMembership[]; message?: string; detail?: string } = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(body.message || body.detail || 'School enrollment could not be checked.');
  return Array.isArray(body.institutions) ? body.institutions : [];
}

export default function StudentSchoolPage() {
  const { locale } = useLocale();
  const english = locale === 'english';
  const { brand, isResolving } = useTenantBranding();
  const [memberships, setMemberships] = useState<InstitutionMembership[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const refresh = useCallback(async () => {
    const institutionCode = brand?.institutionCode;
    if (!institutionCode) {
      setMemberships([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try { setMemberships(await loadLearnerMemberships(institutionCode)); }
    catch (problem) { setError(problem instanceof Error ? problem.message : 'School enrollment could not be checked.'); }
    finally { setLoading(false); }
  }, [brand?.institutionCode]);

  useEffect(() => { void refresh(); }, [refresh]);

  // Never list the learner's other institution memberships in a school-branded experience.
  const membership = brand
    ? memberships.find(item => String(item.tenant_code || '').trim().toUpperCase() === brand.institutionCode)
    : undefined;
  const active = membership?.institution_status === 'ACTIVE' && membership.membership_status === 'ACTIVE';

  return (
    <main className="qe-structured-student-page qe-institutions-page">
      <header className="qe-institutions-page-header">
        <QuantaEdgeBrand href="/student" variant="compact" />
        <div>
          <span className="eyebrow">{brand?.displayName || (english ? 'YOUR SCHOOL' : 'आपका विद्यालय')}</span>
          <h1>{english ? 'My school' : 'मेरा विद्यालय'}</h1>
          <p>{english ? 'Your school identity and enrollment for this learning space.' : 'इस सीखने के स्थान में आपके विद्यालय और नामांकन की जानकारी।'}</p>
        </div>
      </header>

      {error ? <div className="auth-message is-error" role="alert">{error}</div> : null}

      <section className="qe-institutions-panel">
        {isResolving || loading ? <p>{english ? 'Checking your school enrollment…' : 'विद्यालय में नामांकन की जाँच हो रही है…'}</p> : null}
        {!isResolving && !brand ? <div className="qe-institutions-empty">
          <strong>{english ? 'Open your school’s learning link' : 'अपने विद्यालय का learning link खोलें'}</strong>
          <p>{english ? 'Use the link shared by your school to enter its branded learning space. Your account will be linked through that school’s invitation.' : 'अपने विद्यालय द्वारा साझा किया गया लिंक खोलें। उसी विद्यालय के आमंत्रण से आपका मौजूदा खाता जुड़ेगा।'}</p>
        </div> : null}
        {!isResolving && !loading && brand && !membership ? <div className="qe-institutions-empty">
          <strong>{english ? 'Enrollment is not active for this school' : 'इस विद्यालय में नामांकन सक्रिय नहीं है'}</strong>
          <p>{english ? 'Open the enrollment link shared by your school, or contact the school administrator.' : 'विद्यालय द्वारा साझा किया गया नामांकन लिंक खोलें या विद्यालय प्रशासक से संपर्क करें।'}</p>
        </div> : null}
        {!loading && brand && membership ? (
          <article className="qe-institution-membership">
            {brand.logoUrl
              ? <img className="qe-institution-school-logo" src={brand.logoUrl} alt="" aria-hidden="true" />
              : <span className="qe-brand__tenant-mark" aria-hidden="true">{brand.displayName.slice(0, 1).toLocaleUpperCase()}</span>}
            <div className="qe-institution-membership-copy">
              <strong>{brand.displayName}</strong>
              <small>{english ? 'School enrollment' : 'विद्यालय में नामांकन'}</small>
              {membership.joined_at ? <small>{english ? 'Joined: ' : 'जुड़े: '}{new Date(membership.joined_at).toLocaleDateString(locale === 'english' ? 'en-IN' : 'hi-IN')}</small> : null}
              <small>{english ? 'Authorised courses: ' : 'अधिकृत पाठ्यक्रम: '}{membership.authorized_courses ?? 0}</small>
            </div>
            <span className={active ? 'qe-institution-status is-active' : 'qe-institution-status'}>
              {active ? (english ? 'Enrolled' : 'नामांकित') : (english ? 'Inactive' : 'निष्क्रिय')}
            </span>
          </article>
        ) : null}
        <div className="qe-institutions-panel-heading">
          <span />
          <button type="button" className="button button-light button-small" onClick={() => void refresh()} disabled={loading || isResolving}>
            {english ? 'Refresh status' : 'स्थिति रीफ़्रेश करें'}
          </button>
        </div>
      </section>
    </main>
  );
}
