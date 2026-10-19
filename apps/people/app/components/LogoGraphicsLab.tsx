'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { apiFetch } from './apiClient';
import styles from './LogoGraphicsLab.module.css';

type Surface = 'admin' | 'hrms' | 'institution';
type LogoMark = 'book' | 'spark' | 'shield' | 'leaf' | 'orbit' | 'star';
type LogoLayout = 'horizontal' | 'stacked' | 'icon';
type LogoStyle = 'clean' | 'friendly' | 'classic' | 'geometric';
type Palette = { name: string; primary: string; secondary: string; ink: string; background: string };
type LogoAsset = { assetId: string; contentType: string; byteLength: number; width: number; height: number };

const PALETTES: Palette[] = [
  { name: 'QuantaEdge Orange', primary: '#F97316', secondary: '#FDBA74', ink: '#18243A', background: '#FFFFFF' },
  { name: 'Midnight & Gold', primary: '#D6A84F', secondary: '#F3DEAA', ink: '#202A42', background: '#FFFFFF' },
  { name: 'Forest', primary: '#187A59', secondary: '#91D2B1', ink: '#16372E', background: '#FFFFFF' },
  { name: 'Ocean', primary: '#2878B8', secondary: '#91C5EA', ink: '#16314B', background: '#FFFFFF' },
  { name: 'Violet', primary: '#7656C8', secondary: '#C3B0F5', ink: '#302653', background: '#FFFFFF' },
  { name: 'Monochrome', primary: '#253247', secondary: '#AAB4C2', ink: '#253247', background: '#FFFFFF' }
];

const MARKS: Array<{ value: LogoMark; label: string; glyph: string }> = [
  { value: 'book', label: 'Open book', glyph: '▤' },
  { value: 'spark', label: 'Spark', glyph: '✦' },
  { value: 'shield', label: 'Shield', glyph: '⬟' },
  { value: 'leaf', label: 'Leaf', glyph: '❧' },
  { value: 'orbit', label: 'Orbit', glyph: '◉' },
  { value: 'star', label: 'Star', glyph: '★' }
];

function roundedRect(ctx: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, radius: number) {
  const r = Math.min(radius, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + r, y); ctx.lineTo(x + w - r, y); ctx.quadraticCurveTo(x + w, y, x + w, y + r);
  ctx.lineTo(x + w, y + h - r); ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  ctx.lineTo(x + r, y + h); ctx.quadraticCurveTo(x, y + h, x, y + h - r);
  ctx.lineTo(x, y + r); ctx.quadraticCurveTo(x, y, x + r, y); ctx.closePath();
}

