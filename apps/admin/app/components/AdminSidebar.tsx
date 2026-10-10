'use client';

import Link from 'next/link';
import { useContext, useEffect, useState } from 'react';
import { AdminWorkspaceContext } from './AdminWorkspaceContext';
import { useRouter } from 'next/navigation';

export type AdminSection = 'overview' | 'content' | 'questions' | 'resources' | 'sources' | 'assessments' | 'classes' | 'students' | 'parents' | 'staff';
type AdminSidebarProps = {
  active: AdminSection;
  variant?: 'default' | 'overview' | 'content';
  displayName?: string;
  persistent?: boolean;
};

const links: Array<{ key: AdminSection; href: string; icon: string; label: string }> = [
  { key: 'overview', href: '/', icon: '▦', label: 'Overview' },
  { key: 'content', href: '/content', icon: '◈', label: 'Content Studio' },
  { key: 'questions', href: '/questions', icon: '✓', label: 'Question review' },
  { key: 'resources', href: '/resources', icon: '▤', label: 'Textbook library' },
  { key: 'sources', href: '/source-ingestion', icon: '⇣', label: 'Source ingestion' },
  { key: 'assessments', href: '/assessments', icon: '✓', label: 'Tests & grading' },
  { key: 'classes', href: '/classes', icon: '◷', label: 'Live & recorded classes' },
  { key: 'students', href: '/students', icon: '◉', label: 'Students' },
  { key: 'parents', href: '/parents', icon: '♧', label: 'Parents & families' },
];

