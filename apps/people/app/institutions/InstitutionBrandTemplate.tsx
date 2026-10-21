'use client';

import { useEffect, useMemo, useState } from 'react';
import { apiFetch } from '../components/apiClient';
import styles from './Institutions.module.css';

type Props = {
  institutionId: number;
  displayName: string;
  tagline: string;
  logoUrl?: string | null;
  appIconUrl?: string | null;
  storeListingUrl?: string | null;
  themeTokens: Record<string, unknown>;
  onDisplayNameChange: (value: string) => void;
  onTaglineChange: (value: string) => void;
  onRemoveAsset: (assetType: 'logo' | 'app-icon') => Promise<boolean>;
  busy?: boolean;
  onApplied: () => Promise<void> | void;
};
type PackFile = { path: string; blob: Blob };
type GeneratedPack = { zip: Blob; logo: Blob; applyLogo: Blob; icon: Blob; lockup: Blob; name: string; tagline: string; themeFingerprint: string; files: string[] };

const MAX_UPLOAD_BYTES = 2 * 1024 * 1024;
const MAX_DIMENSION = 4096;
const MAX_PIXELS = 16_000_000;
const encoder = new TextEncoder();

function validateSourceFile(file: File, label: string) {
  if (!['image/png', 'image/jpeg'].includes(file.type)) throw new Error(label + ' must be a static PNG or JPEG image.');
  if (file.size < 1 || file.size > MAX_UPLOAD_BYTES) throw new Error(label + ' must be between 1 byte and 2 MiB.');
}

async function loadSource(file: File | null, url: string | null | undefined, label: string): Promise<Blob> {
  if (file) {
    validateSourceFile(file, label);
    return file;
  }
  if (!url) throw new Error('Upload a ' + label.toLowerCase() + ' before generating the asset pack.');
  const response = await fetch(url, { credentials: 'same-origin', cache: 'no-store' });
  if (!response.ok) throw new Error('The saved ' + label.toLowerCase() + ' could not be loaded. Upload it again.');
  const blob = await response.blob();
  if (!['image/png', 'image/jpeg'].includes(blob.type) || blob.size < 1 || blob.size > MAX_UPLOAD_BYTES) {
    throw new Error('The saved ' + label.toLowerCase() + ' is not a valid supported image.');
  }
  return blob;
}

async function decodeImage(blob: Blob, label: string): Promise<ImageBitmap> {
  let bitmap: ImageBitmap;
  try { bitmap = await createImageBitmap(blob); }
  catch { throw new Error(label + ' could not be decoded. Use a valid PNG or JPEG.'); }
  if (bitmap.width < 1 || bitmap.height < 1 || bitmap.width > MAX_DIMENSION ||
      bitmap.height > MAX_DIMENSION || bitmap.width * bitmap.height > MAX_PIXELS) {
    bitmap.close();
    throw new Error(label + ' dimensions must not exceed 4096 × 4096 pixels or 16 million pixels.');
  }
  return bitmap;
}

function pngBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('The browser could not export a PNG image.')), 'image/png');
  });
}

async function renderLogo(bitmap: ImageBitmap, longestEdge: number): Promise<Blob> {
  const factor = Math.min(1, longestEdge / Math.max(bitmap.width, bitmap.height));
  const width = Math.max(1, Math.round(bitmap.width * factor));
  const height = Math.max(1, Math.round(bitmap.height * factor));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('The browser could not create a logo canvas.');
  context.clearRect(0, 0, width, height);
  // Keep the source ratio and artwork intact; this is a contain-fit, not a crop.
  const inset = Math.min(width, height) * 0.025;
  const scale = Math.min(Math.max(1, width - inset * 2) / bitmap.width, Math.max(1, height - inset * 2) / bitmap.height);
  const drawWidth = bitmap.width * scale;
  const drawHeight = bitmap.height * scale;
  context.drawImage(bitmap, (width - drawWidth) / 2, (height - drawHeight) / 2, drawWidth, drawHeight);
  return pngBlob(canvas);
}

async function renderLogoLimited(bitmap: ImageBitmap, longestEdge: number): Promise<Blob> {
  let edge = longestEdge;
  for (let attempt = 0; attempt < 8; attempt += 1) {
    const blob = await renderLogo(bitmap, edge);
    if (blob.size <= MAX_UPLOAD_BYTES) return blob;
    edge = Math.floor(edge * 0.78);
  }
  throw new Error('The logo remains larger than 2 MiB after resizing. Use a simpler source image.');
}

