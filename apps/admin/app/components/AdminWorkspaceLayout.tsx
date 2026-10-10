'use client';

import Link from 'next/link';
import { useEffect, useMemo, useState } from 'react';
import { usePathname } from 'next/navigation';
import AdminSidebar, { type AdminSection } from './AdminSidebar';
import { AdminWorkspaceContext, type AdminLocale } from './AdminWorkspaceContext';

type StaffSession = { role?: string; staffId?: number; displayName?: string; permissions?: string[] };
type NavItem = { key: AdminSection; href: string; icon: string; english: string; hinglish: string };

const navItems: NavItem[] = [
  { key: 'overview', href: '/', icon: '▦', english: 'Overview', hinglish: 'Overview' },
  { key: 'content', href: '/content', icon: '◈', english: 'Content Studio', hinglish: 'Content Studio' },
  { key: 'questions', href: '/questions', icon: '✓', english: 'Question review', hinglish: 'MCQ समीक्षा' },
  { key: 'resources', href: '/resources', icon: '▤', english: 'Textbook library', hinglish: 'Textbook library' },
  { key: 'sources', href: '/source-ingestion', icon: '⇣', english: 'Source ingestion', hinglish: 'Source जोड़ें' },
  { key: 'assessments', href: '/assessments', icon: '✓', english: 'Tests & grading', hinglish: 'Tests & grading' },
  { key: 'classes', href: '/classes', icon: '◷', english: 'Live & recorded classes', hinglish: 'Live classes' },
  { key: 'students', href: '/students', icon: '◉', english: 'Students', hinglish: 'Students' },
  { key: 'parents', href: '/parents', icon: '♧', english: 'Parents & families', hinglish: 'Parents & families' },
  { key: 'staff', href: '/employees', icon: '♙', english: 'Staff & audit', hinglish: 'Staff & audit' },
];

function activeSection(pathname: string): AdminSection {
  if (pathname === '/') return 'overview';
  if (pathname.startsWith('/content') || pathname.startsWith('/legacy-content')) return 'content';
  if (pathname.startsWith('/questions')) return 'questions';
  if (pathname.startsWith('/resources')) return 'resources';
  if (pathname.startsWith('/source-ingestion')) return 'sources';
  if (pathname.startsWith('/assessments')) return 'assessments';
  if (pathname.startsWith('/classes')) return 'classes';
  if (pathname.startsWith('/students')) return 'students';
  if (pathname.startsWith('/parents')) return 'parents';
  if (pathname.startsWith('/employees')) return 'staff';
  return 'overview';
}

function canSee(item: NavItem, role: string, permissions: string[]) {
  if (!role) return true;
  if (item.key === 'overview') return role === 'ADMIN';
  if (item.key === 'students' || item.key === 'parents') return role === 'ADMIN';
  if (item.key === 'content') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW');
  if (item.key === 'questions') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW') || permissions.includes('CONTENT_REVIEW');
  if (item.key === 'resources') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW') || permissions.includes('CONTENT_EDIT') || permissions.includes('CONTENT_PUBLISH');
  if (item.key === 'sources') return role === 'ADMIN' || permissions.includes('CONTENT_REVIEW');
  if (item.key === 'assessments') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW') || permissions.includes('CONTENT_CREATE') || permissions.includes('ASSESSMENT_GRADE');
  if (item.key === 'classes') return role === 'ADMIN' || permissions.includes('CONTENT_VIEW') || permissions.includes('CLASS_MANAGE');
  if (item.key === 'staff') return role === 'ADMIN' || permissions.includes('AUDIT_VIEW');
  return false;
}

export default function AdminWorkspaceLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const publicRoute = pathname === '/login' || pathname === '/session';
  const [locale, setLocale] = useState<AdminLocale>('hinglish');
  const [session, setSession] = useState<StaffSession | null>(null);

  useEffect(() => {
    try {
      const saved = window.localStorage.getItem('qe-locale');
      if (saved === 'hinglish' || saved === 'english') setLocale(saved);
    } catch { /* Keep the default locale if storage is unavailable. */ }
    const onStorage = (event: StorageEvent) => {
      if (event.key === 'qe-locale' && (event.newValue === 'hinglish' || event.newValue === 'english')) setLocale(event.newValue);
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  useEffect(() => {
    if (publicRoute) { setSession(null); return; }
    let alive = true;
    fetch('/api/v1/auth/me', { cache: 'no-store' })
      .then(response => response.ok ? response.json() : null)
      .then(me => { if (alive) setSession(me && me.staffId ? me as StaffSession : null); })
      .catch(() => { if (alive) setSession(null); });
    return () => { alive = false; };
  }, [publicRoute]);

  const value = useMemo(() => ({
    insideWorkspace: !publicRoute,
    locale,
    role: session?.role || '',
    permissions: Array.isArray(session?.permissions) ? session.permissions : [],
    displayName: session?.displayName || '',
  }), [publicRoute, locale, session]);

  function changeLocale(next: AdminLocale) {
    setLocale(next);
    if (typeof document !== 'undefined') document.documentElement.lang = next === 'hinglish' ? 'hi' : 'en';
    try { window.localStorage.setItem('qe-locale', next); } catch { /* Keep the in-memory preference. */ }
  }

  if (publicRoute) return <AdminWorkspaceContext.Provider value={value}>{children}</AdminWorkspaceContext.Provider>;

  const active = activeSection(pathname);
  const variant = active === 'overview' ? 'overview' : ['content', 'resources', 'sources', 'classes'].includes(active) ? 'content' : 'default';
  const visibleItems = navItems.filter(item => canSee(item, value.role, value.permissions));

  return <AdminWorkspaceContext.Provider value={value}>
    <div className="qe-admin-workspace-layout">
      <AdminSidebar active={active} variant={variant} displayName={session?.displayName || undefined} persistent />
      <div className="qe-admin-workspace-content">
        <header className="qe-admin-global-header">
          <div className="qe-admin-global-context">
            <span>QUANTAEDGE · LEARNING OPERATIONS</span>
            <strong>{locale === 'english' ? 'Staff workspace' : 'Staff workspace · Learning operations'}</strong>
          </div>
          <div className="qe-admin-global-actions">
            <span className="qe-admin-secure-indicator"><i />Secure workspace</span>
            <label className="qe-admin-language-switcher">
              <span aria-hidden="true">文</span><span className="qe-admin-language-label">भाषा / Language</span>
              <select value={locale} onChange={event => changeLocale(event.target.value as AdminLocale)} aria-label="भाषा चुनें / Choose language">
                <option value="hinglish">Hinglish (हिन्दी + English)</option>
                <option value="english">English</option>
              </select>
            </label>
          </div>
        </header>
        <div className="qe-admin-workspace-page-slot">{children}</div>
      </div>
      <nav className="qe-admin-mobile-nav" aria-label="Admin navigation">
        {visibleItems.map(item => <Link key={item.key} href={item.href} aria-current={active === item.key ? 'page' : undefined} className={active === item.key ? 'is-active' : ''} title={locale === 'english' ? item.english : item.hinglish}>
          <span aria-hidden="true">{item.icon}</span><small>{locale === 'english' ? item.english : item.hinglish}</small>
        </Link>)}
      </nav>
    </div>
  </AdminWorkspaceContext.Provider>;
}
