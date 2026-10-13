'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useLocale } from '../../components/LanguageProvider';
import AuthenticatedPageImage from '../components/AuthenticatedPageImage';
import PremiumPagination from '../../components/PremiumPagination';
import './textbooks.css';

type LearningDocument = {
  document_id: number;
  scope: 'SUBJECT_BOOK' | 'CHAPTER_PDF';
  book_part?: number | null;
  language?: 'hi' | 'en';
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
  const { locale, learningLanguage } = useLocale();
  const requestedLanguage = learningLanguage;
  const requestedSubjectCode = searchParams.get('subjectCode') || '';
  const documentId = Number(searchParams.get('documentId') || 0);
  const readerRef = useRef<HTMLElement | null>(null);
  const [documents, setDocuments] = useState<LearningDocument[]>([]);
  const [documentsLanguage, setDocumentsLanguage] = useState<'hi' | 'en' | null>(null);
  const [selected, setSelected] = useState<LearningDocument | null>(null);
  const [subjectFilter, setSubjectFilter] = useState(() => searchParams.get('subjectCode') || 'ALL');
  const [expandedSubject, setExpandedSubject] = useState('');
  const [chapterPageBySubject, setChapterPageBySubject] = useState<Record<string, number>>({});
  const [chapterPageSize, setChapterPageSize] = useState(10);
  const initializedLibraryScope = useRef('');
  const [currentPage, setCurrentPage] = useState(1);
  const [zoom, setZoom] = useState(100);
  const [thumbnailsOpen, setThumbnailsOpen] = useState(false);
  const [fitWidth, setFitWidth] = useState(false);
  const [fitPage, setFitPage] = useState(true);
  const [loading, setLoading] = useState(true);
  const [imageLoading, setImageLoading] = useState(false);
  const [error, setError] = useState('');
  const [pageError, setPageError] = useState('');
  const [pageRetry, setPageRetry] = useState(0);
  const [notice, setNotice] = useState('');
  const [fullscreen, setFullscreen] = useState(false);

  const loadDocuments = useCallback(async () => {
    setLoading(true);
    setError('');
    setDocumentsLanguage(null);
    try {
      const data = await api('/api/v1/learning/documents?language=' + requestedLanguage);
      setDocuments(Array.isArray(data) ? data : []);
      setDocumentsLanguage(requestedLanguage);
    } catch (e: any) {
      if (e?.auth) { router.replace('/login/student'); return; }
      setDocuments([]);
      setError(e instanceof Error ? e.message : 'Your textbook library could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [router, requestedLanguage]);

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
        setFitWidth(false);
        setFitPage(true);
      })
      .catch((e: any) => {
        if (!active) return;
        if (e?.auth) { router.replace('/login/student'); return; }
        setError(e instanceof Error ? e.message : 'This textbook is not available to your account.');
        setSelected(null);
      });
    return () => { active = false; };
  }, [documentId, router]);

  useEffect(() => {
    if (!documentId || !selected || Number(selected.document_id) !== documentId || loading || documentsLanguage !== requestedLanguage || !selected.language) return;
    if (selected.language === requestedLanguage) return;

    const sameSlot = documents.find(item =>
      item.language === requestedLanguage &&
      item.class_code === selected.class_code &&
      item.subject_code === selected.subject_code &&
      item.scope === selected.scope &&
      (item.scope === 'SUBJECT_BOOK' ||
        (selected.chapter_id != null && item.chapter_id === selected.chapter_id) ||
        (Boolean(selected.chapter_code) && item.chapter_code === selected.chapter_code))
    );
    if (sameSlot) {
      const nextParams = new URLSearchParams(searchParams.toString());
      nextParams.set('documentId', String(sameSlot.document_id));
      router.replace('/student/textbooks?' + nextParams.toString());
      return;
    }

    setNotice(locale === 'english'
      ? 'The English PDF for this book or chapter has not been published yet. Showing the English textbook library.'
      : 'इस पुस्तक या अध्याय की हिन्दी PDF अभी प्रकाशित नहीं है। हिन्दी पुस्तकालय दिखाया जा रहा है।');
    setSelected(null);
    setPageError('');
    setCurrentPage(1);
    router.replace('/student/textbooks?subjectCode=' + encodeURIComponent(selected.subject_code));
  }, [documentId, selected, loading, documentsLanguage, documents, requestedLanguage, locale, router, searchParams]);

  const pageCount = Number(selected?.page_count || 0);
  const firstThumb = Math.max(1, Math.min(currentPage - 6, pageCount - 19));
  const thumbnails = useMemo(() => {
    const count = Math.min(20, pageCount);
    return Array.from({ length: count }, (_v, index) => firstThumb + index);
  }, [firstThumb, pageCount]);
  const pageUrl = selected
    ? '/api/v1/learning/documents/' + selected.document_id + '/pages/' + currentPage + (pageRetry ? '?retry=' + pageRetry : '')
    : '';
  const availableSubjects = useMemo(() => {
    const subjects = new Map<string, string>();
    for (const item of documents) {
      if (!subjects.has(item.subject_code)) subjects.set(item.subject_code, item.subject_name);
    }
    return [...subjects.entries()].map(([code, name]) => ({ code, name }));
  }, [documents]);
  const visibleDocuments = useMemo(
    () => documents.filter(item => subjectFilter === 'ALL' || item.subject_code === subjectFilter),
    [documents, subjectFilter],
  );
  const subjectGroups = useMemo(() => {
    const grouped = new Map<string, LearningDocument[]>();
    for (const item of visibleDocuments) {
      const rows = grouped.get(item.subject_code) || [];
      rows.push(item);
      grouped.set(item.subject_code, rows);
    }
    return [...grouped.entries()].map(([code, rows]) => ({
      code, name: rows[0]?.subject_name || code, rows,
    }));
  }, [visibleDocuments]);
  const subjectLabel = selected
    ? (locale === 'english'
        ? (selected.subject_code === 'maths' ? 'Mathematics' : selected.subject_code === 'science' ? 'Science' : selected.subject_name)
        : selected.subject_name)
    : 'TEXTBOOK';
  const displaySubjectName = (code: string, fallback: string) =>
    locale === 'english' ? (code === 'maths' ? 'Mathematics' : code === 'science' ? 'Science' : fallback) : fallback;
  const displayChapterTitle = (item: LearningDocument, english: boolean) => {
    if (english && item.scope === 'CHAPTER_PDF') {
      const match = item.title.match(/Chapter\s+\d+\s*:\s*(.+)$/i);
      if (match?.[1]) return match[1].trim();
    }
    return item.chapter_name || item.title;
  };
  const rawReturnTo = searchParams.get('returnTo') || '';
  const returnTarget = rawReturnTo.startsWith('/') && !rawReturnTo.startsWith('//') ? rawReturnTo : '/student/textbooks';
  const returnLabel = returnTarget.startsWith('/student/learn') ? 'Back to subject' : returnTarget === '/student' ? 'Back to today' : 'Textbook library';

  useEffect(() => {
    if (loading || subjectGroups.length === 0) return;
    const classCode = String(subjectGroups[0]?.rows[0]?.class_code || 'default');
    if (initializedLibraryScope.current === classCode) return;
    const key = 'qe:student:textbooks:open:' + classCode;
    const requested = requestedSubjectCode;
    const remembered = localStorage.getItem(key);
    const initial = subjectGroups.find(group => group.code === requested)?.code
      || subjectGroups.find(group => group.code === remembered)?.code
      || subjectGroups[0]?.code
      || '';
    setExpandedSubject(initial);
    if (initial) localStorage.setItem(key, initial);
    initializedLibraryScope.current = classCode;
  }, [loading, subjectGroups, requestedSubjectCode]);

  useEffect(() => {
    if (documentId) return;
    const requested = requestedSubjectCode;
    if (!requested || !availableSubjects.some(subject => subject.code === requested)) return;
    setSubjectFilter(requested);
    setExpandedSubject(requested);
    const classCode = String(documents.find(item => item.subject_code === requested)?.class_code || 'default');
    localStorage.setItem('qe:student:textbooks:open:' + classCode, requested);
  }, [documentId, requestedSubjectCode, availableSubjects, documents]);

  useEffect(() => {
    if (documentId || loading) return;
    const key = 'qe:student:textbooks:scrollY';
    const raw = localStorage.getItem(key);
    if (raw === null) return;
    const target = Math.max(0, Number(raw) || 0);
    requestAnimationFrame(() => requestAnimationFrame(() => {
      window.scrollTo({ top: target, behavior: 'auto' });
      localStorage.removeItem(key);
    }));
  }, [documentId, loading, subjectGroups.length]);

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
      if (event.altKey) return;
      const target = event.target as HTMLElement | null;
      if (target && ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(target.tagName)) return;
      if ((event.ctrlKey || event.metaKey) && ['0', '+', '=', '-'].includes(event.key)) {
        event.preventDefault();
        if (event.key === '0') { setFitPage(true); setFitWidth(false); }
        else if (event.key === '-') { setFitPage(false); setFitWidth(false); setZoom(z => Math.max(60, z - 10)); }
        else { setFitPage(false); setFitWidth(false); setZoom(z => Math.min(160, z + 10)); }
        return;
      }
      if (event.ctrlKey || event.metaKey) return;
      if (event.key === 'ArrowRight' || event.key === 'PageDown') {
        event.preventDefault(); setCurrentPage(p => Math.min(pageCount, p + 1));
      } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
        event.preventDefault(); setCurrentPage(p => Math.max(1, p - 1));
      } else if (event.key === 'Home') { event.preventDefault(); setCurrentPage(1); }
      else if (event.key === 'End') { event.preventDefault(); setCurrentPage(pageCount); }
      else if (event.key === '0') { event.preventDefault(); setFitPage(true); setFitWidth(false); }
      else if (event.key === '+' || event.key === '=') { event.preventDefault(); setFitPage(false); setFitWidth(false); setZoom(z => Math.min(160, z + 10)); }
      else if (event.key === '-') { event.preventDefault(); setFitPage(false); setFitWidth(false); setZoom(z => Math.max(60, z - 10)); }
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

  function openDocument(id: number, sourceSubjectCode?: string) {
    setError(''); setNotice(''); setPageError('');
    localStorage.setItem('qe:student:textbooks:scrollY', String(window.scrollY));
    const code = sourceSubjectCode || subjectFilter;
    const classCode = String(documents.find(item => item.document_id === id)?.class_code || 'default');
    if (code && code !== 'ALL') localStorage.setItem('qe:student:textbooks:open:' + classCode, code);
    const returnPath = code && code !== 'ALL'
      ? '/student/textbooks?subjectCode=' + encodeURIComponent(code)
      : '/student/textbooks';
    router.push('/student/textbooks?documentId=' + id + '&returnTo=' + encodeURIComponent(returnPath));
  }

  function returnToLibrary() {
    setSelected(null); setError(''); setPageError('');
    router.push(returnTarget);
  }

  function changeSubjectFilter(value: string) {
    setSubjectFilter(value);
    setChapterPageBySubject({});
    const group = value === 'ALL' ? null : subjectGroups.find(item => item.code === value);
    const code = group?.code || '';
    // Selecting one subject opens its assignments; returning to All subjects
    // deliberately collapses every book so the user can choose what to open.
    setExpandedSubject(code);
    const classCode = String(group?.rows[0]?.class_code || documents[0]?.class_code || 'default');
    const storageKey = 'qe:student:textbooks:open:' + classCode;
    if (code) localStorage.setItem(storageKey, code);
    else localStorage.removeItem(storageKey);

    // Keep the URL filter in sync. Removing subjectCode for "All subjects"
    // prevents an old deep-link filter from silently reopening a card.
    const params = new URLSearchParams(searchParams.toString());
    params.delete('documentId');
    if (value === 'ALL') params.delete('subjectCode');
    else params.set('subjectCode', value);
    const query = params.toString();
    router.replace('/student/textbooks' + (query ? '?' + query : ''), { scroll: false });
  }

  const progressPercent = pageCount ? Math.round((currentPage / pageCount) * 100) : 0;

  useEffect(() => {
    if (!selected) return;
    setImageLoading(true);
    setPageError('');
  }, [selected?.document_id, currentPage]);

  return <main className="app-shell qe-textbook-shell">

    {!documentId ? <section className="qe-textbook-library">
      {error && <div className="qe-textbook-error" role="alert">{error}</div>}
      {notice && <div className="qe-textbook-notice" role="status">{notice}</div>}
      <section className="qe-textbook-governance" aria-labelledby="qe-textbook-governance-title">
        <div className="qe-textbook-library-toolbar">
          <div className="qe-textbook-governance-copy">
            <span className="qe-textbook-eyebrow">RESOURCE GOVERNANCE</span>
            <h2 id="qe-textbook-governance-title">Book and chapter assignments</h2>
            <p>Browse only the published resources available for your enrolled class and subjects.</p>
          </div>
          <div className="qe-textbook-filter-wrap">
            <label>Subject scope
              <select aria-label="Filter textbook assignments by subject" value={subjectFilter} onChange={e => changeSubjectFilter(e.target.value)}>
                <option value="ALL">All subjects</option>{availableSubjects.map(subject => <option key={subject.code} value={subject.code}>{displaySubjectName(subject.code, subject.name)}</option>)}
              </select>
            </label>
            <span className="qe-textbook-filter-summary" aria-live="polite">{visibleDocuments.length} resource{visibleDocuments.length === 1 ? '' : 's'} · {subjectGroups.length} subject{subjectGroups.length === 1 ? '' : 's'}</span>
          </div>
        </div>
      </section>

      {loading ? <div className="qe-textbook-loading"><span className="qe-reader-spinner"/> Loading your textbook library…</div>
        : visibleDocuments.length === 0 ? <div className="qe-textbook-empty"><span>▤</span><h3>No published PDFs available yet</h3><p>When your teacher publishes a subject book or chapter PDF for your class, it will appear here.</p><Link href="/student/learn">Continue with lessons →</Link></div>
        : <div className="qe-textbook-resource-groups">
          {subjectGroups.map(({ code, name: rawName, rows }) => {
            const name = displaySubjectName(code, rawName);
            const subjectBooks = rows.filter(item => item.scope === 'SUBJECT_BOOK').slice().sort((a,b) =>
              Number(a.book_part || 0) - Number(b.book_part || 0));
            const hasBookParts = subjectBooks.some(item => Number(item.book_part || 0) > 0);
            const chapterBooks = rows.filter(item => item.scope === 'CHAPTER_PDF').slice().sort((a,b)=>
              String(a.chapter_code||a.chapter_name||a.title).localeCompare(String(b.chapter_code||b.chapter_name||b.title),undefined,{numeric:true,sensitivity:'base'}));
            const book = subjectBooks[0];
            const expanded = expandedSubject === code;
            const chapterPageCount = Math.max(1, Math.ceil(chapterBooks.length / chapterPageSize));
            const chapterPage = Math.max(1, Math.min(chapterPageBySubject[code] || 1, chapterPageCount));
            const visibleChapterBooks = chapterBooks.slice((chapterPage - 1) * chapterPageSize, chapterPage * chapterPageSize);
            const showChapterPagination = chapterPageCount > 1;
            const renderChapterPagination = (position: 'top' | 'bottom') => (
              <div className={'qe-textbook-pagination qe-textbook-pagination--' + position}>
                <PremiumPagination
                  page={chapterPage - 1}
                  total={chapterBooks.length}
                  pageSize={chapterPageSize}
                  onPageChange={nextPage => setChapterPageBySubject(old => ({ ...old, [code]: nextPage + 1 }))}
                  onPageSizeChange={size => { setChapterPageSize(size); setChapterPageBySubject(old => ({ ...old, [code]: 1 })); }}
                  itemLabel={name + ' chapters'}
                />
              </div>
            );
            const bookTitle = hasBookParts
              ? (locale === 'english' ? name + ' · NCERT textbook parts' : name + ' · पाठ्यपुस्तक के भाग')
              : (book?.title || (name + ' · Chapter PDFs'));
            const partLabel = (part: number) => {
              if (locale !== 'english') return 'भाग ' + part;
              const roman = ({1:'I',2:'II',3:'III',4:'IV',5:'V',6:'VI',7:'VII',8:'VIII',9:'IX'} as Record<number,string>)[part];
              return roman ? 'Part ' + roman : 'Part ' + part;
            };
            const toggle = () => {
              const next = expanded ? '' : code;
              setExpandedSubject(next);
              if (next) setChapterPageBySubject(old => ({ ...old, [code]: 1 }));
              const classCode = String(rows[0]?.class_code || 'default');
              const storageKey = 'qe:student:textbooks:open:' + classCode;
              if (next) localStorage.setItem(storageKey, next);
              else localStorage.removeItem(storageKey);
            };
            return <section className={'qe-textbook-book-group' + (expanded ? ' expanded' : '')} key={code}>
              <div className="qe-textbook-book-row">
                <button type="button" className="qe-textbook-book-toggle" aria-expanded={expanded} onClick={toggle} aria-controls={'qe-textbook-chapters-' + code}>
                  <span className="qe-library-pdf-mark" aria-hidden="true"><svg viewBox="0 0 24 28"><path d="M4 1.5h10l6 6V26.5H4z"/><path d="M14 1.5v6h6M6.5 16h11M6.5 20h11"/></svg><small>PDF</small></span>
                  <span className="qe-textbook-book-copy"><strong>{bookTitle}</strong><small>Class {rows[0]?.class_code} · {name} · {hasBookParts ? subjectBooks.filter(item => Number(item.book_part || 0) > 0).map(item => partLabel(Number(item.book_part))).join(' & ') + ' available' : book ? book.page_count + ' pages' : 'Chapter reading collection'}</small></span>
                  <span className="qe-textbook-chapter-count">{chapterBooks.length} chapter PDF{chapterBooks.length===1?'':'s'}</span>
                  <span className="qe-textbook-book-chevron">{expanded?'−':'+'}</span>
                </button>
                {book && !hasBookParts && <button type="button" className="qe-textbook-open-book" onClick={()=>openDocument(book.document_id,code)} aria-label={'Open complete textbook '+book.title}>Open book ↗</button>}
              </div>
              {expanded&&<div className="qe-textbook-book-contents" id={'qe-textbook-chapters-' + code}>
                {(hasBookParts || subjectBooks.length>1) && <div className="qe-textbook-edition-list" aria-label={hasBookParts ? 'Textbook parts' : 'Textbook editions'}>
                  {(hasBookParts ? subjectBooks.filter(item => Number(item.book_part || 0) > 0) : subjectBooks.slice(1)).map(item => <button type="button" key={item.document_id} onClick={()=>openDocument(item.document_id,code)}>
                    <span className="qe-library-mini-pdf">PDF</span>
                    <span><strong>{hasBookParts && item.book_part ? partLabel(Number(item.book_part)) : item.title}</strong><small>{item.edition || (hasBookParts ? 'NCERT textbook part' : 'Complete subject book')} · {item.page_count} pages</small></span>
                    <span>Open ↗</span>
                  </button>)}
                </div>}
                {chapterBooks.length>0 ? <>
                  {showChapterPagination && renderChapterPagination('top')}
                  <div className="qe-textbook-chapter-list">{visibleChapterBooks.map((item,index)=><button type="button" className="qe-textbook-chapter-row" key={item.document_id} onClick={()=>openDocument(item.document_id,code)}>
                    <span className="qe-textbook-chapter-index">{String((chapterPage - 1) * chapterPageSize + index + 1).padStart(2,'0')}</span>
                    <span className="qe-library-mini-pdf">PDF</span>
                    <span className="qe-textbook-chapter-copy"><strong>{displayChapterTitle(item, locale === 'english')}</strong><small>{item.title}{item.chapter_code?' · '+item.chapter_code:''}</small></span>
                    <span className="qe-textbook-chapter-pages">{item.page_count} pages</span>
                    <span className="qe-textbook-chapter-open">↗</span>
                  </button>)}</div>
                  {showChapterPagination && renderChapterPagination('bottom')}
                </> : <div className="qe-textbook-no-chapters"><span>▤</span><div><strong>No chapter PDFs published yet</strong><small>Published chapter files will appear under this book.</small></div></div>}
              </div>}
            </section>;
          })}
        </div>}
      <div className="qe-textbook-privacy-note"><span>▣</span><p><b>Private reading mode.</b> The viewer does not provide a PDF download or print button. To deliver pages securely, QuantaEdge checks your account and subject access before rendering each page.</p></div>
    </section> : <section className="qe-document-reader" ref={readerRef}>
      <div className="qe-reader-heading"><div className="qe-reader-breadcrumb"><button type="button" onClick={returnToLibrary}>← {returnLabel}</button><span>/</span><span>{selected?.scope === 'SUBJECT_BOOK' && selected.book_part ? (locale === 'english' ? 'Part ' + ({1:'I',2:'II',3:'III',4:'IV',5:'V',6:'VI',7:'VII',8:'VIII',9:'IX'} as Record<number,string>)[Number(selected.book_part)] : 'भाग ' + selected.book_part) : selected?.scope === 'SUBJECT_BOOK' ? 'Complete subject book' : selected ? displayChapterTitle(selected, locale === 'english') : 'Chapter PDF'}</span></div>
        <div className="qe-reader-title-row"><div><span className="qe-textbook-eyebrow">{selected?.scope === 'SUBJECT_BOOK' ? 'SUBJECT TEXTBOOK' : 'CHAPTER READING'} · {subjectLabel.toUpperCase()}</span><h1>{selected ? (selected.scope === 'CHAPTER_PDF' ? displayChapterTitle(selected, locale === 'english') : selected.title) : 'Loading textbook…'}</h1><p>{selected?.scope === 'CHAPTER_PDF' && selected ? displayChapterTitle(selected, locale === 'english') + ' · ' : ''}{selected?.edition || 'View-only online reader'}</p></div>
          {selected && <div className="qe-reader-progress"><strong>{progressPercent}%</strong><span>read</span><div><i style={{width: progressPercent + '%'}}/></div></div>}
        </div>
      </div>

      {error ? <div className="qe-textbook-error" role="alert">{error}<button type="button" onClick={returnToLibrary}>Back to library</button></div>
        : !selected ? <div className="qe-textbook-loading"><span className="qe-reader-spinner"/> Preparing the secure reader…</div>
        : <>
          <div className="qe-reader-toolbar" role="toolbar" aria-label="Textbook viewer controls">
            <div className="qe-reader-page-controls"><button type="button" aria-label="Previous page" disabled={currentPage <= 1} onClick={() => setCurrentPage(p => Math.max(1, p - 1))}>‹</button><label><span>Page</span><input type="number" min="1" max={pageCount} value={currentPage} onChange={e => { const value = Number(e.target.value); if (Number.isFinite(value)) setCurrentPage(Math.max(1, Math.min(pageCount, value))); }} onBlur={() => setCurrentPage(p => Math.max(1, Math.min(pageCount, p)))}/><span>of {pageCount}</span></label><button type="button" aria-label="Next page" disabled={currentPage >= pageCount} onClick={() => setCurrentPage(p => Math.min(pageCount, p + 1))}>›</button></div>
            <div className="qe-reader-toolbar-divider"/>
            <div className="qe-reader-zoom"><button type="button" aria-label="Zoom out" disabled={zoom <= 60} onClick={() => {setFitPage(false);setFitWidth(false);setZoom(z => Math.max(60, z - 10));}}>−</button><span>{fitPage ? 'Fit page' : fitWidth ? 'Fit width' : zoom + '%'}</span><button type="button" aria-label="Zoom in" disabled={zoom >= 160} onClick={() => {setFitPage(false);setFitWidth(false);setZoom(z => Math.min(160, z + 10));}}>＋</button></div>
            <button type="button" className={'qe-reader-tool-button' + (fitPage ? ' selected' : '')} onClick={() => {setFitPage(true);setFitWidth(false);}}>Fit page</button>
            <button type="button" className={'qe-reader-tool-button' + (fitWidth ? ' selected' : '')} onClick={() => {setFitPage(false);setFitWidth(value => !value);}}>{fitWidth ? 'Actual size' : 'Fit width'}</button>
            <button type="button" className={'qe-reader-tool-button' + (thumbnailsOpen ? ' selected' : '')} onClick={() => setThumbnailsOpen(value => !value)}>▤ Pages</button>
            <button type="button" className="qe-reader-tool-button qe-reader-fullscreen" onClick={() => void toggleFullscreen()}>{fullscreen ? 'Exit full screen' : '⛶ Full screen'}</button>
          </div>

          <div className={'qe-reader-workspace' + (thumbnailsOpen ? ' with-thumbnails' : '')}>
            {thumbnailsOpen && <aside className="qe-reader-thumbnails" aria-label="Page thumbnails"><div className="qe-reader-thumbs-head"><b>Pages</b><small>{pageCount} total</small></div><div className="qe-reader-thumb-list">{thumbnails.map(number => <button type="button" key={number} className={number === currentPage ? 'active' : ''} onClick={() => {setCurrentPage(number);setPageError('');}}><img src={'/api/v1/learning/documents/' + selected.document_id + '/pages/' + number + '?rendition=thumbnail'} alt="" loading="lazy" decoding="async" draggable={false}/><span>Page {number}</span></button>)}</div><div className="qe-reader-thumbs-foot">Showing pages {thumbnails[0] || 0}–{thumbnails[thumbnails.length - 1] || 0}</div></aside>}
            <div className="qe-reader-page-stage" onContextMenu={e => e.preventDefault()}><div className="qe-reader-canvas-top"><span><i/> Secured page rendering</span><span>{currentPage} / {pageCount}</span></div>
              <div className={'qe-reader-page-viewport' + (fitWidth ? ' fit-width' : '') + (fitPage ? ' fit-page' : '')} key={selected.document_id + '-' + currentPage}>
                {imageLoading && <div className="qe-reader-image-loading"><span className="qe-reader-spinner"/> Rendering page {currentPage}…</div>}
                <AuthenticatedPageImage
                  className="qe-reader-page-image"
                  src={pageUrl}
                  alt={'Page ' + currentPage + ' of ' + selected.title}
                  onLoad={() => { setImageLoading(false); setPageError(''); }}
                  onError={message => { setImageLoading(false); setPageError(message); }}
                  style={{ width: fitPage ? 'auto' : fitWidth ? '100%' : zoom + '%' }}
                />
                {pageError && <div className="qe-reader-page-error" role="alert">{pageError}<button type="button" onClick={() => { setPageError(''); setImageLoading(true); setPageRetry(value => value + 1); }}>Retry page</button></div>}
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