async function renderSquare(bitmap: ImageBitmap, size: number, padding: number, background: string | null = null): Promise<Blob> {
  const canvas = document.createElement('canvas');
  canvas.width = size;
  canvas.height = size;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('The browser could not create an icon canvas.');
  if (background) {
    context.fillStyle = background;
    context.fillRect(0, 0, size, size);
  } else {
    context.clearRect(0, 0, size, size);
  }
  const available = size * (1 - padding * 2);
  const scale = Math.min(available / bitmap.width, available / bitmap.height);
  const width = bitmap.width * scale;
  const height = bitmap.height * scale;
  context.drawImage(bitmap, (size - width) / 2, (size - height) / 2, width, height);
  return pngBlob(canvas);
}

async function renderSquareLimited(bitmap: ImageBitmap, size: number, padding: number, background: string | null = null): Promise<Blob> {
  const blob = await renderSquare(bitmap, size, padding, background);
  if (blob.size <= MAX_UPLOAD_BYTES) return blob;
  // Re-render smaller artwork centered on the fixed-size canvas to preserve the required output dimensions.
  const shrinkCanvas = document.createElement('canvas');
  shrinkCanvas.width = Math.max(64, Math.floor(size * 0.68));
  shrinkCanvas.height = Math.max(64, Math.floor(size * 0.68));
  const shrinkContext = shrinkCanvas.getContext('2d');
  if (!shrinkContext) throw new Error('The browser could not create a reduced icon canvas.');
  const available = Math.min(shrinkCanvas.width, shrinkCanvas.height) * (1 - padding * 2);
  const scale = Math.min(available / bitmap.width, available / bitmap.height);
  const width = bitmap.width * scale;
  const height = bitmap.height * scale;
  if (background) {
    shrinkContext.fillStyle = background;
    shrinkContext.fillRect(0, 0, shrinkCanvas.width, shrinkCanvas.height);
  }
  shrinkContext.drawImage(bitmap, (shrinkCanvas.width - width) / 2, (shrinkCanvas.height - height) / 2, width, height);
  const reduced = await pngBlob(shrinkCanvas);
  const fixed = document.createElement('canvas');
  fixed.width = size;
  fixed.height = size;
  const fixedContext = fixed.getContext('2d');
  if (!fixedContext) throw new Error('The browser could not create the final icon canvas.');
  if (background) {
    fixedContext.fillStyle = background;
    fixedContext.fillRect(0, 0, size, size);
  }
  const reducedBitmap = await createImageBitmap(reduced);
  fixedContext.drawImage(reducedBitmap, (size - shrinkCanvas.width) / 2, (size - shrinkCanvas.height) / 2);
  reducedBitmap.close();
  const finalBlob = await pngBlob(fixed);
  if (finalBlob.size > MAX_UPLOAD_BYTES) throw new Error('The app icon remains larger than 2 MiB. Use a simpler source image.');
  return finalBlob;
}

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff;
  for (const byte of bytes) {
    crc ^= byte;
    for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
  }
  return (crc ^ 0xffffffff) >>> 0;
}

