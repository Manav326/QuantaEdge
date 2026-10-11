'use client';

import { LocaleText } from './LanguageProvider';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';

type StudentAccountMenuProps = {
  displayName: string;
  classCode: string | number;
  profileImageUrl?: string | null;
};

function MenuIcon({ kind }: { kind: 'settings' | 'logout' }) {
  const common = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true as const };
  if (kind === 'settings') return <svg {...common}><path d="M12 8.5a3.5 3.5 0 1 0 0 7 3.5 3.5 0 0 0 0-7Z"/><path d="m19.4 15 .1.1a1.8 1.8 0 0 1-2.5 2.5l-.1-.1a1.8 1.8 0 0 0-3 .9v.2a1.8 1.8 0 0 1-3.6 0v-.2a1.8 1.8 0 0 0-3-.9l-.1.1a1.8 1.8 0 0 1-2.5-2.5l.1-.1a1.8 1.8 0 0 0-.9-3h-.2a1.8 1.8 0 0 1 0-3.6h.2a1.8 1.8 0 0 0 .9-3l-.1-.1a1.8 1.8 0 0 1 2.5-2.5l.1.1a1.8 1.8 0 0 0 3-.9v-.2a1.8 1.8 0 0 1 3.6 0v.2a1.8 1.8 0 0 0 3 .9l.1-.1a1.8 1.8 0 0 1 2.5 2.5l-.1.1a1.8 1.8 0 0 0 .9 3h.2a1.8 1.8 0 0 1 0 3.6h-.2a1.8 1.8 0 0 0-.9 3Z"/></svg>;
  return <svg {...common}><path d="M10 17l5-5-5-5M15 12H3"/><path d="M12 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-6"/></svg>;
}

export default function StudentAccountMenu({ displayName, classCode, profileImageUrl }: StudentAccountMenuProps) {
  const avatarSrc = profileImageUrl || '/branding/student-avatar.svg';
  const router = useRouter();
  const accountRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!open) return;
    function handlePointerDown(event: PointerEvent) {
      if (!accountRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    }
    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
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
      router.replace('/login/student');
      router.refresh();
      setBusy(false);
    }
  }

  return (
    <div className="student-profile" ref={accountRef}>
      <button
        ref={triggerRef}
        type="button"
        className="student-account-trigger"
        aria-label={`Open profile and settings for ${displayName}`}
        aria-haspopup="true"
        aria-expanded={open}
        aria-controls="student-account-menu"
        onClick={() => { setOpen(value => !value); }}
      >
        <span className="student-account-trigger__copy">
          <strong>{displayName}</strong>
          <small>Class {classCode}</small>
        </span>
        <span className="student-avatar">
          <img src={avatarSrc} alt="" width="44" height="44" />
        </span>
        <svg className={`student-account-chevron${open ? ' is-open' : ''}`} width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
      </button>

      {open && (
        <div className="student-account-menu" id="student-account-menu" aria-label="Student account options">
          <Link href="/student/profile" className="student-account-menu__item" onClick={closeMenu}>
            <span className="student-account-menu__icon"><MenuIcon kind="settings" /></span>
            <span><strong><LocaleText hinglish="सेटिंग्स" english="Settings" /></strong><small><LocaleText hinglish="Profile और personal details" english="Profile and personal details" /></small></span>
            <span className="student-account-menu__arrow">→</span>
          </Link>
          <div className="student-account-menu__divider" />
          <button type="button" className="student-account-menu__logout" onClick={() => void logout()} disabled={busy}>
            <MenuIcon kind="logout" /><span>{busy ? 'Please wait…' : <LocaleText hinglish="लॉग आउट" english="Logout" />}</span>
          </button>
        </div>
      )}
    </div>
  );
}
