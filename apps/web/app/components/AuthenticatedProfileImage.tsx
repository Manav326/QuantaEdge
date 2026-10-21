'use client';

import { useEffect, useState } from 'react';

type AuthenticatedProfileImageProps = {
  src?: string | null;
  fallbackSrc: string;
  alt: string;
  width: number;
  height: number;
  className?: string;
};

export default function AuthenticatedProfileImage({
  src,
  fallbackSrc,
  alt,
  width,
  height,
  className,
}: AuthenticatedProfileImageProps) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let active = true;
    let ownedObjectUrl: string | null = null;
    const controller = new AbortController();

    setObjectUrl(null);
    setFailed(false);

    const imageUrl: string = src ?? '';
    if (!imageUrl) {
      return () => {
        active = false;
        controller.abort();
      };
    }

    async function loadPhoto() {
      try {
        const photoHeaders = new Headers({ Accept: 'image/avif,image/webp,image/png,image/jpeg,image/*;q=0.8' });
        // These photo endpoints are session-protected; native <img> requests cannot attach
        // the workspace header required by AuthFilter, so fetch them as authenticated blobs.
        if (imageUrl.startsWith('/api/v1/students/')) photoHeaders.set('X-QE-Workspace', 'STUDENT');
        else if (imageUrl.startsWith('/api/v1/guardians/')) photoHeaders.set('X-QE-Workspace', 'PARENT');
        const response = await fetch(imageUrl, {
          method: 'GET',
          credentials: 'same-origin',
          cache: 'no-store',
          headers: photoHeaders,
          signal: controller.signal,
        });
        if (!response.ok) throw new Error(`Profile photo request failed (HTTP ${response.status}).`);

        const contentType = (response.headers.get('content-type') || '').split(';', 1)[0].trim().toLowerCase();
        if (!contentType.startsWith('image/')) throw new Error('The profile photo response was not an image.');

        const blob = await response.blob();
        if (blob.size === 0) throw new Error('The profile photo was empty.');

        const url = URL.createObjectURL(blob);
        if (!active) {
          URL.revokeObjectURL(url);
          return;
        }
        ownedObjectUrl = url;
        setObjectUrl(url);
      } catch (error) {
        if (active && !(error instanceof DOMException && error.name === 'AbortError')) {
          setFailed(true);
        }
      }
    }

    void loadPhoto();

    return () => {
      active = false;
      controller.abort();
      if (ownedObjectUrl) URL.revokeObjectURL(ownedObjectUrl);
    };
  }, [src]);

  const imageSrc = src && !failed && objectUrl ? objectUrl : fallbackSrc;

  return (
    <img
      src={imageSrc}
      alt={alt}
      width={width}
      height={height}
      className={className}
      decoding="async"
      onError={() => {
        if (imageSrc !== fallbackSrc) setFailed(true);
      }}
    />
  );
}
