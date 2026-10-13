'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { LanguageSwitcher, LearningLanguageSwitcher, useLocale } from './LanguageProvider';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import StudentAccountMenu from './StudentAccountMenu';
import ParentAccountMenu from './ParentAccountMenu';

type Item = { href: string; hinglish: string; english: string; icon: string; match?: string };
type StudentNavLink = { href: string; hinglish: string; english: string; icon: string };
type StudentNavGroup = {
  id: 'today' | 'learn' | 'practice' | 'progress' | 'library';
  href?: string;
  hinglish: string;
  english: string;
  mobileHinglish: string;
  mobileEnglish: string;
  icon: string;
  children?: StudentNavLink[];
};
type WorkspaceProfile = {
  display_name?: string | null;
  class_code?: string | number | null;
  profile_image_url?: string | null;
  mobile_e164?: string | null;
};

const studentNavGroups: StudentNavGroup[] = [
  { id: 'today', href: '/student', hinglish: 'आज की योजना', english: "Today's plan", mobileHinglish: 'आज', mobileEnglish: 'Today', icon: '⌂' },
  {
    id: 'learn', hinglish: 'पढ़ाई', english: 'Learn', mobileHinglish: 'पढ़ाई', mobileEnglish: 'Learn', icon: '▤',
    children: [
      { href: '/student/learn', hinglish: 'Lessons और topics', english: 'Lessons & topics', icon: '▤' },
      { href: '/student/classes', hinglish: 'Live और recorded classes', english: 'Live & recorded classes', icon: '◷' },
    ],
  },
  {
    id: 'practice', hinglish: 'अभ्यास और टेस्ट', english: 'Practice & Tests', mobileHinglish: 'अभ्यास', mobileEnglish: 'Practice', icon: '✦',
    children: [
      { href: '/student/practice', hinglish: 'अभ्यास', english: 'Practice', icon: '✦' },
      { href: '/student/tests', hinglish: 'Tests और results', english: 'Tests & results', icon: '✓' },
      { href: '/student/diagnostic', hinglish: 'शुरुआती diagnostic', english: 'Initial diagnostic', icon: '◎' },
    ],
  },
  {
    id: 'progress', hinglish: 'प्रगति और प्रमाणपत्र', english: 'Progress & Certificates', mobileHinglish: 'प्रगति', mobileEnglish: 'Progress', icon: '↗',
    children: [
      { href: '/student/progress', hinglish: 'मेरी प्रगति', english: 'My progress', icon: '↗' },
      { href: '/student/certificates', hinglish: 'प्रमाणपत्र', english: 'Certificates', icon: '🏅' },
    ],
  },
  { id: 'library', href: '/student/textbooks', hinglish: 'किताबें', english: 'Textbook Library', mobileHinglish: 'किताबें', mobileEnglish: 'Books', icon: '▣' },
];

const parentItems: Item[] = [
  { href: '/parent', hinglish: 'परिवार की रिपोर्ट', english: 'Family report', icon: '⌂', match: '/parent' },
  { href: '/parent/children', hinglish: 'बच्चों के Profiles', english: 'Children’s profiles', icon: '♧' },
  { href: '/parent/profile', hinglish: 'Parent Profile', english: 'Parent profile', icon: '◉' },
];

