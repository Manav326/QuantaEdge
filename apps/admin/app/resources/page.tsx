'use client';

import AdminSidebar from '../components/AdminSidebar';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import styles from './Resources.module.css';

type PdfAsset = {
  pdf_asset_id: number;
  id?: number;
  title: string;
  original_filename: string;
  sha256: string;
  file_size_bytes: number;
  page_count: number;
  source_kind: string;
  source_reference?: string | null;
  assignment_count?: number;
};
type Chapter = { chapter_id: number; chapter_code: string; chapter_name: string; chapter_status?: string };
type Track = { class_code: string; class_name: string; subject_code: string; subject_name: string };
type Assignment = {
  document_id: number;
  pdf_asset_id: number;
  scope: 'SUBJECT_BOOK' | 'CHAPTER_PDF';
  title: string;
  source_title?: string | null;
  source_url?: string | null;
  edition?: string | null;
  page_start: number;
  page_end: number;
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED';
  language: 'hi' | 'en';
  original_filename: string;
  sha256: string;
  file_size_bytes: number;
  page_count: number;
  class_code: string;
  subject_code: string;
  chapter_id?: number | null;
  chapter_name?: string | null;
  chapter_active?: boolean | null;
  chapter_content_status?: string | null;
  asset_review_status?: string | null;
};

async function api(url: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers || {});
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const response = await fetch(url, {
    ...init, headers, credentials: 'same-origin', cache: 'no-store',
  });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(body.detail || body.message || body.error || ('Request failed (' + response.status + ')'));
  return body;
}

function contentCoverageWarning(reference?: string | null): string | null {
  if (!reference) return null;
  const parentStatus = reference.match(/parent_book_status=(PARTIAL|UNAVAILABLE)/i)?.[1]?.toUpperCase();
  const parentMissing = reference.match(/parent_missing_chapters=([^;]+)/i)?.[1]?.trim();
  if (parentStatus) {
    return parentMissing
      ? 'PARENT BOOK ' + parentStatus + ' — chapters missing from the original book: ' + parentMissing
        + '. This individual chapter PDF may still be complete.'
      : 'PARENT BOOK ' + parentStatus + ' — review the original book coverage report.';
  }
  const status = reference.match(/content_status=(PARTIAL|UNAVAILABLE)/i)?.[1]?.toUpperCase();
  if (!status) return null;
  const missing = reference.match(/missing_chapters=([^;]+)/i)?.[1]?.trim();
  if (status === 'UNAVAILABLE') return 'SOURCE CONTENT UNAVAILABLE — do not treat this PDF as a complete book.';
  return missing
    ? 'PARTIAL SOURCE — missing chapters: ' + missing + '. Review the coverage report before assigning to students.'
    : 'PARTIAL SOURCE — chapter coverage requires review before assigning to students.';
}

function formatBytes(value: number) {
  if (value < 1024 * 1024) return Math.max(1, Math.round(value / 1024)) + ' KB';
  return (value / (1024 * 1024)).toFixed(1) + ' MB';
}

