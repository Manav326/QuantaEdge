'use client';


import { apiFetch } from './apiClient';
import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import AdminSidebar from './AdminSidebar';
import { StaffNotificationBell } from './StaffNotificationInbox';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import { AdminAppearanceModeButton, AdminThemeProvider } from './AdminThemeProvider';
import { getUiLocaleLanguage, resolveUiLocale, UI_LOCALE_OPTIONS, translateUi } from '../i18n/catalog';
import { AdminWorkspaceContext, type AdminLocale, type AdminWorkspaceIdentityType } from './AdminWorkspaceContext';
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

type StaffSession = { role?: string; staffId?: number; displayName?: string; permissions?: string[]; institutionCode?: string; institution_code?: string; tenantCode?: string; tenant_code?: string };
type AdminInstitution = { institution_id: number; tenant_code: string; display_name: string; is_default?: boolean };
type InstitutionAdminSession = {
  authenticated?: boolean;
  workspace?: string;
  institutionId?: number;
  institutionCode?: string;
  institutionName?: string;
  adminUsername?: string;
  adminDisplayName?: string;
  mustChangePassword?: boolean;
  capabilities?: string[];
  branding?: Record<string, unknown>;
};

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
  if (pathname.startsWith('/institution-operations')) return 'institution';
  if (pathname.startsWith('/marketplace')) return 'marketplace';
  if (pathname.startsWith('/notifications')) return 'notifications';
  return 'overview';
}

