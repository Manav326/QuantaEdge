'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { UiText } from '../../i18n/useUiTranslation';
import styles from '../InstitutionRedirect.module.css';

function resolveAdminNotificationsTarget(): string {
  const configured = process.env.NEXT_PUBLIC_EDUCATION_ADMIN_ORIGIN?.trim();
  let origin = '';
  if (configured) {
    try {
      const parsed = new URL(configured);
      const local = parsed.hostname === 'localhost' || parsed.hostname === '127.0.0.1';
      if (parsed.protocol === 'https:' || (local && parsed.protocol === 'http:')) origin = parsed.origin;
    } catch { /* Ignore invalid Admin origins; never send users to an arbitrary host. */ }
  }
  if (!origin && typeof window !== 'undefined' && ['localhost', '127.0.0.1'].includes(window.location.hostname)) {
    origin = 'http://localhost:3001';
  }
  return origin ? new URL('/notifications', origin).toString() : '';
}

/** Old institution notification URL retained only for bookmarks; active tooling lives in Admin. */
export default function InstitutionNotificationsRedirect() {
  const [target, setTarget] = useState('');

  useEffect(() => {
    const destination = resolveAdminNotificationsTarget();
    setTarget(destination);
    if (destination) window.location.replace(destination);
  }, []);

  return (
    <main className={styles.page}>
      <section className={styles.card}>
        <span className={styles.eyebrow}>QUANTAEDGE · ADMIN</span>
        <h1 className={styles.title}><UiText message="admin.institutionOperations.notificationsRedirectTitle" /></h1>
        <p className={styles.message}><UiText message="admin.institutionOperations.notificationsRedirectDescription" /></p>
        {target ? (
          <Link href={target} className={styles.link}>
            <UiText message="admin.institutionOperations.openAdminNotifications" /> ↗
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