function drawMark(ctx: CanvasRenderingContext2D, mark: LogoMark, x: number, y: number, size: number,
  primary: string, secondary: string, style: LogoStyle, variant: number) {
  ctx.save();
  ctx.translate(x, y);
  const scale = size / 200;
  ctx.scale(scale, scale);
  const grad = ctx.createLinearGradient(-70, -75, 75, 78);
  grad.addColorStop(0, primary); grad.addColorStop(1, secondary);
  if (style === 'friendly') {
    ctx.fillStyle = secondary; ctx.beginPath(); ctx.arc(0, 0, 91, 0, Math.PI * 2); ctx.fill();
  } else if (style === 'classic') {
    ctx.strokeStyle = primary; ctx.lineWidth = 7; ctx.beginPath(); ctx.arc(0, 0, 91, 0, Math.PI * 2); ctx.stroke();
  } else if (style === 'geometric') {
    ctx.fillStyle = primary; ctx.beginPath();
    for (let i = 0; i < 6; i++) {
      const a = Math.PI / 3 * i - Math.PI / 2;
      const px = Math.cos(a) * 94, py = Math.sin(a) * 94;
      i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    }
    ctx.closePath(); ctx.fill();
  }
  ctx.fillStyle = style === 'friendly' ? primary : grad;
  ctx.strokeStyle = style === 'friendly' ? primary : grad;
  ctx.lineWidth = 11; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
  if (mark === 'book') {
    ctx.beginPath();
    ctx.moveTo(-66, -46); ctx.quadraticCurveTo(-26, -60, -5, -33); ctx.lineTo(-5, 58);
    ctx.quadraticCurveTo(-31, 32, -66, 44); ctx.closePath(); ctx.fill();
    ctx.beginPath(); ctx.moveTo(66, -46); ctx.quadraticCurveTo(26, -60, 5, -33); ctx.lineTo(5, 58);
    ctx.quadraticCurveTo(31, 32, 66, 44); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = secondary; ctx.lineWidth = 7; ctx.beginPath(); ctx.moveTo(0, -29); ctx.lineTo(0, 61); ctx.stroke();
    ctx.strokeStyle = primary; ctx.lineWidth = 5;
    [-20, 0, 20].forEach(offset => { ctx.beginPath(); ctx.moveTo(-53, offset - 18); ctx.lineTo(-22, offset - 18); ctx.stroke(); ctx.beginPath(); ctx.moveTo(22, offset - 18); ctx.lineTo(53, offset - 18); ctx.stroke(); });
  } else if (mark === 'spark' || mark === 'star') {
    const points = mark === 'star' ? 5 : 8;
    const outer = mark === 'star' ? 78 : 83, inner = mark === 'star' ? 34 : 17;
    ctx.beginPath();
    for (let i = 0; i < points * 2; i++) {
      const a = Math.PI * i / points - Math.PI / 2 + variant * .15;
      const radius = i % 2 === 0 ? outer : inner;
      const px = Math.cos(a) * radius, py = Math.sin(a) * radius;
      i === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py);
    }
    ctx.closePath(); ctx.fill();
    if (mark === 'spark') {
      ctx.fillStyle = secondary; ctx.beginPath(); ctx.arc(45, -48, 12, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = primary; ctx.beginPath(); ctx.arc(-52, 42, 8, 0, Math.PI * 2); ctx.fill();
    }
  } else if (mark === 'shield') {
    ctx.beginPath(); ctx.moveTo(0, -82); ctx.lineTo(66, -56); ctx.lineTo(57, 26);
    ctx.quadraticCurveTo(38, 66, 0, 84); ctx.quadraticCurveTo(-38, 66, -57, 26);
    ctx.lineTo(-66, -56); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = secondary; ctx.lineWidth = 7; ctx.beginPath(); ctx.moveTo(-27, 0); ctx.lineTo(-6, 23); ctx.lineTo(33, -25); ctx.stroke();
  } else if (mark === 'leaf') {
    ctx.beginPath(); ctx.moveTo(-5, 80); ctx.bezierCurveTo(-88, 44, -75, -42, 70, -78);
    ctx.bezierCurveTo(77, 5, 44, 63, -5, 80); ctx.closePath(); ctx.fill();
    ctx.strokeStyle = secondary; ctx.lineWidth = 8; ctx.beginPath(); ctx.moveTo(-49, 48); ctx.quadraticCurveTo(4, 5, 58, -58); ctx.stroke();
  } else if (mark === 'orbit') {
    ctx.beginPath(); ctx.arc(0, 0, 42, 0, Math.PI * 2); ctx.fill();
    ctx.strokeStyle = primary; ctx.lineWidth = 12; ctx.beginPath(); ctx.ellipse(0, 0, 81, 35, -.6 + variant * .2, 0, Math.PI * 2); ctx.stroke();
    ctx.fillStyle = secondary; ctx.beginPath(); ctx.arc(61, -46, 14, 0, Math.PI * 2); ctx.fill();
  }
  ctx.restore();
}

function drawLogo(canvas: HTMLCanvasElement, options: {
  name: string; tagline: string; mark: LogoMark; layout: LogoLayout; style: LogoStyle;
  palette: Palette; transparent: boolean; background: string; variant: number;
}) {
  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const width = 1200, height = 700;
  canvas.width = width; canvas.height = height;
  ctx.clearRect(0, 0, width, height);
  if (!options.transparent) {
    ctx.fillStyle = options.background;
    roundedRect(ctx, 0, 0, width, height, 38); ctx.fill();
  }
  const cleanName = options.name.trim().slice(0, 60) || 'Your Brand';
  const tag = options.tagline.trim().slice(0, 90);
  const { palette, mark, layout, style, variant } = options;
  const horizontal = layout === 'horizontal';
  const iconOnly = layout === 'icon';
  const mx = horizontal ? 245 : 600;
  const my = horizontal ? 350 : (iconOnly ? 350 : 245);
  const markSize = iconOnly ? 270 : 205;
  drawMark(ctx, mark, mx, my, markSize, variant % 2 ? palette.secondary : palette.primary, variant % 2 ? palette.primary : palette.secondary, style, variant);
  if (!iconOnly) {
    ctx.fillStyle = palette.ink;
    ctx.textAlign = horizontal ? 'left' : 'center';
    ctx.textBaseline = 'middle';
    const nameX = horizontal ? 400 : 600;
    const nameY = horizontal ? (tag ? 330 : 354) : 455;
    const maxNameWidth = horizontal ? 700 : 930;
    let fontSize = style === 'classic' ? 72 : style === 'friendly' ? 80 : 76;
    ctx.font = (style === 'classic' ? '700 ' : '800 ') + fontSize + 'px ' + (style === 'classic' ? 'Georgia, serif' : 'Arial, Helvetica, sans-serif');
    while (ctx.measureText(cleanName).width > maxNameWidth && fontSize > 32) {
      fontSize -= 2;
      ctx.font = (style === 'classic' ? '700 ' : '800 ') + fontSize + 'px ' + (style === 'classic' ? 'Georgia, serif' : 'Arial, Helvetica, sans-serif');
    }
    ctx.fillText(cleanName, nameX, nameY, maxNameWidth);
    if (tag) {
      ctx.font = '500 28px Arial, Helvetica, sans-serif';
      ctx.fillStyle = palette.ink;
      ctx.globalAlpha = .76;
      ctx.fillText(tag, nameX, horizontal ? 400 : 512, maxNameWidth);
      ctx.globalAlpha = 1;
    }
    if (style === 'geometric') {
      ctx.fillStyle = palette.primary;
      roundedRect(ctx, horizontal ? 401 : 500, horizontal ? 444 : 550, horizontal ? 130 : 200, 8, 4);
      ctx.fill();
    }
  }
}

