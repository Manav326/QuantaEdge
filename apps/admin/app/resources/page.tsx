'use client';

import AdminSidebar from '../components/AdminSidebar';
import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import styles from './Resources.module.css';
import { useUiTranslation } from '../i18n/useUiTranslation';
import PremiumPagination from '../components/PremiumPagination';

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

function normalizeIntakeReport(value: any): any {
  const parseJson = (item: any) => {
    if (typeof item === 'string') {
      try { return JSON.parse(item); } catch { return {}; }
    }
    if (item && typeof item.value === 'string') {
      try { return JSON.parse(item.value); } catch { return {}; }
    }
    return item && typeof item === 'object' ? item : {};
  };
  return { ...(value || {}), inventory: parseJson(value?.inventory), reconciliation: parseJson(value?.reconciliation), chapter_ranges: parseJson(value?.chapter_ranges) };
}

async function api(url: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers || {});
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const response = await fetch(url, {
    ...init, headers, credentials: 'same-origin', cache: 'no-store',
  });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(body.detail || body.message || body.error || ('__QE_REQUEST_FAILED__' + response.status));
  return body;
}

function localizedError(error: unknown, t: UiTranslate, fallbackKey: string): string {
  if (error instanceof Error) {
    const status = error.message.match(/^__QE_REQUEST_FAILED__(\d+)$/);
    if (status) return t('admin.resources.requestFailed', { status: Number(status[1]) });
    if (error.message) return error.message;
  }
  return t(fallbackKey);
}
function resourceStatusLabel(status: string | null | undefined, t: UiTranslate): string {
  const value = String(status || '').toUpperCase();
  const map: Record<string, string> = {
    PUBLISHED: 'admin.resources.published', DRAFT: 'admin.resources.draft',
    ARCHIVED: 'admin.resources.archived', PENDING: 'admin.resources.statusPending',
    REVIEWED: 'admin.resources.statusReviewed', NEEDS_REVIEW: 'admin.resources.statusNeedsReview',
    APPROVED: 'admin.resources.statusApproved', FAILED: 'admin.resources.statusFailed',
    RUNNING: 'admin.resources.statusRunning', PRIVATE: 'admin.resources.statusPrivate',
    COMPLETE: 'admin.resources.statusComplete',
  };
  return map[value] ? t(map[value]) : (value ? t('admin.resources.unknownStatus', { status: value.replaceAll('_', ' ') }) : '');
}
function resourceCategoryLabel(category: string | null | undefined, t: UiTranslate): string {
  const value = String(category || '').toUpperCase();
  const map: Record<string, string> = {
    MAIN_TEXTBOOK: 'admin.resources.mainTextbook', PART: 'admin.resources.partVolume',
    SUPPLEMENTARY: 'admin.resources.supplementary', WORKBOOK: 'admin.resources.workbook',
    TEACHER_GUIDE: 'admin.resources.teacherGuide', REFERENCE: 'admin.resources.referenceBook',
    OTHER: 'admin.resources.otherResource',
  };
  return map[value] ? t(map[value]) : String(category || '').replaceAll('_', ' ');
}
function contentCoverageWarning(reference: string | null | undefined, t: UiTranslate): string | null {
  if (!reference) return null;
  const parentStatus = reference.match(/parent_book_status=(PARTIAL|UNAVAILABLE)/i)?.[1]?.toUpperCase();
  const parentMissing = reference.match(/parent_missing_chapters=([^;]+)/i)?.[1]?.trim();
  if (parentStatus) {
    return parentMissing
      ? t('admin.resources.warning.parentMissing', { status: parentStatus, chapters: parentMissing })
      : t('admin.resources.warning.parentReview', { status: parentStatus });;
  }
  const status = reference.match(/content_status=(PARTIAL|UNAVAILABLE)/i)?.[1]?.toUpperCase();
  if (!status) return null;
  const missing = reference.match(/missing_chapters=([^;]+)/i)?.[1]?.trim();
  if (status === 'UNAVAILABLE') return t('admin.resources.warning.unavailable');
  return missing
    ? t('admin.resources.warning.partialMissing', { chapters: missing })
    : t('admin.resources.warning.partial');
}

function formatBytes(value: number) {
  if (value < 1024 * 1024) return Math.max(1, Math.round(value / 1024)) + ' KB';
  return (value / (1024 * 1024)).toFixed(1) + ' MB';
}

type UiTranslate = (key: string, values?: Record<string, string | number>) => string;
type GovernanceFacet = 'class' | 'subject' | 'status' | 'language' | 'scope';
type GovernanceSelections = Record<GovernanceFacet, string[]>;
type FilterOption = { value: string; label: string; count: number };

function valueForFacet(row: Assignment, facet: GovernanceFacet): string {
  switch (facet) {
    case 'class': return String(row.class_code);
    case 'subject': return String(row.subject_code);
    case 'status': return String(row.status);
    case 'language': return String(row.language);
    case 'scope': return String(row.scope);
  }
}

function matchesGovernanceFilters(
  row: Assignment,
  selected: GovernanceSelections,
  ignoreFacet?: GovernanceFacet,
): boolean {
  return (['class', 'subject', 'status', 'language', 'scope'] as GovernanceFacet[]).every(facet =>
    facet === ignoreFacet || selected[facet].length === 0 || selected[facet].includes(valueForFacet(row, facet)),
  );
}

function addFacetCounts(
  facets: Record<string, Record<string, number>>,
  options: Array<{ value: string; label: string }>,
  facet: GovernanceFacet,
): FilterOption[] {
  return options.map(option => ({ ...option, count: Number(facets[facet]?.[option.value] || 0) }));
}

function MultiSelectFilter({
  label,
  options,
  selected,
  onChange,
  t,
}: {
  label: string;
  options: FilterOption[];
  selected: string[];
  onChange: (values: string[]) => void;
  t: UiTranslate;
}) {
  const [open, setOpen] = useState(false);
  const filterRef = useRef<HTMLDivElement | null>(null);

  useEffect(() => {
    if (!open) return;
    const closeWhenOutside = (event: MouseEvent | TouchEvent) => {
      if (event.target instanceof Node && !filterRef.current?.contains(event.target)) setOpen(false);
    };
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('mousedown', closeWhenOutside);
    document.addEventListener('touchstart', closeWhenOutside);
    document.addEventListener('keydown', closeOnEscape);
    return () => {
      document.removeEventListener('mousedown', closeWhenOutside);
      document.removeEventListener('touchstart', closeWhenOutside);
      document.removeEventListener('keydown', closeOnEscape);
    };
  }, [open]);

  return (
    <div ref={filterRef} className={styles.multiFilter + (open ? ' ' + styles.multiFilterOpen : '')}>
      <button type="button" className={styles.filterTrigger} aria-haspopup="true" aria-expanded={open}
        onClick={() => setOpen(value => !value)}>
        <span>{label}</span>
        <strong>{selected.length === 0 ? t('admin.resources.all') : t('admin.resources.selectedCount', { count: selected.length })}</strong>
        <span className={styles.filterChevron} aria-hidden="true">{open ? '▴' : '▾'}</span>
      </button>
      {open && <div className={styles.filterMenu} role="group" aria-label={t('admin.resources.filterOptions', { label })}>
        <div className={styles.filterMenuActions}>
          <button type="button" onClick={() => onChange(options.map(option => option.value))}>{t('admin.resources.selectAll')}</button>
          <button type="button" onClick={() => onChange([])}>{t('admin.resources.clearFilter')}</button>
        </div>
        {options.length === 0
          ? <p className={styles.filterEmpty}>{t('admin.resources.noOptions')}</p>
          : options.map(option => (
            <label className={styles.filterOption} key={option.value}>
              <input
                type="checkbox"
                checked={selected.includes(option.value)}
                onChange={event => onChange(
                  event.target.checked
                    ? [...selected, option.value]
                    : selected.filter(value => value !== option.value),
                )}
              />
              <span>{option.label}</span>
              <small>{option.count}</small>
            </label>
          ))}
      </div>}
    </div>
  );
}

