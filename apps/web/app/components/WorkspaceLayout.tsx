'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';

type Item = { href: string; label: string; icon: string; match?: string };
const studentItems: Item[] = [
  { href: '/student', label: 'Aaj ka plan', icon: '⌂', match: '/student' },
  { href: '/student/learn', label: 'Padhai', icon: '▤' },
  { href: '/student/practice', label: 'Abhyas', icon: '✦' },
  { href: '/student/progress', label: 'Meri progress', icon: '↗' },
  { href: '/student/profile', label: 'Profile', icon: '◉' },
];
const parentItems: Item[] = [
  { href: '/parent', label: 'Family report', icon: '⌂', match: '/parent' },
  { href: '/parent/children', label: 'Bachchon ke profiles', icon: '♧' },
  { href: '/parent/profile', label: 'Parent profile', icon: '◉' },
];

export default function WorkspaceLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const role = pathname.startsWith('/student') ? 'student' : pathname.startsWith('/parent') ? 'parent' : null;
  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => {
    if (!role) return;
    try { setCollapsed(window.localStorage.getItem('qe-' + role + '-sidebar-collapsed') === 'true'); } catch {}
  }, [role]);
  function toggleCollapsed() {
    const next = !collapsed;
    setCollapsed(next);
    try { window.localStorage.setItem('qe-' + role + '-sidebar-collapsed', String(next)); } catch {}
  }
  if (!role) return <>{children}</>;
  const items = role === 'student' ? studentItems : parentItems;
  const active = (item: Item) => item.href === '/student' || item.href === '/parent'
    ? pathname === item.href || pathname === item.href + '/diagnostic'
    : pathname === item.href || pathname.startsWith(item.href + '/');
  return <div className={'qe-workspace-layout qe-' + role + '-workspace' + (collapsed ? ' is-collapsed' : '')}>
    <aside className="qe-workspace-sidebar">
      <Link href={role === 'student' ? '/student' : '/parent'} className="qe-workspace-brand" aria-label="QuantaEdge workspace">
        <img src="/branding/quantaedge-icon.png" alt="" width="38" height="38" />
        <span><strong>QuantaEdge</strong><small>{role === 'student' ? 'LEARNING SPACE' : 'FAMILY SPACE'}</small></span>
      </Link>
      <button type="button" className="qe-workspace-collapse" onClick={toggleCollapsed} aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'} aria-expanded={!collapsed} title={collapsed ? 'Expand navigation' : 'Collapse navigation'}>{collapsed ? '»' : '«'}</button>
      <p className="qe-workspace-label">{role === 'student' ? 'YOUR LEARNING' : 'YOUR FAMILY'}</p>
      <nav aria-label={role === 'student' ? 'Student navigation' : 'Parent navigation'}>
        {items.map(item => <Link key={item.href} href={item.href} title={collapsed ? item.label : undefined} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}><span className="qe-workspace-icon" aria-hidden="true">{item.icon}</span><span className="qe-workspace-link-label">{item.label}</span></Link>)}
      </nav>
      <div className="qe-workspace-sidebar-foot"><span>✓</span><div><strong>Safe learning space</strong><small>{role === 'student' ? 'Progress auto-save hoti hai' : 'Child reports are private'}</small></div></div>
    </aside>
    <div className="qe-workspace-content">{children}</div>
    <nav className="qe-workspace-mobile-nav" aria-label={role === 'student' ? 'Student navigation' : 'Parent navigation'}>
      {items.slice(0, 4).map(item => <Link key={item.href} href={item.href} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'is-active' : ''}><span aria-hidden="true">{item.icon}</span><small>{item.label}</small></Link>)}
    </nav>
  </div>;
}