async function createZip(files: PackFile[]): Promise<Blob> {
  // ZIP's stored method is enough for PNG and metadata files, and avoids a dependency.
  const localParts: Uint8Array[] = [];
  const centralParts: Uint8Array[] = [];
  let localOffset = 0;
  for (const file of files) {
    const name = encoder.encode(file.path);
    const data = new Uint8Array(await file.blob.arrayBuffer());
    const crc = crc32(data);
    const local = new Uint8Array(30 + name.length);
    const lv = new DataView(local.buffer);
    lv.setUint32(0, 0x04034b50, true);
    lv.setUint16(4, 20, true);
    lv.setUint16(6, 0x0800, true);
    lv.setUint16(8, 0, true);
    lv.setUint16(10, 0, true);
    lv.setUint16(12, 0, true);
    lv.setUint32(14, crc, true);
    lv.setUint32(18, data.length, true);
    lv.setUint32(22, data.length, true);
    lv.setUint16(26, name.length, true);
    lv.setUint16(28, 0, true);
    local.set(name, 30);
    localParts.push(local, data);

    const central = new Uint8Array(46 + name.length);
    const cv = new DataView(central.buffer);
    cv.setUint32(0, 0x02014b50, true);
    cv.setUint16(4, 20, true);
    cv.setUint16(6, 20, true);
    cv.setUint16(8, 0x0800, true);
    cv.setUint16(10, 0, true);
    cv.setUint16(12, 0, true);
    cv.setUint16(14, 0, true);
    cv.setUint32(16, crc, true);
    cv.setUint32(20, data.length, true);
    cv.setUint32(24, data.length, true);
    cv.setUint16(28, name.length, true);
    cv.setUint16(30, 0, true);
    cv.setUint16(32, 0, true);
    cv.setUint16(34, 0, true);
    cv.setUint16(36, 0, true);
    cv.setUint32(38, 0, true);
    cv.setUint32(42, localOffset, true);
    central.set(name, 46);
    centralParts.push(central);
    localOffset += local.length + data.length;
  }
  const centralLength = centralParts.reduce((sum, part) => sum + part.length, 0);
  const end = new Uint8Array(22);
  const ev = new DataView(end.buffer);
  ev.setUint32(0, 0x06054b50, true);
  ev.setUint16(4, 0, true);
  ev.setUint16(6, 0, true);
  ev.setUint16(8, files.length, true);
  ev.setUint16(10, files.length, true);
  ev.setUint32(12, centralLength, true);
  ev.setUint32(16, localOffset, true);
  ev.setUint16(20, 0, true);

  // Build one owned ArrayBuffer so TypeScript and Blob agree on the backing-store type.
  const parts = [...localParts, ...centralParts, end];
  const archive = new Uint8Array(parts.reduce((sum, part) => sum + part.length, 0));
  let archiveOffset = 0;
  for (const part of parts) {
    archive.set(part, archiveOffset);
    archiveOffset += part.length;
  }
  return new Blob([archive.buffer], { type: 'application/zip' });
}

function safeStem(value: string): string {
  const stem = value.trim().toLowerCase().normalize('NFKD').replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 56);
  return stem || 'institution';
}

function xmlEscape(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&apos;');
}

function validThemeColour(value: unknown, fallback: string): string {
  return typeof value === 'string' && /^#[0-9a-f]{6}$/i.test(value) ? value.toUpperCase() : fallback;
}

function lockupLayout(name: string, tagline: string) {
  const longest = Math.max(name.length * 32, tagline.length * 13.3);
  const width = Math.max(720, Math.min(4400, 220 + Math.ceil(longest) + 40));
  const nameSize = Math.min(56, Math.max(24, (width - 220) / Math.max(1, name.length * 0.58)));
  const taglineSize = Math.min(20, Math.max(12, (width - 220) / Math.max(1, tagline.length * 0.56)));
  return { width, height: 176, nameSize, taglineSize };
}

async function blobBase64(blob: Blob): Promise<string> {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  const chunkSize = 0x4000;
  for (let offset = 0; offset < bytes.length; offset += chunkSize) {
    binary += String.fromCharCode(...bytes.subarray(offset, Math.min(bytes.length, offset + chunkSize)));
  }
  return btoa(binary);
}

