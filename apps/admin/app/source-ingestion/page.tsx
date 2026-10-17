'use client';

import AdminSidebar from '../components/AdminSidebar';
import PremiumPagination from '../components/PremiumPagination';
import { useCallback, useEffect, useMemo, useState } from 'react';
import styles from './SourceIngestion.module.css';
import { useUiTranslation } from '../i18n/useUiTranslation';

type Source = {
  source_id: number; source_kind: string; title: string; provider?: string | null;
  source_url?: string | null; edition?: string | null; language?: string | null;
  status: string; matching_pdf_asset_id?: number | null; pdf_asset_title?: string | null;
  pdf_page_count?: number | null; pdf_review_status?: string | null; chapter_mapping_count?: number;
};
type Track = { class_code: string; class_name: string; subject_code: string; subject_name: string };
type RegistryBook = {
  book_id: string; publisher: string; source_type: string; class: number; classes: number[];
  medium: 'hindi' | 'english'; title: string; subject: string; language: string; edition: string;
  source_url: string; catalog_entry_url: string; pdf_url: string; sha256: string; bytes: number; page_count: number;
};
type RegistryBookGap = {
  book_id: string; title: string; publisher?: string; subject?: string;
  class: number; classes?: number[]; medium: 'hindi' | 'english';
  status: string; expected_chapters?: number[]; available_chapters?: number[];
  missing_chapters?: number[]; note?: string; last_error?: string;
  source_url?: string; attempts_total?: number; updated_at?: string;
};
type ExistingMapping = {
  chapter_source_id: number; chapter_id: number; chapter_code: string; chapter_name: string;
  source_chapter_no?: number | null; source_chapter_title?: string | null; source_locator?: string | null;
  coverage_type: string; coverage_status: string; notes?: string | null;
  document_id?: number | null; document_title?: string | null; document_status?: string | null;
  document_source_title?: string | null; document_source_url?: string | null;
  pdf_asset_id?: number | null; pdf_title?: string | null; pdf_page_count?: number | null;
  pdf_review_status?: string | null; pdf_sha256?: string | null;
};
type Chapter = { chapter_id: number; chapter_code: string; chapter_name: string };
type Outline = { title: string; pageStart: number; pageEnd: number };
type Candidate = {
  chapter_id: number; chapter_row_id: number; chapter_title: string; chapter_code: string;
  page_start: number; page_end: number; status: string; pdf_asset_id?: number; pdf_title?: string;
  pdf_page_count?: number; pdf_review_status?: string; pdf_sha256?: string; review_notes?: string;
};
type ExtractionStatus = {
  status: string; total_pages?: number; processed_pages?: number; extracted_pages?: number;
  ocr_pages?: number; failed_pages?: number; last_error?: string | null;
};
type Job = {
  job_id: number; source_id: number; source_title: string; source_url: string; final_pdf_url?: string;
  cache_medium?: string | null; cache_book_id?: string | null; cache_sha256?: string | null;
  edition: string; language: string; status: string; error_message?: string | null;
  chapter_count?: number; approved_chapter_count?: number; rejected_chapter_count?: number;
  book_asset_id?: number; book_asset_title?: string; page_count?: number; class_code: string;
  class_name: string; subject_code: string; subject_name: string; detected_outline: Outline[];
  chapters: Candidate[]; book_review_status?: string;
};

