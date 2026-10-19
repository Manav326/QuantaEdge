'use client';

import { useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import Link from 'next/link';
import { apiFetch } from './apiClient';
import styles from './RichNotificationRenderer.module.css';

type Surface = 'user' | 'admin' | 'hrms' | 'institution';
type Block = Record<string, unknown> & { type?: string };
type RichDocument = { version?: number; blocks?: Block[] };

function safeTarget(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const target = value.trim();
  if (target.startsWith('/') && !target.startsWith('//') && !target.includes('\\')) return target;
  try {
    const url = new URL(target);
    if (url.protocol === 'https:' && !url.username && !url.password) return url.toString();
  } catch { /* Unsupported destinations are not rendered as links. */ }
  return null;
}

function mediaEndpoint(surface: Surface, id: string) {
  const prefix = surface === 'user' ? '/api/v1/notifications/media/'
    : surface === 'hrms' ? '/api/v1/hrms/notifications/media/'
    : surface === 'institution' ? '/api/v1/institution/notifications/media/'
    : '/api/v1/admin/notifications/media/';
  return prefix + encodeURIComponent(id);
}

function Asset({ id, alt, kind, surface, fit = 'contain', aspectRatio = 'auto', focalPoint = 'center' }: {
  id: unknown; alt?: string; kind: 'image' | 'video'; surface: Surface;
  fit?: string; aspectRatio?: string; focalPoint?: string;
}) {
  const assetId = typeof id === 'string' && /^[0-9a-f-]{36}$/i.test(id) ? id : '';
  const [url, setUrl] = useState('');
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    let objectUrl = '';
    setUrl('');
    setFailed(false);
    if (!assetId) { setFailed(true); return; }
    void apiFetch(mediaEndpoint(surface, assetId), { cache: 'no-store' })
      .then(async response => {
        if (!response.ok) throw new Error('Media unavailable');
        const blob = await response.blob();
        objectUrl = URL.createObjectURL(blob);
        if (active) setUrl(objectUrl);
        else URL.revokeObjectURL(objectUrl);
      })
      .catch(() => { if (active) setFailed(true); });
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [assetId, surface]);

  if (failed) return <div className={styles.mediaFallback} role="status">Media is unavailable.</div>;
  if (!url) return <div className={styles.mediaSkeleton} aria-label="Loading media" />;
  const ratios: Record<string, string> = { '16:9': '16 / 9', '4:3': '4 / 3', '1:1': '1 / 1', '3:4': '3 / 4' };
  const positions: Record<string, string> = { center: 'center', top: 'center top', bottom: 'center bottom', left: 'left center', right: 'right center' };
  const imageStyle: CSSProperties = {
    objectFit: fit === 'cover' ? 'cover' : 'contain',
    aspectRatio: ratios[aspectRatio] || 'auto',
    objectPosition: positions[focalPoint] || 'center',
  };
  return kind === 'video'
    ? <video className={styles.video} src={url} controls playsInline preload="metadata" aria-label={alt || 'Notification video'} />
    : <img className={styles.image} style={imageStyle} src={url} alt={alt || ''} loading="lazy" />;
}

function Destination({ target, label, surface, editMode = false }: { target: unknown; label: string; surface: Surface; editMode?: boolean }) {
  const href = safeTarget(target);
  if (!href || !label.trim()) return null;
  if (editMode) return <span className={styles.cta}>{label}</span>;
  const external = /^https:\/\//i.test(href);
  const className = styles.cta;
  return external
    ? <a className={className} href={href} target="_blank" rel="noopener noreferrer">{label}</a>
    : <Link className={className} href={href}>{label}</Link>;
}