async function renderBrandLockup(
  iconBitmap: ImageBitmap,
  iconPng: Blob,
  name: string,
  tagline: string,
  themeTokens: Record<string, unknown>,
): Promise<{ png: Blob; svg: Blob }> {
  const layout = lockupLayout(name, tagline);
  const primary = validThemeColour(themeTokens.brandPrimary ?? themeTokens.primary, '#152038');
  const accent = validThemeColour(themeTokens.brandAccent ?? themeTokens.accent, '#566176');
  const iconBase64 = await blobBase64(iconPng);
  const nameSvg = xmlEscape(name);
  const taglineSvg = xmlEscape(tagline);
  const svg = '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<svg xmlns="http://www.w3.org/2000/svg" width="' + layout.width + '" height="' + layout.height +
    '" viewBox="0 0 ' + layout.width + ' ' + layout.height + '" role="img" aria-labelledby="brand-title brand-tagline">' +
    '<title id="brand-title">' + nameSvg + '</title>' +
    (taglineSvg ? '<desc id="brand-tagline">' + taglineSvg + '</desc>' : '') +
    '<image href="data:image/png;base64,' + iconBase64 + '" x="12" y="18" width="140" height="140" preserveAspectRatio="xMidYMid meet"/>' +
    '<text x="176" y="79" font-family="DM Sans, Arial, sans-serif" font-size="' + layout.nameSize +
    '" font-weight="800" fill="' + primary + '">' + nameSvg + '</text>' +
    (taglineSvg ? '<text x="179" y="124" font-family="DM Sans, Arial, sans-serif" font-size="' +
      layout.taglineSize + '" font-weight="600" fill="' + accent + '">' + taglineSvg + '</text>' : '') +
    '</svg>\n';

  const canvas = document.createElement('canvas');
  canvas.width = layout.width;
  canvas.height = layout.height;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('The browser could not create the generated brand lockup.');
  context.clearRect(0, 0, canvas.width, canvas.height);
  const iconSize = 140;
  const iconScale = Math.min(iconSize / iconBitmap.width, iconSize / iconBitmap.height);
  const iconWidth = iconBitmap.width * iconScale;
  const iconHeight = iconBitmap.height * iconScale;
  context.drawImage(iconBitmap, 12 + (iconSize - iconWidth) / 2, 18 + (iconSize - iconHeight) / 2, iconWidth, iconHeight);
  context.fillStyle = primary;
  context.font = '800 ' + layout.nameSize + 'px "DM Sans", "Noto Sans Devanagari", Arial, sans-serif';
  context.textBaseline = 'alphabetic';
  context.fillText(name, 176, 79, Math.max(1, layout.width - 190));
  if (tagline) {
    context.fillStyle = accent;
    context.font = '600 ' + layout.taglineSize + 'px "DM Sans", "Noto Sans Devanagari", Arial, sans-serif';
    context.fillText(tagline, 179, 124, Math.max(1, layout.width - 193));
  }
  return {
    png: await pngBlob(canvas),
    svg: new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }),
  };
}

function saveDownload(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
}

function checkText(name: string, tagline: string) {
  if (name.trim().length < 2 || name.trim().length > 180 || /[\u0000-\u001f\u007f]/.test(name)) {
    throw new Error('Institution name must contain 2–180 printable characters.');
  }
  if (tagline.length > 240 || /[\u0000-\u001f\u007f]/.test(tagline)) {
    throw new Error('Tagline must be 240 characters or fewer and contain no control characters.');
  }
}

