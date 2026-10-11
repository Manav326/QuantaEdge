'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { LanguageSwitcher, useLocale } from './LanguageProvider';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import StudentAccountMenu from './StudentAccountMenu';
import ParentAccountMenu from './ParentAccountMenu';

type Item = { href: string; hinglish: string; english: string; icon: string; match?: string };
type WorkspaceProfile = {
  display_name?: string | null;
  class_code?: string | number | null;
  profile_image_url?: string | null;
  mobile_e164?: string | null;
};
const studentItems: Item[] = [
  { href: '/student', hinglish: 'आज की योजना', english: "Today's plan", icon: '⌂', match: '/student' },
  { href: '/student/learn', hinglish: 'पढ़ाई', english: 'Learning', icon: '▤' },
  { href: '/student/practice', hinglish: 'अभ्यास', english: 'Practice', icon: '✦' },
  { href: '/student/progress', hinglish: 'मेरी प्रगति', english: 'My progress', icon: '↗' },
  { href: '/student/textbooks', hinglish: 'किताबें', english: 'Textbooks', icon: '▤' },
  { href: '/student/tests', hinglish: 'Tests & results', english: 'Tests & results', icon: '✓' },
  { href: '/student/classes', hinglish: 'Live classes', english: 'Live & recorded classes', icon: '◷' },
  { href: '/student/profile', hinglish: 'Profile', english: 'Profile', icon: '◉' },
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
  const items = role === 'student' ? studentItems : parentItems;
  const itemLabel = (item: Item) => locale === 'english' ? item.english : item.hinglish;
  const [collapsed, setCollapsed] = useState(false);
  const [profile, setProfile] = useState<WorkspaceProfile | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);

  useEffect(() => {
    if (!role) return;
    try { setCollapsed(window.localStorage.getItem('qe-' + role + '-sidebar-collapsed') === 'true'); } catch {}
  }, [role]);

  // Keep account identity in the persistent header; refresh only when the role changes
  // or when a profile page explicitly reports a saved profile/photo.
  useEffect(() => {
    let active = true;
    if (!role) {
      setProfile(null);
      return () => { active = false; };
    }
    const endpoint = role === 'student' ? '/api/v1/students/me' : '/api/v1/guardians/profile';
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

  const active = (item: Item) => item.href === '/student' || item.href === '/parent'
    ? pathname === item.href || pathname === item.href + '/diagnostic'
    : pathname === item.href || pathname.startsWith(item.href + '/');
 return <div className={'qe-workspace-layout qe-' + role + '-workspace' + (collapsed ? ' is-collapsed' : '')}>
    <aside className="qe-workspace-sidebar">
      <QuantaEdgeBrand href={role === 'student' ? '/student' : '/parent'} variant="compact" className="qe-workspace-sidebar-brand" />
      <button type="button" className="qe-workspace-collapse" onClick={toggleCollapsed} aria-label={collapsed ? (locale === 'english' ? 'Expand navigation' : 'Navigation खोलें') : (locale === 'english' ? 'Collapse navigation' : 'Navigation समेटें')} aria-expanded={!collapsed} title={collapsed ? (locale === 'english' ? 'Expand navigation' : 'Navigation खोलें') : (locale === 'english' ? 'Collapse navigation' : 'Navigation समेटें')}>{collapsed ? '»' : '«'}</button>
      <div className="qe-workspace-nav-scroll">
        <p className="qe-workspace-label">{role === 'student' ? (locale === 'english' ? 'YOUR LEARNING' : 'आपकी पढ़ाई') : (locale === 'english' ? 'YOUR FAMILY' : 'आपका परिवार')}</p>
        <nav aria-label={role === 'student' ? 'Student navigation' : 'Parent navigation'}>
          {items.map(item => <Link key={item.href} href={item.href} title={collapsed ? itemLabel(item) : undefined} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}><span className="qe-workspace-icon" aria-hidden="true">{item.icon}</span><span className="qe-workspace-link-label">{itemLabel(item)}</span></Link>)}
        </nav>
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
            ? <StudentAccountMenu displayName={profile?.display_name || 'Student'} classCode={profile?.class_code ?? '—'} profileImageUrl={profile?.profile_image_url} />
            : <ParentAccountMenu displayName={profile?.display_name || 'Parent account'} mobile={profile?.mobile_e164 || ''} profileImageUrl={profile?.profile_image_url} />}
          <LanguageSwitcher compact />
        </div>
      </header>
      {children}
    </div>
    <nav className="qe-workspace-mobile-nav" aria-label={role === 'student' ? 'Student navigation' : 'Parent navigation'}>
      {items.map(item => <Link key={item.href} href={item.href} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'is-active' : ''} title={itemLabel(item)}>
        <span aria-hidden="true">{item.icon}</span><small>{itemLabel(item)}</small>
      </Link>)}
      <button type="button" onClick={() => void logout()} disabled={loggingOut} aria-label={locale === 'english' ? 'Log out' : 'लॉग आउट'}>
        <span aria-hidden="true">↗</span><small>{loggingOut ? '…' : (locale === 'english' ? 'Logout' : 'लॉग आउट')}</small>
      </button>
    </nav>
  </div>;
}
