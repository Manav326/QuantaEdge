'use client';

import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import { useLocale } from './LanguageProvider';
import { apiFetch } from './apiClient';
import { formatUserTimestamp } from './formatUserTimestamp';
import RichNotificationRenderer from './RichNotificationRenderer';
import styles from './NotificationInbox.module.css';

type NotificationItem = {
  recipient_id: number;
  state: string;
  delivered_at: string;
  read_at?: string | null;
  scope: string;
  title: string;
  summary: string;
  contentDocument: unknown;
  course_id?: number | null;
  expires_at?: string | null;
  unread?: boolean;
};
type InboxResponse = { items?: NotificationItem[]; nextBeforeId?: number | null };
type Role = 'student' | 'parent';

export function NotificationBell({ role }: { role: Role | null }) {
  const { locale } = useLocale();
  const [count, setCount] = useState(0);
  const [available, setAvailable] = useState(true);
  const english = locale === 'english';
  const loadCount = useCallback(async () => {
    try {
      const response = await apiFetch('/api/v1/notifications?limit=100&unreadOnly=true', { cache: 'no-store' });
      if (!response.ok) { setAvailable(false); return; }
      const data = await response.json() as InboxResponse;
      setCount(Array.isArray(data.items) ? data.items.length : 0);
      setAvailable(true);
    } catch { setAvailable(false); }
  }, []);

  useEffect(() => {
    void loadCount();
    const timer = window.setInterval(() => void loadCount(), 60000);
    const onFocus = () => void loadCount();
    window.addEventListener('focus', onFocus);
    return () => { window.clearInterval(timer); window.removeEventListener('focus', onFocus); };
  }, [loadCount]);

  if (!role || !available) return null;
  return <Link className={styles.bell} href={role === 'student' ? '/student/notifications' : '/parent/notifications'}
    aria-label={english ? `Notifications${count ? `, ${count} unread` : ''}` : `Notifications${count ? `, ${count} unread` : ''}`}>
    <svg aria-hidden="true" viewBox="0 0 24 24" width="19" height="19" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/>
    </svg>
    {count > 0 ? <span className={styles.bellCount}>{count > 99 ? '99+' : count}</span> : null}
  </Link>;
}