async function api(url: string, init: RequestInit = {}) {
  const headers = new Headers(init.headers || {});
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(url, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(body.detail || body.message || body.error || ('__QE_REQUEST_FAILED__' + response.status));
  return body;
}
function localizedError(error: unknown, t: (key: string, values?: Record<string, string | number>) => string, fallbackKey: string) {
  if (error instanceof Error) {
    const status = error.message.match(/^__QE_REQUEST_FAILED__(\d+)$/);
    if (status) return t('admin.sourceIngestion.requestFailed', { status: Number(status[1]) });
    if (error.message) return error.message;
  }
  return t(fallbackKey);
}
function sourceStatusLabel(status: string | null | undefined, t: (key: string, values?: Record<string, string | number>) => string) {
  const value = String(status || '').toUpperCase();
  const known = ['QUEUED','DOWNLOADING','REVIEW','APPROVED','REJECTED','FAILED','INCOMPLETE','UNAVAILABLE','PENDING','RUNNING','COMPLETE'];
  return known.includes(value) ? t('admin.sourceIngestion.status.' + value) : (value.replaceAll('_', ' ') || t('admin.sourceIngestion.unknown'));
}
function normalizeJob(value: any) {
  let outline = value?.detected_outline;
  if (typeof outline === 'string') {
    try { outline = JSON.parse(outline); } catch { outline = []; }
  }
  return { ...value, detected_outline: Array.isArray(outline) ? outline : [], chapters: Array.isArray(value?.chapters) ? value.chapters : [] } as Job;
}
function normalized(value: string) {
  return value.toLowerCase().replace(/chapter|अध्याय|पाठ/gi, '').replace(/[^\p{L}\p{N}]+/gu, '').trim();
}

export default function SourceIngestionPage() {
  const t = useUiTranslation();
  const [sources, setSources] = useState<Source[]>([]);
  const [sourceSearchDraft, setSourceSearchDraft] = useState('');
  const [sourceQuery, setSourceQuery] = useState('');
  const [sourcePage, setSourcePage] = useState(0);
  const [sourcesTotal, setSourcesTotal] = useState(0);
  const [sourcePageSize, setSourcePageSize] = useState(10);
  const [registryBooks, setRegistryBooks] = useState<RegistryBook[]>([]);
  const [registryBookGaps, setRegistryBookGaps] = useState<RegistryBookGap[]>([]);
  const [registryMedium, setRegistryMedium] = useState<'hindi' | 'english'>('hindi');
  const [selectedRegistryBook, setSelectedRegistryBook] = useState('');
  const [confirmSubjectMapping, setConfirmSubjectMapping] = useState(false);
  const [existingMappings, setExistingMappings] = useState<ExistingMapping[]>([]);
  const [mappingError, setMappingError] = useState('');
  const [tracks, setTracks] = useState<Track[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [jobPage, setJobPage] = useState(0);
  const [jobsTotal, setJobsTotal] = useState(0);
  const [jobSummary, setJobSummary] = useState<Record<string, number>>({});
  const [jobPageSize, setJobPageSize] = useState(10);
  const [selectedSource, setSelectedSource] = useState('');
  const [classCode, setClassCode] = useState('');
  const [subjectCode, setSubjectCode] = useState('');
  const [sourceTitle, setSourceTitle] = useState('');
  const [sourceUrl, setSourceUrl] = useState('');
  const [edition, setEdition] = useState('UNVERIFIED');
  const [language, setLanguage] = useState('hi');
  const [provider, setProvider] = useState('SCERT Bihar');
  const [sourceKind, setSourceKind] = useState('BOARD_TEXTBOOK');
  const [job, setJob] = useState<Job | null>(null);
  const [chapterOptions, setChapterOptions] = useState<Chapter[]>([]);
  const [mapping, setMapping] = useState<Array<{ key: string; included: boolean; chapterId: string; title: string; start: string; end: string }>>([]);
  const [previewPage, setPreviewPage] = useState('1');
  const [previewChapterRow, setPreviewChapterRow] = useState('');
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [extractionStatus, setExtractionStatus] = useState<ExtractionStatus | null>(null);

  const tracksByClass = useMemo(() => [...new Map(tracks.map(t => [t.class_code, t])).values()], [tracks]);
  const subjects = useMemo(() => [...new Map(tracks.filter(t => t.class_code === classCode).map(t => [t.subject_code, t])).values()], [tracks, classCode]);
  const latestReviewJobs = jobs.filter(item => ['DOWNLOADING', 'REVIEW', 'FAILED'].includes(item.status));
  const activeTrack = tracks.find(track => track.class_code === classCode);
  const gradeMatches = (classCode + ' ' + (activeTrack?.class_name || '')).match(/\d+/g) || [];
  const selectedGrade = gradeMatches.length ? Number(gradeMatches[gradeMatches.length - 1]) : 0;
  const registryBookOptions = registryBooks.filter(book =>
    book.medium === registryMedium && (book.classes || [book.class]).map(Number).includes(selectedGrade));
  const registryBookGapRows = registryBookGaps.filter(book =>
    book.medium === registryMedium && Number(book.class) === selectedGrade);
  const selectedRegistryBookRow = registryBookOptions.find(book => book.book_id === selectedRegistryBook)
    || registryBookOptions[0];

  const loadBase = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [sourceRows, jobRows, curriculumRows, registryRows, registryGapRows] = await Promise.all([
        api('/api/v1/admin/source-ingestion/sources?' + new URLSearchParams({
          query: sourceQuery,
          limit: String(sourcePageSize),
          offset: String(sourcePage * sourcePageSize),
        }).toString()),
        api('/api/v1/admin/source-ingestion/jobs?limit=' + jobPageSize + '&offset=' + (jobPage * jobPageSize)),
        api('/api/v1/curriculum'),
        api('/api/v1/admin/source-ingestion/registry-books?medium=both').catch(() => []),
        api('/api/v1/admin/source-ingestion/registry-book-gaps?medium=both').catch(() => []),
      ]);
      const sourceData = Array.isArray(sourceRows?.items) ? sourceRows.items : (Array.isArray(sourceRows) ? sourceRows : []);
      setSourcesTotal(Number(sourceRows?.total ?? sourceData.length));
      const jobData = Array.isArray(jobRows?.items) ? jobRows.items : (Array.isArray(jobRows) ? jobRows : []);
      setJobsTotal(Number(jobRows?.total ?? jobData.length));
      setJobSummary(jobRows?.summary && typeof jobRows.summary === 'object' ? jobRows.summary : {});
      const curriculum = Array.isArray(curriculumRows) ? curriculumRows : [];
      const nextTracks = [...new Map(curriculum.map((r: any) => [String(r.class_code) + ':' + String(r.subject_code), {
        class_code: String(r.class_code), class_name: String(r.class_name),
        subject_code: String(r.subject_code), subject_name: String(r.subject_name),
      }])).values()] as Track[];
      setSources(sourceData);
      setJobs(jobData);
      setTracks(nextTracks);
      setRegistryBooks(Array.isArray(registryRows) ? registryRows as RegistryBook[] : []);
      setRegistryBookGaps(Array.isArray(registryGapRows) ? registryGapRows as RegistryBookGap[] : []);
      if (!classCode && nextTracks.length) {
        setClassCode(nextTracks[0].class_code);
        setSubjectCode(nextTracks[0].subject_code);
      } else if (!nextTracks.some(t => t.class_code === classCode)) {
        setClassCode(nextTracks[0]?.class_code || '');
        setSubjectCode(nextTracks[0]?.subject_code || '');
      } else if (!nextTracks.some(t => t.class_code === classCode && t.subject_code === subjectCode)) {
        setSubjectCode(nextTracks.find(t => t.class_code === classCode)?.subject_code || '');
      }
      if (!selectedSource && sourceData.length) {
        setSelectedSource(String(sourceData[0].source_id));
        setSourceTitle(sourceData[0].title || '');
        setSourceUrl(sourceData[0].source_url || '');
        setEdition(sourceData[0].edition || 'UNVERIFIED');
        setLanguage(sourceData[0].language || 'hi');
        setProvider(sourceData[0].provider || '');
        setSourceKind(sourceData[0].source_kind || 'BOARD_TEXTBOOK');
      } else if (!selectedSource) {
        setSelectedSource('new');
      }
    } catch (e) {
      setError(localizedError(e, t, 'admin.sourceIngestion.dataLoadFailed'));
    } finally { setLoading(false); }
  }, [classCode, subjectCode, selectedSource, jobPage, jobPageSize, sourceQuery, sourcePage, sourcePageSize]);

  const loadJob = useCallback(async (id: number) => {
    const item = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + id));
    setJob(item);
    if (item.book_asset_id) {
      try {
        setExtractionStatus(await api('/api/v1/admin/textbook-extraction/assets/' + item.book_asset_id));
      } catch { setExtractionStatus(null); }
    } else setExtractionStatus(null);
    setSelectedSource(String(item.source_id));
    setSourceTitle(item.source_title || '');
    setSourceUrl(item.source_url || '');
    setEdition(item.edition || 'UNVERIFIED');
    setLanguage(item.language || 'hi');
    setPreviewPage('1');
    setPreviewChapterRow('');
    const rows = await api('/api/v1/admin/content?classCode=' + encodeURIComponent(item.class_code)
      + '&subjectCode=' + encodeURIComponent(item.subject_code));
    const distinct = new Map<number, Chapter>();
    (Array.isArray(rows) ? rows : []).forEach((r: any) => {
      const chapterId = Number(r.chapter_id);
      if (chapterId > 0 && r.chapter_name) distinct.set(chapterId, {
        chapter_id: chapterId, chapter_code: String(r.chapter_code || ''),
        chapter_name: String(r.chapter_name),
      });
    });
    setChapterOptions([...distinct.values()]);
    if (item.chapters.length) {
      setMapping(item.chapters.map(ch => ({
        key: String(ch.chapter_row_id), included: true, chapterId: String(ch.chapter_id), title: ch.chapter_title,
        start: String(ch.page_start), end: String(ch.page_end),
      })));
    } else {
      const outline = Array.isArray(item.detected_outline) ? item.detected_outline : [];
      if (outline.length) {
        const next = outline.map((entry, index) => {
          const found = [...distinct.values()].find(ch => {
            const a = normalized(ch.chapter_name); const b = normalized(entry.title);
            return a === b || b.includes(a) || a.includes(b) || normalized(ch.chapter_code) === b;
          });
          return {
            key: 'outline-' + index, included: Boolean(found), chapterId: found ? String(found.chapter_id) : '',
            title: entry.title, start: String(entry.pageStart),
            end: String(entry.pageEnd || (index + 1 < outline.length ? outline[index + 1].pageStart - 1 : item.page_count || '')),
          };
        });
        setMapping(next);
      } else {
        setMapping([...distinct.values()].map(ch => ({
          key: 'chapter-' + ch.chapter_id, included: false, chapterId: String(ch.chapter_id),
          title: ch.chapter_name, start: '', end: '',
        })));
      }
    }
  }, []);

  useEffect(() => { void loadBase(); }, [loadBase]);

  useEffect(() => {
    let active = true;
    setMappingError('');
    if (!selectedSource || selectedSource === 'new') {
      setExistingMappings([]);
      return () => { active = false; };
    }
    api('/api/v1/admin/source-ingestion/sources/' + encodeURIComponent(selectedSource) + '/chapter-mappings')
      .then(rows => { if (active) setExistingMappings(Array.isArray(rows) ? rows : []); })
      .catch(e => {
        if (active) setMappingError(localizedError(e, t, 'admin.sourceIngestion.mappingLoadFailed'));
      });
    return () => { active = false; };
  }, [selectedSource]);

  async function startJob() {
    if (!classCode || !subjectCode) { setError(t('admin.sourceIngestion.chooseClassSubject')); return; }
    if (!sourceTitle.trim() || !sourceUrl.trim()) { setError(t('admin.sourceIngestion.addSourceTitleUrl')); return; }
    if (!/^https:\/\//i.test(sourceUrl.trim())) { setError(t('admin.sourceIngestion.httpsOnly')); return; }
    setBusy(true); setError(''); setNotice('');
    try {
      const created = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs', {
        method: 'POST',
        body: JSON.stringify({
          sourceId: selectedSource === 'new' ? null : Number(selectedSource),
          classCode, subjectCode, sourceTitle: sourceTitle.trim(), sourceUrl: sourceUrl.trim(),
          edition: edition.trim() || 'UNVERIFIED', language: language.trim() || 'hi',
          provider: provider.trim(), sourceKind: sourceKind.trim() || 'BOARD_TEXTBOOK',
        }),
      }));
      setNotice(t('admin.sourceIngestion.ingestionStarted', { jobId: created.job_id }));
      await loadBase();
      setJob(created);
      if (created.status === 'REVIEW') await loadJob(created.job_id);
      else setJob(created);
    } catch (e) {
      setError(localizedError(e, t, 'admin.sourceIngestion.startFailed'));
    } finally { setBusy(false); }
  }

  async function startCachedBookJob() {
    if (!classCode || !subjectCode) { setError(t('admin.sourceIngestion.chooseCurriculumTrack')); return; }
    if (!selectedRegistryBookRow) { setError(t('admin.sourceIngestion.noCachedBook')); return; }
    if (!confirmSubjectMapping) { setError(t('admin.sourceIngestion.confirmBookMapping')); return; }
    setBusy(true); setError(''); setNotice('');
    try {
      const created = normalizeJob(await api(
        '/api/v1/admin/source-ingestion/registry-books/' + encodeURIComponent(registryMedium)
          + '/' + encodeURIComponent(selectedRegistryBookRow.book_id) + '/jobs',
        { method: 'POST', body: JSON.stringify({ classCode, subjectCode, confirmSubjectMapping }) },
      ));
      setJob(created);
      await loadBase();
      if (created.status === 'REVIEW') await loadJob(created.job_id);
      else setJob(created);
      setNotice(created.status === 'APPROVED'
        ? t('admin.sourceIngestion.cachedBookAlreadyReviewed')
        : created.status === 'REVIEW'
          ? t('admin.sourceIngestion.cachedBookOpened')
          : t('admin.sourceIngestion.cachedBookStarted', { jobId: created.job_id }));
    } catch (e) {
      setError(localizedError(e, t, 'admin.sourceIngestion.cachedJobFailed'));
    } finally { setBusy(false); }
  }

  async function refreshJob(id?: number) {
    setBusy(true); setError('');
    try {
      await loadBase();
      const target = id || job?.job_id || jobs[0]?.job_id;
      if (target) await loadJob(target);
      setNotice(t('admin.sourceIngestion.statusRefreshed'));
    } catch (e) { setError(localizedError(e, t, 'admin.sourceIngestion.refreshFailed')); }
    finally { setBusy(false); }
  }
  async function queueTextbookExtraction() {
    if (!job?.book_asset_id) { setError(t('admin.sourceIngestion.noStoredAsset')); return; }
    setBusy(true); setError(''); setNotice('');
    try {
      const response = await api('/api/v1/admin/textbook-extraction/assets/' + job.book_asset_id, {
        method: 'POST', body: JSON.stringify({}),
      });
      setExtractionStatus(response.job as ExtractionStatus);
      setNotice(t('admin.sourceIngestion.extractionQueued'));
    } catch (e) {
      setError(localizedError(e, t, 'admin.sourceIngestion.extractionQueueFailed'));
    } finally { setBusy(false); }
  }

  async function refreshTextbookExtraction() {
    if (!job?.book_asset_id) return;
    setBusy(true); setError('');
    try {
      setExtractionStatus(await api('/api/v1/admin/textbook-extraction/assets/' + job.book_asset_id));
      setNotice(t('admin.sourceIngestion.extractionStatusRefreshed'));
    } catch (e) {
      setError(localizedError(e, t, 'admin.sourceIngestion.extractionStatusFailed'));
    } finally { setBusy(false); }
  }


  function updateMapping(key: string, patch: Partial<(typeof mapping)[number]>) {
    setMapping(old => old.map(row => row.key === key ? { ...row, ...patch } : row));
  }

  function applyDetectedOutline() {
    if (!job || !job.detected_outline?.length) return;
    const next = job.detected_outline.map((entry, index) => {
      const found = chapterOptions.find(ch => {
        const a = normalized(ch.chapter_name); const b = normalized(entry.title);
        return a === b || b.includes(a) || a.includes(b) || normalized(ch.chapter_code) === b;
      });
      return {
        key: 'outline-' + index, included: true, chapterId: found ? String(found.chapter_id) : '',
        title: entry.title, start: String(entry.pageStart),
        end: String(entry.pageEnd || (index + 1 < job.detected_outline.length ? job.detected_outline[index + 1].pageStart - 1 : job.page_count || '')),
      };
    });
    setMapping(next);
  }

  async function saveJobMetadata() {
    if (!job) return;
    if (!sourceTitle.trim() || !sourceUrl.trim() || !edition.trim()) {
      setError(t('admin.sourceIngestion.metadataRequired'));
      return;
    }
    if (!/^https:\/\//i.test(sourceUrl.trim())) {
      setError(t('admin.sourceIngestion.reviewedUrlHttps'));
      return;
    }
    if (/^(unverified|unknown|tbd|n\/a)$/i.test(edition.trim())
        || /verify edition|to be verified|not specified/i.test(edition.trim())) {
      setError(t('admin.sourceIngestion.editionRequired'));
      return;
    }
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/metadata', {
        method: 'PATCH',
        body: JSON.stringify({
          sourceTitle: sourceTitle.trim(),
          sourceUrl: sourceUrl.trim(),
          edition: edition.trim(),
        }),
      }));
      setJob(updated);
      setSourceTitle(updated.source_title || '');
      setSourceUrl(updated.source_url || '');
      setEdition(updated.edition || '');
      await loadBase();
      setNotice(t('admin.sourceIngestion.metadataSaved'));
    } catch (e) {
      setError(localizedError(e, t, 'admin.sourceIngestion.metadataSaveFailed'));
    } finally { setBusy(false); }
  }

  async function splitChapters() {
    if (!job) return;
    const selected = mapping.filter(row => row.included);
    if (!selected.length) { setError(t('admin.sourceIngestion.selectChapters')); return; }
    if (selected.some(row => !row.chapterId || !row.start || !row.end)) {
      setError(t('admin.sourceIngestion.chapterMappingIncomplete')); return;
    }
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/chapters', {
        method: 'POST',
        body: JSON.stringify({ chapters: selected.map(row => ({
          chapterId: Number(row.chapterId), title: row.title, pageStart: Number(row.start), pageEnd: Number(row.end),
        })) }),
      }));
      setJob(updated);
      await loadJob(updated.job_id);
      await loadBase();
      setNotice(t('admin.sourceIngestion.chaptersCreated'));
    } catch (e) { setError(localizedError(e, t, 'admin.sourceIngestion.splitFailed')); }
    finally { setBusy(false); }
  }

  async function reviewChapter(row: Candidate, status: 'APPROVED' | 'REJECTED') {
    if (!job) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/chapters/' + row.chapter_row_id, {
        method: 'PATCH', body: JSON.stringify({ status }),
      }));
      setJob(updated);
      setNotice(t('admin.sourceIngestion.chapterStatusSaved', { title: row.chapter_title, status: sourceStatusLabel(status, t) }));
      await loadBase();
    } catch (e) { setError(localizedError(e, t, 'admin.sourceIngestion.chapterReviewFailed')); }
    finally { setBusy(false); }
  }

  async function approveJob() {
    if (!job) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/approve', { method: 'POST', body: '{}' }));
      setJob(updated);
      await loadBase();
      setNotice(t('admin.sourceIngestion.bookApproved'));
    } catch (e) { setError(localizedError(e, t, 'admin.sourceIngestion.bookApproveFailed')); }
    finally { setBusy(false); }
  }

  async function rejectJob() {
    if (!job || !window.confirm(t('admin.sourceIngestion.rejectConfirm'))) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/reject', { method: 'POST', body: '{}' }));
      setJob(updated);
      await loadBase();
      setNotice(t('admin.sourceIngestion.ingestionRejected'));
    } catch (e) { setError(localizedError(e, t, 'admin.sourceIngestion.rejectFailed')); }
    finally { setBusy(false); }
  }

  const previewUrl = job
    ? '/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/pages/' + encodeURIComponent(previewPage)
      + (previewChapterRow ? '?chapterRowId=' + encodeURIComponent(previewChapterRow) : '')
    : '';

  return <main className="admin-shell">
    <AdminSidebar active="sources" variant="content" />
    <section className={'admin-main ' + styles.page}>
      <header className="admin-top">
        <div>
          <span className="admin-kicker">QUANTAEDGE LEARNING / CONTENT GOVERNANCE</span>
          <h1>{t('admin.sourceIngestion.title')}</h1>
          <p>{t('admin.sourceIngestion.description')}</p>
        </div>
        <button className="admin-refresh" type="button" aria-label={t('admin.sourceIngestion.refreshStatus')} disabled={busy} onClick={() => void refreshJob()} title={t('admin.sourceIngestion.refreshTitle')}>↻</button>
      </header>

      {error && <div className={styles.error} role="alert">{error}</div>}
      {notice && <div className={styles.notice} role="status">{notice}</div>}

      <section className={styles.summary}>
        <div><small>{t('admin.sourceIngestion.registeredSources')}</small><strong>{sourcesTotal}</strong><span>{t('admin.sourceIngestion.matchingSourceRecords')}</span></div>
        <div><small>{t('admin.sourceIngestion.inReview')}</small><strong>{Number(jobSummary.in_review ?? jobs.filter(j => j.status === 'REVIEW').length)}</strong><span>{t('admin.sourceIngestion.booksAwaitingReview')}</span></div>
        <div><small>{t('admin.sourceIngestion.approvedJobs')}</small><strong>{Number(jobSummary.approved ?? jobs.filter(j => j.status === 'APPROVED').length)}</strong><span>{t('admin.sourceIngestion.addedAsDrafts')}</span></div>
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}><div><span>{t('admin.sourceIngestion.stepOne')}</span><h2>{t('admin.sourceIngestion.chooseRegisterSource')}</h2><p>{t('admin.sourceIngestion.sourceChecksDescription')}</p></div></div>
        <div className={styles.fields}>
          <div className={styles.field}>
            <label htmlFor="qe-source-search">{t('admin.sourceIngestion.sourceRecord')}</label>
            <input id="qe-source-search" value={sourceSearchDraft} onChange={e=>setSourceSearchDraft(e.target.value)} placeholder={t('admin.sourceIngestion.searchSourcePlaceholder')} aria-label={t('admin.sourceIngestion.searchRegisteredSources')} />
            <div style={{display:'flex',gap:8,alignItems:'center',flexWrap:'wrap',margin:'8px 0'}}>
              <button type="button" className={styles.secondary} onClick={()=>{setSourceQuery(sourceSearchDraft.trim());setSourcePage(0)}} >{t('admin.sourceIngestion.searchSources')}</button>
              <button type="button" className={styles.secondary} disabled={!sourceQuery&&!sourceSearchDraft} onClick={()=>{setSourceSearchDraft('');setSourceQuery('');setSourcePage(0)}}>{t('admin.sourceIngestion.clearSearch')}</button>
            </div>
            <select aria-label={t('admin.sourceIngestion.chooseRegisteredSource')} value={selectedSource || 'new'} onChange={e => {
              const value = e.target.value; setSelectedSource(value);
              if (value !== 'new') {
                const source = sources.find(row => String(row.source_id) === value);
                if (source) {
                  setSourceTitle(source.title || ''); setSourceUrl(source.source_url || '');
                  setEdition(source.edition || 'UNVERIFIED'); setLanguage(source.language || 'hi');
                  setProvider(source.provider || ''); setSourceKind(source.source_kind || 'BOARD_TEXTBOOK');
                }
              } else {
                setSourceTitle(''); setSourceUrl(''); setEdition('UNVERIFIED'); setLanguage('hi');
                setProvider(''); setSourceKind('BOARD_TEXTBOOK');
              }
            }}>
              <option value="new">{t('admin.sourceIngestion.registerNewSource')}</option>
              {selectedSource && selectedSource!=='new' && !sources.some(source=>String(source.source_id)===selectedSource) &&
                <option value={selectedSource}>{t('admin.sourceIngestion.selectedRegisteredSource', { sourceId: selectedSource })}</option>}
              {sources.map(source => <option key={source.source_id} value={source.source_id}>
                {source.title} · {source.source_kind} {source.matching_pdf_asset_id ? '· PDF stored' : '· PDF not linked'}
              </option>)}
            </select>
            <PremiumPagination page={sourcePage} total={sourcesTotal} pageSize={sourcePageSize}
              onPageChange={setSourcePage} onPageSizeChange={size=>{setSourcePageSize(size);setSourcePage(0);}}
              itemLabel={t('admin.sourceIngestion.registeredSourcesPlural')} loading={loading} />
          </div>
          <label>{t('admin.sourceIngestion.class')}
            <select value={classCode} onChange={e => {
              const value = e.target.value; setClassCode(value);
              setSubjectCode(tracks.find(t => t.class_code === value)?.subject_code || '');
              setConfirmSubjectMapping(false);
            }}>
              {tracksByClass.map(track => <option key={track.class_code} value={track.class_code}>{track.class_name} ({track.class_code})</option>)}
            </select>
          </label>
          <label>{t('admin.sourceIngestion.subject')}
            <select value={subjectCode} onChange={e => { setSubjectCode(e.target.value); setConfirmSubjectMapping(false); }}>
              {subjects.map(track => <option key={track.subject_code} value={track.subject_code}>{track.subject_name}</option>)}
            </select>
          </label>
          <label>{t('admin.sourceIngestion.sourceTitle')}
            <input value={sourceTitle} onChange={e => setSourceTitle(e.target.value)} placeholder={t('admin.sourceIngestion.bookTitlePlaceholder')} />
          </label>
          <label className={styles.wide}>{t('admin.sourceIngestion.officialSourceUrl')}
            <input type="url" value={sourceUrl} onChange={e => setSourceUrl(e.target.value)} placeholder={t('admin.sourceIngestion.officialPdfPlaceholder')} />
          </label>
          <label>{t('admin.sourceIngestion.editionSession')}
            <input value={edition} onChange={e => setEdition(e.target.value)} placeholder={t('admin.sourceIngestion.editionPlaceholder')} />
          </label>
          <label>{t('admin.sourceIngestion.language')}
            <input value={language} onChange={e => setLanguage(e.target.value)} placeholder={t('admin.sourceIngestion.languagePlaceholder')} />
          </label>
          {selectedSource === 'new' && <>
            <label>{t('admin.sourceIngestion.providerBoard')}
              <input value={provider} onChange={e => setProvider(e.target.value)} placeholder={t('admin.sourceIngestion.providerPlaceholder')} />
            </label>
            <label>{t('admin.sourceIngestion.sourceType')}
              <input value={sourceKind} onChange={e => setSourceKind(e.target.value)} placeholder={t('admin.sourceIngestion.sourceTypePlaceholder')} />
            </label>
          </>}
        </div>
        <div className={styles.actions}>
          <p>{t('admin.sourceIngestion.downloadLimitGuidance')}</p>
          <button className={styles.primary} type="button" disabled={busy || !sourceTitle.trim() || !sourceUrl.trim() || !subjectCode} onClick={() => void startJob()}>
            {busy ? t('admin.sourceIngestion.working') : t('admin.sourceIngestion.startDownload')}
          </button>
        </div>
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}>
          <div><span>{t('admin.sourceIngestion.ghcrCache')}</span><h2>{t('admin.sourceIngestion.persistentCompleteBook')}</h2>
            <p>{t('admin.sourceIngestion.cacheDescription')}</p>
          </div>
          <span className={styles.bigStatus}>{t('admin.sourceIngestion.indexedBooks', { count: registryBooks.length })}</span>
        </div>
        <div className={styles.fields}>
          <label>{t('admin.sourceIngestion.medium')}
            <select value={registryMedium} onChange={e => { setRegistryMedium(e.target.value as 'hindi' | 'english'); setConfirmSubjectMapping(false); }}>
              <option value="hindi">{t('admin.sourceIngestion.hindi')}</option>
              <option value="english">{t('admin.sourceIngestion.english')}</option>
            </select>
          </label>
          <label>{t('admin.sourceIngestion.completeBookForClass', { className: activeTrack?.class_name || classCode || t('admin.sourceIngestion.class') })}
            <select value={selectedRegistryBookRow?.book_id || ''} onChange={e => { setSelectedRegistryBook(e.target.value); setConfirmSubjectMapping(false); }}
              disabled={!registryBookOptions.length}>
              {registryBookOptions.length === 0 && <option value="">{t('admin.sourceIngestion.noCachedBooksOption')}</option>}
              {registryBookOptions.map(book => <option key={book.book_id} value={book.book_id}>
                {book.title} — {book.subject || 'Subject not specified'} · {book.publisher}
              </option>)}
            </select>
          </label>
        </div>
        {selectedRegistryBookRow ? <div className={styles.cacheMeta}>
          <b>{selectedRegistryBookRow.title}</b>
          <span>{selectedRegistryBookRow.publisher} · Class {(selectedRegistryBookRow.classes || [selectedRegistryBookRow.class]).join(', ')} · {selectedRegistryBookRow.subject || 'Subject unspecified'}</span>
          <span>{(Number(selectedRegistryBookRow.bytes || 0) / (1024 * 1024)).toFixed(1)} MiB · {selectedRegistryBookRow.page_count || 'Page count pending'} pages · {selectedRegistryBookRow.medium} medium</span>
          <code>{selectedRegistryBookRow.book_id} · SHA-256 {String(selectedRegistryBookRow.sha256 || '').slice(0, 16)}…</code>
          <label className={styles.mappingConfirm}>
            <input type="checkbox" checked={confirmSubjectMapping} onChange={e => setConfirmSubjectMapping(e.target.checked)} />
            <span>{t('admin.sourceIngestion.confirmSelectedMapping', { className: activeTrack?.class_name || classCode, subjectName: subjects.find(t => t.subject_code === subjectCode)?.subject_name || subjectCode })}</span>
          </label>
        </div> : <p className={styles.help}>
          {registryBooks.length === 0
            ? 'No GHCR cache index is mounted in this API environment yet. Sync both language images using scripts/quantaedge-textbook-sync.ps1 or scripts/quantaedge-textbook-sync.sh, then refresh this screen.'
            : 'No complete cached book matches the selected class and medium. Check the GHCR inventory and retry any missing registry entries.'}
        </p>}
        <div className={styles.actions}>
          <p>The downloaded source must be a complete book. We do not fall back to individual NCERT chapter downloads. Page ranges and curriculum chapter assignments remain review decisions; approval creates private library drafts, not student-visible content.</p>
          <button className={styles.primary} type="button"
            disabled={busy || !selectedRegistryBookRow || !subjectCode || !classCode || !confirmSubjectMapping}
            onClick={() => void startCachedBookJob()}>
            {busy ? 'Working…' : 'Review complete cached book'}
          </button>
        </div>
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}>
          <div><span>{t('admin.sourceIngestion.sourceAvailability')}</span><h2>{t('admin.sourceIngestion.unavailableBooks')}</h2>
            <p>{t('admin.sourceIngestion.sourceGapsDescription')}</p>
          </div>
          <span className={styles.bigStatus}>{t('admin.sourceIngestion.gapCount', { count: registryBookGapRows.length })}</span>
        </div>
        {registryBookGapRows.length === 0
          ? <p className={styles.empty}>{t('admin.sourceIngestion.noReportedGaps', { classNumber: selectedGrade || '—', medium: registryMedium === 'hindi' ? t('admin.sourceIngestion.hindi') : t('admin.sourceIngestion.english') })}</p>
          : <div className={styles.gapList}>
              {registryBookGapRows.map((gap) => <article className={styles.gapCard} key={gap.medium + ':' + gap.class + ':' + gap.book_id}>
                <div className={styles.gapTop}>
                  <div><b>{gap.title}</b><small>{gap.book_id} · {gap.publisher || t('admin.sourceIngestion.officialSource')} · {gap.subject || t('admin.sourceIngestion.subjectUnspecified')}</small></div>
                  <span className={styles.gapBadge}>{gap.status.replaceAll('_', ' ').toUpperCase()}</span>
                </div>
                <div className={styles.gapCoverage}>
                  <span><b>{t('admin.sourceIngestion.chaptersDetectedNotCached')}</b> {gap.available_chapters?.length ? gap.available_chapters.join(', ') : t('admin.sourceIngestion.noneVerified')}</span>
                  <span><b>{t('admin.sourceIngestion.missingChapterNumbers')}</b> {gap.missing_chapters?.length ? gap.missing_chapters.join(', ') : t('admin.sourceIngestion.notDeterminedBySource')}</span>
                  {gap.expected_chapters?.length ? <span><b>{t('admin.sourceIngestion.expectedChapters')}</b> {gap.expected_chapters.join(', ')}</span> : null}
                </div>
                {gap.note && <p>{gap.note}</p>}
                {gap.last_error && <p className={styles.gapError}>{gap.last_error}</p>}
                {gap.source_url && <a href={gap.source_url} target="_blank" rel="noreferrer">{t('admin.sourceIngestion.openSourceUrl')}</a>}
              </article>)}
            </div>}
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}><div><span>{t('admin.sourceIngestion.databaseCheck')}</span><h2>{t('admin.sourceIngestion.existingChapterRecords')}</h2>
          <p>{t('admin.sourceIngestion.mappingDescription')}</p></div>
          <span className={styles.bigStatus}>{t('admin.sourceIngestion.mappingsCount', { count: existingMappings.length })}</span>
        </div>
        {mappingError && <div className={styles.error} role="alert">{mappingError}</div>}
        {selectedSource === 'new' || !selectedSource
          ? <p className={styles.empty}>{t('admin.sourceIngestion.selectExistingSource')}</p>
          : existingMappings.length === 0
            ? <p className={styles.empty}>{t('admin.sourceIngestion.noMappings')}</p>
            : <div className={styles.existingMappingList}>{existingMappings.map(row => <article className={styles.existingMapping} key={row.chapter_source_id}>
              <div className={styles.mappingIdentity}>
                <b>{row.chapter_code} — {row.chapter_name}</b>
                <small>{t('admin.sourceIngestion.mapping')}: {sourceStatusLabel(row.coverage_status, t)} · {sourceStatusLabel(row.coverage_type, t)}</small>
                {row.source_chapter_title && <small>{t('admin.sourceIngestion.sourceChapter')}: {row.source_chapter_title}{row.source_chapter_no ? ' · ' + t('admin.sourceIngestion.numberAbbreviation') + ' ' + row.source_chapter_no : ''}</small>}
                {row.source_locator && <small>{t('admin.sourceIngestion.sourceLocator')}: {row.source_locator}</small>}
                {row.notes && <p>{row.notes}</p>}
              </div>
              <div className={styles.mappingAsset}>
                {row.pdf_asset_id
                  ? <><b>{row.pdf_title || row.document_title || t('admin.sourceIngestion.storedChapterPdf')}</b>
                    <span>{row.document_status ? sourceStatusLabel(row.document_status, t) : t('admin.sourceIngestion.noDocumentStatus')} · {row.pdf_page_count || '—'} pages · Asset #{row.pdf_asset_id}</span>
                    <span>Asset review: {row.pdf_review_status ? sourceStatusLabel(row.pdf_review_status, t) : t('admin.sourceIngestion.unknown')}</span>
                    {row.document_source_url && <a href={row.document_source_url} target="_blank" rel="noreferrer">{t('admin.sourceIngestion.openRecordedUrl')}</a>}
                  </>
                  : <><b>{t('admin.sourceIngestion.noLinkedPdf')}</b><span>{t('admin.sourceIngestion.mappingWithoutPdf')}</span></>}
              </div>
            </article>)}</div>}
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}><div><span>{t('admin.sourceIngestion.stepTwo')}</span><h2>{t('admin.sourceIngestion.ingestionJobs')}</h2><p>{t('admin.sourceIngestion.jobsDescription')}</p></div>
          <button className={styles.secondary} type="button" disabled={busy} onClick={() => void refreshJob()}>{t('admin.sourceIngestion.refreshJobStatus')}</button>
        </div>
        {loading ? <p className={styles.empty} role="status" aria-live="polite">{t('admin.sourceIngestion.loadingSourceRecords')}</p> : jobs.length === 0 ? <p className={styles.empty}>{t('admin.sourceIngestion.noJobs')}</p> :
          <div className={styles.jobList}>{jobs.map(row => <button type="button" key={row.job_id}
            className={styles.jobButton + (job?.job_id === row.job_id ? ' ' + styles.selected : '')}
            onClick={() => void loadJob(row.job_id)}>
            <span className={styles.jobStatus + ' ' + styles['status' + row.status]}>{sourceStatusLabel(row.status, t)}</span>
            <b>#{row.job_id} · {row.source_title}</b>
            <small>{row.class_name} · {row.subject_name} · {row.page_count ? t('admin.sourceIngestion.pageCount', { count: row.page_count }) : t('admin.sourceIngestion.pdfNotReady')}</small>
            <small>{t('admin.sourceIngestion.chapterCandidates', { count: row.chapter_count || 0 })} · {t('admin.sourceIngestion.approvedCount', { count: row.approved_chapter_count || 0 })} · {t('admin.sourceIngestion.rejectedCount', { count: row.rejected_chapter_count || 0 })}</small>
            {row.error_message && <small className={styles.jobError}>{row.error_message}</small>}
          </button>)}</div>}
        <PremiumPagination page={jobPage} total={jobsTotal} pageSize={jobPageSize}
          onPageChange={setJobPage} onPageSizeChange={size=>{setJobPageSize(size);setJobPage(0);}}
          itemLabel="ingestion jobs" loading={loading} />
        {latestReviewJobs.length > 0 && <p className={styles.help}>{t('admin.sourceIngestion.downloadingHelp')}</p>}
      </section>

      {job && <section className={styles.panel}>
        <div className={styles.heading}><div><span>{t('admin.sourceIngestion.stepThree')}</span><h2>{t('admin.sourceIngestion.reviewJob', { jobId: job.job_id, title: job.source_title })}</h2>
          <p>{job.class_name} · {job.subject_name} · {job.edition} · {job.page_count ? t('admin.sourceIngestion.pageCount', { count: job.page_count }) : '—'} · <b>{sourceStatusLabel(job.status, t)}</b></p></div>
          <span className={styles.bigStatus}>{sourceStatusLabel(job.status, t)}</span></div>
        {job.error_message && <div className={styles.error} role="alert">{job.error_message}</div>}
        {job.status === 'REVIEW' && <div className={styles.metadataReview}>
          <div><b>{t('admin.sourceIngestion.sourceProvenance')}</b><p>{t('admin.sourceIngestion.provenanceInstructions')}</p></div>
          <button className={styles.secondary} type="button" disabled={busy}
            onClick={() => void saveJobMetadata()}>{t('admin.sourceIngestion.saveVerifiedMetadata')}</button>
        </div>}
        {job.status === 'DOWNLOADING' && <div className={styles.empty}>The source is being checked and downloaded. Select “Refresh status” above to see the detected PDF outline.</div>}
        {job.status === 'REVIEW' && <>
          <div className={styles.preview}>
            <div className={styles.previewBar}>
              <div><b>{t('admin.sourceIngestion.privatePdfPreview')}</b><small>{t('admin.sourceIngestion.previewDescription')}</small></div>
              <label>{t('admin.sourceIngestion.preview')}
                <select value={previewChapterRow} onChange={e => { setPreviewChapterRow(e.target.value); setPreviewPage('1'); }}>
                  <option value="">{t('admin.sourceIngestion.completeBookPages')}</option>
                  {job.chapters.map(ch => <option key={ch.chapter_row_id} value={ch.chapter_row_id}>{ch.chapter_title} · {t('admin.sourceIngestion.splitPdf')}</option>)}
                </select>
              </label>
              <label>{t('admin.sourceIngestion.page')}
                <input type="number" min="1" max={previewChapterRow ? (job.chapters.find(ch => String(ch.chapter_row_id) === previewChapterRow)?.pdf_page_count || 2000) : (job.page_count || 2000)}
                  value={previewPage} onChange={e => setPreviewPage(e.target.value)} />
              </label>
            </div>
            {(job.book_asset_id || job.cache_book_id) && <img className={styles.previewImage} src={previewUrl} alt="Privately rendered PDF page preview" />}
          </div>
          <div className={styles.outline}>
            <div className={styles.outlineHeading}><div><h3>{t('admin.sourceIngestion.detectedOutline')}</h3><p>{t('admin.sourceIngestion.outlineHelp')}</p></div>
              {job.detected_outline?.length > 0 && <button className={styles.secondary} type="button" onClick={applyDetectedOutline}>{t('admin.sourceIngestion.useDetectedRanges')}</button>}
            </div>
            {job.detected_outline?.length ? <div className={styles.outlineList}>{job.detected_outline.map((item, index) =>
              <div key={index}><b>{item.title}</b><span>PDF pages {item.pageStart}–{item.pageEnd || job.page_count}</span></div>)}</div>
              : <p className={styles.help}>{t('admin.sourceIngestion.noOutline')}</p>}
          </div>
          <div className={styles.chapterEditor}>
            <div className={styles.outlineHeading}><div><h3>{t('admin.sourceIngestion.mapSplitChapters')}</h3><p>{t('admin.sourceIngestion.chapterRangeHelp')}</p></div></div>
            <div className={styles.mappingList}>
              {mapping.map(row => <div key={row.key} className={styles.mappingRow}>
                <label className={styles.include}><input type="checkbox" checked={row.included} onChange={e => updateMapping(row.key, { included: e.target.checked })} /><span>{t('admin.sourceIngestion.include')}</span></label>
                <label>{t('admin.sourceIngestion.detectedChapterTitle')}<input value={row.title} onChange={e => updateMapping(row.key, { title: e.target.value })} /></label>
                <label>{t('admin.sourceIngestion.curriculumChapter')}
                  <select value={row.chapterId} onChange={e => updateMapping(row.key, { chapterId: e.target.value })}>
                    <option value="">{t('admin.sourceIngestion.chooseMapping')}</option>
                    {chapterOptions.map(ch => <option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}
                  </select>
                </label>
                <label>{t('admin.sourceIngestion.startPage')}<input type="number" min="1" max={job.page_count || 2000} value={row.start} onChange={e => updateMapping(row.key, { start: e.target.value })} /></label>
                <label>{t('admin.sourceIngestion.endPage')}<input type="number" min={row.start || 1} max={job.page_count || 2000} value={row.end} onChange={e => updateMapping(row.key, { end: e.target.value })} /></label>
              </div>)}
            </div>
            <div className={styles.actions}>
              <p>{t('admin.sourceIngestion.privateReviewHelp')}</p>
              <button className={styles.primary} type="button" disabled={busy || chapterOptions.length === 0} onClick={() => void splitChapters()}>{busy ? t('admin.sourceIngestion.splitting') : t('admin.sourceIngestion.createChapters')}</button>
            </div>
          </div>
          {job.chapters.length > 0 && <div className={styles.reviewList}>
            <div className={styles.outlineHeading}><div><h3>{t('admin.sourceIngestion.chapterReviewQueue')}</h3><p>{t('admin.sourceIngestion.chapterReviewQueueHelp')}</p></div></div>
            {job.chapters.map(ch => <article className={styles.chapterCard} key={ch.chapter_row_id}>
              <div><b>{ch.chapter_title}</b><small>{ch.chapter_code} · source pages {ch.page_start}–{ch.page_end} · {ch.pdf_page_count} pages</small>
                <small>{t('admin.sourceIngestion.assetReviewStatus', { status: ch.pdf_review_status ? sourceStatusLabel(ch.pdf_review_status, t) : t('admin.sourceIngestion.status.REVIEW') })} · {ch.pdf_sha256 ? ch.pdf_sha256.slice(0, 16) + '…' : ''}</small>
                {ch.review_notes && <p>{ch.review_notes}</p>}
              </div>
              <span className={styles.jobStatus + ' ' + styles['status' + ch.status]}>{ch.status}</span>
              <div className={styles.chapterActions}>
                <button type="button" className={styles.secondary} onClick={() => { setPreviewChapterRow(String(ch.chapter_row_id)); setPreviewPage('1'); }}>{t('admin.sourceIngestion.preview')}</button>
                <button type="button" className={styles.approve} disabled={busy || ch.status === 'APPROVED'} onClick={() => void reviewChapter(ch, 'APPROVED')}>{t('admin.sourceIngestion.approve')}</button>
                <button type="button" className={styles.reject} disabled={busy || ch.status === 'REJECTED'} onClick={() => void reviewChapter(ch, 'REJECTED')}>{t('admin.sourceIngestion.reject')}</button>
              </div>
            </article>)}
            <div className={styles.actions}>
              <p>{t('admin.sourceIngestion.finalApprovalRules')}</p>
              <button className={styles.primary} type="button" disabled={busy || !job.chapters.length || job.chapters.some(ch => !['APPROVED', 'REJECTED'].includes(ch.status)) || !job.chapters.some(ch => ch.status === 'APPROVED')} onClick={() => void approveJob()}>{t('admin.sourceIngestion.approveBookAndChapters')}</button>
              <button className={styles.reject} type="button" disabled={busy} onClick={() => void rejectJob()}>{t('admin.sourceIngestion.rejectIngestion')}</button>
            </div>
          </div>}
        </>}
        {job.status === 'APPROVED' && <>
          <div className={styles.approvedNote}>{t('admin.sourceIngestion.approvedNotice')}</div>
          {job.book_asset_id && <div className={styles.metadataReview}>
            <div><b>{t('admin.sourceIngestion.searchableText')}</b>
              <p>{t('admin.sourceIngestion.searchableTextHelp')}</p>
              {extractionStatus && <p role="status" aria-live="polite">
                {t('admin.sourceIngestion.extractionStatus')} <b>{sourceStatusLabel(extractionStatus.status, t)}</b>
                {typeof extractionStatus.total_pages === 'number' ? ' · ' + extractionStatus.processed_pages + '/' + extractionStatus.total_pages + ' pages processed' : ''}
                {typeof extractionStatus.extracted_pages === 'number' ? ' · ' + extractionStatus.extracted_pages + ' pages with text' : ''}
                {typeof extractionStatus.ocr_pages === 'number' ? ' · ' + extractionStatus.ocr_pages + ' OCR pages' : ''}
                {typeof extractionStatus.failed_pages === 'number' ? ' · ' + extractionStatus.failed_pages + ' failed pages' : ''}
              </p>}
              {extractionStatus?.last_error && <p className={styles.jobError}>{extractionStatus.last_error}</p>}
            </div>
            <div className={styles.chapterActions}>
              <button className={styles.primary} type="button" disabled={busy || ['PENDING','RUNNING'].includes(extractionStatus?.status || '')} onClick={() => void queueTextbookExtraction()}>
                {extractionStatus?.status === 'COMPLETE' ? t('admin.sourceIngestion.recheckExtraction') : t('admin.sourceIngestion.extractText')}
              </button>
              <button className={styles.secondary} type="button" disabled={busy || !extractionStatus} onClick={() => void refreshTextbookExtraction()}>{t('admin.sourceIngestion.refreshExtractionStatus')}</button>
            </div>
          </div>}
        </>}
        {job.status === 'REJECTED' && <div className={styles.empty}>{t('admin.sourceIngestion.rejectedNotice')}</div>}
      </section>}
    </section>
  </main>;
}
