'use client';

import Link from 'next/link';
import { useEffect, useRef, useState } from 'react';
import styles from './Verify.module.css';

export default function CertificateVerifyPage() {
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
    if (!normalized) { setError('Enter the certificate ID printed on the document.'); return; }
    setBusy(true); setError(''); setData(null);
    try {
      const response = await fetch('/api/v1/certificates/verify/' + encodeURIComponent(normalized), { cache: 'no-store' });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(response.status === 404 ? 'No certificate record matches this ID.' : (body.message || 'Verification could not be completed.'));
      setData(body);
    } catch (e: any) {
      setError(e?.message || 'Verification could not be completed.');
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
      setScannerHint('Camera scanning is unavailable in this browser. You can still type the certificate number or use a handheld barcode scanner focused on the Certificate ID field.');
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setScannerHint('This browser cannot access a camera here. Open the verifier over HTTPS, or enter the certificate number manually.');
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
      setScannerHint('Point the camera at the certificate QR code or barcode.');
      await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));
      const video = videoRef.current;
      if (!video) throw new Error('Camera preview could not be initialized.');
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
        ? 'Camera permission was denied. Allow camera access or enter the certificate number manually.'
        : 'The camera could not scan this certificate. Use the certificate number field or a handheld barcode scanner.');
    }
  }

  return (
    <main className="app-shell">
      <section className={styles.page}>
        <Link href="/" className={styles.brand}>QuantaEdge <span>Certificate verification</span></Link>
        <div className={styles.card}>
          <span className={styles.kicker}>AUTHENTICITY CHECK</span>
          <h1>Verify a certificate</h1>
          <p>Enter the certificate ID shown on a QuantaEdge PDF or PNG. Only the certificate's visible details are displayed.</p>
          <form className={styles.form} onSubmit={(event) => { event.preventDefault(); void verify(); }}>
            <label htmlFor="certificate-code">Certificate ID</label>
            <input id="certificate-code" value={code} onChange={(event) => setCode(event.target.value)}
              placeholder="QE-2026-ABCDEF1234567890" autoComplete="off" maxLength={40} />
            <button className="button button-dark" disabled={busy}>{busy ? 'Checking…' : 'Verify certificate'}</button>
          </form>
          <div className={styles.scanControls}>
            <button type="button" className="button button-light" onClick={() => void startScanner()}
              disabled={busy || scanning || scanFormats.length === 0}>
              {scanning ? 'Scanning…' : 'Scan QR code or barcode'}
            </button>
            {scanning && <button type="button" className="button button-light" onClick={stopScanner}>Stop camera</button>}
          </div>
          <p className={styles.scannerHint}>
            A QR code opens this verifier automatically. You can also scan the printed Code 128 barcode with a supported camera or handheld scanner, or enter the certificate ID above.
          </p>
          {scanning && (
            <div className={styles.scannerPanel}>
              <video ref={videoRef} className={styles.scannerVideo} muted playsInline autoPlay aria-label="Live certificate code scanner" />
            </div>
          )}
          {(scannerHint || scanFormats.length === 0) && (
            <p className={styles.scannerHint}>
              {scannerHint || 'Camera scanning is not supported in this browser. Enter the certificate ID, or connect a handheld barcode scanner and scan into the field above.'}
              {scanFormats.length > 0 && (
                <span> Supported camera formats: {[
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
              <strong>{data.verified ? 'Valid certificate' : 'Certificate revoked'}</strong>
            </div>
            <dl>
              <div><dt>Certificate ID</dt><dd>{data.certificateNumber}</dd></div>
              <div><dt>Recipient</dt><dd>{data.recipientName}</dd></div>
              <div><dt>Achievement</dt><dd>{data.achievementTitle}</dd></div>
              <div><dt>Class</dt><dd>{data.classCode ? 'Class ' + data.classCode : '—'}</dd></div>
              <div><dt>Issue date</dt><dd>{data.issuedAt ? new Date(data.issuedAt).toLocaleDateString() : '—'}</dd></div>
            </dl>
            <p className={styles.privacy}>This check confirms the current certificate status; it does not disclose the student's profile or assessment history.</p>
          </section>}
        </div>
      </section>
    </main>
  );
}
