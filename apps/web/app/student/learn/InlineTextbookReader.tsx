'use client';

import { useEffect, useState } from 'react';
import type { CSSProperties } from 'react';

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
  const [page, setPage] = useState(() => Math.max(1, Math.min(Math.max(1, Number(item.page_count || 1)), Number(item.last_page || 1))));
  const [expanded, setExpanded] = useState(initiallyExpanded);
  const [loading, setLoading] = useState(true);
  const [pageError, setPageError] = useState('');
  const [retryKey, setRetryKey] = useState(0);
  const pageCount = Math.max(1, Number(item.page_count || 1));
  const imageUrl = '/api/v1/learning/documents/' + item.document_id + '/pages/' + page + (retryKey ? '?retry=' + retryKey : '');

  useEffect(() => {
    setLoading(true);
    setPageError('');
  }, [item.document_id, page, retryKey]);

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
      border: '1px solid #e5e7eb',
      borderRadius: 14,
      background: '#fff',
      boxShadow: '0 5px 20px rgba(23, 35, 56, .05)',
    }}>
      <div style={{
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 12,
        padding: '13px 15px',
        borderBottom: expanded ? '1px solid #edf0f4' : 0,
        background: '#fffaf6',
      }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ color: '#c2410c', fontSize: 10, fontWeight: 850, letterSpacing: '.08em', textTransform: 'uppercase' }}>{heading}</div>
          <strong style={{ display: 'block', marginTop: 4, color: '#1f2937', fontSize: 13, overflowWrap: 'anywhere' }}>{item.title}</strong>
          <small style={{ display: 'block', marginTop: 3, color: '#6b7280', fontSize: 11 }}>
            {item.edition ? item.edition + ' · ' : ''}{pageCount} pages · Secure online reader
          </small>
        </div>
        <button type="button" aria-expanded={expanded} onClick={() => setExpanded(value => !value)} style={{
          flex: '0 0 auto',
          border: '1px solid #f1d3bd',
          borderRadius: 9,
          padding: '8px 10px',
          background: '#fff',
          color: '#9a3412',
          fontSize: 11,
          fontWeight: 800,
          cursor: 'pointer',
        }}>{expanded ? 'Hide reader ↑' : 'Read here ↓'}</button>
      </div>

      {expanded && <>
        <div style={{
          display: 'flex',
          flexWrap: 'wrap',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 10,
          padding: '10px 13px',
          background: '#f8fafc',
          borderBottom: '1px solid #e8edf3',
        }}>
          <span style={{ color: '#667085', fontSize: 11 }}>Page <b style={{ color: '#293548' }}>{page}</b> of {pageCount}</span>
          <div style={{ display: 'flex', gap: 7 }}>
            <button type="button" disabled={page <= 1} onClick={() => setPage(value => Math.max(1, value - 1))} style={controlStyle(page <= 1)}>← Previous</button>
            <button type="button" disabled={page >= pageCount} onClick={() => setPage(value => Math.min(pageCount, value + 1))} style={controlStyle(page >= pageCount)}>Next →</button>
          </div>
        </div>
        <div style={{
          position: 'relative',
          minHeight: 220,
          display: 'flex',
          justifyContent: 'center',
          alignItems: 'flex-start',
          padding: 12,
          background: '#e9edf3',
        }}>
          {loading && <div role="status" style={{
            position: 'absolute',
            zIndex: 1,
            top: 22,
            left: 22,
            padding: '8px 11px',
            borderRadius: 8,
            background: '#fff',
            color: '#667085',
            fontSize: 11,
            boxShadow: '0 2px 8px rgba(0,0,0,.08)',
          }}>Loading page {page}…</div>}
          <img
            key={item.document_id + '-' + page + '-' + retryKey}
            src={imageUrl}
            alt={item.title + ', page ' + page}
            draggable={false}
            onLoad={() => { setLoading(false); setPageError(''); }}
            onError={() => { setLoading(false); setPageError('This page could not be displayed. Please retry.'); }}
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
          {pageError && <div role="alert" style={{ padding: 24, textAlign: 'center', color: '#9b3325', fontSize: 12 }}>
            <p>{pageError}</p>
            <button type="button" onClick={() => { setPageError(''); setLoading(true); setRetryKey(value => value + 1); }} style={controlStyle(false)}>Retry page</button>
          </div>}
        </div>
      </>}
    </section>
  );
}

function controlStyle(disabled: boolean): CSSProperties {
  return {
    border: '1px solid #dbe1e9',
    borderRadius: 8,
    padding: '7px 9px',
    background: disabled ? '#f1f3f6' : '#fff',
    color: disabled ? '#9ca3af' : '#344054',
    fontSize: 10,
    fontWeight: 750,
    cursor: disabled ? 'not-allowed' : 'pointer',
  };
}
