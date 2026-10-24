'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { UiText } from '../i18n/useUiTranslation';

function resolveAdminTarget(): string {
  const configured = process.env.NEXT_PUBLIC_EDUCATION_ADMIN_ORIGIN?.trim();
  let origin = '';
  if (configured) {
    try {
      const parsed = new URL(configured);
      const local = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1';
      if (parsed.protocol === 'https:' || (local && parsed.protocol === 'http:')) origin = parsed.origin;
    } catch { /* Invalid configuration must never send an administrator to an arbitrary target. */ }
  }
  if (!origin && typeof window !== 'undefined' && ['localhost', '127.0.0.1'].includes(window.location.hostname)) {
    origin = 'http://localhost:3001';
  }
  if (!origin) return '';

  const params = new URLSearchParams();
  const current = new URLSearchParams(window.location.search);
  if (current.get('view') === 'courses') {
    params.set('view', 'courses');
    const courseId = current.get('courseId') || '';
    if (/^[0-9]+$/.test(courseId)) params.set('courseId', courseId);
  }
  const suffix = params.toString();
  return new URL('/institution-operations' + (suffix ? '?' + suffix : ''), origin).toString();
}

/**
 * Compatibility route only. Institution management now lives in apps/admin.
 * Keep this page as a safe redirect while old bookmarks and deployed links age out;
 * the separate institution-admin UI and its third-workspace navigation are retired.
 */
export default function InstitutionRouteRedirect() {
  const [target, setTarget] = useState('');

  useEffect(() => {
    const adminTarget = resolveAdminTarget();
    setTarget(adminTarget);
    if (adminTarget) window.location.replace(adminTarget);
  }, []);

  return (
    <main className="qe-institution-compatibility" style={{ minHeight: '100vh', display: 'grid', placeItems: 'center', padding: 24, background: 'var(--institution-background)', color: 'var(--qe-color-text)' }}>
      <style>{'.qe-institution-compatibility { --institution-background:var(--qe-color-background); }'}</style>
      <section style={{ width: 'min(620px, 100%)', border: '1px solid var(--qe-color-border-subtle)', borderRadius: 20, padding: 28, background: 'var(--qe-color-surface)', boxShadow: 'var(--qe-elevation-2)' }}>
        <span style={{ fontSize: 12, fontWeight: 800, letterSpacing: '.1em', color: 'var(--qe-color-primary, #c2410c)' }}>QUANTAEDGE · ADMIN</span>
        <h1 style={{ fontSize: 'clamp(24px, 4vw, 32px)', lineHeight: 1.2, margin: '12px 0' }}><UiText message="admin.institutionOperations.redirectTitle" /></h1>
        <p style={{ color: 'var(--qe-color-text-muted, #536176)', fontSize: 15, lineHeight: 1.7 }}><UiText message="admin.institutionOperations.redirectDescription" /></p>
        {target ? (
          <Link href={target} style={{ display: 'inline-flex', marginTop: 14, padding: '11px 15px', borderRadius: 10, background: 'var(--qe-color-primary, #c2410c)', color: '#fff', fontWeight: 700 }}>
            <UiText message="admin.institutionOperations.openAdmin" /> ↗
          </Link>
        ) : (
          <p role="status" style={{ marginTop: 18, padding: 12, borderRadius: 10, background: 'var(--qe-color-background, #f6f7fa)', fontSize: 13, lineHeight: 1.6 }}>
            <UiText message="admin.institutionOperations.adminOriginMissing" />
          </p>
        )}
      </section>
    </main>
  );
}
