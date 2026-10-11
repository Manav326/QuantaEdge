'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import AdminSidebar from '../components/AdminSidebar';
import styles from './Translations.module.css';

type Row = {
  entity_type: string;
  translation_id: number | null;
  entity_id: number;
  language: 'hi' | 'en';
  source_hash: string;
  version_no: number;
  translation_status: string;
  translation_method: string;
  model_name?: string | null;
  quality_notes?: string | null;
  created_at?: string;
  updated_at?: string;
  reviewed_at?: string | null;
  published_at?: string | null;
  class_code?: string | null;
  subject_code?: string | null;
  chapter_code?: string | null;
  chapter_name?: string | null;
  lesson_title?: string | null;
  source_content_text?: string;
  translated_content_text?: string;
  current_source_hash?: string;
  is_current_revision?: boolean;
};

type Identity = { role?: string; displayName?: string; permissions?: string[]; staffId?: number };
type ApiResult<T> = T & { message?: string; detail?: string };

const ENTITY_LABELS: Record<string, string> = {
  curriculum_class: 'Class name',
  curriculum_program: 'Curriculum program',
  curriculum_subject: 'Subject name',
  curriculum_chapter: 'Chapter',
  learning_objective: 'Learning objective',
  lesson: 'Lesson title and summary',
  lesson_block: 'Teaching block',
  question: 'Question and options',
  assessment: 'Test title and description',
  assessment_question: 'Test question',
  learning_class_session: 'Live class',
  recorded_class: 'Recorded class'
};
const STATUS_OPTIONS = ['ALL', 'DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'REJECTED', 'SUPERSEDED'];
const ENTITY_OPTIONS = Object.keys(ENTITY_LABELS);

function pretty(value: unknown) {
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
  }
  return JSON.stringify(value ?? {}, null, 2);
}
function parseJson(value: string) {
  try {
    const parsed = JSON.parse(value);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error('Translation content must be a JSON object.');
    }
    return parsed as Record<string, unknown>;
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error('The translation JSON is invalid. Fix the JSON syntax before saving.');
    throw error;
  }
}
function errorMessage(body: any, status: number) {
  return body?.detail || body?.message || body?.error || ('Request failed (' + status + ').');
}
async function api<T = any>(url: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers || {});
  if (init.body) headers.set('Content-Type', 'application/json');
  const response = await fetch(url, { ...init, headers, credentials: 'same-origin', cache: 'no-store' });
  const raw = await response.text();
  let body: any = {};
  try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
  if (!response.ok) throw new Error(errorMessage(body, response.status));
  return body as T;
}
function rowKey(row: Row) { return row.entity_type + ':' + (row.translation_id == null ? 'source:' + row.entity_id + ':' + row.language : row.translation_id); }
function contextLabel(row: Row) {
  return [
    row.class_code ? 'Class ' + row.class_code : '',
    row.subject_code ? row.subject_code === 'maths' ? 'Mathematics' : row.subject_code === 'science' ? 'Science' : row.subject_code : '',
    row.chapter_name || '',
    row.lesson_title || ''
  ].filter(Boolean).join(' · ') || (ENTITY_LABELS[row.entity_type] || row.entity_type) + ' #' + row.entity_id;
}
function badgeClass(status: string) {
  const classes: Record<string, string> = {
    NOT_STARTED: styles.statusNOTSTARTED,
    DRAFT: styles.statusDRAFT,
    IN_REVIEW: styles.statusINREVIEW,
    APPROVED: styles.statusAPPROVED,
    PUBLISHED: styles.statusPUBLISHED,
    REJECTED: styles.statusREJECTED,
    SUPERSEDED: styles.statusSUPERSEDED
  };
  return classes[status] || styles.statusDefault;
}

