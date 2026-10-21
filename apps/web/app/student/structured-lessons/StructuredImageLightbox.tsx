'use client';

import { useEffect, useState } from 'react';

type Props = {
  url: string;
  alt: string;
  caption?: string;
  locale: 'en' | 'hi';
};

export default function StructuredImageLightbox({ url, alt, caption = '', locale }: Props) {
  const [open, setOpen] = useState(false);
  const openLabel = locale === 'hi' ? 'चित्र बड़ा करके देखें' : 'View larger image';
  const closeLabel = locale === 'hi' ? 'बंद करें' : 'Close image';
  const dialogLabel = caption || alt || (locale === 'hi' ? 'चित्र पूर्वावलोकन' : 'Image preview');

  useEffect(() => {
    if (!open) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [open]);

  if (!url) {
    return <div className="qe-structured-muted" role="status">
      {locale === 'hi' ? 'चित्र उपलब्ध नहीं है।' : 'Image is unavailable.'}
    </div>;
  }

  return <>
    <button
      type="button"
      className="qe-structured-image-lightbox__trigger"
      aria-label={openLabel}
      aria-haspopup="dialog"
      onClick={() => setOpen(true)}
    >
      <img src={url} alt={alt} loading="lazy" />
      <span aria-hidden="true">{locale === 'hi' ? 'बड़ा करके देखें ↗' : 'View larger ↗'}</span>
    </button>
    {open && <div
      className="qe-structured-image-lightbox__backdrop"
      role="presentation"
      onClick={event => { if (event.target === event.currentTarget) setOpen(false); }}
    >
      <section
        className="qe-structured-image-lightbox__dialog"
        role="dialog"
        aria-modal="true"
        aria-label={dialogLabel}
        tabIndex={-1}
      >
        <header className="qe-structured-image-lightbox__toolbar">
          <p>{caption || alt}</p>
          <button type="button" autoFocus onClick={() => setOpen(false)} aria-label={closeLabel}>
            <span aria-hidden="true">×</span>
          </button>
        </header>
        <img className="qe-structured-image-lightbox__full-image" src={url} alt={alt} />
        {caption && <p className="qe-structured-image-lightbox__caption">{caption}</p>}
      </section>
    </div>}
  </>;
}
