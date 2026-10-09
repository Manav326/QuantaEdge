'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';

function AccountIcon({ kind }: { kind: 'student' | 'parent' | 'staff' }) {
  const props = { width: 17, height: 17, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true as const };
  if (kind === 'student') return <svg {...props}><path d="M3 10.5 12 5l9 5.5-9 5.5-9-5.5Z"/><path d="M6.5 13v4.5c3.2 2.6 7.8 2.6 11 0V13"/><path d="M21 11v6"/></svg>;
  if (kind === 'parent') return <svg {...props}><circle cx="9" cy="7" r="4"/><path d="M2 21v-2a7 7 0 0 1 14 0v2"/><path d="M19 8v6M16 11h6"/></svg>;
  return <svg {...props}><path d="M12 3 19 6v5c0 4.5-3 8-7 10-4-2-7-5.5-7-10V6l7-3Z"/><path d="m9 12 2 2 4-4"/></svg>;
}

export default function LandingAccountMenu() {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent) {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') { setOpen(false); triggerRef.current?.focus(); }
    }
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  function close() { setOpen(false); }

  return (
    <div className="qe-landing-account" ref={rootRef}>
      <button ref={triggerRef} type="button" className="qe-account-trigger"
        aria-label="Open account options" aria-haspopup="true" aria-expanded={open}
        aria-controls="qe-account-menu" onClick={() => setOpen(value => !value)}>
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></svg>
        <svg className={open ? 'qe-account-chevron is-open' : 'qe-account-chevron'} width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
      </button>
      {open && <div className="qe-account-menu" id="qe-account-menu" role="menu" aria-label="QuantaEdge account options">
        <div className="qe-account-menu__heading"><strong>Sign in to QuantaEdge</strong><span>Choose your account type</span></div>
        <Link href="/login/student" role="menuitem" className="qe-account-menu__item" onClick={close}>
          <span className="qe-account-menu__icon"><AccountIcon kind="student" /></span>
          <span><strong>Student login</strong><small>Continue your own learning</small></span><span className="qe-account-menu__arrow">→</span>
        </Link>
        <Link href="/login" role="menuitem" className="qe-account-menu__item" onClick={close}>
          <span className="qe-account-menu__icon"><AccountIcon kind="parent" /></span>
          <span><strong>Parent / guardian login</strong><small>Manage children and progress</small></span><span className="qe-account-menu__arrow">→</span>
        </Link>
        <div className="qe-account-menu__divider" />
        <Link href="/admin/login" role="menuitem" className="qe-account-menu__item" onClick={close}>
          <span className="qe-account-menu__icon"><AccountIcon kind="staff" /></span>
          <span><strong>Staff / admin</strong><small>Authorised platform access</small></span><span className="qe-account-menu__arrow">→</span>
        </Link>
        <p className="qe-account-menu__footnote">Secure access for every part of the learning platform.</p>
      </div>}
    </div>
  );
}