export default function TextbookLibraryPage() {
  const [classCode, setClassCode] = useState('6');
  const [subjectCode, setSubjectCode] = useState('maths');
  const [tracks, setTracks] = useState<Track[]>([]);
  const [role, setRole] = useState('');
  const [permissions, setPermissions] = useState<string[]>([]);
  const [previewDocumentId, setPreviewDocumentId] = useState<number | null>(null);
  const [previewPage, setPreviewPage] = useState(1);
  const [previewPageDraft, setPreviewPageDraft] = useState('1');
  const [previewZoom, setPreviewZoom] = useState(100);
  const [previewFitWidth, setPreviewFitWidth] = useState(false);
  const [previewFitPage, setPreviewFitPage] = useState(true);
  const [previewThumbnailsOpen, setPreviewThumbnailsOpen] = useState(false);
  const [previewFullscreen, setPreviewFullscreen] = useState(false);
  const [previewImageLoading, setPreviewImageLoading] = useState(false);
  const [previewImageError, setPreviewImageError] = useState('');
  const previewDialogRef = useRef<HTMLElement | null>(null);
  const previewPageImageRef = useRef<HTMLImageElement | null>(null);
  const [scope, setScope] = useState<'SUBJECT_BOOK' | 'CHAPTER_PDF'>('CHAPTER_PDF');
  const [resourceLanguage, setResourceLanguage] = useState<'hi' | 'en'>('hi');
  const [chapterId, setChapterId] = useState('');
  const [library, setLibrary] = useState<PdfAsset[]>([]);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [selectedPdfId, setSelectedPdfId] = useState('');
  const [pdfTitle, setPdfTitle] = useState('');
  const [sourceReference, setSourceReference] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [sourceTitle, setSourceTitle] = useState('');
  const [sourceUrl, setSourceUrl] = useState('https://scert.bihar.gov.in/textbooks');
  const [edition, setEdition] = useState('');
  const [pageStart, setPageStart] = useState('1');
  const [pageEnd, setPageEnd] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const previewOriginScrollY = useRef(0);
  const previewHistoryEntry = useRef(false);

  const isAdmin = role === 'ADMIN';
  const maxPdfBytes = isAdmin ? 500 * 1024 * 1024 : 50 * 1024 * 1024;
  const canEdit = isAdmin || permissions.includes('CONTENT_EDIT');
  const canPublish = isAdmin || permissions.includes('CONTENT_PUBLISH');
  const classOptions = useMemo(
    () => [...new Map(tracks.map(track => [track.class_code, track])).values()],
    [tracks],
  );
  const subjectOptions = useMemo(
    () => tracks.filter(track => track.class_code === classCode),
    [tracks, classCode],
  );
  const selectedPdf = useMemo(
    () => library.find(item => Number(item.pdf_asset_id || item.id) === Number(selectedPdfId)),
    [library, selectedPdfId],
  );
  const previewAssignment = useMemo(
    () => assignments.find(item => item.document_id === previewDocumentId) || null,
    [assignments, previewDocumentId],
  );
  const previewPageCount = previewAssignment
    ? Math.max(1, previewAssignment.page_end - previewAssignment.page_start + 1)
    : 0;
  const firstPreviewThumb = Math.max(1, Math.min(previewPage - 6, previewPageCount - 19));
  const previewThumbnails = Array.from({ length: Math.min(20, previewPageCount) }, (_value, index) => firstPreviewThumb + index);
  const previewPageUrl = previewDocumentId
    ? '/api/v1/admin/learning-documents/' + previewDocumentId + '/pages/' + previewPage
    : '';
  const previewPageTitle = previewAssignment?.title || 'textbook PDF';

  const openPdfPreview = useCallback((documentId: number) => {
    previewOriginScrollY.current = window.scrollY;
    previewHistoryEntry.current = true;
    const url = new URL(window.location.href);
    url.searchParams.set('previewDocumentId', String(documentId));
    url.searchParams.set('previewPage', '1');
    window.history.pushState({ ...(window.history.state || {}), qePdfPreview: true }, '', url);
    setPreviewDocumentId(documentId);
    setPreviewPage(1);
    setPreviewPageDraft('1');
    setPreviewZoom(100);
    setPreviewFitWidth(false);
    setPreviewFitPage(true);
    setPreviewThumbnailsOpen(false);
    setPreviewImageError('');
    setPreviewImageLoading(true);
  }, []);

  const closePdfPreview = useCallback(() => {
    if (previewHistoryEntry.current && window.history.state?.qePdfPreview === true) {
      window.history.back();
      return;
    }
    const url = new URL(window.location.href);
    url.searchParams.delete('previewDocumentId');
    url.searchParams.delete('previewPage');
    window.history.replaceState(window.history.state, '', url);
    previewHistoryEntry.current = false;
    setPreviewDocumentId(null);
    setPreviewPage(1);
    window.requestAnimationFrame(() => window.scrollTo({ top: previewOriginScrollY.current, behavior: 'auto' }));
  }, []);

  const changePreviewPage = useCallback((requestedPage: number) => {
    const nextPage = Math.max(1, Math.min(Math.max(1, previewPageCount), Number(requestedPage) || 1));
    setPreviewPage(nextPage);
    setPreviewPageDraft(String(nextPage));
    setPreviewImageLoading(true);
    setPreviewImageError('');
    const url = new URL(window.location.href);
    url.searchParams.set('previewPage', String(nextPage));
    window.history.replaceState({ ...(window.history.state || {}), qePdfPreview: true }, '', url);
  }, [previewPageCount]);

  useEffect(() => {
    const syncPreviewWithHistory = () => {
      const params = new URLSearchParams(window.location.search);
      const documentId = Number(params.get('previewDocumentId') || 0);
      if (Number.isSafeInteger(documentId) && documentId > 0) {
        setPreviewDocumentId(documentId);
        const page = Number(params.get('previewPage') || 1);
        const safePage = Number.isSafeInteger(page) && page > 0 ? page : 1;
        setPreviewPage(safePage);
        setPreviewPageDraft(String(safePage));
        previewHistoryEntry.current = Boolean(window.history.state?.qePdfPreview);
        return;
      }
      setPreviewDocumentId(null);
      setPreviewPage(1);
      if (previewHistoryEntry.current) {
        previewHistoryEntry.current = false;
        window.requestAnimationFrame(() => window.scrollTo({ top: previewOriginScrollY.current, behavior: 'auto' }));
      }
    };
    window.addEventListener('popstate', syncPreviewWithHistory);
    syncPreviewWithHistory();
    return () => window.removeEventListener('popstate', syncPreviewWithHistory);
  }, []);

  useEffect(() => {
    if (previewDocumentId === null) return;
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    const onKeyDown = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (target && ['INPUT', 'SELECT', 'TEXTAREA', 'BUTTON'].includes(target.tagName)) return;
      if (event.altKey) return;
      if ((event.ctrlKey || event.metaKey) && ['0', '+', '=', '-'].includes(event.key)) {
        event.preventDefault();
        if (event.key === '0') { setPreviewFitPage(true); setPreviewFitWidth(false); }
        else if (event.key === '-') { setPreviewFitPage(false); setPreviewFitWidth(false); setPreviewZoom(value => Math.max(60, value - 10)); }
        else { setPreviewFitPage(false); setPreviewFitWidth(false); setPreviewZoom(value => Math.min(160, value + 10)); }
        return;
      }
      if (event.ctrlKey || event.metaKey) return;
      if (event.key === 'Escape') {
        event.preventDefault();
        if (document.fullscreenElement && previewDialogRef.current) {
          void document.exitFullscreen().catch(() => undefined);
          return;
        }
        closePdfPreview();
      } else if (event.key === 'ArrowRight' || event.key === 'PageDown') {
        event.preventDefault(); changePreviewPage(previewPage + 1);
      } else if (event.key === 'ArrowLeft' || event.key === 'PageUp') {
        event.preventDefault(); changePreviewPage(previewPage - 1);
      } else if (event.key === 'Home') {
        event.preventDefault(); changePreviewPage(1);
      } else if (event.key === 'End') {
        event.preventDefault(); changePreviewPage(previewPageCount);
      } else if (event.key === '0') {
        event.preventDefault(); setPreviewFitPage(true); setPreviewFitWidth(false);
      } else if (event.key === '+' || event.key === '=') {
        event.preventDefault(); setPreviewFitPage(false); setPreviewFitWidth(false); setPreviewZoom(value => Math.min(160, value + 10));
      } else if (event.key === '-') {
        event.preventDefault(); setPreviewFitPage(false); setPreviewFitWidth(false); setPreviewZoom(value => Math.max(60, value - 10));
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => {
      document.body.style.overflow = previousOverflow;
      window.removeEventListener('keydown', onKeyDown);
    };
  }, [previewDocumentId, previewPage, previewPageCount, closePdfPreview, changePreviewPage]);

  useEffect(() => {
    const updateFullscreen = () => setPreviewFullscreen(Boolean(document.fullscreenElement && document.fullscreenElement === previewDialogRef.current));
    document.addEventListener('fullscreenchange', updateFullscreen);
    return () => document.removeEventListener('fullscreenchange', updateFullscreen);
  }, []);

  useEffect(() => {
    if (previewDocumentId === null) return;
    setPreviewImageLoading(true);
    setPreviewImageError('');
  }, [previewDocumentId, previewPage]);

  async function togglePreviewFullscreen() {
    try {
      if (!document.fullscreenElement && previewDialogRef.current) await previewDialogRef.current.requestFullscreen();
      else if (document.fullscreenElement) await document.exitFullscreen();
    } catch {
      setPreviewImageError('Full-screen mode is not available in this browser.');
    }
  }

  useEffect(() => {
    fetch('/api/v1/auth/me', { cache: 'no-store' })
      .then(response => response.ok ? response.json() : null)
      .then(me => {
        if (!me) return;
        setRole(String(me.role || ''));
        setPermissions(Array.isArray(me.permissions) ? me.permissions : []);
      })
      .catch(() => undefined);
  }, []);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const curriculumRows = await api('/api/v1/curriculum');
      const trackMap = new Map<string, Track>();
      (Array.isArray(curriculumRows) ? curriculumRows : []).forEach((row: any) => {
        if (!row.class_code || !row.subject_code) return;
        const track: Track = {
          class_code: String(row.class_code),
          class_name: String(row.class_name || ('Class ' + row.class_code)),
          subject_code: String(row.subject_code),
          subject_name: String(row.subject_name || row.subject_code),
        };
        trackMap.set(track.class_code + ':' + track.subject_code, track);
      });
      const nextTracks = [...trackMap.values()];
      setTracks(nextTracks);
      const nextClassCode = nextTracks.some(track => track.class_code === classCode)
        ? classCode : (nextTracks[0]?.class_code || '');
      const nextSubjectCode = nextTracks.some(track => track.class_code === nextClassCode && track.subject_code === subjectCode)
        ? subjectCode : (nextTracks.find(track => track.class_code === nextClassCode)?.subject_code || '');
      if (nextClassCode !== classCode) setClassCode(nextClassCode);
      if (nextSubjectCode !== subjectCode) setSubjectCode(nextSubjectCode);

      const params = new URLSearchParams({ status: statusFilter });
      if (nextClassCode) params.set('classCode', nextClassCode);
      if (nextSubjectCode) params.set('subjectCode', nextSubjectCode);
      const chapterPromise = canEdit && nextClassCode && nextSubjectCode
        ? api('/api/v1/admin/content?classCode=' + encodeURIComponent(nextClassCode) + '&subjectCode=' + encodeURIComponent(nextSubjectCode))
        : Promise.resolve([]);
      const [pdfRows, contentRows, resourceRows] = await Promise.all([
        api('/api/v1/admin/learning-pdfs'),
        chapterPromise,
        api('/api/v1/admin/learning-documents?' + params.toString()),
      ]);
      const pdfs = Array.isArray(pdfRows) ? pdfRows : [];
      const content = Array.isArray(contentRows) ? contentRows : [];
      const resources = Array.isArray(resourceRows) ? resourceRows : [];
      setLibrary(pdfs);
      const distinct = new Map<number, Chapter>();
      content.forEach((row: any) => {
        const id = Number(row.chapter_id);
        if (Number.isFinite(id) && id > 0 && row.chapter_name) {
          distinct.set(id, {
            chapter_id: id,
            chapter_code: String(row.chapter_code || ''),
            chapter_name: String(row.chapter_name),
            chapter_status: String(row.chapter_status || ''),
          });
        }
      });
      const nextChapters = [...distinct.values()];
      setChapters(nextChapters);
      setAssignments(resources);
      if (selectedPdfId && !pdfs.some(pdf => String(pdf.pdf_asset_id || pdf.id) === selectedPdfId)) {
        setSelectedPdfId('');
      } else if (!selectedPdfId && pdfs.length > 0) {
        setSelectedPdfId(String(pdfs[0].pdf_asset_id || pdfs[0].id));
      }
      if (!chapterId || !nextChapters.some(chapter => String(chapter.chapter_id) === chapterId)) {
        setChapterId(nextChapters.length ? String(nextChapters[0].chapter_id) : '');
      }
      if (previewDocumentId && !resources.some(resource => resource.document_id === previewDocumentId)) {
        setPreviewDocumentId(null);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The textbook library could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [classCode, subjectCode, statusFilter, selectedPdfId, chapterId, canEdit, previewDocumentId]);

  useEffect(() => { void load(); }, [load]);

  async function uploadPdf() {
    if (!file) { setError('Choose the existing textbook PDF file first.'); return; }
    if (file.size > maxPdfBytes) {
      setError(isAdmin
        ? 'Admin PDF uploads are limited to 500 MB per file by the API configuration.'
        : 'PDF uploads for this staff account are limited to 50 MB per file.');
      return;
    }
    if (!pdfTitle.trim()) { setError('Give this PDF a recognizable library title.'); return; }
    setBusy(true); setError(''); setNotice('');
    try {
      const form = new FormData();
      form.append('file', file);
      form.append('title', pdfTitle.trim());
      if (sourceReference.trim()) form.append('sourceReference', sourceReference.trim());
      const created = await api('/api/v1/admin/learning-pdfs', { method: 'POST', body: form });
      const id = Number(created.pdf_asset_id || created.id);
      await load();
      setSelectedPdfId(String(id));
      setTitle(String(created.title || pdfTitle.trim()));
      setPageStart('1');
      setPageEnd(String(created.page_count || ''));
      setNotice(created.duplicate
        ? 'This PDF already exists in the library. The existing database record has been selected; no duplicate copy was created.'
        : 'PDF validated and saved to the private library. Select a subject/chapter below to attach it.');
      setFile(null);
      setPdfTitle('');
      setSourceReference('');
      const input = document.getElementById('qe-pdf-file') as HTMLInputElement | null;
      if (input) input.value = '';
    } catch (e) {
      setError(e instanceof Error ? e.message : 'PDF upload failed.');
    } finally {
      setBusy(false);
    }
  }

  async function attachPdf() {
    if (!selectedPdf) { setError('Select a PDF from the library or upload it first.'); return; }
    if (scope === 'CHAPTER_PDF' && !chapterId) { setError('Select the chapter that this PDF belongs to.'); return; }
    if (!title.trim()) { setError('Enter the title students should see.'); return; }
    if (!sourceTitle.trim() || !sourceUrl.trim() || !edition.trim()) {
      setError('Add the source title, official HTTPS URL, and edition/session before attaching this learning resource.');
      return;
    }
    if (!/^https:\/\//i.test(sourceUrl.trim())) { setError('The official source URL must use HTTPS.'); return; }
    setBusy(true); setError(''); setNotice('');
    try {
      const resource = await api('/api/v1/admin/learning-documents', {
        method: 'POST',
        body: JSON.stringify({
          pdfAssetId: Number(selectedPdf.pdf_asset_id || selectedPdf.id),
          scope, classCode, subjectCode, language: resourceLanguage,
          chapterId: scope === 'CHAPTER_PDF' ? Number(chapterId) : null,
          title: title.trim(), sourceTitle: sourceTitle.trim(),
          sourceUrl: sourceUrl.trim(), edition: edition.trim(),
          pageStart: Number(pageStart || 1),
          pageEnd: Number(pageEnd || selectedPdf.page_count),
        }),
      });
      setNotice('Resource attached as a draft. Review the metadata, then publish it for enrolled students.');
      setTitle('');
      await load();
      setSelectedPdfId(String(resource.pdf_asset_id || selectedPdfId));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'PDF could not be attached.');
    } finally {
      setBusy(false);
    }
  }

  async function setStatus(row: Assignment, nextStatus: 'PUBLISHED' | 'ARCHIVED' | 'DRAFT') {
    setBusy(true); setError(''); setNotice('');
    try {
      await api('/api/v1/admin/learning-documents/' + row.document_id + '/status', {
        method: 'PATCH', body: JSON.stringify({ status: nextStatus }),
      });
      setNotice('“' + row.title + '” is now ' + nextStatus.toLowerCase() + '.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The resource status could not be changed.');
    } finally {
      setBusy(false);
    }
  }

  return <main className="admin-shell">
    <AdminSidebar active="resources" variant="content" />
    <section className={'admin-main ' + styles.page}>
      <header className="admin-top">
        <div>
          <span className="admin-kicker">QUANTAEDGE LEARNING / TEXTBOOK RESOURCES</span>
          <h1>Textbook PDF library</h1>
          <p>Reuse a stored textbook PDF or add it once, then attach it to a whole subject or a specific chapter.</p>
        </div>
        <button type="button" className="admin-refresh" onClick={() => void load()} title="Refresh textbook library">↻</button>
      </header>

      <section className={styles.hero}>
        <div className={styles.heroIcon}>▤</div>
        <div><span>PRIVATE SOURCE LIBRARY</span><h2>One PDF. Multiple learning placements.</h2>
          <p>PDFs are stored privately in the database. Students receive rendered page images after access checks, not the original PDF download.</p>
        </div>
        <div className={styles.heroStats}><strong>{library.length}</strong><small>PDF files in library</small><strong>{assignments.filter(a => a.status === 'PUBLISHED').length}</strong><small>Published in this track</small></div>
      </section>

      {error && <div className={styles.alertError} role="alert">{error}</div>}
      {notice && <div className={styles.alertSuccess} role="status">{notice}</div>}

      {previewAssignment && <div className={styles.previewBackdrop} role="presentation" onMouseDown={event => {
        if (event.target === event.currentTarget) closePdfPreview();
      }}>
        <section ref={node => { previewDialogRef.current = node; }} className={styles.previewDialog} role="dialog" aria-modal="true" aria-labelledby="qe-pdf-preview-title" tabIndex={-1}>
          <div className={styles.previewHeading}>
            <div className={styles.previewTitleCopy}><span>PDF PREVIEW · READ ONLY</span><h2 id="qe-pdf-preview-title">{previewAssignment.title}</h2>
              <p>{previewAssignment.scope === 'SUBJECT_BOOK' ? 'Complete subject book' : (previewAssignment.chapter_name || 'Chapter PDF')} · {previewAssignment.language === 'en' ? 'English' : 'Hindi'} · {previewPageCount} pages</p>
            </div>
            <button type="button" className={styles.secondaryButton} onClick={closePdfPreview}>← Back to assignments</button>
          </div>

          <div className={styles.previewReaderToolbar} role="toolbar" aria-label="PDF reader controls">
            <div className={styles.previewPageControls}>
              <button type="button" className={styles.previewIconButton} aria-label="Previous page" title="Previous page" disabled={previewPage <= 1} onClick={() => changePreviewPage(previewPage - 1)}>‹</button>
              <label>Page <input type="number" min="1" max={previewPageCount} value={previewPageDraft} onChange={event => setPreviewPageDraft(event.target.value)} onBlur={() => changePreviewPage(Number(previewPageDraft))} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); changePreviewPage(Number(previewPageDraft)); } }} /><span>of {previewPageCount}</span></label>
              <button type="button" className={styles.previewIconButton} aria-label="Next page" title="Next page" disabled={previewPage >= previewPageCount} onClick={() => changePreviewPage(previewPage + 1)}>›</button>
            </div>
            <span className={styles.previewToolbarDivider} aria-hidden="true"/>
            <div className={styles.previewZoomControls}>
              <button type="button" className={styles.previewIconButton} aria-label="Zoom out" title="Zoom out" disabled={previewZoom <= 60} onClick={() => {setPreviewFitPage(false);setPreviewFitWidth(false);setPreviewZoom(value => Math.max(60,value-10));}}>−</button>
              <span>{previewFitPage?'Fit page':previewFitWidth?'Fit width':previewZoom+'%'}</span>
              <button type="button" className={styles.previewIconButton} aria-label="Zoom in" title="Zoom in" disabled={previewZoom >= 160} onClick={() => {setPreviewFitPage(false);setPreviewFitWidth(false);setPreviewZoom(value => Math.min(160,value+10));}}>＋</button>
            </div>
            <button type="button" className={styles.previewToolButton+(previewFitPage?' '+styles.isSelected:'')} onClick={() => {setPreviewFitPage(true);setPreviewFitWidth(false);}}>Fit page</button>
            <button type="button" className={styles.previewToolButton+(previewFitWidth?' '+styles.isSelected:'')} onClick={() => {setPreviewFitPage(false);setPreviewFitWidth(value => !value);}}>{previewFitWidth?'Actual size':'Fit width'}</button>
            <button type="button" className={styles.previewToolButton+(previewThumbnailsOpen?' '+styles.isSelected:'')} onClick={() => setPreviewThumbnailsOpen(value => !value)}>▤ Pages</button>
            <button type="button" className={styles.previewToolButton+' '+styles.previewFullscreenButton} onClick={() => void togglePreviewFullscreen()}>{previewFullscreen?'Exit full screen':'⛶ Full screen'}</button>
          </div>

          <div className={styles.previewReaderWorkspace+(previewThumbnailsOpen?' '+styles.withThumbnails:'')}>
            {previewThumbnailsOpen && <aside className={styles.previewThumbnails} aria-label="Page thumbnails">
              <div className={styles.previewThumbsHeader}><b>Pages</b><small>{previewPageCount} total</small></div>
              <div className={styles.previewThumbList}>{previewThumbnails.map(pageNumber=><button type="button" key={pageNumber} className={pageNumber===previewPage?styles.activeThumb:''} onClick={() => changePreviewPage(pageNumber)}>
                <img src={'/api/v1/admin/learning-documents/'+previewDocumentId+'/pages/'+pageNumber} alt="" loading="lazy" draggable={false}/><span>Page {pageNumber}</span>
              </button>)}</div>
              <div className={styles.previewThumbsFooter}>Showing pages {previewThumbnails[0]||0}–{previewThumbnails[previewThumbnails.length-1]||0}</div>
            </aside>}
            <div className={styles.previewPageStage}>
              <div className={styles.previewCanvasTop}><span><i/> Secured source preview</span><span>Page {previewPage} / {previewPageCount}</span></div>
              <div className={styles.previewPageViewport+(previewFitWidth?' '+styles.fitWidth:'')+(previewFitPage?' '+styles.fitPage:'')} key={previewDocumentId+'-'+previewPage}>
                {previewImageLoading&&<div className={styles.previewImageLoading}><span className={styles.previewSpinner}/> Rendering page {previewPage}…</div>}
                <img ref={previewPageImageRef} className={styles.previewPageImage} src={previewPageUrl} alt={'Page '+previewPage+' of '+previewPageTitle} draggable={false} onLoad={() => {setPreviewImageLoading(false);setPreviewImageError('');}} onError={() => {setPreviewImageLoading(false);setPreviewImageError('This page could not be rendered. Try again or return to assignments.');}} style={{width:previewFitPage?'auto':previewFitWidth?'100%':previewZoom+'%'}}/>
                {previewImageError&&<div className={styles.previewPageError} role="alert"><span>{previewImageError}</span><button type="button" className={styles.secondaryButton} onClick={() => {setPreviewImageError('');setPreviewImageLoading(true);if(previewPageImageRef.current)previewPageImageRef.current.src=previewPageUrl+'?retry='+Date.now();}}>Retry page</button></div>}
              </div>
              <div className={styles.previewCanvasBottom}><span>Private source preview · Page {previewPage}</span><span>Use ← → or Page Up / Page Down to navigate</span></div>
            </div>
          </div>
          <div className={styles.previewDialogFooter}><span>Read-only secure page rendering</span><span>Original PDF bytes are not exposed in this preview</span></div>
        </section>
      </div>}

      {canEdit && <section className={styles.panel}>
        <div className={styles.sectionHeading}><div><span>STEP 01</span><h2>Choose an existing PDF or upload it once</h2><p>Duplicates are detected by file checksum. Selecting a library item does not copy its PDF bytes.</p></div><span className={styles.stepIcon}>01</span></div>
        <div className={styles.libraryGrid}>
          <label className={styles.field}>Existing PDF library
            <select value={selectedPdfId} onChange={e => {
              setSelectedPdfId(e.target.value);
              const asset = library.find(item => Number(item.pdf_asset_id || item.id) === Number(e.target.value));
              if (asset) { setTitle(asset.title); setPageStart('1'); setPageEnd(String(asset.page_count)); }
            }}>
              <option value="">Select a stored PDF…</option>
              {library.map(pdf => <option key={pdf.pdf_asset_id || pdf.id} value={pdf.pdf_asset_id || pdf.id}>
                {pdf.title} · {pdf.page_count} pages · {formatBytes(pdf.file_size_bytes)}{contentCoverageWarning(pdf.source_reference) ? ' · ⚠ PARTIAL' : ''}
              </option>)}
            </select>
            <small>{library.length ? 'Choose one already stored in the library.' : 'No PDFs are registered yet. Upload an existing source PDF once, or import existing source records into this library.'}</small>
          </label>
          <div className={styles.uploadBox}>
            <div className={styles.uploadTitle}><b>Add a PDF to the library</b><small>PDF only · maximum {isAdmin ? '500 MB for Admin' : '50 MB'} · up to 2,000 pages</small></div>
            <label className={styles.field}>Library title
              <input value={pdfTitle} onChange={e => setPdfTitle(e.target.value)} placeholder="e.g. Bihar Board Class 6 Mathematics" />
            </label>
            <label className={styles.field}>Extraction source reference (optional)
              <input value={sourceReference} onChange={e => setSourceReference(e.target.value)} placeholder="Source filename, SHA-256, extraction bundle ID…" />
            </label>
            <label className={styles.fileInput} htmlFor="qe-pdf-file"><span>Choose PDF file</span><input id="qe-pdf-file" type="file" accept="application/pdf,.pdf" onChange={e => {
              const nextFile = e.target.files?.[0] || null;
              setFile(nextFile);
              if (nextFile && nextFile.size > maxPdfBytes) {
                setError(isAdmin
                  ? 'This file exceeds the 500 MB Admin upload limit.'
                  : 'This file exceeds the 50 MB upload limit for this staff account.');
              } else {
                setError('');
              }
            }} /><small>{file ? file.name + ' · ' + formatBytes(file.size) : 'No file selected'}</small></label>
            <button type="button" className={styles.primaryButton} disabled={busy || !file || !pdfTitle.trim()} onClick={() => void uploadPdf()}>{busy ? 'Processing…' : 'Validate and save PDF'}</button>
          </div>
        </div>
        {selectedPdf && <div className={styles.selectedSummary}>
          <span className={styles.pdfBadge}>PDF</span><div><b>{selectedPdf.title}</b><small>{selectedPdf.original_filename} · {selectedPdf.page_count} pages · {formatBytes(selectedPdf.file_size_bytes)} · {selectedPdf.source_kind.replaceAll('_',' ')}</small>
            {contentCoverageWarning(selectedPdf.source_reference) && <small className={styles.coverageWarning}>{contentCoverageWarning(selectedPdf.source_reference)}</small>}
            {selectedPdf.source_reference && <small>Source reference: {selectedPdf.source_reference}</small>}
          </div><span className={styles.hash}>{selectedPdf.sha256.slice(0, 12)}…</span>
        </div>}
      </section>}

      {canEdit && <section className={styles.panel}>
        <div className={styles.sectionHeading}><div><span>STEP 02</span><h2>Attach PDF to a book or chapter</h2><p>Subject books appear at subject level. Chapter PDFs appear with the matching chapter in the student library.</p></div><span className={styles.stepIcon}>02</span></div>
        <div className={styles.assignmentFields}>
          <label className={styles.field}>Class
            <select value={classCode} disabled={!classOptions.length} onChange={e => {
              const nextClass = e.target.value;
              setClassCode(nextClass);
              setSubjectCode(tracks.find(track => track.class_code === nextClass)?.subject_code || '');
              setChapterId('');
            }}>
              {classOptions.map(track => <option key={track.class_code} value={track.class_code}>{track.class_name} ({track.class_code})</option>)}
            </select>
          </label>
          <label className={styles.field}>Subject
            <select value={subjectCode} disabled={!subjectOptions.length} onChange={e => { setSubjectCode(e.target.value); setChapterId(''); }}>
              {subjectOptions.map(track => <option key={track.subject_code} value={track.subject_code}>{track.subject_name}</option>)}
            </select>
          </label>
          <label className={styles.field}>Placement
            <select value={scope} onChange={e => setScope(e.target.value as 'SUBJECT_BOOK' | 'CHAPTER_PDF')}>
              <option value="CHAPTER_PDF">Chapter PDF</option><option value="SUBJECT_BOOK">Complete subject book</option>
            </select>
          </label>
          <label className={styles.field}>Content medium
            <select value={resourceLanguage} onChange={e => setResourceLanguage(e.target.value as 'hi' | 'en')}>
              <option value="hi">Hindi (हिन्दी)</option><option value="en">English</option>
            </select>
          </label>
          {scope === 'CHAPTER_PDF' && <label className={styles.field}>Chapter
            <select value={chapterId} onChange={e => setChapterId(e.target.value)}>
              <option value="">Select chapter…</option>
              {chapters.map(ch => <option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}
            </select>
          </label>}
          <label className={styles.field}>Student-facing title
            <input value={title} onChange={e => setTitle(e.target.value)} placeholder={selectedPdf?.title || 'Title shown to students'} />
          </label>
          <label className={styles.field}>Official source title
            <input value={sourceTitle} onChange={e => setSourceTitle(e.target.value)} placeholder="Book title as printed on the source" />
          </label>
          <label className={styles.field}>Official HTTPS source URL
            <input type="url" value={sourceUrl} onChange={e => setSourceUrl(e.target.value)} placeholder="https://…" />
          </label>
          <label className={styles.field}>Edition / academic session
            <input value={edition} onChange={e => setEdition(e.target.value)} placeholder="e.g. 2025–26 edition" />
          </label>
          <label className={styles.field}>PDF start page
            <input type="number" min="1" value={pageStart} onChange={e => setPageStart(e.target.value)} />
          </label>
          <label className={styles.field}>PDF end page
            <input type="number" min={pageStart || 1} max={selectedPdf?.page_count || 2000} value={pageEnd} placeholder={String(selectedPdf?.page_count || '')} onChange={e => setPageEnd(e.target.value)} />
          </label>
        </div>
        <div className={styles.attachFooter}>
          <p>{scope === 'SUBJECT_BOOK' ? 'Complete book: include all concepts and practice questions.' : 'Chapter PDF: include concepts and QuantaEdge Advanced / Next Level material, with no practice questions.'} This {resourceLanguage === 'hi' ? 'Hindi' : 'English'} edition is delivered when a student selects the matching language. The saved page range can map a chapter to its own PDF or to a range within a complete book.</p>
          <button type="button" className={styles.primaryButton} disabled={busy || !selectedPdf || (scope === 'CHAPTER_PDF' && !chapterId) || !title.trim()} onClick={() => void attachPdf()}>{busy ? 'Saving…' : 'Attach as draft'}</button>
        </div>
      </section>}

      <section className={styles.panel}>
        <div className={styles.sectionHeading}><div><span>RESOURCE GOVERNANCE</span><h2>Book and chapter assignments</h2><p>Drafts remain invisible to students until a permitted publisher publishes them.</p></div>
          <select className={styles.filter} value={statusFilter} onChange={e => setStatusFilter(e.target.value)}><option value="ALL">All statuses</option><option value="DRAFT">Drafts</option><option value="PUBLISHED">Published</option><option value="ARCHIVED">Archived</option></select>
        </div>
        {loading ? <p className={styles.empty}>Loading resource assignments…</p> : assignments.length === 0 ? <div className={styles.empty}><b>No assignments in this view</b><span>Choose a PDF above and attach it to this class/subject.</span></div> :
          <div className={styles.assignmentList}>
            {assignments.map(row => <article key={row.document_id} className={styles.assignmentCard}>
              <div className={styles.assignmentIcon}>{row.scope === 'SUBJECT_BOOK' ? '▤' : '▧'}</div>
              <div className={styles.assignmentBody}>
                <div className={styles.assignmentTitle}><h3>{row.title}</h3><span className={styles['status' + row.status]}>{row.status}</span></div>
                <p>{row.scope === 'SUBJECT_BOOK' ? 'Complete subject book' : 'Chapter PDF'} · {row.language === 'en' ? 'English' : 'Hindi'} · Class {row.class_code} · {row.subject_code} {row.chapter_name ? '· ' + row.chapter_name : ''}</p>
                <small>{row.original_filename} · pages {row.page_start}–{row.page_end} · {row.source_title || 'Source title not set'} · {row.edition || 'Edition not set'}</small>
                <small>{row.source_url || 'Official source URL not set'}</small>
                {row.scope === 'CHAPTER_PDF' && row.chapter_active !== true && <small className={styles.publishHint}>This curriculum chapter is inactive. Reactivate it before publishing its PDF.</small>}
              </div>
              <div className={styles.rowActions}>
                <button type="button" className={styles.secondaryButton} disabled={row.asset_review_status != null && row.asset_review_status !== 'APPROVED'} onClick={() => openPdfPreview(row.document_id)}>Preview</button>
                {row.status !== 'PUBLISHED' && row.status !== 'ARCHIVED' && canPublish &&
                  <button type="button" className={styles.publishButton}
                    disabled={busy || (row.scope === 'CHAPTER_PDF' && row.chapter_active !== true)}
                    title={row.scope === 'CHAPTER_PDF' && row.chapter_active !== true ? 'Reactivate this curriculum chapter first.' : 'Publish this resource for eligible students.'}
                    onClick={() => void setStatus(row, 'PUBLISHED')}>Publish</button>}
                {row.status === 'PUBLISHED' && canPublish && <button type="button" className={styles.archiveButton} disabled={busy} onClick={() => void setStatus(row, 'ARCHIVED')}>Archive</button>}
                {row.status === 'ARCHIVED' && canEdit && <button type="button" className={styles.publishButton} disabled={busy} onClick={() => void setStatus(row, 'DRAFT')}>Restore to draft</button>}
                {row.status === 'DRAFT' && !canPublish && <small className={styles.roleHint}>Awaiting a publisher</small>}
              </div>
            </article>)}
          </div>}
      </section>
    </section>
  </main>;
}
