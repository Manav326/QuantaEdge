'use client';


import { apiFetch } from './apiClient';
import Link from 'next/link';
import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useLocale } from './LanguageProvider';
import { translateUi } from '../i18n/catalog';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import StudentAccountMenu from './StudentAccountMenu';
import ParentAccountMenu from './ParentAccountMenu';
import { NotificationBell } from './NotificationInboxClient';
import { AppearanceModeButton } from './ColorThemeProvider';
import { useTenantBranding } from './TenantBrandingProvider';

type Item = { href: string; message: string; icon: string; match?: string };
type StudentNavLink = { href: string; message: string; icon: string };
type StudentNavGroup = {
 id: 'today' | 'courses' | 'learn' | 'practice' | 'progress' | 'library'; message: string; mobileMessage: string;
 href?: string; icon: string; children?: StudentNavLink[];
};
type WorkspaceProfile = { display_name?: string | null; class_code?: string | number | null; profile_image_url?: string | null; mobile_e164?: string | null };

const studentNavGroups: StudentNavGroup[] = [
 { id: 'today', href: '/student', message: 'web.navigation.student.today', mobileMessage: 'web.navigation.student.mobileToday', icon: '⌂' },
 { id: 'courses', message: 'web.navigation.student.coursesMarketplace', mobileMessage: 'web.navigation.student.mobileCourses', icon: '✦', children: [
  { href: '/student/courses', message: 'web.navigation.student.allCourses', icon: '▦' },
  { href: '/student/marketplace', message: 'web.navigation.student.marketplace', icon: '₹' },
 ] },
 { id: 'learn', message: 'web.navigation.student.learn', mobileMessage: 'web.navigation.student.mobileLearn', icon: '▤', children: [
  { href: '/student/learn', message: 'web.navigation.student.learnTopics', icon: '▤' },
  { href: '/student/structured-lessons', message: 'web.navigation.student.structuredLessons', icon: '✧' },
  { href: '/student/classes', message: 'web.navigation.student.liveClasses', icon: '◷' },
 ] },
 { id: 'practice', message: 'web.navigation.student.practiceTests', mobileMessage: 'web.navigation.student.mobilePractice', icon: '✦', children: [
  { href: '/student/practice', message: 'web.navigation.student.practice', icon: '✦' },
  { href: '/student/tests', message: 'web.navigation.student.testsResults', icon: '✓' },
  { href: '/student/diagnostic', message: 'web.navigation.student.diagnostic', icon: '◎' },
 ] },
 { id: 'progress', message: 'web.navigation.student.progressCertificates', mobileMessage: 'web.navigation.student.mobileProgress', icon: '↗', children: [
  { href: '/student/progress', message: 'web.navigation.student.myProgress', icon: '↗' },
  { href: '/student/certificates', message: 'web.navigation.student.certificates', icon: '🏅' },
 ] },
 { id: 'library', href: '/student/textbooks', message: 'web.navigation.student.books', mobileMessage: 'web.navigation.student.mobileBooks', icon: '▣' },
];
const parentItems: Item[] = [
 { href: '/parent', message: 'web.navigation.parent.familyReport', icon: '⌂', match: '/parent' },
 { href: '/parent/courses', message: 'web.navigation.parent.familyCourses', icon: '✦' },
 { href: '/parent/children', message: 'web.navigation.parent.childrenProfiles', icon: '♧' },
 { href: '/parent/profile', message: 'web.navigation.parent.profile', icon: '◉' },
];

