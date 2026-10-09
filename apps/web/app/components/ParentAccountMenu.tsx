'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';

type ParentAccountMenuProps = {
  displayName?: string;
  mobile?: string;
  profileImageUrl?: string | null;
};

function ParentMenuIcon({ kind }: { kind: 'profile' | 'children' | 'report' | 'logout' }) {
  const common = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true as const };
  if (kind === 'profile') return <svg {...common}><circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>;
  if (kind === 'children') return <svg {...common}><circle cx="9" cy="8" r="3"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0"/><path d="M16 5.5a3 3 0 0 1 0 5.8M17 14a5 5 0 0 1 4 4.8"/></svg>;
  if (kind === 'report') return <svg {...common}><path d="M4 19V5M4 19h17"/><path d="m7 15 4-4 3 2 5-6"/><path d="M15.5 7H19v3.5"/></svg>;
  return <svg {...common}><path d="M10 17l5-5-5-5M15 12H3"/><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6"/></svg>;
}

export default function ParentAccountMenu({
  displayName = 'Parent account',
  mobile = '',
  profileImageUrl,
}: ParentAccountMenuProps) {
  const router = useRouter();
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const avatarSrc = profileImageUrl || '/branding/parent-avatar.svg';

  useEffect(() => {
    if (!open) return;
    function onOutsidePointer(event: PointerEvent) {
      if (!menuRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    }
    document.addEventListener('pointerdown', onOutsidePointer);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onOutsidePointer);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  function closeMenu() {
    setOpen(false);
  }

  async function logout() {
    setBusy(true);
    try {
      await fetch('/api/v1/auth/logout', { method: 'POST' });
    } finally {
      router.replace('/login');
      router.refresh();
      setBusy(false);
    }
  }

  return (
    <div className="parent-account" ref={menuRef}>
      <button
        ref={triggerRef}
        type="button"
        className="parent-account__trigger"
        aria-label={`Open parent account menu for ${displayName}`}
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls="parent-account-menu"
        onClick={() => setOpen(value => !value)}
      >
        <span className="parent-account__trigger-copy">
          <strong>{displayName}</strong>
          <small>Parent account</small>
        </span>
        <span className="parent-account__avatar"><img src={avatarSrc} alt="" width="44" height="44" /></span>
        <svg className={`parent-account__chevron${open ? ' is-open' : ''}`} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
      </button>

      {open && (
        <div className="parent-account__menu" id="parent-account-menu" aria-label="Parent account options">
          <div className="parent-account__identity">
            <img src={avatarSrc} alt="" width="50" height="50" />
            <div>
              <strong>{displayName}</strong>
              <span>Parent account</span>
              {mobile && <small>{mobile}</small>}
            </div>
          </div>
          <div className="parent-account__section-label">AAPKI FAMILY</div>
          <Link href="/parent" className="parent-account__item" onClick={closeMenu}>
            <span className="parent-account__icon"><ParentMenuIcon kind="report" /></span>
            <span><strong>Family learning report</strong><small>Bachche ki learning summary dekhein</small></span>
            <span className="parent-account__arrow">→</span>
          </Link>
          <Link href="/parent/children" className="parent-account__item" onClick={closeMenu}>
            <span className="parent-account__icon"><ParentMenuIcon kind="children" /></span>
            <span><strong>Bachchon ke profiles manage karein</strong><small>Subjects aur login access set karein</small></span>
            <span className="parent-account__arrow">→</span>
          </Link>
          <div className="parent-account__divider" />
          <Link href="/parent/profile" className="parent-account__item" onClick={closeMenu}>
            <span className="parent-account__icon"><ParentMenuIcon kind="profile" /></span>
            <span><strong>Parent profile aur settings</strong><small>Photo, contact aur personal details</small></span>
            <span className="parent-account__arrow">→</span>
          </Link>
          <div className="parent-account__divider" />
          <button type="button" className="parent-account__logout" onClick={() => void logout()} disabled={busy}>
            <ParentMenuIcon kind="logout" /><span>{busy ? 'Please wait…' : 'Sign out'}</span>
          </button>
          <p className="parent-account__footnote">Aapki family details sirf account aur bachche ki learning support ke liye use hoti hain.</p>
        </div>
      )}
    </div>
  );
}
