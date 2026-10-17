'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import AdminSidebar from './AdminSidebar';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import { translateUi } from '../i18n/catalog';
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
  if (pathname.startsWith('/certificates')) return 'certificates';
  return 'overview';
}

function normalizeAdminLocale(value: unknown): AdminLocale | null {
  const raw = String(value ?? '').trim().toLowerCase();
  if (raw === 'english' || raw === 'en') return 'english';
  if (raw === 'hinglish' || raw === 'hindi' || raw === 'hi') return 'hinglish';
  return null;
}
function readSharedAdminLocale(): AdminLocale | null {
  const match = document.cookie.match(/(?:^|;\s*)qe-locale=([^;]+)/);
  return match ? normalizeAdminLocale(decodeURIComponent(match[1])) : null;
}
function persistAdminLocale(locale: AdminLocale) {
  try { window.localStorage.setItem('qe-locale', locale); } catch {}
  const secure = window.location.protocol === 'https:' ? '; Secure' : '';
  document.cookie = 'qe-locale=' + locale + '; Path=/; Max-Age=31536000; SameSite=Lax' + secure;
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
  const t = (key: string, values: Record<string, string | number> = {}) => translateUi(locale, key, values);
  const changeLocale = useCallback((next: AdminLocale) => {
    setLocale(next);
    if (typeof document !== 'undefined') document.documentElement.lang = next === 'hinglish' ? 'hi' : 'en';
    persistAdminLocale(next);
  }, []);

  useEffect(() => {
    let saved: AdminLocale | null = null;
    try { saved = readSharedAdminLocale() ?? normalizeAdminLocale(window.localStorage.getItem('qe-locale')); } catch {}
    if (saved) {
      setLocale(saved);
      document.documentElement.lang = saved === 'english' ? 'en' : 'hi';
      if (!readSharedAdminLocale()) persistAdminLocale(saved);
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === 'qe-locale') {
        const next = normalizeAdminLocale(event.newValue);
        if (next) { setLocale(next); document.documentElement.lang = next === 'english' ? 'en' : 'hi'; }
      }
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
    setLocale: changeLocale,
    role: session?.role || '',
    permissions: Array.isArray(session?.permissions) ? session.permissions : [],
    displayName: session?.displayName || '',
  }), [publicRoute, locale, session, changeLocale]);

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
            <span className="qe-admin-global-context-label">QUANTAEDGE</span>
            <QuantaEdgeBrand href="/" variant="compact" className="qe-admin-global-brand" />
            <strong>{t('admin.workspace.staff')}</strong>
          </div>
          <div className="qe-admin-global-actions">
            <label className="qe-admin-language-switcher">
              <span aria-hidden="true">文</span><span className="qe-admin-language-label">{t('admin.language.label')}</span>
              <select value={locale} onChange={event => changeLocale(event.target.value as AdminLocale)} aria-label={t('admin.language.choose')}>
                <option value="hinglish">{t('common.language.hinglish')}</option>
                <option value="english">{t('common.language.english')}</option>
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
            aria-label={t('common.navigation.allWorkspaceTools')}
          >
            <header className="qe-admin-mobile-menu-header">
              <div>
                <strong>{t('common.navigation.allWorkspaceTools')}</strong>
                <small>{t('common.navigation.browseByWorkflow')}</small>
              </div>
              <button type="button" autoFocus onClick={() => setMobileMenuOpen(false)} aria-label={t('common.navigation.close')}>×</button>
            </header>
            <div className="qe-admin-mobile-menu-scroll">
              {visibleOverviewItems.map(item => (
                <section className="qe-admin-mobile-menu-group qe-admin-mobile-menu-overview" key={item.key}>
                  <h2>{t('common.navigation.workspace')}</h2>
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
                <strong title={session?.displayName || (t('common.account.staff'))}>{session?.displayName || (t('common.account.staff'))}</strong>
                <span className={'qe-staff-role-badge qe-staff-role-' + adminRoleClass(value.role)} aria-label={t('common.account.assignedRole', { role: adminRoleLabel(value.role, locale) })}>
                  <span className="qe-staff-role-dot" aria-hidden="true" />
                  {adminRoleLabel(value.role, locale)}
                </span>
              </div>
              <button type="button" onClick={() => void logout()} disabled={loggingOut}>
                <span aria-hidden="true">↪</span>{loggingOut ? (t('common.actions.signingOut')) : (t('common.actions.signOut'))}
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
          aria-label={t('admin.language.more')}
        >
          <span aria-hidden="true">{mobileMenuOpen ? '×' : '☷'}</span>
          <small>{t('common.navigation.more')}</small>
        </button>
      </nav>
    </div>
  </AdminWorkspaceContext.Provider>;
}
