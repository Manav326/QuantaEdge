'use client';

import { useEffect, useRef, useState } from 'react';

type Tone = 'info' | 'success' | 'error';
type Detail = { message?: unknown; tone?: Tone };
type Active = { id: number; message: string; tone: Tone };
const EVENT = 'qe:notification';

export function notifyUser(message: string, tone: Tone = 'info') {
  if (typeof window === 'undefined') return;
  const clean = String(message || '').trim().slice(0, 700);
  if (clean) window.dispatchEvent(new CustomEvent<Detail>(EVENT, { detail: { message: clean, tone } }));
}

function explicitApiNotice(payload: any): { message: string; tone: Tone } | null {
  if (!payload || typeof payload !== 'object') return null;
  const candidate = payload.notification ?? payload.notice ?? payload.userMessage;
  if (typeof candidate === 'string') {
    const message = candidate.trim();
    if (!message) return null;
    return { message: message.slice(0, 700), tone: payload.level === 'error' ? 'error' : payload.level === 'success' ? 'success' : 'info' };
  }
  if (candidate && typeof candidate === 'object' && typeof candidate.message === 'string') {
    const message = candidate.message.trim();
    if (!message) return null;
    const tone = candidate.tone === 'success' || candidate.tone === 'error' ? candidate.tone : 'info';
    return { message: message.slice(0, 700), tone };
  }
  return null;
}

export default function GlobalNotificationHost() {
  const [active, setActive] = useState<Active | null>(null);
  const [copiedId, setCopiedId] = useState<number | null>(null);
  const serial = useRef(0);

  useEffect(() => {
    let dismissTimer: number | undefined;
    const publish = (message: string, tone: Tone) => {
      const clean = message.trim().slice(0, 700);
      if (!clean) return;
      const id = ++serial.current;
      if (dismissTimer !== undefined) window.clearTimeout(dismissTimer);
      setCopiedId(null);
      setActive({ id, message: clean, tone });
      dismissTimer = window.setTimeout(() => setActive(current => current?.id === id ? null : current), 8000);
    };
    const onNotification = (event: Event) => {
      const detail = (event as CustomEvent<Detail | string>).detail;
      if (typeof detail === 'string') publish(detail, 'info');
      else if (detail && typeof detail.message === 'string') {
        const tone = detail.tone === 'success' || detail.tone === 'error' ? detail.tone : 'info';
        publish(detail.message, tone);
      }
    };
    const originalFetch = window.fetch.bind(window);
    const wrappedFetch: typeof window.fetch = async (input, init) => {
      const response = await originalFetch(input, init);
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url;
      if (url.includes('/api/v1/')) {
        void response.clone().json().then(payload => {
          const notice = explicitApiNotice(payload);
          if (notice) publish(notice.message, notice.tone);
        }).catch(() => { /* Non-JSON responses do not carry user-facing notification fields. */ });
      }
      return response;
    };
    window.addEventListener(EVENT, onNotification);
    window.fetch = wrappedFetch;
    return () => {
      window.removeEventListener(EVENT, onNotification);
      if (window.fetch === wrappedFetch) window.fetch = originalFetch;
      if (dismissTimer !== undefined) window.clearTimeout(dismissTimer);
    };
  }, []);

  async function copyNotification() {
    if (!active) return;
    try {
      await navigator.clipboard.writeText(active.message);
      setCopiedId(active.id);
    } catch {
      try {
        const area = document.createElement('textarea');
        area.value = active.message;
        area.setAttribute('readonly', '');
        area.style.position = 'fixed';
        area.style.opacity = '0';
        document.body.appendChild(area);
        area.select();
        const copied = document.execCommand('copy');
        area.remove();
        if (copied) setCopiedId(active.id);
      } catch { /* The notification remains readable when clipboard access is denied. */ }
    }
  }

  useEffect(() => {
    if (copiedId === null) return;
    const id = copiedId;
    const timer = window.setTimeout(() => setCopiedId(current => current === id ? null : current), 1200);
    return () => window.clearTimeout(timer);
  }, [copiedId]);

  if (!active) return null;
  const accent = active.tone === 'error' ? 'var(--qe-color-error, #b42318)'
    : active.tone === 'success' ? 'var(--qe-color-success, #067647)'
    : 'var(--qe-color-border-subtle, var(--line, #d0d5dd))';
  return (
    <div role={active.tone === 'error' ? 'alert' : 'status'} aria-live={active.tone === 'error' ? 'assertive' : 'polite'}
      style={{ display: 'flex', alignItems: 'center', gap: 12, margin: '10px clamp(12px, 2.5vw, 32px)', padding: '11px 13px',
        border: '1px solid ' + accent, borderRadius: 12, background: 'var(--qe-color-surface, var(--card, #fff))',
        color: 'var(--qe-color-text, var(--ink, #152038))', boxShadow: '0 4px 18px rgba(0,0,0,.06)' }}>
      <span aria-hidden="true" style={{ display: 'grid', placeItems: 'center', flex: '0 0 24px', color: accent, fontWeight: 800 }}>
        {active.tone === 'error' ? '!' : active.tone === 'success' ? '✓' : 'i'}
      </span>
      <span style={{ flex: 1, minWidth: 0, fontSize: 13, lineHeight: 1.5, overflowWrap: 'anywhere' }}>{active.message}</span>
      <button type="button" onClick={() => void copyNotification()} aria-label="Copy notification" title="Copy notification"
        style={{ border: '1px solid var(--qe-color-border-subtle, var(--line, #d0d5dd))', borderRadius: 8, minWidth: 34, height: 34,
          background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 17 }}>
        {copiedId === active.id ? '✓' : '⧉'}
      </button>
      <button type="button" onClick={() => setActive(null)} aria-label="Dismiss notification" title="Dismiss notification"
        style={{ border: '1px solid var(--qe-color-border-subtle, var(--line, #d0d5dd))', borderRadius: 8, minWidth: 34, height: 34,
          background: 'transparent', color: 'inherit', cursor: 'pointer', fontSize: 20, lineHeight: 1 }}>×</button>
    </div>
  );
}
