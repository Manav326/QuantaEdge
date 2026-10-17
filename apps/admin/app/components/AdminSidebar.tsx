'use client';

import Link from 'next/link';
import { useContext, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { AdminWorkspaceContext } from './AdminWorkspaceContext';
import QuantaEdgeBrand from './QuantaEdgeBrand';
import {
  ADMIN_NAV_GROUPS,
  ADMIN_OVERVIEW_ITEM,
  adminNavGroupLabel,
  adminNavHref,
  adminNavLabel,
  adminRoleClass,
  adminRoleCompactLabel,
  adminRoleLabel,
  canSeeAdminItem,
  type AdminSection,
} from './admin-navigation';

export type { AdminSection } from './admin-navigation';

type AdminSidebarProps = {
  active: AdminSection;
  variant?: 'default' | 'overview' | 'content';
  displayName?: string;
  persistent?: boolean;
};

export default function AdminSidebar({ active, variant = 'default', displayName, persistent = false }: AdminSidebarProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [role, setRole] = useState('');
  const [permissions, setPermissions] = useState<string[]>([]);
  const [accountName, setAccountName] = useState('');
  const [expandedGroups, setExpandedGroups] = useState<Record<string, boolean>>({ curriculum: true });
  const workspace = useContext(AdminWorkspaceContext);
  const { insideWorkspace, locale, role: workspaceRole, permissions: workspacePermissions, displayName: workspaceDisplayName } = workspace;
  const visibleRole = insideWorkspace ? workspaceRole : role;
  const visiblePermissions = insideWorkspace ? workspacePermissions : permissions;
  const visibleAccountName = displayName || (insideWorkspace ? workspaceDisplayName : accountName) || 'Staff account';
  const roleLabel = adminRoleLabel(visibleRole);
  const compactRoleLabel = adminRoleCompactLabel(visibleRole);
  const roleClass = adminRoleClass(visibleRole);
  const router = useRouter();

  useEffect(() => {
    try {
      setCollapsed(window.localStorage.getItem('qe-admin-sidebar-collapsed') === 'true');
    } catch {
      // The sidebar still works for this session if storage is unavailable.
    }
    if (insideWorkspace) return;
    fetch('/api/v1/auth/me', { cache: 'no-store' })
      .then(response => response.ok ? response.json() : null)
      .then(me => {
        if (!me) return;
        setRole(String(me.role || ''));
        setPermissions(Array.isArray(me.permissions) ? me.permissions : []);
        setAccountName(String(me.displayName || me.display_name || ''));
      })
      .catch(() => undefined);
  }, [insideWorkspace]);

  const visibleGroups = useMemo(
    () => ADMIN_NAV_GROUPS
      .map(group => ({ ...group, items: group.items.filter(item => canSeeAdminItem(item, visibleRole, visiblePermissions)) }))
      .filter(group => group.items.length > 0),
    [visibleRole, visiblePermissions],
  );
  const activeGroup = visibleGroups.find(group => group.items.some(item => item.key === active))?.id;

  useEffect(() => {
    if (activeGroup) setExpandedGroups(current => current[activeGroup] ? current : { ...current, [activeGroup]: true });
  }, [active, activeGroup]);

  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsed(next);
    try {
      window.localStorage.setItem('qe-admin-sidebar-collapsed', String(next));
    } catch {
      // Keep the in-memory state even if storage is unavailable.
    }
  }

  function toggleGroup(groupId: string) {
    setExpandedGroups(current => ({ ...current, [groupId]: !current[groupId] }));
  }

  async function logout() {
    try {
      await fetch('/api/v1/auth/logout', { method: 'POST', credentials: 'same-origin' });
    } catch {
      // Return to login even if the logout request cannot reach the server.
    } finally {
      router.replace('/login');
    }
  }

  if (insideWorkspace && !persistent) return null;

  const showOverview = canSeeAdminItem(ADMIN_OVERVIEW_ITEM, visibleRole, visiblePermissions);

  return (
    <aside className={'admin-sidebar qe-shared-sidebar' + (collapsed ? ' is-collapsed' : '')}>
      <div className="qe-sidebar-brand-row">
        <QuantaEdgeBrand
          href="/"
          variant={collapsed ? 'compact' : 'header'}
          className={'qe-admin-brand-lockup' + (collapsed ? ' qe-admin-brand-lockup--collapsed' : '')}
        />
        {!collapsed ? <small className="qe-admin-brand-context">{variant === 'content' ? 'ACADEMIC CONSOLE' : 'ADMIN CONSOLE'}</small> : null}
        <button
          type="button"
          className="qe-sidebar-toggle"
          onClick={toggleCollapsed}
          aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
          aria-expanded={!collapsed}
          title={collapsed ? 'Expand navigation' : 'Collapse navigation'}
        >{collapsed ? '»' : '«'}</button>
      </div>

      <div className="qe-sidebar-scroll">
        {!collapsed ? <div className="sidebar-label">{variant === 'content' ? 'LEARNING OPERATIONS' : 'WORKSPACE'}</div> : null}
        <nav className="qe-admin-navigation" aria-label="Admin navigation">
          {showOverview ? (
            <Link
              href={ADMIN_OVERVIEW_ITEM.href}
              title={collapsed ? ADMIN_OVERVIEW_ITEM.english : undefined}
              className={'qe-nav-link' + (active === 'overview' ? ' active' : '')}
              aria-current={active === 'overview' ? 'page' : undefined}
            >
              <span className="qe-nav-icon" aria-hidden="true">{ADMIN_OVERVIEW_ITEM.icon}</span>
              <span className="qe-nav-label">{adminNavLabel(ADMIN_OVERVIEW_ITEM, locale, visibleRole)}</span>
            </Link>
          ) : null}

          {visibleGroups.map(group => {
            const expanded = collapsed || Boolean(expandedGroups[group.id]);
            const groupHasActivePage = group.items.some(item => item.key === active);
            return (
              <section key={group.id} className={'qe-admin-nav-group' + (groupHasActivePage ? ' has-active-page' : '') + (expanded ? ' is-expanded' : '')}>
                {!collapsed ? (
                  <button
                    type="button"
                    className="qe-admin-nav-group-toggle"
                    onClick={() => toggleGroup(group.id)}
                    aria-expanded={Boolean(expandedGroups[group.id])}
                  >
                    <span>{adminNavGroupLabel(group, locale)}</span>
                    <span className="qe-admin-nav-chevron" aria-hidden="true">{expandedGroups[group.id] ? '−' : '+'}</span>
                  </button>
                ) : null}
                {expanded ? (
                  <div className="qe-admin-nav-group-items">
                    {group.items.map(item => (
                      <Link
                        key={item.key}
                        href={adminNavHref(item, visibleRole)}
                        title={collapsed ? adminNavLabel(item, locale, visibleRole) : undefined}
                        className={'qe-nav-link' + (active === item.key ? ' active' : '')}
                        aria-current={active === item.key ? 'page' : undefined}
                      >
                        <span className="qe-nav-icon" aria-hidden="true">{item.icon}</span>
                        <span className="qe-nav-label">{adminNavLabel(item, locale, visibleRole)}</span>
                      </Link>
                    ))}
                  </div>
                ) : null}
              </section>
            );
          })}
        </nav>

        {variant === 'overview' ? (
          <div className="qe-admin-overview-shortcuts">
            <div className="sidebar-divider" />
            {!collapsed ? <div className="sidebar-label">OVERVIEW SHORTCUTS</div> : null}
            <a href="#analytics" title={collapsed ? 'Learning analytics' : undefined}>
              <span className="qe-nav-icon" aria-hidden="true">◌</span>
              <span className="qe-nav-label">Learning analytics</span>
            </a>
            <a href="#governance" title={collapsed ? 'Content governance' : undefined}>
              <span className="qe-nav-icon" aria-hidden="true">✓</span>
              <span className="qe-nav-label">Content governance</span>
            </a>
          </div>
        ) : null}
      </div>

      <div className="admin-user qe-admin-user-footer">
        <div className="qe-admin-user-identity">
          <span className="avatar" aria-hidden="true">{visibleAccountName.trim().slice(0, 1).toUpperCase() || 'A'}</span>
          <div className="admin-user-copy">
            <strong title={visibleAccountName}>{visibleAccountName}</strong>
            <span
              className={'qe-staff-role-badge qe-staff-role-' + roleClass}
              title={'Assigned role: ' + roleLabel}
              aria-label={'Assigned role: ' + roleLabel}
            >
              <span className="qe-staff-role-dot" aria-hidden="true" />
              <span className="qe-staff-role-full">{roleLabel}</span>
              <span className="qe-staff-role-short" aria-hidden="true">{compactRoleLabel}</span>
            </span>
          </div>
        </div>
        <button className="qe-sidebar-logout" type="button" aria-label={locale === 'english' ? 'Log out' : 'लॉग आउट'} title={locale === 'english' ? 'Log out' : 'लॉग आउट'} onClick={() => void logout()}>
          <span className="qe-sidebar-logout-icon" aria-hidden="true">↪</span>
          <span className="qe-sidebar-logout-label">{locale === 'english' ? 'Log out' : 'लॉग आउट'}</span>
        </button>
      </div>
    </aside>
  );
}
