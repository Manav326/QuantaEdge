'use client';

import { FormEvent, useCallback, useEffect, useMemo, useState } from 'react';

type Brand = {
  institutionCode?: string;
  displayName?: string;
  logoAssetKey?: string | null;
  appIconAssetKey?: string | null;
  brandingVersion?: number;
  themeTokens?: Record<string, string>;
};
type Principal = {
  authenticated?: boolean;
  workspace?: string;
  institutionId?: number;
  institutionCode?: string;
  institutionName?: string;
  adminUsername?: string;
  adminDisplayName?: string;
  mustChangePassword?: boolean;
  joinCode?: string | null;
  branding?: Brand;
};
type Overview = {
  active_learners?: number;
  all_memberships?: number;
  authorized_courses?: number;
  lessons_with_activity?: number;
  completed_lessons?: number;
  current_period_billable_events?: number;
  current_period_amount_paise?: number;
};
type Learner = {
  learner_reference?: string;
  display_name?: string;
  class_code?: string;
  board?: string;
  language?: string;
  city?: string;
  state?: string;
  school_name?: string;
  membership_status?: string;
  joined_at?: string;
  completed_lessons?: number;
  lessons_with_activity?: number;
  average_lesson_progress_percent?: number;
};
type Course = {
  code?: string;
  display_name?: string;
  entitlement_start?: string;
  entitlement_end?: string | null;
  published_lessons?: number;
  institution_learners?: number;
};

async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('X-QE-Workspace', 'INSTITUTION');
  headers.set('Accept', 'application/json');
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const response = await fetch(path, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let data: any = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch { data = { message: raw }; }
  if (!response.ok) {
    const message = data.message || data.detail || data.error || `Request failed (${response.status})`;
    throw new Error(String(message));
  }
  return data as T;
}

function numberText(value: unknown) {
  const n = Number(value ?? 0);
  return Number.isFinite(n) ? n.toLocaleString('en-IN') : '0';
}

function dateText(value?: string) {
  if (!value) return '—';
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : parsed.toLocaleDateString('en-IN');
}