export default function WorkspaceLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const { locale } = useLocale();
  const role = pathname.startsWith('/student') ? 'student' : pathname.startsWith('/parent') ? 'parent' : null;
  const items = parentItems;
  const itemLabel = (item: { hinglish: string; english: string }) => locale === 'english' ? item.english : item.hinglish;
  const [collapsed, setCollapsed] = useState(false);
  const [profile, setProfile] = useState<WorkspaceProfile | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  const [expandedStudentGroup, setExpandedStudentGroup] = useState<string | null>(null);
  const [mobileOpenGroup, setMobileOpenGroup] = useState<string | null>(null);
  const [diagnosticAvailable, setDiagnosticAvailable] = useState(false);
  const visibleStudentNavGroups = studentNavGroups.map(group => group.id === 'practice'
    ? { ...group, children: group.children?.filter(item => item.href !== '/student/diagnostic' || diagnosticAvailable) }
    : group);

  useEffect(() => {
    if (!role) return;
    try { setCollapsed(window.localStorage.getItem('qe-' + role + '-sidebar-collapsed') === 'true'); } catch {}
  }, [role]);

  // Keep the one-time diagnostic in navigation only when the recommendation service says it is due.
  useEffect(() => {
    if (role !== 'student') {
      setDiagnosticAvailable(false);
      return;
    }
    let active = true;
    fetch('/api/v1/recommendations/next', { credentials: 'same-origin', cache: 'no-store' })
      .then(response => response.ok ? response.json() : null)
      .then(data => { if (active) setDiagnosticAvailable(data?.kind === 'DIAGNOSTIC'); })
      .catch(() => { if (active) setDiagnosticAvailable(false); });
    return () => { active = false; };
  }, [role]);

  // Deep links open the relevant group automatically; students can still collapse it manually.
  useEffect(() => {
    if (role !== 'student') {
      setExpandedStudentGroup(null);
      setMobileOpenGroup(null);
      return;
    }
    const currentGroup = studentNavGroups.find(group => group.children?.some(item =>
      pathname === item.href || pathname.startsWith(item.href + '/')
    ));
    if (currentGroup) setExpandedStudentGroup(currentGroup.id);
    setMobileOpenGroup(null);
  }, [pathname, role]);

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
      fetch(endpoint, { credentials: 'same-origin', cache: 'no-store' })
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
    if (href === '/student' || href === '/parent') return pathname === href;
    return pathname === href || pathname.startsWith(href + '/');
  }

  function studentGroupActive(group: StudentNavGroup) {
    const fullGroup = studentNavGroups.find(item => item.id === group.id) ?? group;
    if (fullGroup.href && routeActive(fullGroup.href)) return true;
    return Boolean(fullGroup.children?.some(item => routeActive(item.href)));
  }

  async function logout() {
    setLoggingOut(true);
    try {
      await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'same-origin' });
    } catch {
      // Always leave the workspace after the logout request, even if the network fails.
    } finally {
      router.replace(role === 'student' ? '/login/student' : '/login');
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
      <QuantaEdgeBrand href={role === 'student' ? '/student' : '/parent'} variant="compact" className="qe-workspace-sidebar-brand" />
      <button type="button" className="qe-workspace-collapse" onClick={toggleCollapsed} aria-label={collapsed ? (locale === 'english' ? 'Expand navigation' : 'Navigation खोलें') : (locale === 'english' ? 'Collapse navigation' : 'Navigation समेटें')} aria-expanded={!collapsed} title={collapsed ? (locale === 'english' ? 'Expand navigation' : 'Navigation खोलें') : (locale === 'english' ? 'Collapse navigation' : 'Navigation समेटें')}>{collapsed ? '»' : '«'}</button>
      <div className="qe-workspace-nav-scroll">
        <p className="qe-workspace-label">{role === 'student' ? (locale === 'english' ? 'YOUR LEARNING' : 'आपकी पढ़ाई') : (locale === 'english' ? 'YOUR FAMILY' : 'आपका परिवार')}</p>
        {role === 'student' ? (
          <nav aria-label="Student navigation">
            {visibleStudentNavGroups.map(group => group.href ? (
              <Link key={group.id} href={group.href} title={collapsed ? itemLabel(group) : undefined}
                aria-current={routeActive(group.href) ? 'page' : undefined}
                onClick={() => setMobileOpenGroup(null)}
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
                      onClick={() => setMobileOpenGroup(null)}
                      className={routeActive(item.href) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}>
                      <span className="qe-workspace-icon" aria-hidden="true">{item.icon}</span>
                      <span className="qe-workspace-link-label">{itemLabel(item)}</span>
                    </Link>)}
                  </div>
                ) : null}
              </div>
            ))}
          </nav>
        ) : (
          <nav aria-label="Parent navigation">
            {items.map(item => <Link key={item.href} href={item.href} title={collapsed ? itemLabel(item) : undefined} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}><span className="qe-workspace-icon" aria-hidden="true">{item.icon}</span><span className="qe-workspace-link-label">{itemLabel(item)}</span></Link>)}
          </nav>
        )}
      </div>
      <div className="qe-workspace-sidebar-footer">
        <div className="qe-workspace-sidebar-foot"><span>✓</span><div><strong>{locale === 'english' ? 'Safe learning space' : 'सुरक्षित learning space'}</strong><small>{role === 'student' ? (locale === 'english' ? 'Progress saves automatically' : 'Progress अपने-आप save होती है') : (locale === 'english' ? 'Child reports are private' : 'बच्चों की reports private रहती हैं')}</small></div></div>
        <button type="button" className="qe-workspace-logout" onClick={() => void logout()} disabled={loggingOut} aria-label={locale === 'english' ? 'Log out' : 'लॉग आउट'}>
          <span aria-hidden="true">↗</span><span>{loggingOut ? (locale === 'english' ? 'Logging out…' : 'लॉग आउट…') : (locale === 'english' ? 'Logout' : 'लॉग आउट')}</span>
        </button>
      </div>
    </aside>
    <div className="qe-workspace-content">
      <header className="qe-workspace-topbar">
        <QuantaEdgeBrand href={role === 'student' ? '/student' : '/parent'} variant="compact" className="qe-workspace-topbar-brand" />
        <div className="qe-workspace-topbar-actions">
          {role === 'student'
            ? <StudentAccountMenu displayName={profile?.display_name || 'Student'} classCode={profile?.class_code ?? '—'} profileImageUrl={profile?.profile_image_url} onLogout={() => void logout()} loggingOut={loggingOut} />
            : <ParentAccountMenu displayName={profile?.display_name || 'Parent account'} mobile={profile?.mobile_e164 || ''} profileImageUrl={profile?.profile_image_url} onLogout={() => void logout()} loggingOut={loggingOut} />}
          {role === 'student' ? <LearningLanguageSwitcher compact /> : null}
          <LanguageSwitcher compact />
        </div>
      </header>
      {children}
    </div>
    {role === 'student' ? (
      <>
        {mobileOpenGroup ? <button type="button" className="qe-workspace-mobile-nav-backdrop"
          aria-label={locale === 'english' ? 'Close navigation submenu' : 'Navigation submenu बंद करें'}
          onClick={() => setMobileOpenGroup(null)} /> : null}
        {visibleStudentNavGroups.map(group => group.children?.length ? (
          <section key={group.id} className="qe-workspace-mobile-subnav" id={'qe-mobile-subnav-' + group.id}
            hidden={mobileOpenGroup !== group.id} role="region" aria-label={itemLabel(group)}>
            <div className="qe-workspace-mobile-subnav-heading">
              <strong>{itemLabel(group)}</strong>
              <button type="button" onClick={() => setMobileOpenGroup(null)}
                aria-label={locale === 'english' ? 'Close navigation submenu' : 'Navigation submenu बंद करें'}>×</button>
            </div>
            <div className="qe-workspace-mobile-subnav-links">
              {group.children.map(item => <Link key={item.href} href={item.href}
                aria-current={routeActive(item.href) ? 'page' : undefined}
                className={routeActive(item.href) ? 'is-active' : ''}
                onClick={() => setMobileOpenGroup(null)}>
                <span aria-hidden="true">{item.icon}</span><span>{itemLabel(item)}</span>
              </Link>)}
            </div>
          </section>
        ) : null)}
                <nav className="qe-workspace-mobile-nav qe-student-mobile-nav" aria-label="Student navigation">
          {visibleStudentNavGroups.map(group => group.href ? (
            <Link key={group.id} href={group.href} aria-current={routeActive(group.href) ? 'page' : undefined}
              className={routeActive(group.href) ? 'is-active' : ''} title={itemLabel(group)}
              onClick={() => setMobileOpenGroup(null)}>
              <span aria-hidden="true">{group.icon}</span><small>{locale === 'english' ? group.mobileEnglish : group.mobileHinglish}</small>
            </Link>
          ) : (
            <button key={group.id} type="button" title={itemLabel(group)}
              aria-label={itemLabel(group)} aria-haspopup="true"
              aria-expanded={mobileOpenGroup === group.id}
              aria-controls={'qe-mobile-subnav-' + group.id}
              className={(studentGroupActive(group) ? 'is-active ' : '') + (mobileOpenGroup === group.id ? 'is-expanded' : '')}
              onClick={() => setMobileOpenGroup(current => current === group.id ? null : group.id)}>
              <span aria-hidden="true">{group.icon}</span><small>{locale === 'english' ? group.mobileEnglish : group.mobileHinglish}</small>
            </button>
          ))}
        </nav>
      </>
    ) : (
      <nav className="qe-workspace-mobile-nav" aria-label="Parent navigation">
        {items.map(item => <Link key={item.href} href={item.href} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'is-active' : ''} title={itemLabel(item)}>
          <span aria-hidden="true">{item.icon}</span><small>{itemLabel(item)}</small>
        </Link>)}
      </nav>
    )}
  </div>;
}
