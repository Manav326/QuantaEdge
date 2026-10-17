'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import AdminSidebar from '../components/AdminSidebar';
import { useUiTranslation } from '../i18n/useUiTranslation';
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

const ENTITY_MESSAGE_KEYS: Record<string, string> = {
  curriculum_class: 'admin.translations.entity.className',
  curriculum_program: 'admin.translations.entity.curriculumProgram',
  curriculum_subject: 'admin.translations.entity.subjectName',
  curriculum_chapter: 'admin.translations.entity.chapter',
  learning_objective: 'admin.translations.entity.learningObjective',
  lesson: 'admin.translations.entity.lesson',
  lesson_block: 'admin.translations.entity.teachingBlock',
  question: 'admin.translations.entity.question',
  assessment: 'admin.translations.entity.assessment',
  assessment_question: 'admin.translations.entity.assessmentQuestion',
  learning_class_session: 'admin.translations.entity.liveClass',
  recorded_class: 'admin.translations.entity.recordedClass',
};
const STATUS_OPTIONS = ['ALL', 'DRAFT', 'IN_REVIEW', 'APPROVED', 'PUBLISHED', 'REJECTED', 'SUPERSEDED'];
const ENTITY_OPTIONS = Object.keys(ENTITY_MESSAGE_KEYS);

