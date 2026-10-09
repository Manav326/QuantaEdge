'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';

type StudentAccountMenuProps = {
  displayName: string;
  classCode: string | number;
  profileImageUrl?: string | null;
};

function MenuIcon({ kind }: { kind: 'book' | 'practice' | 'progress' | 'settings' | 'logout' }) {
  const common = { width: 18, height: 18, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true as const };
  if (kind === 'book') return <svg {...common}><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21V5.5Z"/><path d="M4 5.5V21M8 7h8M8 11h7"/></svg>;
  if (kind === 'practice') return <svg {...common}><path d="m12 3 2.4 5.1 5.6.8-4 3.9.9 5.5-4.9-2.6-4.9 2.6.9-5.5-4-3.9 5.6-.8L12 3Z"/></svg>;
  if (kind === 'progress') return <svg {...common}><path d="M4 19V5M4 19h17"/><path d="m7 15 4-4 3 2 5-6"/><path d="M15.5 7H19v3.5"/></svg>;
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
      router.replace('/login');
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
          <div className="student-account-menu__identity">
            <img src={avatarSrc} alt="" width="48" height="48" />
            <div>
              <strong>{displayName}</strong>
              <span>Student account</span>
              <small>Class {classCode}</small>
            </div>
          </div>
          <div className="student-account-menu__section-label">AAPKI PADHAI</div>
          <Link href="/student/learn" className="student-account-menu__item" onClick={closeMenu}>
            <span className="student-account-menu__icon"><MenuIcon kind="book" /></span><span><strong>Padhai continue karein</strong><small>Jahan chhoda tha, wahin se shuru karein</small></span><span className="student-account-menu__arrow">→</span>
          </Link>
          <Link href="/student/practice" className="student-account-menu__item" onClick={closeMenu}>
            <span className="student-account-menu__icon"><MenuIcon kind="practice" /></span><span><strong>Practice questions</strong><small>Practice se confidence badhayein</small></span><span className="student-account-menu__arrow">→</span>
          </Link>
          <Link href="/student/progress" className="student-account-menu__item" onClick={closeMenu}>
            <span className="student-account-menu__icon"><MenuIcon kind="progress" /></span><span><strong>Meri progress</strong><small>Apni learning journey dekhein</small></span><span className="student-account-menu__arrow">→</span>
          </Link>
          <div className="student-account-menu__divider" />
          <Link href="/student/profile" className="student-account-menu__item" onClick={closeMenu}>
            <span className="student-account-menu__icon"><MenuIcon kind="settings" /></span><span><strong>Profile aur settings</strong><small>Photo, school aur personal details</small></span><span className="student-account-menu__arrow">→</span>
          </Link>
          <div className="student-account-menu__divider" />
          <button type="button" className="student-account-menu__logout" onClick={() => void logout()} disabled={busy}>
            <MenuIcon kind="logout" /><span>{busy ? 'Please wait…' : 'Sign out'}</span>
          </button>
          <p className="student-account-menu__footnote">Aapki learning progress automatically save hoti hai.</p>
        </div>
      )}
    </div>
  );
}