function blockPresentationStyle(block: Block): CSSProperties {
  const color = (value: unknown): string | undefined =>
    typeof value === 'string' && /^#(?:[0-9a-f]{3}|[0-9a-f]{6})$/i.test(value) ? value : undefined;
  const style: CSSProperties = {};
  const background = color(block.backgroundColor);
  const foreground = color(block.textColor);
  const accent = color(block.accentColor);
  const alignment = String(block.alignment || '');
  if (background) style.backgroundColor = background;
  if (foreground) style.color = foreground;
  if (['left','center','right','justify'].includes(alignment)) {
    style.textAlign = alignment as CSSProperties['textAlign'];
  }
  const padding = Number(block.padding);
  if (Number.isFinite(padding) && padding >= 0 && padding <= 48) style.padding = padding + 'px';
  const radius = Number(block.borderRadius);
  if (Number.isFinite(radius) && radius >= 0 && radius <= 40) style.borderRadius = radius + 'px';
  const fontSize = Number(block.fontSize);
  if (Number.isFinite(fontSize) && fontSize >= 10 && fontSize <= 32) style.fontSize = fontSize + 'px';
  if (accent) Object.assign(style, {'--qe-color-accent': accent} as CSSProperties);
  return style;
}

function renderBlock(block: Block, index: number, surface: Surface, editMode = false): ReactNode {
  const type = String(block.type || '');
  const text = (key: string, fallback = '') => typeof block[key] === 'string' ? String(block[key]) : fallback;
  const assetId = block.assetId;
  switch (type) {
    case 'hero':
      return <section className={styles.hero} key={index}>
        {text('eyebrow') ? <p className={styles.eyebrow}>{text('eyebrow')}</p> : null}
        {text('title') ? <h3>{text('title')}</h3> : null}
        {text('subtitle') ? <p>{text('subtitle')}</p> : null}
        {typeof assetId === 'string' ? <Asset id={assetId} alt={text('alt', text('title'))} kind="image" surface={surface} fit={text('fit','cover')} aspectRatio={text('aspectRatio','16:9')} focalPoint={text('focalPoint','center')} /> : null}
      </section>;
    case 'richText':
      return <div className={styles.richText} key={index}>
        {text('text').split(/\n{2,}/).filter(Boolean).map((paragraph, part) => <p key={part}>{paragraph}</p>)}
      </div>;
    case 'image':
      return <figure className={styles.figure} key={index}>
        <Asset id={assetId} alt={Boolean(block.decorative) ? '' : text('alt')} kind="image" surface={surface} fit={text('fit','cover')} aspectRatio={text('aspectRatio','auto')} focalPoint={text('focalPoint','center')} />
        {text('caption') ? <figcaption>{text('caption')}</figcaption> : null}
      </figure>;
    case 'video':
      return <figure className={styles.figure} key={index}>
        <Asset id={assetId} alt={text('alt', text('title', 'Notification video'))} kind="video" surface={surface} />
        {text('caption') ? <figcaption>{text('caption')}</figcaption> : null}
      </figure>;
    case 'animation':
      return <figure className={styles.figure} key={index}>
        <Asset id={assetId} alt={Boolean(block.decorative) ? '' : text('alt')} kind="image" surface={surface} />
        {text('caption') ? <figcaption>{text('caption')}</figcaption> : null}
      </figure>;
    case 'courseCard':
      return <section className={styles.dataCard} key={index}>
        <div className={styles.cardKicker}>COURSE</div>
        <h4>{text('courseName', text('title', 'Course update'))}</h4>
        {text('description') ? <p>{text('description')}</p> : null}
        <Destination target={block.target} label={text('buttonLabel', 'Open course')} surface={surface} editMode={editMode} />
      </section>;
    case 'eventCard':
      return <section className={styles.dataCard} key={index}>
        <div className={styles.cardKicker}>EVENT</div>
        <h4>{text('title', 'Upcoming event')}</h4>
        {text('dateLabel') ? <p className={styles.eventDate}>{text('dateLabel')}</p> : null}
        {text('description') ? <p>{text('description')}</p> : null}
        <Destination target={block.target} label={text('buttonLabel', 'View details')} surface={surface} editMode={editMode} />
      </section>;
    case 'highlight':
      return <section className={styles.highlight} key={index}>
        <div><span>{text('label')}</span><strong>{text('value')}</strong></div>
        {text('description') ? <p>{text('description')}</p> : null}
      </section>;
    case 'badge':
      return <span className={styles.badge} key={index}>{text('text', text('label'))}</span>;
    case 'icon':
      return <div className={styles.iconRow} key={index}><span aria-hidden="true">{text('symbol', '✦')}</span>{text('text')}</div>;
    case 'divider':
      return <hr className={styles.divider} key={index} />;
    case 'spacer':
      return <div className={styles.spacer} key={index} aria-hidden="true" />;
    case 'cta':
      return <div className={styles.ctaRow} key={index}>
        <Destination target={block.target} label={text('label', 'Open')} surface={surface} editMode={editMode} />
      </div>;
    case 'footer':
      return <footer className={styles.footer} key={index}>{text('text')}</footer>;
    case 'container':
    case 'carousel': {
      const children = Array.isArray(block.children) ? block.children : Array.isArray(block.items) ? block.items : [];
      return <div className={type === 'carousel' ? styles.carousel : styles.container} key={index}>
        {children.map((child, childIndex) => child && typeof child === 'object'
          ? renderBlock(child as Block, childIndex, surface, editMode) : null)}
      </div>;
    }
    default:
      return null;
  }
}