function pretty(value: unknown) {
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
  }
  return JSON.stringify(value ?? {}, null, 2);
}
function parseJson(value: string, t: (key: string) => string) {
  try {
    const parsed = JSON.parse(value);
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      throw new Error(t('admin.translations.invalidObject'));
    }
    return parsed as Record<string, unknown>;
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error(t('admin.translations.invalidJsonSyntax'));
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

function hasQualityWarnings(row: Row) {
  return /HUMAN REVIEW REQUIRED|PUBLISHED WITH QUALITY WARNINGS/i.test(row.quality_notes || '');
}
function entityLabel(entityType: string, t: (key: string, values?: Record<string, string | number>) => string) {
  const key = ENTITY_MESSAGE_KEYS[entityType];
  return key ? t(key) : t('admin.translations.entity.unknown', { entityType });
}
function statusLabel(status: string, t: (key: string, values?: Record<string, string | number>) => string) {
  const normalized = String(status || 'UNKNOWN').toUpperCase();
  const key = 'admin.translations.status.' + normalized;
  return t(key);
}
function contextLabel(row: Row, t: (key: string, values?: Record<string, string | number>) => string) {
  return [
    row.class_code ? t('admin.translations.classCode', { code: row.class_code }) : '',
    row.subject_code ? row.subject_code === 'maths' ? t('admin.translations.subject.maths') : row.subject_code === 'science' ? t('admin.translations.subject.science') : row.subject_code : '',
    row.chapter_name || '',
    row.lesson_title || ''
  ].filter(Boolean).join(' · ') || entityLabel(row.entity_type, t) + ' #' + row.entity_id;
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
  const t = useUiTranslation();
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
        setError(e instanceof Error ? e.message : t('admin.translations.loadFailed'));
      }
      return [];
    } finally {
      if (sequence === loadSequence.current) setLoading(false);
    }
  }, [language, status, entityType, classCode, subjectCode, t]);

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
  }, [t]);

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
    try { translatedContent = parseJson(editor, t); }
    catch (e) { setError(e instanceof Error ? e.message : t('admin.translations.invalidJson')); return; }
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
        ? t('admin.translations.draftCreated')
        : result.createdNewVersion
          ? t('admin.translations.newVersionSaved')
          : t('admin.translations.draftSaved'));
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
      setError(e instanceof Error ? e.message : t('admin.translations.saveFailed'));
    } finally { setSaving(false); }
  }
  async function changeStatus(next: string) {
    if (!selected || saving) return;
    setSaving(true); setError(''); setNotice('');
    try {
      await api('/api/v1/admin/translations/' + selected.entity_type + '/' + selected.translation_id + '/status',
        { method: 'PATCH', body: JSON.stringify({ status: next, qualityNotes }) });
      setNotice(next === 'PUBLISHED'
        ? t('admin.translations.published')
        : next === 'APPROVED'
          ? t('admin.translations.approved')
          : next === 'IN_REVIEW'
            ? t('admin.translations.submitted')
            : t('admin.translations.statusUpdated'));
      setSelectedKey('');
      await load(offset, true);
    } catch (e) {
      setError(e instanceof Error ? e.message : t('admin.translations.statusChangeFailed'));
    } finally { setSaving(false); }
  }

  const pending = items.filter(row => row.translation_status === 'IN_REVIEW').length;
  const stale = items.filter(row => row.is_current_revision === false).length;

  return <main className={styles.page}>
    <AdminSidebar active="translations" variant="content" displayName={identity?.displayName || t('common.account.staff')} />
    <section className={styles.main}>
      <header className={styles.header}>
        <div>
          <span className={styles.kicker}>{t('admin.translations.eyebrow')}</span>
          <h1>{t('admin.translations.title')}</h1>
          <p>{t('admin.translations.description')}</p>
        </div>
        <Link className={styles.backLink} href="/content">{t('admin.translations.back')}</Link>
      </header>

      <div className={styles.statGrid}>
        <article><span>{t('admin.translations.matchingItems')}</span><strong>{total.toLocaleString('en-IN')}</strong><small>{t('admin.translations.existingAndMissing')}</small></article>
        <article><span>{t('admin.translations.awaitingReview')}</span><strong>{pending}</strong><small>{statusLabel('IN_REVIEW', t)}</small></article>
        <article><span>{t('admin.translations.staleVersions')}</span><strong>{stale}</strong><small>{t('admin.translations.cannotPublishStale')}</small></article>
      </div>

      <section className={styles.filters} aria-label={t('admin.translations.filters')}>
        <label>{t('admin.translations.targetLanguage')}<select value={language} onChange={e => {setLanguage(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">{t('admin.translations.allLanguages')}</option><option value="hi">{t('admin.translations.hindiDevanagari')}</option><option value="en">{t('common.language.english')}</option></select></label>
        <label>{t('admin.translations.workflowStatus')}<select value={status} onChange={e => {setStatus(e.target.value);setOffset(0);setSelectedKey('');}}>{STATUS_OPTIONS.map(value => <option value={value} key={value}>{value === 'ALL' ? t('admin.translations.status.ALL') : statusLabel(value, t)}</option>)}</select></label>
        <label>{t('admin.translations.contentType')}<select value={entityType} onChange={e => {setEntityType(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">{t('admin.translations.allContentTypes')}</option>{ENTITY_OPTIONS.map(value => <option value={value} key={value}>{entityLabel(value, t)}</option>)}</select></label>
        <label>{t('admin.translations.class')}<select value={classCode} onChange={e => {setClassCode(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">{t('admin.translations.allClasses')}</option><option value="6">{t('admin.translations.classCode', { code: 6 })}</option><option value="7">{t('admin.translations.classCode', { code: 7 })}</option><option value="8">{t('admin.translations.classCode', { code: 8 })}</option></select></label>
        <label>{t('admin.translations.subject')}<select value={subjectCode} onChange={e => {setSubjectCode(e.target.value);setOffset(0);setSelectedKey('');}}><option value="ALL">{t('admin.translations.allSubjects')}</option><option value="maths">{t('admin.translations.subject.maths')}</option><option value="science">{t('admin.translations.subject.science')}</option></select></label>
        <button type="button" className={styles.reset} onClick={() => {setLanguage('ALL');setStatus('ALL');setEntityType('ALL');setClassCode('ALL');setSubjectCode('ALL');setOffset(0);setSelectedKey('');setNotice('');}}>{t('admin.translations.resetFilters')}</button>
        <button type="button" className={styles.refresh} onClick={() => void load(0, false)} disabled={loading}>{t('admin.translations.refreshList')}</button>
      </section>

      {(error || notice) && <div className={error ? styles.error : styles.notice} role={error ? 'alert' : 'status'}>{error || notice}</div>}

      <div className={styles.workspace}>
        <section className={styles.listPanel}>
          <div className={styles.panelHeader}><div><h2>{t('admin.translations.queue')}</h2><p>{t('admin.translations.matchingCount', { count: total.toLocaleString(locale === 'english' ? 'en-IN' : 'hi-IN') })}</p></div><span role="status" aria-live="polite">{loading ? t('admin.translations.loading') : (total ? Math.min(offset + 1, total) + '–' + Math.min(offset + items.length, total) : '0–0')}</span></div>
          <div className={styles.rows}>
            {!loading && items.length === 0 && <div className={styles.empty}><strong>{t('admin.translations.noMatch')}</strong><p>{t('admin.translations.tryFilters')}</p></div>}
            {items.map(row => <button type="button" key={rowKey(row)} className={styles.row + (selectedKey === rowKey(row) ? ' ' + styles.rowSelected : '')} onClick={() => selectRow(row)}>
              <div className={styles.rowTop}><span className={styles.language}>{row.language === 'hi' ? 'हिन्दी' : 'English'}</span><span className={badgeClass(row.translation_status)}>{statusLabel(row.translation_status, t)}</span></div>
              <strong>{entityLabel(row.entity_type, t)}</strong>
              <small>{contextLabel(row, t)}</small>
              <span className={styles.rowMeta}>{row.translation_id == null
                ? '#' + row.entity_id + ' · ' + t('admin.translations.sourceNoTranslation')
                : '#' + row.entity_id + ' · v' + row.version_no + ' · ' + (row.translation_method === 'AI' ? t('admin.translations.aiDraft') : t('admin.translations.humanEdited'))}</span>
              {hasQualityWarnings(row) && <span className={styles.qualityFlag}>{t('admin.translations.qualityWarnings')}</span>}
              {row.is_current_revision === false && <span className={styles.staleFlag}>{t('admin.translations.sourceChanged')}</span>}
            </button>)}
          </div>
          <footer className={styles.pager}>
            <button type="button" onClick={() => void load(Math.max(0, offset - pageSize), false)} disabled={offset === 0 || loading}>{t('admin.translations.previous')}</button>
            <span>{total ? Math.floor(offset / pageSize) + 1 + ' / ' + Math.ceil(total / pageSize) : '0 / 0'}</span>
            <button type="button" onClick={() => void load(offset + pageSize, false)} disabled={offset + pageSize >= total || loading}>{t('admin.translations.next')}</button>
          </footer>
        </section>

        <section className={styles.editorPanel}>
          {!selected ? <div className={styles.editorEmpty}><span>文 / Aa</span><h2>{t('admin.translations.selectTranslation')}</h2><p>{t('admin.translations.editorEmpty')}</p></div> : <>
            <div className={styles.editorHeader}>
              <div><span className={styles.kicker}>{selected.language === 'hi' ? t('admin.translations.hindiDevanagari') : t('common.language.english')} / {t('admin.translations.version', { version: selected.version_no })}</span><h2>{entityLabel(selected.entity_type, t)}</h2><p>{contextLabel(selected, t)} · entity #{selected.entity_id}</p></div>
              <span className={badgeClass(selected.translation_status)}>{statusLabel(selected.translation_status, t)}</span>
            </div>
            {selected.is_current_revision === false && <div className={styles.error}>{t('admin.translations.olderSource')}</div>}
            {hasQualityWarnings(selected) && <div className={styles.qualityWarning}><strong>{t('admin.translations.qualityWarningFlag')}</strong> {t('admin.translations.qualityWarningText')}</div>}
            <div className={styles.revisionMeta}><span><b>{t('admin.translations.sourceRevision')}</b><code>{selected.source_hash}</code></span><span><b>{t('admin.translations.modelMethod')}</b><code>{selected.model_name || selected.translation_method || t('admin.translations.imported')}</code></span></div>
            <div className={styles.jsonGrid}>
              <label><span>{t('admin.translations.sourceReadOnly')}</span><textarea readOnly value={pretty(selected.source_content_text)} spellCheck={false} /></label>
              <label><span>{t('admin.translations.translatedEditable')}</span><textarea value={editor} onChange={e => setEditor(e.target.value)} disabled={saving || (!canEdit && !canReview)} spellCheck={false} /></label>
            </div>
            <label className={styles.notes}><span>{t('admin.translations.reviewerNotes')}</span><textarea value={qualityNotes} maxLength={2000} onChange={e => setQualityNotes(e.target.value)} placeholder={t("admin.translations.notesPlaceholder")} disabled={saving || (!canEdit && !canReview)} rows={3}/></label>
            {selected.translation_id == null && <div className={styles.notice}><strong>{t('admin.translations.notStarted')}</strong> {t('admin.translations.sourceIntro')} {t('admin.translations.sourceContentOnly', { language: selected.language === 'hi' ? t('admin.translations.hindiDevanagari') : t('common.language.english') })}</div>}
            <div className={styles.workflowHint}><strong>{t('admin.translations.safePublicationPath')}</strong><span>{t('admin.translations.workflowDescription')}</span></div>
            <div className={styles.actions}>
              <button type="button" onClick={() => void saveDraft()} disabled={saving || (!canEdit && !canReview) || selected.is_current_revision === false}>{saving ? t('admin.translations.saving') : t('admin.translations.saveDraft')}</button>
              {['DRAFT', 'REJECTED'].includes(selected.translation_status) && canSubmit && <button type="button" className={styles.secondary} onClick={() => void changeStatus('IN_REVIEW')} disabled={saving || selected.is_current_revision === false}>{t('admin.translations.submitReview')}</button>}
              {selected.translation_status === 'IN_REVIEW' && canReview && <>
                <button type="button" className={styles.secondary} onClick={() => void changeStatus('REJECTED')} disabled={saving || selected.is_current_revision === false}>{t('admin.translations.rejectNotes')}</button>
                <button type="button" onClick={() => void changeStatus('APPROVED')} disabled={saving || selected.is_current_revision === false}>{t('admin.translations.approve')}</button>
              </>}
              {selected.translation_status === 'APPROVED' && canPublish && <button type="button" onClick={() => void changeStatus('PUBLISHED')} disabled={saving || selected.is_current_revision === false}>{t('admin.translations.publish')}</button>}
              {selected.translation_status === 'PUBLISHED' && <span className={styles.publishedNote}>{t('admin.translations.liveNote')}</span>}
            </div>
          </>}
        </section>
      </div>
    </section>
  </main>;
}
