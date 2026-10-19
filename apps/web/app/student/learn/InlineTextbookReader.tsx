'use client';

import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';
import AuthenticatedPageImage from '../components/AuthenticatedPageImage';
import { useUiTranslation } from '../../i18n/useUiTranslation';

type InlineDocument = {
  document_id: number;
  title: string;
  scope: 'SUBJECT_BOOK' | 'CHAPTER_PDF';
  page_count: number;
  edition?: string | null;
  chapter_name?: string | null;
  chapter_code?: string | null;
  last_page?: number;
};

export default function InlineTextbookReader({
  item,
  heading,
  initiallyExpanded = false,
}: {
  item: InlineDocument;
  heading: string;
  initiallyExpanded?: boolean;
}) {
  const t = useUiTranslation();
  const [page, setPage] = useState(() => Math.max(1, Math.min(Math.max(1, Number(item.page_count || 1)), Number(item.last_page || 1))));
  const [expanded, setExpanded] = useState(initiallyExpanded);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState('');
  const [retryKey, setRetryKey] = useState(0);
  const pageCount = Math.max(1, Number(item.page_count || 1));
  const imageUrl = '/api/v1/learning/documents/' + item.document_id + '/pages/' + page + (retryKey ? '?retry=' + retryKey : '');

  useEffect(() => {
    if (!expanded) return;
    setLoading(true);
    setPageError('');
  }, [item.document_id, page, retryKey, expanded]);

  useEffect(() => {
    if (!expanded) return;
    const timeout = window.setTimeout(() => {
      void fetch('/api/v1/learning/documents/' + item.document_id + '/progress', {
        method: 'PUT',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lastPage: page }),
      }).catch(() => undefined);
    }, 350);
    return () => window.clearTimeout(timeout);
  }, [item.document_id, page, expanded]);

  return (
    <section className="qe-inline-textbook" aria-label={heading} style={{
      margin: '14px 0 20px',
      overflow: 'hidden',
      border: '1px solid var(--qe-color-border-subtle)',
      borderRadius: 16,
      background: 'var(--qe-color-surface)',
      boxShadow: 'var(--qe-elevation-1)',
    }}>
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 12,
        padding: '13px 15px',
        borderBottom: expanded ? '1px solid var(--qe-color-border-subtle)' : 'none',
        background: 'var(--qe-color-primary-container)',
      }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ color: 'var(--qe-color-primary-text)', fontSize: 10, fontWeight: 850, letterSpacing: '.08em', textTransform: 'uppercase' }}>{heading}</div>
          <strong style={{ display: 'block', marginTop: 4, color: 'var(--qe-color-text)', fontSize: 13, overflowWrap: 'anywhere' }}>{item.title}</strong>
          <small style={{ display: 'block', marginTop: 3, color: 'var(--qe-color-text-muted)', fontSize: 11 }}>
            {item.edition ? item.edition + ' · ' : ''}{t('web.textbookReader.pageCount', { count: pageCount })} · {t('web.textbookReader.secureOnline')}
          </small>
        </div>
        <button type="button" aria-expanded={expanded} onClick={() => setExpanded(value => !value)} style={{
          flex: '0 0 auto',
          border: '1px solid var(--qe-color-border-subtle)',
          borderRadius: 9,
          padding: '8px 10px',
          background: 'var(--qe-color-surface)',
          color: 'var(--qe-color-primary-text)',
          fontSize: 11,
          fontWeight: 800,
          cursor: 'pointer',
        }}>{expanded ? t('web.textbookReader.hideReader') : t('web.textbookReader.readHere')}</button>
      </div>

      {expanded && <>
        <div style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 10,
          padding: '10px 13px',
          background: 'var(--qe-color-surface-elevated)',
          borderBottom: '1px solid var(--qe-color-border-subtle)',
        }}>
          <span style={{ color: 'var(--qe-color-text-muted)', fontSize: 11 }}>{t('web.textbookReader.pageLabel')} <b style={{ color: 'var(--qe-color-text)' }}>{page}</b> {t('web.textbookReader.of')} {pageCount}</span>
          <div style={{ display: 'flex', gap: 7 }}>
            <button type="button" disabled={page <= 1} onClick={() => setPage(value => Math.max(1, value - 1))} style={controlStyle(page <= 1)}>{t('web.textbookReader.previous')}</button>
            <button type="button" disabled={page >= pageCount} onClick={() => setPage(value => Math.min(pageCount, value + 1))} style={controlStyle(page >= pageCount)}>{t('web.textbookReader.next')}</button>
          </div>
        </div>
        <div style={{
          position: 'relative',
          minHeight: 220,
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'flex-start',
          padding: 12,
          background: 'var(--qe-color-background)',
        }}>
          {loading && <div role="status" style={{
            position: 'absolute',
            zIndex: 1,
            top: 22,
            left: 22,
            padding: '8px 11px',
            borderRadius: 8,
            background: 'var(--qe-color-surface)',
            color: 'var(--qe-color-text-muted)',
            fontSize: 11,
            boxShadow: '0 2px 8px rgba(0,0,0,.08)',
          }}>{t('web.textbookReader.loadingPage', { page })}</div>}
          <AuthenticatedPageImage
            key={item.document_id + '-' + page + '-' + retryKey}
            src={imageUrl}
            alt={t('web.textbookReader.pageAlt', { title: item.title, page })}
            onLoad={() => { setLoading(false); setPageError(''); }}
            onError={() => { setLoading(false); setPageError(t('web.textbookReader.loadFailed')); }}
            style={{
              display: pageError ? 'none' : 'block',
              width: '100%',
              maxWidth: 1000,
              height: 'auto',
              borderRadius: 3,
              background: '#fff',
              boxShadow: '0 3px 16px rgba(20, 30, 50, .12)',
              userSelect: 'none',
            }}
          />
          {pageError && <div role="alert" style={{ padding: 24, textAlign: 'center', color: 'var(--qe-color-error)', fontSize: 12 }}>
            <p>{pageError}</p>
            <button type="button" onClick={() => { setPageError(''); setLoading(true); setRetryKey(value => value + 1); }} style={controlStyle(false)}>{t('web.textbookReader.retryPage')}</button>
          </div>}
        </div>
      </>}
    </section>
  );
}

function controlStyle(disabled: boolean): CSSProperties {
  return {
    border: '1px solid var(--qe-color-border-subtle)',
    borderRadius: 8,
    padding: '7px 9px',
    background: disabled ? 'var(--qe-color-disabled-background)' : 'var(--qe-color-surface)',
    color: disabled ? 'var(--qe-color-disabled-text)' : 'var(--qe-color-text)',
    fontSize: 10,
    fontWeight: 750,
    cursor: disabled ? 'not-allowed' : 'pointer',
  };
}
