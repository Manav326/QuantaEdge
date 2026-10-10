'use client';

import Link from 'next/link';
import { useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';
import { LanguageSwitcher, useLocale } from './LanguageProvider';

type Item = { href: string; hinglish: string; english: string; icon: string; match?: string };
const studentItems: Item[] = [
  { href: '/student', hinglish: 'आज की योजना', english: "Today's plan", icon: '⌂', match: '/student' },
  { href: '/student/learn', hinglish: 'पढ़ाई', english: 'Learning', icon: '▤' },
  { href: '/student/practice', hinglish: 'अभ्यास', english: 'Practice', icon: '✦' },
  { href: '/student/progress', hinglish: 'मेरी प्रगति', english: 'My progress', icon: '↗' },
  { href: '/student/profile', hinglish: 'Profile', english: 'Profile', icon: '◉' },
];
const parentItems: Item[] = [
  { href: '/parent', hinglish: 'परिवार की रिपोर्ट', english: 'Family report', icon: '⌂', match: '/parent' },
  { href: '/parent/children', hinglish: 'बच्चों के Profiles', english: 'Children’s profiles', icon: '♧' },
  { href: '/parent/profile', hinglish: 'Parent Profile', english: 'Parent profile', icon: '◉' },
];

export default function WorkspaceLayout({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { locale } = useLocale();
  const itemLabel = (item: Item) => locale === 'english' ? item.english : item.hinglish;
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
        <span><strong>QuantaEdge</strong><small>{role === 'student' ? (locale === 'english' ? 'LEARNING SPACE' : 'सीखने की जगह') : (locale === 'english' ? 'FAMILY SPACE' : 'परिवार की जगह')}</small></span>
      </Link>
      <button type="button" className="qe-workspace-collapse" onClick={toggleCollapsed} aria-label={collapsed ? (locale === 'english' ? 'Expand navigation' : 'Navigation खोलें') : (locale === 'english' ? 'Collapse navigation' : 'Navigation समेटें')} aria-expanded={!collapsed} title={collapsed ? (locale === 'english' ? 'Expand navigation' : 'Navigation खोलें') : (locale === 'english' ? 'Collapse navigation' : 'Navigation समेटें')}>{collapsed ? '»' : '«'}</button>
      <p className="qe-workspace-label">{role === 'student' ? (locale === 'english' ? 'YOUR LEARNING' : 'आपकी पढ़ाई') : (locale === 'english' ? 'YOUR FAMILY' : 'आपका परिवार')}</p>
      <nav aria-label={role === 'student' ? 'Student navigation' : 'Parent navigation'}>
        {items.map(item => <Link key={item.href} href={item.href} title={collapsed ? itemLabel(item) : undefined} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'qe-workspace-link is-active' : 'qe-workspace-link'}><span className="qe-workspace-icon" aria-hidden="true">{item.icon}</span><span className="qe-workspace-link-label">{itemLabel(item)}</span></Link>)}
      </nav>
      <div className="qe-workspace-sidebar-foot"><span>✓</span><div><strong>{locale === 'english' ? 'Safe learning space' : 'सुरक्षित learning space'}</strong><small>{role === 'student' ? (locale === 'english' ? 'Progress saves automatically' : 'Progress अपने-आप save होती है') : (locale === 'english' ? 'Child reports are private' : 'बच्चों की reports private रहती हैं')}</small></div></div>
    </aside>
    <div className="qe-workspace-content"><div className="qe-language-toolbar"><LanguageSwitcher compact /></div>{children}</div>
    <nav className="qe-workspace-mobile-nav" aria-label={role === 'student' ? 'Student navigation' : 'Parent navigation'}>
      {items.slice(0, 4).map(item => <Link key={item.href} href={item.href} aria-current={active(item) ? 'page' : undefined} className={active(item) ? 'is-active' : ''}><span aria-hidden="true">{item.icon}</span><small>{itemLabel(item)}</small></Link>)}
    </nav>
  </div>;
}
