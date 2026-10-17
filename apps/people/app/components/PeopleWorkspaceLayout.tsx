'use client';

import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import { useUiTranslation } from '../i18n/UiLocaleProvider';

type StaffSession = {
  staffId?: number;
  role?: string;
  displayName?: string;
  permissions?: string[];
};

type NavigationItem = {
  message: string;
  href: string;
  icon: string;
  permissions?: string[];
};

type NavigationGroup = {
  message: string;
  items: NavigationItem[];
};

const NAVIGATION: NavigationGroup[] = [
  { message: 'people.navigation.group.organization', items: [
    { message: 'people.navigation.employeeDirectory', href: '/', icon: '◉', permissions: ['HR_EMPLOYEE_VIEW'] },
    { message: 'people.navigation.workforceLifecycle', href: '/workforce', icon: '↗', permissions: [
      'HR_RECRUITMENT_VIEW', 'HR_RECRUITMENT_MANAGE', 'HR_PERFORMANCE_VIEW', 'HR_PERFORMANCE_MANAGE',
      'HR_ASSET_VIEW', 'HR_ASSET_MANAGE', 'HR_EXIT_VIEW', 'HR_EXIT_MANAGE',
    ] },
  ] },
  { message: 'people.navigation.group.platform', items: [
    { message: 'people.navigation.institutions', href: '/institutions', icon: '▦', permissions: ['QE_PLATFORM_OWNER'] },
  ] },
  { message: 'people.navigation.group.access', items: [
    { message: 'people.navigation.loginAccess', href: '/access', icon: '⌁', permissions: ['HR_ACCESS_MANAGE'] },
  ] },
  { message: 'people.navigation.group.timeLeave', items: [
    { message: 'people.navigation.attendanceLeave', href: '/attendance', icon: '▦', permissions: [
      'HR_ATTENDANCE_VIEW', 'HR_ATTENDANCE_MANAGE', 'HR_LEAVE_VIEW', 'HR_LEAVE_MANAGE',
      'HR_ATTENDANCE_CORRECTION_REVIEW', 'HR_APPROVAL_POLICY_MANAGE', 'HR_HOLIDAY_VIEW',
      'HR_HOLIDAY_MANAGE', 'HR_LEAVE_BALANCE_VIEW', 'HR_LEAVE_BALANCE_MANAGE',
    ] },
  ] },
  { message: 'people.navigation.group.payroll', items: [
    { message: 'people.navigation.payrollCompensation', href: '/payroll', icon: '₹', permissions: ['HR_PAYROLL_VIEW', 'HR_PAYROLL_MANAGE', 'HR_PAYROLL_APPROVE', 'HR_PAYROLL_PAY'] },
  ] },
  { message: 'people.navigation.group.academic', items: [
    { message: 'people.navigation.teachingAssignments', href: '/assignments', icon: '◇', permissions: ['HR_ACADEMIC_ASSIGNMENT_VIEW', 'HR_ACADEMIC_ASSIGNMENT_MANAGE', 'HR_ACADEMIC_ASSIGNMENT_APPROVE'] },
    { message: 'people.navigation.myClasses', href: '/my-classes', icon: '▤', permissions: ['TEACHING_ASSIGNMENT_VIEW'] },
  ] },
  { message: 'people.navigation.group.selfService', items: [
    { message: 'people.navigation.employeeSelfService', href: '/self-service', icon: '○' },
  ] },
];

function roleLabel(role?: string): string {
  const value = String(role || 'STAFF').trim().replaceAll('_', ' ').toLowerCase();
  return value.replace(/\b\w/g, character => character.toUpperCase());
}

function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (!parts.length) return 'S';
  return parts.length > 1
    ? (parts[0][0] + parts[parts.length - 1][0]).toUpperCase()
    : parts[0].slice(0, 2).toUpperCase();
}