export default function AdminSidebar({ active, variant = 'default', displayName, persistent = false }: AdminSidebarProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [role, setRole] = useState('');
  const [permissions, setPermissions] = useState<string[]>([]);
  const [accountName, setAccountName] = useState('');
  const workspace = useContext(AdminWorkspaceContext);
  const { insideWorkspace, locale, role: workspaceRole, permissions: workspacePermissions, displayName: workspaceDisplayName } = workspace;
  const visibleRole = insideWorkspace ? workspaceRole : role;
  const visiblePermissions = insideWorkspace ? workspacePermissions : permissions;
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

  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsed(next);
    try {
      window.localStorage.setItem('qe-admin-sidebar-collapsed', String(next));
    } catch {
      // Keep the in-memory state even if storage is unavailable.
    }
  }

  async function logout() {
    try {
      await fetch('/api/v1/auth/logout', { method: 'POST' });
    } finally {
      router.replace('/login');
    }
  }

  if (insideWorkspace && !persistent) return null;
  const localizedLabel = (key: AdminSection, label: string) => {
    if (locale === 'english') return label;
    const labels: Partial<Record<AdminSection, string>> = { questions: 'MCQ समीक्षा', sources: 'Sources जोड़ें', classes: 'Live classes' };
    return labels[key] || label;
  };

  return (
    <aside className={'admin-sidebar qe-shared-sidebar' + (collapsed ? ' is-collapsed' : '')}>
      <div className="qe-sidebar-brand-row">
        <Link href="/" className="admin-brand qe-admin-brand" aria-label="QuantaEdge — Smarter Decisions. Greater Growth.">
          {collapsed ? (
            <img className="qe-admin-brand-mark" src="/branding/quantaedge-icon.png" alt="" width={35} height={35} aria-hidden="true" />
          ) : (
            <span className="qe-admin-brand-copy">
              <img className="qe-admin-brand-wordmark" src="/branding/quantaedge-wordmark.png" alt="" width={600} height={94} aria-hidden="true" />
              <small className="qe-admin-brand-tagline">Smarter Decisions. Greater Growth.</small>
              <small className="qe-admin-brand-context">{variant === 'content' ? 'ACADEMIC CONSOLE' : 'ADMIN CONSOLE'}</small>
            </span>
          )}
        </Link>
        <button
          type="button"
          className="qe-sidebar-toggle"
          onClick={toggleCollapsed}
          aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
          aria-expanded={!collapsed}
          title={collapsed ? 'Expand navigation' : 'Collapse navigation'}
        >{collapsed ? '»' : '«'}</button>
      </div>

      <div className="sidebar-label">{variant === 'content' ? 'LEARNING OPERATIONS' : 'WORKSPACE'}</div>
      <nav aria-label="Admin navigation">
        {links.filter(item => {
          if (!visibleRole) return true;
          if (item.key === 'overview') return visibleRole === 'ADMIN';
          if (item.key === 'students' || item.key === 'parents') return visibleRole === 'ADMIN';
          if (item.key === 'content') return visibleRole === 'ADMIN' || visiblePermissions.includes('CONTENT_VIEW');
          if (item.key === 'questions') return visibleRole === 'ADMIN' || visiblePermissions.includes('CONTENT_VIEW') || visiblePermissions.includes('CONTENT_REVIEW');
          if (item.key === 'resources') return visibleRole === 'ADMIN' || visiblePermissions.includes('CONTENT_VIEW') || visiblePermissions.includes('CONTENT_EDIT') || visiblePermissions.includes('CONTENT_PUBLISH');
          if (item.key === 'sources') return visibleRole === 'ADMIN' || visiblePermissions.includes('CONTENT_REVIEW');
          if (item.key === 'assessments') return visibleRole === 'ADMIN' || visiblePermissions.includes('CONTENT_VIEW') || visiblePermissions.includes('CONTENT_CREATE') || visiblePermissions.includes('ASSESSMENT_GRADE');
          if (item.key === 'classes') return visibleRole === 'ADMIN' || visiblePermissions.includes('CONTENT_VIEW') || visiblePermissions.includes('CLASS_MANAGE');
          return true;
        }).map(item => (
          <Link key={item.key} href={item.href} title={collapsed ? item.label : undefined} className={active === item.key ? 'active' : ''}>
            <span className="qe-nav-icon" aria-hidden="true">{item.icon}</span>
            <span className="qe-nav-label">{localizedLabel(item.key, item.label)}</span>
            {item.key === 'content' && variant === 'overview' ? <small>01</small> : null}
          </Link>
        ))}
        {visibleRole === 'ADMIN' ? <Link href="/legacy-content" title={collapsed ? 'Detailed authoring' : undefined} className="qe-legacy-nav">
          <span className="qe-nav-icon" aria-hidden="true">✎</span><span className="qe-nav-label">Detailed authoring</span>
        </Link> : null}
        {visibleRole === 'ADMIN' ? <Link href="/employees" title={collapsed ? 'Staff & audit' : undefined} className={'qe-staff-nav '+(active === 'staff' ? 'active' : '')}>
          <span className="qe-nav-icon" aria-hidden="true">♙</span><span className="qe-nav-label">Staff & audit</span>
        </Link> : visiblePermissions.includes('AUDIT_VIEW') ? <Link href="/employees?view=audit" title={collapsed ? 'Activity trail' : undefined} className={'qe-staff-nav '+(active === 'staff' ? 'active' : '')}>
          <span className="qe-nav-icon" aria-hidden="true">◷</span><span className="qe-nav-label">Activity trail</span>
        </Link> : null}
      </nav>

      {variant === 'overview' ? <>
        <div className="sidebar-divider" />
        <div className="sidebar-label">SYSTEM</div>
        <a href="#analytics" title={collapsed ? 'Learning analytics' : undefined}><span className="qe-nav-icon" aria-hidden="true">◌</span><span className="qe-nav-label">Learning analytics</span></a>
        <a href="#governance" title={collapsed ? 'Content governance' : undefined}><span className="qe-nav-icon" aria-hidden="true">✓</span><span className="qe-nav-label">Content governance</span></a>
      </> : null}

      {variant === 'content' ? <>
        <div className="sidebar-divider" />
        <div className="sidebar-label qe-sidebar-extra">CONTENT WORKFLOW</div>
        <div className="qe-sidebar-guide qe-sidebar-extra"><span>01</span><div><b>Organise</b><small>Class → subject → chapter</small></div></div>
        <div className="qe-sidebar-guide qe-sidebar-extra"><span>02</span><div><b>Author</b><small>Teaching blocks and examples</small></div></div>
        <div className="qe-sidebar-guide qe-sidebar-extra"><span>03</span><div><b>Preview</b><small>Check desktop and phone</small></div></div>
        <div className="qe-sidebar-guide qe-sidebar-extra"><span>04</span><div><b>Publish safely</b><small>Complete all checks first</small></div></div>
      </> : null}

      <div className="admin-sidebar-footer">
        <span className="admin-security-icon">✓</span>
        <div><strong>Secure workspace</strong><small>{locale === 'english' ? 'Access is role-restricted' : 'Role ke according access'}</small></div>
      </div>
      {(displayName || (insideWorkspace ? workspaceDisplayName : accountName)) ? <div className="admin-user">
        <span className="avatar">{(displayName || (insideWorkspace ? workspaceDisplayName : accountName)).trim().slice(0, 1).toUpperCase() || 'A'}</span>
        <div><strong>{displayName || (insideWorkspace ? workspaceDisplayName : accountName)}</strong><small>Authorized operator</small></div>
        <button type="button" aria-label="Logout" title="Logout" onClick={logout}>↗</button>
      </div> : null}
    </aside>
  );
}
