'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { UiText } from '../i18n/useUiTranslation';
import styles from './InstitutionRedirect.module.css';

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
    <main className={styles.page}>
      <section className={styles.card}>
        <span className={styles.eyebrow}>QUANTAEDGE · ADMIN</span>
        <h1 className={styles.title}><UiText message="admin.institutionOperations.redirectTitle" /></h1>
        <p className={styles.message}><UiText message="admin.institutionOperations.redirectDescription" /></p>
        {target ? (
          <Link href={target} className={styles.link}>
            <UiText message="admin.institutionOperations.openAdmin" /> ↗
          </Link>
        ) : (
          <p role="status" className={styles.status}>
            <UiText message="admin.institutionOperations.adminOriginMissing" />
          </p>
        )}
      </section>
    </main>
  );
}
