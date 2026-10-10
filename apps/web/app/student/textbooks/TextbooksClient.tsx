'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { LocaleText } from '../../components/LanguageProvider';

type LearningDocument = {
  document_id: number;
  scope: 'SUBJECT_BOOK' | 'CHAPTER_PDF';
  title: string;
  edition?: string | null;
  page_count: number;
  last_page?: number;
  class_code: string;
  subject_code: string;
  subject_name: string;
  chapter_id?: number | null;
  chapter_code?: string | null;
  chapter_name?: string | null;
  last_opened_at?: string | null;
};

async function api(url: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers || {});
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(url, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) throw Object.assign(new Error('Please log in to open your textbook library.'), { auth: true });
    throw new Error(body.detail || body.message || body.error || ('Request failed (' + response.status + ')'));
  }
  return body;
}

export default function TextbooksClient() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const documentId = Number(searchParams.get('documentId') || 0);
  const readerRef = useRef<HTMLElement | null>(null);
  const [documents, setDocuments] = useState<LearningDocument[]>([]);
  const [selected, setSelected] = useState<LearningDocument | null>(null);
  const [subjectFilter, setSubjectFilter] = useState('ALL');
  const [currentPage, setCurrentPage] = useState(1);
  const [zoom, setZoom] = useState(100);
  const [thumbnailsOpen, setThumbnailsOpen] = useState(false);
  const [fitWidth, setFitWidth] = useState(true);
  const [loading, setLoading] = useState(true);
  const [imageLoading, setImageLoading] = useState(false);
  const [error, setError] = useState('');
  const [pageError, setPageError] = useState('');
  const [notice, setNotice] = useState('');
  const [fullscreen, setFullscreen] = useState(false);

  const loadDocuments = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const data = await api('/api/v1/learning/documents');
      setDocuments(Array.isArray(data) ? data : []);
    } catch (e: any) {
      if (e?.auth) { router.replace('/login/student'); return; }
      setError(e instanceof Error ? e.message : 'Your textbook library could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [router]);

  useEffect(() => { void loadDocuments(); }, [loadDocuments]);

  useEffect(() => {
    let active = true;
    if (!documentId) { setSelected(null); return; }
    setError('');
    api('/api/v1/learning/documents/' + documentId)
      .then(data => {
        if (!active) return;
        setSelected(data);
        const lastPage = Number(data.last_page || 1);
        setCurrentPage(Math.max(1, Math.min(Number(data.page_count || 1), lastPage)));
        setPageError('');
        setZoom(100);
        setFitWidth(true);
      })
      .catch((e: any) => {
        if (!active) return;
        if (e?.auth) { router.replace('/login/student'); return; }
        setError(e instanceof Error ? e.message : 'This textbook is not available to your account.');
        setSelected(null);
      });
    return () => { active = false; };
  }, [documentId, router]);

  const pageCount = Number(selected?.page_count || 0);
  const firstThumb = Math.max(1, Math.min(currentPage - 6, pageCount - 19));
  const thumbnails = useMemo(() => {
    const count = Math.min(20, pageCount);
    return Array.from({ length: count }, (_v, index) => firstThumb + index);
  }, [firstThumb, pageCount]);
  const pageUrl = selected ? '/api/v1/learning/documents/' + selected.document_id + '/pages/' + currentPage : '';
  const visibleDocuments = documents.filter(item => subjectFilter === 'ALL' || item.subject_code === subjectFilter);
  const subjectLabel = selected?.subject_code === 'maths' ? 'Mathematics' : selected?.subject_name || 'Science';

  useEffect(() => {
    if (!selected || currentPage < 1 || currentPage > pageCount) return;
    const timeout = window.setTimeout(() => {
      void api('/api/v1/learning/documents/' + selected.document_id + '/progress', {
        method: 'PUT', body: JSON.stringify({ lastPage: currentPage }),
      }).catch(() => undefined);
    }, 350);
    return () => window.clearTimeout(timeout);
  }, [selected, currentPage, pageCount]);

  useEffect(() => {
    if (!documentId) return;
    function onKeyDown(event: KeyboardEvent) {
      if (event.altKey || event.ctrlKey || event.metaKey) return;
      const target = event.target as HTMLElement | null;
      if (target && ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(target.tagName)) return;
      if (event.key === 'ArrowRight' || event.key === 'PageDown') {
        event.preventDefault(); setCurrentPage(p => Math.min(pageCount, p + 1));
      } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
        event.preventDefault(); setCurrentPage(p => Math.max(1, p - 1));
      } else if (event.key === 'Home') { event.preventDefault(); setCurrentPage(1); }
      else if (event.key === 'End') { event.preventDefault(); setCurrentPage(pageCount); }
    }
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [documentId, pageCount]);

  useEffect(() => {
    function updateFullscreen() { setFullscreen(Boolean(document.fullscreenElement)); }
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);

  async function toggleFullscreen() {
    try {
      if (!document.fullscreenElement && readerRef.current) await readerRef.current.requestFullscreen();
      else if (document.fullscreenElement) await document.exitFullscreen();
    } catch { setPageError('Fullscreen is not available in this browser.'); }
  }

  function openDocument(id: number) {
    setError(''); setNotice(''); setPageError('');
    router.push('/student/textbooks?documentId=' + id);
  }

  function returnToLibrary() {
    setSelected(null); setError(''); setPageError('');
    router.push('/student/textbooks');
  }

  const progressPercent = pageCount ? Math.round((currentPage / pageCount) * 100) : 0;

  return <main className="app-shell qe-textbook-shell">
    <header className="app-header qe-textbook-header">
      <Link href="/student" className="qe-textbook-brand"><span className="qe-textbook-mark">Q</span><span><b>QuantaEdge</b><small>STUDENT LEARNING</small></span></Link>
      <div className="qe-textbook-header-actions">
        <Link href="/student/learn" className="qe-textbook-back"><span>←</span><LocaleText hinglish="पढ़ाई" english="Learning" /></Link>
        <Link href="/student" className="qe-textbook-home">⌂</Link>
      </div>
    </header>

    {!documentId ? <section className="qe-textbook-library">
      <div className="qe-textbook-library-hero">
        <div className="qe-textbook-hero-copy"><span className="qe-textbook-eyebrow"><i/> YOUR PERSONAL READING SPACE</span>
          <h1><LocaleText hinglish="आपकी किताबें, एक जगह" english="Your textbooks, all in one place" /></h1>
          <p><LocaleText hinglish="अपनी subject book खोलें या chapter के हिसाब से पढ़ें। आपकी reading progress अपने-आप save होती है।" english="Open the complete subject book or read a specific chapter. Your reading progress saves automatically." /></p>
          <div className="qe-textbook-hero-chips"><span>↔ Page navigation</span><span>＋ Zoom controls</span><span>✓ Progress saved</span></div>
        </div>
        <div className="qe-textbook-book-art" aria-hidden="true"><div className="qe-book-orbit orbit-one"/><div className="qe-book-orbit orbit-two"/><div className="qe-book-cover"><span>Q</span><b>LEARN<br/>EVERY DAY</b><small>YOUR TEXTBOOK</small></div></div>
      </div>

      {error && <div className="qe-textbook-error" role="alert">{error}</div>}
      <div className="qe-textbook-library-toolbar"><div><span className="qe-textbook-eyebrow">RESOURCE LIBRARY</span><h2>Available textbooks</h2><p>Only the books and chapters available to your enrolled subjects are shown.</p></div>
        <label>Subject <select value={subjectFilter} onChange={e => setSubjectFilter(e.target.value)}><option value="ALL">All subjects</option><option value="maths">Mathematics</option><option value="science">Science</option></select></label>
      </div>

      {loading ? <div className="qe-textbook-loading"><span className="qe-reader-spinner"/> Loading your textbook library…</div>
        : visibleDocuments.length === 0 ? <div className="qe-textbook-empty"><span>▤</span><h3>No published PDFs available yet</h3><p>When your teacher publishes a subject book or chapter PDF for your class, it will appear here.</p><Link href="/student/learn">Continue with lessons →</Link></div>
        : <div className="qe-textbook-resource-groups">
          {(['maths', 'science'] as const).filter(code => subjectFilter === 'ALL' || subjectFilter === code).map(code => {
            const rows = visibleDocuments.filter(item => item.subject_code === code);
            if (!rows.length) return null;
            const subjectBooks = rows.filter(item => item.scope === 'SUBJECT_BOOK');
            const chapterBooks = rows.filter(item => item.scope === 'CHAPTER_PDF');
            return <section className="qe-textbook-subject" key={code}>
              <div className="qe-textbook-subject-heading"><span className="qe-subject-symbol">{code === 'maths' ? '∑' : '⚗'}</span><div><h3>{code === 'maths' ? 'Mathematics' : 'Science'}</h3><small>Class {rows[0].class_code} · {rows[0].subject_name}</small></div><span className="qe-textbook-resource-count">{rows.length} resource{rows.length === 1 ? '' : 's'}</span></div>
              {subjectBooks.length > 0 && <div className="qe-textbook-book-grid">{subjectBooks.map(item => <article className="qe-textbook-resource-card qe-book-card" key={item.document_id}>
                <div className="qe-resource-card-top"><span className="qe-resource-type">COMPLETE BOOK</span><span className="qe-resource-pages">{item.page_count} pages</span></div><div className="qe-resource-cover large-cover"><span>{code === 'maths' ? '∑' : '⚗'}</span><b>{item.title}</b><small>{item.edition || 'Official textbook'}</small></div><div className="qe-resource-card-bottom"><div><h4>{item.title}</h4><p>{item.edition || 'Complete subject book'}</p></div><button type="button" onClick={() => openDocument(item.document_id)}>Read online <span>↗</span></button></div>
              </article>)}</div>}
              {chapterBooks.length > 0 && <><div className="qe-textbook-chapter-heading"><h4>Chapter PDFs</h4><span>{chapterBooks.length} chapters</span></div><div className="qe-textbook-chapter-grid">{chapterBooks.map(item => <article className="qe-textbook-chapter-card" key={item.document_id}><div className="qe-chapter-pdf-icon">▤</div><div className="qe-chapter-copy"><span>{item.chapter_code || 'CHAPTER'}</span><h4>{item.chapter_name || item.title}</h4><p>{item.title} · {item.page_count} pages</p></div><button type="button" onClick={() => openDocument(item.document_id)} aria-label={'Read ' + (item.chapter_name || item.title) + ' online'}>→</button></article>)}</div></>}
            </section>;
          })}
        </div>}
      <div className="qe-textbook-privacy-note"><span>▣</span><p><b>Private reading mode.</b> The viewer does not provide a PDF download or print button. To deliver pages securely, QuantaEdge checks your account and subject access before rendering each page. Screenshots or capturing displayed pages cannot be fully prevented by a website.</p></div>
    </section> : <section className="qe-document-reader" ref={readerRef as React.RefObject<HTMLElement>}>
      <div className="qe-reader-heading"><div className="qe-reader-breadcrumb"><button type="button" onClick={returnToLibrary}>← Textbook library</button><span>/</span><span>{selected?.scope === 'SUBJECT_BOOK' ? 'Complete subject book' : selected?.chapter_name || 'Chapter PDF'}</span></div>
        <div className="qe-reader-title-row"><div><span className="qe-textbook-eyebrow">{selected?.scope === 'SUBJECT_BOOK' ? 'SUBJECT TEXTBOOK' : 'CHAPTER READING'} · {subjectLabel.toUpperCase()}</span><h1>{selected?.title || 'Loading textbook…'}</h1><p>{selected?.chapter_name ? selected.chapter_name + ' · ' : ''}{selected?.edition || 'View-only online reader'}</p></div>
          {selected && <div className="qe-reader-progress"><strong>{progressPercent}%</strong><span>read</span><div><i style={{width: progressPercent + '%'}}/></div></div>}
        </div>
      </div>

      {error ? <div className="qe-textbook-error" role="alert">{error}<button type="button" onClick={returnToLibrary}>Back to library</button></div>
        : !selected ? <div className="qe-textbook-loading"><span className="qe-reader-spinner"/> Preparing the secure reader…</div>
        : <>
          <div className="qe-reader-toolbar" role="toolbar" aria-label="Textbook viewer controls">
            <div className="qe-reader-page-controls"><button type="button" aria-label="Previous page" disabled={currentPage <= 1} onClick={() => setCurrentPage(p => Math.max(1, p - 1))}>‹</button><label><span>Page</span><input type="number" min="1" max={pageCount} value={currentPage} onChange={e => { const value = Number(e.target.value); if (Number.isFinite(value)) setCurrentPage(Math.max(1, Math.min(pageCount, value))); }} onBlur={() => setCurrentPage(p => Math.max(1, Math.min(pageCount, p)))}/><span>of {pageCount}</span></label><button type="button" aria-label="Next page" disabled={currentPage >= pageCount} onClick={() => setCurrentPage(p => Math.min(pageCount, p + 1))}>›</button></div>
            <div className="qe-reader-toolbar-divider"/>
            <div className="qe-reader-zoom"><button type="button" aria-label="Zoom out" disabled={zoom <= 60} onClick={() => {setFitWidth(false);setZoom(z => Math.max(60, z - 10));}}>−</button><span>{fitWidth ? 'Fit width' : zoom + '%'}</span><button type="button" aria-label="Zoom in" disabled={zoom >= 160} onClick={() => {setFitWidth(false);setZoom(z => Math.min(160, z + 10));}}>＋</button></div>
            <button type="button" className="qe-reader-tool-button" onClick={() => setFitWidth(value => !value)}>{fitWidth ? 'Actual size' : 'Fit width'}</button>
            <button type="button" className={'qe-reader-tool-button' + (thumbnailsOpen ? ' selected' : '')} onClick={() => setThumbnailsOpen(value => !value)}>▤ Pages</button>
            <button type="button" className="qe-reader-tool-button qe-reader-fullscreen" onClick={() => void toggleFullscreen()}>{fullscreen ? 'Exit full screen' : '⛶ Full screen'}</button>
          </div>

          <div className={'qe-reader-workspace' + (thumbnailsOpen ? ' with-thumbnails' : '')}>
            {thumbnailsOpen && <aside className="qe-reader-thumbnails" aria-label="Page thumbnails"><div className="qe-reader-thumbs-head"><b>Pages</b><small>{pageCount} total</small></div><div className="qe-reader-thumb-list">{thumbnails.map(number => <button type="button" key={number} className={number === currentPage ? 'active' : ''} onClick={() => {setCurrentPage(number);setPageError('');}}><img src={'/api/v1/learning/documents/' + selected.document_id + '/pages/' + number} alt="" loading="lazy" draggable={false}/><span>Page {number}</span></button>)}</div><div className="qe-reader-thumbs-foot">Showing pages {thumbnails[0] || 0}–{thumbnails[thumbnails.length - 1] || 0}</div></aside>}
            <div className="qe-reader-page-stage" onContextMenu={e => e.preventDefault()}><div className="qe-reader-canvas-top"><span><i/> Secured page rendering</span><span>{currentPage} / {pageCount}</span></div>
              <div className={'qe-reader-page-viewport' + (fitWidth ? ' fit-width' : '')} key={selected.document_id + '-' + currentPage}>
                {imageLoading && <div className="qe-reader-image-loading"><span className="qe-reader-spinner"/> Rendering page {currentPage}…</div>}
                <img className="qe-reader-page-image" src={pageUrl} alt={'Page ' + currentPage + ' of ' + selected.title} draggable={false} onLoad={() => {setImageLoading(false);setPageError('');}} onLoadStart={() => setImageLoading(true)} onError={() => {setImageLoading(false);setPageError('This page could not be rendered. Try again or return to the library.');}} style={{width: fitWidth ? '100%' : zoom + '%'}} />
                {pageError && <div className="qe-reader-page-error" role="alert">{pageError}<button type="button" onClick={() => {setPageError('');setImageLoading(true);const image=document.querySelector('.qe-reader-page-image') as HTMLImageElement|null;if(image)image.src=pageUrl+'?retry='+(Date.now());}}>Retry page</button></div>}
              </div>
              <div className="qe-reader-canvas-bottom"><span>Private textbook viewer · Page {currentPage}</span><span>Use ← → or Page Up / Page Down to navigate</span></div>
            </div>
          </div>
          <div className="qe-reader-bottom-nav"><button type="button" disabled={currentPage <= 1} onClick={() => setCurrentPage(p => Math.max(1, p - 1))}>← Previous page</button><span>Page {currentPage} of {pageCount}</span><button type="button" disabled={currentPage >= pageCount} onClick={() => setCurrentPage(p => Math.min(pageCount, p + 1))}>Next page →</button></div>
          <p className="qe-reader-viewonly-note">View-only mode · Original PDF downloads are not provided. This can deter casual downloading but cannot fully prevent screenshots or capture of displayed pages.</p>
        </>}
    </section>}
  </main>;
}
