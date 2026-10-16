'use client';

import { useState } from 'react';
import type { LessonLocale, StructuredExplainBlock, StructuredLesson } from './contract';
import { isStudentRenderableAsset, readLocale } from './contract';

type Mode = 'student' | 'admin-preview';
type Props = { lesson: StructuredLesson; locale: LessonLocale; mode?: Mode; onActivitySave?: (blockId: string, value: string) => void };

function localized(value: unknown, locale: LessonLocale, fallback = ''): string {
  return readLocale(value, locale) || fallback;
}

export function StructuredDiagramView({ block, locale }: { block: StructuredExplainBlock; locale: LessonLocale }) {
  const data = block.diagram_data || {};
  const nodes = Array.isArray(data.nodes) ? data.nodes as Array<Record<string, any>> : [];
  const edges = Array.isArray(data.edges) ? data.edges as Array<Record<string, any>> : [];
  const value = (entry: unknown) => {
    if (entry && typeof entry === 'object') return localized(entry, locale, '');
    return typeof entry === 'string' ? entry : '';
  };
  const contextLabel = value(data.context_label_by_locale);
  const outcomeLabel = value(data.outcome_by_locale);
  if (!nodes.length) return <div className="qe-structured-muted" role="status">{locale === 'hi' ? 'यह आरेख अभी समीक्षा के लिए तैयार है।' : 'This diagram is not ready for display yet.'}</div>;
  return <figure className="qe-structured-diagram">
    {contextLabel && <p className="qe-structured-diagram__context">{contextLabel}</p>}
    <div className="qe-structured-diagram__nodes">
      {nodes.map((node, index) => <div className="qe-structured-diagram__node" key={String(node.id || index)}>
        <span className="qe-structured-diagram__step">{index + 1}</span>
        <span>{value(node.label_by_locale) || value(node.label) || (locale === 'hi' ? 'चरण' : 'Step')}</span>
      </div>)}
    </div>
    {edges.length > 0 && <details className="qe-structured-diagram__connections">
      <summary>{locale === 'hi' ? 'संबंध देखें' : 'View connections'}</summary>
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

function ActivityBlock({ block, locale, mode, onSave }: { block: StructuredExplainBlock; locale: LessonLocale; mode: Mode; onSave?: Props['onActivitySave'] }) {
  const [responses, setResponses] = useState<Record<string, string>>({});
  const [saved, setSaved] = useState(false);
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
    (locale === 'hi' ? 'क्रियाकलाप' : 'Activity');
  const safety = localized((block as any).safety_note_by_locale, locale) ||
    localized((block as any).safety_and_scope, locale) ||
    localized((block as any).safety, locale);
  const update = (id: string, value: string) => { setResponses(previous => ({ ...previous, [id]: value })); setSaved(false); };
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
      const fieldLabel = localized(field.label_by_locale, locale) || (locale === 'hi' ? 'उत्तर ' + (index + 1) : 'Response ' + (index + 1));
      return <label className="qe-structured-field" key={fieldId}>
        <span>{fieldLabel}</span>
        <textarea value={responses[fieldId] || ''} onChange={event => update(fieldId, event.target.value)} rows={2} />
      </label>;
    }) : <label className="qe-structured-field">
      <span>{locale === 'hi' ? 'अपना अवलोकन या उत्तर लिखें' : 'Record your observation or response'}</span>
      <textarea value={responses.response || ''} onChange={event => update('response', event.target.value)} rows={3} />
    </label>}
    <div className="qe-structured-actions">
      <button type="button" disabled={!onSave} onClick={() => {
        if (!onSave) return;
        onSave(block.id, JSON.stringify(responses));
        setSaved(true);
      }}>
        {String(controls.save || (locale === 'hi' ? 'उत्तर सहेजें' : 'Save response'))}
      </button>
      <button type="button" className="qe-structured-button-secondary" onClick={() => { setResponses({}); setSaved(false); }}>
        {String(controls.reset || (locale === 'hi' ? 'मिटाएँ' : 'Clear'))}
      </button>
      {saved && <span role="status">{locale === 'hi' ? (mode === 'admin-preview' ? 'उत्तर पूर्वावलोकन में सहेजा गया।' : 'सहेजने का अनुरोध भेजा गया।') : (mode === 'admin-preview' ? 'Preview response saved locally.' : 'Save request sent to the host.')}</span>}
      {!onSave && <span role="status">{locale === 'hi' ? 'इस स्क्रीन पर उत्तर सहेजना अभी जुड़ा नहीं है।' : 'Response saving is not connected on this screen yet.'}</span>}
    </div>
  </section>;
}

