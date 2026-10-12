'use client';

import Link from 'next/link';
import { useEffect, useMemo, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import AdminSidebar from './AdminSidebar';
import { AdminWorkspaceContext, type AdminLocale } from './AdminWorkspaceContext';
import {
  ADMIN_NAV_GROUPS,
  ADMIN_OVERVIEW_ITEM,
  adminNavGroupLabel,
  adminNavHref,
  adminNavLabel,
  adminNavShortLabel,
  adminRoleClass,
  adminRoleLabel,
  canSeeAdminItem,
  type AdminNavItem,
  type AdminSection,
} from './admin-navigation';

type StaffSession = { role?: string; staffId?: number; displayName?: string; permissions?: string[] };

function activeSection(pathname: string): AdminSection {
  if (pathname === '/') return 'overview';
  if (pathname.startsWith('/content') || pathname.startsWith('/legacy-content')) return 'content';
  if (pathname.startsWith('/translations')) return 'translations';
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

export default function AdminWorkspaceLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const publicRoute = pathname === '/login' || pathname === '/session';
  const [locale, setLocale] = useState<AdminLocale>('hinglish');
  const [session, setSession] = useState<StaffSession | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const mobileMoreButtonRef = useRef<HTMLButtonElement | null>(null);
  const mobileMenuWasOpen = useRef(false);
  const router = useRouter();

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

  useEffect(() => {
    setMobileMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!mobileMenuOpen) {
      if (mobileMenuWasOpen.current) mobileMoreButtonRef.current?.focus();
      mobileMenuWasOpen.current = false;
      return;
    }

    mobileMenuWasOpen.current = true;
    const previousBodyOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';

    const handleMenuKeyboard = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setMobileMenuOpen(false);
        return;
      }
      if (event.key !== 'Tab') return;

      const menu = document.getElementById('qe-admin-mobile-menu');
      if (!menu) return;
      const focusable = Array.from(menu.querySelectorAll<HTMLElement>(
        'a[href], button:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
      ));
      if (focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && (document.activeElement === first || !menu.contains(document.activeElement))) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && (document.activeElement === last || !menu.contains(document.activeElement))) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', handleMenuKeyboard);
    return () => {
      document.removeEventListener('keydown', handleMenuKeyboard);
      document.body.style.overflow = previousBodyOverflow;
    };
  }, [mobileMenuOpen]);

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

  async function logout() {
    setLoggingOut(true);
    setMobileMenuOpen(false);
    try {
      await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'same-origin' });
    } catch {
      // The UI still returns to login if the logout request cannot reach the server.
    } finally {
      router.replace('/login');
      router.refresh();
      setLoggingOut(false);
    }
  }

  if (publicRoute) return <AdminWorkspaceContext.Provider value={value}>{children}</AdminWorkspaceContext.Provider>;

  const active = activeSection(pathname);
  const variant = active === 'overview' ? 'overview' : ['content', 'resources', 'sources', 'classes'].includes(active) ? 'content' : 'default';
  const visibleOverviewItems = [ADMIN_OVERVIEW_ITEM].filter(item => canSeeAdminItem(item, value.role, value.permissions));
  const visibleGroups = ADMIN_NAV_GROUPS
    .map(group => ({ ...group, items: group.items.filter(item => canSeeAdminItem(item, value.role, value.permissions)) }))
    .filter(group => group.items.length > 0);
  const allVisibleItems: AdminNavItem[] = [...visibleOverviewItems, ...visibleGroups.flatMap(group => group.items)];

  // Keep the bottom bar focused on common destinations. All routes remain available in More.
  const quickItems: AdminNavItem[] = [];
  for (const key of ['overview', 'content', 'questions'] as AdminSection[]) {
    const item = allVisibleItems.find(candidate => candidate.key === key);
    if (item && !quickItems.some(candidate => candidate.key === item.key)) quickItems.push(item);
  }
  for (const item of allVisibleItems) {
    if (quickItems.length >= 3) break;
    if (!quickItems.some(candidate => candidate.key === item.key)) quickItems.push(item);
  }

  return <AdminWorkspaceContext.Provider value={value}>
    <div className="qe-admin-workspace-layout">
      <AdminSidebar active={active} variant={variant} displayName={session?.displayName || undefined} persistent />
      <div className="qe-admin-workspace-content">
        <header className="qe-admin-global-header">
          <div className="qe-admin-global-context">
            <span>QUANTAEDGE</span>
            <strong>Staff workspace</strong>
          </div>
          <div className="qe-admin-global-actions">
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

      {mobileMenuOpen ? (
        <>
          <button
            type="button"
            className="qe-admin-mobile-menu-backdrop"
            onClick={() => setMobileMenuOpen(false)}
            aria-label="Close navigation menu"
            tabIndex={-1}
          />
          <section
            id="qe-admin-mobile-menu"
            className="qe-admin-mobile-menu"
            role="dialog"
            aria-modal="true"
            aria-label={locale === 'english' ? 'All workspace tools' : 'सभी workspace विकल्प'}
          >
            <header className="qe-admin-mobile-menu-header">
              <div>
                <strong>{locale === 'english' ? 'All workspace tools' : 'सभी workspace विकल्प'}</strong>
                <small>{locale === 'english' ? 'Browse by workflow' : 'काम के अनुसार विकल्प'}</small>
              </div>
              <button type="button" autoFocus onClick={() => setMobileMenuOpen(false)} aria-label={locale === 'english' ? 'Close menu' : 'मेनू बंद करें'}>×</button>
            </header>
            <div className="qe-admin-mobile-menu-scroll">
              {visibleOverviewItems.map(item => (
                <section className="qe-admin-mobile-menu-group qe-admin-mobile-menu-overview" key={item.key}>
                  <h2>{locale === 'english' ? 'Workspace' : 'Workspace'}</h2>
                  <div className="qe-admin-mobile-menu-items">
                    <Link href={adminNavHref(item, value.role)} onClick={() => setMobileMenuOpen(false)} aria-current={active === item.key ? 'page' : undefined} className={active === item.key ? 'is-active' : ''}>
                      <span className="qe-admin-mobile-menu-icon" aria-hidden="true">{item.icon}</span>
                      <span>{adminNavLabel(item, locale, value.role)}</span>
                    </Link>
                  </div>
                </section>
              ))}
              {visibleGroups.map(group => (
                <section className="qe-admin-mobile-menu-group" key={group.id}>
                  <h2>{adminNavGroupLabel(group, locale)}</h2>
                  <div className="qe-admin-mobile-menu-items">
                    {group.items.map(item => (
                      <Link key={item.key} href={adminNavHref(item, value.role)} onClick={() => setMobileMenuOpen(false)} aria-current={active === item.key ? 'page' : undefined} className={active === item.key ? 'is-active' : ''}>
                        <span className="qe-admin-mobile-menu-icon" aria-hidden="true">{item.icon}</span>
                        <span>{adminNavLabel(item, locale, value.role)}</span>
                      </Link>
                    ))}
                  </div>
                </section>
              ))}
            </div>
            <footer className="qe-admin-mobile-menu-footer">
              <div className="qe-admin-mobile-user">
                <strong title={session?.displayName || (locale === 'english' ? 'Staff account' : 'स्टाफ खाता')}>{session?.displayName || (locale === 'english' ? 'Staff account' : 'स्टाफ खाता')}</strong>
                <span className={'qe-staff-role-badge qe-staff-role-' + adminRoleClass(value.role)} aria-label={'Assigned role: ' + adminRoleLabel(value.role)}>
                  <span className="qe-staff-role-dot" aria-hidden="true" />
                  {adminRoleLabel(value.role)}
                </span>
              </div>
              <button type="button" onClick={() => void logout()} disabled={loggingOut}>
                <span aria-hidden="true">↪</span>{loggingOut ? (locale === 'english' ? 'Logging out…' : 'लॉग आउट हो रहा है…') : (locale === 'english' ? 'Log out' : 'लॉग आउट')}
              </button>
            </footer>
          </section>
        </>
      ) : null}

      <nav className="qe-admin-mobile-nav" aria-label="Admin navigation" inert={mobileMenuOpen}>
        {quickItems.map(item => (
          <Link
            key={item.key}
            href={adminNavHref(item, value.role)}
            aria-current={active === item.key ? 'page' : undefined}
            className={active === item.key ? 'is-active' : ''}
            title={adminNavLabel(item, locale, value.role)}
          >
            <span aria-hidden="true">{item.icon}</span>
            <small>{adminNavShortLabel(item, locale)}</small>
          </Link>
        ))}
        <button
          type="button"
          ref={mobileMoreButtonRef}
          className={'qe-admin-mobile-more' + (mobileMenuOpen || !quickItems.some(item => item.key === active) ? ' is-active' : '')}
          onClick={() => setMobileMenuOpen(open => !open)}
          aria-expanded={mobileMenuOpen}
          aria-controls="qe-admin-mobile-menu"
          aria-label={locale === 'english' ? 'More navigation options' : 'और विकल्प'}
        >
          <span aria-hidden="true">{mobileMenuOpen ? '×' : '☷'}</span>
          <small>{locale === 'english' ? 'More' : 'और'}</small>
        </button>
      </nav>
    </div>
  </AdminWorkspaceContext.Provider>;
}
