'use client';

import { translateUi } from '../../i18n/catalog';
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
  const t = (english: string, hindi: string) => locale === 'english' ? english : hindi;
  const requestedSubjectCode = searchParams.get('subjectCode') || '';
  const documentId = Number(searchParams.get('documentId') || 0);
  const readerRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    document.body.classList.toggle('qe-reading-mode', documentId > 0);
    return () => document.body.classList.remove('qe-reading-mode');
  }, [documentId]);
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
      setError(e instanceof Error ? e.message : translateUi(locale, 'legacy.web.your.textbook.library.could.not.be.loa.3c77f5e0'));
    } finally {
      setLoading(false);
    }
  }, [router, requestedLanguage, locale]);

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
        setError(e instanceof Error ? e.message : translateUi(locale, 'legacy.web.this.textbook.is.not.available.to.your.8697d28f'));
        setSelected(null);
      });
    return () => { active = false; };
  }, [documentId, router, locale]);

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

    const requestedLabel = requestedLanguage === 'en' ? 'English' : 'Hindi';
    const requestedLabelHi = requestedLanguage === 'en' ? 'अंग्रेज़ी' : 'हिन्दी';
    setNotice(locale === 'english'
      ? `The ${requestedLabel} PDF for this book or chapter has not been published yet. Showing the ${requestedLabel} textbook library.`
      : `इस पुस्तक या अध्याय की ${requestedLabelHi} PDF अभी प्रकाशित नहीं है। ${requestedLabelHi} पुस्तकालय दिखाया जा रहा है।`);
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
  const displaySubjectName = (code: string, fallback: string) => {
    const normalized = String(code || '').toLowerCase();
    if (['maths', 'math', 'mathematics'].includes(normalized)) return locale === 'english' ? 'Mathematics' : 'गणित';
    if (normalized === 'science') return locale === 'english' ? 'Science' : 'विज्ञान';
    return fallback;
  };
  const subjectLabel = selected
    ? displaySubjectName(selected.subject_code, selected.subject_name || selected.subject_code)
    : translateUi(locale, 'legacy.web.textbook.18cd63d6');
  const displayChapterTitle = (item: LearningDocument, english: boolean) => {
    if (english && item.scope === 'CHAPTER_PDF') {
      const match = item.title.match(/Chapter\s+\d+\s*:\s*(.+)$/i);
      if (match?.[1]) return match[1].trim();
    }
    return item.chapter_name || item.title;
  };
  const readerTitle = (item: LearningDocument) => {
    if (item.scope === 'CHAPTER_PDF') return displayChapterTitle(item, learningLanguage === 'en');
    if (item.book_part) {
      const roman = ({1:'I',2:'II',3:'III',4:'IV',5:'V',6:'VI',7:'VII',8:'VIII',9:'IX'} as Record<number,string>)[Number(item.book_part)];
      return locale === 'english' ? 'Part ' + (roman || item.book_part) : 'भाग ' + item.book_part;
    }
    return translateUi(locale, 'legacy.web.complete.subject.book.9fa02f9d');
  };
  const chapterNumberLabel = (item: LearningDocument) => {
    const englishMatch = String(item.title || '').match(/Chapter\s+(\d+)\s*:/i);
    if (englishMatch?.[1]) return t('Chapter '+englishMatch[1],'अध्याय '+englishMatch[1]);
    const hindiMatch = String(item.title || '').match(/अध्याय\s*(\d+)\s*[:.:-]/i);
    if (hindiMatch?.[1]) return t('Chapter '+hindiMatch[1],'अध्याय '+hindiMatch[1]);
    return '';
  };
  const rawReturnTo = searchParams.get('returnTo') || '';
  const returnTarget = rawReturnTo.startsWith('/') && !rawReturnTo.startsWith('//') ? rawReturnTo : '/student/textbooks';
  const returnLabel = returnTarget.startsWith('/student/learn') ? translateUi(locale, 'legacy.web.back.to.subject.ffd3f558') : returnTarget === '/student' ? translateUi(locale, 'legacy.web.back.to.today.d7003bfb') : translateUi(locale, 'legacy.web.textbook.library.278aacb6');

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

  return <main className={'app-shell qe-textbook-shell' + (documentId ? ' qe-textbook-shell--reader' : '')}>

    {!documentId ? <section className="qe-textbook-library">
      {error && <div className="qe-textbook-error" role="alert">{error}</div>}
      {notice && <div className="qe-textbook-notice" role="status">{notice}</div>}
      <section className="qe-textbook-governance" aria-labelledby="qe-textbook-governance-title">
        <div className="qe-textbook-library-toolbar">
          <div className="qe-textbook-governance-copy">
            <span className="qe-textbook-eyebrow">{translateUi(locale, 'legacy.web.resource.governance.dd634f00')}</span>
            <h2 id="qe-textbook-governance-title">{translateUi(locale, 'legacy.web.books.and.chapters.7c0f264b')}</h2>
            <p>{translateUi(locale, 'legacy.web.browse.only.the.published.resources.av.80a34039')}</p>
          </div>
          <div className="qe-textbook-filter-wrap">
            <label>{translateUi(locale, 'legacy.web.subject.scope.f130e513')}
              <select aria-label={translateUi(locale, 'legacy.web.filter.textbook.assignments.by.subject.e0994ff3')} value={subjectFilter} onChange={e => changeSubjectFilter(e.target.value)}>
                <option value="ALL">{translateUi(locale, 'legacy.web.all.subjects.7895d918')}</option>{availableSubjects.map(subject => <option key={subject.code} value={subject.code}>{displaySubjectName(subject.code, subject.name)}</option>)}
              </select>
            </label>
            <span className="qe-textbook-filter-summary" aria-live="polite">{visibleDocuments.length} {translateUi(locale, visibleDocuments.length === 1 ? 'legacy.web.resource.d42911fa' : 'web.count.resourcesPlural')} · {subjectGroups.length} {translateUi(locale, subjectGroups.length === 1 ? 'legacy.web.subject.e4481da7' : 'web.count.subjectsPlural')}</span>
          </div>
        </div>
      </section>

      {loading ? <div className="qe-textbook-loading"><span className="qe-reader-spinner"/> {translateUi(locale, 'legacy.web.loading.your.textbook.library.e562b86c')}</div>
        : visibleDocuments.length === 0 ? <div className="qe-textbook-empty"><span>▤</span><h3>{translateUi(locale, 'legacy.web.no.published.pdfs.available.yet.6a520470')}</h3><p>{translateUi(locale, 'legacy.web.when.your.teacher.publishes.a.subject..6029df49')}</p><Link href="/student/learn">{translateUi(locale, 'legacy.web.continue.with.lessons.2a65104e')}</Link></div>
        : <div className="qe-textbook-resource-groups">
          {subjectGroups.map(({ code, name: rawName, rows }) => {
            const name = displaySubjectName(code, rawName);
            const subjectBooks = rows.filter(item => item.scope === 'SUBJECT_BOOK').slice().sort((a,b) =>
              Number(a.book_part || 0) - Number(b.book_part || 0));
            const hasBookParts = subjectBooks.some(item => Number(item.book_part || 0) > 0);
            // The API returns chapter PDFs in curriculum teaching order. Alphabetically
            // sorting chapter codes/slugs can break the book's official chapter sequence.
            const chapterBooks = rows.filter(item => item.scope === 'CHAPTER_PDF');
            const book = subjectBooks[0];
            const expanded = expandedSubject === code;
            const chapterPageCount = Math.max(1, Math.ceil(chapterBooks.length / chapterPageSize));
            const chapterPage = Math.max(1, Math.min(chapterPageBySubject[code] || 1, chapterPageCount));
            const visibleChapterBooks = chapterBooks.slice((chapterPage - 1) * chapterPageSize, chapterPage * chapterPageSize);
            const showChapterPagination = chapterBooks.length > 10 && chapterPageCount > 1;
            const renderChapterPagination = (position: 'top' | 'bottom') => (
              <div className={'qe-textbook-pagination qe-textbook-pagination--' + position}>
                <PremiumPagination
                  page={chapterPage - 1}
                  total={chapterBooks.length}
                  pageSize={chapterPageSize}
                  onPageChange={nextPage => setChapterPageBySubject(old => ({ ...old, [code]: nextPage + 1 }))}
                  onPageSizeChange={size => { setChapterPageSize(size); setChapterPageBySubject(old => ({ ...old, [code]: 1 })); }}
                  itemLabel={name + ' ' + translateUi(locale, 'legacy.web.chapters.b7609a1c')}
                />
              </div>
            );
            const displayName = displaySubjectName(code, name);
            const metadataDocument = book || rows[0];
            const metadataParts = String(metadataDocument?.title || '').split('|').map(part => part.trim()).filter(Boolean);
            const publisherLabel = metadataParts.length > 1 ? metadataParts[0] : String(metadataDocument?.edition || '').trim();
            const bookLanguageLabel = (metadataDocument?.language || learningLanguage) === 'en' ? translateUi(locale, 'legacy.web.english.5c61a233') : translateUi(locale, 'legacy.web.hindi.197af92c');
            const bookKindLabel = hasBookParts ? translateUi(locale, 'legacy.web.book.parts.e55c930d') : book ? translateUi(locale, 'legacy.web.complete.book.db3cef6c') : translateUi(locale, 'legacy.web.chapter.pdfs.f9ab1e38');
            const bookTitle = hasBookParts
              ? (locale === 'english' ? displayName + ' textbook parts' : displayName + ' की पाठ्यपुस्तक के भाग')
               : book ? (locale === 'english' ? displayName + ' textbook' : displayName + ' की पाठ्यपुस्तक')
                : (locale === 'english' ? displayName + ' chapter collection' : displayName + ' के अध्याय');
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
                  <span className="qe-textbook-book-copy"><strong>{bookTitle}</strong>
                    <span className="qe-book-meta-tags">
                      {publisherLabel && <span className="qe-book-meta-tag qe-book-meta-tag--source">{publisherLabel}</span>}
                      <span className="qe-book-meta-tag qe-book-meta-tag--class">{translateUi(locale, 'legacy.web.class.3f617911')}{rows[0]?.class_code}</span>
                      <span className="qe-book-meta-tag qe-book-meta-tag--subject">{displayName}</span>
                      <span className="qe-book-meta-tag qe-book-meta-tag--language">{bookLanguageLabel}</span>
                      <span className="qe-book-meta-tag qe-book-meta-tag--type">{bookKindLabel}</span>
                    </span>
                    <small>{hasBookParts ? subjectBooks.filter(item => Number(item.book_part || 0) > 0).map(item => partLabel(Number(item.book_part))).join(' & ') + ' ' + translateUi(locale, 'legacy.web.available.7c7e1f1d') : book ? book.page_count + ' ' + translateUi(locale, 'legacy.web.pages.216981bc') : translateUi(locale, 'legacy.web.published.chapter.pdfs.cb126dfd')}</small>
                  </span>
                  <span className="qe-textbook-chapter-count">{chapterBooks.length} {translateUi(locale, chapterBooks.length === 1 ? 'legacy.web.chapter.pdf.689e1479' : 'web.count.chapterPdfsPlural')}</span>
                  <span className="qe-textbook-book-chevron">{expanded?'−':'+'}</span>
                </button>
                {book && !hasBookParts && <button type="button" className="qe-textbook-open-book" onClick={()=>openDocument(book.document_id,code)} aria-label={translateUi(locale, 'legacy.web.open.complete.textbook.f19d2905')+book.title}>{translateUi(locale, 'legacy.web.open.book.e025edc7')}</button>}
              </div>
              {expanded&&<div className="qe-textbook-book-contents" id={'qe-textbook-chapters-' + code}>
                {(hasBookParts || subjectBooks.length>1) && <div className="qe-textbook-edition-list" aria-label={hasBookParts ? translateUi(locale, 'legacy.web.textbook.parts.0e2e59f6') : translateUi(locale, 'legacy.web.textbook.editions.fba4e58c')}>
                  {(hasBookParts ? subjectBooks.filter(item => Number(item.book_part || 0) > 0) : subjectBooks.slice(1)).map(item => <button type="button" key={item.document_id} onClick={()=>openDocument(item.document_id,code)}>
                    <span className="qe-library-mini-pdf">PDF</span>
                    <span><strong>{hasBookParts && item.book_part ? partLabel(Number(item.book_part)) : item.title}</strong><small>{item.edition || (hasBookParts ? translateUi(locale, 'legacy.web.ncert.textbook.part.df28ce27') : translateUi(locale, 'legacy.web.complete.subject.book.9fa02f9d'))} · {item.page_count} {translateUi(locale, 'legacy.web.pages.216981bc')}</small></span>
                    <span>{translateUi(locale, 'legacy.web.open.2fcc1213')}</span>
                  </button>)}
                </div>}
                {chapterBooks.length>0 ? <>
                  {showChapterPagination && renderChapterPagination('top')}
                  <div className="qe-textbook-chapter-list">{visibleChapterBooks.map((item,index)=><button type="button" className="qe-textbook-chapter-row" key={item.document_id} onClick={()=>openDocument(item.document_id,code)}>
                    <span className="qe-textbook-chapter-index">{String((chapterPage - 1) * chapterPageSize + index + 1).padStart(2,'0')}</span>
                    <span className="qe-library-mini-pdf">PDF</span>
                    <span className="qe-textbook-chapter-copy"><strong>{displayChapterTitle(item, learningLanguage === 'en')}</strong><small>{chapterNumberLabel(item) || translateUi(locale, 'legacy.web.published.chapter.pdf.f01588a6')}</small></span>
                    <span className="qe-textbook-chapter-pages">{item.page_count} {translateUi(locale, 'legacy.web.pages.216981bc')}</span>
                    <span className="qe-textbook-chapter-open">↗</span>
                  </button>)}</div>
                  {showChapterPagination && renderChapterPagination('bottom')}
                </> : <div className="qe-textbook-no-chapters"><span>▤</span><div><strong>{translateUi(locale, 'legacy.web.no.chapter.pdfs.published.yet.5e639606')}</strong><small>{translateUi(locale, 'legacy.web.published.chapter.files.will.appear.un.6df6e592')}</small></div></div>}
              </div>}
            </section>;
          })}
        </div>}
      <div className="qe-textbook-privacy-note"><span>▣</span><p><b>{translateUi(locale, 'legacy.web.private.reading.mode.4ee7442e')}</b> {translateUi(locale, 'legacy.web.the.viewer.does.not.provide.a.pdf.down.a5279925')}</p></div>
    </section> : <section className="qe-document-reader" ref={readerRef}>
      <div className="qe-reader-heading">
        <button type="button" className="qe-reader-heading-back" onClick={returnToLibrary} aria-label={returnLabel}>←</button>
        <strong className="qe-reader-heading-title">{selected ? subjectLabel + ' · ' + readerTitle(selected) : translateUi(locale, 'legacy.web.loading.textbook.330c8c7c')}</strong>
        {selected && <span className="qe-reader-page-indicator">{translateUi(locale, 'legacy.web.page.9da95b95')}{currentPage}/{pageCount}</span>}
      </div>

      {error ? <div className="qe-textbook-error" role="alert">{error}<button type="button" onClick={returnToLibrary}>{translateUi(locale, 'legacy.web.back.to.library.409dd37a')}</button></div>
        : !selected ? <div className="qe-textbook-loading"><span className="qe-reader-spinner"/> {translateUi(locale, 'legacy.web.preparing.the.secure.reader.89f6ce6b')}</div>
        : <>
          <div className="qe-reader-toolbar" role="toolbar" aria-label={translateUi(locale, 'legacy.web.textbook.viewer.controls.ba6025be')}>
            <div className="qe-reader-page-controls"><button type="button" aria-label={translateUi(locale, 'legacy.web.previous.page.dcddbbc8')} disabled={currentPage <= 1} onClick={() => setCurrentPage(p => Math.max(1, p - 1))}>‹</button><label><span>{translateUi(locale, 'legacy.web.page.5d16e0a7')}</span><input type="number" aria-label={translateUi(locale, 'legacy.web.current.page.b64f7568')} min="1" max={pageCount} value={currentPage} onChange={e => { const value = Number(e.target.value); if (Number.isFinite(value)) setCurrentPage(Math.max(1, Math.min(pageCount, value))); }} onBlur={() => setCurrentPage(p => Math.max(1, Math.min(pageCount, p)))}/><span>{translateUi(locale, 'legacy.web.of.ffc7a9ee')} {pageCount}</span></label><button type="button" aria-label={translateUi(locale, 'legacy.web.next.page.24bee7ac')} disabled={currentPage >= pageCount} onClick={() => setCurrentPage(p => Math.min(pageCount, p + 1))}>›</button></div>
            <div className="qe-reader-toolbar-divider"/>
            <div className="qe-reader-zoom"><button type="button" aria-label={translateUi(locale, 'legacy.web.zoom.out.15e85a79')} disabled={zoom <= 60} onClick={() => {setFitPage(false);setFitWidth(false);setZoom(z => Math.max(60, z - 10));}}>−</button><span>{fitPage ? translateUi(locale, 'legacy.web.fit.page.1b43aaac') : fitWidth ? translateUi(locale, 'legacy.web.fit.width.7e6e2e4d') : zoom + '%'}</span><button type="button" aria-label={translateUi(locale, 'legacy.web.zoom.in.308b06c7')} disabled={zoom >= 160} onClick={() => {setFitPage(false);setFitWidth(false);setZoom(z => Math.min(160, z + 10));}}>＋</button></div>
            <button type="button" className={'qe-reader-tool-button' + (fitPage ? ' selected' : '')} onClick={() => {setFitPage(true);setFitWidth(false);}}>{translateUi(locale, 'legacy.web.fit.page.1b43aaac')}</button>
            <button type="button" className={'qe-reader-tool-button' + (fitWidth ? ' selected' : '')} onClick={() => {setFitPage(false);setFitWidth(value => !value);}}>{fitWidth ? translateUi(locale, 'legacy.web.actual.size.85a377bc') : translateUi(locale, 'legacy.web.fit.width.7e6e2e4d')}</button>
            <button type="button" className={'qe-reader-tool-button' + (thumbnailsOpen ? ' selected' : '')} onClick={() => setThumbnailsOpen(value => !value)}>▤ {translateUi(locale, 'legacy.web.pages.4b727f1c')}</button>
            <button type="button" className="qe-reader-tool-button qe-reader-fullscreen" onClick={() => void toggleFullscreen()}>{fullscreen ? translateUi(locale, 'legacy.web.exit.full.screen.983bdcd1') : '⛶ ' + translateUi(locale, 'legacy.web.full.screen.2509978d')}</button>
          </div>

          <div className={'qe-reader-workspace' + (thumbnailsOpen ? ' with-thumbnails' : '')}>
            {thumbnailsOpen && <aside className="qe-reader-thumbnails" aria-label={translateUi(locale, 'legacy.web.page.thumbnails.bb715704')}><div className="qe-reader-thumbs-head"><b>{translateUi(locale, 'legacy.web.pages.4b727f1c')}</b><small>{pageCount} {translateUi(locale, 'legacy.web.total.37a7923f')}</small></div><div className="qe-reader-thumb-list">{thumbnails.map(number => <button type="button" key={number} className={number === currentPage ? 'active' : ''} onClick={() => {setCurrentPage(number);setPageError('');}}><img src={'/api/v1/learning/documents/' + selected.document_id + '/pages/' + number + '?rendition=thumbnail'} alt="" loading="lazy" decoding="async" draggable={false}/><span>{translateUi(locale, 'legacy.web.page.5d16e0a7')} {number}</span></button>)}</div><div className="qe-reader-thumbs-foot">{translateUi(locale, 'legacy.web.showing.pages.4fa9e8a0')} {thumbnails[0] || 0}–{thumbnails[thumbnails.length - 1] || 0}</div></aside>}
            <div className="qe-reader-page-stage" onContextMenu={e => e.preventDefault()}><div className={'qe-reader-page-viewport' + (fitWidth ? ' fit-width' : '') + (fitPage ? ' fit-page' : '')} key={selected.document_id + '-' + currentPage}>
                {imageLoading && <div className="qe-reader-image-loading"><span className="qe-reader-spinner"/> {translateUi(locale, 'legacy.web.rendering.page.821433ef')} {currentPage}…</div>}
                <AuthenticatedPageImage
                  className="qe-reader-page-image"
                  src={pageUrl}
                  alt={translateUi(locale, 'legacy.web.page.9da95b95')+currentPage+' '+translateUi(locale, 'legacy.web.of.ffc7a9ee')+' '+selected.title}
                  onLoad={() => { setImageLoading(false); setPageError(''); }}
                  onError={message => { setImageLoading(false); setPageError(message); }}
                  style={{ width: fitPage ? 'auto' : fitWidth ? '100%' : zoom + '%' }}
                />
                {pageError && <div className="qe-reader-page-error" role="alert">{pageError}<button type="button" onClick={() => { setPageError(''); setImageLoading(true); setPageRetry(value => value + 1); }}>{translateUi(locale, 'legacy.web.retry.page.ce3b950d')}</button></div>}
              </div>
            </div>
          </div>
        </>}
    </section>}
  </main>;
}
