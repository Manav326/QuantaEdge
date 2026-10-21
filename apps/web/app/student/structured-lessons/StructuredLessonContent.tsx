'use client';

import { useState } from 'react';
import { useUiTranslation } from '../../i18n/useUiTranslation';
import type { LessonLocale, StructuredExplainBlock, StructuredLesson } from './contract';
import { isStudentRenderableAsset, readLocale } from './contract';
import StructuredImageLightbox from './StructuredImageLightbox';

type Mode = 'student' | 'admin-preview';
type Props = { lesson: StructuredLesson; locale: LessonLocale; mode?: Mode; showHeader?: boolean; onActivitySave?: (blockId: string, value: string) => void | Promise<void>; initialActivityResponses?: Record<string, Record<string, string>> };

function localized(value: unknown, locale: LessonLocale, fallback = ''): string {
  return readLocale(value, locale) || fallback;
}

const PRIVATE_BLOCK_KEYS = new Set([
  'answerpayload', 'correctanswer', 'correctanswerids', 'correctpairs',
  'acceptedanswers', 'answerguidebylocale', 'grading', 'iscorrect',
  'correctoption', 'answerkey', 'solutionkey', 'modelanswer',
  'expectedanswer', 'explanationkey'
]);

function collectStructuredBlockText(value: unknown, prefix = '', output: string[] = []): string[] {
  if (value === null || value === undefined) return output;
  if (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') {
    const text = String(value).trim();
    if (text) output.push(prefix ? prefix + ': ' + text : text);
    return output;
  }
  if (Array.isArray(value)) {
    value.forEach((item, index) => collectStructuredBlockText(item, prefix ? prefix + ' ' + (index + 1) : '', output));
    return output;
  }
  if (typeof value !== 'object') return output;
  Object.entries(value as Record<string, unknown>).forEach(([key, child]) => {
    const normalized = key.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
    if (PRIVATE_BLOCK_KEYS.has(normalized)) return;
    if (['id','type','order','objectiveids','sourcerefs','url','asseturl','renderingcontractid','schemaVersion'.toLowerCase()].includes(normalized)) return;
    const label = key.replace(/([A-Z])/g, ' $1').replace(/[_-]+/g, ' ').trim();
    collectStructuredBlockText(child, prefix ? prefix + ' · ' + label : label, output);
  });
  return output;
}

export function StructuredDiagramView({ block, locale }: { block: StructuredExplainBlock; locale: LessonLocale }) {
  const uiT = useUiTranslation();
  const data = block.diagram_data || {};
  const nodes = Array.isArray(data.nodes) ? data.nodes as Array<Record<string, any>> : [];
  const edges = Array.isArray(data.edges) ? data.edges as Array<Record<string, any>> : [];
  const value = (entry: unknown) => {
    if (entry && typeof entry === 'object') return localized(entry, locale, '');
    return typeof entry === 'string' ? entry : '';
  };
  const contextLabel = value(data.context_label_by_locale);
  const outcomeLabel = value(data.outcome_by_locale);
  if (!nodes.length) return <div className="qe-structured-muted" role="status">{uiT('structured.ui.diagramNotReady')}</div>;
  return <figure className="qe-structured-diagram">
    {contextLabel && <p className="qe-structured-diagram__context">{contextLabel}</p>}
    <div className="qe-structured-diagram__nodes">
      {nodes.map((node, index) => <div className="qe-structured-diagram__node" key={String(node.id || index)}>
        <span className="qe-structured-diagram__step">{index + 1}</span>
        <span>{value(node.label_by_locale) || value(node.label) || (locale === 'hi' ? 'चरण' : 'Step')}</span>
      </div>)}
    </div>
    {edges.length > 0 && <details className="qe-structured-diagram__connections">
      <summary>{uiT('structured.ui.viewConnections')}</summary>
      <ul>{edges.map((edge, index) => {
        const endpointLabel = (id: unknown) => {
          const node = nodes.find(entry => String(entry.id ?? '') === String(id ?? ''));
          return node ? value(node.label_by_locale) || value(node.label) || (locale === 'hi' ? 'चरण' : 'Step') : '';
        };
        const fromLabel = endpointLabel(edge.from);
        const toLabel = endpointLabel(edge.to);
        return <li key={String(edge.from || index) + '-' + String(edge.to || index)}>
          {(fromLabel || (locale === 'hi' ? 'अज्ञात चरण' : 'Unknown step')) + ' → ' + (toLabel || (locale === 'hi' ? 'अज्ञात चरण' : 'Unknown step'))}{edge.label_by_locale ? ': ' + value(edge.label_by_locale) : ''}
        </li>;
      })}</ul>
    </details>}
    {outcomeLabel && <p className="qe-structured-diagram__outcome">{outcomeLabel}</p>}
    <figcaption>{localized(block.caption_by_locale, locale, locale === 'hi' ? 'मौलिक शिक्षण आरेख' : 'Original learning diagram')}</figcaption>
    <p className="qe-structured-visually-hidden">{localized(block.alt_by_locale, locale, locale === 'hi' ? 'अवधारणा आरेख' : 'Concept diagram')}</p>
  </figure>;
}