export default function InstitutionPortalPage() {
  const [tenantCode, setTenantCode] = useState('');
  const [brand, setBrand] = useState<Brand | null>(null);
  const [brandLoading, setBrandLoading] = useState(false);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [principal, setPrincipal] = useState<Principal | null>(null);
  const [overview, setOverview] = useState<Overview | null>(null);
  const [learners, setLearners] = useState<Learner[]>([]);
  const [courses, setCourses] = useState<Course[]>([]);
  const [search, setSearch] = useState('');
  const [activeView, setActiveView] = useState<'overview' | 'learners' | 'courses'>('overview');
  const [currentPassword, setCurrentPassword] = useState('');
  const [nextPassword, setNextPassword] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(false);
  const [loginMode, setLoginMode] = useState(true);

  const applyBrand = useCallback((value: Brand | null) => {
    setBrand(value);
    // Always reset all tenant tokens before applying a new tenant or signing out.
    // Otherwise an institution with no custom token could inherit the previous tenant's theme.
    const root = document.documentElement;
    const mapping: Record<string, { css: string; fallback: string }> = {
      primary: { css: '--institution-primary', fallback: '#F97316' },
      accent: { css: '--institution-accent', fallback: '#C2410C' },
      background: { css: '--institution-background', fallback: '#F7F8FC' },
      surface: { css: '--institution-surface', fallback: '#FFFFFF' },
      text: { css: '--institution-text', fallback: '#152038' },
    };
    Object.entries(mapping).forEach(([key, token]) => {
      const color = value?.themeTokens?.[key];
      root.style.setProperty(token.css,
        typeof color === 'string' && /^#[0-9a-f]{6}$/i.test(color) ? color : token.fallback);
    });
  }, []);

  const loadData = useCallback(async (current: Principal) => {
    if (!current.authenticated) return;
    setLoading(true);
    setError('');
    try {
      const [me, summary, roster, authorizedCourses] = await Promise.all([
        api<Principal>('/api/v1/institution/auth/me'),
        api<Overview>('/api/v1/institution/reports/overview'),
        api<{ learners?: Learner[] }>('/api/v1/institution/reports/roster?page=1&pageSize=200'),
        api<Course[]>('/api/v1/institution/reports/courses'),
      ]);
      setPrincipal(me);
      setOverview(summary);
      setLearners(Array.isArray(roster.learners) ? roster.learners : []);
      setCourses(Array.isArray(authorizedCourses) ? authorizedCourses : []);
      if (me.branding) applyBrand({
        ...me.branding,
        institutionCode: me.institutionCode,
        displayName: me.institutionName || me.branding.displayName,
      });
      if (!current.mustChangePassword && !me.mustChangePassword) setNotice('');
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Institution data could not be loaded.';
      setError(message);
      if (/authentication|required|session expired/i.test(message)) setPrincipal(null);
    } finally {
      setLoading(false);
    }
  }, [applyBrand]);

  useEffect(() => {
    let alive = true;
    api<Principal>('/api/v1/institution/auth/me')
      .then(me => {
        if (!alive || !me.authenticated) return;
        setPrincipal(me);
        if (me.institutionCode) setTenantCode(me.institutionCode);
        if (me.branding) applyBrand({ ...me.branding, institutionCode: me.institutionCode, displayName: me.institutionName || me.branding.displayName });
        void loadData(me);
      })
      .catch(() => undefined);
    return () => { alive = false; };
  }, [applyBrand, loadData]);

  const filteredLearners = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase();
    if (!needle) return learners;
    return learners.filter(item => [
      item.display_name, item.class_code, item.city, item.state, item.school_name, item.learner_reference,
    ].some(value => String(value ?? '').toLocaleLowerCase().includes(needle)));
  }, [learners, search]);

  async function resolveBrand(code = tenantCode) {
    const clean = code.trim().toUpperCase();
    if (!clean) {
      setError('Enter the institution code shared by your school or college.');
      return;
    }
    setBrandLoading(true);
    setError('');
    setNotice('');
    try {
      const result = await api<Brand>(`/api/v1/institution/public/branding/${encodeURIComponent(clean)}`);
      setTenantCode(clean);
      applyBrand(result);
      setNotice('Institution identity verified. Sign in with the credentials supplied by your institution.');
    } catch (e) {
      applyBrand(null);
      setError(e instanceof Error ? e.message : 'Institution branding could not be loaded.');
    } finally {
      setBrandLoading(false);
    }
  }

  async function login(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError('');
    setNotice('');
    try {
      const result = await api<Principal>('/api/v1/institution/auth/login', {
        method: 'POST',
        body: JSON.stringify({ username, password }),
      });
      setPrincipal(result);
      setPassword('');
      if (result.institutionCode) setTenantCode(result.institutionCode);
      if (result.institutionName) applyBrand({
        ...(brand || {}),
        institutionCode: result.institutionCode,
        displayName: result.institutionName,
      });
      setNotice(result.mustChangePassword ? 'Set a new password before opening institution reports.' : 'Signed in.');
      if (!result.mustChangePassword) await loadData(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  async function changePassword(event: FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError('');
    setNotice('');
    try {
      await api('/api/v1/institution/auth/change-password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword, newPassword: nextPassword }),
      });
      setPrincipal(null);
      setCurrentPassword('');
      setNextPassword('');
      setPassword('');
      setNotice('Password changed. Sign in again with your new password.');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Password could not be changed.');
    } finally {
      setLoading(false);
    }
  }

  async function logout() {
    try { await api('/api/v1/institution/auth/logout', { method: 'POST' }); } catch {}
    setPrincipal(null);
    applyBrand(null);
    setOverview(null);
    setLearners([]);
    setCourses([]);
    setPassword('');
    setCurrentPassword('');
    setNextPassword('');
    setNotice('You are signed out.');
  }

  async function copyCode() {
    if (!principal?.joinCode) return;
    try {
      await navigator.clipboard.writeText(principal.joinCode);
      setNotice('Institution join code copied.');
    } catch {
      setNotice(`Share this institution code securely: ${principal.joinCode}`);
    }
  }

  const heading = principal?.institutionName || brand?.displayName || 'Institution portal';
  const customPrimary = brand?.themeTokens?.primary;
  const cssStyle = {
    ...(customPrimary && /^#[0-9a-f]{6}$/i.test(customPrimary) ? { ['--institution-primary' as any]: customPrimary } : {}),
  } as React.CSSProperties;

  return (
    <main className="institution-portal" style={cssStyle}>
      <header className="institution-header">
        <div className="institution-brand">
          <div className="institution-mark" aria-hidden="true">
            {brand?.logoAssetKey ? '◆' : heading.trim().slice(0, 1).toUpperCase()}
          </div>
          <div>
            <strong>{heading}</strong>
            <span>{principal ? 'Institution workspace' : 'School & college learning portal'}</span>
          </div>
        </div>
        {principal ? (
          <div className="institution-account">
            <span>{principal.adminDisplayName || principal.adminUsername}</span>
            <button type="button" className="institution-quiet-button" onClick={() => void logout()}>Sign out</button>
          </div>
        ) : (
          <button type="button" className="institution-quiet-button" onClick={() => { setLoginMode(v => !v); setError(''); }}>
            {loginMode ? 'Set institution brand' : 'Back to sign in'}
          </button>
        )}
      </header>

      {error ? <div className="institution-alert is-error" role="alert">{error}</div> : null}
      {notice ? <div className="institution-alert" role="status">{notice}</div> : null}

      {!principal ? (
        <section className="institution-auth-layout">
          <div className="institution-intro">
            <span className="institution-eyebrow">YOUR INSTITUTION · YOUR LEARNERS</span>
            <h1>One view of your learners’ progress.</h1>
            <p>Review the learners who joined through your institution, see authorised courses, and follow learning activity. Course content and student access are managed centrally.</p>
            <div className="institution-feature-grid">
              <article><strong>Read-only records</strong><span>Roster, class details and learning progress.</span></article>
              <article><strong>Authorised catalogue</strong><span>Only courses approved for your institution.</span></article>
              <article><strong>Institution code</strong><span>Share your code so learners can join your institution.</span></article>
            </div>
          </div>
          <div className="institution-auth-card">
            <div className="institution-auth-toggle">
              <button type="button" className={loginMode ? 'is-active' : ''} onClick={() => setLoginMode(true)}>Sign in</button>
              <button type="button" className={!loginMode ? 'is-active' : ''} onClick={() => setLoginMode(false)}>Identify institution</button>
            </div>
            {loginMode ? (
              <form onSubmit={login}>
                <h2>Institution sign in</h2>
                <p>Use the administrator username and initial password provided by the platform owner.</p>
                <label>Institution code (optional)
                  <input value={tenantCode} onChange={e => setTenantCode(e.target.value.toUpperCase())} maxLength={32} autoComplete="organization" placeholder="e.g. SCHOOL-01" />
                </label>
                <label>Username
                  <input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" required />
                </label>
                <label>Password
                  <input value={password} onChange={e => setPassword(e.target.value)} type="password" autoComplete="current-password" required />
                </label>
                <button className="institution-primary-button" disabled={loading} type="submit">{loading ? 'Signing in…' : 'Sign in securely'}</button>
                <p className="institution-small-note">Institution administrators cannot create accounts, add staff, or edit/publish course content.</p>
              </form>
            ) : (
              <form onSubmit={event => { event.preventDefault(); void resolveBrand(); }}>
                <h2>Identify your institution</h2>
                <p>Enter the official join code to load your school or college identity before sign-in.</p>
                <label>Institution code
                  <input value={tenantCode} onChange={e => setTenantCode(e.target.value.toUpperCase())} maxLength={32} autoComplete="organization" required placeholder="Enter the code shared by the platform owner" />
                </label>
                <button className="institution-primary-button" disabled={brandLoading} type="submit">{brandLoading ? 'Checking…' : 'Load institution identity'}</button>
              </form>
            )}
          </div>
        </section>
      ) : principal.mustChangePassword ? (
        <section className="institution-auth-card institution-password-card">
          <span className="institution-eyebrow">FIRST SIGN-IN</span>
          <h1>Protect your administrator account.</h1>
          <p>Choose a password at least 12 characters long. Your initial password will stop working after it is changed.</p>
          <form onSubmit={changePassword}>
            <label>Current password<input type="password" autoComplete="current-password" value={currentPassword} onChange={e => setCurrentPassword(e.target.value)} required /></label>
            <label>New password<input type="password" autoComplete="new-password" minLength={12} value={nextPassword} onChange={e => setNextPassword(e.target.value)} required /></label>
            <button className="institution-primary-button" disabled={loading} type="submit">Change password</button>
          </form>
        </section>
      ) : (
        <section className="institution-dashboard">
          <div className="institution-page-heading">
            <div><span className="institution-eyebrow">INSTITUTION REPORTS</span><h1>{heading}</h1><p>View-only insight into the learners and courses linked to this institution.</p></div>
            <button type="button" className="institution-code-button" onClick={() => void copyCode()} disabled={!principal.joinCode}>
              <span>Institution join code</span><strong>{principal.joinCode || 'Not available'}</strong><small>{principal.joinCode ? 'Copy and share with learners' : 'Ask the platform owner to issue a code'}</small>
            </button>
          </div>
          <nav className="institution-tabs" aria-label="Institution report sections">
            {([['overview','Overview'],['learners','Learners'],['courses','Authorised courses']] as const).map(([id,label]) =>
              <button type="button" key={id} className={activeView===id?'is-active':''} onClick={()=>setActiveView(id)}>{label}</button>
            )}
            <button type="button" className="institution-refresh" onClick={()=>principal&&void loadData(principal)} disabled={loading}>{loading?'Refreshing…':'Refresh data'}</button>
          </nav>
          {activeView === 'overview' ? (
            <div className="institution-section">
              <div className="institution-metrics">
                <article><span>Active learners</span><strong>{numberText(overview?.active_learners)}</strong><small>Active membership in this institution</small></article>
                <article><span>All memberships</span><strong>{numberText(overview?.all_memberships)}</strong><small>Includes historical records</small></article>
                <article><span>Authorised courses</span><strong>{numberText(overview?.authorized_courses)}</strong><small>Published courses allowed for this institution</small></article>
                <article><span>Completed lessons</span><strong>{numberText(overview?.completed_lessons)}</strong><small>Completed learner-lesson records in authorised courses</small></article>
              </div>
              <div className="institution-panel institution-summary-panel">
                <div><h2>Learning activity</h2><p>Counts reflect recorded lesson activity from active learners and institution-authorised published content.</p></div>
                <div className="institution-summary-row"><span>Lessons with activity</span><strong>{numberText(overview?.lessons_with_activity)}</strong></div>
                <div className="institution-summary-row"><span>Billable enrollment events this month</span><strong>{numberText(overview?.current_period_billable_events)}</strong></div>
                <p className="institution-small-note">Billing totals are shown only when a platform billing contract is configured. Enrollment counts in this portal are informational; contractual invoices are controlled by the platform owner.</p>
              </div>
            </div>
          ) : activeView === 'learners' ? (
            <div className="institution-panel">
              <div className="institution-table-heading"><div><h2>Learner records</h2><p>Only learners linked to this institution are listed.</p></div>
                <input aria-label="Search learners" value={search} onChange={e=>setSearch(e.target.value)} placeholder="Search name, class, city…" /></div>
              <div className="institution-table-scroll">
                <table><thead><tr><th>Learner</th><th>Class</th><th>Location</th><th>Membership</th><th>Progress</th></tr></thead>
                  <tbody>
                    {filteredLearners.map((learner,index)=><tr key={learner.learner_reference || `${learner.display_name}-${index}`}>
                      <td><strong>{learner.display_name || '—'}</strong><small>{learner.learner_reference || ''}</small></td>
                      <td>{learner.class_code || '—'}<small>{learner.board || ''}</small></td>
                      <td>{[learner.city,learner.state].filter(Boolean).join(', ') || 'Not provided'}</td>
                      <td><span className={learner.membership_status==='ACTIVE'?'institution-status is-active':'institution-status'}>{learner.membership_status || '—'}</span><small>Joined {dateText(learner.joined_at)}</small></td>
                      <td><strong>{numberText(learner.average_lesson_progress_percent)}%</strong><small>{numberText(learner.completed_lessons)} completed / {numberText(learner.lessons_with_activity)} active</small></td>
                    </tr>)}
                    {!filteredLearners.length ? <tr><td colSpan={5} className="institution-empty-cell">{loading?'Loading learner records…':'No learner records match this search.'}</td></tr>:null}
                  </tbody>
                </table>
              </div>
              <p className="institution-small-note">This profile contains city/state only, not an exact home address. Contact details and any additional sensitive fields are intentionally not exposed in the institution roster.</p>
            </div>
          ) : (
            <div className="institution-panel">
              <div className="institution-table-heading"><div><h2>Authorised courses</h2><p>Courses and lessons approved centrally for this institution.</p></div></div>
              <div className="institution-course-grid">
                {courses.map(course=><article key={course.code}>
                  <span className="institution-course-mark">▤</span><div><h3>{course.display_name || course.code}</h3><p>{course.code || ''}</p></div>
                  <div className="institution-course-meta"><span>Published lessons</span><strong>{numberText(course.published_lessons)}</strong><span>Institution learners</span><strong>{numberText(course.institution_learners)}</strong></div>
                  <small>Access is controlled by the platform owner.</small>
                </article>)}
                {!courses.length ? <p className="institution-empty-cell">{loading?'Loading authorised courses…':'No courses have been authorised for this institution yet.'}</p>:null}
              </div>
            </div>
          )}
        </section>
      )}
      <footer className="institution-footer"><span>{principal?.institutionName || brand?.displayName || 'Institution learning services'}</span><span>Student access and course content are centrally governed.</span></footer>

      <style jsx>{`
        .institution-portal{--institution-primary:#f97316;--institution-accent:#c2410c;--institution-background:#f7f8fc;--institution-surface:#fff;--institution-text:#152038;min-height:100vh;background:var(--institution-background);color:var(--institution-text);font-family:Arial,Helvetica,sans-serif;padding-bottom:30px}
        .institution-header{min-height:76px;display:flex;justify-content:space-between;align-items:center;gap:16px;padding:13px clamp(18px,4vw,52px);background:var(--institution-surface);border-bottom:1px solid #e4e8ef}
        .institution-brand{display:flex;align-items:center;gap:12px;min-width:0}.institution-mark{width:42px;height:42px;flex:0 0 42px;border-radius:13px;background:var(--institution-primary);display:grid;place-items:center;color:#fff;font-weight:900;font-size:22px}.institution-brand strong,.institution-brand span{display:block;overflow-wrap:anywhere}.institution-brand strong{font-size:17px}.institution-brand span{font-size:10px;color:#777f91;margin-top:4px}.institution-account{display:flex;align-items:center;gap:13px;font-size:12px}.institution-quiet-button{border:1px solid #d9deea;border-radius:10px;background:#fff;padding:10px 13px;color:#33415a;font-size:11px;font-weight:800;cursor:pointer}
        .institution-alert{max-width:1180px;margin:16px auto 0;padding:11px 15px;border:1px solid #b7e2cf;border-radius:11px;background:#effaf4;color:#166748;font-size:12px;line-height:1.5}.institution-alert.is-error{border-color:#f2c2ba;background:#fff2ef;color:#a53629}
        .institution-auth-layout{max-width:1180px;margin:0 auto;padding:clamp(30px,6vw,78px) clamp(18px,3vw,28px);display:grid;grid-template-columns:minmax(0,1.1fr) minmax(320px,.72fr);align-items:center;gap:clamp(25px,5vw,70px)}
        .institution-intro h1{max-width:690px;font-size:clamp(36px,5.5vw,62px);letter-spacing:-2.8px;line-height:1.04;margin:22px 0 18px}.institution-intro>p{max-width:650px;color:#667187;font-size:15px;line-height:1.8}.institution-eyebrow{font-size:10px;letter-spacing:.15em;color:var(--institution-accent);font-weight:900}
        .institution-feature-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;margin-top:35px}.institution-feature-grid article{padding:15px;border-radius:14px;background:#fff;border:1px solid #e5e8ef}.institution-feature-grid strong,.institution-feature-grid span{display:block}.institution-feature-grid strong{font-size:11px}.institution-feature-grid span{margin-top:7px;font-size:10px;line-height:1.6;color:#768095}
        .institution-auth-card{width:100%;background:#fff;border:1px solid #e1e5ed;border-radius:22px;padding:clamp(20px,3vw,30px);box-shadow:0 18px 60px #1828420d}.institution-auth-card h2{font-size:22px;letter-spacing:-.5px;margin:20px 0 7px}.institution-auth-card>p,.institution-auth-card form>p{color:#6f788c;font-size:12px;line-height:1.65}.institution-auth-card form{display:grid;gap:10px}.institution-auth-card label{display:grid;gap:7px;margin-top:10px;font-size:11px;font-weight:800}.institution-auth-card input,.institution-table-heading input{width:100%;min-height:43px;max-width:100%;border:1px solid #d7dce7;border-radius:10px;padding:11px 12px;outline-color:var(--institution-primary);background:#fff;color:#152038;font:inherit}.institution-primary-button{width:100%;min-height:45px;margin-top:12px;border:0;border-radius:11px;background:var(--institution-primary);color:#fff;font-weight:850;cursor:pointer}.institution-primary-button:disabled{opacity:.65;cursor:wait}.institution-small-note{color:#747e91!important;font-size:10px!important;line-height:1.65!important;margin:12px 0 0}.institution-auth-toggle{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:5px;background:#f4f5f9;border:1px solid #e7e9ef;border-radius:11px;padding:4px}.institution-auth-toggle button{border:0;background:transparent;min-height:36px;border-radius:8px;font-size:10px;font-weight:800;color:#6e788d;cursor:pointer}.institution-auth-toggle button.is-active{background:#fff;box-shadow:0 1px 4px #15203812;color:var(--institution-accent)}.institution-password-card{max-width:470px;margin:55px auto}.institution-password-card h1{font-size:30px;line-height:1.2;letter-spacing:-1px}.institution-password-card form{display:grid;gap:10px}
        .institution-dashboard{max-width:1240px;margin:0 auto;padding:34px clamp(16px,3vw,30px) 55px}.institution-page-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:22px;margin-bottom:25px}.institution-page-heading h1{font-size:clamp(25px,3.2vw,38px);letter-spacing:-1.5px;margin:7px 0}.institution-page-heading p{color:#6e788d;font-size:12px;margin:0}.institution-code-button{display:grid;gap:5px;text-align:left;min-width:220px;max-width:330px;padding:14px 16px;border:1px solid #f3cfb1;border-radius:14px;background:#fff8f2;cursor:pointer}.institution-code-button span,.institution-code-button small{font-size:9px;color:#84563b}.institution-code-button strong{font-size:16px;overflow-wrap:anywhere;color:#9a3412}.institution-tabs{display:flex;align-items:center;gap:8px;flex-wrap:wrap;border-bottom:1px solid #e0e5ee;margin-bottom:23px}.institution-tabs button{border:0;border-bottom:3px solid transparent;background:transparent;padding:12px 14px;margin-bottom:-1px;color:#667187;font-size:11px;font-weight:800;cursor:pointer}.institution-tabs button.is-active{border-color:var(--institution-primary);color:var(--institution-accent)}.institution-tabs .institution-refresh{margin-left:auto;border:1px solid #d9deea;border-radius:9px;margin-bottom:8px;padding:9px 12px}.institution-section{display:grid;gap:16px}.institution-metrics{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:13px}.institution-metrics article{padding:20px;background:#fff;border:1px solid #e4e8ef;border-radius:16px}.institution-metrics span,.institution-metrics small{display:block;color:#7b8496;font-size:10px;line-height:1.5}.institution-metrics strong{display:block;font-size:30px;margin:10px 0 8px;letter-spacing:-1px}.institution-panel{padding:clamp(16px,2.8vw,24px);background:#fff;border:1px solid #e4e8ef;border-radius:17px;min-width:0}.institution-summary-panel h2,.institution-table-heading h2{font-size:16px;margin:0 0 6px}.institution-summary-panel p,.institution-table-heading p{color:#778095;font-size:11px;line-height:1.65;margin:0}.institution-summary-row{display:flex;align-items:center;justify-content:space-between;gap:15px;padding:14px 0;border-bottom:1px solid #edf0f4;font-size:11px}.institution-summary-row strong{font-size:15px}.institution-table-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:18px;margin-bottom:15px}.institution-table-heading input{max-width:270px;min-width:190px;min-height:38px;font-size:11px}.institution-table-scroll{max-width:100%;overflow:auto;border:1px solid #e5e8ef;border-radius:12px}table{width:100%;border-collapse:collapse;min-width:710px;text-align:left;font-size:11px}th{background:#f8f9fc;color:#7c8597;font-size:9px;text-transform:uppercase;letter-spacing:.06em}th,td{padding:13px 14px;border-bottom:1px solid #edf0f4;vertical-align:top}td strong,td small{display:block}td small{margin-top:4px;color:#8790a0;font-size:9px;line-height:1.5}.institution-status{display:inline-flex;padding:4px 7px;border-radius:999px;background:#f4f5f8;color:#657085;font-size:9px;font-weight:800}.institution-status.is-active{background:#ecfdf3;color:#067647}.institution-empty-cell{text-align:center;padding:25px;color:#7b8496}.institution-course-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:13px}.institution-course-grid article{display:grid;grid-template-columns:36px 1fr;gap:8px 11px;border:1px solid #e7eaf1;border-radius:13px;padding:16px}.institution-course-mark{grid-row:span 2;display:grid;place-items:center;width:36px;height:36px;border-radius:11px;background:#fff0e5;color:var(--institution-accent);font-size:16px}.institution-course-grid h3{font-size:13px;margin:0 0 5px}.institution-course-grid p{font-size:9px;color:#7b8496;margin:0}.institution-course-meta{grid-column:1/-1;display:grid;grid-template-columns:1fr auto;gap:8px;font-size:10px;color:#727c90;padding-top:9px;border-top:1px solid #edf0f4}.institution-course-meta strong{color:#152038}.institution-course-grid article>small{grid-column:1/-1;color:#7b8496;font-size:9px}.institution-footer{max-width:1240px;margin:0 auto;padding:20px clamp(16px,3vw,30px);border-top:1px solid #e2e7ee;display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;color:#7b8496;font-size:9px}
        @media(max-width:850px){.institution-auth-layout{grid-template-columns:1fr;max-width:720px}.institution-auth-card{max-width:550px}.institution-feature-grid{grid-template-columns:1fr}.institution-metrics{grid-template-columns:repeat(2,minmax(0,1fr))}.institution-page-heading{flex-direction:column}.institution-code-button{max-width:100%;width:100%}}
        @media(max-width:580px){.institution-header{align-items:flex-start;flex-direction:column}.institution-account{width:100%;justify-content:space-between}.institution-intro h1{font-size:38px}.institution-metrics,.institution-course-grid{grid-template-columns:1fr}.institution-table-heading{flex-direction:column}.institution-table-heading input{width:100%;max-width:none;min-width:0}.institution-tabs .institution-refresh{margin-left:0}.institution-footer{flex-direction:column}}
      `}</style>
    </main>
  );
}
