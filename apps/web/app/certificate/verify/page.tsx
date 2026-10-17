'use client';

import QuantaEdgeBrand from '../../components/QuantaEdgeBrand';
import { useUiTranslation } from '../../i18n/useUiTranslation';
import { useEffect, useRef, useState } from 'react';
import styles from './Verify.module.css';

export default function CertificateVerifyPage() {
  const t = useUiTranslation();
  const [code, setCode] = useState('');
  const [data, setData] = useState<any>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [scanFormats, setScanFormats] = useState<string[]>([]);
  const [scanning, setScanning] = useState(false);
  const [scannerHint, setScannerHint] = useState('');
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const scannerRunningRef = useRef(false);
  const scannerStreamRef = useRef<MediaStream | null>(null);

  useEffect(() => {
    const query = new URLSearchParams(window.location.search);
    const initial = query.get('code') || '';
    setCode(initial);
    if (initial.trim()) void verify(initial);
  }, []);

  useEffect(() => {
    const Detector = (window as any).BarcodeDetector;
    if (!Detector || typeof Detector.getSupportedFormats !== 'function') return;
    void Detector.getSupportedFormats()
      .then((formats: string[]) => setScanFormats(['qr_code', 'code_128'].filter((format) => formats.includes(format))))
      .catch(() => setScanFormats([]));
    return () => {
      scannerRunningRef.current = false;
      scannerStreamRef.current?.getTracks().forEach((track) => track.stop());
      scannerStreamRef.current = null;
    };
  }, []);

  async function verify(value = code) {
    const normalized = value.trim().toUpperCase();
    if (!normalized) { setError(t('certificate.verify.enterId')); return; }
    setBusy(true); setError(''); setData(null);
    try {
      const response = await fetch('/api/v1/certificates/verify/' + encodeURIComponent(normalized), { cache: 'no-store' });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(response.status === 404 ? t('certificate.verify.notFound') : t('certificate.verify.failed'));
      setData(body);
    } catch (e: any) {
      setError(t('certificate.verify.failed'));
    } finally { setBusy(false); }
  }

  function stopScanner() {
    scannerRunningRef.current = false;
    scannerStreamRef.current?.getTracks().forEach((track) => track.stop());
    scannerStreamRef.current = null;
    if (videoRef.current) videoRef.current.srcObject = null;
    setScanning(false);
  }

  function scannedIdentifier(rawValue: string) {
    const raw = rawValue.trim();
    try {
      const parsed = new URL(raw);
      const code = parsed.searchParams.get('code');
      if (code) return code;
      const verificationPath = '/api/v1/certificates/verify/';
      const verificationPathIndex = parsed.pathname.indexOf(verificationPath);
      if (verificationPathIndex >= 0) {
        return decodeURIComponent(parsed.pathname.slice(verificationPathIndex + verificationPath.length));
      }
    } catch {
      // A linear barcode normally contains only the certificate number, not a URL.
    }
    return raw;
  }

  async function startScanner() {
    setError('');
    setScannerHint('');
    const Detector = (window as any).BarcodeDetector;
    if (!Detector || !scanFormats.length) {
      setScannerHint(t('certificate.verify.scannerUnavailable'));
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setScannerHint(t('certificate.verify.cameraUnavailable'));
      return;
    }
    try {
      const detector = new Detector({ formats: scanFormats });
      const stream = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: { ideal: 'environment' } },
        audio: false,
      });
      scannerStreamRef.current = stream;
      scannerRunningRef.current = true;
      setScanning(true);
      setScannerHint(t('certificate.verify.pointCamera'));
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
      const video = videoRef.current;
      if (!video) throw new Error(t('certificate.verify.cameraPreviewFailed'));
      video.srcObject = stream;
      await video.play();
      while (scannerRunningRef.current) {
        try {
          if (video.readyState >= 2) {
            const detections = await detector.detect(video);
            const value = detections?.[0]?.rawValue;
            if (value) {
              const identifier = scannedIdentifier(String(value));
              stopScanner();
              void verify(identifier);
              return;
            }
          }
        } catch {
          // A frame may not contain a decodable barcode; continue scanning.
        }
        await new Promise((resolve) => window.setTimeout(resolve, 180));
      }
    } catch (e: any) {
      stopScanner();
      setScannerHint(e?.name === 'NotAllowedError'
        ? t('certificate.verify.cameraDenied')
        : t('certificate.verify.cameraFailed'));
    }
  }

  return (
    <main className="app-shell">
      <section className={styles.page}>
        <QuantaEdgeBrand href="/" variant="header" className={styles.brandLockup} />
        <div className={styles.card}>
          <span className={styles.kicker}>{t('certificate.verify.kicker')}</span>
          <h1>{t('certificate.verify.title')}</h1>
          <p>{t('certificate.verify.description')}</p>
          <form className={styles.form} onSubmit={(event) => { event.preventDefault(); void verify(); }}>
            <label htmlFor="certificate-code">{t('certificate.verify.idLabel')}</label>
            <input id="certificate-code" value={code} onChange={(event) => setCode(event.target.value)}
              placeholder="QE-2026-ABCDEF1234567890" autoComplete="off" maxLength={40} />
            <button className="button button-dark" disabled={busy}>{busy ? t('certificate.verify.checking') : t('certificate.verify.action')}</button>
          </form>
          <div className={styles.scanControls}>
            <button type="button" className="button button-light" onClick={() => void startScanner()}
              disabled={busy || scanning || scanFormats.length === 0}>
              {scanning ? t('certificate.verify.scanning') : t('certificate.verify.scanAction')}
            </button>
            {scanning && <button type="button" className="button button-light" onClick={stopScanner}>{t('certificate.verify.stopCamera')}</button>}
          </div>
          <p className={styles.scannerHint}>
            {t('certificate.verify.scannerHelp')}
          </p>
          {scanning && (
            <div className={styles.scannerPanel}>
              <video ref={videoRef} className={styles.scannerVideo} muted playsInline autoPlay aria-label={t('certificate.verify.videoLabel')} />
            </div>
          )}
          {(scannerHint || scanFormats.length === 0) && (
            <p className={styles.scannerHint}>
              {scannerHint || t('certificate.verify.cameraUnsupported')}
              {scanFormats.length > 0 && (
                <span> {t('certificate.verify.supportedFormatsPrefix')}: {[
                  scanFormats.includes('qr_code') ? 'QR code' : '',
                  scanFormats.includes('code_128') ? 'Code 128 barcode' : '',
                ].filter(Boolean).join(' and ')}.</span>
              )}
            </p>
          )}
          {error && <div className={styles.error} role="alert">{error}</div>}
          {data && <section className={styles.result} aria-live="polite">
            <div className={data.verified ? styles.statusValid : styles.statusRevoked}>
              <span aria-hidden="true">{data.verified ? '✓' : '!'}</span>
              <strong>{data.verified ? t('certificate.verify.valid') : t('certificate.verify.revoked')}</strong>
            </div>
            <dl>
              <div><dt>{t('certificate.verify.idLabel')}</dt><dd>{data.certificateNumber}</dd></div>
              <div><dt>{t('certificate.verify.recipient')}</dt><dd>{data.recipientName}</dd></div>
              <div><dt>{t('certificate.verify.achievement')}</dt><dd>{data.achievementTitle}</dd></div>
              <div><dt>Class</dt><dd>{data.classCode ? t('certificate.verify.class', { classCode: data.classCode }) : '—'}</dd></div>
              <div><dt>{t('certificate.verify.subject')}</dt><dd>{data.subjectTrack || '—'}</dd></div>
              <div><dt>{t('certificate.verify.issueDate')}</dt><dd>{data.issuedAt ? new Date(data.issuedAt).toLocaleDateString() : '—'}</dd></div>
            </dl>
            <p className={styles.privacy}>{t('certificate.verify.privacy')}</p>
          </section>}
        </div>
      </section>
    </main>
  );
}
