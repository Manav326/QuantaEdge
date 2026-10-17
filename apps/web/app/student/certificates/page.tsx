'use client';

import { UiText } from '../../i18n/useUiTranslation';
import { translateUi } from '../../i18n/catalog';
import { formatUserTimestamp } from '../../components/formatUserTimestamp';
import Link from 'next/link';
import { Suspense, useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useLocale } from '../../components/LanguageProvider';
import styles from './Certificates.module.css';

function localizedTrackName(code: string | undefined, name: string | undefined, language: string) {
  const normalized = String(code || name || '').trim().toLowerCase().replace(/[ -]+/g, '_');
  const translations: Record<string, [string, string]> = {
    maths: ['Mathematics', 'गणित'], math: ['Mathematics', 'गणित'], mathematics: ['Mathematics', 'गणित'],
    science: ['Science', 'विज्ञान'],
    english: ['English', 'अंग्रेज़ी'], hindi: ['Hindi', 'हिन्दी'],
    social_science: ['Social Science', 'सामाजिक विज्ञान'], social_studies: ['Social Science', 'सामाजिक विज्ञान'],
    sanskrit: ['Sanskrit', 'संस्कृत'],
    computer_science: ['Computer Science', 'कंप्यूटर विज्ञान'], computer: ['Computer Science', 'कंप्यूटर विज्ञान'],
  };
  const translated = translations[normalized] || translations[String(name || '').trim().toLowerCase().replace(/[ -]+/g, '_')];
  if (translated) return language === 'hi' ? translated[1] : translated[0];
  return name || code || '';
}

type Certificate = {
  id: number;
  certificate_number: string;
  achievement_type: string;
  class_code: string;
  subject_code?: string;
  subject_name?: string;
  achievement_title: string;
  template_id: string;
  language: string;
  status: string;
  issued_at: string;
  revoked_at?: string;
};

function StudentCertificatesContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { locale, certificateLanguage, setCertificateLanguage } = useLocale();
  const [profile, setProfile] = useState<any>(null);
  const [certificates, setCertificates] = useState<Certificate[]>([]);
  const [directCertificate, setDirectCertificate] = useState<Certificate | null>(null);
  const [certificateLookupLoading, setCertificateLookupLoading] = useState(false);
  const [eligibleAttempts, setEligibleAttempts] = useState<any[]>([]);
  const [passThreshold, setPassThreshold] = useState(80);
  const [achievementType, setAchievementType] = useState<'SUBJECT_COMPLETION' | 'ASSESSMENT_PASS'>('SUBJECT_COMPLETION');
  const [selectedTrackKey, setSelectedTrackKey] = useState('');
  const [attemptId, setAttemptId] = useState('');
  const language = certificateLanguage;
  const t = (english: string, hindi: string) => locale === 'english' ? english : hindi;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [previewFormat, setPreviewFormat] = useState<'pdf' | 'png'>('pdf');
  const [previewUrl, setPreviewUrl] = useState('');
  const [previewLoading, setPreviewLoading] = useState(false);
  const [previewError, setPreviewError] = useState('');
  const [downloadBusy, setDownloadBusy] = useState(false);
  const refresh = useCallback(async () => {
    setError('');
    const requestJson = async (path: string) => {
      const response = await fetch(path, {
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { 'X-QE-Workspace': 'STUDENT' },
      });
      const body: any = await response.json().catch(() => ({}));
      if ([401, 403].includes(response.status)) {
        throw Object.assign(new Error(body.detail || body.message || body.error || 'Student sign-in is required.'), { auth: true });
      }
      if (!response.ok) {
        throw new Error(body.detail || body.message || body.error || ('Request failed (' + response.status + ').'));
      }
      return body;
    };
    const results = await Promise.allSettled([
      requestJson('/api/v1/students/me'),
      requestJson('/api/v1/students/me/certificates'),
      requestJson('/api/v1/students/me/certificates/eligible-assessments'),
      requestJson('/api/v1/students/me/certificates/subject-tracks'),
    ]);
    if (results.some(result => result.status === 'rejected' && (result.reason as any)?.auth)) {
      router.replace('/login/student');
      return;
    }
    const [progressResult, certificateResult, eligibleResult, trackResult] = results;
    const progress = progressResult.status === 'fulfilled' ? progressResult.value : {};
    const certs = certificateResult.status === 'fulfilled' ? certificateResult.value : {};
    const eligible = eligibleResult.status === 'fulfilled' ? eligibleResult.value : {};
    const trackPayload = trackResult.status === 'fulfilled' ? trackResult.value : {};
    const availableTracks = Array.isArray(trackPayload.items) ? trackPayload.items : [];
    // Keep selectors and stored certificate records available if a separate endpoint fails.
    setProfile({ ...progress, curriculum: availableTracks });
    if (certificateResult.status === 'fulfilled') setCertificates(Array.isArray(certs.items) ? certs.items : []);
    if (eligibleResult.status === 'fulfilled') {
      setEligibleAttempts(Array.isArray(eligible.items) ? eligible.items : []);
      setPassThreshold(Number(eligible.passThresholdPercent || 80));
    }
    if (trackResult.status === 'fulfilled') {
      setSelectedTrackKey(current => availableTracks.some((item: any) => item.track_key === current)
        ? current : String(availableTracks[0]?.track_key || ''));
    }
    const failure = certificateResult.status === 'rejected' ? certificateResult.reason
      : trackResult.status === 'rejected' ? trackResult.reason
      : eligibleResult.status === 'rejected' ? eligibleResult.reason
      : progressResult.status === 'rejected' ? progressResult.reason : null;
    setError(failure?.message || '');
  }, [router, locale]);

  useEffect(() => {
    void refresh();
    const refreshWhenVisible = () => {
      if (!document.hidden) void refresh();
    };
    window.addEventListener('focus', refreshWhenVisible);
    window.addEventListener('pageshow', refreshWhenVisible);
    document.addEventListener('visibilitychange', refreshWhenVisible);
    return () => {
      window.removeEventListener('focus', refreshWhenVisible);
      window.removeEventListener('pageshow', refreshWhenVisible);
      document.removeEventListener('visibilitychange', refreshWhenVisible);
    };
  }, [refresh]);

  const tracks = useMemo(() => Array.isArray(profile?.curriculum) ? profile.curriculum : [], [profile]);
  const selectedTrack = useMemo(
    () => tracks.find((track: any) => track.track_key === selectedTrackKey),
    [tracks, selectedTrackKey],
  );
  const releasedAttempts = useMemo(() => eligibleAttempts, [eligibleAttempts]);
  const selectedAssessment = useMemo(
    () => releasedAttempts.find((attempt: any) => String(attempt.attempt_id) === attemptId),
    [releasedAttempts, attemptId],
  );
  const requestedCertificateId = Number(searchParams.get('certificateId') || 0);
  const requestedCertificate = certificates.find(certificate => Number(certificate.id) === requestedCertificateId)
    || (Number(directCertificate?.id) === requestedCertificateId ? directCertificate : undefined);

  // Resolve the record by ID as well as from the recent-history list. The latter is intentionally
  // bounded, so an older issued certificate must still be openable and downloadable directly.
  useEffect(() => {
    if (!requestedCertificateId || !Number.isSafeInteger(requestedCertificateId) || requestedCertificateId < 1) {
      setDirectCertificate(null);
      setCertificateLookupLoading(false);
      return;
    }
    let active = true;
    setDirectCertificate(null);
    setCertificateLookupLoading(true);
    const loadRecord = async () => {
      try {
        const response = await fetch('/api/v1/students/me/certificates/' + requestedCertificateId, {
          cache: 'no-store',
          credentials: 'same-origin',
          headers: { 'X-QE-Workspace': 'STUDENT' },
        });
        if (response.status === 401 || response.status === 403) {
          router.replace('/login/student');
          return;
        }
        if (!response.ok) return;
        const body = await response.json().catch(() => null);
        if (active && Number(body?.id) === requestedCertificateId) setDirectCertificate(body as Certificate);
      } catch {
        // The history list remains a fallback if a transient detail lookup fails.
      } finally {
        if (active) setCertificateLookupLoading(false);
      }
    };
    void loadRecord();
    return () => { active = false; };
  }, [requestedCertificateId, router]);

  useEffect(() => {
    if (!requestedCertificateId) {
      setPreviewUrl('');
      setPreviewError('');
      setPreviewLoading(false);
      return;
    }
    if (!requestedCertificate) {
      setPreviewUrl('');
      setPreviewLoading(false);
      return;
    }
    if (requestedCertificate.status !== 'ISSUED') {
      setPreviewUrl('');
      setPreviewError(translateUi(locale, 'legacy.web.this.certificate.is.revoked.or.is.not..e52e2d8d'));
      setPreviewLoading(false);
      return;
    }
    let active = true;
    let objectUrl = '';
    setPreviewUrl('');
    setPreviewError('');
    setPreviewLoading(true);
    const loadPreview = async () => {
      try {
        const response = await fetch('/api/v1/students/me/certificates/' + requestedCertificateId + '/view?format=' + previewFormat, {
          cache: 'no-store',
          credentials: 'same-origin',
          headers: { 'X-QE-Workspace': 'STUDENT' },
        });
        if (response.status === 401 || response.status === 403) {
          router.replace('/login/student');
          return;
        }
        if (!response.ok) {
          const raw = await response.text();
          let body: any = {};
          try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
          throw new Error(body.detail || body.message || body.error || translateUi(locale, 'legacy.web.certificate.preview.could.not.be.loade.4cc69ac9'));
        }
        const blob = await response.blob();
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setPreviewUrl(objectUrl);
      } catch (e: any) {
        if (active) setPreviewError(e?.message || translateUi(locale, 'legacy.web.certificate.preview.could.not.be.loade.4cc69ac9'));
      } finally {
        if (active) setPreviewLoading(false);
      }
    };
    void loadPreview();
    return () => {
      active = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [requestedCertificateId, requestedCertificate, previewFormat, router, locale]);

  async function downloadSelectedCertificate() {
    if (!requestedCertificate || requestedCertificate.status !== 'ISSUED' || downloadBusy) return;
    setDownloadBusy(true);
    setPreviewError('');
    try {
      const response = await fetch('/api/v1/students/me/certificates/' + requestedCertificate.id + '/download?format=' + previewFormat, {
        cache: 'no-store',
        credentials: 'same-origin',
        headers: { 'X-QE-Workspace': 'STUDENT' },
      });
      if (response.status === 401 || response.status === 403) {
        router.replace('/login/student');
        return;
      }
      if (!response.ok) {
        const raw = await response.text();
        let body: any = {};
        try { body = raw ? JSON.parse(raw) : {}; } catch { body = { message: raw }; }
        throw new Error(body.detail || body.message || body.error || translateUi(locale, 'legacy.web.certificate.download.failed.c9470e3c'));
      }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = url;
      link.download = 'QuantaEdge-' + requestedCertificate.certificate_number + '.' + previewFormat;
      document.body.appendChild(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (e: any) {
      setPreviewError(e?.message || translateUi(locale, 'legacy.web.certificate.download.failed.c9470e3c'));
    } finally {
      setDownloadBusy(false);
    }
  }

  async function issueCertificate() {
    if (achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED') {
      setBusy(true);
      setError('');
      setNotice('');
      try {
        const body: Record<string, unknown> = {
          achievementType: 'ASSESSMENT_PASS',
          assessmentAttemptId: Number(attemptId),
          templateId: 'CLASSIC',
          language,
        };
        if (Number(selectedAssessment.course_id) > 0) body.courseId = Number(selectedAssessment.course_id);
        const response = await fetch('/api/v1/students/me/certificates/issue', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'STUDENT' },
          credentials: 'same-origin',
          body: JSON.stringify(body),
        });
        const record = await response.json().catch(() => ({}));
        if (!response.ok) throw new Error(record.detail || record.message || record.error
          || translateUi(locale, 'legacy.web.existing.certificate.could.not.be.reso.0323badc'));
        const id = Number(record.id);
        if (!Number.isSafeInteger(id) || id < 1) throw new Error(translateUi(locale, 'legacy.web.invalid.certificate.id.d95a8103'));
        setNotice(translateUi(locale, 'legacy.web.opening.your.existing.certificate.ab3b11f2'));
        router.push('/student/certificates?certificateId=' + id);
      } catch (e: any) {
        setError(e?.message || translateUi(locale, 'legacy.web.existing.certificate.could.not.be.reso.0323badc'));
      } finally {
        setBusy(false);
      }
      return;
    }
    if (achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED') {
      setError(translateUi(locale, 'legacy.web.a.certificate.for.this.assessment.has..57989b50'));
      setNotice('');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const body: Record<string, unknown> = { achievementType, templateId: 'CLASSIC', language };
      if (achievementType === 'SUBJECT_COMPLETION') {
        if (!selectedTrack) throw new Error(translateUi(locale, 'legacy.web.choose.an.active.subject.track.65a5b52d'));
        body.subjectCode = selectedTrack.subject_code;
        if (Number(selectedTrack.course_id) > 0) body.courseId = Number(selectedTrack.course_id);
      } else {
        body.assessmentAttemptId = Number(attemptId);
        if (Number(selectedAssessment?.course_id) > 0) body.courseId = Number(selectedAssessment.course_id);
      }
      const response = await fetch('/api/v1/students/me/certificates/issue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-QE-Workspace': 'STUDENT' },
        credentials: 'same-origin',
        body: JSON.stringify(body),
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.detail || result.message || result.error || translateUi(locale, 'legacy.web.certificate.could.not.be.issued.505ce13b'));
      const id = Number(result.id);
      if (!Number.isSafeInteger(id) || id < 1 || result.status !== 'ISSUED') {
        throw new Error(translateUi(locale, 'legacy.web.the.server.did.not.return.a.confirmed..dcf66796'));
      }
      setNotice(translateUi(locale, 'legacy.web.certificate.issued.opening.the.certifi.410f199a'));
      router.push('/student/certificates?certificateId=' + id);
    } catch (e: any) {
      setError(e?.message || translateUi(locale, 'legacy.web.certificate.could.not.be.issued.505ce13b'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="app-shell">
      <section className={styles.page}>
        <header className={styles.header}>
          <div>
            <span className={styles.kicker}>{translateUi(locale, 'legacy.web.achievements.09a2cc77')}</span>
            <h1>{translateUi(locale, 'admin.navigation.certificates')}</h1>
            <p>{translateUi(locale, 'legacy.web.earn.verified.certificates.and.downloa.61103457')}</p>
          </div>
          <Link href="/student/progress" className="button button-light">← {translateUi(locale, 'legacy.web.learning.progress.cd800468')}</Link>
        </header>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {notice && <div className={styles.notice} role="status">{notice}</div>}

        {requestedCertificateId > 0 && <section className={styles.panel} aria-label={translateUi(locale, 'legacy.web.certificate.viewer.ed28e6dd')}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>{translateUi(locale, 'legacy.web.certificate.viewer.9951acdd')}</span><h2>{requestedCertificate?.achievement_title || translateUi(locale, 'legacy.web.certificate.760980ff')}</h2></div>
            <button type="button" className="button button-light" onClick={() => router.push('/student/certificates')}>{translateUi(locale, 'legacy.web.all.certificates.dd290d66')}</button>
          </div>
          {!profile || (!requestedCertificate && certificateLookupLoading)
            ? <div className={styles.viewerLoading}>{translateUi(locale, 'legacy.web.loading.your.certificate.691b8811')}</div>
            : !requestedCertificate ? <div className={styles.empty}><strong>{translateUi(locale, 'legacy.web.certificate.not.found.2ecc7450')}</strong><p>{translateUi(locale, 'legacy.web.this.certificate.is.not.available.in.y.dca2e2b9')}</p></div>
            : requestedCertificate.status !== 'ISSUED' ? <div className={styles.error}>{translateUi(locale, 'legacy.web.this.certificate.has.been.revoked.and..0f58ec45')}</div>
            : <>
              <div className={styles.viewerToolbar}>
                <div className={styles.formatChoices} role="group" aria-label={translateUi(locale, 'legacy.web.certificate.format.2b65f7d5')}>
                  <button type="button" aria-pressed={previewFormat === 'pdf'} className={previewFormat === 'pdf' ? styles.formatButton + ' ' + styles.formatButtonActive : styles.formatButton} onClick={() => setPreviewFormat('pdf')}>PDF</button>
                  <button type="button" aria-pressed={previewFormat === 'png'} className={previewFormat === 'png' ? styles.formatButton + ' ' + styles.formatButtonActive : styles.formatButton} onClick={() => setPreviewFormat('png')}>PNG</button>
                </div>
                <button type="button" className="button button-dark" disabled={downloadBusy || previewLoading} onClick={() => void downloadSelectedCertificate()}>
                  {downloadBusy ? translateUi(locale, 'legacy.web.preparing.download.1fc1096c') : translateUi(locale, 'legacy.web.download.selected.format.0bdc6272') + ' (' + previewFormat.toUpperCase() + ')'}
                </button>
              </div>
              {previewError && <div className={styles.error} role="alert">{previewError}</div>}
              {previewLoading ? <div className={styles.viewerLoading}>{translateUi(locale, 'legacy.web.preparing.certificate.preview.56c67d62')}</div>
                : previewUrl && previewFormat === 'pdf' ? <iframe className={styles.certificatePdfPreview} src={previewUrl} title={translateUi(locale, 'legacy.web.certificate.pdf.preview.62fc39ea')} />
                : previewUrl && previewFormat === 'png' ? <div className={styles.certificateImageStage}><img className={styles.certificateImagePreview} src={previewUrl} alt={translateUi(locale, 'legacy.web.issued.certificate.preview.2e287fa6')} /></div>
                : null}
            </>}
        </section>}

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>01 · {translateUi(locale, 'legacy.web.certificate.ea04cb7f')}</span><h2>{translateUi(locale, 'legacy.web.issue.an.achievement.certificate.b1633fb4')}</h2></div>
            <span className={styles.meta}>{translateUi(locale, 'legacy.web.standard.design.applied.automatically.e65a8a93')}</span>
          </div>

          <div className={styles.formGroup}>
            <h3 className={styles.groupTitle}>{translateUi(locale, 'legacy.web.certificate.settings.029fc02d')}</h3>
            <div className={styles.formGrid}>
              <label className={styles.field}>
                <span>{translateUi(locale, 'legacy.web.achievement.type.a2a9c2eb')}</span>
                <select value={achievementType} onChange={(event) => setAchievementType(event.target.value as any)}>
                  <option value="SUBJECT_COMPLETION">{translateUi(locale, 'legacy.web.subject.learning.completion.bfc75806')}</option>
                  <option value="ASSESSMENT_PASS">{translateUi(locale, 'legacy.web.assessment.result.6bfe79be')} ({passThreshold}% {translateUi(locale, 'legacy.web.or.higher.f419fb1a')})</option>
                </select>
              </label>
              <label className={styles.field}>
                <span>{translateUi(locale, 'legacy.web.certificate.language.d0920722')}</span>
                <select value={language} onChange={(event) => setCertificateLanguage(event.target.value as 'hi' | 'en')}>
                  <option value="hi">हिन्दी (Hindi)</option>
                  <option value="en">English</option>
                </select>
              </label>
            </div>
          </div>

          <div className={styles.formGroup}>
            <h3 className={styles.groupTitle}>{translateUi(locale, 'legacy.web.achievement.details.004b5ef1')}</h3>
            <div className={styles.formGridSingle}>
              {achievementType === 'SUBJECT_COMPLETION' ? (
                <label className={styles.field}>
                  <span>{translateUi(locale, 'legacy.web.subject.track.b3c28eb5')}</span>
                  <select value={selectedTrackKey} onChange={(event) => setSelectedTrackKey(event.target.value)} disabled={!tracks.length}>
                    <option value="">{tracks.length ? translateUi(locale, 'legacy.web.choose.a.subject.track.585eb9ad') : translateUi(locale, 'legacy.web.no.active.subject.track.available.591e0174')}</option>
                    {tracks.map((track: any) => {
                      const scopeLabel = Number(track.course_id) > 0 && track.course_name
                        ? String(track.course_name)
                        : translateUi(locale, 'legacy.web.class.3f617911') + track.class_code;
                      return (
                        <option key={track.track_key} value={track.track_key}>
                          {scopeLabel} · {localizedTrackName(track.subject_code, track.subject_name, language)}
                          {track.completed != null && track.lessons != null
                            ? ` · ${Number(track.completed)} / ${Number(track.lessons)} ${translateUi(locale, 'legacy.web.lessons.complete.54b61c54')}`
                            : ''}
                        </option>
                      );
                    })}
                  </select>
                </label>
              ) : (
                <label className={styles.field}>
                  <span>{translateUi(locale, 'legacy.web.released.assessment.result.b36a17f5')}</span>
                  <select value={attemptId} onChange={(event) => setAttemptId(event.target.value)}>
                    <option value="">{translateUi(locale, 'legacy.web.choose.an.eligible.released.result.6d1980f1')}</option>
                    {releasedAttempts.map((attempt: any) => {
                      const scopeLabel = Number(attempt.course_id) > 0 && attempt.course_name
                        ? String(attempt.course_name)
                        : translateUi(locale, 'legacy.web.class.3f617911') + attempt.class_code;
                      return (
                        <option key={attempt.attempt_id} value={attempt.attempt_id}>
                          {scopeLabel} · {localizedTrackName(attempt.subject_code, attempt.subject_name, language)} — {attempt.assessment_title} · {attempt.final_score} / {attempt.max_score}
                          {attempt.certificate_status === 'ISSUED' ? ' · ' + translateUi(locale, 'legacy.web.certificate.already.issued.2539565e') : ''}
                          {attempt.certificate_status === 'REVOKED' ? ' · ' + translateUi(locale, 'legacy.web.certificate.revoked.29f7fafd') : ''}
                        </option>
                      );
                    })}
                  </select>
                </label>
              )}
            </div>
            {achievementType === 'ASSESSMENT_PASS' && releasedAttempts.length === 0 && (
              <p className={styles.help}>
                {translateUi(locale, 'legacy.web.no.eligible.released.admin.created.tes.04c71473')}
                {' '}<Link href="/student/tests">{translateUi(locale, 'legacy.web.check.your.test.results.61b59418')}</Link>
              </p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED' && (
              <p className={styles.help}>{translateUi(locale, 'legacy.web.certificate.already.issued.for.this.re.144bddaf')}</p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED' && (
              <p className={styles.error}>{translateUi(locale, 'legacy.web.this.certificate.was.revoked.and.canno.2af16133')}</p>
            )}
          </div>

          <div className={styles.rule}>
            <strong>{translateUi(locale, 'legacy.web.eligibility.ba503253')}</strong>
            {achievementType === 'SUBJECT_COMPLETION'
              ? translateUi(locale, 'legacy.web.complete.all.published.lessons.in.this.c157618b')
              : t(`Pass a released admin-created test at ${passThreshold}% or higher.`,`जारी Admin-created टेस्ट में कम-से-कम ${passThreshold}% अंक प्राप्त करें।`)}
          </div>
          <button className="button button-dark" type="button"
            onClick={() => void issueCertificate()}
            disabled={busy
              || (achievementType === 'SUBJECT_COMPLETION' && (!selectedTrackKey || !selectedTrack))
              || (achievementType === 'ASSESSMENT_PASS' && (!attemptId || !selectedAssessment || selectedAssessment?.certificate_status === 'REVOKED'))}>
            {busy ? translateUi(locale, 'legacy.web.verifying.achievement.c48cbd89') : achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED'
              ? translateUi(locale, 'legacy.web.open.existing.certificate.8ba85e12')
              : achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED'
                ? translateUi(locale, 'legacy.web.certificate.revoked.29f7fafd')
                : translateUi(locale, 'legacy.web.verify.eligibility.issue.certificate.799b6ae3')}
          </button>
        </section>

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>03 · {translateUi(locale, 'legacy.web.your.records.c4304201')}</span><h2>{translateUi(locale, 'legacy.web.issued.certificates.3cbbcab6')}</h2></div>
            <span className={styles.meta}>{certificates.length} {translateUi(locale, 'legacy.web.records.415b5a7c')}</span>
          </div>
          {!certificates.length ? (
            <div className={styles.empty}><strong>{translateUi(locale, 'legacy.web.no.certificates.yet.46af6a77')}</strong><p>{translateUi(locale, 'legacy.web.complete.all.published.lessons.or.pass.6578ce33')}</p></div>
          ) : (
            <div className={styles.certificateList}>
              {certificates.map((certificate) => (
                <article className={styles.certificateRow} key={certificate.id}>
                  <div className={styles.certificateIcon} aria-hidden="true">QE</div>
                  <div className={styles.certificateInfo}>
                    <strong>{certificate.achievement_title}</strong>
                    <span>{translateUi(locale, 'legacy.web.class.3f617911')}{certificate.class_code} · {localizedTrackName(certificate.subject_code, certificate.subject_name, certificate.language || 'en')}</span>
                    <span>{certificate.certificate_number}</span>
                    <small>{translateUi(locale, 'legacy.web.issued.454de9e4')}: {formatUserTimestamp(certificate.issued_at)} · {certificate.language === 'hi' ? translateUi(locale, 'legacy.web.hindi.197af92c') : 'English'}</small>
                  </div>
                  <span className={certificate.status === 'ISSUED' ? styles.valid : styles.revoked}>{certificate.status === 'ISSUED' ? translateUi(locale, 'legacy.web.issued.454de9e4') : translateUi(locale, 'legacy.web.revoked.e75eac7b')}</span>
                  <div className={styles.downloads}>
                    {certificate.status === 'ISSUED' ? <>
                      <Link href={'/student/certificates?certificateId=' + certificate.id}>{translateUi(locale, 'legacy.web.view.certificate.07663aac')}</Link>
                    </> : <small>{translateUi(locale, 'legacy.web.revoked.contact.support.if.you.believe.61cb6a1f')}</small>}
                    <Link href={'/certificate/verify?code=' + encodeURIComponent(certificate.certificate_number)} target="_blank">{translateUi(locale, 'legacy.web.verify.status.8146b8bf')}</Link>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </section>
    </main>
  );
}

export default function StudentCertificatesPage() {
  const { locale } = useLocale();
  return <Suspense fallback={<main className="app-shell"><section className={styles.page}><div className={styles.panel}>
    <div className={styles.viewerLoading}>{locale === 'english' ? 'Loading certificates…' : 'प्रमाणपत्र लोड हो रहे हैं…'}</div>
  </div></section></main>}>
    <StudentCertificatesContent />
  </Suspense>;
}