export default function StructuredLessonContent({ lesson, locale, mode = 'student', onActivitySave }: Props) {
  const [showBlocked, setShowBlocked] = useState(false);
  const blocks = lesson.explain.blocks.slice().sort((a, b) => a.order - b.order);
  return <section className="qe-structured-lesson" aria-label={localized(lesson.topic.title_by_locale, locale, 'Lesson')}>
    <header className="qe-structured-lesson__header">
      <p className="qe-structured-eyebrow">{locale === 'hi' ? 'कक्षा ' : 'Class '}{String((lesson.curriculum as any)?.class ?? 6)} · {localized((lesson.curriculum as any)?.subject_by_locale || (lesson.curriculum as any)?.subject, locale, locale === 'hi' ? 'विज्ञान' : 'Science')}</p>
      <h1>{localized(lesson.chapter.title_by_locale, locale)}</h1>
      <h2>{localized(lesson.topic.title_by_locale, locale)}</h2>
      {lesson.learning_objectives?.length ? <div className="qe-structured-objectives">
        <h3>{locale === 'hi' ? 'आप क्या सीखेंगे' : 'What you will learn'}</h3>
        <ul>{lesson.learning_objectives.map(item => <li key={item.id}>{localized(item.statement_by_locale, locale)}</li>)}</ul>
      </div> : null}
    </header>
    <div className="qe-structured-blocks">
      {blocks.map(block => {
        const text = block.text_by_locale?.[locale];
        const content = block.content_by_locale;
        if (block.type === 'rich_text') return <article className="qe-structured-card" key={block.id}>
          {text?.heading && <h3>{text.heading}</h3>}
          {(text?.paragraphs || []).map((paragraph, index) => <p key={index}>{paragraph}</p>)}
          {typeof text?.callout === 'string' && <aside className="qe-structured-callout">{text.callout}</aside>}
          {typeof text?.key_point === 'string' && <p className="qe-structured-keypoint"><strong>{locale === 'hi' ? 'मुख्य बात:' : 'Key point:'}</strong> {text.key_point}</p>}
        </article>;
        if (block.type === 'image') {
          const asset = block.asset || {};
          const canDisplay = isStudentRenderableAsset(asset);
          const canPreview = canDisplay || mode === 'admin-preview';
          if (!canDisplay && mode === 'student') return null;
          const url = typeof asset.url === 'string' ? asset.url : '';
          const alt = localized(asset.alt_by_locale, locale) || localized(block.alt_by_locale, locale);
          return <figure className="qe-structured-card qe-structured-image" key={block.id}>
            {canPreview && url ? <img src={url} alt={alt} loading="lazy" /> :
              <div className="qe-structured-muted" role="status">{locale === 'hi' ? 'चित्र समीक्षा लंबित है; विद्यार्थी को नहीं दिखाया गया।' : 'Visual review is pending; hidden from students.'}</div>}
            <figcaption>{localized(asset.caption_by_locale, locale) || localized(block.caption_by_locale, locale)}</figcaption>
            {mode === 'admin-preview' && <small>{String(asset.source_book_id || '')} · {locale === 'hi' ? 'PDF पृष्ठ ' : 'PDF page '}{String(asset.source_pdf_page_number || '—')} · {String(asset.review_status || 'UNREVIEWED')}</small>}
          </figure>;
        }
        if (block.type === 'diagram') {
          const data = block.diagram_data || {};
          const reviewStatus = String((block as any).review_status || data.review_status || '').toUpperCase();
          const canDisplay = ((block as any).student_renderable ?? data.student_renderable) === true && ['APPROVED', 'APPROVED_FOR_STUDENT_RENDERING'].includes(reviewStatus);
          const canPreview = canDisplay || mode === 'admin-preview';
          if (!canDisplay && mode === 'student') return null;
          return <article className="qe-structured-card" key={block.id}>
            {!canDisplay && <div className="qe-structured-muted" role="status">{locale === 'hi' ? 'आरेख समीक्षा लंबित है; यह विद्यार्थी को नहीं दिखाया गया।' : 'Diagram review is pending; hidden from students.'}</div>}
            {canPreview && <StructuredDiagramView block={block} locale={locale} />}
            {mode === 'admin-preview' && <small>{locale === 'hi' ? 'दृश्य स्थिति:' : 'Visual status:'} {String(data.review_status || block.review_status || 'UNREVIEWED')}</small>}
          </article>;
        }
        if (block.type === 'interactive' || block.type === 'activity') return <ActivityBlock key={block.id} block={block} locale={locale} mode={mode} onSave={onActivitySave} />;
        if (block.type === 'recap') {
          const selected = typeof content === 'object' && content !== null ? (content as Record<string, unknown>)[locale] : content;
          const recapObject = selected && typeof selected === 'object' ? selected as Record<string, unknown> : null;
          const takeaways = recapObject && Array.isArray(recapObject.key_takeaways) ? recapObject.key_takeaways :
            recapObject && Array.isArray(recapObject.points) ? recapObject.points :
            Array.isArray(selected) ? selected : typeof selected === 'string' ? [selected] : [];
          const vocabulary = recapObject && Array.isArray(recapObject.vocabulary) ? recapObject.vocabulary as Array<Record<string, unknown>> : [];
          return <aside className="qe-structured-card qe-structured-recap" key={block.id}>
            <h3>{locale === 'hi' ? 'दोहराएँ' : 'Recap'}</h3>
            {takeaways.map((point, index) => <p key={index}>✓ {String(point)}</p>)}
            {vocabulary.length > 0 && <div className="qe-structured-vocabulary">
              <h4>{locale === 'hi' ? 'मुख्य शब्द' : 'Key vocabulary'}</h4>
              <dl>{vocabulary.map((item, index) => <div key={String(item.term || index)}>
                <dt>{String(item.term || '')}</dt><dd>{String(item.meaning || '')}</dd>
              </div>)}</dl>
            </div>}
          </aside>;
        }
        if (mode === 'admin-preview') return <div className="qe-structured-muted" key={block.id} role="status">{locale === 'hi' ? 'असमर्थित सामग्री ब्लॉक:' : 'Unsupported content block:'} {block.type}</div>;
        return null;
      })}
    </div>
    {mode === 'admin-preview' && <details className="qe-structured-debug">
      <summary>{locale === 'hi' ? 'लेखक/समीक्षा विवरण' : 'Author/review details'}</summary>
      <p>{locale === 'hi' ? 'कलाकृति:' : 'Artifact:'} {lesson.artifact_id}</p>
      <p>{locale === 'hi' ? 'प्रकाशन अनुमति:' : 'Publication allowed:'} {String(lesson.workflow?.publication_allowed === true)}</p>
      <button type="button" onClick={() => setShowBlocked(value => !value)}>{showBlocked ? (locale === 'hi' ? 'समीक्षा नोट छिपाएँ' : 'Hide review note') : (locale === 'hi' ? 'समीक्षा नोट दिखाएँ' : 'Show review note')}</button>
      {showBlocked && <p>{localized(lesson.topic.title_by_locale, locale)} · {String(lesson.workflow?.status || 'UNKNOWN')} · {String(lesson.workflow?.source_completeness_status || 'SOURCE STATUS UNKNOWN')}</p>}
    </details>}
  </section>;
}