function normalizeAdminLocale(value: unknown): AdminLocale | null {
  return resolveUiLocale(value);
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
  const [institutionAdminSession, setInstitutionAdminSession] = useState<InstitutionAdminSession | null>(null);
  const [institutions, setInstitutions] = useState<AdminInstitution[]>([]);
  const [institutionCode, setInstitutionCode] = useState('');
  const [institutionLoading, setInstitutionLoading] = useState(true);
  const [institutionError, setInstitutionError] = useState('');
  const [institutionRetryVersion, setInstitutionRetryVersion] = useState(0);
  const [loggingOut, setLoggingOut] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const mobileMoreButtonRef = useRef<HTMLButtonElement | null>(null);
  const mobileMenuWasOpen = useRef(false);
  const router = useRouter();
  const t = (key: string, values: Record<string, string | number> = {}) => translateUi(locale, key, values);
  const changeLocale = useCallback((next: AdminLocale) => {
    setLocale(next);
    if (typeof document !== 'undefined') document.documentElement.lang = getUiLocaleLanguage(next);
    persistAdminLocale(next);
  }, []);

  useEffect(() => {
    let saved: AdminLocale | null = null;
    try { saved = readSharedAdminLocale() ?? normalizeAdminLocale(window.localStorage.getItem('qe-locale')); } catch {}
    if (saved) {
      setLocale(saved);
      document.documentElement.lang = getUiLocaleLanguage(saved);
      if (!readSharedAdminLocale()) persistAdminLocale(saved);
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === 'qe-locale') {
        const next = normalizeAdminLocale(event.newValue);
        if (next) { setLocale(next); document.documentElement.lang = getUiLocaleLanguage(next); }
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  useEffect(() => {
    if (publicRoute) {
      setSession(null);
      setInstitutionAdminSession(null);
      return;
    }
    let alive = true;
    (async () => {
      // Prefer an actual staff session. Institution principals use a distinct cookie and are
      // discovered only when no valid Education Admin identity exists.
      try {
        const staffResponse = await apiFetch('/api/v1/auth/me', { cache: 'no-store', credentials: 'same-origin' }, { notificationMode: 'caller' });
        const staffMe = staffResponse.ok ? await staffResponse.json().catch(() => null) : null;
        if (staffMe && Number(staffMe.staffId) > 0) {
          if (alive) {
            setSession(staffMe as StaffSession);
            setInstitutionAdminSession(null);
          }
          return;
        }
      } catch { /* Try the separate institution principal next. */ }

      try {
        const institutionResponse = await apiFetch('/api/v1/institution/auth/me', {
          cache: 'no-store',
          credentials: 'same-origin',
          headers: { Accept: 'application/json', 'X-QE-Workspace': 'INSTITUTION' },
        }, { notificationMode: 'caller' });
        const institutionMe = institutionResponse.ok ? await institutionResponse.json().catch(() => null) : null;
        if (alive) {
          if (institutionMe?.authenticated === true && Number(institutionMe.institutionId) > 0 && institutionMe.institutionCode) {
            setInstitutionAdminSession(institutionMe as InstitutionAdminSession);
            setSession(null);
          } else {
            setInstitutionAdminSession(null);
            setSession(null);
          }
        }
      } catch {
        if (alive) {
          setInstitutionAdminSession(null);
          setSession(null);
        }
      }
    })();
    return () => { alive = false; };
  }, [publicRoute]);

  // Either a staff identity receives its fixed institution binding from HRMS, or an
  // institution-head identity receives its scope from the institution_admin_session cookie.
  // Browser storage and request parameters never select an institution.
  useEffect(() => {
    let alive = true;
    if (publicRoute) {
      setInstitutions([]);
      setInstitutionCode('');
      setInstitutionError('');
      setInstitutionLoading(false);
      try { window.localStorage.removeItem('qe:admin:activeInstitutionCode'); } catch {}
      return () => { alive = false; };
    }
    if (institutionAdminSession) {
      const tenantCode = String(institutionAdminSession.institutionCode || '').trim().toUpperCase();
      const name = String(institutionAdminSession.institutionName || '').trim();
      const id = Number(institutionAdminSession.institutionId);
      if (!tenantCode || !name || !Number.isSafeInteger(id) || id <= 0) {
        setInstitutions([]);
        setInstitutionCode('');
        setInstitutionError('The institution session returned an invalid institution identity.');
      } else {
        setInstitutions([{ institution_id: id, tenant_code: tenantCode, display_name: name, is_default: true }]);
        setInstitutionCode(tenantCode);
        setInstitutionError('');
      }
      try { window.localStorage.removeItem('qe:admin:activeInstitutionCode'); } catch {}
      setInstitutionLoading(false);
      return () => { alive = false; };
    }
    if (!session) {
      setInstitutionLoading(true);
      return () => { alive = false; };
    }
    if (!session.staffId) {
      setInstitutions([]);
      setInstitutionCode('');
      setInstitutionError('An Education Admin staff account is required for this workspace.');
      setInstitutionLoading(false);
      return () => { alive = false; };
    }

    setInstitutionLoading(true);
    setInstitutionError('');
    apiFetch('/api/v1/admin/content/institution-context', { cache: 'no-store' })
      .then(async response => {
        const body = await response.json().catch(() => ({}));
        if (!response.ok) {
          const detail = body && typeof body === 'object'
            ? String((body as { message?: unknown; detail?: unknown }).message
              || (body as { detail?: unknown }).detail || '')
            : '';
          if (response.status === 403) {
            throw new Error(detail || 'This staff account has no active HRMS institution assignment. Ask an HRMS platform owner to assign it.');
          }
          throw new Error(detail || 'The assigned institution could not be loaded.');
        }
        const tenantCode = String(body?.tenant_code || '').trim().toUpperCase();
        const name = String(body?.display_name || '').trim();
        const id = Number(body?.institution_id);
        if (!tenantCode || !name || !Number.isSafeInteger(id) || id <= 0) {
          throw new Error('The HRMS institution assignment returned an invalid institution identity.');
        }
        return [{ institution_id: id, tenant_code: tenantCode, display_name: name, is_default: true }] as AdminInstitution[];
      })
      .then(rows => {
        if (!alive) return;
        setInstitutions(rows);
        setInstitutionCode(rows[0]?.tenant_code || '');
        try { window.localStorage.removeItem('qe:admin:activeInstitutionCode'); } catch {}
      })
      .catch(error => {
        if (!alive) return;
        setInstitutions([]);
        setInstitutionCode('');
        setInstitutionError(error instanceof Error ? error.message : 'The HRMS institution assignment could not be loaded.');
      })
      .finally(() => { if (alive) setInstitutionLoading(false); });
    return () => { alive = false; };
  }, [publicRoute, session, institutionAdminSession, institutionRetryVersion]);

  useEffect(() => {
    if (!institutionAdminSession) return;
    const mustChange = Boolean(institutionAdminSession.mustChangePassword);
    if (mustChange && pathname !== '/institution-change-password') {
      router.replace('/institution-change-password');
      return;
    }
    if (!mustChange) {
      const allowedRoutes = ['/institution-operations', '/notifications', '/institution-change-password'];
      const allowed = allowedRoutes.some(route => pathname === route || pathname.startsWith(route + '/'));
      if (!allowed) router.replace('/institution-operations');
    }
  }, [institutionAdminSession, pathname, router]);

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

  const value = useMemo(() => {
    const identityType: AdminWorkspaceIdentityType | null = session
      ? 'EDUCATION_ADMIN'
      : institutionAdminSession ? 'INSTITUTION' : null;
    const permissions = session && Array.isArray(session.permissions)
      ? session.permissions
      : institutionAdminSession && Array.isArray(institutionAdminSession.capabilities)
        ? institutionAdminSession.capabilities
        : [];
    return {
      insideWorkspace: !publicRoute,
      locale,
      setLocale: changeLocale,
      role: session?.role || (institutionAdminSession ? 'INSTITUTION_ADMIN' : ''),
      permissions,
      displayName: session?.displayName || institutionAdminSession?.adminDisplayName || '',
      identityType,
      institutionId: institutionAdminSession ? Number(institutionAdminSession.institutionId) || null : null,
      institutionCode: institutionAdminSession?.institutionCode || institutionCode,
      mustChangePassword: Boolean(institutionAdminSession?.mustChangePassword),
    };
  }, [publicRoute, locale, session, institutionAdminSession, institutionCode, changeLocale]);

  async function logout() {
    setLoggingOut(true);
    setMobileMenuOpen(false);
    try {
      if (institutionAdminSession) {
        await apiFetch('/api/v1/institution/auth/logout', {
          method: 'POST',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'application/json', 'X-QE-Workspace': 'INSTITUTION' },
        }, { notificationMode: 'caller' });
      } else {
        await apiFetch('/api/v1/auth/logout', { method: 'POST', credentials: 'same-origin' });
      }
    } catch {
      // The UI still returns to login if the logout request cannot reach the server.
    } finally {
      setSession(null);
      setInstitutionAdminSession(null);
      router.replace('/login');
      setLoggingOut(false);
    }
  }

  if (publicRoute) return <AdminThemeProvider institutionCode={institutionCode} enabled={Boolean(institutionCode)}><AdminWorkspaceContext.Provider value={value}><div className="qe-admin-workspace-page-slot qe-admin-public-page">{children}</div></AdminWorkspaceContext.Provider></AdminThemeProvider>;

  const active = activeSection(pathname);
  const variant = active === 'overview' ? 'overview' : ['content', 'resources', 'sources', 'classes'].includes(active) ? 'content' : 'default';
  const institutionSelectionRequired = Boolean(session?.staffId) && !institutionLoading && !institutionCode;
  const activeInstitution = institutions.find(item => item.tenant_code === institutionCode) || null;
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

  return <AdminThemeProvider institutionCode={institutionCode}>
  <AdminWorkspaceContext.Provider value={value}>
    <div className="qe-admin-workspace-layout">
      <AdminSidebar active={active} variant={variant} displayName={value.displayName || undefined} persistent />
      <div className="qe-admin-workspace-content">
        <header className="qe-admin-global-header">
          <div className="qe-admin-global-context">
            <span className="qe-admin-global-context-label">{institutionAdminSession || activeInstitution ? 'Institution workspace' : 'Education Admin'}</span>
            <QuantaEdgeBrand href="/" variant="compact" className="qe-admin-global-brand" />
            <strong>{institutionAdminSession ? t('admin.institutionOperations.title') : t('admin.workspace.staff')}</strong>
          </div>
          <div className="qe-admin-global-actions">
            {institutionAdminSession && value.permissions.includes('NOTIFICATION_INSTITUTION_SEND')
              ? <StaffNotificationBell workspace="institution" />
              : session?.permissions?.some(permission => ['NOTIFICATION_CAMPAIGN_VIEW','NOTIFICATION_COURSE_SEND','NOTIFICATION_INSTITUTION_SEND','NOTIFICATION_TEMPLATE_MANAGE'].includes(permission))
                ? <StaffNotificationBell workspace="admin" /> : null}
            {activeInstitution ? <div className="qe-admin-institution-switcher" aria-label="Assigned institution" style={{ display: 'grid', gap: 3, fontSize: 11, fontWeight: 700, color: 'var(--admin-muted,#64748b)', minWidth: 180 }}>
              <span>Assigned institution</span>
              <strong style={{ fontSize: 13, color: 'var(--admin-text,#1b2430)' }}>{activeInstitution.display_name}</strong>
              <small style={{ fontSize: 10, fontWeight: 600 }}>Managed by HRMS · fixed assignment</small>
            </div> : null}
            <label className="qe-admin-language-switcher">
              <span aria-hidden="true">文</span>
              <select value={locale} onChange={event => changeLocale(event.target.value as AdminLocale)} aria-label={t('admin.language.choose')}>
                {UI_LOCALE_OPTIONS.map(option => <option key={option.id} value={option.id}>{option.nativeLabel}</option>)}
              </select>
            </label>
            <AdminAppearanceModeButton />
          </div>
        </header>

        <div className="qe-admin-workspace-page-slot" key={pathname + '|' + institutionCode}>
          {!publicRoute && !session && !institutionAdminSession ? <div role="status" style={{ padding: 28 }}>Preparing workspace…</div>
            : institutionAdminSession?.mustChangePassword && pathname !== '/institution-change-password'
              ? <div role="alert" style={{ margin: 24, padding: 18, border: '1px solid var(--admin-border,#d8dce5)', borderRadius: 12 }}>
                  <h2>Set your institution password</h2><p>Change the temporary password before opening institution operations.</p><Link href="/institution-change-password" className="button button-dark">Change password</Link>
                </div>
            : institutionLoading && session?.staffId ? <div role="status" style={{ padding: 28 }}>Resolving your HRMS institution assignment…</div>
            : institutionError && session?.staffId ? <div role="alert" style={{ margin: 24, padding: 18, border: '1px solid var(--admin-border,#d8dce5)', borderRadius: 12 }}>
                <h2>Institution access unavailable</h2><p>{institutionError}</p><p>Education Admin cannot switch institutions. An HRMS platform owner must configure or restore this account’s institution assignment.</p><button type="button" onClick={() => setInstitutionRetryVersion(version => version + 1)}>Retry</button>
              </div>
            : institutionSelectionRequired ? <div role="alert" style={{ margin: 24, padding: 22, border: '1px solid var(--admin-border,#d8dce5)', borderRadius: 12 }}>
                <h2>HRMS institution assignment required</h2><p>This staff account cannot access an institution until HRMS assigns it to one. There is no institution selector in Education Admin.</p>
              </div>
            : children}
        </div>
      </div>

      {mobileMenuOpen ? (
        <>
          <button
            type="button"
            className="qe-admin-mobile-menu-backdrop"
            onClick={() => setMobileMenuOpen(false)}
            aria-label={t('common.navigation.closeMenu')}
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

      <nav className="qe-admin-mobile-nav" aria-label={t('common.navigation.adminLabel')} inert={mobileMenuOpen}>
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
  </AdminWorkspaceContext.Provider>
</AdminThemeProvider>;
}