function ActivityBlock({ block, locale, mode, onSave, initialResponses }: { block: StructuredExplainBlock; locale: LessonLocale; mode: Mode; onSave?: Props['onActivitySave']; initialResponses?: Record<string, string> }) {
  const uiT = useUiTranslation();
  const [responses, setResponses] = useState<Record<string, string>>(() => initialResponses || {});
  const [saved, setSaved] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState('');
  const config = (block.configuration || {}) as Record<string, any>;
  const rawInstructions = (block as any).instructions_by_locale?.[locale];
  const instructions = Array.isArray(rawInstructions) ? rawInstructions : [];
  const instructionText = typeof rawInstructions === 'string' ? rawInstructions :
    localized(config.instruction_by_locale, locale) ||
    localized(block.instructions_by_locale, locale) ||
    (typeof config.instruction === 'string' ? config.instruction : '');
  const steps = Array.isArray(config.steps) ? config.steps : [];
  const responseFields = Array.isArray((block as any).response_fields) ? (block as any).response_fields :
    Array.isArray(config.response_fields) ? config.response_fields : [];
  const controls = (config.controls_by_locale && config.controls_by_locale[locale]) || {};
  const title = localized(config.title_by_locale, locale) || localized((block as any).title_by_locale, locale) ||
    uiT('structured.ui.activity');
  const safety = localized((block as any).safety_note_by_locale, locale) ||
    localized((block as any).safety_and_scope, locale) ||
    localized((block as any).safety, locale);
  const update = (id: string, value: string) => { setResponses(previous => ({ ...previous, [id]: value })); setSaved(false); setSaveError(''); };
  return <section className="qe-structured-activity" aria-labelledby={'activity-' + block.id}>
    <h3 id={'activity-' + block.id}>{title}</h3>
    {instructionText && <p>{instructionText}</p>}
    {instructions.length > 0 && <ol className="qe-structured-activity__instructions">{instructions.map((instruction: string, index: number) => <li key={index}>{instruction}</li>)}</ol>}
    {steps.length > 0 && <ol>{steps.map((step: Record<string, any>, i: number) =>
      <li key={String(step.id || i)}>{localized(step.instruction_by_locale, locale) || localized(step.label_by_locale, locale) || String(step.instruction || step.label || '')}</li>
    )}</ol>}
    {safety && <p className="qe-structured-safety">{safety}</p>}
    {responseFields.length > 0 ? responseFields.map((field: Record<string, any>, index: number) => {
      const fieldId = String(field.id || 'response-' + index);
      const fieldLabel = localized(field.label_by_locale, locale) || uiT('structured.ui.responseField', { index: index + 1 });
      return <label className="qe-structured-field" key={fieldId}>
        <span>{fieldLabel}</span>
        <textarea value={responses[fieldId] || ''} onChange={event => update(fieldId, event.target.value)} rows={2} />
      </label>;
    }) : <label className="qe-structured-field">
      <span>{uiT('structured.ui.recordObservation')}</span>
      <textarea value={responses.response || ''} onChange={event => update('response', event.target.value)} rows={3} />
    </label>}
    <div className="qe-structured-actions">
      <button type="button" disabled={!onSave || saving} onClick={async () => {
        if (!onSave || saving) return;
        setSaving(true); setSaveError('');
        try { await onSave(block.id, JSON.stringify(responses)); setSaved(true); }
        catch (error) { setSaveError(error instanceof Error ? error.message : uiT('structured.ui.responseSaveFailed')); }
        finally { setSaving(false); }
      }}>
        {saving ? uiT('structured.ui.saving') : String(controls.save || uiT('structured.ui.saveResponse'))}
      </button>
      <button type="button" className="qe-structured-button-secondary" onClick={() => { setResponses({}); setSaved(false); }}>
        {String(controls.reset || uiT('structured.ui.clear'))}
      </button>
      {saved && <span role="status">{mode === 'admin-preview' ? uiT('structured.ui.previewSaved') : uiT('structured.ui.saveRequestSent')}</span>}
      {!onSave && <span role="status">{uiT('structured.ui.saveNotConnected')}</span>}
    </div>
  </section>;
}

