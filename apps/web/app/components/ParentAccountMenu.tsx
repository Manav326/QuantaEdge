'use client';

import { LocaleText } from './LanguageProvider';
import AuthenticatedProfileImage from './AuthenticatedProfileImage';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';

type ParentAccountMenuProps = {
  displayName?: string;
  mobile?: string;
  profileImageUrl?: string | null;
  onLogout: () => void;
  loggingOut: boolean;
};

function ParentMenuIcon() {
  const common = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true as const };
  return <svg {...common}><circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/></svg>;
}

export default function ParentAccountMenu({
  displayName = 'Parent account',
  mobile = '',
  profileImageUrl,
  onLogout,
  loggingOut,
}: ParentAccountMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);

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
          <small><LocaleText hinglish="Parent account" english="Parent account" /></small>
        </span>
        <span className="parent-account__avatar"><AuthenticatedProfileImage src={profileImageUrl} fallbackSrc="/branding/parent-avatar.svg" alt="" width={44} height={44} /></span>
        <svg className={`parent-account__chevron${open ? ' is-open' : ''}`} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
      </button>

      {open && (
        <div className="parent-account__menu" id="parent-account-menu" aria-label="Parent account options">
          <Link href="/parent/profile" className="parent-account__item" onClick={closeMenu}>
            <span className="parent-account__icon"><ParentMenuIcon /></span>
            <span><strong><LocaleText hinglish="सेटिंग्स" english="Settings" /></strong><small><LocaleText hinglish="Profile और personal details" english="Profile and personal details" /></small></span>
            <span className="parent-account__arrow">→</span>
          </Link>
          <div className="parent-account__divider" />
          <button type="button" className="parent-account__logout" disabled={loggingOut}
            aria-label={loggingOut ? 'Logging out' : 'Log out'}
            onClick={() => { closeMenu(); onLogout(); }}>
            <span aria-hidden="true">↗</span>
            <span><LocaleText hinglish={loggingOut ? 'लॉग आउट…' : 'लॉग आउट'} english={loggingOut ? 'Logging out…' : 'Log out'} /></span>
          </button>
        </div>
      )}
    </div>
  );
}
