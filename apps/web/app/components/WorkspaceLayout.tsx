'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useLocale } from './LanguageProvider';
import { translateUi } from '../i18n/catalog';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import StudentAccountMenu from './StudentAccountMenu';
import ParentAccountMenu from './ParentAccountMenu';

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
  const t = (key: string) => translateUi(locale, key);
  const role = pathname.startsWith('/student') ? 'student' : pathname.startsWith('/parent') ? 'parent' : null;
  const items = parentItems;
  const itemLabel = (item: { message: string }) => t(item.message);
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
      <button type="button" className="qe-workspace-collapse" onClick={toggleCollapsed} aria-label={t(collapsed ? 'common.navigation.expand' : 'common.navigation.collapse')} aria-expanded={!collapsed} title={t(collapsed ? 'common.navigation.expand' : 'common.navigation.collapse')}>{collapsed ? '»' : '«'}</button>
      <div className="qe-workspace-nav-scroll">
        <p className="qe-workspace-label">{t(role === 'student' ? 'web.navigation.student.yourLearning' : 'web.navigation.parent.yourFamily')}</p>
        {role === 'student' ? (
          <nav aria-label={t('web.navigation.student.label')}>
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
                      <span className="qe-workspace-link-label">{itemLabel(item)}</span>
                    </Link>)}
                  </div>
                ) : null}
              </div>
            ))}
          </nav>
        ) : (
          <nav aria-label={t('web.navigation.parent.label')}>
            {items.map(item => <Link key={item.href} href={item.href} title={collapsed ? itemLabel(item) : undefined} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}><span className="qe-workspace-icon" aria-hidden="true">{item.icon}</span><span className="qe-workspace-link-label">{itemLabel(item)}</span></Link>)}
          </nav>
        )}
      </div>
      <div className="qe-workspace-sidebar-footer">
        <div className="qe-workspace-sidebar-foot"><span>✓</span><div><strong>{t('web.workspace.safeLearningSpace')}</strong><small>{t(role === 'student' ? 'web.workspace.progressSavesAutomatically' : 'web.workspace.childReportsPrivate')}</small></div></div>
        <button type="button" className="qe-workspace-logout" onClick={() => void logout()} disabled={loggingOut} aria-label={t('web.workspace.logout')}>
          <span aria-hidden="true">↗</span><span>{loggingOut ? t('web.workspace.loggingOut') : t('web.workspace.logout')}</span>
        </button>
      </div>
    </aside>
    <div className="qe-workspace-content">
      <header className="qe-workspace-topbar">
        <QuantaEdgeBrand href={role === 'student' ? '/student' : '/parent'} variant="compact" className="qe-workspace-topbar-brand" />
        <div className="qe-workspace-topbar-actions">
          {role === 'student'
            ? <StudentAccountMenu displayName={profile?.display_name || t('web.workspace.studentFallbackName')} profileImageUrl={profile?.profile_image_url} onLogout={() => void logout()} loggingOut={loggingOut} />
            : <ParentAccountMenu displayName={profile?.display_name || t('web.workspace.parentFallbackName')} mobile={profile?.mobile_e164 || ''} profileImageUrl={profile?.profile_image_url} onLogout={() => void logout()} loggingOut={loggingOut} />}
        </div>
      </header>
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
      <nav className="qe-workspace-mobile-nav" aria-label={t('web.navigation.parent.label')}>
        {items.map(item => <Link key={item.href} href={item.href} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'is-active' : ''} title={itemLabel(item)}>
          <span aria-hidden="true">{item.icon}</span><small>{itemLabel(item)}</small>
        </Link>)}
      </nav>
    )}
  </div>;
}
