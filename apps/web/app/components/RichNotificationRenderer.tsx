'use client';

import { useEffect, useState, type ReactNode } from 'react';
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

function Asset({ id, alt, kind, surface }: {
  id: unknown; alt?: string; kind: 'image' | 'video'; surface: Surface;
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
  return kind === 'video'
    ? <video className={styles.video} src={url} controls playsInline preload="metadata" aria-label={alt || 'Notification video'} />
    : <img className={styles.image} src={url} alt={alt || ''} loading="lazy" />;
}

function Destination({ target, label, surface }: { target: unknown; label: string; surface: Surface }) {
  const href = safeTarget(target);
  if (!href || !label.trim()) return null;
  const external = /^https:\/\//i.test(href);
  const className = styles.cta;
  return external
    ? <a className={className} href={href} target="_blank" rel="noopener noreferrer">{label}</a>
    : <Link className={className} href={href}>{label}</Link>;
}

function renderBlock(block: Block, index: number, surface: Surface): ReactNode {
  const type = String(block.type || '');
  const text = (key: string, fallback = '') => typeof block[key] === 'string' ? String(block[key]) : fallback;
  const assetId = block.assetId;
  switch (type) {
    case 'hero':
      return <section className={styles.hero} key={index}>
        {text('eyebrow') ? <p className={styles.eyebrow}>{text('eyebrow')}</p> : null}
        {text('title') ? <h3>{text('title')}</h3> : null}
        {text('subtitle') ? <p>{text('subtitle')}</p> : null}
        {typeof assetId === 'string' ? <Asset id={assetId} alt={text('alt', text('title'))} kind="image" surface={surface} /> : null}
      </section>;
    case 'richText':
      return <div className={styles.richText} key={index}>
        {text('text').split(/\n{2,}/).filter(Boolean).map((paragraph, part) => <p key={part}>{paragraph}</p>)}
      </div>;
    case 'image':
      return <figure className={styles.figure} key={index}>
        <Asset id={assetId} alt={Boolean(block.decorative) ? '' : text('alt')} kind="image" surface={surface} />
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
        <Destination target={block.target} label={text('buttonLabel', 'Open course')} surface={surface} />
      </section>;
    case 'eventCard':
      return <section className={styles.dataCard} key={index}>
        <div className={styles.cardKicker}>EVENT</div>
        <h4>{text('title', 'Upcoming event')}</h4>
        {text('dateLabel') ? <p className={styles.eventDate}>{text('dateLabel')}</p> : null}
        {text('description') ? <p>{text('description')}</p> : null}
        <Destination target={block.target} label={text('buttonLabel', 'View details')} surface={surface} />
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
        <Destination target={block.target} label={text('label', 'Open')} surface={surface} />
      </div>;
    case 'footer':
      return <footer className={styles.footer} key={index}>{text('text')}</footer>;
    case 'container':
    case 'carousel': {
      const children = Array.isArray(block.children) ? block.children : Array.isArray(block.items) ? block.items : [];
      return <div className={type === 'carousel' ? styles.carousel : styles.container} key={index}>
        {children.map((child, childIndex) => child && typeof child === 'object'
          ? renderBlock(child as Block, childIndex, surface) : null)}
      </div>;
    }
    default:
      return null;
  }
}

export default function RichNotificationRenderer({
  contentDocument, surface = 'user',
}: { contentDocument: unknown; surface?: Surface }) {
  const document = contentDocument && typeof contentDocument === 'object'
    ? contentDocument as RichDocument : null;
  if (!document || document.version !== 1 || !Array.isArray(document.blocks)) {
    return <div className={styles.invalid}>This notification uses a format this app cannot display.</div>;
  }
  return <div className={styles.document}>
    {document.blocks.map((block, index) => block && typeof block === 'object'
      ? renderBlock(block, index, surface) : null)}
  </div>;
}
