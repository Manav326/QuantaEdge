'use client';

import { useCallback, useEffect, useRef, useState, type ChangeEvent } from 'react';
import { apiFetch } from './apiClient';
import RichNotificationRenderer from './RichNotificationRenderer';
import LogoGraphicsLab from './LogoGraphicsLab';
import styles from './NotificationStudio.module.css';

type Surface = 'admin' | 'hrms' | 'institution';
type Scope = 'USER' | 'COURSE' | 'INSTITUTION' | 'HRMS_PLATFORM';
type Block = Record<string, unknown> & { type: string };
type Document = { version: 1; blocks: Block[] };
type Template = {
  id: number; public_id?: string; template_name?: string; templateName?: string;
  description?: string; content_version?: number; contentVersion?: number;
  contentDocument?: Document; content_document?: string | Document; status?: string;
};
type Option = { id: number; institution_code?: string; tenant_code?: string; display_name?: string; institution_name?: string; status?: string };
type Course = { course_id: number; course_code: string; course_name: string; class_code?: string };
type Recipient = { recipient_id: number; recipient_kind: string; recipient_name: string; class_code?: string | null };
type Campaign = {
  id: number; public_id?: string; scope?: string; title?: string; summary?: string; status?: string;
  resolved_recipient_count?: number; scheduled_at?: string | null; dispatched_at?: string | null; created_at?: string;
};

const DEFAULT_DOCUMENT: Document = {
  version: 1,
  blocks: [
    { type: 'hero', eyebrow: 'IMPORTANT UPDATE', title: 'A new update for you', subtitle: 'Keep up with your learning journey.' },
    { type: 'richText', text: 'Write a useful, clear notification message here.\n\nYou can add cards, illustrations, links and other visual blocks below.' },
    { type: 'cta', label: 'Open learning', target: '/student' },
    { type: 'footer', text: 'You received this update because of your relationship with this institution or course.' }
  ]
};

const PRESETS: Array<{ name: string; summary: string; document: Document }> = [
  { name: 'Course update', summary: 'A new learning update is available.', document: DEFAULT_DOCUMENT },
  { name: 'New resource', summary: 'New learning material is ready to explore.', document: { version: 1, blocks: [
    { type: 'hero', eyebrow: 'NEW RESOURCE', title: 'Explore your new learning resource', subtitle: 'Ready when you are.' },
    { type: 'courseCard', courseName: 'Your course', description: 'Open your course to review the latest resource.', buttonLabel: 'Open course', target: '/student/courses' },
    { type: 'cta', label: 'Start learning', target: '/student/structured-lessons' }
  ]}},
  { name: 'Live class reminder', summary: 'Your upcoming live class is approaching.', document: { version: 1, blocks: [
    { type: 'hero', eyebrow: 'COMING UP', title: 'Your live class is approaching', subtitle: 'Get ready to learn together.' },
    { type: 'eventCard', title: 'Live learning session', dateLabel: 'See your current class schedule', description: 'Open Classes for the current schedule and authorized join option.', buttonLabel: 'View live classes', target: '/student/classes' }
  ]}},
  { name: 'Achievement', summary: 'Celebrate a new learning milestone.', document: { version: 1, blocks: [
    { type: 'hero', eyebrow: 'MILESTONE', title: 'Look how far you have come!', subtitle: 'Every step forward is progress.' },
    { type: 'highlight', label: 'Your next step', value: 'Keep learning', description: 'Your actual progress remains available in your account.' },
    { type: 'cta', label: 'View progress', target: '/student/progress' }
  ]}},
  { name: 'Important notice', summary: 'Please review this important notice.', document: { version: 1, blocks: [
    { type: 'badge', text: 'IMPORTANT NOTICE' },
    { type: 'hero', title: 'Please take a moment to read this', subtitle: 'The details below explain what you need to know.' },
    { type: 'richText', text: 'Add the full notice here.' },
    { type: 'footer', text: 'If action is required, please use the button above or contact your institution.' }
  ]}}
];

const BLOCK_OPTIONS = [
  ['hero', 'Hero / banner'], ['richText', 'Rich text'], ['image', 'Image / illustration'],
  ['video', 'Video'], ['animation', 'Animated graphic'], ['courseCard', 'Course card'],
  ['eventCard', 'Event card'], ['highlight', 'Highlight / statistic'], ['badge', 'Badge'],
  ['icon', 'Icon with text'], ['divider', 'Divider'], ['spacer', 'Spacing'],
  ['cta', 'Button / action'], ['footer', 'Footer note']
] as const;

function clone<T>(value: T): T { return JSON.parse(JSON.stringify(value)) as T; }
function field(template: Template, snake: keyof Template, camel?: keyof Template) {
  return template[snake] ?? (camel ? template[camel] : undefined);
}
function asDocument(value: unknown): Document | null {
  let parsed = value;
  if (typeof parsed === 'string') { try { parsed = JSON.parse(parsed); } catch { return null; } }
  if (!parsed || typeof parsed !== 'object') return null;
  const doc = parsed as Document;
  return doc.version === 1 && Array.isArray(doc.blocks) ? doc : null;
}