export default function StructuredLessonContent({ lesson, locale, mode = 'student', showHeader = true, onActivitySave, initialActivityResponses }: Props) {
  const uiT = useUiTranslation();
  const [showBlocked, setShowBlocked] = useState(false);
  const blocks = lesson.explain.blocks.slice().sort((a, b) => a.order - b.order);
  return <section className="qe-structured-lesson" aria-label={localized(lesson.topic.title_by_locale, locale, 'Lesson')}>
    {showHeader && <header className="qe-structured-lesson__header">
      <p className="qe-structured-eyebrow">{uiT('structured.ui.class')} {String((lesson.curriculum as any)?.class ?? 6)} · {localized((lesson.curriculum as any)?.subject_by_locale || (lesson.curriculum as any)?.subject, locale, locale === 'hi' ? 'विज्ञान' : 'Science')}</p>
      <h1>{localized(lesson.chapter.title_by_locale, locale)}</h1>
      <h2>{localized(lesson.topic.title_by_locale, locale)}</h2>
      {lesson.learning_objectives?.length ? <div className="qe-structured-objectives">
        <h3>{uiT('structured.ui.whatLearn')}</h3>
        <ul>{lesson.learning_objectives.map(item => <li key={item.id}>{localized(item.statement_by_locale, locale)}</li>)}</ul>
      </div> : null}
    </header>}
    <div className="qe-structured-blocks">
      {blocks.map(block => {
        const text = block.text_by_locale?.[locale];
        const content = block.content_by_locale;
        if (block.type === 'rich_text') {
          const media = (block as any).media_by_locale?.[locale] || {};
          const mediaType = String(media.type || '').toLowerCase();
          const mediaUrl = typeof media.url === 'string' && /^https:\/\//i.test(media.url) ? media.url : '';
          const resourceUrl = (block as any).resource_url_by_locale?.[locale];
          const safeResourceUrl = typeof resourceUrl === 'string' && /^https:\/\//i.test(resourceUrl) ? resourceUrl : '';
          const youtubeEmbed = (() => {
            if (mediaType !== 'video' || !mediaUrl) return '';
            try {
              const parsed = new URL(mediaUrl);
              const host = parsed.hostname.toLowerCase();
              if (!['youtube.com','www.youtube.com','m.youtube.com','youtu.be'].includes(host)) return '';
              const id = host === 'youtu.be' ? parsed.pathname.split('/').filter(Boolean)[0] : parsed.searchParams.get('v');
              return id && /^[A-Za-z0-9_-]{6,20}$/.test(id) ? 'https://www.youtube-nocookie.com/embed/' + encodeURIComponent(id) : '';
            } catch { return ''; }
          })();
          const directVideo = mediaType === 'video' && /\.(mp4|webm|ogg|mov|ogv)(\?.*)?$/i.test(mediaUrl);
          const directAnimation = mediaType === 'animation' && /\.(mp4|webm|ogg|mov|ogv)(\?.*)?$/i.test(mediaUrl);
          return <article className="qe-structured-card" key={block.id}>
            {text?.heading && <h3>{text.heading}</h3>}
            {(text?.paragraphs || []).map((paragraph, index) => <p key={index}>{paragraph}</p>)}
            {typeof text?.callout === 'string' && <aside className="qe-structured-callout">{text.callout}</aside>}
            {typeof text?.key_point === 'string' && <p className="qe-structured-keypoint"><strong>{uiT('structured.ui.keyPoint')}</strong> {text.key_point}</p>}
            {mediaUrl && mediaType === 'audio' && <audio controls preload="metadata" className="qe-structured-media" src={mediaUrl}>{locale === 'hi' ? 'ऑडियो उपलब्ध नहीं है।' : 'Audio is not supported by this browser.'}</audio>}
            {mediaUrl && (directVideo || directAnimation) && <video controls preload="metadata" playsInline className="qe-structured-media" src={mediaUrl}>{locale === 'hi' ? 'वीडियो उपलब्ध नहीं है।' : 'Video is not supported by this browser.'}</video>}
            {mediaUrl && mediaType === 'video' && !directVideo && youtubeEmbed && <div className="lesson-visual-embed"><iframe src={youtubeEmbed} title={String(media.title || text?.heading || 'Lesson video')} allow="accelerometer; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share" allowFullScreen referrerPolicy="strict-origin-when-cross-origin" /></div>}
            {mediaUrl && mediaType === 'animation' && !directAnimation && <a href={mediaUrl} target="_blank" rel="noreferrer">{locale === 'hi' ? 'एनिमेशन खोलें' : 'Open animation'}</a>}
            {mediaUrl && !['audio','video','animation'].includes(mediaType) && <a href={mediaUrl} target="_blank" rel="noreferrer">{locale === 'hi' ? 'सीखने का संसाधन खोलें' : 'Open learning resource'}</a>}
            {!mediaUrl && mediaType && <p className="qe-structured-muted" role="status">{locale === 'hi' ? 'इस ब्लॉक का मीडिया लिंक अभी उपलब्ध नहीं है।' : 'The media link for this block is not available yet.'}</p>}
            {safeResourceUrl && <p><a href={safeResourceUrl} target="_blank" rel="noreferrer">{locale === 'hi' ? 'स्रोत / संसाधन खोलें' : 'Open source / resource'}</a></p>}
          </article>;
        }
        if (block.type === 'image') {
          const asset = block.asset || {};
          const canDisplay = isStudentRenderableAsset(asset);
          const canPreview = canDisplay || mode === 'admin-preview';
          const url = typeof asset.url === 'string' && (canDisplay || mode === 'admin-preview') ? asset.url : '';
          const alt = localized(asset.alt_by_locale, locale) || localized(block.alt_by_locale, locale);
          return <figure className="qe-structured-card qe-structured-image" key={block.id}>
            {canPreview && url ? <StructuredImageLightbox url={url} alt={alt} caption={localized(asset.caption_by_locale, locale) || localized(block.caption_by_locale, locale)} locale={locale} /> :
              <div className="qe-structured-muted" role="status">{uiT('structured.ui.visualReviewPending')}</div>}
            <figcaption>{localized(asset.caption_by_locale, locale) || localized(block.caption_by_locale, locale)}</figcaption>
            {mode === 'admin-preview' && <small>{String(asset.source_book_id || '')} · {uiT('structured.ui.pdfPage')} {String(asset.source_pdf_page_number || '—')} · {String(asset.review_status || 'UNREVIEWED')}</small>}
          </figure>;
        }
        if (block.type === 'diagram') {
          const data = block.diagram_data || {};
          const reviewStatus = String((block as any).review_status || data.review_status || '').toUpperCase();
          const canDisplay = ((block as any).student_renderable ?? data.student_renderable) === true && ['APPROVED', 'APPROVED_FOR_STUDENT_RENDERING'].includes(reviewStatus);
          const canPreview = canDisplay || mode === 'admin-preview';
          return <article className="qe-structured-card" key={block.id}>
            {!canDisplay && <div className="qe-structured-muted" role="status">{uiT('structured.ui.diagramReviewPending')}</div>}
            {canPreview && <StructuredDiagramView block={block} locale={locale} />}
            {mode === 'admin-preview' && <small>{uiT('structured.ui.visualStatus')} {String(data.review_status || block.review_status || 'UNREVIEWED')}</small>}
          </article>;
        }
        if (block.type === 'interactive' || block.type === 'activity') return <ActivityBlock key={block.id} block={block} locale={locale} mode={mode} onSave={onActivitySave} initialResponses={initialActivityResponses?.[block.id]} />;
        if (block.type === 'recap') {
          const selected = typeof content === 'object' && content !== null ? (content as Record<string, unknown>)[locale] : content;
          const recapObject = selected && typeof selected === 'object' ? selected as Record<string, unknown> : null;
          const takeaways = recapObject && Array.isArray(recapObject.key_takeaways) ? recapObject.key_takeaways :
            recapObject && Array.isArray(recapObject.points) ? recapObject.points :
            Array.isArray(selected) ? selected : typeof selected === 'string' ? [selected] : [];
          const vocabulary = recapObject && Array.isArray(recapObject.vocabulary) ? recapObject.vocabulary as Array<Record<string, unknown>> : [];
          return <aside className="qe-structured-card qe-structured-recap" key={block.id}>
            <h3>{uiT('structured.ui.recap')}</h3>
            {takeaways.map((point, index) => <p key={index}>✓ {String(point)}</p>)}
            {vocabulary.length > 0 && <div className="qe-structured-vocabulary">
              <h4>{uiT('structured.ui.keyVocabulary')}</h4>
              <dl>{vocabulary.map((item, index) => <div key={String(item.term || index)}>
                <dt>{String(item.term || '')}</dt><dd>{String(item.meaning || '')}</dd>
              </div>)}</dl>
            </div>}
          </aside>;
        }
        // Keep unrecognized future/backend block types visible without exposing answer keys.
        const fallbackText = block.text_by_locale?.[locale] || {};
        const rawLocalized = content && typeof content === 'object'
          ? ((content as Record<string, unknown>)[locale] ?? content) : content;
        const fallbackInstructions = block.instructions_by_locale
          ? (block.instructions_by_locale as unknown as Record<string, unknown>)[locale] : undefined;
        const fallbackFields = collectStructuredBlockText({
          text: fallbackText,
          content: rawLocalized,
          instructions: fallbackInstructions,
          configuration: block.configuration,
          diagram_data: block.diagram_data,
          asset: block.asset,
          caption: block.caption_by_locale ? (block.caption_by_locale as unknown as Record<string, unknown>)[locale] : undefined,
        });
        const fallbackAsset = block.asset || {};
        const fallbackConfiguration = block.configuration || {};
        const rawUrl = String(fallbackAsset.url || fallbackConfiguration.url || (block as any).url || '');
        const fallbackUrl = /^https:\/\//i.test(rawUrl) ? rawUrl : '';
        const fallbackPath = fallbackUrl.split('?')[0].toLowerCase();
        const fallbackVideo = /\.(mp4|webm|mov|ogv)$/.test(fallbackPath) || /video/i.test(block.type);
        const fallbackAudio = /\.(mp3|m4a|wav|aac|flac|oga)$/.test(fallbackPath) || /audio/i.test(block.type);
        const fallbackImage = /\.(png|jpe?g|gif|webp|avif|svg)$/.test(fallbackPath);
        const fallbackTitle = String(fallbackText.heading || fallbackText.title ||
          localized(block.topic_by_locale, locale) || block.type.replaceAll('_', ' '));
        return <article className="qe-structured-card qe-structured-unknown-block" key={block.id}>
          <h3>{fallbackTitle}</h3>
          {fallbackUrl && fallbackVideo && <video className="lesson-visual-video" controls preload="metadata" playsInline src={fallbackUrl}/>}
          {fallbackUrl && fallbackAudio && <audio className="lesson-visual-audio" controls preload="metadata" src={fallbackUrl}/>}
          {fallbackUrl && fallbackImage && <StructuredImageLightbox url={fallbackUrl}
            alt={localized(fallbackAsset.alt_by_locale, locale) || fallbackTitle}
            caption={localized(block.caption_by_locale, locale)} locale={locale} />}
          {fallbackUrl && !fallbackVideo && !fallbackAudio && !fallbackImage &&
            <a className="qe-structured-link" href={fallbackUrl} target="_blank" rel="noreferrer">{fallbackTitle} ↗</a>}
          {fallbackFields.map((field, index) => <p key={index}>{field}</p>)}
          {!fallbackFields.length && !fallbackUrl && <p>{uiT('structured.ui.unsupportedBlock')} {block.type}</p>}
          {mode === 'admin-preview' && <small>{uiT('structured.ui.unsupportedBlock')} {block.type}</small>}
        </article>;
      })}
    </div>
    {mode === 'admin-preview' && <details className="qe-structured-debug">
      <summary>{uiT('structured.ui.authorReviewDetails')}</summary>
      <p>{uiT('structured.ui.artifact')} {lesson.artifact_id}</p>
      <p>{uiT('structured.ui.publicationAllowed')} {String(lesson.workflow?.publication_allowed === true)}</p>
      <button type="button" onClick={() => setShowBlocked(value => !value)}>{showBlocked ? uiT('structured.ui.hideReviewNote') : uiT('structured.ui.showReviewNote')}</button>
      {showBlocked && <p>{localized(lesson.topic.title_by_locale, locale)} · {String(lesson.workflow?.status || 'UNKNOWN')} · {String(lesson.workflow?.source_completeness_status || 'SOURCE STATUS UNKNOWN')}</p>}
    </details>}
  </section>;
}