export default function TranslationsPage() {
  const [identity, setIdentity] = useState<Identity | null>(null);
  const [items, setItems] = useState<Row[]>([]);
  const [total, setTotal] = useState(0);
  const [language, setLanguage] = useState('ALL');
  const [status, setStatus] = useState('ALL');
  const [entityType, setEntityType] = useState('ALL');
  const [classCode, setClassCode] = useState('ALL');
  const [subjectCode, setSubjectCode] = useState('ALL');
  const [offset, setOffset] = useState(0);
  const [selectedKey, setSelectedKey] = useState('');
  const [editor, setEditor] = useState('');
  const [qualityNotes, setQualityNotes] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const pageSize = 100;
  const loadSequence = useRef(0);

  const isAdmin = identity?.role === 'ADMIN';
  const permissions = Array.isArray(identity?.permissions) ? identity.permissions : [];
  const hasPermission = (permission: string) => Boolean(isAdmin || permissions.includes(permission));
  const canEdit = hasPermission('CONTENT_EDIT');
  const canSubmit = hasPermission('CONTENT_SUBMIT') || hasPermission('CONTENT_REVIEW');
  const canReview = hasPermission('CONTENT_REVIEW');
  const canPublish = hasPermission('CONTENT_PUBLISH');
  const selected = useMemo(() => items.find(row => rowKey(row) === selectedKey) || null, [items, selectedKey]);

  const load = useCallback(async (nextOffset = 0, clearSelection = false): Promise<Row[]> => {
    const sequence = ++loadSequence.current;
    setLoading(true);
    setError('');
    if (clearSelection) {
      setItems([]);
      setTotal(0);
      setSelectedKey('');
      setEditor('');
      setQualityNotes('');
    }
    try {
      const params = new URLSearchParams({
        language, status, entityType,
        classCode: classCode === 'ALL' ? '' : classCode,
        subjectCode: subjectCode === 'ALL' ? '' : subjectCode,
        limit: String(pageSize), offset: String(nextOffset)
      });
      const body = await api<ApiResult<{ items: Row[]; total: number; limit: number; offset: number }>>('/api/v1/admin/translations?' + params.toString());
      if (sequence !== loadSequence.current) return [];
      const existingItems = Array.isArray(body.items) ? body.items : [];
      const existingTotal = Number(body.total || 0);
      let nextItems = existingItems;
      let combinedTotal = existingTotal;

      // Existing translation versions are listed first. When the page has room,
      // fill it with source content that still has no translation for this target language.
      if (status === 'ALL') {
        const inventoryLimit = Math.max(1, pageSize - existingItems.length);
        const inventoryOffset = Math.max(0, nextOffset + existingItems.length - existingTotal);
        const inventoryParams = new URLSearchParams({
          language, entityType,
          classCode: classCode === 'ALL' ? '' : classCode,
          subjectCode: subjectCode === 'ALL' ? '' : subjectCode,
          limit: String(inventoryLimit), offset: String(inventoryOffset)
        });
        const inventory = await api<ApiResult<{ items: Row[]; total: number; limit: number; offset: number }>>(
          '/api/v1/admin/translations/inventory?' + inventoryParams.toString()
        );
        if (sequence !== loadSequence.current) return [];
        const sourceItems = Array.isArray(inventory.items) ? inventory.items : [];
        if (existingItems.length < pageSize) nextItems = [...existingItems, ...sourceItems];
        combinedTotal = existingTotal + Number(inventory.total || 0);
      }

      if (sequence !== loadSequence.current) return [];
      setItems(nextItems);
      setTotal(combinedTotal);
      setOffset(nextOffset);
      return nextItems;
    } catch (e) {
      if (sequence === loadSequence.current) {
        setItems([]);
        setTotal(0);
        setError(e instanceof Error ? e.message : 'Translation review could not be loaded.');
      }
      return [];
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [language, status, entityType, classCode, subjectCode]);

  useEffect(() => {
    let active = true;
    (async () => {
      try {
        const me = await api<Identity>('/api/v1/auth/me');
        if (!active) return;
        if (!me.staffId) { window.location.assign('/login'); return; }
        if (me.role !== 'ADMIN' && !(me.permissions || []).some(p => ['CONTENT_VIEW', 'CONTENT_REVIEW', 'CONTENT_EDIT'].includes(p))) {
          window.location.assign('/login');
          return;
        }
        setIdentity(me);
      } catch (e) {
        if (active) setError(e instanceof Error ? e.message : 'Translation review could not be loaded.');
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => { active = false; };
  }, []);

  useEffect(() => {
    if (!identity) return;
    void load(0, true);
  }, [identity, language, status, entityType, classCode, subjectCode, load]);

  function selectRow(row: Row) {
    setSelectedKey(rowKey(row));
    setEditor(pretty(row.translated_content_text ?? row.source_content_text));
    setQualityNotes(row.quality_notes || '');
    setNotice('');
    setError('');
  }
  async function saveDraft() {
    if (!selected || saving || (!canEdit && !canReview)) return;
    let translatedContent: Record<string, unknown>;
    try { translatedContent = parseJson(editor); }
    catch (e) { setError(e instanceof Error ? e.message : 'Invalid translation JSON.'); return; }
    setSaving(true); setError(''); setNotice('');
    try {
      const isNewSource = selected.translation_id == null;
      const draftUrl = isNewSource
        ? '/api/v1/admin/translations/' + selected.entity_type + '/' + selected.entity_id + '/draft?language=' + encodeURIComponent(selected.language)
        : '/api/v1/admin/translations/' + selected.entity_type + '/' + selected.translation_id + '/draft';
      const result = await api<{ translationId: number; translationStatus: string; createdNewVersion: boolean; versionNo?: number }>(
        draftUrl,
        { method: isNewSource ? 'POST' : 'PATCH', body: JSON.stringify({ translatedContent, qualityNotes }) }
      );
      setNotice(isNewSource
        ? 'Translation draft created. Student delivery is unchanged until review and publication are complete.'
        : result.createdNewVersion
          ? 'Saved as a new draft version. The previously approved or published version is unchanged.'
          : 'Draft saved. Student delivery is unchanged until review and publication are complete.');
      const nextItems = await load(selected.translation_id == null ? 0 : offset, false);
      const saved = nextItems.find(row => row.entity_type === selected.entity_type && row.translation_id === result.translationId);
      const savedRow: Row = saved || {
        ...selected,
        translation_id: result.translationId,
        version_no: Number(result.versionNo || 1),
        translation_status: 'DRAFT',
        translation_method: 'HUMAN',
        translated_content_text: JSON.stringify(translatedContent),
        is_current_revision: true
      };
      if (!saved) setItems(current => [savedRow, ...current.filter(row => rowKey(row) !== rowKey(savedRow))].slice(0, pageSize));
      setSelectedKey(rowKey(savedRow));
      setEditor(pretty(savedRow.translated_content_text));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The draft could not be saved.');
    } finally { setSaving(false); }
  }
  async function changeStatus(next: string) {
    if (!selected || saving) return;
    setSaving(true); setError(''); setNotice('');
    try {
      await api('/api/v1/admin/translations/' + selected.entity_type + '/' + selected.translation_id + '/status',
        { method: 'PATCH', body: JSON.stringify({ status: next, qualityNotes }) });
      setNotice(next === 'PUBLISHED'
        ? 'Translation published. The previous published version for this source revision was superseded.'
        : next === 'APPROVED'
          ? 'Translation approved. It is still not student-visible until it is published.'
          : next === 'IN_REVIEW'
            ? 'Translation submitted for review.'
            : 'Translation status updated.');
      setSelectedKey('');
      await load(offset, true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'The translation status could not be changed.');
    } finally { setSaving(false); }
  }

  const pending = items.filter(row => row.translation_status === 'IN_REVIEW').length;
  const stale = items.filter(row => row.is_current_revision === false).length;

  return <main className={styles.page}>
    <AdminSidebar active="translations" variant="content" displayName={identity?.displayName || 'Content team'} />
    <section className={styles.main}>
      <header className={styles.header}>
        <div>
          <span className={styles.kicker}>QUANTAEDGE · CONTENT GOVERNANCE</span>
          <h1>Language & translation review</h1>
          <p>Review contextual Hindi and English editions without changing the source curriculum or answer keys.</p>
        </div>
        <Link className={styles.backLink} href="/content">← Content Studio</Link>
      </header>

      <div className={styles.statGrid}>
        <article><span>Matching queue items</span><strong>{total.toLocaleString('en-IN')}</strong><small>Existing translations and sources not yet translated</small></article>
        <article><span>Awaiting review on this page</span><strong>{pending}</strong><small>IN_REVIEW</small></article>
        <article><span>Stale source versions on this page</span><strong>{stale}</strong><small>Cannot be approved or published</small></article>
      </div>

      <section className={styles.filters} aria-label="Translation filters">
        <label>Target language<select value={language} onChange={e => {setLanguage(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">All languages</option><option value="hi">Hindi · हिन्दी</option><option value="en">English</option></select></label>
        <label>Workflow status<select value={status} onChange={e => {setStatus(e.target.value);setOffset(0);setSelectedKey('');}}>{STATUS_OPTIONS.map(value => <option value={value} key={value}>{value === 'ALL' ? 'All content & translations' : value.replaceAll('_', ' ')}</option>)}</select></label>
        <label>Content type<select value={entityType} onChange={e => {setEntityType(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">All content types</option>{ENTITY_OPTIONS.map(value => <option value={value} key={value}>{ENTITY_LABELS[value]}</option>)}</select></label>
        <label>Class<select value={classCode} onChange={e => {setClassCode(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">All classes</option><option value="6">Class 6</option><option value="7">Class 7</option><option value="8">Class 8</option></select></label>
        <label>Subject<select value={subjectCode} onChange={e => {setSubjectCode(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">All subjects</option><option value="maths">Mathematics</option><option value="science">Science</option></select></label>
        <button type="button" className={styles.reset} onClick={() => {setLanguage('ALL');setStatus('ALL');setEntityType('ALL');setClassCode('ALL');setSubjectCode('ALL');setOffset(0);setSelectedKey('');setNotice('');}}>Reset filters</button>
        <button type="button" className={styles.refresh} onClick={() => void load(0, false)} disabled={loading}>↻ Refresh list</button>
      </section>

      {(error || notice) && <div className={error ? styles.error : styles.notice} role={error ? 'alert' : 'status'}>{error || notice}</div>}

      <div className={styles.workspace}>
        <section className={styles.listPanel}>
          <div className={styles.panelHeader}><div><h2>Translation queue</h2><p>{total.toLocaleString('en-IN')} matching items</p></div><span>{loading ? 'Loading…' : (total ? Math.min(offset + 1, total) + '–' + Math.min(offset + items.length, total) : '0–0')}</span></div>
          <div className={styles.rows}>
            {!loading && items.length === 0 && <div className={styles.empty}><strong>No content matches these filters</strong><p>Try another class, subject, language, or reset the filters. Source content is included when no current translation exists.</p></div>}
            {items.map(row => <button type="button" key={rowKey(row)} className={styles.row + (selectedKey === rowKey(row) ? ' ' + styles.rowSelected : '')} onClick={() => selectRow(row)}>
              <div className={styles.rowTop}><span className={styles.language}>{row.language === 'hi' ? 'हिन्दी' : 'English'}</span><span className={badgeClass(row.translation_status)}>{row.translation_status.replaceAll('_', ' ')}</span></div>
              <strong>{ENTITY_LABELS[row.entity_type] || row.entity_type}</strong>
              <small>{contextLabel(row)}</small>
              <span className={styles.rowMeta}>{row.translation_id == null
                ? '#' + row.entity_id + ' · Source · no translation yet'
                : '#' + row.entity_id + ' · v' + row.version_no + ' · ' + (row.translation_method === 'AI' ? 'AI draft' : 'Human edited')}</span>
              {row.is_current_revision === false && <span className={styles.staleFlag}>Source has changed</span>}
            </button>)}
          </div>
          <footer className={styles.pager}>
            <button type="button" onClick={() => void load(Math.max(0, offset - pageSize), false)} disabled={offset === 0 || loading}>← Previous</button>
            <span>{total ? Math.floor(offset / pageSize) + 1 + ' / ' + Math.ceil(total / pageSize) : '0 / 0'}</span>
            <button type="button" onClick={() => void load(offset + pageSize, false)} disabled={offset + pageSize >= total || loading}>Next →</button>
          </footer>
        </section>

        <section className={styles.editorPanel}>
          {!selected ? <div className={styles.editorEmpty}><span>文 / Aa</span><h2>Select a translation</h2><p>Source content and translation JSON will appear here. Save creates or updates a draft; only reviewed and published versions reach students.</p></div> : <>
            <div className={styles.editorHeader}>
              <div><span className={styles.kicker}>{selected.language === 'hi' ? 'HINDI · हिन्दी' : 'ENGLISH'} / VERSION {selected.version_no}</span><h2>{ENTITY_LABELS[selected.entity_type] || selected.entity_type}</h2><p>{contextLabel(selected)} · entity #{selected.entity_id}</p></div>
              <span className={badgeClass(selected.translation_status)}>{selected.translation_status.replaceAll('_', ' ')}</span>
            </div>
            {selected.is_current_revision === false && <div className={styles.error}>This translation targets an older source revision. It cannot be approved or published; regenerate it for the current source.</div>}
            <div className={styles.revisionMeta}><span><b>Source revision</b><code>{selected.source_hash}</code></span><span><b>Model / method</b><code>{selected.model_name || selected.translation_method || 'Imported'}</code></span></div>
            <div className={styles.jsonGrid}>
              <label><span>Original source (read-only)</span><textarea readOnly value={pretty(selected.source_content_text)} spellCheck={false} /></label>
              <label><span>Translated version (editable JSON)</span><textarea value={editor} onChange={e => setEditor(e.target.value)} disabled={saving || (!canEdit && !canReview)} spellCheck={false} /></label>
            </div>
            <label className={styles.notes}><span>Reviewer notes / quality observations</span><textarea value={qualityNotes} maxLength={2000} onChange={e => setQualityNotes(e.target.value)} placeholder="Terminology, mathematics, grammar, missing labels, source alignment…" disabled={saving || (!canEdit && !canReview)} rows={3}/></label>
            {selected.translation_id == null && <div className={styles.notice}><strong>Translation not started</strong> This is the original source content. Edit the right-hand JSON in ${selected.language === 'hi' ? 'Hindi (Devanagari)' : 'English'} and save it as a draft. It will not be shown to students until approved and published.</div>}
            <div className={styles.workflowHint}><strong>Safe publication path</strong><span>Save draft → Submit for review → Approve → Publish. Publishing is blocked for stale source revisions, invalid structure or missing reviewer/publisher metadata.</span></div>
            <div className={styles.actions}>
              <button type="button" onClick={() => void saveDraft()} disabled={saving || (!canEdit && !canReview) || selected.is_current_revision === false}>{saving ? 'Saving…' : 'Save draft'}</button>
              {['DRAFT', 'REJECTED'].includes(selected.translation_status) && canSubmit && <button type="button" className={styles.secondary} onClick={() => void changeStatus('IN_REVIEW')} disabled={saving || selected.is_current_revision === false}>Submit for review</button>}
              {selected.translation_status === 'IN_REVIEW' && canReview && <>
                <button type="button" className={styles.secondary} onClick={() => void changeStatus('REJECTED')} disabled={saving || selected.is_current_revision === false}>Reject with notes</button>
                <button type="button" onClick={() => void changeStatus('APPROVED')} disabled={saving || selected.is_current_revision === false}>Approve</button>
              </>}
              {selected.translation_status === 'APPROVED' && canPublish && <button type="button" onClick={() => void changeStatus('PUBLISHED')} disabled={saving || selected.is_current_revision === false}>Publish translation</button>}
              {selected.translation_status === 'PUBLISHED' && <span className={styles.publishedNote}>Live translation · save edits as a new draft version.</span>}
            </div>
          </>}
        </section>
      </div>
    </section>
  </main>;
}
