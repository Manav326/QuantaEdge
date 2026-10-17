'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import styles from './AssignedLearningResources.module.css';

type AssignedResource = {
  resourceId: number;
  assignmentId: number;
  resourceType: string;
  title: string;
  description?: string | null;
  language?: string;
  sourceKind: string;
  mediaType?: string | null;
  fileSizeBytes?: number | null;
  durationSeconds?: number | null;
  downloadAllowed: boolean;
  lastPositionSeconds?: number;
  watchedSeconds?: number;
  completed?: boolean;
  streamUrl?: string;
  downloadUrl?: string;
  readerApiUrl?: string;
  learningDocumentId?: number;
  assetUrl?: string;
  externalUrl?: string;
};

type WatchState = { lastTime: number; watchedSeconds: number; lastSaved: number };

function formatSize(size?: number | null): string {
  if (!size || size <= 0) return '';
  return size >= 1024 * 1024 ? (size / (1024 * 1024)).toFixed(1) + ' MB' : Math.max(1, Math.round(size / 1024)) + ' KB';
}

export default function AssignedLearningResources({ artifactId, language }: { artifactId: string; language: 'hi' | 'en' }) {
  const [resources, setResources] = useState<AssignedResource[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const watch = useRef(new Map<number, WatchState>());
  const hi = language === 'hi';
  const t = (hindi: string, english: string) => hi ? hindi : english;

  const load = useCallback(async (signal?: AbortSignal) => {
    setLoading(true);
    setError('');
    try {
      const query = new URLSearchParams({ artifactId, language });
      const response = await fetch('/api/v1/learning/resources?' + query.toString(), {
        credentials: 'same-origin', cache: 'no-store', signal,
      });
      if (response.status === 401 || response.status === 403) throw new Error(t('संसाधन देखने के लिए विद्यार्थी खाते में साइन इन करें।', 'Sign in to your student account to view these resources.'));
      const body = await response.json();
      if (!response.ok) throw new Error(body.detail || body.message || body.error || t('संसाधन लोड नहीं हो पाए।', 'Resources could not be loaded.'));
      const next = Array.isArray(body) ? body as AssignedResource[] : [];
      setResources(next);
      for (const item of next) {
        if (!watch.current.has(item.resourceId)) watch.current.set(item.resourceId, {
          lastTime: Math.max(0, item.lastPositionSeconds || 0),
          watchedSeconds: Math.max(0, item.watchedSeconds || 0),
          lastSaved: Math.max(0, item.lastPositionSeconds || 0),
        });
      }
    } catch (cause) {
      if (cause instanceof DOMException && cause.name === 'AbortError') return;
      setError(cause instanceof Error ? cause.message : t('संसाधन लोड नहीं हो पाए।', 'Resources could not be loaded.'));
    } finally {
      setLoading(false);
    }
  }, [artifactId, language]);

  useEffect(() => {
    const controller = new AbortController();
    void load(controller.signal);
    return () => controller.abort();
  }, [load]);

  const saveProgress = useCallback(async (item: AssignedResource, position: number, completed: boolean) => {
    const state = watch.current.get(item.resourceId) || { lastTime: position, watchedSeconds: item.watchedSeconds || 0, lastSaved: 0 };
    state.lastSaved = position;
    watch.current.set(item.resourceId, state);
    try {
      const response = await fetch('/api/v1/learning/resources/' + item.resourceId + '/progress?assignmentId=' + item.assignmentId, {
        method: 'PUT', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ lastPositionSeconds: Math.max(0, Math.floor(position)), watchedSeconds: Math.max(0, Math.floor(state.watchedSeconds)), completed }),
      });
      if (!response.ok) throw new Error(t('वीडियो प्रगति सुरक्षित नहीं हो सकी।', 'Video progress could not be saved.'));
      setResources(current => current.map(row => row.resourceId === item.resourceId ? {
        ...row, lastPositionSeconds: position, watchedSeconds: state.watchedSeconds, completed,
      } : row));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('वीडियो प्रगति सुरक्षित नहीं हो सकी।', 'Video progress could not be saved.'));
    }
  }, [hi]);

  function onTimeUpdate(item: AssignedResource, element: HTMLMediaElement) {
    const state = watch.current.get(item.resourceId) || { lastTime: 0, watchedSeconds: item.watchedSeconds || 0, lastSaved: 0 };
    const position = Math.max(0, element.currentTime || 0);
    const delta = position - state.lastTime;
    if (!element.paused && delta > 0 && delta <= 3.5) state.watchedSeconds += delta;
    state.lastTime = position;
    watch.current.set(item.resourceId, state);
    if (Math.abs(position - state.lastSaved) >= 10) void saveProgress(item, position, false);
  }

  return <section className={styles.section} aria-labelledby="assigned-learning-resources-heading">
    <div className={styles.heading}>
      <div>
        <p className={styles.eyebrow}>{t('इस पाठ के साथ', 'FOR THIS LESSON')}</p>
        <h2 id="assigned-learning-resources-heading">{t('सीखने के संसाधन', 'Learning resources')}</h2>
        <p>{t('शिक्षक द्वारा जोड़े गए वीडियो, ऑडियो, PDF और अन्य सामग्री।', 'Videos, audio, PDFs and other materials attached by your teacher.')}</p>
      </div>
      <button type="button" className={styles.refresh} onClick={() => void load()} disabled={loading}>{t('रिफ्रेश', 'Refresh')}</button>
    </div>
    {loading && <p role="status" className={styles.muted}>{t('संसाधन लोड हो रहे हैं…', 'Loading resources…')}</p>}
    {error && <p role="alert" className={styles.error}>{error}</p>}
    {!loading && !error && resources.length === 0 && <p className={styles.empty}>{t('इस पाठ के लिए अभी कोई अतिरिक्त संसाधन प्रकाशित नहीं है।', 'No additional resources have been published for this lesson yet.')}</p>}
    {resources.length > 0 && <div className={styles.grid}>
      {resources.map(item => {
        const kind = String(item.resourceType || '').toUpperCase();
        const isVideo = kind === 'VIDEO';
        const isAudio = kind === 'AUDIO';
        const mediaUrl = item.streamUrl || item.assetUrl || '';
        const position = Math.max(0, item.lastPositionSeconds || 0);
        return <article className={styles.card} key={item.assignmentId + ':' + item.resourceId}>
          <div className={styles.cardHeading}>
            <span className={styles.kind}>{kind.replaceAll('_', ' ') || 'RESOURCE'}</span>
            {item.fileSizeBytes ? <span className={styles.muted}>{formatSize(item.fileSizeBytes)}</span> : null}
          </div>
          <h3>{item.title}</h3>
          {item.description && <p className={styles.description}>{item.description}</p>}
          {isVideo && item.streamUrl && <video className={styles.media} controls preload="metadata"
            src={item.streamUrl} onLoadedMetadata={event => {
              const video = event.currentTarget;
              if (position > 0 && Number.isFinite(video.duration)) video.currentTime = Math.min(position, Math.max(0, video.duration - 1));
            }} onTimeUpdate={event => onTimeUpdate(item, event.currentTarget)}
            onEnded={event => void saveProgress(item, event.currentTarget.currentTime, true)} />}
          {isAudio && item.streamUrl && <audio className={styles.audio} controls preload="metadata" src={item.streamUrl}
            onLoadedMetadata={event => { if (position > 0) event.currentTarget.currentTime = position; }}
            onTimeUpdate={event => onTimeUpdate(item, event.currentTarget)}
            onEnded={event => void saveProgress(item, event.currentTarget.currentTime, true)} />}
          {kind === 'IMAGE' && mediaUrl && <a className={styles.openLink} href={mediaUrl} target="_blank" rel="noreferrer">{t('चित्र खोलें', 'Open image')}</a>}
          {item.externalUrl && <a className={styles.openLink} href={item.externalUrl} target="_blank" rel="noopener noreferrer">{t('संसाधन खोलें', 'Open resource')} ↗</a>}
          {kind === 'PDF' && item.learningDocumentId && <Link className={styles.openLink} href="/student/textbooks">{t('पाठ्यपुस्तक लाइब्रेरी में खोलें', 'Open textbook library')} ↗</Link>}
          {item.sourceKind === 'CONTENT_ASSET' && item.assetUrl && !isVideo && !isAudio && kind !== 'IMAGE' &&
            <a className={styles.openLink} href={item.assetUrl} target="_blank" rel="noopener noreferrer">{t('संसाधन खोलें', 'Open resource')} ↗</a>}
          {item.streamUrl && !isVideo && !isAudio && kind !== 'IMAGE' && !item.learningDocumentId && !item.assetUrl && !item.externalUrl &&
            <a className={styles.openLink} href={item.streamUrl} target="_blank" rel="noopener noreferrer">{t('संसाधन ऑनलाइन खोलें', 'Open resource online')} ↗</a>}
          {item.downloadUrl && item.downloadAllowed && <a className={styles.downloadLink} href={item.downloadUrl} download>{t('डिवाइस पर डाउनलोड करें', 'Download to device')} ↓</a>}
          {(isVideo || isAudio) && !item.streamUrl && !item.externalUrl && <p className={styles.muted}>{t('इस संसाधन के लिए ऑनलाइन प्लेयर उपलब्ध नहीं है।', 'An online player is not available for this resource.')}</p>}
          {item.completed && <p className={styles.completed}>{t('देखना पूरा हुआ', 'Completed')}</p>}
        </article>;
      })}
    </div>}
  </section>;
}