export default function RichNotificationRenderer({
  contentDocument, surface = 'user', editMode = false, selectedBlockIndex = -1,
  onSelectBlock, onReorderBlocks,
}: {
  contentDocument: unknown;
  surface?: Surface;
  editMode?: boolean;
  selectedBlockIndex?: number;
  onSelectBlock?: (index: number) => void;
  onReorderBlocks?: (fromIndex: number, toIndex: number) => void;
}) {
  const document = contentDocument && typeof contentDocument === 'object'
    ? contentDocument as RichDocument : null;
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [dropIndex, setDropIndex] = useState<number | null>(null);
  if (!document || document.version !== 1 || !Array.isArray(document.blocks)) {
    return <div className={styles.invalid}>This notification uses a format this app cannot display.</div>;
  }
  return <div className={styles.document + (editMode ? ' ' + styles.canvasDocument : '')}>
    {document.blocks.map((block, index) => {
      if (!block || typeof block !== 'object') return null;
      const presentationStyle = blockPresentationStyle(block);
      if (!editMode) return <div key={index} style={presentationStyle}>{renderBlock(block, index, surface, false)}</div>;
      const selected = selectedBlockIndex === index;
      const over = dropIndex === index && dragIndex !== index;
      return <div key={index}
        className={styles.editorBlock + (selected ? ' ' + styles.selectedEditorBlock : '') + (over ? ' ' + styles.dropEditorBlock : '')}
        style={presentationStyle}
        draggable
        tabIndex={0}
        role="group"
        aria-label={'Content block ' + (index + 1) + ': ' + String(block.type || 'content') + '. Click to select or drag to reorder.'}
        onClick={event => { event.stopPropagation(); onSelectBlock?.(index); }}
        onKeyDown={event => {
          if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault(); event.stopPropagation(); onSelectBlock?.(index);
          }
        }}
        onDragStart={event => { setDragIndex(index); event.dataTransfer.effectAllowed = 'move'; event.dataTransfer.setData('text/plain', String(index)); }}
        onDragOver={event => { event.preventDefault(); event.dataTransfer.dropEffect = 'move'; setDropIndex(index); }}
        onDragLeave={() => setDropIndex(current => current === index ? null : current)}
        onDrop={event => {
          event.preventDefault();
          const from = Number(event.dataTransfer.getData('text/plain'));
          if (Number.isInteger(from) && from >= 0 && from < document.blocks!.length && from !== index) onReorderBlocks?.(from, index);
          setDragIndex(null); setDropIndex(null);
        }}
        onDragEnd={() => { setDragIndex(null); setDropIndex(null); }}>
        {renderBlock(block, index, surface, true)}
        <span className={styles.editorBlockHandle} aria-hidden="true">⠿ {index + 1}</span>
      </div>;
    })}
  </div>;
}