export default function NotificationInboxClient({ role }: { role: Role }) {
  const { locale } = useLocale();
  const english = locale === 'english';
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [cursor, setCursor] = useState<number | null>(null);
  const [filter, setFilter] = useState<'ALL' | 'UNREAD'>('ALL');
  const [category, setCategory] = useState('ALL');
  const [expanded, setExpanded] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const t = (en: string, hi: string) => english ? en : hi;
  const load = useCallback(async (beforeId?: number | null, append = false, unreadOnly = false) => {
    if (append) setLoadingMore(true); else setLoading(true);
    setError('');
    try {
      const params = new URLSearchParams({ limit: '50', unreadOnly: String(unreadOnly) });
      if (beforeId) params.set('beforeId', String(beforeId));
      const response = await apiFetch('/api/v1/notifications?' + params.toString(), { cache: 'no-store' });
      if (!response.ok) throw new Error(t('Notifications could not be loaded. Retry.', 'Notifications load nahi ho sakin. Dobara try karein.'));
      const data = await response.json() as InboxResponse;
      const next = Array.isArray(data.items) ? data.items : [];
      setItems(current => append
        ? [...current, ...next.filter(item => !current.some(old => old.recipient_id === item.recipient_id))]
        : next);
      setCursor(typeof data.nextBeforeId === 'number' ? data.nextBeforeId : null);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Notifications could not be loaded.', 'Notifications load nahi ho sakin.'));
    } finally {
      setLoading(false);
      setLoadingMore(false);
    }
  }, [english]);

  useEffect(() => { void load(null, false, false); }, [load]);

  async function markRead(item: NotificationItem) {
    if (!item.unread && item.state === 'READ') return;
    try {
      const response = await apiFetch('/api/v1/notifications/' + item.recipient_id + '/read', {
        method: 'POST', cache: 'no-store',
      });
      if (!response.ok) throw new Error(t('Read state could not be saved.', 'Read state save nahi ho saka.'));
      setItems(current => current.map(row => row.recipient_id === item.recipient_id
        ? { ...row, state: 'READ', read_at: new Date().toISOString(), unread: false } : row));
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Read state could not be saved.', 'Read state save nahi ho saka.'));
    }
  }

  async function markAllRead() {
    setSaving(true); setError('');
    try {
      const response = await apiFetch('/api/v1/notifications/read-all', { method: 'POST', cache: 'no-store' });
      if (!response.ok) throw new Error(t('Notifications could not be marked as read.', 'Notifications read nahi ho sakin.'));
      setItems(current => current.map(item => ({ ...item, state: 'READ', read_at: item.read_at || new Date().toISOString(), unread: false })));
    } catch (err) {
      setError(err instanceof Error ? err.message : t('Notifications could not be marked as read.', 'Notifications read nahi ho sakin.'));
    } finally { setSaving(false); }
  }

  const visible = items.filter(item => {
    const scope = String(item.scope || '').toUpperCase();
    const matchesFilter = filter === 'ALL' || (item.unread ?? item.state !== 'READ') && item.state !== 'DISMISSED';
    const matchesCategory = category === 'ALL' || scope === category;
    return matchesFilter && matchesCategory;
  });
  const unreadCount = items.filter(item => (item.unread ?? item.state !== 'READ') && item.state !== 'DISMISSED').length;

  return <main className={styles.page}>
    <div className={styles.pageHeader}>
      <div>
        <p className={styles.eyebrow}>{role === 'parent' ? t('FAMILY & LEARNING', 'FAMILY & LEARNING') : t('YOUR LEARNING', 'YOUR LEARNING')}</p>
        <h1>{t('Notifications', 'Notifications')}</h1>
        <p>{t('Important updates from your courses and institution, kept in one place.', 'Aapke course aur institution ke zaroori updates, ek hi jagah.')}</p>
      </div>
      <div className={styles.headerActions}>
        <span className={styles.unreadPill}>{unreadCount} {t('unread', 'unread')}</span>
        <button type="button" className={styles.secondaryButton} onClick={() => void markAllRead()} disabled={saving || unreadCount === 0}>
          {saving ? t('Saving…', 'Save ho raha hai…') : t('Mark all read', 'Sab read karein')}
        </button>
      </div>
    </div>

    <div className={styles.filters}>
      <div className={styles.segmented}>
        <button type="button" aria-pressed={filter === 'ALL'} className={filter === 'ALL' ? styles.selected : ''} onClick={() => setFilter('ALL')}>{t('All', 'All')}</button>
        <button type="button" aria-pressed={filter === 'UNREAD'} className={filter === 'UNREAD' ? styles.selected : ''} onClick={() => setFilter('UNREAD')}>{t('Unread', 'Unread')}</button>
      </div>
      <label className={styles.categoryLabel}>
        <span>{t('Category', 'Category')}</span>
        <select value={category} onChange={event => setCategory(event.target.value)}>
          <option value="ALL">{t('All categories', 'All categories')}</option>
          <option value="USER">{t('Personal', 'Personal')}</option>
          <option value="COURSE">{t('Course', 'Course')}</option>
          <option value="INSTITUTION">{t('Institution', 'Institution')}</option>
          <option value="HRMS_PLATFORM">{t('Platform', 'Platform')}</option>
        </select>
      </label>
    </div>

    {error ? <div className={styles.error} role="alert">{error}<button type="button" onClick={() => void load(null, false, false)}>{t('Retry', 'Dobara')}</button></div> : null}
    {loading ? <div className={styles.loading}><span/><span/><span/></div> : visible.length === 0 ? (
      <section className={styles.empty}>
        <div className={styles.emptyIcon} aria-hidden="true">♧</div>
        <h2>{t('You’re all caught up', 'Abhi koi naya notification nahi hai')}</h2>
        <p>{t('New course, school and account updates will appear here.', 'Course, school aur account ke naye updates yahan dikhenge.')}</p>
        <Link href={role === 'student' ? '/student' : '/parent'}>{t('Back to learning', 'Learning par wapas')}</Link>
      </section>
    ) : (
      <div className={styles.list}>
        {visible.map(item => {
          const isOpen = expanded === item.recipient_id;
          const unread = (item.unread ?? item.state !== 'READ') && item.state !== 'DISMISSED';
          const scope = String(item.scope || '').toUpperCase();
          const scopeLabel = scope === 'HRMS_PLATFORM' ? t('Platform', 'Platform')
            : scope === 'INSTITUTION' ? t('Institution', 'Institution')
            : scope === 'COURSE' ? t('Course', 'Course') : t('Personal', 'Personal');
          return <article key={item.recipient_id} className={styles.item + (unread ? ' ' + styles.unread : '')}>
            <button type="button" className={styles.itemHeader} aria-expanded={isOpen}
              onClick={() => {
                setExpanded(isOpen ? null : item.recipient_id);
                if (!isOpen) void markRead(item);
              }}>
              <span className={styles.scopeIcon} aria-hidden="true">{scope === 'HRMS_PLATFORM' ? '✦' : scope === 'INSTITUTION' ? '▦' : scope === 'COURSE' ? '▤' : '◉'}</span>
              <span className={styles.itemCopy}>
                <span className={styles.itemMeta}><span className={styles.category}>{scopeLabel}</span><time dateTime={item.delivered_at}>{item.delivered_at ? formatUserTimestamp(item.delivered_at) : t('Just now', 'Abhi')}</time></span>
                <strong>{item.title}</strong>
                <span className={styles.summary}>{item.summary}</span>
              </span>
              {unread ? <span className={styles.unreadDot} aria-label={t('Unread', 'Unread')} /> : null}
              <span className={styles.chevron} aria-hidden="true">{isOpen ? '−' : '+'}</span>
            </button>
            {isOpen ? <div className={styles.itemBody}>
              <RichNotificationRenderer contentDocument={item.contentDocument} surface="user" />
              {item.expires_at ? <p className={styles.expiry}>{t('Available until', 'Available until')} {formatUserTimestamp(item.expires_at)}</p> : null}
              {item.state === 'READ' ? <span className={styles.readLabel}>{t('Read', 'Read')}</span> : null}
            </div> : null}
          </article>;
        })}
      </div>
    )}
    {!loading && cursor ? <div className={styles.loadMore}>
      <button type="button" className={styles.secondaryButton} onClick={() => void load(cursor, true, false)} disabled={loadingMore}>
        {loadingMore ? t('Loading…', 'Load ho raha hai…') : t('Load older notifications', 'Purane notifications')}
      </button>
    </div> : null}
  </main>;
}
