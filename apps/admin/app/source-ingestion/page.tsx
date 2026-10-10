'use client';

import AdminSidebar from '../components/AdminSidebar';
import { useCallback, useEffect, useMemo, useState } from 'react';
import styles from './SourceIngestion.module.css';

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
  if (!response.ok) throw new Error(body.detail || body.message || body.error || ('Request failed (' + response.status + ')'));
  return body;
}
function normalizeJob(value: any): Job {
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
  const [sources, setSources] = useState<Source[]>([]);
  const [registryBooks, setRegistryBooks] = useState<RegistryBook[]>([]);
  const [registryBookGaps, setRegistryBookGaps] = useState<RegistryBookGap[]>([]);
  const [registryMedium, setRegistryMedium] = useState<'hindi' | 'english'>('hindi');
  const [selectedRegistryBook, setSelectedRegistryBook] = useState('');
  const [confirmSubjectMapping, setConfirmSubjectMapping] = useState(false);
  const [existingMappings, setExistingMappings] = useState<ExistingMapping[]>([]);
  const [mappingError, setMappingError] = useState('');
  const [tracks, setTracks] = useState<Track[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
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
        api('/api/v1/admin/source-ingestion/sources'),
        api('/api/v1/admin/source-ingestion/jobs'),
        api('/api/v1/curriculum'),
        api('/api/v1/admin/source-ingestion/registry-books?medium=both').catch(() => []),
        api('/api/v1/admin/source-ingestion/registry-book-gaps?medium=both').catch(() => []),
      ]);
      const sourceData = Array.isArray(sourceRows) ? sourceRows : [];
      const jobData = Array.isArray(jobRows) ? jobRows : [];
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
      setError(e instanceof Error ? e.message : 'Source ingestion data could not be loaded.');
    } finally { setLoading(false); }
  }, [classCode, subjectCode, selectedSource]);

  const loadJob = useCallback(async (id: number) => {
    const item = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + id));
    setJob(item);
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
        if (active) setMappingError(e instanceof Error ? e.message : 'Existing chapter-source mappings could not be loaded.');
      });
    return () => { active = false; };
  }, [selectedSource]);

  async function startJob() {
    if (!classCode || !subjectCode) { setError('Choose a class and subject first.'); return; }
    if (!sourceTitle.trim() || !sourceUrl.trim()) { setError('Add the source title and official HTTPS URL.'); return; }
    if (!/^https:\/\//i.test(sourceUrl.trim())) { setError('Source downloads must use HTTPS.'); return; }
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
      setNotice('Ingestion job #' + created.job_id + ' started. Refresh its status after the download finishes.');
      await loadBase();
      setJob(created);
      if (created.status === 'REVIEW') await loadJob(created.job_id);
      else setJob(created);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The source ingestion could not be started.');
    } finally { setBusy(false); }
  }

  async function startCachedBookJob() {
    if (!classCode || !subjectCode) { setError('Choose a curriculum class and subject first.'); return; }
    if (!selectedRegistryBookRow) { setError('No complete cached book is available for this class and medium.'); return; }
    if (!confirmSubjectMapping) { setError('Confirm that this complete textbook belongs to the selected curriculum class and subject.'); return; }
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
        ? 'This GHCR textbook has already completed source review for the selected track.'
        : created.status === 'REVIEW'
          ? 'Complete book opened from GHCR. Inspect its detected outline, map curriculum chapters and review every split before approval.'
          : 'Created source-ingestion job #' + created.job_id + ' from the checksummed GHCR complete book. Refresh status after PDF inspection finishes.');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The cached whole-book ingestion could not be started.');
    } finally { setBusy(false); }
  }

  async function refreshJob(id?: number) {
    setBusy(true); setError('');
    try {
      await loadBase();
      const target = id || job?.job_id || jobs[0]?.job_id;
      if (target) await loadJob(target);
      setNotice('Source ingestion status refreshed.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Refresh failed.'); }
    finally { setBusy(false); }
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
      setError('Source title, official HTTPS URL, and the printed edition/reprint year or session are required before approval.');
      return;
    }
    if (!/^https:\/\//i.test(sourceUrl.trim())) {
      setError('The reviewed source URL must use HTTPS.');
      return;
    }
    if (/^(unverified|unknown|tbd|n\/a)$/i.test(edition.trim())
        || /verify edition|to be verified|not specified/i.test(edition.trim())) {
      setError('Read the book cover or publication page and enter the printed edition, reprint year, or academic session.');
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
      setNotice('Official source metadata saved. You can continue chapter mapping and review.');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Source metadata could not be saved.');
    } finally { setBusy(false); }
  }

  async function splitChapters() {
    if (!job) return;
    const selected = mapping.filter(row => row.included);
    if (!selected.length) { setError('Select at least one chapter to split.'); return; }
    if (selected.some(row => !row.chapterId || !row.start || !row.end)) {
      setError('Every selected row needs a curriculum chapter and start/end PDF page.'); return;
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
      setNotice('Chapter PDFs have been created as private review candidates. Approve or reject each one below.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Chapter PDFs could not be prepared.'); }
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
      setNotice(row.chapter_title + ' marked ' + status.toLowerCase() + '.');
      await loadBase();
    } catch (e) { setError(e instanceof Error ? e.message : 'The chapter review could not be saved.'); }
    finally { setBusy(false); }
  }

  async function approveJob() {
    if (!job) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/approve', { method: 'POST', body: '{}' }));
      setJob(updated);
      await loadBase();
      setNotice('The complete book and approved chapter PDFs are now library drafts. Rejected chapters were excluded; preview and publish each resource from Textbook library.');
    } catch (e) { setError(e instanceof Error ? e.message : 'The source book could not be approved.'); }
    finally { setBusy(false); }
  }

  async function rejectJob() {
    if (!job || !window.confirm('Reject this entire source ingestion? Its pending PDFs will remain hidden from the library.')) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = normalizeJob(await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/reject', { method: 'POST', body: '{}' }));
      setJob(updated);
      await loadBase();
      setNotice('Source ingestion rejected. Its pending PDF assets remain hidden from the library.');
    } catch (e) { setError(e instanceof Error ? e.message : 'The ingestion could not be rejected.'); }
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
          <h1>Source ingestion & chapter review</h1>
          <p>Reuse database source records, download official books, split chapter PDFs, review them, then add approved material to the library.</p>
        </div>
        <button className="admin-refresh" type="button" disabled={busy} onClick={() => void refreshJob()} title="Refresh source status">↻</button>
      </header>

      {error && <div className={styles.error} role="alert">{error}</div>}
      {notice && <div className={styles.notice} role="status">{notice}</div>}

      <section className={styles.summary}>
        <div><small>REGISTERED SOURCES</small><strong>{sources.length}</strong><span>Existing database records checked</span></div>
        <div><small>IN REVIEW</small><strong>{jobs.filter(j => j.status === 'REVIEW').length}</strong><span>Books waiting for chapter review</span></div>
        <div><small>APPROVED JOBS</small><strong>{jobs.filter(j => j.status === 'APPROVED').length}</strong><span>Added to library as drafts</span></div>
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}><div><span>STEP 01</span><h2>Choose or register a source</h2><p>The tool checks linked PDFs, stored checksums and source references before downloading again.</p></div></div>
        <div className={styles.fields}>
          <label>Source record
            <select value={selectedSource || 'new'} onChange={e => {
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
              <option value="new">Register a new subject/source</option>
              {sources.map(source => <option key={source.source_id} value={source.source_id}>
                {source.title} · {source.source_kind} {source.matching_pdf_asset_id ? '· PDF stored' : '· PDF not linked'}
              </option>)}
            </select>
          </label>
          <label>Class
            <select value={classCode} onChange={e => {
              const value = e.target.value; setClassCode(value);
              setSubjectCode(tracks.find(t => t.class_code === value)?.subject_code || '');
              setConfirmSubjectMapping(false);
            }}>
              {tracksByClass.map(track => <option key={track.class_code} value={track.class_code}>{track.class_name} ({track.class_code})</option>)}
            </select>
          </label>
          <label>Subject
            <select value={subjectCode} onChange={e => { setSubjectCode(e.target.value); setConfirmSubjectMapping(false); }}>
              {subjects.map(track => <option key={track.subject_code} value={track.subject_code}>{track.subject_name}</option>)}
            </select>
          </label>
          <label>Source title
            <input value={sourceTitle} onChange={e => setSourceTitle(e.target.value)} placeholder="Title printed on the book" />
          </label>
          <label className={styles.wide}>Official source URL (catalogue page or direct PDF)
            <input type="url" value={sourceUrl} onChange={e => setSourceUrl(e.target.value)} placeholder="https://official-board.example/book.pdf" />
          </label>
          <label>Edition / session
            <input value={edition} onChange={e => setEdition(e.target.value)} placeholder="Edition or academic session" />
          </label>
          <label>Language
            <input value={language} onChange={e => setLanguage(e.target.value)} placeholder="hi / en" />
          </label>
          {selectedSource === 'new' && <>
            <label>Provider / board
              <input value={provider} onChange={e => setProvider(e.target.value)} placeholder="Board / publisher" />
            </label>
            <label>Source type
              <input value={sourceKind} onChange={e => setSourceKind(e.target.value)} placeholder="BOARD_TEXTBOOK" />
            </label>
          </>}
        </div>
        <div className={styles.actions}>
          <p>Manual website downloads are limited to the private library's 50 MiB per-file limit. Use the GHCR complete-book cache above for larger books; its full PDF is reviewed from disk and only approved chapter PDFs are copied into PostgreSQL.</p>
          <button className={styles.primary} type="button" disabled={busy || !sourceTitle.trim() || !sourceUrl.trim() || !subjectCode} onClick={() => void startJob()}>
            {busy ? 'Working…' : 'Check database & start download'}
          </button>
        </div>
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}>
          <div><span>GHCR CACHE</span><h2>Use a persistent complete textbook</h2>
            <p>These books are already stored in the Hindi/English GHCR images. The selected whole book is checksum-verified and reviewed from the mounted cache; the source website is not downloaded again.</p>
          </div>
          <span className={styles.bigStatus}>{registryBooks.length} indexed books</span>
        </div>
        <div className={styles.fields}>
          <label>Medium
            <select value={registryMedium} onChange={e => { setRegistryMedium(e.target.value as 'hindi' | 'english'); setConfirmSubjectMapping(false); }}>
              <option value="hindi">Hindi</option>
              <option value="english">English</option>
            </select>
          </label>
          <label>Complete textbook for {activeTrack?.class_name || 'selected class'}
            <select value={selectedRegistryBookRow?.book_id || ''} onChange={e => { setSelectedRegistryBook(e.target.value); setConfirmSubjectMapping(false); }}
              disabled={!registryBookOptions.length}>
              {registryBookOptions.length === 0 && <option value="">No cached books for this class/medium</option>}
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
            <span>I checked this book and confirm it belongs to {activeTrack?.class_name || classCode} · {subjects.find(t => t.subject_code === subjectCode)?.subject_name || subjectCode}.</span>
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
          <div><span>SOURCE AVAILABILITY</span><h2>Unavailable books and missing chapters</h2>
            <p>This inventory is read-only. Chapter numbers detected in rejected/incomplete official bundles are for planning only; those partial bundles are not cached or available to students. These rows cannot be ingested as complete textbooks.</p>
          </div>
          <span className={styles.bigStatus}>{registryBookGapRows.length} gaps</span>
        </div>
        {registryBookGapRows.length === 0
          ? <p className={styles.empty}>No reported source gaps for Class {selectedGrade || '—'} · {registryMedium}. This means no gap was recorded in the currently synced cache indexes; refresh the cache inventory to see newer source reports.</p>
          : <div className={styles.gapList}>
              {registryBookGapRows.map((gap) => <article className={styles.gapCard} key={gap.medium + ':' + gap.class + ':' + gap.book_id}>
                <div className={styles.gapTop}>
                  <div><b>{gap.title}</b><small>{gap.book_id} · {gap.publisher || 'Official source'} · {gap.subject || 'Subject unspecified'}</small></div>
                  <span className={styles.gapBadge}>{gap.status.replaceAll('_', ' ').toUpperCase()}</span>
                </div>
                <div className={styles.gapCoverage}>
                  <span><b>Chapters detected in rejected bundle (not cached):</b> {gap.available_chapters?.length ? gap.available_chapters.join(', ') : 'None verified'}</span>
                  <span><b>Missing chapter numbers:</b> {gap.missing_chapters?.length ? gap.missing_chapters.join(', ') : 'Not determined by source'}</span>
                  {gap.expected_chapters?.length ? <span><b>Expected chapters:</b> {gap.expected_chapters.join(', ')}</span> : null}
                </div>
                {gap.note && <p>{gap.note}</p>}
                {gap.last_error && <p className={styles.gapError}>{gap.last_error}</p>}
                {gap.source_url && <a href={gap.source_url} target="_blank" rel="noreferrer">Open official source or bundle URL</a>}
              </article>)}
            </div>}
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}><div><span>DATABASE CHECK</span><h2>Existing chapter/source records</h2>
          <p>Compare stored chapter mappings with any chapter PDFs already associated with the registered source before processing a new download.</p></div>
          <span className={styles.bigStatus}>{existingMappings.length} mappings</span>
        </div>
        {mappingError && <div className={styles.error} role="alert">{mappingError}</div>}
        {selectedSource === 'new' || !selectedSource
          ? <p className={styles.empty}>Select an existing source record above to inspect its chapter_source relationships.</p>
          : existingMappings.length === 0
            ? <p className={styles.empty}>No chapter_source mappings are registered for this source yet. A new book download can create mappings after the chapter PDFs are reviewed and approved.</p>
            : <div className={styles.existingMappingList}>{existingMappings.map(row => <article className={styles.existingMapping} key={row.chapter_source_id}>
              <div className={styles.mappingIdentity}>
                <b>{row.chapter_code} — {row.chapter_name}</b>
                <small>Mapping: {row.coverage_status} · {row.coverage_type}</small>
                {row.source_chapter_title && <small>Source chapter: {row.source_chapter_title}{row.source_chapter_no ? ' · No. ' + row.source_chapter_no : ''}</small>}
                {row.source_locator && <small>Source locator: {row.source_locator}</small>}
                {row.notes && <p>{row.notes}</p>}
              </div>
              <div className={styles.mappingAsset}>
                {row.pdf_asset_id
                  ? <><b>{row.pdf_title || row.document_title || 'Stored chapter PDF'}</b>
                    <span>{row.document_status || 'No document status'} · {row.pdf_page_count || '—'} pages · Asset #{row.pdf_asset_id}</span>
                    <span>Asset review: {row.pdf_review_status || 'unknown'}</span>
                    {row.document_source_url && <a href={row.document_source_url} target="_blank" rel="noreferrer">Open recorded source URL</a>}
                  </>
                  : <><b>No source-linked chapter PDF detected</b><span>Mapping metadata exists, but no chapter PDF could be confidently linked to this source. Verify the URL and process the book above.</span></>}
              </div>
            </article>)}</div>}
      </section>

      <section className={styles.panel}>
        <div className={styles.heading}><div><span>STEP 02</span><h2>Ingestion jobs</h2><p>Open a job to review the book, detected chapter headings, page ranges and chapter-wise PDFs.</p></div>
          <button className={styles.secondary} type="button" disabled={busy} onClick={() => void refreshJob()}>Refresh status</button>
        </div>
        {loading ? <p className={styles.empty}>Loading source records…</p> : jobs.length === 0 ? <p className={styles.empty}>No ingestion jobs yet. Start with a source above.</p> :
          <div className={styles.jobList}>{jobs.map(row => <button type="button" key={row.job_id}
            className={styles.jobButton + (job?.job_id === row.job_id ? ' ' + styles.selected : '')}
            onClick={() => void loadJob(row.job_id)}>
            <span className={styles.jobStatus + ' ' + styles['status' + row.status]}>{row.status}</span>
            <b>#{row.job_id} · {row.source_title}</b>
            <small>{row.class_name} · {row.subject_name} · {row.page_count ? row.page_count + ' pages' : 'PDF not ready'}</small>
            <small>{row.chapter_count} chapter candidates · {row.approved_chapter_count} approved · {row.rejected_chapter_count || 0} rejected</small>
            {row.error_message && <small className={styles.jobError}>{row.error_message}</small>}
          </button>)}</div>}
        {latestReviewJobs.length > 0 && <p className={styles.help}>Jobs still marked DOWNLOADING may need a minute for the PDF to be fetched and inspected. Select the job and refresh its status.</p>}
      </section>

      {job && <section className={styles.panel}>
        <div className={styles.heading}><div><span>STEP 03</span><h2>Review job #{job.job_id}: {job.source_title}</h2>
          <p>{job.class_name} · {job.subject_name} · {job.edition} · {job.page_count || '—'} pages · <b>{job.status}</b></p></div>
          <span className={styles.bigStatus}>{job.status}</span></div>
        {job.error_message && <div className={styles.error}>{job.error_message}</div>}
        {job.status === 'REVIEW' && <div className={styles.metadataReview}>
          <div><b>Source provenance</b><p>Before approving this book, verify the printed edition/reprint year or academic session in the PDF. Edit the source title, official HTTPS URL and edition in Step 01 above, then save them here.</p></div>
          <button className={styles.secondary} type="button" disabled={busy}
            onClick={() => void saveJobMetadata()}>Save verified source metadata</button>
        </div>}
        {job.status === 'DOWNLOADING' && <div className={styles.empty}>The source is being checked and downloaded. Select “Refresh status” above to see the detected PDF outline.</div>}
        {job.status === 'REVIEW' && <>
          <div className={styles.preview}>
            <div className={styles.previewBar}>
              <div><b>Private PDF preview</b><small>Review page boundaries visually before splitting.</small></div>
              <label>Preview
                <select value={previewChapterRow} onChange={e => { setPreviewChapterRow(e.target.value); setPreviewPage('1'); }}>
                  <option value="">Complete book · source pages</option>
                  {job.chapters.map(ch => <option key={ch.chapter_row_id} value={ch.chapter_row_id}>{ch.chapter_title} · split PDF</option>)}
                </select>
              </label>
              <label>Page
                <input type="number" min="1" max={previewChapterRow ? (job.chapters.find(ch => String(ch.chapter_row_id) === previewChapterRow)?.pdf_page_count || 2000) : (job.page_count || 2000)}
                  value={previewPage} onChange={e => setPreviewPage(e.target.value)} />
              </label>
            </div>
            {(job.book_asset_id || job.cache_book_id) && <img className={styles.previewImage} src={previewUrl} alt="Privately rendered PDF page preview" />}
          </div>
          <div className={styles.outline}>
            <div className={styles.outlineHeading}><div><h3>Detected chapter outline</h3><p>Bookmarks or recognizable chapter headings are suggestions, not trusted mappings. Correct them before splitting.</p></div>
              {job.detected_outline?.length > 0 && <button className={styles.secondary} type="button" onClick={applyDetectedOutline}>Use detected page ranges</button>}
            </div>
            {job.detected_outline?.length ? <div className={styles.outlineList}>{job.detected_outline.map((item, index) =>
              <div key={index}><b>{item.title}</b><span>PDF pages {item.pageStart}–{item.pageEnd || job.page_count}</span></div>)}</div>
              : <p className={styles.help}>No reliable table-of-contents bookmarks/headings were detected. Add chapter mappings and page ranges manually below.</p>}
          </div>
          <div className={styles.chapterEditor}>
            <div className={styles.outlineHeading}><div><h3>Map and split chapters</h3><p>Select only chapters present in this PDF. Ranges use 1-based PDF pages and cannot overlap.</p></div></div>
            <div className={styles.mappingList}>
              {mapping.map(row => <div key={row.key} className={styles.mappingRow}>
                <label className={styles.include}><input type="checkbox" checked={row.included} onChange={e => updateMapping(row.key, { included: e.target.checked })} /><span>Include</span></label>
                <label>Detected / chapter title<input value={row.title} onChange={e => updateMapping(row.key, { title: e.target.value })} /></label>
                <label>Curriculum chapter
                  <select value={row.chapterId} onChange={e => updateMapping(row.key, { chapterId: e.target.value })}>
                    <option value="">Choose mapping…</option>
                    {chapterOptions.map(ch => <option key={ch.chapter_id} value={ch.chapter_id}>{ch.chapter_code} — {ch.chapter_name}</option>)}
                  </select>
                </label>
                <label>Start<input type="number" min="1" max={job.page_count || 2000} value={row.start} onChange={e => updateMapping(row.key, { start: e.target.value })} /></label>
                <label>End<input type="number" min={row.start || 1} max={job.page_count || 2000} value={row.end} onChange={e => updateMapping(row.key, { end: e.target.value })} /></label>
              </div>)}
            </div>
            <div className={styles.actions}>
              <p>Splitting creates private, checksum-deduplicated chapter PDFs in review status. Students cannot see them at this stage.</p>
              <button className={styles.primary} type="button" disabled={busy || chapterOptions.length === 0} onClick={() => void splitChapters()}>{busy ? 'Splitting…' : 'Create chapter PDFs for review'}</button>
            </div>
          </div>
          {job.chapters.length > 0 && <div className={styles.reviewList}>
            <div className={styles.outlineHeading}><div><h3>Chapter PDF review queue</h3><p>Preview each split, confirm title and mapping, then approve or reject it.</p></div></div>
            {job.chapters.map(ch => <article className={styles.chapterCard} key={ch.chapter_row_id}>
              <div><b>{ch.chapter_title}</b><small>{ch.chapter_code} · source pages {ch.page_start}–{ch.page_end} · {ch.pdf_page_count} pages</small>
                <small>Asset review: {ch.pdf_review_status || 'REVIEW'} · {ch.pdf_sha256 ? ch.pdf_sha256.slice(0, 16) + '…' : ''}</small>
                {ch.review_notes && <p>{ch.review_notes}</p>}
              </div>
              <span className={styles.jobStatus + ' ' + styles['status' + ch.status]}>{ch.status}</span>
              <div className={styles.chapterActions}>
                <button type="button" className={styles.secondary} onClick={() => { setPreviewChapterRow(String(ch.chapter_row_id)); setPreviewPage('1'); }}>Preview</button>
                <button type="button" className={styles.approve} disabled={busy || ch.status === 'APPROVED'} onClick={() => void reviewChapter(ch, 'APPROVED')}>Approve</button>
                <button type="button" className={styles.reject} disabled={busy || ch.status === 'REJECTED'} onClick={() => void reviewChapter(ch, 'REJECTED')}>Reject</button>
              </div>
            </article>)}
            <div className={styles.actions}>
              <p>Final approval requires saved, verified source edition/session metadata. It adds the complete book when it fits the library limit and only approved chapter PDFs to the library as drafts. Every chapter must be explicitly approved or rejected, and at least one chapter must be approved. A publisher must then preview and publish each resource for students.</p>
              <button className={styles.primary} type="button" disabled={busy || !job.chapters.length || job.chapters.some(ch => !['APPROVED', 'REJECTED'].includes(ch.status)) || !job.chapters.some(ch => ch.status === 'APPROVED')} onClick={() => void approveJob()}>Approve book & approved chapters</button>
              <button className={styles.reject} type="button" disabled={busy} onClick={() => void rejectJob()}>Reject entire ingestion</button>
            </div>
          </div>}
        </>}
        {job.status === 'APPROVED' && <div className={styles.approvedNote}>Approved. The complete book and chapter PDFs are now in the textbook library as drafts. Use Admin → Textbook library to publish the resources when they are ready for students.</div>}
        {job.status === 'REJECTED' && <div className={styles.empty}>This ingestion was rejected and its pending PDF assets remain hidden from the reusable library.</div>}
      </section>}
    </section>
  </main>;
}