export default function NotificationStudio({ surface }: { surface: Surface }) {
  const prefix = surface === 'hrms' ? '/api/v1/hrms/notifications'
    : surface === 'institution' ? '/api/v1/institution/notifications'
    : '/api/v1/admin/notifications';
  const [templates, setTemplates] = useState<Template[]>([]);
  const [institutions, setInstitutions] = useState<Option[]>([]);
  const [courses, setCourses] = useState<Course[]>([]);
  const [templateId, setTemplateId] = useState('');
  const [templateVersion, setTemplateVersion] = useState<number | null>(null);
  const [templateName, setTemplateName] = useState('Course update');
  const [templateDescription, setTemplateDescription] = useState('');
  const [title, setTitle] = useState('A new update for you');
  const [summary, setSummary] = useState('A new learning update is available.');
  const [scope, setScope] = useState<Scope>(surface === 'hrms' ? 'HRMS_PLATFORM' : 'COURSE');
  const [courseId, setCourseId] = useState('');
  const [selectedInstitutionIds, setSelectedInstitutionIds] = useState<string[]>([]);
  const [allInstitutions, setAllInstitutions] = useState(false);
  const [recipientTypes, setRecipientTypes] = useState<string[]>(['STUDENT']);
  const [includeParents, setIncludeParents] = useState(false);
  const [directKind, setDirectKind] = useState('STUDENT');
  const [directQuery, setDirectQuery] = useState('');
  const [recipientOptions, setRecipientOptions] = useState<Recipient[]>([]);
  const [directRecipients, setDirectRecipients] = useState<Recipient[]>([]);
  const [document, setDocument] = useState<Document>(() => clone(DEFAULT_DOCUMENT));
  const [selectedBlock, setSelectedBlock] = useState(0);
  const [previewMode, setPreviewMode] = useState<'mobile' | 'desktop' | 'push'>('mobile');
  const [workspaceTab, setWorkspaceTab] = useState<'studio' | 'logo' | 'templates' | 'campaigns'>('studio');
  const [campaigns, setCampaigns] = useState<Campaign[]>([]);
  const [campaignLoading, setCampaignLoading] = useState(false);
  const [campaignError, setCampaignError] = useState('');
  const idempotencyRef = useRef<{ fingerprint: string; key: string } | null>(null);
  const [previewCount, setPreviewCount] = useState<number | null>(null);
  const [previewCounts, setPreviewCounts] = useState<Record<string, number>>({});
  const [previewed, setPreviewed] = useState(false);
  const [scheduledAt, setScheduledAt] = useState('');
  const [expiresAt, setExpiresAt] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [sending, setSending] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const activeBlock = document.blocks[selectedBlock] ?? null;
  const isPlatform = surface === 'hrms';

  const loadBase = useCallback(async () => {
    setLoading(true); setError('');
    try {
      const [templateResponse, optionResponse] = await Promise.all([
        apiFetch(prefix + '/templates', { cache: 'no-store' }),
        apiFetch(prefix + '/options', { cache: 'no-store' })
      ]);
      if (!templateResponse.ok) throw new Error('Notification templates could not be loaded.');
      if (!optionResponse.ok) throw new Error('Notification audience options could not be loaded.');
      const templateData = await templateResponse.json() as { templates?: Template[] };
      const optionData = await optionResponse.json() as { institutions?: Option[]; courses?: Course[] };
      setTemplates(Array.isArray(templateData.templates) ? templateData.templates : []);
      setInstitutions(Array.isArray(optionData.institutions) ? optionData.institutions : []);
      setCourses(Array.isArray(optionData.courses) ? optionData.courses : []);
    } catch (e) { setError(e instanceof Error ? e.message : 'Notification creator could not be loaded.'); }
    finally { setLoading(false); }
  }, [prefix]);

  useEffect(() => { void loadBase(); }, [loadBase]);

  const loadCampaigns = useCallback(async () => {
    setCampaignLoading(true); setCampaignError('');
    try {
      const response = await apiFetch(prefix + '/campaigns?limit=100', { cache: 'no-store' });
      if (!response.ok) throw new Error('Campaign history is not available for this account.');
      const data = await response.json() as { campaigns?: Campaign[] };
      setCampaigns(Array.isArray(data.campaigns) ? data.campaigns : []);
    } catch (error) {
      setCampaignError(error instanceof Error ? error.message : 'Campaign history could not be loaded.');
    } finally { setCampaignLoading(false); }
  }, [prefix]);

  useEffect(() => {
    if (workspaceTab === 'campaigns') void loadCampaigns();
  }, [workspaceTab, loadCampaigns]);

  function useLogoInNotification(assetId: string, fileName: string) {
    const current = document.blocks[selectedBlock];
    const accessibleName = fileName.replace(/-logo\\.png$/i, '').replace(/[-_]+/g, ' ').trim() || 'Brand logo';
    if (current && ['hero','image','animation'].includes(current.type)) {
      updateBlock('assetId', assetId);
      if (current.type === 'image' || current.type === 'animation') {
        updateBlock('alt', accessibleName + ' logo');
        updateBlock('decorative', false);
        updateBlock('fit', 'contain');
        updateBlock('aspectRatio', 'auto');
      }
    } else {
      const blocks = [...document.blocks, {
        type: 'image', assetId, alt: accessibleName + ' logo', caption: '',
        decorative: false, fit: 'contain', aspectRatio: 'auto', focalPoint: 'center'
      } as Block];
      setDocument({ ...document, blocks });
      setSelectedBlock(blocks.length - 1);
    }
    setWorkspaceTab('studio');
    setPreviewed(false);
    setPreviewCount(null);
    setNotice('Logo asset added to the notification design. Preview and validate again before sending.');
  }

  async function cancelCampaign(campaign: Campaign) {
    if (String(campaign.status).toUpperCase() !== 'SCHEDULED') return;
    if (!window.confirm('Cancel this scheduled notification before it is delivered?')) return;
    setCampaignLoading(true); setCampaignError('');
    try {
      const response = await apiFetch(prefix + '/campaigns/' + campaign.id + '/cancel', {
        method: 'POST', cache: 'no-store'
      });
      const data = await response.json().catch(() => ({})) as { message?: string };
      if (!response.ok) throw new Error(data.message || 'Scheduled notification could not be cancelled.');
      await loadCampaigns();
    } catch (error) {
      setCampaignError(error instanceof Error ? error.message : 'Scheduled notification could not be cancelled.');
    } finally { setCampaignLoading(false); }
  }

  function chooseTemplate(id: string) {
    setTemplateId(id); setPreviewed(false); setPreviewCount(null); setError(''); setNotice('');
    const row = templates.find(t => String(t.id) === id);
    if (!row) {
      setTemplateVersion(null);
      return;
    }
    const doc = asDocument(field(row, 'content_document', 'contentDocument'));
    if (doc) setDocument(clone(doc));
    setTemplateName(String(field(row, 'template_name', 'templateName') ?? 'Notification template'));
    setTemplateDescription(String(row.description ?? ''));
    setTemplateVersion(Number(field(row, 'content_version', 'contentVersion') ?? 1));
    setSelectedBlock(0);
  }

  function updateBlock(key: string, value: string | boolean) {
    setDocument(current => {
      const blocks = [...current.blocks];
      const target = { ...blocks[selectedBlock] };
      target[key] = value;
      blocks[selectedBlock] = target;
      return { ...current, blocks };
    });
    setPreviewed(false); setPreviewCount(null);
  }

  function addBlock(type: string) {
    const base: Block = type === 'hero' ? { type, eyebrow: 'NEW UPDATE', title: 'Add a headline', subtitle: 'Add a short supporting message.' }
      : type === 'richText' ? { type, text: 'Write your message here.' }
      : type === 'image' ? { type, assetId: '', alt: '', caption: '', decorative: false }
      : type === 'video' ? { type, assetId: '', alt: 'Notification video', caption: '' }
      : type === 'animation' ? { type, assetId: '', alt: '', caption: '', decorative: false }
      : type === 'courseCard' ? { type, courseName: 'Course update', description: 'Add course details.', buttonLabel: 'Open course', target: '/student/courses' }
      : type === 'eventCard' ? { type, title: 'Upcoming event', dateLabel: 'Add schedule', description: 'Add event details.', buttonLabel: 'View details', target: '/student/classes' }
      : type === 'highlight' ? { type, label: 'Your next step', value: 'Keep learning', description: 'Add context.' }
      : type === 'badge' ? { type, text: 'IMPORTANT' }
      : type === 'icon' ? { type, symbol: '✦', text: 'Add a short note.' }
      : type === 'cta' ? { type, label: 'Open details', target: '/student' }
      : type === 'footer' ? { type, text: 'You received this update from your institution or course.' }
      : { type };
    const blocks = [...document.blocks, base];
    setDocument({ ...document, blocks });
    setSelectedBlock(blocks.length - 1);
    setPreviewed(false); setPreviewCount(null);
  }

  function moveBlock(delta: number) {
    const next = selectedBlock + delta;
    if (next < 0 || next >= document.blocks.length) return;
    const blocks = [...document.blocks];
    [blocks[selectedBlock], blocks[next]] = [blocks[next], blocks[selectedBlock]];
    setDocument({ ...document, blocks });
    setSelectedBlock(next);
    setPreviewed(false); setPreviewCount(null);
  }

  function reorderBlocks(from: number, to: number) {
    if (from < 0 || from >= document.blocks.length || to < 0 || to >= document.blocks.length || from === to) return;
    const blocks = [...document.blocks];
    const moved = blocks.splice(from, 1)[0];
    blocks.splice(to, 0, moved);
    setDocument({ ...document, blocks });
    setSelectedBlock(to);
    setPreviewed(false); setPreviewCount(null);
  }

  function removeBlock() {
    if (document.blocks.length < 2) { setError('Keep at least one content block.'); return; }
    const blocks = document.blocks.filter((_, index) => index !== selectedBlock);
    setDocument({ ...document, blocks });
    setSelectedBlock(Math.max(0, selectedBlock - 1));
    setPreviewed(false); setPreviewCount(null);
  }

  async function uploadMedia(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !activeBlock) return;
    const type = String(activeBlock.type);
    if (!['image', 'video', 'animation', 'hero'].includes(type)) {
      setError('Select an image, video or hero block first.'); return;
    }
    const form = new FormData(); form.append('file', file);
    setUploading(true); setError('');
    try {
      const response = await apiFetch(prefix + '/media', { method: 'POST', body: form });
      const data = await response.json().catch(() => ({})) as { assetId?: string; ready?: boolean; contentType?: string };
      if (!response.ok || !data.assetId || !data.ready) throw new Error('Media upload or validation failed.');
      updateBlock('assetId', data.assetId);
      setNotice('Media uploaded and attached to the selected block.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Media upload failed.'); }
    finally { setUploading(false); }
  }

  async function searchRecipients() {
    if (surface === 'hrms') return;
    setError('');
    try {
      const q = new URLSearchParams({ kind: directKind, query: directQuery });
      const response = await apiFetch(prefix + '/recipients?' + q.toString(), { cache: 'no-store' });
      if (!response.ok) throw new Error('Recipient search failed.');
      const data = await response.json() as { recipients?: Recipient[] };
      setRecipientOptions(Array.isArray(data.recipients) ? data.recipients : []);
    } catch (e) { setError(e instanceof Error ? e.message : 'Recipient search failed.'); }
  }

  function audienceFilter() {
    if (scope === 'USER') return { recipients: directRecipients.map(r => ({ kind: r.recipient_kind, id: r.recipient_id })) };
    if (scope === 'HRMS_PLATFORM') return {
      allActiveInstitutions: allInstitutions,
      institutionIds: allInstitutions ? [] : selectedInstitutionIds.map(Number),
      recipientTypes
    };
    return { recipientTypes: [...new Set([...recipientTypes, ...(includeParents ? ['PARENT'] : [])])], includeParents };
  }

  function requestBody(includeSchedule: boolean) {
    const filter = audienceFilter();
    return {
      scope,
      courseId: scope === 'COURSE' ? Number(courseId) : null,
      institutionId: isPlatform ? null : undefined,
      audienceFilter: filter,
      title, summary, contentDocument: document,
      templateId: templateId ? Number(templateId) : null,
      templateVersion,
      ...(includeSchedule ? {
        scheduledAt: scheduledAt ? new Date(scheduledAt).toISOString() : null,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null
      } : {})
    };
  }

  function validateContent(): string | null {
    if (!title.trim() || !summary.trim()) return 'A title and short summary are required.';
    if (!templateName.trim()) return 'A template name is required.';
    if (!document.blocks.length || document.blocks.length > 40) return 'Use between 1 and 40 content blocks.';
    for (const block of document.blocks) {
      if (block.type === 'richText' && !String(block.text ?? '').trim()) return 'Rich-text blocks cannot be empty.';
      if (block.type === 'cta' && (!String(block.label ?? '').trim() || !String(block.target ?? '').trim())) return 'Every button needs a label and destination.';
      if (block.type === 'image' && !Boolean(block.decorative) && !String(block.alt ?? '').trim()) return 'Meaningful images need alternative text.';
    }
    return null;
  }

  function validateLocally(): string | null {
    const contentIssue = validateContent();
    if (contentIssue) return contentIssue;
    if (!document.blocks.length || document.blocks.length > 40) return 'Use between 1 and 40 content blocks.';
    if (scope === 'COURSE' && !courseId) return 'Select an assigned course first.';
    if (scope === 'USER' && directRecipients.length === 0) return 'Select at least one individual recipient.';
    if (scope === 'HRMS_PLATFORM' && !allInstitutions && selectedInstitutionIds.length === 0) return 'Select at least one institution.';
    if (!isPlatform && !['USER','COURSE','INSTITUTION'].includes(scope)) return 'This workspace cannot send platform notifications.';
    for (const block of document.blocks) {
      if (block.type === 'cta' && (!String(block.label ?? '').trim() || !String(block.target ?? '').trim())) return 'Every button needs a label and destination.';
      if (block.type === 'image' && !Boolean(block.decorative) && !String(block.alt ?? '').trim()) return 'Meaningful images need alternative text.';
    }
    return null;
  }

  async function preview() {
    const issue = validateLocally();
    if (issue) { setError(issue); return; }
    setSaving(true); setError(''); setNotice('');
    try {
      const response = await apiFetch(prefix + '/preview', {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(requestBody(false))
      });
      const data = await response.json().catch(() => ({})) as { valid?: boolean; recipientCount?: number; recipientCounts?: Record<string, number>; message?: string };
      if (!response.ok || !data.valid) throw new Error(data.message || 'Preview validation failed.');
      setPreviewCount(Number(data.recipientCount ?? 0));
      setPreviewCounts(data.recipientCounts ?? {});
      setPreviewed(true);
      setNotice('Preview validated. Sending will resolve the audience again.');
    } catch (e) { setError(e instanceof Error ? e.message : 'Preview validation failed.'); }
    finally { setSaving(false); }
  }

  async function saveTemplate() {
    const issue = validateContent();
    if (issue) { setError(issue); return; }
    setSaving(true); setError(''); setNotice('');
    try {
      const current = templateId ? templates.find(t => String(t.id) === templateId) : null;
      const method = current ? 'PUT' : 'POST';
      const url = current ? prefix + '/templates/' + current.id : prefix + '/templates';
      const response = await apiFetch(url, {
        method, headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ templateName, description: templateDescription, contentDocument: document })
      });
      const data = await response.json().catch(() => ({})) as Template & { message?: string };
      if (!response.ok) throw new Error(data.message || 'Template could not be saved.');
      setNotice('Template saved as a new immutable version.');
      await loadBase();
      if (data.id) {
        setTemplateId(String(data.id));
        setTemplateVersion(Number(field(data,'content_version','contentVersion') ?? 1));
        setTemplateName(String(field(data,'template_name','templateName') ?? templateName));
        setTemplateDescription(String(data.description ?? templateDescription));
        const savedDocument = asDocument(field(data,'content_document','contentDocument'));
        if (savedDocument) setDocument(clone(savedDocument));
      }
    } catch (e) { setError(e instanceof Error ? e.message : 'Template could not be saved.'); }
    finally { setSaving(false); }
  }

  async function send() {
    const issue = validateLocally();
    if (issue) { setError(issue); return; }
    if (!previewed) { setError('Preview and validate the notification before sending.'); return; }
    if (!window.confirm(`Send this notification to ${previewCount ?? 'the resolved'} eligible recipients? The recipient audience will be rechecked by the server.`)) return;
    setSending(true); setError(''); setNotice('');
    try {
      const baseBody = requestBody(true);
      const fingerprint = JSON.stringify(baseBody);
      let idempotency = idempotencyRef.current;
      if (!idempotency || idempotency.fingerprint !== fingerprint) {
        idempotency = { fingerprint, key: crypto.randomUUID() };
        idempotencyRef.current = idempotency;
      }
      const response = await apiFetch(prefix + '/send', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...baseBody, idempotencyKey: idempotency.key })
      });
      const data = await response.json().catch(() => ({})) as { status?: string; resolved_recipient_count?: number; resolvedRecipientCount?: number; id?: number; message?: string; idempotentReplay?: boolean };
      if (!response.ok) throw new Error(data.message || 'Notification could not be sent.');
      setNotice(`Campaign ${data.id ?? ''} · ${data.status ?? 'QUEUED'} · ${data.resolved_recipient_count ?? data.resolvedRecipientCount ?? previewCount ?? 0} recipients`);
      setPreviewed(false);
      setPreviewCount(null);
      idempotencyRef.current = null;
      if (data.idempotentReplay) setNotice('This send was already accepted. ' + (data.status ?? 'Campaign recorded.') );
    } catch (e) { setError(e instanceof Error ? e.message : 'Notification could not be sent.'); }
    finally { setSending(false); }
  }

  if (loading) return <main className={styles.page}><p>Loading notification creator…</p></main>;

  return <main className={styles.page}>
    <header className={styles.header}>
      <div><p className={styles.eyebrow}>RICH NOTIFICATION STUDIO</p>
        <h1>Create a modern notification</h1>
        <p>Build a reusable visual template, preview the actual rich content, validate the target audience, then send or schedule it.</p></div>
      <span className={styles.headerTag}>{surface === 'hrms' ? 'HRMS platform' : surface === 'institution' ? 'Institution workspace' : 'Education Admin'}</span>
    </header>
    {error ? <div className={styles.error} role="alert">{error}</div> : null}
    {notice ? <div className={styles.notice} role="status">{notice}</div> : null}

    <nav className={styles.studioNav} aria-label="Notification workspace">
      <button type="button" className={workspaceTab==='studio'?styles.studioNavActive:styles.studioNavItem} onClick={()=>setWorkspaceTab('studio')}>Design Studio</button>
      <button type="button" className={workspaceTab==='logo'?styles.studioNavActive:styles.studioNavItem} onClick={()=>setWorkspaceTab('logo')}>Logo & Graphics</button>
      <button type="button" className={workspaceTab==='templates'?styles.studioNavActive:styles.studioNavItem} onClick={()=>setWorkspaceTab('templates')}>Templates <span>{templates.length}</span></button>
      <button type="button" className={workspaceTab==='campaigns'?styles.studioNavActive:styles.studioNavItem} onClick={()=>setWorkspaceTab('campaigns')}>Campaigns & Delivery</button>
    </nav>
    <div className={styles.layout + (workspaceTab!=='studio'?' '+styles.workspaceHidden:'')}>
      <section className={styles.editor}>
        <div className={styles.panel}>
          <div className={styles.sectionHeading}><span>01</span><div><h2>Start from a template</h2><p>Choose a preset or load and edit a saved version.</p></div></div>
          <div className={styles.formGrid}>
            <label>Saved template<select value={templateId} onChange={event => chooseTemplate(event.target.value)}>
              <option value="">New notification</option>
              {templates.map(template => <option key={template.id} value={template.id}>{String(field(template,'template_name','templateName') ?? 'Template')} · v{field(template,'content_version','contentVersion') ?? 1}</option>)}
            </select></label>
            <label>Preset<select value="" onChange={event => {
              const preset = PRESETS[Number(event.target.value)];
              if (preset) { setDocument(clone(preset.document)); setTemplateName(preset.name); setSummary(preset.summary); setTemplateId(''); setTemplateVersion(null); setSelectedBlock(0); setPreviewed(false); }
            }}><option value="">Pick a layout…</option>{PRESETS.map((preset,index)=><option value={index} key={preset.name}>{preset.name}</option>)}</select></label>
          </div>
          <div className={styles.formGrid}>
            <label>Template name<input value={templateName} maxLength={120} onChange={event=>setTemplateName(event.target.value)}/></label>
            <label>Description<input value={templateDescription} maxLength={500} onChange={event=>setTemplateDescription(event.target.value)} placeholder="Internal description"/></label>
          </div>
          <div className={styles.formGrid}>
            <label>Notification title<input value={title} maxLength={180} onChange={event=>{setTitle(event.target.value);setPreviewed(false);}}/></label>
            <label>Short summary<input value={summary} maxLength={400} onChange={event=>{setSummary(event.target.value);setPreviewed(false);}}/></label>
          </div>
        </div>

        <div className={styles.panel}>
          <div className={styles.sectionHeading}><span>02</span><div><h2>Choose the audience</h2><p>The server rechecks memberships and permissions when previewing and sending.</p></div></div>
          <div className={styles.formGrid}>
            <label>Notification scope<select value={scope} onChange={event=>{setScope(event.target.value as Scope);setPreviewed(false);setPreviewCount(null);}}>
              {isPlatform ? <option value="HRMS_PLATFORM">HRMS platform / selected institutions</option> : <>
                <option value="COURSE">Course</option><option value="INSTITUTION">Institution</option><option value="USER">Individual user(s)</option>
              </>}
            </select></label>
            {scope==='COURSE' ? <label>Assigned course<select value={courseId} onChange={event=>{setCourseId(event.target.value);setPreviewed(false);setPreviewCount(null);}}>
              <option value="">Choose a course…</option>{courses.map(course=><option value={course.course_id} key={course.course_id}>{course.class_code ? `Class ${course.class_code} · ` : ''}${course.course_name} ({course.course_code})</option>)}
            </select></label> : null}
          </div>
          {scope==='HRMS_PLATFORM' ? <div className={styles.audienceBox}>
            <label className={styles.check}><input type="checkbox" checked={allInstitutions} onChange={event=>{setAllInstitutions(event.target.checked);setPreviewed(false);setPreviewCount(null);}}/> All active institutions</label>
            {!allInstitutions ? <div className={styles.institutionPicker}>
              <strong>Select institutions</strong>
              <div className={styles.institutionList}>{institutions.map(item=><label key={item.id} className={styles.check}>
                <input type="checkbox" checked={selectedInstitutionIds.includes(String(item.id))} onChange={event=>{setSelectedInstitutionIds(current=>event.target.checked?[...new Set([...current,String(item.id)])]:current.filter(id=>id!==String(item.id)));setPreviewed(false);setPreviewCount(null);}}/>
                <span>{item.display_name} <small>{item.tenant_code ?? item.institution_code}</small></span>
              </label>)}</div>
              <p className={styles.helpText}>{selectedInstitutionIds.length} institutions selected.</p>
            </div> : <p className={styles.helpText}>The audience will be resolved across every currently active institution when sent.</p>}
          </div> : null}
          {scope==='USER' ? <div className={styles.audienceBox}>
            <div className={styles.formGrid}><label>Recipient type<select value={directKind} onChange={event=>{setDirectKind(event.target.value);setRecipientOptions([]);setDirectRecipients([]);setPreviewed(false);setPreviewCount(null);}}>
              <option value="STUDENT">Student</option><option value="USER_ACCOUNT">Parent / guardian</option><option value="INSTITUTION_ADMIN">Institution admin</option>
            </select></label><label>Search recipients<input value={directQuery} onChange={event=>setDirectQuery(event.target.value)} placeholder="Name contains…"/></label></div>
            <button className={styles.ghostButton} type="button" onClick={()=>void searchRecipients()}>Search eligible recipients</button>
            <div className={styles.recipientResults}>{recipientOptions.map(item=>{
              const selected=directRecipients.some(row=>row.recipient_kind===item.recipient_kind&&row.recipient_id===item.recipient_id);
              return <label key={item.recipient_kind+':'+item.recipient_id} className={styles.recipient}><input type="checkbox" checked={selected} onChange={event=>{setDirectRecipients(current=>event.target.checked?[...current,item]:current.filter(row=>!(row.recipient_kind===item.recipient_kind&&row.recipient_id===item.recipient_id)));setPreviewed(false);setPreviewCount(null);}}/><span><strong>{item.recipient_name}</strong><small>{item.recipient_kind} · #{item.recipient_id}{item.class_code? ` · Class ${item.class_code}`:''}</small></span></label>;
            })}</div>
            <p className={styles.helpText}>{directRecipients.length} direct recipients selected.</p>
          </div> : null}
          {scope!=='USER' ? <div className={styles.audienceBox}>
            <strong>Who should receive it?</strong>
            <div className={styles.checkList}>
              {[
                ['STUDENT','Students'],['PARENT','Parents / guardians'],['INSTITUTION_ADMINS','Institution administrators']
              ].map(([value,label])=><label key={value} className={styles.check}><input type="checkbox" checked={recipientTypes.includes(value)} onChange={event=>{setRecipientTypes(current=>event.target.checked?[...current,value]:current.filter(item=>item!==value));setPreviewed(false);setPreviewCount(null);}}/>{label}</label>)}
            </div>
            {scope==='COURSE' ? <label className={styles.check}><input type="checkbox" checked={includeParents} onChange={event=>{setIncludeParents(event.target.checked);setPreviewed(false);setPreviewCount(null);}}/> Also notify linked parents / guardians</label>:null}
            {isPlatform ? <p className={styles.helpText}>The same user is delivered at most once per campaign across selected institutions. Only eligible memberships are resolved by the server.</p>:null}
          </div>:null}
        </div>

        <div className={styles.panel}>
          <div className={styles.sectionHeading}><span>03</span><div><h2>Design your content</h2><p>Build with media, cards, highlights and actions—not only text and images.</p></div></div>
          <div className={styles.canvaToolbar}>
            <div className={styles.canvasTabs} role="group" aria-label="Preview surface">
              <button type="button" className={previewMode==='mobile'?styles.canvasTabActive:styles.canvasTab} onClick={()=>setPreviewMode('mobile')}>▯ Phone</button>
              <button type="button" className={previewMode==='desktop'?styles.canvasTabActive:styles.canvasTab} onClick={()=>setPreviewMode('desktop')}>▱ Web</button>
              <button type="button" className={previewMode==='push'?styles.canvasTabActive:styles.canvasTab} onClick={()=>setPreviewMode('push')}>♧ Push</button>
            </div>
            <span>Drag blocks on the canvas to rearrange · Select any block to edit</span>
          </div>
          <div className={styles.builder}>
            <aside className={styles.blockList} aria-label="Content elements and layers">
              <div className={styles.railLabel}>LAYERS</div>
              {document.blocks.map((block,index)=><button type="button" key={index} draggable
                onDragStart={event=>{event.dataTransfer.setData('text/plain',String(index));event.dataTransfer.effectAllowed='move';}}
                onDragOver={event=>event.preventDefault()}
                onDrop={event=>{event.preventDefault();const from=Number(event.dataTransfer.getData('text/plain'));if(Number.isInteger(from))reorderBlocks(from,index);}}
                onClick={()=>setSelectedBlock(index)}
                className={selectedBlock===index?styles.activeBlock:styles.blockButton}>
                <span>{String(index+1).padStart(2,'0')}</span><strong>{BLOCK_OPTIONS.find(option=>option[0]===block.type)?.[1] ?? block.type}</strong>
              </button>)}
              <div className={styles.railLabel}>ADD ELEMENT</div>
              <div className={styles.elementPalette}>
                {BLOCK_OPTIONS.map(option=><button type="button" key={option[0]} onClick={()=>addBlock(option[0])} className={styles.elementButton}>
                  <span aria-hidden="true">{option[0]==='image'?'▧':option[0]==='video'?'▷':option[0]==='hero'?'▰':option[0]==='richText'?'T':option[0]==='cta'?'↗':option[0]==='courseCard'?'▤':option[0]==='eventCard'?'◷':option[0]==='highlight'?'▦':option[0]==='animation'?'✦':'＋'}</span>
                  <strong>{option[1]}</strong>
                </button>)}
              </div>
            </aside>
            <section className={styles.canvasStage} aria-label="Notification design canvas">
              <div className={styles.canvasTopline}><span>CANVAS</span><span>{document.blocks.length} elements</span></div>
              {previewMode==='push' ? <div className={styles.pushPreview}>
                <div className={styles.pushAppIcon}>Q</div>
                <div className={styles.pushCopy}><strong>{title || 'Notification title'}</strong><p>{summary || 'Notification summary'}</p><small>now · QuantaEdge</small></div>
                <div className={styles.pushMedia}>{document.blocks.some(b=>['hero','image','animation'].includes(b.type)&&Boolean(b.assetId))?'▧':'✦'}</div>
                <p className={styles.pushFootnote}>System push is compact. Tap opens the full rich notification.</p>
              </div> : <div className={previewMode==='mobile'?styles.phoneCanvas:styles.desktopCanvas}>
                <div className={styles.canvasBrandBar}><span className={styles.brandGlyph}>✦</span><div><strong>{title || 'Notification title'}</strong><small>{surface==='hrms'?'HRMS platform':surface==='institution'?'Your institution':'QuantaEdge Admin'}</small></div><span className={styles.canvasBell}>♧</span></div>
                <p className={styles.previewSummary}>{summary}</p>
                <RichNotificationRenderer
                  surface={surface}
                  contentDocument={document}
                  editMode
                  selectedBlockIndex={selectedBlock}
                  onSelectBlock={setSelectedBlock}
                  onReorderBlocks={reorderBlocks}
                />
                <div className={styles.canvasFooter}>PREVIEW · CONTENT IS NOT YET SENT</div>
              </div>}
            </section>
            <aside className={styles.inspector} aria-label="Selected element properties">
              <div className={styles.inspectorHeading}><div><span>DESIGN</span><h3>{activeBlock ? BLOCK_OPTIONS.find(option=>option[0]===activeBlock.type)?.[1] ?? activeBlock.type : 'Select an element'}</h3></div>
                {activeBlock ? <div className={styles.inspectorMove}><button type="button" onClick={()=>moveBlock(-1)} disabled={selectedBlock===0} aria-label="Move element up">↑</button><button type="button" onClick={()=>moveBlock(1)} disabled={selectedBlock===document.blocks.length-1} aria-label="Move element down">↓</button><button type="button" onClick={removeBlock} aria-label="Delete element">×</button></div>:null}
              </div>
              {activeBlock ? <div className={styles.fieldGrid}>
                {Object.entries(activeBlock).filter(([key])=>!['type','assetId','decorative','fit','aspectRatio','focalPoint','backgroundColor','textColor','accentColor','alignment','padding','borderRadius','fontSize'].includes(key)).map(([key,value])=>{
                  const multiline=['text','subtitle','description','caption'].includes(key);
                  return <label key={key}>{key.replace(/([A-Z])/g,' $1').replace(/^./,c=>c.toUpperCase())}
                    {multiline?<textarea rows={key==='text'?4:2} value={String(value??'')} onChange={event=>updateBlock(key,event.target.value)}/>
                      :<input value={String(value??'')} onChange={event=>updateBlock(key,event.target.value)}/>}
                  </label>;
                })}
                <div className={styles.appearanceControls}>
                  <div className={styles.appearanceTitle}><strong>Appearance</strong><span>Style this element</span></div>
                  <label>Alignment<select value={String(activeBlock.alignment??'left')} onChange={event=>updateBlock('alignment',event.target.value)}>
                    <option value="left">Left</option><option value="center">Center</option><option value="right">Right</option><option value="justify">Justified</option>
                  </select></label>
                  <label>Base text size<select value={String(activeBlock.fontSize??'14')} onChange={event=>updateBlock('fontSize',event.target.value)}>
                    {[12,14,16,18,20,24,28,32].map(size=><option key={size} value={size}>{size}px</option>)}
                  </select></label>
                  <label>Spacing<select value={String(activeBlock.padding??'12')} onChange={event=>updateBlock('padding',event.target.value)}>
                    {[0,4,8,12,16,24,32,40,48].map(size=><option key={size} value={size}>{size}px</option>)}
                  </select></label>
                  <label>Corner radius<select value={String(activeBlock.borderRadius??'12')} onChange={event=>updateBlock('borderRadius',event.target.value)}>
                    {[0,4,8,12,16,20,24,32,40].map(size=><option key={size} value={size}>{size}px</option>)}
                  </select></label>
                  <label>Surface colour<input type="color" value={String(activeBlock.backgroundColor??'#ffffff')} onChange={event=>updateBlock('backgroundColor',event.target.value)}/></label>
                  <label>Text colour<input type="color" value={String(activeBlock.textColor??'#172637')} onChange={event=>updateBlock('textColor',event.target.value)}/></label>
                  <label>Accent colour<input type="color" value={String(activeBlock.accentColor??'#c45c16')} onChange={event=>updateBlock('accentColor',event.target.value)}/></label>
                  <p className={styles.helpText}>Colours are applied to this element only. Image fit and crop controls are available for media elements.</p>
                  <button type="button" className={styles.ghostButton} onClick={()=>{
                    const keys=['backgroundColor','textColor','accentColor','alignment','padding','borderRadius','fontSize'];
                    setDocument(current=>{
                      const blocks=[...current.blocks];
                      const block={...blocks[selectedBlock]};
                      keys.forEach(key=>delete block[key]);
                      blocks[selectedBlock]=block;
                      return {...current,blocks};
                    });
                    setPreviewed(false);setPreviewCount(null);
                  }}>Reset appearance</button>
                </div>
                {['image','video','animation','hero'].includes(activeBlock.type) ? <div className={styles.mediaControl}>
                  <div><strong>{activeBlock.assetId?'Media attached':'Media & graphics'}</strong><small>{activeBlock.assetId?String(activeBlock.assetId):'Upload a graphic, animation or video.'}</small></div>
                  <label className={styles.uploadButton}>{uploading?'Uploading…':'Upload media'}<input type="file" accept="image/jpeg,image/png,image/webp,image/gif,video/mp4,video/webm" onChange={event=>void uploadMedia(event)} disabled={uploading}/></label>
                  {activeBlock.assetId?<button type="button" className={styles.ghostButton} onClick={()=>updateBlock('assetId','')}>Remove media</button>:null}
                </div>:null}
                {['image','animation'].includes(activeBlock.type)?<label className={styles.check}><input type="checkbox" checked={Boolean(activeBlock.decorative)} onChange={event=>updateBlock('decorative',event.target.checked)}/> Decorative graphic</label>:null}
                {['image','hero','animation'].includes(activeBlock.type)?<>
                  <label>Image fit<select value={String(activeBlock.fit??'cover')} onChange={event=>updateBlock('fit',event.target.value)}><option value="cover">Fill / cover</option><option value="contain">Fit inside</option></select></label>
                  <label>Aspect ratio<select value={String(activeBlock.aspectRatio??'16:9')} onChange={event=>updateBlock('aspectRatio',event.target.value)}><option value="auto">Natural</option><option value="16:9">Wide · 16:9</option><option value="4:3">Landscape · 4:3</option><option value="1:1">Square · 1:1</option><option value="3:4">Portrait · 3:4</option></select></label>
                  <label>Focus point<select value={String(activeBlock.focalPoint??'center')} onChange={event=>updateBlock('focalPoint',event.target.value)}><option value="center">Center</option><option value="top">Top</option><option value="bottom">Bottom</option><option value="left">Left</option><option value="right">Right</option></select></label>
                </>:null}
              </div> : <p className={styles.helpText}>Select an element on the canvas or add a new block.</p>}
            </aside>
          </div>        </div>

        <div className={styles.panel}>
          <div className={styles.sectionHeading}><span>04</span><div><h2>Preview, validate and send</h2><p>Delivery begins only after a successful server-side preview and confirmation.</p></div></div>
          <div className={styles.formGrid}>
            <label>Schedule send (optional)<input type="datetime-local" value={scheduledAt} onChange={event=>{setScheduledAt(event.target.value);setPreviewed(false);setPreviewCount(null);}}/></label>
            <label>Expiry (optional)<input type="datetime-local" value={expiresAt} onChange={event=>{setExpiresAt(event.target.value);setPreviewed(false);setPreviewCount(null);}}/></label>
          </div>
          {previewCount!==null?<div className={styles.audienceResult}><strong>{previewCount} eligible recipients</strong><span>{Object.entries(previewCounts).map(([kind,count])=>kind+': '+count).join(' · ')}</span></div>:null}
          <div className={styles.sendActions}>
            <button type="button" className={styles.ghostButton} onClick={()=>void saveTemplate()} disabled={saving||sending}>{saving?'Saving…':'Save template version'}</button>
            <button type="button" className={styles.previewButton} onClick={()=>void preview()} disabled={saving||sending}>{saving?'Validating…':'Preview & validate audience'}</button>
            <button type="button" className={styles.sendButton} onClick={()=>void send()} disabled={saving||sending||!previewed}>{sending?'Sending…':scheduledAt?'Schedule notification':'Send notification'}</button>
          </div>
          <p className={styles.helpText}>Preview is not delivery. The backend resolves the audience again at send time; scheduled campaigns are rechecked before dispatch.</p>
        </div>
      </section>

      <aside className={styles.side}>
        <div className={styles.sticky}>
          <div className={styles.sideCard}><h3>Rich content blocks</h3><p>Combine hero layouts, formatted copy, image/video/animation assets, course and event cards, highlights and action buttons.</p><ul><li>Reorder blocks</li><li>Preview the actual renderer</li><li>Save immutable versions</li><li>Safe links and accessible media</li></ul></div>
          <div className={styles.sideCard}><h3>Delivery behavior</h3><p>The persistent inbox is the source of truth. Push is a compact heads-up where configured; opening it reveals the complete content.</p><p className={styles.muted}>No send occurs until audience validation and confirmation succeed.</p></div>
          <div className={styles.sideCard}><h3>Template library</h3>{templates.length?<div className={styles.templateList}>{templates.slice(0,8).map(t=><button type="button" key={t.id} onClick={()=>chooseTemplate(String(t.id))}><span>{String(field(t,'template_name','templateName')??'Template')}</span><small>v{field(t,'content_version','contentVersion')??1} · {t.status}</small></button>)}</div>:<p className={styles.muted}>Your saved templates will appear here.</p>}</div>
        </div>
      </aside>
    </div>
    {workspaceTab==='logo' ? <section className={styles.libraryPanel}>
      <LogoGraphicsLab surface={surface} onUseInNotification={useLogoInNotification}/>
    </section> : null}
    {workspaceTab==='templates' ? <section className={styles.libraryPanel}>
      <div className={styles.libraryHeading}>
        <div><p className={styles.eyebrow}>REUSABLE DESIGNS</p><h2>Template library</h2><p>Each edit creates a new version. Sent campaigns retain the content they were sent with.</p></div>
        <button type="button" className={styles.sendButton} onClick={()=>{
          setTemplateId('');setTemplateVersion(null);setTemplateName('New notification template');setTemplateDescription('');
          setDocument(clone(DEFAULT_DOCUMENT));setSelectedBlock(0);setPreviewed(false);setWorkspaceTab('studio');
        }}>+ Create a template</button>
      </div>
      {templates.length===0 ? <div className={styles.campaignEmpty}><h3>No saved templates yet</h3><p>Create a design in Design Studio and save it as a reusable template.</p></div> :
      <div className={styles.templateGallery}>{templates.map(template=>{
        const version=Number(field(template,'content_version','contentVersion')??1);
        const name=String(field(template,'template_name','templateName')??'Untitled template');
        const doc=asDocument(field(template,'content_document','contentDocument'))??DEFAULT_DOCUMENT;
        return <article className={styles.templateTile} key={template.id}>
          <div className={styles.templateTilePreview}><RichNotificationRenderer surface={surface} contentDocument={doc}/></div>
          <div className={styles.templateTileMeta}><div><h3>{name}</h3><p>{template.description||'Reusable notification design'}</p></div><span>v{version}</span></div>
          <button type="button" className={styles.previewButton} onClick={()=>{chooseTemplate(String(template.id));setWorkspaceTab('studio');}}>Open and edit</button>
        </article>;
      })}</div>}
    </section> : null}
    {workspaceTab==='campaigns' ? <section className={styles.libraryPanel}>
      <div className={styles.libraryHeading}>
        <div><p className={styles.eyebrow}>DELIVERY HISTORY</p><h2>Campaigns & delivery</h2><p>Campaign status and audience counts. Per-channel delivery attempts remain distinct from recipient read state.</p></div>
        <button type="button" className={styles.ghostButton} onClick={()=>void loadCampaigns()} disabled={campaignLoading}>{campaignLoading?'Refreshing…':'Refresh history'}</button>
      </div>
      {campaignError ? <div className={styles.error} role="alert">{campaignError}</div> : null}
      {campaignLoading ? <div className={styles.campaignEmpty}>Loading campaign history…</div> :
        campaigns.length===0 ? <div className={styles.campaignEmpty}><h3>No campaigns found</h3><p>Sent and scheduled notifications will appear here.</p></div> :
        <div className={styles.campaignTableWrap}><table className={styles.campaignTable}><thead><tr><th>Notification</th><th>Scope</th><th>Status</th><th>Recipients</th><th>Schedule / sent</th><th>Created</th><th>Action</th></tr></thead><tbody>
          {campaigns.map(campaign=><tr key={campaign.id}><td><strong>{campaign.title||'Untitled campaign'}</strong><small>{campaign.summary||''}</small></td><td>{campaign.scope||'—'}</td><td><span className={styles.campaignStatus} data-status={String(campaign.status||'').toLowerCase()}>{campaign.status||'—'}</span></td><td>{Number(campaign.resolved_recipient_count||0).toLocaleString('en-IN')}</td><td>{campaign.scheduled_at?new Date(campaign.scheduled_at).toLocaleString('en-IN'):campaign.dispatched_at?new Date(campaign.dispatched_at).toLocaleString('en-IN'):'—'}</td><td>{campaign.created_at?new Date(campaign.created_at).toLocaleString('en-IN'):'—'}</td><td>{String(campaign.status).toUpperCase()==='SCHEDULED'?<button type="button" className={styles.ghostButton} onClick={()=>void cancelCampaign(campaign)} disabled={campaignLoading}>Cancel</button>:'—'}</td></tr>)}
        </tbody></table></div>}
    </section> : null}
  </main>;
}
