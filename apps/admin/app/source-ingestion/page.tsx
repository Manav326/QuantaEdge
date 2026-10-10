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
type Chapter = { chapter_id: number; chapter_code: string; chapter_name: string };
type Outline = { title: string; pageStart: number; pageEnd: number };
type Candidate = {
  chapter_id: number; chapter_row_id: number; chapter_title: string; chapter_code: string;
  page_start: number; page_end: number; status: string; pdf_asset_id?: number; pdf_title?: string;
  pdf_page_count?: number; pdf_review_status?: string; pdf_sha256?: string; review_notes?: string;
};
type Job = {
  job_id: number; source_id: number; source_title: string; source_url: string; final_pdf_url?: string;
  edition: string; language: string; status: string; error_message?: string | null;
  book_asset_id?: number; book_asset_title?: string; page_count?: number; class_code: string;
  class_name: string; subject_code: string; subject_name: string; detected_outline: Outline[];
  chapters: Candidate[]; book_review_status?: string; chapter_count?: number; approved_chapter_count?: number;
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
function normalized(value: string) {
  return value.toLowerCase().replace(/chapter|अध्याय|पाठ/gi, '').replace(/[^\p{L}\p{N}]+/gu, '').trim();
}

export default function SourceIngestionPage() {
  const [sources, setSources] = useState<Source[]>([]);
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

  const loadBase = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [sourceRows, jobRows, curriculumRows] = await Promise.all([
        api('/api/v1/admin/source-ingestion/sources'),
        api('/api/v1/admin/source-ingestion/jobs'),
        api('/api/v1/curriculum'),
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
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Source ingestion data could not be loaded.');
    } finally { setLoading(false); }
  }, [classCode, subjectCode, selectedSource]);

  const loadJob = useCallback(async (id: number) => {
    const item = await api('/api/v1/admin/source-ingestion/jobs/' + id) as Job;
    setJob(item);
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

  async function startJob() {
    if (!classCode || !subjectCode) { setError('Choose a class and subject first.'); return; }
    if (!sourceTitle.trim() || !sourceUrl.trim()) { setError('Add the source title and official HTTPS URL.'); return; }
    if (!/^https:\/\//i.test(sourceUrl.trim())) { setError('Source downloads must use HTTPS.'); return; }
    setBusy(true); setError(''); setNotice('');
    try {
      const created = await api('/api/v1/admin/source-ingestion/jobs', {
        method: 'POST',
        body: JSON.stringify({
          sourceId: selectedSource === 'new' ? null : Number(selectedSource),
          classCode, subjectCode, sourceTitle: sourceTitle.trim(), sourceUrl: sourceUrl.trim(),
          edition: edition.trim() || 'UNVERIFIED', language: language.trim() || 'hi',
          provider: provider.trim(), sourceKind: sourceKind.trim() || 'BOARD_TEXTBOOK',
        }),
      }) as Job;
      setNotice('Ingestion job #' + created.job_id + ' started. Refresh its status after the download finishes.');
      await loadBase();
      setJob(created);
      if (created.status === 'REVIEW') await loadJob(created.job_id);
      else setJob(created);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The source ingestion could not be started.');
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

  async function splitChapters() {
    if (!job) return;
    const selected = mapping.filter(row => row.included);
    if (!selected.length) { setError('Select at least one chapter to split.'); return; }
    if (selected.some(row => !row.chapterId || !row.start || !row.end)) {
      setError('Every selected row needs a curriculum chapter and start/end PDF page.'); return;
    }
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/chapters', {
        method: 'POST',
        body: JSON.stringify({ chapters: selected.map(row => ({
          chapterId: Number(row.chapterId), title: row.title, pageStart: Number(row.start), pageEnd: Number(row.end),
        })) }),
      }) as Job;
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
      const updated = await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/chapters/' + row.chapter_row_id, {
        method: 'PATCH', body: JSON.stringify({ status }),
      }) as Job;
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
      const updated = await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/approve', { method: 'POST', body: '{}' }) as Job;
      setJob(updated);
      await loadBase();
      setNotice('Approved PDFs have been added to the textbook library as drafts. A publisher must still publish them for students.');
    } catch (e) { setError(e instanceof Error ? e.message : 'The source book could not be approved.'); }
    finally { setBusy(false); }
  }

  async function rejectJob() {
    if (!job || !window.confirm('Reject this entire source ingestion? Its pending PDFs will remain hidden from the library.')) return;
    setBusy(true); setError(''); setNotice('');
    try {
      const updated = await api('/api/v1/admin/source-ingestion/jobs/' + job.job_id + '/reject', { method: 'POST', body: '{}' }) as Job;
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
            }}>
              {tracksByClass.map(track => <option key={track.class_code} value={track.class_code}>{track.class_name} ({track.class_code})</option>)}
            </select>
          </label>
          <label>Subject
            <select value={subjectCode} onChange={e => setSubjectCode(e.target.value)}>
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
          <p>Only public HTTPS URLs are accepted. The downloader checks the PDF signature and limits files to 50 MB and 2,000 pages. New files stay hidden until reviewed.</p>
          <button className={styles.primary} type="button" disabled={busy || !sourceTitle.trim() || !sourceUrl.trim() || !subjectCode} onClick={() => void startJob()}>
            {busy ? 'Working…' : 'Check database & start download'}
          </button>
        </div>
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
            <small>{row.chapter_count} chapter candidates · {row.approved_chapter_count} approved</small>
            {row.error_message && <small className={styles.jobError}>{row.error_message}</small>}
          </button>)}</div>}
        {latestReviewJobs.length > 0 && <p className={styles.help}>Jobs still marked DOWNLOADING may need a minute for the PDF to be fetched and inspected. Select the job and refresh its status.</p>}
      </section>

      {job && <section className={styles.panel}>
        <div className={styles.heading}><div><span>STEP 03</span><h2>Review job #{job.job_id}: {job.source_title}</h2>
          <p>{job.class_name} · {job.subject_name} · {job.edition} · {job.page_count || '—'} pages · <b>{job.status}</b></p></div>
          <span className={styles.bigStatus}>{job.status}</span></div>
        {job.error_message && <div className={styles.error}>{job.error_message}</div>}
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
            {job.book_asset_id && <img className={styles.previewImage} src={previewUrl} alt="Privately rendered PDF page preview" />}
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
              <p>Final approval adds the complete book and approved chapter PDFs to the textbook library as drafts. Publication for students remains a separate permission-protected step.</p>
              <button className={styles.primary} type="button" disabled={busy || !job.chapters.length || job.chapters.some(ch => ch.status !== 'APPROVED')} onClick={() => void approveJob()}>Approve book & add PDFs to library</button>
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
