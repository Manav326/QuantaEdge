'use client';

import { UiText, useUiTranslation } from '../i18n/useUiTranslation';
import { useLocale } from './LanguageProvider';
import { useTenantBranding } from './TenantBrandingProvider';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';

function AccountIcon({ kind }: { kind: 'student' | 'parent' }) {
  const props = { width: 17, height: 17, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const, 'aria-hidden': true as const };
  if (kind === 'student') return <svg {...props}><path d="M3 10.5 12 5l9 5.5-9 5.5-9-5.5Z"/><path d="M6.5 13v4.5c3.2 2.6 7.8 2.6 11 0V13"/><path d="M21 11v6"/></svg>;
  if (kind === 'parent') return <svg {...props}><circle cx="9" cy="7" r="4"/><path d="M2 21v-2a7 7 0 0 1 14 0v2"/><path d="M19 8v6M16 11h6"/></svg>;
  return <svg {...props}><circle cx="9" cy="7" r="4"/><path d="M2 21v-2a7 7 0 0 1 14 0v2"/><path d="M19 8v6M16 11h6"/></svg>;
}

export default function LandingAccountMenu() {
  const t = useUiTranslation();
  const { locale } = useLocale();
  const { brand, institutionContextRequested } = useTenantBranding();
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
        aria-label={t('common.accountMenu.openOptions')} aria-haspopup="true" aria-expanded={open}
        aria-controls="qe-account-menu" onClick={() => setOpen(value => !value)}>
        <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><circle cx="12" cy="8" r="4"/><path d="M4 21v-2a8 8 0 0 1 16 0v2"/></svg>
        <svg className={open ? 'qe-account-chevron is-open' : 'qe-account-chevron'} width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
      </button>
      {open && <div className="qe-account-menu" id="qe-account-menu" role="menu" aria-label={t('common.accountMenu.options')}>
        <div className="qe-account-menu__heading"><strong>{brand ? (locale === "english" ? "Sign in to " + brand.displayName : brand.displayName + " में साइन इन करें") : institutionContextRequested ? (locale === "english" ? "Sign in to your learning space" : "अपने learning space में साइन इन करें") : <UiText message="legacy.web.sign.in.to.quantaedge.972d39f9" />}</strong><span><UiText message="legacy.web.choose.your.account.type.6d9b63b8" /></span></div>
        <Link href="/login/student" role="menuitem" className="qe-account-menu__item" onClick={close}>
          <span className="qe-account-menu__icon"><AccountIcon kind="student" /></span>
          <span><strong><UiText message="legacy.web.student.login.9541c2d5" /></strong><small><UiText message="legacy.web.continue.learning.7a7da80e" /></small></span><span className="qe-account-menu__arrow">→</span>
        </Link>
        <Link href="/login" role="menuitem" className="qe-account-menu__item" onClick={close}>
          <span className="qe-account-menu__icon"><AccountIcon kind="parent" /></span>
          <span><strong><UiText message="legacy.web.parent.guardian.login.b45fedd1" /></strong><small><UiText message="legacy.web.view.your.children.s.learning.and.prog.d7ffb0be" /></small></span><span className="qe-account-menu__arrow">→</span>
        </Link>
        <p className="qe-account-menu__footnote"><UiText message="legacy.web.secure.access.for.every.account.3b1b285c" /></p>
      </div>}
    </div>
  );
}
