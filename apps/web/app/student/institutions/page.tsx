'use client';

import { FormEvent, useCallback, useEffect, useState } from 'react';
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
  logo_asset_key?: string;
  branding_version?: number;
};

async function learnerApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-QE-Workspace', 'STUDENT');
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(String(body.message || body.detail || body.error || 'The request could not be completed.'));
  return body as T;
}

export default function StudentInstitutionsPage() {
  const { locale } = useLocale();
  const english = locale === 'english';
  const { brand, resolveInstitutionCode } = useTenantBranding();
  const [memberships, setMemberships] = useState<InstitutionMembership[]>([]);
  const [code, setCode] = useState('');
  const [loading, setLoading] = useState(true);
  const [joining, setJoining] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const loadMemberships = useCallback(async () => {
    setLoading(true);
    try {
      const result = await learnerApi<{ institutions?: InstitutionMembership[] }>('/api/v1/institution/memberships/me');
      setMemberships(Array.isArray(result.institutions) ? result.institutions : []);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Institution memberships could not be loaded.');
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { void loadMemberships(); }, [loadMemberships]);

  async function joinInstitution(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError('');
    setNotice('');
    const clean = code.trim().toUpperCase();
    if (clean.length < 3) {
      setError(english ? 'Enter the institution code shared by your school.' : 'अपने विद्यालय द्वारा दिया गया संस्थान कोड दर्ज करें।');
      return;
    }
    setJoining(true);
    try {
      const result = await learnerApi<{ institutionCode?: string; institutionName?: string; status?: string }>('/api/v1/institution/join', {
        method: 'POST',
        body: JSON.stringify({ code: clean }),
      });
      setCode('');
      const institutionCode = String(result.institutionCode || '').trim();
      if (institutionCode) await resolveInstitutionCode(institutionCode);
      setNotice(english
        ? 'You are now linked with ' + (result.institutionName || 'your school') + '.'
        : (result.institutionName || 'आपका विद्यालय') + ' से आपका खाता जुड़ गया है।');
      await loadMemberships();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The institution code could not be used.');
    } finally { setJoining(false); }
  }

  return (
    <main className="qe-structured-student-page qe-institutions-page">
      <header className="qe-institutions-page-header">
        <QuantaEdgeBrand href="/student" variant="compact" />
        <div>
          <span className="eyebrow">{brand?.displayName || (english ? 'YOUR SCHOOL' : 'आपका विद्यालय')}</span>
          <h1>{english ? 'My institutions' : 'मेरे विद्यालय'}</h1>
          <p>{english ? 'See the schools linked to your learner account, or join another school using its invitation code.' : 'अपने विद्यार्थी खाते से जुड़े विद्यालय देखें या आमंत्रण कोड से दूसरे विद्यालय से जुड़ें।'}</p>
        </div>
      </header>

      {error ? <div className="auth-message is-error" role="alert">{error}</div> : null}
      {notice ? <div className="auth-message" role="status">{notice}</div> : null}

      <section className="qe-institutions-panel">
        <h2>{english ? 'Join your school' : 'अपने विद्यालय से जुड़ें'}</h2>
        <p>{english ? 'Enter the join code provided by the school. This links your existing account; it does not create a duplicate learner account.' : 'विद्यालय द्वारा दिया गया जुड़ने का कोड दर्ज करें। आपका मौजूदा खाता जुड़ेगा, नया विद्यार्थी खाता नहीं बनेगा।'}</p>
        <form className="qe-institutions-join-form" onSubmit={joinInstitution}>
          <label>
            {english ? 'Institution join code' : 'संस्थान का जुड़ने का कोड'}
            <input value={code} onChange={event => setCode(event.target.value.toUpperCase().replace(/[^A-Z0-9_-]/g, '').slice(0, 32))}
              autoComplete="off" minLength={3} maxLength={32} required placeholder={english ? 'Enter school code' : 'विद्यालय कोड दर्ज करें'} />
          </label>
          <button type="submit" className="button button-dark" disabled={joining || loading}>{joining ? (english ? 'Joining…' : 'जुड़ रहे हैं…') : (english ? 'Join institution' : 'विद्यालय से जुड़ें')}</button>
        </form>
      </section>

      <section className="qe-institutions-panel">
        <div className="qe-institutions-panel-heading">
          <h2>{english ? 'Schools linked to your account' : 'आपके खाते से जुड़े विद्यालय'}</h2>
          <button type="button" className="button button-light button-small" onClick={() => void loadMemberships()} disabled={loading}>{english ? 'Refresh' : 'रीफ़्रेश'}</button>
        </div>
        {loading ? <p>{english ? 'Loading your school memberships…' : 'विद्यालय की जानकारी लोड हो रही है…'}</p> : null}
        {!loading && memberships.length === 0 ? <div className="qe-institutions-empty">
          <strong>{english ? 'No school linked yet' : 'अभी कोई विद्यालय नहीं जुड़ा है'}</strong>
          <p>{english ? 'Use the join code supplied by your school to connect this account.' : 'इस खाते को जोड़ने के लिए विद्यालय से मिला कोड दर्ज करें।'}</p>
        </div> : null}
        <div className="qe-institutions-list">
          {memberships.map((item, index) => {
            const active = item.institution_status === 'ACTIVE' && item.membership_status === 'ACTIVE';
            return <article className="qe-institution-membership" key={(item.tenant_code || 'tenant') + '-' + index}>
              <span className="qe-brand__tenant-mark" aria-hidden="true">{(item.institution_name || 'S').slice(0, 1).toLocaleUpperCase()}</span>
              <div className="qe-institution-membership-copy">
                <strong>{item.institution_name || item.tenant_code || (english ? 'School' : 'विद्यालय')}</strong>
                <span>{item.tenant_code || ''}</span>
                <small>{english ? 'Authorised courses: ' : 'अधिकृत पाठ्यक्रम: '}{item.authorized_courses ?? 0}</small>
              </div>
              <span className={active ? 'qe-institution-status is-active' : 'qe-institution-status'}>{active ? (english ? 'Active' : 'सक्रिय') : (item.membership_status || item.institution_status || (english ? 'Unavailable' : 'उपलब्ध नहीं'))}</span>
            </article>;
          })}
        </div>
      </section>
    </main>
  );
}
