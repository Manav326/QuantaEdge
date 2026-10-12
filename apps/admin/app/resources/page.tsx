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
}: {
  label: string;
  options: FilterOption[];
  selected: string[];
  onChange: (values: string[]) => void;
}) {
  return (
    <details className={styles.multiFilter}>
      <summary className={styles.filterTrigger}>
        <span>{label}</span>
        <strong>{selected.length === 0 ? 'All' : selected.length + ' selected'}</strong>
        <span className={styles.filterChevron} aria-hidden="true">▾</span>
      </summary>
      <div className={styles.filterMenu}>
        <div className={styles.filterMenuActions}>
          <button type="button" onClick={() => onChange(options.map(option => option.value))}>Select all</button>
          <button type="button" onClick={() => onChange([])}>Clear filter</button>
        </div>
        {options.length === 0
          ? <p className={styles.filterEmpty}>No options available.</p>
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
      </div>
    </details>
  );
}

export default function TextbookLibraryPage() {
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
  const libraryPageSize = 25;
  const [chapters, setChapters] = useState<Chapter[]>([]);
  const [assignments, setAssignments] = useState<Assignment[]>([]);
  const [assignmentPage, setAssignmentPage] = useState(0);
  const [assignmentTotal, setAssignmentTotal] = useState(0);
  const assignmentPageSize = 25;
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
  const intakePageSize = 10;
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
  }), [viewClassCodes, viewSubjectCodes, viewStatuses, viewLanguages, viewScopes]);
  const classFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, classOptions.map(track => ({
      value: track.class_code,
      label: 'Class ' + track.class_code,
    })), 'class'),
    [assignmentFacets, classOptions],
  );
  const subjectFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, subjectFilterBaseOptions, 'subject'),
    [assignmentFacets, subjectFilterBaseOptions],
  );
  const statusFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, [
      { value: 'PUBLISHED', label: 'Published' },
      { value: 'DRAFT', label: 'Draft' },
      { value: 'ARCHIVED', label: 'Archived' },
    ], 'status'),
    [assignmentFacets],
  );
  const languageFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, [
      { value: 'hi', label: 'Hindi' },
      { value: 'en', label: 'English' },
    ], 'language'),
    [assignmentFacets],
  );
  const scopeFilterOptions = useMemo(
    () => addFacetCounts(assignmentFacets, [
      { value: 'CHAPTER_PDF', label: 'Chapter PDFs' },
      { value: 'SUBJECT_BOOK', label: 'Complete books' },
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
      setError(e instanceof Error ? e.message : 'The textbook library could not be loaded.');
    } finally {
      setLoading(false);
    }
  }, [classCode, subjectCode, chapterId, canEdit, canReview, previewDocumentId, assignmentPage, intakePage,
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
      if (active) setError(e instanceof Error ? e.message : 'The stored PDF library could not be loaded.');
    });
    return () => { active = false; };
  }, [canEdit, libraryPage, libraryQuery]);

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
      if (!Number.isSafeInteger(id) || id <= 0) {
        throw new Error('The PDF was uploaded, but the API did not return a valid library ID. Refresh the library before retrying.');
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
        setNotice('PDF saved and classified, but automatic inventory analysis needs attention: ' +
          (analysisError instanceof Error ? analysisError.message : 'unknown error') +
          '. The book remains private and unpublished; use Re-run analysis after fixing the issue.');
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
        setNotice((created.duplicate ? 'Existing PDF reused; no duplicate copy was created. ' : 'PDF saved. ') +
          'Classification saved. Inventory scan found ' + missingCount + ' curriculum title(s) not found, ' +
          extraCount + ' unmatched chapter/appendix heading(s), and ' + ambiguousCount +
          ' ambiguous match(es). Nothing was auto-approved or published; review the reconciliation report below.');
      }
      setFile(null);
      setPdfTitle('');
      setSourceReference('');
      setUploadPartLabel('');
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
        <div className={styles.heroStats}><strong>{libraryTotal}</strong><small>PDF files in library</small><strong>{Number(assignmentFacets.status?.PUBLISHED || 0)}</strong><small>Published in filtered view</small></div>
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
            <input value={libraryQuery} onChange={e => {setLibraryQuery(e.target.value);setLibraryPage(0)}} placeholder="Search PDF title, filename or source…" aria-label="Search stored PDF library" />
            <select value={selectedPdfId} onChange={e => {
              setSelectedPdfId(e.target.value);
              const asset = library.find(item => Number(item.pdf_asset_id || item.id) === Number(e.target.value));
              if (asset) { setTitle(asset.title); setPageStart('1'); setPageEnd(String(asset.page_count)); }
            }}>
              <option value="">Select a stored PDF…</option>
              {selectedPdfId && selectedPdf && !library.some(pdf => Number(pdf.pdf_asset_id || pdf.id) === Number(selectedPdfId)) &&
                <option value={selectedPdfId}>Selected: {selectedPdf.title} · {selectedPdf.page_count} pages</option>}
              {library.map(pdf => <option key={pdf.pdf_asset_id || pdf.id} value={pdf.pdf_asset_id || pdf.id}>
                {pdf.title} · {pdf.page_count} pages · {formatBytes(pdf.file_size_bytes)}{contentCoverageWarning(pdf.source_reference) ? ' · ⚠ PARTIAL' : ''}
              </option>)}
            </select>
            <small>{libraryTotal ? 'Showing ' + (libraryTotal===0?0:libraryPage*libraryPageSize+1) + '–' + Math.min(libraryTotal,(libraryPage+1)*libraryPageSize) + ' of ' + libraryTotal + ' matching stored PDFs.' : 'No PDFs match this search. Upload a source PDF or change the search text.'}</small>
            <div style={{display:'flex',gap:8,alignItems:'center',marginTop:8}}>
              <button type="button" className={styles.secondaryButton} disabled={libraryPage===0} onClick={()=>setLibraryPage(value=>Math.max(0,value-1))}>← Previous</button>
              <span style={{fontSize:12}}>Page {libraryTotal===0?0:libraryPage+1} of {Math.max(1,Math.ceil(libraryTotal/libraryPageSize))}</span>
              <button type="button" className={styles.secondaryButton} disabled={(libraryPage+1)*libraryPageSize>=libraryTotal} onClick={()=>setLibraryPage(value=>value+1)}>Next →</button>
            </div>
          </label>
          {canReview && <section className={styles.uploadBox}>
            <div className={styles.uploadTitle}><b>Book intake queue</b><small>{intakeTotal} classified PDF(s)</small></div>
            {intakeRows.length === 0 ? <p>No classified books yet. Upload a complete PDF above to start.</p> :
              <div className={styles.assignmentList}>
                {intakeRows.map((item: any) => <div className={styles.assignmentQueueRow} key={item.pdf_asset_id}>
                  <div>
                    <b>{item.title || ('PDF #' + item.pdf_asset_id)}</b>
                    <small>Class {item.class_code} · {item.subject_name || item.subject_code} · {item.language === 'hi' ? 'Hindi' : 'English'} · {String(item.category || '').replaceAll('_', ' ')}{item.part_label ? ' · ' + item.part_label : ''}</small>
                    <small>{item.page_count} pages · {String(item.status || 'PENDING').replaceAll('_', ' ')} · {item.asset_review_status || 'private library'}</small>
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
                    setNotice('Loaded the saved intake record. Run or re-run analysis to refresh the inventory report.');
                  }}>Open review</button>
                </div>)}
              </div>}
            <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',gap:10,marginTop:12,flexWrap:'wrap'}}>
              <small>Showing {intakeTotal===0?0:intakePage*intakePageSize+1}–{Math.min(intakeTotal,(intakePage+1)*intakePageSize)} of {intakeTotal}</small>
              <div style={{display:'flex',gap:8,alignItems:'center'}}>
                <button type="button" className={styles.secondaryButton} disabled={intakePage===0||loading} onClick={()=>setIntakePage(value=>Math.max(0,value-1))}>← Previous</button>
                <span>Page {intakeTotal===0?0:intakePage+1} of {Math.max(1,Math.ceil(intakeTotal/intakePageSize))}</span>
                <button type="button" className={styles.secondaryButton} disabled={(intakePage+1)*intakePageSize>=intakeTotal||loading} onClick={()=>setIntakePage(value=>value+1)}>Next →</button>
              </div>
            </div>
          </section>}
          {intakeAssetId && <section className={styles.uploadBox} aria-live="polite">
            <div className={styles.uploadTitle}>
              <b>Book inventory review · PDF #{intakeAssetId}</b>
              <button type="button" className={styles.secondaryButton} disabled={busy} onClick={async () => {
                setBusy(true); setError(''); setNotice('');
                try {
                  const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/analyze', { method: 'POST' });
                  setIntakeReport(normalizeIntakeReport(result));
                  const report = result.reconciliation || {};
                  setNotice('Inventory scan complete. ' +
                    (report.missing_from_book?.length || 0) + ' curriculum title(s) not found; ' +
                    (report.extra_in_book?.length || 0) + ' unmatched heading(s); ' +
                    (report.ambiguous?.length || 0) + ' ambiguous match(es). Nothing was auto-approved or published.');
                } catch (e) {
                  setError(e instanceof Error ? e.message : 'Inventory scan failed.');
                } finally { setBusy(false); }
              }}>Re-run analysis</button>
              <button type="button" className={styles.secondaryButton} disabled={busy} onClick={async () => {
                setBusy(true); setError(''); setNotice('');
                try {
                  const result = await api('/api/v1/admin/source-ingestion/intake/' + intakeAssetId + '/analyze-ocr', { method: 'POST' });
                  const normalized = normalizeIntakeReport(result);
                  setIntakeReport(normalized);
                  await load();
                  const warnings = result.reconciliation?.page_boundary_warnings || [];
                  const partial = warnings.some((warning: any) => warning.type === 'OCR_PARTIAL' || warning.type === 'OCR_UNAVAILABLE');
                  setNotice((partial ? 'OCR-assisted analysis was partial; check the page warnings and review remaining pages manually. '
                    : 'OCR-assisted analysis completed or was not needed. ') +
                    'Recognized text is only a candidate for inventory matching. Nothing was approved or published.');
                } catch (e) {
                  setError(e instanceof Error ? e.message : 'OCR-assisted analysis failed.');
                } finally { setBusy(false); }
              }}>Run OCR-assisted analysis</button>
            </div>
            <p>To correct the selected book's tags, edit the category, class, subject, language or part label in the form above, then save them here.</p>
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
                setNotice('Classification saved and inventory analysis refreshed. Review the new report below; nothing was published.');
              } catch (e) {
                setError(e instanceof Error ? e.message : 'Classification could not be saved.');
              } finally { setBusy(false); }
            }}>Save classification and re-run analysis</button>
            {intakeReport ? <>
              <p><b>Status:</b> {String(intakeReport.status || 'NEEDS_REVIEW').replaceAll('_', ' ')}</p>
              <p>These are review signals, not automatic chapter assignments. Scanned page numbers are candidates only.</p>
              <div className={styles.assignmentFields}>
                <div><b>Curriculum titles not found</b><p>{(intakeReport.reconciliation?.missing_from_book || []).length}</p></div>
                <div><b>Unmatched headings / appendices</b><p>{(intakeReport.reconciliation?.extra_in_book || []).length}</p></div>
                <div><b>Ambiguous matches</b><p>{(intakeReport.reconciliation?.ambiguous || []).length}</p></div>
                <div><b>Page / extraction warnings</b><p>{(intakeReport.reconciliation?.page_boundary_warnings || []).length}</p></div>
              </div>
              {((intakeReport.reconciliation?.page_boundary_warnings || []).some((item: any) => item.type === 'OCR_REQUIRED')) && <div className={styles.publishHint}>
                <b>OCR required before trusting automated inventory</b>
                {(() => {
                  const warning = intakeReport.reconciliation.page_boundary_warnings.find((item: any) => item.type === 'OCR_REQUIRED');
                  return <p>{warning.message} Low-text pages: {warning.low_text_page_count} of {warning.total_pages}. Sample page numbers: {(warning.low_text_pages || []).join(', ')}{warning.page_list_truncated ? ' … (list truncated)' : ''}</p>;
                })()}
              </div>}
              {(intakeReport.reconciliation?.page_boundary_warnings || [])
                .filter((item: any) => ['OCR_COMPLETED', 'OCR_PARTIAL', 'OCR_UNAVAILABLE'].includes(item.type))
                .map((warning: any, index: number) => (
                  <div className={styles.publishHint} key={'ocr-result-' + index}>
                    <b>{warning.type === 'OCR_COMPLETED' ? 'OCR text extracted — manual verification required'
                      : warning.type === 'OCR_PARTIAL' ? 'OCR was partial — additional review required'
                      : 'OCR unavailable — use manual page-range review'}</b>
                    <p>{warning.message}</p>
                    {warning.attempted_pages != null && <p>
                      Pages attempted: {warning.attempted_pages}; pages with recognized text: {warning.recognized_pages || 0}
                      {warning.remaining_unprocessed_pages > 0 ? '; low-text pages not processed: ' + warning.remaining_unprocessed_pages : ''}
                    </p>}
                    {(warning.sample_problem_pages || []).length > 0 && <p>
                      Sample pages requiring manual inspection: {warning.sample_problem_pages.join(', ')}
                    </p>}
                  </div>
                ))}
              {((intakeReport.reconciliation?.missing_from_book || []).length > 0) && <div>
                <b>Titles to verify</b>
                <ul>{intakeReport.reconciliation.missing_from_book.slice(0, 30).map((item: any, index: number) =>
                  <li key={'missing-' + index}>{item.title} — {item.reason}</li>)}</ul>
              </div>}
              {((intakeReport.reconciliation?.extra_in_book || []).length > 0) && <div>
                <b>Unmatched headings to verify</b>
                <ul>{intakeReport.reconciliation.extra_in_book.slice(0, 30).map((item: any, index: number) =>
                  <li key={'extra-' + index}>Page {item.page}: {item.title}</li>)}</ul>
              </div>}
              {((intakeReport.reconciliation?.ambiguous || []).length > 0) && <div>
                <b>Ambiguous matches</b>
                <ul>{intakeReport.reconciliation.ambiguous.slice(0, 20).map((item: any, index: number) =>
                  <li key={'ambiguous-' + index}>{item.title} — pages {(item.candidate_pages || []).join(', ')}</li>)}</ul>
              </div>}
              {intakeReport.status !== 'REVIEWED' && <div className={styles.uploadBox}>
                <b>Finish the human review</b>
                <p>Check the flagged items above against the original book. This records your review only; it does not publish the book or approve chapter page ranges.</p>
                <label className={styles.field}>Review note
                  <textarea value={reviewNotes} onChange={e => setReviewNotes(e.target.value)}
                    placeholder="What did you verify? Explain any accepted differences or why a title may not match."
                    maxLength={2000} rows={3} />
                </label>
                <label className={styles.field}>
                  <input type="checkbox" checked={reviewAcknowledged}
                    onChange={e => setReviewAcknowledged(e.target.checked)} />
                  I reviewed the original PDF and the flagged differences. I understand these are candidates, not automatic chapter mappings.
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
                      setNotice('Human review recorded. The source PDF remains private; no chapter was published.');
                    } catch (e) {
                      setError(e instanceof Error ? e.message : 'Review could not be recorded.');
                    } finally { setBusy(false); }
                  }}>Record review (does not publish)</button>
              </div>}
              <div className={styles.rangeReview}>
                <div className={styles.uploadTitle}>
                  <b>Manual chapter page ranges</b>
                  <small>{intakeRanges.filter((row: any) => row.pageStart && row.pageEnd).length} range(s) entered</small>
                </div>
                <p>Use the page numbers printed by the PDF viewer, not guessed chapter order. Save ranges only after checking the original PDF. Overlapping ranges and chapters from another subject are rejected.</p>
                {chapters.length === 0 ? <p>No curriculum chapters are loaded for this book yet. Check the class and subject selection above, then reopen the book.</p> :
                  <div className={styles.rangeReviewList}>
                    {intakeRanges.map((range: any) => <div className={styles.rangeReviewRow} key={range.chapterId}>
                      <div className={styles.rangeReviewChapter}>
                        <b>{range.title}</b>
                        <small>Chapter ID {range.chapterId}</small>
                      </div>
                      <label className={styles.field}>PDF start page
                        <input type="number" min="1" max={Number(intakeRows.find((r: any) => Number(r.pdf_asset_id) === intakeAssetId)?.page_count || 2000)}
                          value={range.pageStart} onChange={e => setIntakeRanges(rows => rows.map((row: any) => row.chapterId === range.chapterId ? {...row,pageStart:e.target.value} : row))} />
                      </label>
                      <label className={styles.field}>PDF end page
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
                          setNotice('Draft placement prepared for ' + range.title + '. Verify the official source URL and edition below, then attach it as a draft. Nothing is published.');
                        }}>Prepare draft</button>
                    </div>)}
                  </div>}
                <label className={styles.field}>Range review notes
                  <textarea rows={2} value={rangeReviewNotes} onChange={e => setRangeReviewNotes(e.target.value)} placeholder="Optional notes about page numbering, contents pages, or uncertain boundaries." />
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
                      setNotice('Manual chapter ranges saved. They remain private review metadata; use Prepare draft for a chapter, then verify source metadata before attaching.');
                      await load();
                    } catch (e) {
                      setError(e instanceof Error ? e.message : 'Chapter ranges could not be saved.');
                    } finally { setBusy(false); }
                  }}>Save reviewed page ranges</button>
                {intakeReport.ranges_reviewed_at && <small>Ranges last saved for review: {String(intakeReport.ranges_reviewed_at)}. Saving ranges does not publish or approve student visibility.</small>}
                {canEdit && <div className={styles.uploadBox}>
                  <b>Create private draft resources from these saved ranges</b>
                  <p>This uses only saved ranges. Confirm the printed source title, official HTTPS URL, and edition/session. Existing identical drafts are reused; conflicts with different page bounds are rejected.</p>
                  <div className={styles.assignmentFields}>
                    <label className={styles.field}>Printed source title
                      <input value={rangeDraftSourceTitle} onChange={e => setRangeDraftSourceTitle(e.target.value)}
                        placeholder="Title printed on the textbook" maxLength={300} />
                    </label>
                    <label className={styles.field}>Official HTTPS source URL
                      <input type="url" value={rangeDraftSourceUrl} onChange={e => setRangeDraftSourceUrl(e.target.value)}
                        placeholder="https://..." maxLength={2000} />
                    </label>
                    <label className={styles.field}>Edition / academic session
                      <input value={rangeDraftEdition} onChange={e => setRangeDraftEdition(e.target.value)}
                        placeholder="Verified edition or session" maxLength={160} />
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
                        setNotice('Draft creation complete: ' + Number(result.created_count || 0) + ' new chapter resource draft(s); '
                          + Number(result.existing_count || 0) + ' identical active resource(s) reused. Nothing was published.');
                      } catch (e) {
                        setError(e instanceof Error ? e.message : 'Draft resources could not be created.');
                      } finally { setBusy(false); }
                    }}>Create drafts for all saved ranges</button>
                </div>}
              </div>
              {intakeReport.status === 'REVIEWED' && <p><b>Human review recorded.</b> The source remains private and unpublished.</p>}
              <small>Original PDF is retained. Chapter page ranges are not guessed, approved, or published by this scan.</small>
            </> : <p>Classification has been saved. Run the inventory scan to compare the PDF against the selected curriculum.</p>}
          </section>}
          <div className={styles.uploadBox}>
            <div className={styles.uploadTitle}><b>Add a PDF to the library</b><small>PDF only · maximum {isAdmin ? '500 MB for Admin' : '50 MB'} · up to 2,000 pages</small></div>
            <label className={styles.field}>Library title
              <input value={pdfTitle} onChange={e => setPdfTitle(e.target.value)} placeholder="e.g. Bihar Board Class 7 Mathematics — Part II" />
            </label>
            <div className={styles.assignmentFields}>
              <label className={styles.field}>Book category
                <select value={uploadCategory} onChange={e => setUploadCategory(e.target.value)}>
                  <option value="MAIN_TEXTBOOK">Main textbook</option>
                  <option value="PART">Part / volume of a textbook</option>
                  <option value="SUPPLEMENTARY">Supplementary book</option>
                  <option value="WORKBOOK">Workbook / practice book</option>
                  <option value="TEACHER_GUIDE">Teacher guide / handbook</option>
                  <option value="REFERENCE">Reference book</option>
                  <option value="OTHER">Other learning resource</option>
                </select>
                <small>This describes the uploaded source PDF; it does not publish or assign it to students.</small>
              </label>
              <label className={styles.field}>Class
                <select value={uploadClassCode} onChange={e => {
                  setUploadClassCode(e.target.value);
                  const first = tracks.find(track => track.class_code === e.target.value);
                  if (first) setUploadSubjectCode(first.subject_code);
                }}>
                  {classOptions.map(track => <option key={track.class_code} value={track.class_code}>{track.class_name}</option>)}
                </select>
              </label>
              <label className={styles.field}>Subject
                <select value={uploadSubjectCode} onChange={e => setUploadSubjectCode(e.target.value)}>
                  {tracks.filter(track => track.class_code === uploadClassCode).map(track =>
                    <option key={track.subject_code} value={track.subject_code}>{track.subject_name}</option>)}
                </select>
              </label>
              <label className={styles.field}>Book language
                <select value={uploadLanguage} onChange={e => setUploadLanguage(e.target.value as 'hi' | 'en')}>
                  <option value="hi">Hindi</option>
                  <option value="en">English</option>
                </select>
              </label>
              <label className={styles.field}>Part / volume label (optional)
                <input value={uploadPartLabel} onChange={e => setUploadPartLabel(e.target.value)}
                  placeholder="e.g. Part II, Volume 2, Supplement A" maxLength={40} />
                <small>Use the label printed on the book. Leave blank for a standalone book.</small>
              </label>
            </div>
            <label className={styles.field}>Extra source reference (optional)
              <input value={sourceReference} onChange={e => setSourceReference(e.target.value)} placeholder="Publisher catalogue ID, extraction bundle ID…" maxLength={220} />
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
        <div className={styles.sectionHeading}>
          <div><span>RESOURCE GOVERNANCE</span><h2>Book and chapter assignments</h2><p>Filter by class, subject, status, language, and resource type. Counts reflect matching records while the list loads one page at a time.</p></div>
          <div className={styles.filterBar}>
            <MultiSelectFilter label="Class" options={classFilterOptions} selected={viewClassCodes} onChange={values=>{setViewClassCodes(values);setAssignmentPage(0)}} />
            <MultiSelectFilter label="Subject" options={subjectFilterOptions} selected={viewSubjectCodes} onChange={values=>{setViewSubjectCodes(values);setAssignmentPage(0)}} />
            <MultiSelectFilter label="Status" options={statusFilterOptions} selected={viewStatuses} onChange={values=>{setViewStatuses(values);setAssignmentPage(0)}} />
            <MultiSelectFilter label="Language" options={languageFilterOptions} selected={viewLanguages} onChange={values=>{setViewLanguages(values);setAssignmentPage(0)}} />
            <MultiSelectFilter label="Resource type" options={scopeFilterOptions} selected={viewScopes} onChange={values=>{setViewScopes(values);setAssignmentPage(0)}} />
          </div>
        </div>
        <div className={styles.resultSummary}>
          <div><strong>{assignmentTotal}</strong><span>matching assignment{assignmentTotal === 1 ? '' : 's'}</span><small>{visibleAssignments.length} shown on this page</small></div>
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
          }}>Reset filters</button>
        </div>
        {loading ? <p className={styles.empty}>Loading resource assignments…</p> : visibleAssignments.length === 0 ? <div className={styles.empty}><b>No assignments match these filters</b><span>Clear or adjust the filters to inspect the matching resources.</span></div> :
          <div className={styles.assignmentList}>
            {visibleAssignments.map(row => <article key={row.document_id} className={styles.assignmentCard}>
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
        <div style={{display:'flex',justifyContent:'space-between',alignItems:'center',gap:12,marginTop:18,flexWrap:'wrap'}}>
          <small>Showing {assignmentTotal===0?0:assignmentPage*assignmentPageSize+1}–{Math.min(assignmentTotal,(assignmentPage+1)*assignmentPageSize)} of {assignmentTotal} assignments</small>
          <div style={{display:'flex',gap:8,alignItems:'center'}}>
            <button type="button" className={styles.secondaryButton} disabled={assignmentPage===0||loading} onClick={()=>setAssignmentPage(p=>Math.max(0,p-1))}>← Previous</button>
            <span>Page {assignmentTotal===0?0:assignmentPage+1} of {Math.max(1,Math.ceil(assignmentTotal/assignmentPageSize))}</span>
            <button type="button" className={styles.secondaryButton} disabled={(assignmentPage+1)*assignmentPageSize>=assignmentTotal||loading} onClick={()=>setAssignmentPage(p=>p+1)}>Next →</button>
          </div>
        </div>
      </section>
    </section>
  </main>;
}