export default function PeopleWorkspaceLayout({ children }: { children: ReactNode }) {
  const pathname = usePathname() || '/';
  const router = useRouter();
  const { locale, setLocale, t } = useUiTranslation();
  const publicRoute = pathname === '/login' || pathname.startsWith('/login/') || pathname === '/change-password';
  const [session, setSession] = useState<StaffSession | null>(null);
  const [collapsed, setCollapsed] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const mobileMenuRef = useRef<HTMLElement>(null);
  const mobileMenuTriggerRef = useRef<HTMLButtonElement>(null);
  const menuWasOpen = useRef(false);

  useEffect(() => {
    try {
      setCollapsed(window.localStorage.getItem('qe-people-sidebar-collapsed') === 'true');
    } catch {
      // The workspace remains usable for the current session without storage.
    }
  }, []);

  useEffect(() => {
    if (publicRoute) {
      setSession(null);
      return;
    }

    let active = true;
    fetch('/api/v1/auth/me', {
      headers: { 'X-QE-Workspace': 'HRMS' },
      credentials: 'same-origin',
      cache: 'no-store',
    })
      .then(async response => {
        if (!response.ok) return null;
        return response.json();
      })
      .then((me: StaffSession | null) => {
        if (active) setSession(me && Number(me.staffId) > 0 ? me : null);
      })
      .catch(() => {
        if (active) setSession(null);
      });

    return () => {
      active = false;
    };
  }, [publicRoute]);

  useEffect(() => {
    setMobileMenuOpen(false);
  }, [pathname]);

  useEffect(() => {
    if (!mobileMenuOpen) {
      if (menuWasOpen.current) mobileMenuTriggerRef.current?.focus();
      menuWasOpen.current = false;
      return;
    }

    menuWasOpen.current = true;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const menu = mobileMenuRef.current;

    const focusable = () => menu?.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    focusable()?.item(0)?.focus();

    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        setMobileMenuOpen(false);
        return;
      }
      if (event.key !== 'Tab') return;
      const elements = focusable();
      if (!elements?.length) return;
      const first = elements.item(0);
      const last = elements.item(elements.length - 1);
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [mobileMenuOpen]);

  const permissions = Array.isArray(session?.permissions) ? session.permissions : [];
  const isAdmin = String(session?.role || '').toUpperCase() === 'ADMIN';

  const visibleGroups = useMemo(() => NAVIGATION
    .map(group => ({
      ...group,
      items: group.items.filter(item => {
        if (!session?.staffId) return false;
        // Cross-institution operations are special: even a role labelled ADMIN
        // must carry the explicit platform-owner grant in the HRMS workspace.
        if (item.href === '/institutions') return permissions.includes('QE_PLATFORM_OWNER');
        if (item.href === '/self-service') {
          const role = String(session.role || '').toUpperCase();
          const employeeWorkspacePermissions = [
            'HR_EMPLOYEE_VIEW', 'HR_ATTENDANCE_VIEW', 'HR_LEAVE_VIEW',
            'HR_PAYROLL_VIEW', 'TEACHING_ASSIGNMENT_VIEW',
          ];
          return !isAdmin && (role === 'EMPLOYEE'
            || employeeWorkspacePermissions.some(permission => permissions.includes(permission)));
        }
        if (isAdmin || !item.permissions?.length) return true;
        return item.permissions.some(permission => permissions.includes(permission));
      }),
    }))
    .filter(group => group.items.length > 0), [session, isAdmin, permissions]);

  const displayName = session?.displayName?.trim() || 'Staff account';
  const currentRole = roleLabel(session?.role);

  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsed(next);
    try {
      window.localStorage.setItem('qe-people-sidebar-collapsed', String(next));
    } catch {
      // Keep the in-memory preference if local storage is unavailable.
    }
  }

  async function logout() {
    setLoggingOut(true);
    setMobileMenuOpen(false);
    try {
      await fetch('/api/v1/auth/logout', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'X-QE-Workspace': 'HRMS' },
      });
    } catch {
      // Return to the login page even when the logout endpoint is unavailable.
    } finally {
      router.replace('/login');
      router.refresh();
      setLoggingOut(false);
    }
  }

  if (publicRoute) return <>{children}</>;

  function renderNavigation(mobile = false) {
    return (
      <nav className="qe-people-navigation" aria-label={t('people.navigation.label')}>
        {visibleGroups.map(group => (
          <section className="qe-people-navigation-group" key={group.message}>
            <h2 className="qe-people-navigation-heading">{t(group.message)}</h2>
            {group.items.map(item => {
              const isActive = item.href === pathname;
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  className={'qe-people-navigation-link' + (isActive ? ' is-active' : '')}
                  aria-current={isActive ? 'page' : undefined}
                  title={collapsed && !mobile ? t(item.message) : undefined}
                  onClick={mobile ? () => setMobileMenuOpen(false) : undefined}
                >
                  <span className="qe-people-navigation-icon" aria-hidden="true">{item.icon}</span>
                  <span className="qe-people-navigation-label">{t(item.message)}</span>
                </Link>
              );
            })}
          </section>
        ))}
      </nav>
    );
  }

  return (
    <div className={'qe-people-workspace' + (collapsed ? ' is-collapsed' : '')}>
      <a className="qe-people-skip-link" href="#main-content">{t('people.skipToMain')}</a>
      <aside className="qe-people-sidebar" aria-label={t('common.navigation.primaryWorkspace')}>
        <div className="qe-people-brand-row">
          <div className="qe-people-brand">
            <QuantaEdgeBrand
              href="/"
              variant={collapsed ? 'compact' : 'header'}
              className={'qe-people-brand-lockup' + (collapsed ? ' qe-people-brand-lockup--collapsed' : '')}
            />
            {!collapsed && <span>{t('people.brand.operations')}</span>}
          </div>
          <button
            type="button"
            className="qe-people-collapse-button"
            onClick={toggleCollapsed}
            aria-label={t(collapsed ? 'common.navigation.expand' : 'common.navigation.collapse')}
            aria-expanded={!collapsed}
            title={t(collapsed ? 'common.navigation.expand' : 'common.navigation.collapse')}
          >{collapsed ? '»' : '«'}</button>
        </div>

        <div className="qe-people-sidebar-scroll">
          <div className="qe-people-workspace-label">{t('people.workspace.label')}</div>
          {renderNavigation()}
        </div>

        <footer className="qe-people-account">
          <span className="qe-people-avatar" aria-hidden="true">{initials(displayName)}</span>
          <div className="qe-people-account-copy">
            <strong title={displayName}>{displayName}</strong>
            <span><i aria-hidden="true" />{currentRole}</span>
          </div>
          <button
            type="button"
            className="qe-people-logout"
            onClick={() => void logout()}
            disabled={loggingOut}
            title={t(loggingOut ? 'people.account.signingOut' : 'people.account.signOut')}
            aria-label={t(loggingOut ? 'people.account.signingOut' : 'people.account.signOut')}
          >{loggingOut ? '…' : '↪'}</button>
        </footer>
      </aside>

      <div className="qe-people-content">
        <header className="qe-people-global-header">
          <div className="qe-people-mobile-brand">
            <button
              ref={mobileMenuTriggerRef}
              type="button"
              className="qe-people-menu-trigger"
              onClick={() => setMobileMenuOpen(open => !open)}
              aria-expanded={mobileMenuOpen}
              aria-controls="qe-people-mobile-menu"
              aria-label={t(mobileMenuOpen ? 'people.navigation.close' : 'people.navigation.open')}
            >{mobileMenuOpen ? '×' : '☰'}</button>
            <div className="qe-people-header-context">
              <QuantaEdgeBrand href="/" variant="compact" className="qe-people-mobile-brand-lockup" />
              <strong>{t('people.brand.title')}</strong>
            </div>
          </div>
          <div className="qe-people-header-actions">
            <label className="qe-people-language-select">
              <span>{t('common.language.label')}</span>
              <select value={locale} onChange={event => setLocale(event.target.value as typeof locale)} aria-label={t('common.language.label')}>
                <option value="hinglish">{t('common.language.hinglish')}</option>
                <option value="english">{t('common.language.english')}</option>
              </select>
            </label>
            <span className="qe-people-workspace-pill"><i aria-hidden="true" /> {t('people.workspace.staff')}</span>
            <span className="qe-people-header-user" title={displayName}>{displayName}</span>
          </div>
        </header>

        <div className="qe-people-page-slot" id="main-content" tabIndex={-1}>
          {children}
        </div>
      </div>

      {mobileMenuOpen && (
        <>
          <button
            type="button"
            className="qe-people-mobile-backdrop"
            onClick={() => setMobileMenuOpen(false)}
            aria-label={t('people.navigation.close')}
            tabIndex={-1}
          />
          <section
            id="qe-people-mobile-menu"
            className="qe-people-mobile-menu"
            ref={mobileMenuRef}
            role="dialog"
            aria-modal="true"
            aria-label={t('people.navigation.label')}
          >
            <header className="qe-people-mobile-menu-header">
              <div>
                <strong>{t('people.brand.title')}</strong>
                <small>{t('people.navigation.browseByWorkflow')}</small>
              </div>
              <button type="button" onClick={() => setMobileMenuOpen(false)} aria-label={t('people.navigation.close')}>×</button>
            </header>
            <div className="qe-people-mobile-menu-scroll">
              {renderNavigation(true)}
            </div>
            <footer className="qe-people-mobile-menu-footer">
              <div className="qe-people-mobile-user">
                <span className="qe-people-avatar" aria-hidden="true">{initials(displayName)}</span>
                <div>
                  <strong>{displayName}</strong>
                  <small>{currentRole}</small>
                </div>
              </div>
              <button type="button" onClick={() => void logout()} disabled={loggingOut}>
                {loggingOut ? t('people.account.signingOut') : '↪ ' + t('people.account.signOut')}
              </button>
            </footer>
          </section>
        </>
      )}
    </div>
  );
}