export default function LogoGraphicsLab({ surface, onUseInNotification }: {
  surface: Surface; onUseInNotification: (assetId: string, fileName: string) => void;
}) {
  const canvases = useRef<Array<HTMLCanvasElement | null>>([]);
  const [name, setName] = useState(surface === 'hrms' ? 'HRMS' : surface === 'institution' ? 'Your Institution' : 'QuantaEdge');
  const [tagline, setTagline] = useState('Learn • Grow • Achieve');
  const [mark, setMark] = useState<LogoMark>('book');
  const [layout, setLayout] = useState<LogoLayout>('horizontal');
  const [style, setStyle] = useState<LogoStyle>('clean');
  const [paletteIndex, setPaletteIndex] = useState(0);
  const [background, setBackground] = useState('#FFFFFF');
  const [transparent, setTransparent] = useState(true);
  const [variantSeed, setVariantSeed] = useState(0);
  const [selectedVariant, setSelectedVariant] = useState(0);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  const prefix = surface === 'hrms' ? '/api/v1/hrms/notifications'
    : surface === 'institution' ? '/api/v1/institution/notifications'
    : '/api/v1/admin/notifications';
  const palette = PALETTES[paletteIndex] ?? PALETTES[0];

  const drawAll = useCallback(() => {
    canvases.current.forEach((canvas, variant) => {
      if (!canvas) return;
      drawLogo(canvas, { name, tagline, mark, layout, style, palette, transparent, background,
        variant: variantSeed + variant });
    });
  }, [name, tagline, mark, layout, style, palette, transparent, background, variantSeed]);

  useEffect(() => { drawAll(); }, [drawAll]);

  const download = useCallback((variant: number) => {
    const canvas = canvases.current[variant];
    if (!canvas) return;
    canvas.toBlob(blob => {
      if (!blob) { setError('Logo export failed in this browser.'); return; }
      const url = URL.createObjectURL(blob);
      const anchor = window.document.createElement('a');
      anchor.href = url;
      anchor.download = (name.trim().replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-|-$/g, '').slice(0, 48) || 'brand-logo') + '-concept-' + (variant + 1) + '.png';
      anchor.click();
      URL.revokeObjectURL(url);
    }, 'image/png');
  }, [name]);

  const useInNotification = useCallback(async () => {
    const canvas = canvases.current[selectedVariant];
    if (!canvas) return;
    setBusy(true); setError(''); setMessage('');
    try {
      const blob = await new Promise<Blob>((resolve, reject) =>
        canvas.toBlob(value => value ? resolve(value) : reject(new Error('The selected logo could not be exported.')), 'image/png'));
      const safeName = (name.trim().replace(/[^A-Za-z0-9_-]+/g, '-').replace(/^-|-$/g, '').slice(0, 48) || 'brand-logo');
      const form = new FormData();
      form.append('file', new File([blob], safeName + '-logo.png', { type: 'image/png' }));
      const response = await apiFetch(prefix + '/media', { method: 'POST', body: form });
      const data = await response.json().catch(() => ({})) as { assetId?: string; ready?: boolean; message?: string };
      if (!response.ok || !data.assetId || !data.ready) throw new Error(data.message || 'Logo asset could not be saved.');
      onUseInNotification(data.assetId, safeName + '-logo.png');
      setMessage('Logo generated, stored in the authorized media library and added to the notification design.');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Logo asset could not be saved.');
    } finally { setBusy(false); }
  }, [selectedVariant, name, prefix, onUseInNotification]);

  return <div className={styles.lab}>
    <header className={styles.labHeader}>
      <div><p className={styles.eyebrow}>LOGO & GRAPHICS LAB</p><h2>Create a reusable brand mark</h2><p>Build editable logo concepts from vector shapes, typography and your chosen palette. Export clean PNG assets for notification designs.</p></div>
      <button type="button" className={styles.generateButton} onClick={() => { setVariantSeed(v => v + 1); setSelectedVariant(0); setError(''); }}>Generate new variations ↻</button>
    </header>
    <div className={styles.labLayout}>
      <section className={styles.controls}>
        <h3>Brand brief</h3>
        <label>Brand name<input value={name} maxLength={60} onChange={event => setName(event.target.value)} placeholder="Your brand name"/></label>
        <label>Tagline <span>Optional</span><input value={tagline} maxLength={90} onChange={event => setTagline(event.target.value)} placeholder="A short phrase"/></label>
        <label>Logo mark<select value={mark} onChange={event => setMark(event.target.value as LogoMark)}>{MARKS.map(item => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>
        <label>Layout<select value={layout} onChange={event => setLayout(event.target.value as LogoLayout)}><option value="horizontal">Icon + wordmark</option><option value="stacked">Stacked brand</option><option value="icon">Icon only</option></select></label>
        <label>Visual style<select value={style} onChange={event => setStyle(event.target.value as LogoStyle)}><option value="clean">Clean & modern</option><option value="friendly">Friendly & rounded</option><option value="classic">Classic & formal</option><option value="geometric">Geometric & bold</option></select></label>
        <div className={styles.paletteLabel}>Colour palette</div>
        <div className={styles.paletteGrid}>{PALETTES.map((item,index)=><button type="button" key={item.name} className={paletteIndex===index?styles.paletteActive:styles.palette} onClick={()=>setPaletteIndex(index)} aria-pressed={paletteIndex===index}>
          <span><i style={{background:item.primary}}/><i style={{background:item.secondary}}/><i style={{background:item.ink}}/></span><strong>{item.name}</strong>
        </button>)}</div>
        <label>Preview background<select value={transparent?'transparent':background} onChange={event=>{setTransparent(event.target.value==='transparent');if(event.target.value!=='transparent')setBackground(event.target.value);}}>
          <option value="transparent">Transparent</option><option value="#FFFFFF">White</option><option value="#F3F5F8">Soft grey</option><option value="#18243A">Midnight</option>
        </select></label>
        <p className={styles.note}>These are editable, locally rendered design concepts—not claims of AI-generated artwork. Prompt-to-image generation can be enabled later when a secure image-generation provider is configured.</p>
      </section>
      <section className={styles.concepts}>
        <div className={styles.conceptsHeading}><h3>Choose a concept</h3><span>1200 × 700 px · PNG export</span></div>
        <div className={styles.conceptGrid}>{[0,1,2,3].map(index=><article key={index} className={selectedVariant===index?styles.conceptSelected:styles.concept}>
          <button type="button" className={styles.conceptPick} onClick={()=>setSelectedVariant(index)} aria-pressed={selectedVariant===index} aria-label={'Select logo concept '+(index+1)}>
            <span className={styles.conceptNumber}>CONCEPT {String(index+1).padStart(2,'0')}</span>
            <canvas ref={element=>{canvases.current[index]=element;}} className={styles.logoCanvas} aria-label={'Preview of logo concept '+(index+1)}/>
            {selectedVariant===index?<span className={styles.selectedTag}>Selected</span>:null}
          </button>
          <div className={styles.conceptActions}><button type="button" onClick={()=>download(index)}>Download PNG</button><button type="button" onClick={()=>setSelectedVariant(index)}>Edit this</button></div>
        </article>)}</div>
        <div className={styles.useRow}><div><strong>Selected concept</strong><span>{name.trim()||'Your brand'} · {MARKS.find(item=>item.value===mark)?.label} · {layout}</span></div>
          <button type="button" className={styles.generateButton} disabled={busy} onClick={()=>void useInNotification()}>{busy?'Saving media…':'Use in notification ↗'}</button>
        </div>
        {error?<div className={styles.error} role="alert">{error}</div>:null}
        {message?<div className={styles.success} role="status">{message}</div>:null}
      </section>
    </div>
  </div>;
}