export default function WorkspaceLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { locale } = useLocale();
  const { brand, enrollmentIssue, clearEnrollmentIssue, retryEnrollment } = useTenantBranding();
  const t = (key: string) => translateUi(locale, key);
  const parentLearningRoute = pathname === '/parent/learning' || pathname.startsWith('/parent/learning/');
  const role = pathname.startsWith('/student') || parentLearningRoute
    ? 'student'
    : pathname.startsWith('/parent') ? 'parent' : null;
  const items = parentItems;
  const itemLabel = (item: { message: string }) => t(item.message);
  const showWorkspaceBack = role !== null
    && pathname !== '/student'
    && pathname !== '/parent'
    && !(parentLearningRoute && pathname === '/parent/learning');

  function goBackWithinWorkspace() {
    const params = new URLSearchParams(window.location.search);

    // A lesson's route keeps the same pathname; remove only its lesson ID to restore
    // the Lessons and Topics list and retain its course/subject filter.
    if (pathname === '/student/learn' && params.has('lessonId')) {
      params.delete('lessonId');
      if (!params.has('subjectCode')) params.set('subjectCode', 'ALL');
      router.push('/student/learn' + (params.size ? '?' + params.toString() : ''));
      return;
    }

    if (pathname === '/student/certificates' && params.has('certificateId')) {
      params.delete('certificateId');
      router.push('/student/certificates' + (params.size ? '?' + params.toString() : ''));
      return;
    }

    // Honour an explicit in-app return link (for example, a protected textbook reader).
    const returnTo = params.get('returnTo');
    if (returnTo && (returnTo.startsWith('/student/') || returnTo.startsWith('/parent/'))
        && !returnTo.startsWith('//')) {
      router.push(returnTo);
      return;
    }

    let sameOriginReferrer = false;
    try {
      sameOriginReferrer = Boolean(document.referrer)
        && new URL(document.referrer).origin === window.location.origin;
    } catch {
      sameOriginReferrer = false;
    }
    if (sameOriginReferrer && window.history.length > 1) {
      router.back();
      return;
    }

    router.push(parentLearningRoute ? '/parent/learning' : role === 'parent' ? '/parent' : '/student');
  }
  const enrollmentIssueText = enrollmentIssue === 'invalid'
    ? locale === 'english'
      ? 'This invitation is expired, revoked, or no longer valid. Ask your school for a new learning link. Your enrollment has not been confirmed by this invitation.'
      : locale === 'hinglish'
        ? 'School invitation ab valid nahi hai. Apne school se naya learning link lein. Is invitation se enrollment confirm nahi hua.'
        : 'यह विद्यालय आमंत्रण समाप्त या अमान्य है। अपने विद्यालय से नया लिंक लें। इस आमंत्रण से नामांकन की पुष्टि नहीं हुई।'
    : enrollmentIssue === 'rate-limited'
      ? locale === 'english'
        ? 'There have been too many attempts to use this invitation. Wait a little before trying again, or contact your school.'
        : locale === 'hinglish'
          ? 'Is invitation par bahut attempts hue hain. Thodi der baad dobara try karein ya school se sampark karein.'
          : 'इस आमंत्रण पर बहुत अधिक प्रयास हुए हैं। कुछ देर बाद फिर कोशिश करें या विद्यालय से संपर्क करें।'
      : enrollmentIssue === 'mismatch'
        ? locale === 'english'
          ? 'This invitation did not match the school shown here. Open the exact learning link sent by your school.'
          : locale === 'hinglish'
            ? 'Invitation is school se match nahi hua. School ka bheja hua exact learning link kholein.'
            : 'यह आमंत्रण यहाँ दिखाए गए विद्यालय से मेल नहीं खाता। विद्यालय द्वारा भेजा गया सही लिंक खोलें।'
        : locale === 'english'
          ? 'We could not confirm school enrollment because the request did not finish reliably. Reopen the official school link or contact your school; retrying the same invitation is safe.'
          : locale === 'hinglish'
            ? 'School enrollment confirm nahi ho saka kyunki request poori tarah verify nahi hui. Official school link dobara kholein ya school se sampark karein.'
            : 'अनुरोध पूरा होने की पुष्टि नहीं हो सकी, इसलिए विद्यालय में नामांकन की पुष्टि नहीं हुई। आधिकारिक विद्यालय लिंक फिर खोलें या विद्यालय से संपर्क करें।';
  const [collapsed, setCollapsed] = useState(false);
  const [profile, setProfile] = useState<WorkspaceProfile | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  const [expandedStudentGroup, setExpandedStudentGroup] = useState<string | null>(null);
  const [mobileOpenGroup, setMobileOpenGroup] = useState<string | null>(null);
  const [diagnosticAvailable, setDiagnosticAvailable] = useState(false);
  const [hasPersonalCourse, setHasPersonalCourse] = useState(false);
  const [expandedParentLearning, setExpandedParentLearning] = useState(false);
  const [expandedParentLearningGroup, setExpandedParentLearningGroup] = useState<string | null>(null);
  const [openingParentLearning, setOpeningParentLearning] = useState(false);
  const [personalLearningError, setPersonalLearningError] = useState('');

  const parentLearningNavGroups = studentNavGroups.map(group => ({
    ...group,
    href: group.href ? group.href.replace(/^\/student(?=\/|$)/, '/parent/learning') : undefined,
    children: group.children?.map(item => ({
      ...item,
      href: item.href.replace(/^\/student(?=\/|$)/, '/parent/learning'),
    })),
  }));
  const navigationGroupsForWorkspace = parentLearningRoute ? parentLearningNavGroups : studentNavGroups;
  const visibleStudentNavGroups = navigationGroupsForWorkspace.map(group => group.id === 'practice'
    ? { ...group, children: group.children?.filter(item => item.href !== (parentLearningRoute ? '/parent/learning/diagnostic' : '/student/diagnostic') || diagnosticAvailable) }
    : group);
  const visibleParentLearningNavGroups = parentLearningNavGroups.map(group => group.id === 'practice'
    ? { ...group, children: group.children?.filter(item =>
        item.href !== '/parent/learning/diagnostic' || diagnosticAvailable || (role === 'parent' && hasPersonalCourse)) }
    : group);

  useEffect(() => {
    if (!role) return;
    try { setCollapsed(window.localStorage.getItem('qe-' + role + '-sidebar-collapsed') === 'true'); } catch {}
  }, [role]);

  // Only expose the parent's personal learner navigation when this institution confirms
  // an active entitlement/enrollment for the parent's own learning account.
  useEffect(() => {
    let active = true;
    if (role !== 'parent') {
      setHasPersonalCourse(false);
      setExpandedParentLearning(false);
      setExpandedParentLearningGroup(null);
      return () => { active = false; };
    }
    const loadPersonalLearningAccess = async () => {
      try {
        const response = await apiFetch('/api/v1/auth/my-learning-access', {
          credentials: 'same-origin', cache: 'no-store',
          headers: { 'X-QE-Workspace': 'PARENT' },
        });
        const body = response.ok ? await response.json() : null;
        if (active) setHasPersonalCourse(body?.available === true);
      } catch {
        if (active) setHasPersonalCourse(false);
      }
    };
    void loadPersonalLearningAccess();
    window.addEventListener('qe:parent-courses-updated', loadPersonalLearningAccess);
    return () => {
      active = false;
      window.removeEventListener('qe:parent-courses-updated', loadPersonalLearningAccess);
    };
  }, [role, pathname]);

  // Keep the one-time diagnostic in navigation only when the recommendation service says it is due.
  useEffect(() => {
    if (role !== 'student') {
      setDiagnosticAvailable(false);
      return;
    }
    let active = true;
    (async()=>{
      try {
        const catalogue=await apiFetch('/api/v1/students/me/programs',{credentials:'same-origin',cache:'no-store',headers:{'X-QE-Workspace':'STUDENT'}});
        if(!catalogue.ok){if(active)setDiagnosticAvailable(false);return;}
        const rows=await catalogue.json();
        const enrolled=(Array.isArray(rows)?rows:[]).filter((course:any)=>course.is_enrolled===true&&!course.paused);
        let saved=0;try{saved=Number(window.localStorage.getItem('qe:student:activeCourseId')||0);}catch{}
        const selected=enrolled.find((course:any)=>Number(course.id)===saved)||enrolled[0];
        if(!selected){if(active)setDiagnosticAvailable(false);return;}
        const response=await apiFetch('/api/v1/recommendations/next?courseId='+encodeURIComponent(String(selected.id)),{
          credentials:'same-origin',cache:'no-store',headers:{'X-QE-Workspace':'STUDENT'}
        });
        const data=response.ok?await response.json():null;
        if(active)setDiagnosticAvailable(data?.kind==='DIAGNOSTIC');
      } catch {
        if(active)setDiagnosticAvailable(false);
      }
    })();
    return () => { active = false; };
  }, [role]);

  // Deep links open the relevant group automatically; students can still collapse it manually.
  useEffect(() => {
    if (role !== 'student') {
      setExpandedStudentGroup(null);
      setMobileOpenGroup(null);
      return;
    }
    const currentGroup = visibleStudentNavGroups.find(group => group.children?.some(item =>
      pathname === item.href || pathname.startsWith(item.href + '/')
    ));
    if (currentGroup) setExpandedStudentGroup(currentGroup.id);
    setMobileOpenGroup(null);
  }, [pathname, role, parentLearningRoute]);

  useEffect(() => {
    if (!mobileOpenGroup) return;
    function handleNavigationKey(event: KeyboardEvent) {
      if (event.key === 'Escape') setMobileOpenGroup(null);
    }
    window.addEventListener('keydown', handleNavigationKey);
    return () => window.removeEventListener('keydown', handleNavigationKey);
  }, [mobileOpenGroup]);

  // Keep account identity in the persistent header; refresh only when the role changes
  // or when a profile page explicitly reports a saved profile/photo.
  useEffect(() => {
    let active = true;
    if (!role) {
      setProfile(null);
      return () => { active = false; };
    }
    const endpoint = role === 'student' ? '/api/v1/students/me/profile' : '/api/v1/guardians/profile';
    const loadProfile = () => {
      apiFetch(endpoint, { credentials: 'same-origin', cache: 'no-store' })
        .then(response => response.ok ? response.json() : null)
        .then(data => {
          if (!active) return;
          if (data && typeof data === 'object') {
            const nextProfile = { ...data };
            if (typeof nextProfile.profile_image_url === 'string' && nextProfile.profile_image_url) {
              const photoPath = nextProfile.profile_image_url.split('?')[0];
              nextProfile.profile_image_url = photoPath + '?v=' + Date.now();
            }
            setProfile(nextProfile);
          } else {
            setProfile(null);
          }
        })
        .catch(() => { if (active) setProfile(null); });
    };
    loadProfile();
    window.addEventListener('qe:profile-updated', loadProfile);
    return () => {
      active = false;
      window.removeEventListener('qe:profile-updated', loadProfile);
    };
  }, [role]);


  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsed(next);
    try { window.localStorage.setItem('qe-' + role + '-sidebar-collapsed', String(next)); } catch {}
  }

  function toggleStudentGroup(groupId: string) {
    if (collapsed) {
      setCollapsed(false);
      try { window.localStorage.setItem('qe-student-sidebar-collapsed', 'false'); } catch {}
      setExpandedStudentGroup(groupId);
      return;
    }
    setExpandedStudentGroup(current => current === groupId ? null : groupId);
  }

  function routeActive(href: string) {
    if (href === '/student' || href === '/parent' || href === '/parent/learning') return pathname === href;
    return pathname === href || pathname.startsWith(href + '/');
  }

  function studentGroupActive(group: StudentNavGroup) {
    const fullGroup = visibleStudentNavGroups.find(item => item.id === group.id) ?? group;
    if (fullGroup.href && routeActive(fullGroup.href)) return true;
    return Boolean(fullGroup.children?.some(item => routeActive(item.href)));
  }

  async function openParentLearning(href = '/parent/learning') {
    if (!hasPersonalCourse || openingParentLearning) return;
    setOpeningParentLearning(true);
    setPersonalLearningError('');
    try {
      const response = await apiFetch('/api/v1/auth/my-learning-session', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'PARENT' },
        body: '{}',
        cache: 'no-store',
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.message || body.detail || 'My Learning could not be opened.');
      if (body.role !== 'STUDENT' || body.workspace !== 'STUDENT' || Number(body.studentId) <= 0) {
        throw new Error('Your personal learning session could not be confirmed.');
      }
      window.location.assign(href);
    } catch (error) {
      setPersonalLearningError(error instanceof Error ? error.message : 'My Learning could not be opened.');
      setOpeningParentLearning(false);
    }
  }

  async function logout() {
    setLoggingOut(true);
    const personalLearningActive = parentLearningRoute
      || (typeof document !== 'undefined' && document.cookie.split(';')
        .some(part => part.trim() === 'QE_PARENT_PERSONAL_LEARNING=1'));
    try {
      // A parent's learner profile uses a separate STUDENT session. When signing
      // out from either side of that paired workspace, revoke both scoped sessions.
      if (personalLearningActive) {
        await apiFetch('/api/v1/auth/logout', {
          method: 'POST', credentials: 'same-origin',
          headers: { 'X-QE-Workspace': 'STUDENT' }, cache: 'no-store',
        });
      }
      await apiFetch('/api/v1/auth/logout', {
        method: 'POST', credentials: 'same-origin',
        headers: { 'X-QE-Workspace': parentLearningRoute ? 'PARENT' : role === 'student' ? 'STUDENT' : 'PARENT' },
        cache: 'no-store',
      });
    } catch {
      // Always leave the workspace after the logout request, even if the network fails.
    } finally {
      if (personalLearningActive && typeof document !== 'undefined') {
        document.cookie = 'QE_PARENT_PERSONAL_LEARNING=; Max-Age=0; Path=/; SameSite=Strict'
          + (window.location.protocol === 'https:' ? '; Secure' : '');
      }
      router.replace(role === 'student' && !parentLearningRoute ? '/login/student' : '/login');
      router.refresh();
      setLoggingOut(false);
    }
  }

  if (!role) return <>{children}</>;

  const active = (item: Item) => item.href === '/parent'
    ? pathname === item.href || pathname === item.href + '/diagnostic'
    : pathname === item.href || pathname.startsWith(item.href + '/');
 return <div className={'qe-workspace-layout qe-' + role + '-workspace' + (collapsed ? ' is-collapsed' : '')}>
    <aside className="qe-workspace-sidebar">
      <QuantaEdgeBrand href={parentLearningRoute ? '/parent/learning' : role === 'student' ? '/student' : '/parent'} variant="compact" className="qe-workspace-sidebar-brand" />
      <button type="button" className="qe-workspace-collapse" onClick={toggleCollapsed} aria-label={t(collapsed ? 'common.navigation.expand' : 'common.navigation.collapse')} aria-expanded={!collapsed} title={t(collapsed ? 'common.navigation.expand' : 'common.navigation.collapse')}>{collapsed ? '»' : '«'}</button>
      <div className="qe-workspace-nav-scroll">
        <p className="qe-workspace-label">{t(role === 'student' ? 'web.navigation.student.yourLearning' : 'web.navigation.parent.yourFamily')}</p>
        {role === 'student' ? (
          <nav aria-label={t('web.navigation.student.label')}>
            {visibleStudentNavGroups.map(group => group.href ? (
              <Link key={group.id} href={group.href} title={collapsed ? itemLabel(group) : undefined}
                aria-current={routeActive(group.href) ? 'page' : undefined}
                onClick={event => { setMobileOpenGroup(null); if (group.href && routeActive(group.href)) event.preventDefault(); }}
                className={routeActive(group.href) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}>
                <span className="qe-workspace-icon" aria-hidden="true">{group.icon}</span>
                <span className="qe-workspace-link-label">{itemLabel(group)}</span>
              </Link>
            ) : (
              <div className="qe-workspace-nav-group" key={group.id}>
                <button type="button"
                  className={'qe-workspace-link qe-workspace-group-button' + (studentGroupActive(group) ? ' is-active' : '') + (expandedStudentGroup === group.id ? ' is-expanded' : '')}
                  title={collapsed ? itemLabel(group) : undefined}
                  aria-expanded={expandedStudentGroup === group.id}
                  aria-controls={'qe-student-group-' + group.id}
                  onClick={() => toggleStudentGroup(group.id)}>
                  <span className="qe-workspace-icon" aria-hidden="true">{group.icon}</span>
                  <span className="qe-workspace-link-label">{itemLabel(group)}</span>
                  <span className="qe-workspace-group-chevron" aria-hidden="true">⌄</span>
                </button>
                {group.children?.length ? (
                  <div className="qe-workspace-subnav" id={'qe-student-group-' + group.id} hidden={expandedStudentGroup !== group.id}>
                    {group.children.map(item => <Link key={item.href} href={item.href}
                      title={collapsed ? itemLabel(item) : undefined}
                      aria-current={routeActive(item.href) ? 'page' : undefined}
                      onClick={event => { setMobileOpenGroup(null); if (routeActive(item.href)) event.preventDefault(); }}
                      className={routeActive(item.href) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}>
                      <span className="qe-workspace-link-label">{itemLabel(item)}</span>
                    </Link>)}
                  </div>
                ) : null}
              </div>
            ))}
          </nav>
        ) : (
          <nav aria-label={t('web.navigation.parent.label')}>
            {items.map(item => <Link key={item.href} href={item.href} title={collapsed ? itemLabel(item) : undefined} aria-current={active(item) ? 'page' : undefined} onClick={event => { if (active(item)) event.preventDefault(); }} className={active(item) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}><span className="qe-workspace-icon" aria-hidden="true">{item.icon}</span><span className="qe-workspace-link-label">{itemLabel(item)}</span></Link>)}
            {hasPersonalCourse ? (
              <div className="qe-workspace-nav-group qe-parent-learning-nav-group">
                <button type="button"
                  className={'qe-workspace-link qe-workspace-group-button' + (pathname.startsWith('/parent/learning') ? ' is-active' : '') + (expandedParentLearning ? ' is-expanded' : '')}
                  title={collapsed ? (locale === 'english' ? 'My Learning' : 'मेरी पढ़ाई') : undefined}
                  aria-expanded={expandedParentLearning}
                  aria-controls="qe-parent-personal-learning-group"
                  onClick={() => setExpandedParentLearning(value => !value)}
                  disabled={openingParentLearning}>
                  <span className="qe-workspace-icon" aria-hidden="true">▤</span>
                  <span className="qe-workspace-link-label">{locale === 'english' ? 'My Learning' : locale === 'hinglish' ? 'My Learning' : 'मेरी पढ़ाई'}</span>
                  <span className="qe-workspace-group-chevron" aria-hidden="true">⌄</span>
                </button>
                <div className="qe-workspace-subnav" id="qe-parent-personal-learning-group" hidden={!expandedParentLearning}>
                  {visibleParentLearningNavGroups.length ? visibleParentLearningNavGroups.map(group => group.href ? (
                    <Link key={group.id} href={group.href} title={collapsed ? itemLabel(group) : undefined}
                      onClick={event => { event.preventDefault(); void openParentLearning(group.href); }}
                      className="qe-workspace-link">
                      <span className="qe-workspace-link-label">{itemLabel(group)}</span>
                    </Link>
                  ) : (
                    <div className="qe-workspace-nav-group" key={group.id}>
                      <button type="button"
                        className={'qe-workspace-link qe-workspace-group-button' + (expandedParentLearningGroup === group.id ? ' is-expanded' : '')}
                        aria-expanded={expandedParentLearningGroup === group.id}
                        aria-controls={'qe-parent-learning-' + group.id}
                        onClick={() => setExpandedParentLearningGroup(current => current === group.id ? null : group.id)}>
                        <span className="qe-workspace-link-label">{itemLabel(group)}</span>
                        <span className="qe-workspace-group-chevron" aria-hidden="true">⌄</span>
                      </button>
                      <div className="qe-workspace-subnav" id={'qe-parent-learning-' + group.id} hidden={expandedParentLearningGroup !== group.id}>
                        {group.children?.map(item => (
                          <Link key={item.href} href={item.href} title={collapsed ? itemLabel(item) : undefined}
                            onClick={event => { event.preventDefault(); void openParentLearning(item.href); }}
                            className="qe-workspace-link">
                            <span className="qe-workspace-link-label">{itemLabel(item)}</span>
                          </Link>
                        ))}
                      </div>
                    </div>
                  )) : null}
                </div>
                {personalLearningError ? <p className="qe-workspace-inline-error" role="alert">{personalLearningError}</p> : null}
                {openingParentLearning ? <p className="qe-workspace-inline-status" role="status">{locale === 'english' ? 'Opening My Learning…' : locale === 'hinglish' ? 'My Learning khul raha hai…' : 'मेरी पढ़ाई खोली जा रही है…'}</p> : null}
              </div>
            ) : null}
          </nav>
        )}
      </div>
      <div className="qe-workspace-sidebar-footer">
        <button type="button" className="qe-workspace-logout" onClick={() => void logout()} disabled={loggingOut} aria-label={t('web.workspace.logout')}>
          <span aria-hidden="true">↗</span><span>{loggingOut ? t('web.workspace.loggingOut') : t('web.workspace.logout')}</span>
        </button>
      </div>
    </aside>
    <div className="qe-workspace-content">
      <header className="qe-workspace-topbar">
        <QuantaEdgeBrand href={parentLearningRoute ? '/parent/learning' : role === 'student' ? '/student' : '/parent'} variant="compact" className="qe-workspace-topbar-brand" />
        {showWorkspaceBack ? (
          <button type="button" className="qe-parent-learning-return qe-workspace-global-back"
            onClick={goBackWithinWorkspace}
            aria-label={locale === 'english' ? 'Back' : locale === 'hinglish' ? 'Back' : 'वापस जाएँ'}>
            <span aria-hidden="true">←</span>
            <span>{locale === 'english' ? 'Back' : locale === 'hinglish' ? 'Back' : 'वापस'}</span>
          </button>
        ) : null}
        <div className="qe-workspace-topbar-actions">
          {parentLearningRoute ? (
            <button type="button" className="qe-parent-learning-return" onClick={() => router.push('/parent')}
              aria-label={locale === 'english' ? 'Return to parent workspace' : locale === 'hinglish' ? 'Parent workspace par wapas jaayein' : 'अभिभावक कार्यक्षेत्र में वापस जाएँ'}>
              <span aria-hidden="true">←</span>
              <span>{locale === 'english' ? 'Parent workspace' : locale === 'hinglish' ? 'Parent workspace' : 'अभिभावक कार्यक्षेत्र'}</span>
            </button>
          ) : null}
          <NotificationBell role={role} />
          <AppearanceModeButton />
          {role === 'student'
            ? <StudentAccountMenu displayName={profile?.display_name || t('web.workspace.studentFallbackName')} profileImageUrl={profile?.profile_image_url} onLogout={() => void logout()} loggingOut={loggingOut} />
            : <ParentAccountMenu displayName={profile?.display_name || t('web.workspace.parentFallbackName')} mobile={profile?.mobile_e164 || ''} profileImageUrl={profile?.profile_image_url} onLogout={() => void logout()} loggingOut={loggingOut} />}
        </div>
      </header>

      {role === 'student' && enrollmentIssue ? (
        <section className="qe-enrollment-issue" role="alert" aria-live="assertive">
          <div className="qe-enrollment-issue-copy">
            <strong>{brand?.displayName
              ? (locale === 'english' ? 'Enrollment with ' + brand.displayName + ' is not confirmed' : locale === 'hinglish' ? brand.displayName + ' ke saath enrollment confirm nahi hua' : brand.displayName + ' में नामांकन की पुष्टि नहीं हुई')
              : (locale === 'english' ? 'School enrollment is not confirmed' : locale === 'hinglish' ? 'School enrollment confirm nahi hua' : 'विद्यालय में नामांकन की पुष्टि नहीं हुई')}</strong>
            <p>{enrollmentIssueText}</p>
          </div>
          <div className="qe-enrollment-issue-actions">
            {enrollmentIssue === 'temporary' || enrollmentIssue === 'rate-limited' ? (
              <button type="button" className="qe-enrollment-issue-retry" onClick={retryEnrollment}>
                {locale === 'english' ? 'Retry' : locale === 'hinglish' ? 'Dobara try karein' : 'फिर प्रयास करें'}
              </button>
            ) : null}
            <button type="button" className="qe-enrollment-issue-dismiss" onClick={clearEnrollmentIssue}
              aria-label={locale === 'english' ? 'Dismiss enrollment message' : locale === 'hinglish' ? 'Message band karein' : 'संदेश बंद करें'}>×</button>
          </div>
        </section>
      ) : null}
      {children}
    </div>
    {role === 'student' ? (
      <>
        {mobileOpenGroup ? <button type="button" className="qe-workspace-mobile-nav-backdrop"
          aria-label={t('web.navigation.closeSubmenu')}
          onClick={() => setMobileOpenGroup(null)} /> : null}
        {visibleStudentNavGroups.map(group => group.children?.length ? (
          <section key={group.id} className="qe-workspace-mobile-subnav" id={'qe-mobile-subnav-' + group.id}
            hidden={mobileOpenGroup !== group.id} role="region" aria-label={itemLabel(group)}>
            <div className="qe-workspace-mobile-subnav-heading">
              <strong>{itemLabel(group)}</strong>
              <button type="button" onClick={() => setMobileOpenGroup(null)}
                aria-label={t('web.navigation.closeSubmenu')}>×</button>
            </div>
            <div className="qe-workspace-mobile-subnav-links">
              {group.children.map(item => <Link key={item.href} href={item.href}
                aria-current={routeActive(item.href) ? 'page' : undefined}
                className={routeActive(item.href) ? 'is-active' : ''}
                onClick={() => setMobileOpenGroup(null)}>
                <span>{itemLabel(item)}</span>
              </Link>)}
            </div>
          </section>
        ) : null)}
                <nav className="qe-workspace-mobile-nav qe-student-mobile-nav" aria-label={t('web.navigation.student.label')}>
          {visibleStudentNavGroups.map(group => group.href ? (
            <Link key={group.id} href={group.href} aria-current={routeActive(group.href) ? 'page' : undefined}
              className={routeActive(group.href) ? 'is-active' : ''} title={itemLabel(group)}
              onClick={() => setMobileOpenGroup(null)}>
              <span aria-hidden="true">{group.icon}</span><small>{t(group.mobileMessage)}</small>
            </Link>
          ) : (
            <button key={group.id} type="button" title={itemLabel(group)}
              aria-label={itemLabel(group)} aria-haspopup="true"
              aria-expanded={mobileOpenGroup === group.id}
              aria-controls={'qe-mobile-subnav-' + group.id}
              className={(studentGroupActive(group) ? 'is-active ' : '') + (mobileOpenGroup === group.id ? 'is-expanded' : '')}
              onClick={() => setMobileOpenGroup(current => current === group.id ? null : group.id)}>
              <span aria-hidden="true">{group.icon}</span><small>{t(group.mobileMessage)}</small>
            </button>
          ))}
        </nav>
      </>
    ) : (
      <nav className="qe-workspace-mobile-nav qe-parent-mobile-nav" aria-label={t('web.navigation.parent.label')}>
        {items.map(item => <Link key={item.href} href={item.href} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'is-active' : ''} title={itemLabel(item)}>
          <span aria-hidden="true">{item.icon}</span><small>{itemLabel(item)}</small>
        </Link>)}
        {hasPersonalCourse ? (
          <button type="button" title={locale === 'english' ? 'My Learning' : 'मेरी पढ़ाई'}
            onClick={() => void openParentLearning('/parent/learning')} disabled={openingParentLearning}
            className={openingParentLearning ? 'is-loading' : ''}>
            <span aria-hidden="true">▤</span>
            <small>{locale === 'english' ? 'My Learning' : locale === 'hinglish' ? 'My Learning' : 'मेरी पढ़ाई'}</small>
          </button>
        ) : null}
      </nav>
    )}
  </div>;
}
