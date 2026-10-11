'use client';

import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';

type AuthenticatedPageImageProps = {
  src: string;
  alt: string;
  className?: string;
  style?: CSSProperties;
  onLoad?: () => void;
  onError?: (message: string) => void;
};

async function responseDetails(response: Response): Promise<string> {
  const type = (response.headers.get('content-type') || '').toLowerCase();
  try {
    if (type.includes('json')) {
      const body = await response.clone().json();
      const detail = body?.detail || body?.message || body?.error;
      return typeof detail === 'string' ? detail.slice(0, 240) : '';
    }
    if (type.startsWith('text/plain')) {
      return (await response.clone().text()).trim().slice(0, 240);
    }
  } catch {
    // Keep the status-specific message when the server returns an unreadable error body.
  }
  return '';
}

async function pageRequestError(response: Response): Promise<string> {
  const detail = await responseDetails(response);
  switch (response.status) {
    case 401:
      return 'Your student session could not be renewed. Please sign in again, then reopen this textbook.';
    case 403:
      return detail || 'Your student account does not have access to this PDF. Check that the book is published for your class.';
    case 404:
      return 'This PDF page is unavailable. The document may have been unpublished, removed, or not assigned to your class.';
    case 422:
      return detail || 'The server could not render this PDF page. The stored PDF may be damaged or unsupported.';
    default:
      if (response.status >= 500) {
        return detail && detail !== 'Internal Server Error'
          ? detail
          : 'The PDF service failed while preparing this page (HTTP ' + response.status + '). Please retry.';
      }
      return detail || 'The PDF page request failed (HTTP ' + response.status + ').';
  }
}

/**
 * Fetches protected page images through the workspace fetch interceptor so an
 * expired access cookie can be refreshed before the browser tries to display
 * the page. A plain <img src="/api/..."> request cannot run that recovery code.
 */
export default function AuthenticatedPageImage({
  src,
  alt,
  className,
  style,
  onLoad,
  onError,
}: AuthenticatedPageImageProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const callbacks = useRef({ onLoad, onError });
  callbacks.current = { onLoad, onError };

  useEffect(() => {
    if (!src) {
      setObjectUrl(null);
      return;
    }

    const controller = new AbortController();
    let active = true;
    let createdUrl: string | null = null;
    setObjectUrl(null);

    void (async () => {
      try {
        const response = await fetch(src, {
          method: 'GET',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: { Accept: 'image/png' },
          signal: controller.signal,
        });

        if (!response.ok) throw new Error(await pageRequestError(response));

        const contentType = (response.headers.get('content-type') || '').toLowerCase();
        if (!contentType.startsWith('image/')) {
          const detail = await responseDetails(response);
          throw new Error(detail || 'The server returned ' + (contentType || 'an unexpected response') + ' instead of a PDF page image.');
        }

        const blob = await response.blob();
        if (!blob.size) throw new Error('The server returned an empty image for this PDF page.');

        createdUrl = URL.createObjectURL(blob);
        if (!active) {
          URL.revokeObjectURL(createdUrl);
          return;
        }
        setObjectUrl(createdUrl);
      } catch (error) {
        if (!active || (error instanceof DOMException && error.name === 'AbortError')) return;
        callbacks.current.onError?.(
          error instanceof Error ? error.message : 'The PDF page could not be loaded. Please retry.',
        );
      }
    })();

    return () => {
      active = false;
      controller.abort();
      if (createdUrl) URL.revokeObjectURL(createdUrl);
    };
  }, [src]);

  if (!objectUrl) return null;

  return (
    <img
      src={objectUrl}
      alt={alt}
      className={className}
      style={style}
      draggable={false}
      onLoad={() => callbacks.current.onLoad?.()}
      onError={() => callbacks.current.onError?.('The page was downloaded, but your browser could not display the image. Please retry.') }
    />
  );
}