export default function InstitutionBrandTemplate({
  institutionId, displayName, tagline, logoUrl, appIconUrl, storeListingUrl, themeTokens,
  onDisplayNameChange, onTaglineChange, onRemoveAsset, busy = false, onApplied,
}: Props) {
  const [logoFile, setLogoFile] = useState<File | null>(null);
  const [iconFile, setIconFile] = useState<File | null>(null);
  const [pack, setPack] = useState<GeneratedPack | null>(null);
  const [previewUrls, setPreviewUrls] = useState<{ logo: string; icon: string; lockup: string }>({ logo: '', icon: '', lockup: '' });
  const [generating, setGenerating] = useState(false);
  const [applying, setApplying] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  useEffect(() => {
    if (!pack) {
      setPreviewUrls({ logo: '', icon: '', lockup: '' });
      return;
    }
    const logo = URL.createObjectURL(pack.logo);
    const icon = URL.createObjectURL(pack.icon);
    const lockup = URL.createObjectURL(pack.lockup);
    setPreviewUrls({ logo, icon, lockup });
    return () => {
      URL.revokeObjectURL(logo);
      URL.revokeObjectURL(icon);
      URL.revokeObjectURL(lockup);
    };
  }, [pack]);

  const canGenerate = useMemo(() =>
    Boolean((logoFile || logoUrl) && (iconFile || appIconUrl) && displayName.trim().length >= 2),
  [logoFile, logoUrl, iconFile, appIconUrl, displayName]);

  async function generatePack() {
    setError('');
    setNotice('');
    setPack(null);
    setGenerating(true);
    let logoBitmap: ImageBitmap | null = null;
    let iconBitmap: ImageBitmap | null = null;
    try {
      const name = displayName.trim();
      const cleanTagline = tagline.trim();
      checkText(name, cleanTagline);
      const [logoSource, iconSource] = await Promise.all([
        loadSource(logoFile, logoUrl, 'Logo'),
        loadSource(iconFile, appIconUrl, 'Icon'),
      ]);
      [logoBitmap, iconBitmap] = await Promise.all([
        decodeImage(logoSource, 'Logo'),
        decodeImage(iconSource, 'Icon'),
      ]);

      const logo = await renderLogo(logoBitmap, 1600);
      const logo1024 = await renderLogo(logoBitmap, 1024);
      const logo512 = await renderLogo(logoBitmap, 512);
      const logoForApply = await renderLogoLimited(logoBitmap, 1600);
      const icon512 = await renderSquareLimited(iconBitmap, 512, 0.12);
      const icon192 = await renderSquareLimited(iconBitmap, 192, 0.12);
      const apple180 = await renderSquareLimited(iconBitmap, 180, 0.12, '#FFFFFF');
      const favicon64 = await renderSquareLimited(iconBitmap, 64, 0.12);
      const favicon48 = await renderSquareLimited(iconBitmap, 48, 0.12);
      const favicon32 = await renderSquareLimited(iconBitmap, 32, 0.12);
      const favicon16 = await renderSquareLimited(iconBitmap, 16, 0.12);
      const maskable512 = await renderSquareLimited(iconBitmap, 512, 0.23, '#FFFFFF');
      const androidForeground = await renderSquareLimited(iconBitmap, 432, 0.19);
      const androidDensitySizes = [
        { density: 'mdpi', size: 48 },
        { density: 'hdpi', size: 72 },
        { density: 'xhdpi', size: 96 },
        { density: 'xxhdpi', size: 144 },
        { density: 'xxxhdpi', size: 192 },
      ];
      const androidDensityIcons = await Promise.all(androidDensitySizes.map(async item => ({
        ...item, blob: await renderSquareLimited(iconBitmap, item.size, 0.12),
      })));
      const brandLockup = await renderBrandLockup(iconBitmap, icon512, name, cleanTagline, themeTokens);
      const faviconBase64 = await blobBase64(favicon64);
      const themePrimary = validThemeColour(themeTokens.brandPrimary ?? themeTokens.primary, '#F97316');
      const themeAccent = validThemeColour(themeTokens.brandAccent ?? themeTokens.accent, '#0F766E');
      const themeFingerprint = JSON.stringify(themeTokens);
      const faviconSvg = '<?xml version="1.0" encoding="UTF-8"?>\n' +
        '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">' +
        '<image href="data:image/png;base64,' + faviconBase64 + '" x="0" y="0" width="64" height="64" preserveAspectRatio="xMidYMid meet"/>' +
        '</svg>\n';
      const manifest = {
        id: './', name, short_name: name.slice(0, 32),
        description: cleanTagline || (name + ' learning space'),
        display: 'standalone', background_color: '#FFFFFF', theme_color: themePrimary,
        icons: [
          { src: './icon-192.png', sizes: '192x192', type: 'image/png', purpose: 'any' },
          { src: './icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'any' },
          { src: './maskable-icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
        ],
      };
      const metadata = {
        schemaVersion: 1, displayName: name, tagline: cleanTagline,
        theme: { primary: themePrimary, accent: themeAccent, presetId: themeTokens.presetId ?? '', appearance: themeTokens.appearance ?? 'system', note: 'Theme colours come from the current institution settings; source image pixels are not changed.' },
        assets: {
          logo: { file: 'logo.png', sourceRatio: Number((logoBitmap.width / logoBitmap.height).toFixed(6)) },
          generatedLockup: { png: 'brand-lockup.png', svg: 'brand-lockup.svg', source: 'square icon plus the supplied institution name/tagline' },
          icon: { file: 'icon-512.png', sourceRatio: 1 },
          favicons: ['favicon.svg', 'favicon-64.png', 'favicon-48.png', 'favicon-32.png', 'favicon-16.png'],
          webManifest: 'site.webmanifest',
          nativeIcons: ['icon-192.png', 'icon-512.png', 'maskable-icon-512.png', 'apple-touch-icon.png'],
          androidLauncher: {
            legacyDensityFolders: androidDensitySizes.map(item => 'android/res/mipmap-' + item.density + '/'),
            adaptiveForeground: 'android/res/drawable-nodpi/institution_brand_icon_foreground.png',
            adaptiveBackground: 'android/res/values/institution_brand_colors.xml',
            adaptiveIconXml: ['android/res/mipmap-anydpi-v26/ic_launcher.xml', 'android/res/mipmap-anydpi-v26/ic_launcher_round.xml'],
          },
        },
      };
      const files: PackFile[] = [
        { path: 'brand-assets/logo.png', blob: logo },
        { path: 'brand-assets/logo-1024.png', blob: logo1024 },
        { path: 'brand-assets/logo-512.png', blob: logo512 },
        { path: 'brand-assets/brand-lockup.png', blob: brandLockup.png },
        { path: 'brand-assets/brand-lockup.svg', blob: brandLockup.svg },
        { path: 'brand-assets/icon-512.png', blob: icon512 },
        { path: 'brand-assets/icon-192.png', blob: icon192 },
        ...androidDensityIcons.flatMap(item => [
          { path: 'brand-assets/android/res/mipmap-' + item.density + '/ic_launcher.png', blob: item.blob },
          { path: 'brand-assets/android/res/mipmap-' + item.density + '/ic_launcher_round.png', blob: item.blob },
        ]),
        { path: 'brand-assets/android/res/drawable-nodpi/institution_brand_icon_foreground.png', blob: androidForeground },
        { path: 'brand-assets/android/res/values/institution_brand_colors.xml', blob: new Blob([
          '<?xml version="1.0" encoding="utf-8"?>\\n<resources>\\n  <color name="institution_brand_icon_background">' + themePrimary + '</color>\\n</resources>\\n',
        ], { type: 'application/xml;charset=utf-8' }) },
        { path: 'brand-assets/android/res/mipmap-anydpi-v26/ic_launcher.xml', blob: new Blob([
          '<?xml version="1.0" encoding="utf-8"?>\\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\\n  <background android:drawable="@color/institution_brand_icon_background"/>\\n  <foreground android:drawable="@drawable/institution_brand_icon_foreground"/>\\n</adaptive-icon>\\n',
        ], { type: 'application/xml;charset=utf-8' }) },
        { path: 'brand-assets/android/res/mipmap-anydpi-v26/ic_launcher_round.xml', blob: new Blob([
          '<?xml version="1.0" encoding="utf-8"?>\\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\\n  <background android:drawable="@color/institution_brand_icon_background"/>\\n  <foreground android:drawable="@drawable/institution_brand_icon_foreground"/>\\n</adaptive-icon>\\n',
        ], { type: 'application/xml;charset=utf-8' }) },
        { path: 'brand-assets/apple-touch-icon.png', blob: apple180 },
        { path: 'brand-assets/maskable-icon-512.png', blob: maskable512 },
        { path: 'brand-assets/favicon.svg', blob: new Blob([faviconSvg], { type: 'image/svg+xml;charset=utf-8' }) },
        { path: 'brand-assets/favicon-64.png', blob: favicon64 },
        { path: 'brand-assets/favicon-48.png', blob: favicon48 },
        { path: 'brand-assets/favicon-32.png', blob: favicon32 },
        { path: 'brand-assets/favicon-16.png', blob: favicon16 },
        { path: 'brand-assets/site.webmanifest', blob: new Blob([JSON.stringify(manifest, null, 2) + '\n'], { type: 'application/manifest+json;charset=utf-8' }) },
        { path: 'brand-assets/brand.json', blob: new Blob([JSON.stringify(metadata, null, 2) + '\n'], { type: 'application/json;charset=utf-8' }) },
        { path: 'brand-assets/README.md', blob: new Blob([
          '# ' + name + ' branding assets\n\n' + (cleanTagline ? cleanTagline + '\n\n' : '') +
          'Generated by the QuantaEdge HRMS Brand Template.\n\n' +
          '- logo.png: high-resolution proportional logo with safe padding.\n' +
          '- logo-1024.png and logo-512.png: responsive logo renditions.\n' +
          '- brand-lockup.png and brand-lockup.svg: optional icon + institution name/tagline composition, coloured from the current theme settings.\n' +
          '- icon-512.png, icon-192.png and apple-touch-icon.png: square icon renditions.\n' +
          '- android/res: legacy launcher-density icons plus adaptive launcher foreground/background/XML; copy this folder into app/src/main/res when preparing a tenant-branded Android package.\n' +
          '- maskable-icon-512.png: opaque padded icon for launchers that apply a mask.\n' +
          '- favicon.svg and favicon-*.png: browser tab icons.\n' +
          '- site.webmanifest: web app identity and icon declarations.\n' +
          '- brand.json: name, tagline and intended asset roles.\n\n' +
          'The source artwork is contained proportionally; it is not stretched, cropped, or automatically recoloured. Preview all outputs on light and dark surfaces before release.\n',
          ], { type: 'text/markdown;charset=utf-8' }) },
        { path: 'brand-assets/source/logo-original.' + (logoSource.type === 'image/jpeg' ? 'jpg' : 'png'), blob: logoSource },
        { path: 'brand-assets/source/icon-original.' + (iconSource.type === 'image/jpeg' ? 'jpg' : 'png'), blob: iconSource },
      ];
      const zip = await createZip(files);
      setPack({ zip, logo, applyLogo: logoForApply, icon: icon512, lockup: brandLockup.png, name, tagline: cleanTagline, themeFingerprint, files: files.map(file => file.path) });
      setNotice('Asset pack generated with ' + files.length + ' files. Review the previews, then download or apply this pack.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Brand assets could not be generated.');
    } finally {
      logoBitmap?.close();
      iconBitmap?.close();
      setGenerating(false);
    }
  }

  async function removeAsset(assetType: 'logo' | 'app-icon') {
    setError('');
    try {
      const removed = await onRemoveAsset(assetType);
      if (removed) {
        if (assetType === 'logo') setLogoFile(null);
        else setIconFile(null);
        setPack(null);
        setNotice(assetType === 'logo' ? 'Full logo removed. Upload a new logo to generate a new pack.' : 'Square icon removed. Upload a new icon to generate a new pack.');
      }
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The branding asset could not be removed.');
    }
  }

  async function applyPack() {
    if (!pack) return;
    if (pack.name !== displayName.trim() || pack.tagline !== tagline.trim() || pack.themeFingerprint !== JSON.stringify(themeTokens)) {
      setError('The name, tagline or theme changed after generation. Generate the asset pack again before downloading or applying it.');
      return;
    }
    setApplying(true);
    setError('');
    setNotice('');
    try {
      const form = new FormData();
      form.set('logo', new File([pack.applyLogo], 'institution-logo.png', { type: 'image/png' }));
      form.set('appIcon', new File([pack.icon], 'institution-icon.png', { type: 'image/png' }));
      form.set('displayName', pack.name);
      form.set('tagline', pack.tagline);
      form.set('storeListingUrl', storeListingUrl || '');
      form.set('themeTokens', JSON.stringify(themeTokens));
      const response = await apiFetch(
        '/api/v1/hrms/platform/institutions/' + institutionId + '/branding/template-assets',
        {
          method: 'POST', body: form, credentials: 'same-origin', cache: 'no-store',
          headers: { 'X-QE-Workspace': 'HRMS', Accept: 'application/json' },
        },
      );
      const body = await response.json().catch(() => ({})) as { message?: string; detail?: string; error?: string; brandingVersion?: number };
      if (!response.ok) throw new Error(body.message || body.detail || body.error || 'The brand template could not be applied.');
      setNotice('Brand template applied atomically to this institution. Branding version ' + String(body.brandingVersion || 'updated') + '.');
      await onApplied();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'The brand template could not be applied.');
    } finally {
      setApplying(false);
    }
  }

  return <section className={styles.brandTemplate} aria-labelledby="brand-template-title">
    <div className={styles.brandTemplateHeader}>
      <div>
        <span className={styles.eyebrow}>QUANTAEDGE BRAND STUDIO</span>
        <h4 id="brand-template-title">Logo and icon template</h4>
        <p>Upload a full logo and a separate square icon. The template generates platform-sized assets and a downloadable ZIP folder.</p>
      </div>
      <span className={styles.brandTemplateOptional}>Optional template</span>
    </div>
    <div className={styles.brandTemplateFields}>
      <label>Institution name *
        <input required minLength={2} maxLength={180} value={displayName} onChange={event => { onDisplayNameChange(event.target.value); setPack(null); }} />
        <small>Used for the compact wordmark, browser metadata and app manifest.</small>
      </label>
      <label>Tagline
        <input maxLength={240} value={tagline} onChange={event => { onTaglineChange(event.target.value); setPack(null); }} placeholder="A short phrase for your institution" />
        <small>Shown as theme-aware text where space allows.</small>
      </label>
      <label>Full logo (PNG/JPEG) *
        <input type="file" accept="image/png,image/jpeg" onChange={event => { const file = event.currentTarget.files?.[0] || null; event.currentTarget.value = ''; setLogoFile(file); setPack(null); setError(''); }} />
        <small>{logoFile ? logoFile.name : logoUrl ? 'Current saved logo will be reused unless replaced.' : 'Choose a full-logo asset.'}</small>
        {logoUrl ? <button type="button" className={styles.quiet} onClick={() => void removeAsset('logo')} disabled={busy || applying || generating}>Remove saved full logo</button> : null}
      </label>
      <label>Square icon (PNG/JPEG) *
        <input type="file" accept="image/png,image/jpeg" onChange={event => { const file = event.currentTarget.files?.[0] || null; event.currentTarget.value = ''; setIconFile(file); setPack(null); setError(''); }} />
        <small>{iconFile ? iconFile.name : appIconUrl ? 'Current saved icon will be reused unless replaced.' : 'Choose a square symbol or icon.'}</small>
        {appIconUrl ? <button type="button" className={styles.quiet} onClick={() => void removeAsset('app-icon')} disabled={busy || applying || generating}>Remove saved square icon</button> : null}
      </label>
    </div>
    <div className={styles.brandTemplatePreviewGrid}>
      <div className={styles.brandTemplatePreviewCard}>
        <span>Full logo slot</span>
        <div className={styles.brandTemplateLogoStage}>
          {pack && previewUrls.logo ? <img src={previewUrls.logo} alt={pack.name + ' full logo preview'} />
            : logoUrl ? <img src={logoUrl} alt={displayName + ' full logo preview'} />
              : <span>Upload a logo to preview it proportionally.</span>}
        </div>
        <small>Wide and responsive. The source aspect ratio is retained; it is never forced into a square.</small>
      </div>
      <div className={styles.brandTemplatePreviewCard}>
        <span>Logo on a dark surface</span>
        <div className={styles.brandTemplateLogoStageDark}>
          {pack && previewUrls.logo ? <img src={previewUrls.logo} alt={pack.name + ' logo on dark surface'} />
            : logoUrl ? <img src={logoUrl} alt={displayName + ' logo on dark surface'} />
              : <span>Upload a logo to check dark-surface visibility.</span>}
        </div>
        <small>Checks contrast without automatically recolouring your institution’s artwork.</small>
      </div>
      <div className={styles.brandTemplatePreviewCard}>
        <span>Compact header slot</span>
        <div className={styles.brandTemplateCompactStage}>
          {pack && previewUrls.icon ? <img src={previewUrls.icon} alt="" />
            : appIconUrl ? <img src={appIconUrl} alt="" />
              : <span className={styles.brandTemplateIconPlaceholder}>{displayName.trim().slice(0, 1).toUpperCase() || '?'}</span>}
          <div><strong>{displayName.trim() || 'Institution name'}</strong>{tagline.trim() ? <small>{tagline.trim()}</small> : null}</div>
        </div>
        <small>Square icon plus readable, theme-aware text; the icon is not stretched.</small>
      </div>
      <div className={styles.brandTemplatePreviewCard}>
        <span>Generated icon + name + tagline lockup</span>
        <div className={styles.brandTemplateLogoStage}>
          {pack && previewUrls.lockup ? <img src={previewUrls.lockup} alt={pack.name + ' generated branded lockup'} />
            : <span>Generated from the square icon, institution name and tagline.</span>}
        </div>
        <small>A downloadable static lockup for environments that need a single logo image; live UI text remains theme-aware.</small>
      </div>
    </div>
    {pack ? <div className={styles.brandTemplateGenerated} role="status">
      <strong>Generated asset pack</strong>
      <span>{pack.files.length} files · Primary upload renditions are validated against the 2 MiB per-file cap.</span>
      <button type="button" className={styles.secondary} onClick={() => saveDownload(pack.zip, safeStem(pack.name) + '-brand-assets.zip')} disabled={pack.themeFingerprint !== JSON.stringify(themeTokens) || applying || busy}>Download ZIP folder</button>
      <button type="button" className={styles.primary} onClick={() => void applyPack()} disabled={applying || busy || pack.themeFingerprint !== JSON.stringify(themeTokens)}>
        {applying ? 'Applying to institution…' : 'Apply pack to this institution'}
      </button>
    </div> : null}
    {pack && pack.themeFingerprint !== JSON.stringify(themeTokens) ? <p className={styles.cropError} role="status">Institution theme colours changed after generation. Regenerate the asset pack before downloading or applying it.</p> : null}
    {error ? <p className={styles.cropError} role="alert">{error}</p> : null}
    {notice ? <p className={styles.disclaimer} role="status">{notice}</p> : null}
    <div className={styles.brandTemplateActions}>
      <button type="button" className={styles.primary} onClick={() => void generatePack()} disabled={!canGenerate || generating || applying}>
        {generating ? 'Generating assets…' : 'Generate asset pack'}
      </button>
      <span>Creates logo renditions, web/PWA icons, favicon sizes, metadata, a manifest and source uploads.</span>
    </div>
  </section>;
}
