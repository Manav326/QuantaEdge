'use client';

import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react';
import styles from './Institutions.module.css';

type AssetType = 'logo' | 'app-icon';
type Aspect = 'original' | 'wide' | 'landscape' | 'square';
type Point = { x: number; y: number };
type Props = { assetType: AssetType; file: File; onCancel: () => void; onConfirm: (file: File) => void };
type Size = { width: number; height: number };
type DragStart = { x: number; y: number; offsetX: number; offsetY: number };

export default function InstitutionBrandAssetEditor({ assetType, file, onCancel, onConfirm }: Props) {
  const [sourceUrl, setSourceUrl] = useState('');
  const [naturalSize, setNaturalSize] = useState<Size>({ width: 0, height: 0 });
  const [stageSize, setStageSize] = useState<Size>({ width: 560, height: 320 });
  const [aspect, setAspect] = useState<Aspect>(assetType === 'app-icon' ? 'square' : 'original');
  const [zoom, setZoom] = useState(1);
  const [offset, setOffset] = useState<Point>({ x: 0, y: 0 });
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState('');
  const stageRef = useRef<HTMLDivElement>(null);
  const imageRef = useRef<HTMLImageElement>(null);
  const dragRef = useRef<DragStart | null>(null);

  useEffect(() => {
    const url = URL.createObjectURL(file);
    setSourceUrl(url);
    setNaturalSize({ width: 0, height: 0 });
    setZoom(1);
    setOffset({ x: 0, y: 0 });
    setError('');
    return () => URL.revokeObjectURL(url);
  }, [file]);

  useEffect(() => {
    const stage = stageRef.current;
    if (!stage) return;
    const measure = () => {
      const bounds = stage.getBoundingClientRect();
      setStageSize({ width: Math.max(1, bounds.width), height: Math.max(1, bounds.height) });
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(stage);
    return () => observer.disconnect();
  }, [sourceUrl]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !exporting) onCancel();
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [exporting, onCancel]);

  const ratio = useMemo(() => {
    if (assetType === 'app-icon' || aspect === 'square') return 1;
    if (aspect === 'wide') return 3;
    if (aspect === 'landscape') return 2;
    return naturalSize.width > 0 && naturalSize.height > 0 ? naturalSize.width / naturalSize.height : 1.5;
  }, [assetType, aspect, naturalSize.width, naturalSize.height]);

  const frame = useMemo(() => {
    const maxWidth = stageSize.width * 0.82;
    const maxHeight = stageSize.height * 0.78;
    if (maxWidth / ratio <= maxHeight) return { width: maxWidth, height: maxWidth / ratio };
    return { width: maxHeight * ratio, height: maxHeight };
  }, [stageSize, ratio]);

  const fitScale = naturalSize.width && naturalSize.height ? Math.min(stageSize.width / naturalSize.width, stageSize.height / naturalSize.height) : 1;
  const minZoom = naturalSize.width && naturalSize.height && frame.width && frame.height ? Math.max(frame.width / (naturalSize.width * fitScale), frame.height / (naturalSize.height * fitScale)) : 1;
  const effectiveZoom = Math.max(zoom, minZoom);
  const displayScale = fitScale * effectiveZoom;
  const imageWidth = naturalSize.width * displayScale;
  const imageHeight = naturalSize.height * displayScale;
  const maxPanX = Math.max(0, (imageWidth - frame.width) / 2);
  const maxPanY = Math.max(0, (imageHeight - frame.height) / 2);
  const offsetX = Math.max(-maxPanX, Math.min(maxPanX, offset.x));
  const offsetY = Math.max(-maxPanY, Math.min(maxPanY, offset.y));
  const frameLeft = (stageSize.width - frame.width) / 2;
  const frameTop = (stageSize.height - frame.height) / 2;
  const imageLeft = (stageSize.width - imageWidth) / 2 + offsetX;
  const imageTop = (stageSize.height - imageHeight) / 2 + offsetY;
  const maxZoom = Math.max(minZoom + 1.6, minZoom * 3);

  useEffect(() => {
    if (!naturalSize.width || !naturalSize.height) return;
    setZoom(minZoom);
    setOffset({ x: 0, y: 0 });
  }, [naturalSize.width, naturalSize.height, minZoom, aspect]);

  function onPointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    if (!naturalSize.width || exporting) return;
    dragRef.current = { x: event.clientX, y: event.clientY, offsetX, offsetY };
    event.currentTarget.setPointerCapture(event.pointerId);
  }
  function onPointerMove(event: ReactPointerEvent<HTMLDivElement>) {
    const start = dragRef.current;
    if (!start) return;
    setOffset({
      x: Math.max(-maxPanX, Math.min(maxPanX, start.offsetX + event.clientX - start.x)),
      y: Math.max(-maxPanY, Math.min(maxPanY, start.offsetY + event.clientY - start.y))
    });
  }
  function onPointerUp() { dragRef.current = null; }
  async function canvasBlob(canvas: HTMLCanvasElement): Promise<Blob | null> {
    return new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
  }

  async function confirmCrop() {
    const image = imageRef.current;
    if (!image || !naturalSize.width || !naturalSize.height || !frame.width || !frame.height) {
      setError('The image is still loading. Please wait a moment and try again.');
      return;
    }
    setExporting(true);
    setError('');
    try {
      const sx = Math.max(0, Math.min(naturalSize.width - 1, (frameLeft - imageLeft) / displayScale));
      const sy = Math.max(0, Math.min(naturalSize.height - 1, (frameTop - imageTop) / displayScale));
      const sw = Math.max(1, Math.min(naturalSize.width - sx, frame.width / displayScale));
      const sh = Math.max(1, Math.min(naturalSize.height - sy, frame.height / displayScale));
      const targetLongestEdge = assetType === 'app-icon' ? 512 : 1024;
      const targetWidth = ratio >= 1 ? targetLongestEdge : Math.max(1, Math.round(targetLongestEdge * ratio));
      const targetHeight = ratio >= 1 ? Math.max(1, Math.round(targetLongestEdge / ratio)) : targetLongestEdge;
      const canvas = document.createElement('canvas');
      let shrink = 1;
      let blob: Blob | null = null;
      while (shrink >= 0.34) {
        canvas.width = Math.max(1, Math.round(targetWidth * shrink));
        canvas.height = Math.max(1, Math.round(targetHeight * shrink));
        const context = canvas.getContext('2d');
        if (!context) throw new Error('This browser could not create an image canvas.');
        context.clearRect(0, 0, canvas.width, canvas.height);
        context.drawImage(image, sx, sy, sw, sh, 0, 0, canvas.width, canvas.height);
        blob = await canvasBlob(canvas);
        if (blob && blob.size <= 2 * 1024 * 1024) break;
        shrink *= 0.82;
      }
      if (!blob || blob.size > 2 * 1024 * 1024) throw new Error('This image cannot be reduced below the 2 MiB upload limit. Try a simpler or smaller source image.');
      const name = file.name.replace(/\.[^.]+$/, '') || (assetType === 'logo' ? 'school-logo' : 'school-app-icon');
      onConfirm(new File([blob], name + '-cropped.png', { type: 'image/png', lastModified: Date.now() }));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The image could not be cropped. Try another file.');
    } finally {
      setExporting(false);
    }
  }

  const choices: { id: Aspect; label: string }[] = assetType === 'app-icon'
    ? [{ id: 'square', label: 'Square 1:1' }]
    : [{ id: 'original', label: 'Original ratio' }, { id: 'wide', label: 'Wide 3:1' }, { id: 'landscape', label: 'Landscape 2:1' }, { id: 'square', label: 'Square 1:1' }];

  return <div className={styles.cropModalBackdrop} onMouseDown={event => { if (event.target === event.currentTarget && !exporting) onCancel(); }}>
    <section className={styles.cropModal} role="dialog" aria-modal="true" aria-labelledby="brand-crop-title">
      <header className={styles.cropModalHeader}>
        <div><span className={styles.eyebrow}>{assetType === 'logo' ? 'SCHOOL LOGO' : 'APP ICON'}</span><h2 id="brand-crop-title">Crop and resize image</h2><p>Drag the image to reposition it inside the frame, then choose a size and preview the result.</p></div>
        <button type="button" className={styles.quiet} onClick={onCancel} disabled={exporting} aria-label="Close crop editor" autoFocus>✕</button>
      </header>
      <div className={styles.cropStage} ref={stageRef} onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerUp}>
        {sourceUrl ? <img ref={imageRef} src={sourceUrl} alt="Source image being cropped" draggable={false} onLoad={event => {setNaturalSize({width:event.currentTarget.naturalWidth,height:event.currentTarget.naturalHeight});setZoom(1);setOffset({x:0,y:0});}} onError={() => setError('This file is not a readable PNG/JPEG image. Choose another file.')} className={styles.cropSourceImage} style={{width:imageWidth+'px',height:imageHeight+'px',left:imageLeft+'px',top:imageTop+'px'}} /> : null}
        {naturalSize.width > 0 ? <div className={styles.cropFrame} style={{left:frameLeft+'px',top:frameTop+'px',width:frame.width+'px',height:frame.height+'px',boxShadow:'0 0 0 999px rgba(15,23,42,.58)'}} /> : null}
      </div>
      <div className={styles.cropControls}>
        <div className={styles.cropRatioControls} aria-label="Crop shape">
          {choices.map(choice => <button type="button" key={choice.id} className={styles.cropRatioButton+(aspect===choice.id ? ' '+styles.cropRatioButtonActive : '')} onClick={() => {setAspect(choice.id);setOffset({x:0,y:0});}} aria-pressed={aspect===choice.id} disabled={exporting}>{choice.label}</button>)}
        </div>
        <label className={styles.cropZoom}><span className={styles.cropZoomLabel}><span>Zoom</span><span>{Math.round(effectiveZoom*100)}%</span></span><input type="range" min={minZoom} max={maxZoom} step="0.01" value={Math.min(maxZoom,Math.max(minZoom,effectiveZoom))} onChange={event => {setZoom(Number(event.target.value));setOffset({x:0,y:0});}} disabled={!naturalSize.width||exporting} /></label>
      </div>
      <p className={styles.cropHelp}>The saved image is normalized to PNG, sized for the selected crop, and kept within the existing 2 MiB server upload limit. Your original file is not modified.</p>
      {error ? <div className={styles.cropError} role="alert">{error}</div> : null}
      <footer className={styles.cropModalFooter}><span>{naturalSize.width ? naturalSize.width+' × '+naturalSize.height+' source pixels' : 'Loading image…'} · Output: {assetType==='app-icon'?'up to 512 × 512':'up to 1024 px on the longest edge'}</span><div className={styles.cardActions}><button type="button" className={styles.secondary} onClick={onCancel} disabled={exporting}>Cancel</button><button type="button" className={styles.primary} onClick={() => void confirmCrop()} disabled={!naturalSize.width||exporting}>{exporting?'Preparing image…':'Use cropped image'}</button></div></footer>
    </section>
  </div>;
}