export default function TextbookLibraryPage() {
  const t = useUiTranslation();
  const [classCode, setClassCode] = useState('6');
  const [subjectCode, setSubjectCode] = useState('maths');
  const [viewClassCodes, setViewClassCodes] = useState<string[]>([]);
  const [viewSubjectCodes, setViewSubjectCodes] = useState<string[]>([]);
  const [viewStatuses, setViewStatuses] = useState<string[]>([]);
  const [viewLanguages, setViewLanguages] = useState<string[]>([]);
  const [viewScopes, setViewScopes] = useState<string[]>([]);
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
  const [libraryCache, setLibraryCache] = useState<PdfAsset[]>([]);
  const [libraryQuery, setLibraryQuery] = useState('');
  const [libraryPage, setLibraryPage] = useState(0);
  const [libraryTotal, setLibraryTotal] = useState(0);
  const [libraryPageSize, setLibraryPageSize] = useState(10);
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [assignmentPage, setAssignmentPage] = useState(0);
  const [assignmentTotal, setAssignmentTotal] = useState(0);
  const [assignmentPageSize, setAssignmentPageSize] = useState(10);
  const [assignmentFacets, setAssignmentFacets] = useState<Record<string, Record<string, number>>>({});
  const [selectedPdfId, setSelectedPdfId] = useState('');
  const [pdfTitle, setPdfTitle] = useState('');
  const [sourceReference, setSourceReference] = useState('');
  const [uploadCategory, setUploadCategory] = useState('MAIN_TEXTBOOK');
  const [uploadClassCode, setUploadClassCode] = useState('6');
  const [uploadSubjectCode, setUploadSubjectCode] = useState('maths');
  const [uploadLanguage, setUploadLanguage] = useState<'hi' | 'en'>('hi');
  const [uploadPartLabel, setUploadPartLabel] = useState('');
  const [intakeAssetId, setIntakeAssetId] = useState<number | null>(null);
  const [intakeReport, setIntakeReport] = useState<any>(null);
  const [intakeRows, setIntakeRows] = useState<any[]>([]);
  const [intakePage, setIntakePage] = useState(0);
  const [intakeTotal, setIntakeTotal] = useState(0);
  const [intakePageSize, setIntakePageSize] = useState(10);
  const [intakeSummary, setIntakeSummary] = useState<Record<string, number>>({});
  const [intakeRanges, setIntakeRanges] = useState<any[]>([]);
  const [rangeReviewNotes, setRangeReviewNotes] = useState('');
  const [rangeDraftSourceTitle, setRangeDraftSourceTitle] = useState('');
  const [rangeDraftSourceUrl, setRangeDraftSourceUrl] = useState('');
  const [rangeDraftEdition, setRangeDraftEdition] = useState('');
  const [reviewAcknowledged, setReviewAcknowledged] = useState(false);
  const [reviewNotes, setReviewNotes] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [sourceTitle, setSourceTitle] = useState('');
  const [sourceUrl, setSourceUrl] = useState('https://scert.bihar.gov.in/textbooks');
  const [edition, setEdition] = useState('');
  const [pageStart, setPageStart] = useState('1');
  const [pageEnd, setPageEnd] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const previewOriginScrollY = useRef(0);
  const previewHistoryEntry = useRef(false);

  useEffect(() => {
    if (!intakeAssetId) { setIntakeRanges([]); return; }
    const saved = Array.isArray(intakeReport?.chapter_ranges) ? intakeReport.chapter_ranges : [];
    setIntakeRanges(chapters.map(chapter => {
      const match = saved.find((row: any) => Number(row.chapter_id) === Number(chapter.chapter_id));
      return {
        chapterId: Number(chapter.chapter_id),
        title: chapter.chapter_name,
        pageStart: match ? String(match.page_start ?? '') : '',
        pageEnd: match ? String(match.page_end ?? '') : '',
      };
    }));
  }, [chapters, intakeAssetId, intakeReport?.chapter_ranges]);

  const isAdmin = role === 'ADMIN';
  const maxPdfBytes = isAdmin ? 500 * 1024 * 1024 : 50 * 1024 * 1024;
  const canEdit = isAdmin || permissions.includes('CONTENT_EDIT');
  const canReview = isAdmin || permissions.includes('CONTENT_REVIEW');
  const canPublish = isAdmin || permissions.includes('CONTENT_PUBLISH');
  const classOptions = useMemo(
    () => [...new Map(tracks.map(track => [track.class_code, track])).values()],
    [tracks],
  );
  const subjectOptions = useMemo(
    () => tracks.filter(track => track.class_code === classCode),
    [tracks, classCode],
  );
  const subjectFilterBaseOptions = useMemo(
    () => [...new Map(tracks.map(track => [track.subject_code, track])).values()]
      .map(track => ({ value: track.subject_code, label: track.subject_name })),
    [tracks],
  );
  const filterSelections = useMemo<GovernanceSelections>(() => ({
    class: viewClassCodes,
    subject: viewSubjectCodes,
    status: viewStatuses,
    language: viewLanguages,
    scope: viewScopes,
  }), [viewClassCodes, viewSubjectCodes, viewStatuses, viewLanguages, viewScopes, t]);
  const classFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, classOptions.map(track => ({
      value: track.class_code,
      label: t('admin.resources.classLine', { classCode: track.class_code }),
    })), 'class'),
    [assignmentFacets, classOptions],
  );
  const subjectFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, subjectFilterBaseOptions, 'subject'),
    [assignmentFacets, subjectFilterBaseOptions],
  );
  const statusFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, [
      { value: 'PUBLISHED', label: t('admin.resources.published') },
      { value: 'DRAFT', label: t('admin.resources.draft') },
      { value: 'ARCHIVED', label: t('admin.resources.archived') },
    ], 'status'),
    [assignmentFacets],
  );
  const languageFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, [
      { value: 'hi', label: t('admin.resources.hindi') },
      { value: 'en', label: t('admin.resources.english') },
    ], 'language'),
    [assignmentFacets],
  );
  const scopeFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, [
      { value: 'CHAPTER_PDF', label: t('admin.resources.chapterPdf') },
      { value: 'SUBJECT_BOOK', label: t('admin.resources.completeSubjectBook') },
    ], 'scope'),
    [assignmentFacets],
  );
  const visibleAssignments = assignments;
  const activeFilterCount = [
    viewClassCodes, viewSubjectCodes, viewStatuses, viewLanguages, viewScopes,
  ].filter(values => values.length > 0).length;
  const selectedPdf = useMemo(
    () => libraryCache.find(item => Number(item.pdf_asset_id || item.id) === Number(selectedPdfId)),
    [libraryCache, selectedPdfId],
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
  const previewPageTitle = previewAssignment?.title || t('admin.resources.title');

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
      if (event.key === 'Escape') {
        event.preventDefault();
        if (document.fullscreenElement && previewDialogRef.current) {
          void document.exitFullscreen().catch(() => undefined);
          return;
        }
        closePdfPreview();
        return;
      }
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
      if (event.key === 'ArrowRight' || event.key === 'PageDown') {
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

      // Fetch only the requested server-side page. Filter facets are computed by the API
      // across the matching inventory, not inferred from the currently visible page.
      const params = new URLSearchParams({ status: 'ALL', limit: String(assignmentPageSize), offset: String(assignmentPage * assignmentPageSize) });
      if (viewClassCodes.length) params.set('classCodes', viewClassCodes.join(','));
      if (viewSubjectCodes.length) params.set('subjectCodes', viewSubjectCodes.join(','));
      if (viewStatuses.length) params.set('statuses', viewStatuses.join(','));
      if (viewLanguages.length) params.set('languages', viewLanguages.join(','));
      if (viewScopes.length) params.set('scopes', viewScopes.join(','));
      const chapterPromise = canEdit && nextClassCode && nextSubjectCode
        ? api('/api/v1/admin/content?classCode=' + encodeURIComponent(nextClassCode) + '&subjectCode=' + encodeURIComponent(nextSubjectCode))
        : Promise.resolve([]);
      const intakePromise = canReview
        ? api('/api/v1/admin/source-ingestion/intake?limit=' + intakePageSize + '&offset=' + (intakePage * intakePageSize)).catch(() => ({ items: [], total: 0, summary: {} }))
        : Promise.resolve({ items: [], total: 0, summary: {} });
      const [contentRows, resourceRows, intakeData] = await Promise.all([
        chapterPromise,
        api('/api/v1/admin/learning-documents?' + params.toString()),
        intakePromise,
      ]);
      const content = Array.isArray(contentRows) ? contentRows : [];
      const resources: Assignment[] = (Array.isArray(resourceRows?.items) ? resourceRows.items : (Array.isArray(resourceRows) ? resourceRows : [])) as Assignment[];
      setAssignmentTotal(Number(resourceRows?.total ?? resources.length));
      setAssignmentFacets(resourceRows?.facets && typeof resourceRows.facets === 'object' ? resourceRows.facets : {});
      setIntakeRows(Array.isArray(intakeData?.items) ? intakeData.items : (Array.isArray(intakeData) ? intakeData : []));
      setIntakeTotal(Number(intakeData?.total ?? (Array.isArray(intakeData) ? intakeData.length : 0)));
      setIntakeSummary(intakeData?.summary && typeof intakeData.summary === 'object' ? intakeData.summary : {});
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
      if (!chapterId || !nextChapters.some(chapter => String(chapter.chapter_id) === chapterId)) {
        setChapterId(nextChapters.length ? String(nextChapters[0].chapter_id) : '');
      }
      if (previewDocumentId && !resources.some(resource => resource.document_id === previewDocumentId)) {
        setPreviewDocumentId(null);
      }
    } catch (e) {
      setError(localizedError(e, t, 'admin.resources.libraryLoadFailed'));
    } finally {
      setLoading(false);
    }
  }, [classCode, subjectCode, chapterId, canEdit, canReview, previewDocumentId, assignmentPage, assignmentPageSize, intakePage, intakePageSize,
      viewClassCodes, viewSubjectCodes, viewStatuses, viewLanguages, viewScopes]);

  useEffect(() => { void load(); }, [load]);

  useEffect(() => {
    if (!canEdit) return;
    let active = true;
    (async () => {
      const params = new URLSearchParams({ limit: String(libraryPageSize), offset: String(libraryPage * libraryPageSize) });
      if (libraryQuery.trim()) params.set('query', libraryQuery.trim());
      const response = await api('/api/v1/admin/learning-pdfs?' + params.toString());
      if (!active) return;
      const items: PdfAsset[] = Array.isArray(response?.items) ? response.items : (Array.isArray(response) ? response : []);
      setLibrary(items);
      setLibraryTotal(Number(response?.total ?? items.length));
      setLibraryCache(current => {
        const byId = new Map<number, PdfAsset>(current.map(item => [Number(item.pdf_asset_id || item.id), item]));
        items.forEach(item => byId.set(Number(item.pdf_asset_id || item.id), item));
        return [...byId.values()];
      });
      if (!selectedPdfId && items.length) setSelectedPdfId(String(items[0].pdf_asset_id || items[0].id));
    })().catch((e:any) => {
      if (active) setError(localizedError(e, t, 'admin.resources.pdfLibraryLoadFailed'));
    });
    return () => { active = false; };
  }, [canEdit, libraryPage, libraryPageSize, libraryQuery, t]);

  useEffect(() => {
    if (!tracks.length) return;
    const selectedClass = tracks.find(track => track.class_code === uploadClassCode);
    if (!selectedClass) {
      setUploadClassCode(tracks[0].class_code);
      setUploadSubjectCode(tracks[0].subject_code);
      return;
    }
    if (!tracks.some(track => track.class_code === uploadClassCode && track.subject_code === uploadSubjectCode)) {
      setUploadSubjectCode(selectedClass.subject_code);
    }
  }, [tracks, uploadClassCode, uploadSubjectCode]);

  async function uploadPdf() {
    if (!file) { setError(t('admin.resources.choosePdfFirst')); return; }
    if (file.size > maxPdfBytes) {
      setError(isAdmin
        ? t('admin.resources.uploadLimitAdmin')
        : t('admin.resources.uploadLimitStaff'));
      return;
    }
    if (!pdfTitle.trim()) { setError(t('admin.resources.libraryTitleRequired')); return; }
    setBusy(true); setError(''); setNotice('');
    try {
      const form = new FormData();
      form.append('file', file);
      form.append('title', pdfTitle.trim());
      if (sourceReference.trim()) form.append('sourceReference', sourceReference.trim());
      const created = await api('/api/v1/admin/learning-pdfs', { method: 'POST', body: form });
      const id = Number(created.pdf_asset_id || created.id);
      if (!Number.isSafeInteger(id) || id <= 0) {
        throw new Error(t('admin.resources.uploadInvalidLibraryId'));
      }
      setIntakeAssetId(id);
      // Classification is stored in a dedicated table, never hidden in source-reference text.
      await api('/api/v1/admin/source-ingestion/intake/' + id, {
        method: 'PUT',
        body: JSON.stringify({
          category: uploadCategory,
          classCode: uploadClassCode,
          subjectCode: uploadSubjectCode,
          language: uploadLanguage,
          partLabel: uploadPartLabel.trim() || null,
          reviewNotes: null,
        }),
      });
      let analysis: any = null;
      try {
        analysis = await api('/api/v1/admin/source-ingestion/intake/' + id + '/analyze', { method: 'POST' });
        setIntakeReport(normalizeIntakeReport(analysis));
      } catch (analysisError) {
        setIntakeReport(null);
        setNotice(t('admin.resources.analysisAttention', { error: analysisError instanceof Error ? analysisError.message : t('admin.resources.unknownError') }));
      }
      await load();
      setSelectedPdfId(String(id));
      setTitle(String(created.title || pdfTitle.trim()));
      setPageStart('1');
      setPageEnd(String(created.page_count || ''));
      if (analysis) {
        const report = analysis.reconciliation || {};
        const missingCount = Array.isArray(report.missing_from_book) ? report.missing_from_book.length : 0;
        const extraCount = Array.isArray(report.extra_in_book) ? report.extra_in_book.length : 0;
        const ambiguousCount = Array.isArray(report.ambiguous) ? report.ambiguous.length : 0;
        setNotice(t('admin.resources.uploadScanSummary', { duplicateNote: created.duplicate ? t('admin.resources.duplicateReused') : t('admin.resources.pdfSaved'), missingCount, extraCount, ambiguousCount }));
      }
      setFile(null);
      setPdfTitle('');
      setSourceReference('');
      setUploadPartLabel('');
      const input = document.getElementById('qe-pdf-file') as HTMLInputElement | null;
      if (input) input.value = '';
    } catch (e) {
      setError(localizedError(e, t, 'admin.resources.uploadFailed'));
    } finally {
      setBusy(false);
    }
  }

  async function attachPdf() {
    if (!selectedPdf) { setError(t('admin.resources.selectPdf')); return; }
    if (scope === 'CHAPTER_PDF' && !chapterId) { setError(t('admin.resources.selectChapter')); return; }
    if (!title.trim()) { setError(t('admin.resources.studentTitleRequired')); return; }
    if (!sourceTitle.trim() || !sourceUrl.trim() || !edition.trim()) {
      setError(t('admin.resources.sourceMetadataRequired'));
      return;
    }
    if (!/^https:\/\//i.test(sourceUrl.trim())) { setError(t('admin.resources.httpsOnly')); return; }
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
      setNotice(t('admin.resources.attachedDraft'));
      setTitle('');
      await load();
      setSelectedPdfId(String(resource.pdf_asset_id || selectedPdfId));
    } catch (e) {
      setError(localizedError(e, t, 'admin.resources.attachFailed'));
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
      setNotice(t('admin.resources.statusUpdated', { title: row.title, status: resourceStatusLabel(nextStatus, t) }));
      await load();
    } catch (e) {
      setError(localizedError(e, t, 'admin.resources.statusChangeFailed'));
    } finally {
      setBusy(false);
    }
  }

  return <main className="admin-shell">
    <AdminSidebar active="resources" variant="content" />
    <section className={'admin-main ' + styles.page}>
      <header className="admin-top">
        <div>
          <span className="admin-kicker">{t('admin.resources.kicker')}</span>
          <h1>{t('admin.resources.title')}</h1>
          <p>{t('admin.resources.description')}</p>
          <Link href="/learning-resources" className={styles.catalogueLink}>{t('admin.resources.unifiedCatalogue')}</Link>
        </div>
        <button type="button" className="admin-refresh" onClick={() => void load()} title={t('admin.resources.refresh')}>↻</button>
      </header>

      <section className={styles.hero}>
        <div className={styles.heroIcon}>▤</div>
        <div><span>{t('admin.resources.privateLibrary')}</span><h2>{t('admin.resources.heroTitle')}</h2>
          <p>{t('admin.resources.privateStorage')}</p>
        </div>
        <div className={styles.heroStats}><strong>{libraryTotal}</strong><small>{t('admin.resources.pdfFiles')}</small><strong>{Number(assignmentFacets.status?.PUBLISHED || 0)}</strong><small>{t('admin.resources.publishedFiltered')}</small></div>
      </section>

      {error && <div className={styles.alertError} role="alert">{error}</div>}
      {notice && <div className={styles.alertSuccess} role="status">{notice}</div>}

      <section className={styles.panel + " " + styles.governancePanel}>
        <div className={styles.governanceHeader}>
          <div className={styles.sectionHeading}>
            <div><span>{t('admin.resources.governance')}</span><h2>{t('admin.resources.assignmentsTitle')}</h2><p>{t('admin.resources.assignmentsDescription')}</p></div>
          </div>
          <div className={styles.governanceFilterArea}>
            <div className={styles.governanceFilterIntro}><span>{t('admin.resources.refineInventory')}</span><small>{t('admin.resources.combineFilters')}</small></div>
            <div className={styles.filterBar}>
              <MultiSelectFilter t={t} label={t('admin.resources.class')} options={classFilterOptions} selected={viewClassCodes} onChange={values=>{setViewClassCodes(values);setAssignmentPage(0)}} />
              <MultiSelectFilter t={t} label={t('admin.resources.subject')} options={subjectFilterOptions} selected={viewSubjectCodes} onChange={values=>{setViewSubjectCodes(values);setAssignmentPage(0)}} />
              <MultiSelectFilter t={t} label={t('admin.resources.status')} options={statusFilterOptions} selected={viewStatuses} onChange={values=>{setViewStatuses(values);setAssignmentPage(0)}} />
              <MultiSelectFilter t={t} label={t('admin.resources.language')} options={languageFilterOptions} selected={viewLanguages} onChange={values=>{setViewLanguages(values);setAssignmentPage(0)}} />
              <MultiSelectFilter t={t} label={t('admin.resources.resourceType')} options={scopeFilterOptions} selected={viewScopes} onChange={values=>{setViewScopes(values);setAssignmentPage(0)}} />
            </div>
          </div>
        </div>
        <div className={styles.resultSummary}>
          <div><strong>{assignmentTotal}</strong><span>{assignmentTotal === 1 ? t('admin.resources.matchingAssignmentOne') : t('admin.resources.matchingAssignments')}</span><small>{t('admin.resources.shownOnPage', { count: visibleAssignments.length })}</small></div>
          <div className={styles.resultBreakdown}>
            <span><b>{Number(assignmentFacets.status?.PUBLISHED || 0)}</b> Published</span>
            <span><b>{Number(assignmentFacets.status?.DRAFT || 0)}</b> Drafts</span>
            <span><b>{Number(assignmentFacets.status?.ARCHIVED || 0)}</b> Archived</span>
          </div>
          <button type="button" className={styles.resetFilters} disabled={activeFilterCount === 0} onClick={() => {
            setAssignmentPage(0);
            setViewClassCodes([]);
            setViewSubjectCodes([]);
            setViewStatuses([]);
            setViewLanguages([]);
            setViewScopes([]);
          }}>{t('admin.resources.resetFilters')}</button>
        </div>
        {loading ? <p className={styles.empty}>{t('admin.resources.loadingAssignments')}</p> : visibleAssignments.length === 0 ? <div className={styles.empty}><b>{t('admin.resources.noAssignments')}</b><span>{t('admin.resources.adjustFilters')}</span></div> :
          <div className={styles.assignmentList}>
            {visibleAssignments.map(row => <article key={row.document_id} className={styles.assignmentCard}>
              <div className={styles.assignmentIcon}>{row.scope === 'SUBJECT_BOOK' ? '▤' : '▧'}</div>
              <div className={styles.assignmentBody}>
                <div className={styles.assignmentTitle}><h3>{row.title}</h3><span className={styles['status' + row.status]}>{resourceStatusLabel(row.status, t)}</span></div>
                <p>{row.scope === 'SUBJECT_BOOK' ? t('admin.resources.completeSubjectBook') : t('admin.resources.chapterPdf')} · {row.language === 'en' ? t('admin.resources.english') : t('admin.resources.hindi')} · Class {row.class_code} · {row.subject_code} {row.chapter_name ? '· ' + row.chapter_name : ''}</p>
                <small>{row.original_filename} · pages {row.page_start}–{row.page_end} · {row.source_title || t('admin.resources.sourceTitleNotSet')} · {row.edition || t('admin.resources.editionNotSet')}</small>
                <small>{row.source_url || t('admin.resources.sourceUrlNotSet')}</small>
                {row.scope === 'CHAPTER_PDF' && row.chapter_active !== true && <small className={styles.publishHint}>This curriculum chapter is inactive. Reactivate it before publishing its PDF.</small>}
              </div>
              <div className={styles.rowActions}>
                <button type="button" className={styles.secondaryButton} disabled={row.asset_review_status != null && row.asset_review_status !== 'APPROVED'} onClick={() => openPdfPreview(row.document_id)}>{t('admin.resources.preview')}</button>
                {row.status !== 'PUBLISHED' && row.status !== 'ARCHIVED' && canPublish &&
                  <button type="button" className={styles.publishButton}
                    disabled={busy || (row.scope === 'CHAPTER_PDF' && row.chapter_active !== true)}
                    title={row.scope === 'CHAPTER_PDF' && row.chapter_active !== true ? t('admin.resources.reactivateChapter') : t('admin.resources.publishResource')}
                    onClick={() => void setStatus(row, 'PUBLISHED')}>{t('admin.resources.publish')}</button>}
                {row.status === 'PUBLISHED' && canPublish && <button type="button" className={styles.archiveButton} disabled={busy} onClick={() => void setStatus(row, 'ARCHIVED')}>{t('admin.resources.archive')}</button>}
                {row.status === 'ARCHIVED' && canEdit && <button type="button" className={styles.publishButton} disabled={busy} onClick={() => void setStatus(row, 'DRAFT')}>{t('admin.resources.restoreDraft')}</button>}
                {row.status === 'DRAFT' && !canPublish && <small className={styles.roleHint}>{t('admin.resources.awaitingPublisher')}</small>}
              </div>
            </article>)}
          </div>}
        <PremiumPagination page={assignmentPage} total={assignmentTotal} pageSize={assignmentPageSize}
          onPageChange={setAssignmentPage} onPageSizeChange={size => { setAssignmentPageSize(size); setAssignmentPage(0); }}
          itemLabel={t('admin.resources.assignments')} loading={loading} />
      </section>

      {previewAssignment && <div className={styles.previewBackdrop} role="presentation" onMouseDown={event => {
        if (event.target === event.currentTarget) closePdfPreview();
      }}>
        <section ref={node => { previewDialogRef.current = node; }} className={styles.previewDialog} role="dialog" aria-modal="true" aria-labelledby="qe-pdf-preview-title" tabIndex={-1}>
          <div className={styles.previewHeading}>
            <div className={styles.previewTitleCopy}><span>{t('admin.resources.previewReadonly')}</span><h2 id="qe-pdf-preview-title">{previewAssignment.title}</h2>
              <p>{previewAssignment.scope === 'SUBJECT_BOOK' ? t('admin.resources.completeSubjectBook') : (previewAssignment.chapter_name || t('admin.resources.chapterPdf'))} · {previewAssignment.language === 'en' ? t('admin.resources.english') : t('admin.resources.hindi')} · {t('admin.resources.pageCount', { count: previewPageCount })}</p>
            </div>
            <button type="button" className={styles.secondaryButton} onClick={closePdfPreview}>{t('admin.resources.backToAssignments')}</button>
          </div>

          <div className={styles.previewReaderToolbar} role="toolbar" aria-label={t('admin.resources.readerControls')}>
            <div className={styles.previewPageControls}>
              <button type="button" className={styles.previewIconButton} aria-label={t('admin.resources.previousPage')} title={t('admin.resources.previousPage')} disabled={previewPage <= 1} onClick={() => changePreviewPage(previewPage - 1)}>‹</button>
              <label>{t('admin.resources.page')} <input type="number" min="1" max={previewPageCount} value={previewPageDraft} onChange={event => setPreviewPageDraft(event.target.value)} onBlur={() => changePreviewPage(Number(previewPageDraft))} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); changePreviewPage(Number(previewPageDraft)); } }} /><span>{t('admin.resources.of', { count: previewPageCount })}</span></label>
              <button type="button" className={styles.previewIconButton} aria-label={t('admin.resources.nextPage')} title={t('admin.resources.nextPage')} disabled={previewPage >= previewPageCount} onClick={() => changePreviewPage(previewPage + 1)}>›</button>
            </div>
            <span className={styles.previewToolbarDivider} aria-hidden="true"/>
            <div className={styles.previewZoomControls}>
              <button type="button" className={styles.previewIconButton} aria-label={t('admin.resources.zoomOut')} title={t('admin.resources.zoomOut')} disabled={previewZoom <= 60} onClick={() => {setPreviewFitPage(false);setPreviewFitWidth(false);setPreviewZoom(value => Math.max(60,value-10));}}>−</button>
              <span>{previewFitPage?t('admin.resources.fitPage'):previewFitWidth?t('admin.resources.fitWidth'):previewZoom+'%'}</span>
              <button type="button" className={styles.previewIconButton} aria-label={t('admin.resources.zoomIn')} title={t('admin.resources.zoomIn')} disabled={previewZoom >= 160} onClick={() => {setPreviewFitPage(false);setPreviewFitWidth(false);setPreviewZoom(value => Math.min(160,value+10));}}>＋</button>
            </div>
            <button type="button" className={styles.previewToolButton+(previewFitPage?' '+styles.isSelected:'')} onClick={() => {setPreviewFitPage(true);setPreviewFitWidth(false);}}>{t('admin.resources.fitPage')}</button>
            <button type="button" className={styles.previewToolButton+(previewFitWidth?' '+styles.isSelected:'')} onClick={() => {setPreviewFitPage(false);setPreviewFitWidth(value => !value);}}>{previewFitWidth?t('admin.resources.actualSize'):t('admin.resources.fitWidth')}</button>
            <button type="button" className={styles.previewToolButton+(previewThumbnailsOpen?' '+styles.isSelected:'')} onClick={() => setPreviewThumbnailsOpen(value => !value)}>▤ {t('admin.resources.pages')}</button>
            <button type="button" className={styles.previewToolButton+' '+styles.previewFullscreenButton} onClick={() => void togglePreviewFullscreen()}>{previewFullscreen?t('admin.resources.exitFullscreen'):t('admin.resources.fullscreen')}</button>
          </div>

          <div className={styles.previewReaderWorkspace+(previewThumbnailsOpen?' '+styles.withThumbnails:'')}>
            {previewThumbnailsOpen && <aside className={styles.previewThumbnails} aria-label={t('admin.resources.pageThumbnails')}>
              <div className={styles.previewThumbsHeader}><b>{t('admin.resources.pages')}</b><small>{t('admin.resources.totalPages', { count: previewPageCount })}</small></div>
              <div className={styles.previewThumbList}>{previewThumbnails.map(pageNumber=><button type="button" key={pageNumber} className={pageNumber===previewPage?styles.activeThumb:''} onClick={() => changePreviewPage(pageNumber)}>
                <img src={'/api/v1/admin/learning-documents/'+previewDocumentId+'/pages/'+pageNumber} alt="" loading="lazy" draggable={false}/><span>{t('admin.resources.page')} {pageNumber}</span>
              </button>)}</div>
              <div className={styles.previewThumbsFooter}>{t('admin.resources.showingPages', { start: previewThumbnails[0] || 0, end: previewThumbnails[previewThumbnails.length-1] || 0 })}</div>
            </aside>}
            <div className={styles.previewPageStage}>
              <div className={styles.previewCanvasTop}><span><i/> {t('admin.resources.securedPreview')}</span><span>{t('admin.resources.page')} {previewPage} / {previewPageCount}</span></div>
              <div className={styles.previewPageViewport+(previewFitWidth?' '+styles.fitWidth:'')+(previewFitPage?' '+styles.fitPage:'')} key={previewDocumentId+'-'+previewPage}>
                {previewImageLoading&&<div className={styles.previewImageLoading}><span className={styles.previewSpinner}/> {t('admin.resources.renderingPage', { page: previewPage })}</div>}
                <img ref={previewPageImageRef} className={styles.previewPageImage} src={previewPageUrl} alt={t('admin.resources.previewImageAlt', { page: previewPage, title: previewPageTitle })} draggable={false} onLoad={() => {setPreviewImageLoading(false);setPreviewImageError('');}} onError={() => {setPreviewImageLoading(false);setPreviewImageError(t('admin.resources.renderFailed'));}} style={{width:previewFitPage?'auto':previewFitWidth?'100%':previewZoom+'%'}}/>
                {previewImageError&&<div className={styles.previewPageError} role="alert"><span>{previewImageError}</span><button type="button" className={styles.secondaryButton} onClick={() => {setPreviewImageError('');setPreviewImageLoading(true);if(previewPageImageRef.current)previewPageImageRef.current.src=previewPageUrl+'?retry='+Date.now();}}>{t('admin.resources.retryPage')}</button></div>}
              </div>
              <div className={styles.previewCanvasBottom}><span>{t('admin.resources.privatePagePreview', { page: previewPage })}</span><span>{t('admin.resources.keyboardHelp')}</span></div>
            </div>
          </div>
          <div className={styles.previewDialogFooter}><span>{t('admin.resources.securePageRendering')}</span><span>{t('admin.resources.originalBytesHidden')}</span></div>
        </section>
      </div>}

      {canEdit && <section className={styles.panel + " " + styles.workflowStep}>
        <div className={styles.sectionHeading}><div><span>{t('admin.resources.stepOne')}</span><h2>{t('admin.resources.chooseUpload')}</h2><p>{t('admin.resources.duplicateDetection')}</p></div><span className={styles.stepIcon}>01</span></div>
        <div className={styles.libraryGrid}>
          <div className={styles.field}>
            <label htmlFor="qe-existing-pdf-search">{t('admin.resources.existingPdfLibrary')}</label>
            <input id="qe-existing-pdf-search" value={libraryQuery} onChange={e => {setLibraryQuery(e.target.value);setLibraryPage(0)}} placeholder={t('admin.resources.searchPdfPlaceholder')} aria-label={t('admin.resources.searchStoredPdf')} />
            <select aria-label={t('admin.resources.selectStoredPdf')} value={selectedPdfId} onChange={e => {
              setSelectedPdfId(e.target.value);
              const asset = library.find(item => Number(item.pdf_asset_id || item.id) === Number(e.target.value));
              if (asset) { setTitle(asset.title); setPageStart('1'); setPageEnd(String(asset.page_count)); }
            }}>
              <option value="">{t('admin.resources.selectStoredPdfOption')}</option>
              {selectedPdfId && selectedPdf && !library.some(pdf => Number(pdf.pdf_asset_id || pdf.id) === Number(selectedPdfId)) &&
                <option value={selectedPdfId}>{t('admin.resources.selectedPdfOption', { title: selectedPdf.title, count: selectedPdf.page_count })}</option>}
              {library.map(pdf => <option key={pdf.pdf_asset_id || pdf.id} value={pdf.pdf_asset_id || pdf.id}>
                {pdf.title} · {t('admin.resources.pageCount', { count: pdf.page_count })} · {formatBytes(pdf.file_size_bytes)}{contentCoverageWarning(pdf.source_reference, t) ? ' · ' + t('admin.resources.partialFlag') : ''}
              </option>)}
            </select>
            <small>{libraryTotal ? t('admin.resources.libraryRange', { start: libraryTotal===0?0:libraryPage*libraryPageSize+1, end: Math.min(libraryTotal,(libraryPage+1)*libraryPageSize), total: libraryTotal }) : t('admin.resources.noPdfMatches')}</small>
            <PremiumPagination page={libraryPage} total={libraryTotal} pageSize={libraryPageSize}
              onPageChange={setLibraryPage} onPageSizeChange={size => { setLibraryPageSize(size); setLibraryPage(0); }}
              itemLabel={t('admin.resources.storedPdfs')} loading={loading} />
          </div>
          {canReview && <section className={styles.uploadBox}>
            <div className={styles.uploadTitle}><b>{t('admin.resources.bookIntakeQueue')}</b><small>{t('admin.resources.classifiedPdfCount', { count: intakeTotal })}</small></div>
            {intakeRows.length === 0 ? <p>{t('admin.resources.noClassifiedBooks')}</p> :
              <div className={styles.assignmentList}>
                {intakeRows.map((item: any) => <div className={styles.assignmentQueueRow} key={item.pdf_asset_id}>
                  <div>
                    <b>{item.title || ('PDF #' + item.pdf_asset_id)}</b>
                    <small>{t('admin.resources.classLine', { classCode: item.class_code })} · {item.subject_name || item.subject_code} · {item.language === 'hi' ? t('admin.resources.hindi') : t('admin.resources.english')} · {resourceCategoryLabel(item.category, t)}{item.part_label ? ' · ' + item.part_label : ''}</small>
                    <small>{t('admin.resources.pageCount', { count: item.page_count })} · {resourceStatusLabel(item.status || 'PENDING', t)} · {item.asset_review_status ? resourceStatusLabel(item.asset_review_status, t) : t('admin.resources.privateLibraryStatus')}</small>
                  </div>
                  <button type="button" className={styles.secondaryButton} disabled={busy} onClick={() => {
                    setIntakeAssetId(Number(item.pdf_asset_id));
                    setIntakeReport(normalizeIntakeReport(item));
                    setUploadCategory(String(item.category || 'MAIN_TEXTBOOK'));
                    setUploadClassCode(String(item.class_code || '6'));
                    setUploadSubjectCode(String(item.subject_code || 'maths'));
                    setUploadLanguage(item.language === 'en' ? 'en' : 'hi');
                    setUploadPartLabel(String(item.part_label || ''));
                    setClassCode(String(item.class_code || '6'));
                    setSubjectCode(String(item.subject_code || 'maths'));
                    setResourceLanguage(item.language === 'en' ? 'en' : 'hi');
                    setSelectedPdfId(String(item.pdf_asset_id));
                    setScope('CHAPTER_PDF');
                    setRangeReviewNotes(String(item.ranges_review_notes || ''));
                    setRangeDraftSourceTitle('');
                    setRangeDraftSourceUrl('');
                    setRangeDraftEdition('');
                    setReviewAcknowledged(false);
                    setReviewNotes(String(item.review_notes || ''));
                    setError('');
                    setNotice(t('admin.resources.loadedIntake'));
                  }}>{t('admin.resources.openReview')}</button>
                </div>)}
              </div>}
            <PremiumPagination page={intakePage} total={intakeTotal} pageSize={intakePageSize}
              onPageChange={setIntakePage} onPageSizeChange={size => { setIntakePageSize(size); setIntakePage(0); }}
              itemLabel={t('admin.resources.intakeRecords')} loading={loading} />
          </section>}
          {intakeAssetId && <section className={styles.uploadBox} aria-live="polite">
            <div className={styles.uploadTitle}>
              <b>{t('admin.resources.intakeReviewTitle', { assetId: intakeAssetId })}</b>
              <button type="button" className={styles.secondaryButton} disabled={busy} onClick={async () => {
                setBusy(true); setError(''); setNotice('');
                try {
                  const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/analyze', { method: 'POST' });
                  setIntakeReport(normalizeIntakeReport(result));
                  const report = result.reconciliation || {};
                  setNotice(t('admin.resources.inventoryScanComplete', { missingCount: report.missing_from_book?.length || 0, extraCount: report.extra_in_book?.length || 0, ambiguousCount: report.ambiguous?.length || 0 }));
                } catch (e) {
                  setError(localizedError(e, t, 'admin.resources.inventoryScanFailed'));
                } finally { setBusy(false); }
              }}>{t('admin.resources.rerunAnalysis')}</button>
              <button type="button" className={styles.secondaryButton} disabled={busy} onClick={async () => {
                setBusy(true); setError(''); setNotice('');
                try {
                  const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/analyze-ocr', { method: 'POST' });
                  const normalized = normalizeIntakeReport(result);
                  setIntakeReport(normalized);
                  await load();
                  const warnings = result.reconciliation?.page_boundary_warnings || [];
                  const partial = warnings.some((warning: any) => warning.type === 'OCR_PARTIAL' || warning.type === 'OCR_UNAVAILABLE');
                  setNotice((partial ? t('admin.resources.ocrPartialNotice') : t('admin.resources.ocrCompleteNotice')) + t('admin.resources.ocrCandidateNotice'));
                } catch (e) {
                  setError(localizedError(e, t, 'admin.resources.ocrAnalysisFailed'));
                } finally { setBusy(false); }
              }}>{t('admin.resources.runOcrAnalysis')}</button>
            </div>
            <p>{t('admin.resources.correctBookTags')}</p>
            <button type="button" className={styles.secondaryButton} disabled={busy} onClick={async () => {
              setBusy(true); setError(''); setNotice('');
              try {
                await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId, {
                  method: 'PUT',
                  body: JSON.stringify({
                    category: uploadCategory, classCode: uploadClassCode,
                    subjectCode: uploadSubjectCode, language: uploadLanguage,
                    partLabel: uploadPartLabel.trim() || null, reviewNotes: reviewNotes.trim() || null,
                  }),
                });
                const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/analyze', { method: 'POST' });
                setIntakeReport(normalizeIntakeReport(result));
                await load();
                setNotice(t('admin.resources.classificationScanSaved'));
              } catch (e) {
                setError(localizedError(e, t, 'admin.resources.classificationSaveFailed'));
              } finally { setBusy(false); }
            }}>{t('admin.resources.saveRerunClassification')}</button>
            {intakeReport ? <>
              <p><b>{t('admin.resources.statusLabel')}</b> {resourceStatusLabel(intakeReport.status || 'NEEDS_REVIEW', t)}</p>
              <p>{t('admin.resources.reviewSignals')}</p>
              <div className={styles.assignmentFields}>
                <div><b>{t('admin.resources.curriculumTitlesMissing')}</b><p>{(intakeReport.reconciliation?.missing_from_book || []).length}</p></div>
                <div><b>{t('admin.resources.unmatchedHeadings')}</b><p>{(intakeReport.reconciliation?.extra_in_book || []).length}</p></div>
                <div><b>{t('admin.resources.ambiguousMatches')}</b><p>{(intakeReport.reconciliation?.ambiguous || []).length}</p></div>
                <div><b>{t('admin.resources.pageExtractionWarnings')}</b><p>{(intakeReport.reconciliation?.page_boundary_warnings || []).length}</p></div>
              </div>
              {((intakeReport.reconciliation?.page_boundary_warnings || []).some((item: any) => item.type === 'OCR_REQUIRED')) && <div className={styles.publishHint}>
                <b>{t('admin.resources.ocrRequired')}</b>
                {(() => {
                  const warning = intakeReport.reconciliation.page_boundary_warnings.find((item: any) => item.type === 'OCR_REQUIRED');
                  return <p>{warning.message} {t('admin.resources.lowTextPages', { lowText: warning.low_text_page_count, total: warning.total_pages, pages: (warning.low_text_pages || []).join(', '), truncated: warning.page_list_truncated ? t('admin.resources.truncatedList') : '' })}</p>;
                })()}
              </div>}
              {(intakeReport.reconciliation?.page_boundary_warnings || [])
                .filter((item: any) => ['OCR_COMPLETED', 'OCR_PARTIAL', 'OCR_UNAVAILABLE'].includes(item.type))
                .map((warning: any, index: number) => (
                  <div className={styles.publishHint} key={'ocr-result-' + index}>
                    <b>{warning.type === 'OCR_COMPLETED' ? t('admin.resources.ocrCompletedWarning')
                      : warning.type === 'OCR_PARTIAL' ? t('admin.resources.ocrPartialWarning')
                      : t('admin.resources.ocrUnavailableWarning')}</b>
                    <p>{warning.message}</p>
                    {warning.attempted_pages != null && <p>
                      {t('admin.resources.pagesAttempted', { attempted: warning.attempted_pages, recognized: warning.recognized_pages || 0 })}
                      {warning.remaining_unprocessed_pages > 0 ? t('admin.resources.lowTextNotProcessed', { count: warning.remaining_unprocessed_pages }) : ''}
                    </p>}
                    {(warning.sample_problem_pages || []).length > 0 && <p>
                      {t('admin.resources.samplePagesManual', { pages: warning.sample_problem_pages.join(', ') })}
                    </p>}
                  </div>
                ))}
              {((intakeReport.reconciliation?.missing_from_book || []).length > 0) && <div>
                <b>{t('admin.resources.titlesToVerify')}</b>
                <ul>{intakeReport.reconciliation.missing_from_book.slice(0, 30).map((item: any, index: number) =>
                  <li key={'missing-' + index}>{item.title} — {item.reason}</li>)}</ul>
              </div>}
              {((intakeReport.reconciliation?.extra_in_book || []).length > 0) && <div>
                <b>{t('admin.resources.unmatchedHeadingsToVerify')}</b>
                <ul>{intakeReport.reconciliation.extra_in_book.slice(0, 30).map((item: any, index: number) =>
                  <li key={'extra-' + index}>{t('admin.resources.pagePrefix', { page: item.page, title: item.title })}</li>)}</ul>
              </div>}
              {((intakeReport.reconciliation?.ambiguous || []).length > 0) && <div>
                <b>Ambiguous matches</b>
                <ul>{intakeReport.reconciliation.ambiguous.slice(0, 20).map((item: any, index: number) =>
                  <li key={'ambiguous-' + index}>{item.title} — {t('admin.resources.pagesList', { pages: (item.candidate_pages || []).join(', ') })}</li>)}</ul>
              </div>}
              {intakeReport.status !== 'REVIEWED' && <div className={styles.uploadBox}>
                <b>{t('admin.resources.finishHumanReview')}</b>
                <p>{t('admin.resources.humanReviewHelp')}</p>
                <label className={styles.field}>{t('admin.resources.reviewNote')}
                  <textarea value={reviewNotes} onChange={e => setReviewNotes(e.target.value)}
                    placeholder={t('admin.resources.reviewNotePlaceholder')}
                    maxLength={2000} rows={3} />
                </label>
                <label className={styles.field}>
                  <input type="checkbox" checked={reviewAcknowledged}
                    onChange={e => setReviewAcknowledged(e.target.checked)} />
                  {t('admin.resources.reviewAcknowledgement')}
                </label>
                <button type="button" className={styles.primaryButton} disabled={busy || !reviewAcknowledged}
                  onClick={async () => {
                    setBusy(true); setError(''); setNotice('');
                    try {
                      const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/review', {
                        method: 'PATCH',
                        body: JSON.stringify({ acknowledgeDiscrepancies: reviewAcknowledged, reviewNotes: reviewNotes.trim() || null }),
                      });
                      setIntakeReport(normalizeIntakeReport({ ...(intakeReport || {}), ...result }));
                      setNotice(t('admin.resources.humanReviewRecorded'));
                    } catch (e) {
                      setError(localizedError(e, t, 'admin.resources.reviewFailed'));
                    } finally { setBusy(false); }
                  }}>{t('admin.resources.recordReview')}</button>
              </div>}
              <div className={styles.rangeReview}>
                <div className={styles.uploadTitle}>
                  <b>{t('admin.resources.manualChapterRanges')}</b>
                  <small>{t('admin.resources.rangesEntered', { count: intakeRanges.filter((row: any) => row.pageStart && row.pageEnd).length })}</small>
                </div>
                <p>{t('admin.resources.rangeInstructions')}</p>
                {chapters.length === 0 ? <p>{t('admin.resources.noCurriculumChapters')}</p> :
                  <div className={styles.rangeReviewList}>
                    {intakeRanges.map((range: any) => <div className={styles.rangeReviewRow} key={range.chapterId}>
                      <div className={styles.rangeReviewChapter}>
                        <b>{range.title}</b>
                        <small>Chapter ID {range.chapterId}</small>
                      </div>
                      <label className={styles.field}>{t('admin.resources.pdfStartPage')}
                        <input type="number" min="1" max={Number(intakeRows.find((r: any) => Number(r.pdf_asset_id) === intakeAssetId)?.page_count || 2000)}
                          value={range.pageStart} onChange={e => setIntakeRanges(rows => rows.map((row: any) => row.chapterId === range.chapterId ? {...row,pageStart:e.target.value} : row))} />
                      </label>
                      <label className={styles.field}>{t('admin.resources.pdfEndPage')}
                        <input type="number" min={range.pageStart || 1} max={Number(intakeRows.find((r: any) => Number(r.pdf_asset_id) === intakeAssetId)?.page_count || 2000)}
                          value={range.pageEnd} onChange={e => setIntakeRanges(rows => rows.map((row: any) => row.chapterId === range.chapterId ? {...row,pageEnd:e.target.value} : row))} />
                      </label>
                      <button type="button" className={styles.secondaryButton} disabled={busy || !range.pageStart || !range.pageEnd}
                        onClick={() => {
                          const book = intakeRows.find((r: any) => Number(r.pdf_asset_id) === intakeAssetId);
                          setSelectedPdfId(String(intakeAssetId));
                          setClassCode(String(book?.class_code || uploadClassCode));
                          setSubjectCode(String(book?.subject_code || uploadSubjectCode));
                          setScope('CHAPTER_PDF');
                          setChapterId(String(range.chapterId));
                          setResourceLanguage(book?.language === 'en' ? 'en' : 'hi');
                          setTitle(String(book?.title || 'Textbook') + ' — ' + range.title);
                          setSourceTitle(String(book?.title || ''));
                          setPageStart(String(range.pageStart));
                          setPageEnd(String(range.pageEnd));
                          setNotice(t('admin.resources.draftPlacementPrepared', { title: range.title }));
                        }}>Prepare draft</button>
                    </div>)}
                  </div>}
                <label className={styles.field}>{t('admin.resources.rangeReviewNotes')}
                  <textarea rows={2} value={rangeReviewNotes} onChange={e => setRangeReviewNotes(e.target.value)} placeholder={t('admin.resources.rangeNotesPlaceholder')} />
                </label>
                <button type="button" className={styles.primaryButton} disabled={busy || !intakeRanges.some((row: any) => row.pageStart && row.pageEnd)}
                  onClick={async () => {
                    const chapterRanges = intakeRanges.filter((row: any) => String(row.pageStart).trim() && String(row.pageEnd).trim())
                      .map((row: any) => ({chapterId:row.chapterId,title:row.title,pageStart:Number(row.pageStart),pageEnd:Number(row.pageEnd)}));
                    setBusy(true); setError(''); setNotice('');
                    try {
                      const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/chapter-ranges', {
                        method: 'PATCH', body: JSON.stringify({chapterRanges,notes:rangeReviewNotes.trim() || null}),
                      });
                      const normalized = normalizeIntakeReport({ ...(intakeReport || {}), ...result });
                      setIntakeReport(normalized);
                      setNotice(t('admin.resources.rangesSaved'));
                      await load();
                    } catch (e) {
                      setError(localizedError(e, t, 'admin.resources.chapterRangesSaveFailed'));
                    } finally { setBusy(false); }
                  }}>{t('admin.resources.saveRanges')}</button>
                {intakeReport.ranges_reviewed_at && <small>{t('admin.resources.rangesLastSaved', { date: String(intakeReport.ranges_reviewed_at) })}</small>}
                {canEdit && <div className={styles.uploadBox}>
                  <b>{t('admin.resources.createDraftsFromRanges')}</b>
                  <p>{t('admin.resources.savedRangeRules')}</p>
                  <div className={styles.assignmentFields}>
                    <label className={styles.field}>{t('admin.resources.printedSourceTitle')}
                      <input value={rangeDraftSourceTitle} onChange={e => setRangeDraftSourceTitle(e.target.value)}
                        placeholder={t('admin.resources.textbookTitlePlaceholder')} maxLength={300} />
                    </label>
                    <label className={styles.field}>{t('admin.resources.officialHttpsSourceUrl')}
                      <input type="url" value={rangeDraftSourceUrl} onChange={e => setRangeDraftSourceUrl(e.target.value)}
                        placeholder={t('admin.resources.httpsPlaceholder')} maxLength={2000} />
                    </label>
                    <label className={styles.field}>{t('admin.resources.editionSession')}
                      <input value={rangeDraftEdition} onChange={e => setRangeDraftEdition(e.target.value)}
                        placeholder={t('admin.resources.verifiedEditionPlaceholder')} maxLength={160} />
                    </label>
                  </div>
                  <button type="button" className={styles.primaryButton}
                    disabled={busy || !intakeReport.ranges_reviewed_at
                      || !Array.isArray(intakeReport.chapter_ranges) || intakeReport.chapter_ranges.length === 0
                      || !rangeDraftSourceTitle.trim() || !rangeDraftSourceUrl.trim() || !rangeDraftEdition.trim()}
                    onClick={async () => {
                      setBusy(true); setError(''); setNotice('');
                      try {
                        const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/draft-resources', {
                          method: 'POST',
                          body: JSON.stringify({
                            sourceTitle: rangeDraftSourceTitle.trim(),
                            sourceUrl: rangeDraftSourceUrl.trim(),
                            edition: rangeDraftEdition.trim(),
                          }),
                        });
                        await load();
                        setNotice(t('admin.resources.draftCreationComplete', { createdCount: Number(result.created_count || 0), existingCount: Number(result.existing_count || 0) }));
                      } catch (e) {
                        setError(localizedError(e, t, 'admin.resources.draftResourcesFailed'));
                      } finally { setBusy(false); }
                    }}>{t('admin.resources.createDraftsAllRanges')}</button>
                </div>}
              </div>
              {intakeReport.status === 'REVIEWED' && <p><b>{t('admin.resources.reviewRecordedLabel')}</b> {t('admin.resources.sourcePrivateUnpublished')}</p>}
              <small>{t('admin.resources.originalPdfRetained')}</small>
            </> : <p>{t('admin.resources.classificationOnlySaved')}</p>}
          </section>}
          <div className={styles.uploadBox}>
            <div className={styles.uploadTitle}><b>{t('admin.resources.addPdfLibrary')}</b><small>{t('admin.resources.pdfUploadConstraint', { limit: isAdmin ? '500 MB for Admin' : '50 MB' })}</small></div>
            <label className={styles.field}>{t('admin.resources.libraryTitle')}
              <input value={pdfTitle} onChange={e => setPdfTitle(e.target.value)} placeholder={t('admin.resources.libraryTitlePlaceholder')} />
            </label>
            <div className={styles.assignmentFields}>
              <label className={styles.field}>{t('admin.resources.bookCategory')}
                <select value={uploadCategory} onChange={e => setUploadCategory(e.target.value)}>
                  <option value="MAIN_TEXTBOOK">{t('admin.resources.mainTextbook')}</option>
                  <option value="PART">{t('admin.resources.partVolume')}</option>
                  <option value="SUPPLEMENTARY">{t('admin.resources.supplementary')}</option>
                  <option value="WORKBOOK">{t('admin.resources.workbook')}</option>
                  <option value="TEACHER_GUIDE">{t('admin.resources.teacherGuide')}</option>
                  <option value="REFERENCE">{t('admin.resources.referenceBook')}</option>
                  <option value="OTHER">{t('admin.resources.otherResource')}</option>
                </select>
                <small>{t('admin.resources.categoryGuidance')}</small>
              </label>
              <label className={styles.field}>{t('admin.resources.class')}
                <select value={uploadClassCode} onChange={e => {
                  setUploadClassCode(e.target.value);
                  const first = tracks.find(track => track.class_code === e.target.value);
                  if (first) setUploadSubjectCode(first.subject_code);
                }}>
                  {classOptions.map(track => <option key={track.class_code} value={track.class_code}>{track.class_name}</option>)}
                </select>
              </label>
              <label className={styles.field}>{t('admin.resources.subject')}
                <select value={uploadSubjectCode} onChange={e => setUploadSubjectCode(e.target.value)}>
                  {tracks.filter(track => track.class_code === uploadClassCode).map(track =>
                    <option key={track.subject_code} value={track.subject_code}>{track.subject_name}</option>)}
                </select>
              </label>
              <label className={styles.field}>{t('admin.resources.language')}
                <select value={uploadLanguage} onChange={e => setUploadLanguage(e.target.value as 'hi' | 'en')}>
                  <option value="hi">{t('admin.resources.hindi')}</option>
                  <option value="en">{t('admin.resources.english')}</option>
                </select>
              </label>
              <label className={styles.field}>{t('admin.resources.partLabelOptional')}
                <input value={uploadPartLabel} onChange={e => setUploadPartLabel(e.target.value)}
                  placeholder={t('admin.resources.partLabelPlaceholder')} maxLength={40} />
                <small>{t('admin.resources.partLabelHelp')}</small>
              </label>
            </div>
            <label className={styles.field}>{t('admin.resources.sourceReferenceOptional')}
              <input value={sourceReference} onChange={e => setSourceReference(e.target.value)} placeholder={t('admin.resources.sourceReferencePlaceholder')} maxLength={220} />
            </label>
            <label className={styles.fileInput} htmlFor="qe-pdf-file"><span>{t('admin.resources.choosePdfFile')}</span><input id="qe-pdf-file" type="file" accept="application/pdf,.pdf" onChange={e => {
              const nextFile = e.target.files?.[0] || null;
              setFile(nextFile);
              if (nextFile && nextFile.size > maxPdfBytes) {
                setError(isAdmin
                  ? t('admin.resources.adminFileTooLarge')
                  : t('admin.resources.staffFileTooLarge'));
              } else {
                setError('');
              }
            }} /><small>{file ? file.name + ' · ' + formatBytes(file.size) : t('admin.resources.noFileSelected')}</small></label>
            <button type="button" className={styles.primaryButton} disabled={busy || !file || !pdfTitle.trim()} onClick={() => void uploadPdf()}>{busy ? t('admin.resources.processing') : t('admin.resources.validateSavePdf')}</button>
          </div>
        </div>
        {selectedPdf && <div className={styles.selectedSummary}>
          <span className={styles.pdfBadge}>PDF</span><div><b>{selectedPdf.title}</b><small>{selectedPdf.original_filename} · {selectedPdf.page_count} pages · {formatBytes(selectedPdf.file_size_bytes)} · {selectedPdf.source_kind.replaceAll('_',' ')}</small>
            {contentCoverageWarning(selectedPdf.source_reference, t) && <small className={styles.coverageWarning}>{contentCoverageWarning(selectedPdf.source_reference, t)}</small>}
            {selectedPdf.source_reference && <small>{t('admin.resources.sourceReference')}: {selectedPdf.source_reference}</small>}
          </div><span className={styles.hash}>{selectedPdf.sha256.slice(0, 12)}…</span>
        </div>}
      </section>}

      {canEdit && <section className={styles.panel + " " + styles.workflowStep}>
        <div className={styles.sectionHeading}><div><span>{t('admin.resources.stepTwo')}</span><h2>{t('admin.resources.attachHeading')}</h2><p>{t('admin.resources.attachDescription')}</p></div><span className={styles.stepIcon}>02</span></div>
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
          <label className={styles.field}>{t('admin.resources.placement')}
            <select value={scope} onChange={e => setScope(e.target.value as 'SUBJECT_BOOK' | 'CHAPTER_PDF')}>
              <option value="CHAPTER_PDF">{t('admin.resources.chapterPdf')}</option><option value="SUBJECT_BOOK">{t('admin.resources.completeSubjectBook')}</option>
            </select>
          </label>
          <label className={styles.field}>{t('admin.resources.contentMedium')}
            <select value={resourceLanguage} onChange={e => setResourceLanguage(e.target.value as 'hi' | 'en')}>
              <option value="hi">{t('admin.resources.hindi')}</option><option value="en">{t('admin.resources.english')}</option>
            </select>
          </label>
          {scope === 'CHAPTER_PDF' && <label className={styles.field}>{t('admin.resources.chapter')}
            <select value={chapterId} onChange={e => setChapterId(e.target.value)}>
              <option value="">{t('admin.resources.selectChapterOption')}</option>
              {chapters.map(ch => <option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}
            </select>
          </label>}
          <label className={styles.field}>{t('admin.resources.studentFacingTitle')}
            <input value={title} onChange={e => setTitle(e.target.value)} placeholder={selectedPdf?.title || t('admin.resources.studentTitlePlaceholder')} />
          </label>
          <label className={styles.field}>{t('admin.resources.officialSourceTitle')}
            <input value={sourceTitle} onChange={e => setSourceTitle(e.target.value)} placeholder={t('admin.resources.sourceTitlePlaceholder')} />
          </label>
          <label className={styles.field}>{t('admin.resources.officialSourceUrl')}
            <input type="url" value={sourceUrl} onChange={e => setSourceUrl(e.target.value)} placeholder={t('admin.resources.sourceUrlPlaceholder')} />
          </label>
          <label className={styles.field}>{t('admin.resources.editionSession')}
            <input value={edition} onChange={e => setEdition(e.target.value)} placeholder={t('admin.resources.editionPlaceholder')} />
          </label>
          <label className={styles.field}>PDF start page
            <input type="number" min="1" value={pageStart} onChange={e => setPageStart(e.target.value)} />
          </label>
          <label className={styles.field}>PDF end page
            <input type="number" min={pageStart || 1} max={selectedPdf?.page_count || 2000} value={pageEnd} placeholder={String(selectedPdf?.page_count || '')} onChange={e => setPageEnd(e.target.value)} />
          </label>
        </div>
        <div className={styles.attachFooter}>
          <p>{scope === 'SUBJECT_BOOK' ? t('admin.resources.attachCompleteHelp') : t('admin.resources.attachChapterHelp')} {t('admin.resources.languageDeliveredNote', { language: resourceLanguage === 'hi' ? t('admin.resources.hindi') : t('admin.resources.english') })}</p>
          <button type="button" className={styles.primaryButton} disabled={busy || !selectedPdf || (scope === 'CHAPTER_PDF' && !chapterId) || !title.trim()} onClick={() => void attachPdf()}>{busy ? t('admin.resources.saving') : t('admin.resources.attachAsDraft')}</button>
        </div>
      </section>}


    </section>
  </main>;
}
