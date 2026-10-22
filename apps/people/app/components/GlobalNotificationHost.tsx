'use client';

import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { DEFAULT_UI_LOCALE, resolveUiLocale, translateUi } from '../i18n/catalog';

type Tone = 'info' | 'success' | 'warning' | 'error';
type Detail = { message?: unknown; tone?: Tone };
type Active = { id: number; message: string; tone: Tone };
const EVENT = 'qe:notification';
const MAX_VISIBLE = 3;
const AUTO_DISMISS_MS = 8000;

let notificationHostReady = false;
const queuedNotifications: Array<{ message: string; tone: Tone }> = [];

export function notifyUser(message: string, tone: Tone = 'info') {
  if (typeof window === 'undefined') return;
  const clean = cleanMessage(message);
  if (!clean) return;
  if (!notificationHostReady) {
    queuedNotifications.push({ message: clean, tone });
    return;
  }
  window.dispatchEvent(new CustomEvent<Detail>(EVENT, { detail: { message: clean, tone } }));
}

function cleanMessage(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const clean = value.replace(/\s+/g, ' ').trim();
  if (!clean || /<(?:!doctype|html|head|body|script)\b/i.test(clean)) return null;
  if (/traceback \(most recent call last\)|\bat\s+\S+\s+\(/i.test(clean)) return null;
  if (/^[\[{]\s*["']/.test(clean)) return null;
  return clean.slice(0, 700);
}

function selectedLocale() {
  try {
    const match = document.cookie.match(/(?:^|;\s*)qe-locale=([^;]+)/);
    const cookieLocale = match ? decodeURIComponent(match[1]) : null;
    const savedLocale = window.localStorage.getItem('qe-locale');
    return resolveUiLocale(cookieLocale ?? savedLocale) ?? DEFAULT_UI_LOCALE;
  } catch {
    return DEFAULT_UI_LOCALE;
  }
}

export default function GlobalNotificationHost() {
  const [notices, setNotices] = useState<Active[]>([]);
  const [copiedId, setCopiedId] = useState<number | null>(null);
  const isPeopleWorkspace = typeof document !== 'undefined' && Boolean(document.documentElement.dataset.qePeopleAppearance);
  const serial = useRef(0);
  const visibleRef = useRef<Active[]>([]);
  const queuedRef = useRef<Active[]>([]);
  const timersRef = useRef<Map<number, number>>(new Map());
  const dismissRef = useRef<(id: number) => void>(() => undefined);

  useEffect(() => {
    function commitVisible(next: Active[]) {
      visibleRef.current = next;
      setNotices(next);
    }
    function scheduleDismiss(id: number) {
      const timer = window.setTimeout(() => dismissRef.current(id), AUTO_DISMISS_MS);
      timersRef.current.set(id, timer);
    }
    function dismiss(id: number) {
      const timer = timersRef.current.get(id);
      if (timer !== undefined) window.clearTimeout(timer);
      timersRef.current.delete(id);
      if (!visibleRef.current.some(item => item.id === id)) return;
      const next = visibleRef.current.filter(item => item.id !== id);
      const waiting = queuedRef.current.shift();
      if (waiting && next.length < MAX_VISIBLE) {
        next.push(waiting);
        scheduleDismiss(waiting.id);
      }
      commitVisible(next);
    }
    function publish(message: string, tone: Tone) {
      const clean = cleanMessage(message);
      if (!clean) return;
      const notice: Active = { id: ++serial.current, message: clean, tone };
      if (visibleRef.current.length < MAX_VISIBLE) {
        commitVisible([...visibleRef.current, notice]);
        scheduleDismiss(notice.id);
      } else {
        queuedRef.current.push(notice);
      }
    }
    dismissRef.current = dismiss;

    const onNotification = (event: Event) => {
      const detail = (event as CustomEvent<Detail | string>).detail;
      if (typeof detail === 'string') publish(detail, 'info');
      else if (detail && typeof detail.message === 'string') {
        const tone = detail.tone === 'success' || detail.tone === 'warning' || detail.tone === 'error' ? detail.tone : 'info';
        publish(detail.message, tone);
      }
    };


    window.addEventListener(EVENT, onNotification);
    notificationHostReady = true;
    queuedNotifications.splice(0).forEach(notice => publish(notice.message, notice.tone));
    return () => {
      notificationHostReady = false;
      window.removeEventListener(EVENT, onNotification);
      timersRef.current.forEach(timer => window.clearTimeout(timer));
      timersRef.current.clear();
      queuedRef.current = [];
      visibleRef.current = [];
      dismissRef.current = () => undefined;
    };
  }, []);

  useEffect(() => {
    if (copiedId === null) return;
    const id = copiedId;
    const timer = window.setTimeout(() => setCopiedId(current => current === id ? null : current), 1200);
    return () => window.clearTimeout(timer);
  }, [copiedId]);

  async function copyNotification(notice: Active) {
    try {
      await navigator.clipboard.writeText(notice.message);
      setCopiedId(notice.id);
    } catch {
      try {
        const area = document.createElement('textarea');
        area.value = notice.message;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        const copied = document.execCommand('copy');
        area.remove();
        if (copied) setCopiedId(notice.id);
      } catch { /* Keep the message readable if clipboard access is blocked. */ }
    }
  }

  if (!notices.length || typeof document === 'undefined') return null;
  return createPortal(
    <div aria-label={translateUi(selectedLocale(), 'common.notifications.label')} style={{
      position: 'fixed',
      top: isPeopleWorkspace ? 'max(76px, calc(env(safe-area-inset-top) + 76px))' : 'max(12px, env(safe-area-inset-top))',
      left: isPeopleWorkspace ? '50%' : 'auto',
      right: isPeopleWorkspace ? 'auto' : 'max(12px, env(safe-area-inset-right))',
      transform: isPeopleWorkspace ? 'translateX(-50%)' : 'none',
      width: 'min(440px, calc(100vw - 24px))', maxHeight: isPeopleWorkspace ? 'calc(100dvh - 100px)' : 'calc(100dvh - 24px)',
      overflowY: 'auto', display: 'grid', alignContent: 'start', gap: 10,
      zIndex: 2147483647, pointerEvents: 'none'
    }}>
      {notices.map(notice => {
        const accent = notice.tone === 'error' ? 'var(--qe-color-error, #b42318)'
          : notice.tone === 'warning' ? 'var(--qe-color-warning, #b54708)'
          : notice.tone === 'success' ? 'var(--qe-color-success, #067647)'
          : 'var(--qe-color-border-subtle, var(--line, #d0d5dd))';
        return (
          <div key={notice.id} role={notice.tone === 'error' ? 'alert' : 'status'}
            aria-live={notice.tone === 'error' ? 'assertive' : 'polite'}
            style={{
              display: 'flex', alignItems: 'center', gap: 12, margin: 0, padding: '11px 13px',
              border: '1px solid ' + accent, borderRadius: 12,
              background: 'var(--qe-color-surface, var(--card, #fff))',
              color: 'var(--qe-color-text, var(--ink, #152038))',
              boxShadow: '0 8px 28px rgba(0,0,0,.16)', pointerEvents: 'auto',
              overflowWrap: 'anywhere'
            }}>
            <span aria-hidden="true" style={{ display: 'grid', placeItems: 'center', flex: '0 0 24px', color: accent, fontWeight: 800 }}>
              {notice.tone === 'error' || notice.tone === 'warning' ? '!' : notice.tone === 'success' ? '✓' : 'i'}
            </span>
            <span style={{ flex: 1, minWidth: 0, fontSize: 13, lineHeight: 1.5 }}>{notice.message}</span>
            <button type="button" onClick={() => void copyNotification(notice)}
              aria-label={translateUi(selectedLocale(), 'common.notifications.copy')}
              title={translateUi(selectedLocale(), 'common.notifications.copy')}
              style={{
                border: '1px solid var(--qe-color-border-subtle, var(--line, #d0d5dd))',
                borderRadius: 8, minWidth: 34, height: 34, background: 'transparent',
                color: 'inherit', cursor: 'pointer', fontSize: 17
              }}>
              {copiedId === notice.id ? '✓' : '⧉'}
            </button>
            <button type="button" onClick={() => dismissRef.current(notice.id)}
              aria-label={translateUi(selectedLocale(), 'common.notifications.dismiss')}
              title={translateUi(selectedLocale(), 'common.notifications.dismiss')}
              style={{
                border: '1px solid var(--qe-color-border-subtle, var(--line, #d0d5dd))',
                borderRadius: 8, minWidth: 34, height: 34, background: 'transparent',
                color: 'inherit', cursor: 'pointer', fontSize: 20, lineHeight: 1
              }}>
              ×
            </button>
          </div>
        );
      })}
    </div>,
    document.body
  );
}
