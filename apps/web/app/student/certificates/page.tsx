'use client';

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
  const requestedCertificate = certificates.find(certificate => certificate.id === requestedCertificateId);

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
      setPreviewError(t('This certificate is revoked or is not available to view.','यह प्रमाणपत्र निरस्त है या देखने के लिए उपलब्ध नहीं है।'));
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
          throw new Error(body.message || body.detail || body.error || t('Certificate preview could not be loaded.','प्रमाणपत्र का पूर्वावलोकन लोड नहीं हो सका।'));
        }
        const blob = await response.blob();
        if (!active) return;
        objectUrl = URL.createObjectURL(blob);
        setPreviewUrl(objectUrl);
      } catch (e: any) {
        if (active) setPreviewError(e?.message || t('Certificate preview could not be loaded.','प्रमाणपत्र का पूर्वावलोकन लोड नहीं हो सका।'));
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
        throw new Error(body.message || body.detail || body.error || t('Certificate download failed.','प्रमाणपत्र डाउनलोड नहीं हो सका।'));
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
      setPreviewError(e?.message || t('Certificate download failed.','प्रमाणपत्र डाउनलोड नहीं हो सका।'));
    } finally {
      setDownloadBusy(false);
    }
  }

  async function issueCertificate() {
    if (achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED'
        && Number.isSafeInteger(Number(selectedAssessment.certificate_id))) {
      setError('');
      setNotice(t('This assessment already has an issued certificate. Opening the existing certificate.','इस टेस्ट का प्रमाणपत्र पहले ही जारी हो चुका है। मौजूदा प्रमाणपत्र खोला जा रहा है।'));
      router.push('/student/certificates?certificateId=' + Number(selectedAssessment.certificate_id));
      return;
    }
    if (achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED') {
      setError(t('A certificate for this assessment has been revoked and cannot be reissued. Contact support if you believe this is an error.','इस टेस्ट का प्रमाणपत्र निरस्त है और दोबारा जारी नहीं हो सकता। इसे गलत मानते हैं तो सहायता टीम से संपर्क करें।'));
      setNotice('');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const body: Record<string, unknown> = { achievementType, templateId: 'CLASSIC', language };
      if (achievementType === 'SUBJECT_COMPLETION') {
        if (!selectedTrack) throw new Error(t('Choose an active subject track.','एक सक्रिय विषय ट्रैक चुनें।'));
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
      if (!response.ok) throw new Error(result.detail || result.message || result.error || t('Certificate could not be issued.','प्रमाणपत्र जारी नहीं हो सका।'));
      setNotice(t('Your certificate is ready. Both PDF and PNG are available below.','आपका प्रमाणपत्र तैयार है। नीचे PDF और PNG दोनों उपलब्ध हैं।'));
      await refresh();
    } catch (e: any) {
      setError(e?.message || t('Certificate could not be issued.','प्रमाणपत्र जारी नहीं हो सका।'));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="app-shell">
      <section className={styles.page}>
        <header className={styles.header}>
          <div>
            <span className={styles.kicker}>{t('ACHIEVEMENTS','उपलब्धियाँ')}</span>
            <h1>{t('Certificates','प्रमाणपत्र')}</h1>
            <p>{t('Earn verified certificates and download PDF or PNG.','पात्रता की पुष्टि के बाद प्रमाणपत्र PDF या PNG में डाउनलोड करें।')}</p>
          </div>
          <Link href="/student/progress" className="button button-light">← {t('Learning progress','पढ़ाई की प्रगति')}</Link>
        </header>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {notice && <div className={styles.notice} role="status">{notice}</div>}

        {requestedCertificateId > 0 && <section className={styles.panel} aria-label={t('Certificate viewer','प्रमाणपत्र व्यूअर')}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>{t('CERTIFICATE VIEWER','प्रमाणपत्र व्यूअर')}</span><h2>{requestedCertificate?.achievement_title || t('Certificate','प्रमाणपत्र')}</h2></div>
            <button type="button" className="button button-light" onClick={() => router.push('/student/certificates')}>{t('All certificates','सभी प्रमाणपत्र')}</button>
          </div>
          {!profile ? <div className={styles.viewerLoading}>{t('Loading your certificate…','आपका प्रमाणपत्र लोड हो रहा है…')}</div>
            : !requestedCertificate ? <div className={styles.empty}><strong>{t('Certificate not found','प्रमाणपत्र नहीं मिला')}</strong><p>{t('This certificate is not available in your account.','यह प्रमाणपत्र आपके खाते में उपलब्ध नहीं है।')}</p></div>
            : requestedCertificate.status !== 'ISSUED' ? <div className={styles.error}>{t('This certificate has been revoked and cannot be displayed as valid proof.','यह प्रमाणपत्र निरस्त है और मान्य प्रमाण के रूप में नहीं दिखाया जा सकता।')}</div>
            : <>
              <div className={styles.viewerToolbar}>
                <div className={styles.formatChoices} role="group" aria-label={t('Certificate format','प्रमाणपत्र का फ़ॉर्मैट')}>
                  <button type="button" aria-pressed={previewFormat === 'pdf'} className={previewFormat === 'pdf' ? styles.formatButton + ' ' + styles.formatButtonActive : styles.formatButton} onClick={() => setPreviewFormat('pdf')}>PDF</button>
                  <button type="button" aria-pressed={previewFormat === 'png'} className={previewFormat === 'png' ? styles.formatButton + ' ' + styles.formatButtonActive : styles.formatButton} onClick={() => setPreviewFormat('png')}>PNG</button>
                </div>
                <button type="button" className="button button-dark" disabled={downloadBusy || previewLoading} onClick={() => void downloadSelectedCertificate()}>
                  {downloadBusy ? t('Preparing download…','डाउनलोड तैयार हो रहा है…') : t('Download selected format','चुना गया फ़ॉर्मैट डाउनलोड करें') + ' (' + previewFormat.toUpperCase() + ')'}
                </button>
              </div>
              {previewError && <div className={styles.error} role="alert">{previewError}</div>}
              {previewLoading ? <div className={styles.viewerLoading}>{t('Preparing certificate preview…','प्रमाणपत्र का पूर्वावलोकन तैयार हो रहा है…')}</div>
                : previewUrl && previewFormat === 'pdf' ? <iframe className={styles.certificatePdfPreview} src={previewUrl} title={t('Certificate PDF preview','प्रमाणपत्र PDF पूर्वावलोकन')} />
                : previewUrl && previewFormat === 'png' ? <div className={styles.certificateImageStage}><img className={styles.certificateImagePreview} src={previewUrl} alt={t('Issued certificate preview','जारी प्रमाणपत्र का पूर्वावलोकन')} /></div>
                : null}
            </>}
        </section>}

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>01 · {t('CERTIFICATE','प्रमाणपत्र')}</span><h2>{t('Issue an achievement certificate','उपलब्धि प्रमाणपत्र जारी करें')}</h2></div>
            <span className={styles.meta}>{t('Standard design applied automatically','मानक डिज़ाइन अपने-आप लागू होगा')}</span>
          </div>

          <div className={styles.formGroup}>
            <h3 className={styles.groupTitle}>{t('Certificate settings','प्रमाणपत्र सेटिंग्स')}</h3>
            <div className={styles.formGrid}>
              <label className={styles.field}>
                <span>{t('Achievement type','उपलब्धि का प्रकार')}</span>
                <select value={achievementType} onChange={(event) => setAchievementType(event.target.value as any)}>
                  <option value="SUBJECT_COMPLETION">{t('Subject learning completion','विषय की पढ़ाई पूरी')}</option>
                  <option value="ASSESSMENT_PASS">{t('Assessment result','टेस्ट परिणाम')} ({passThreshold}% {t('or higher','या अधिक')})</option>
                </select>
              </label>
              <label className={styles.field}>
                <span>{t('Certificate language','प्रमाणपत्र की भाषा')}</span>
                <select value={language} onChange={(event) => setCertificateLanguage(event.target.value as 'hi' | 'en')}>
                  <option value="hi">हिन्दी (Hindi)</option>
                  <option value="en">English</option>
                </select>
              </label>
            </div>
          </div>

          <div className={styles.formGroup}>
            <h3 className={styles.groupTitle}>{t('Achievement details','उपलब्धि का विवरण')}</h3>
            <div className={styles.formGridSingle}>
              {achievementType === 'SUBJECT_COMPLETION' ? (
                <label className={styles.field}>
                  <span>{t('Subject track','विषय ट्रैक')}</span>
                  <select value={selectedTrackKey} onChange={(event) => setSelectedTrackKey(event.target.value)} disabled={!tracks.length}>
                    <option value="">{tracks.length ? t('Choose a subject track','विषय ट्रैक चुनें') : t('No active subject track available','कोई सक्रिय विषय ट्रैक उपलब्ध नहीं है')}</option>
                    {tracks.map((track: any) => {
                      const scopeLabel = Number(track.course_id) > 0 && track.course_name
                        ? String(track.course_name)
                        : t('Class ','कक्षा ') + track.class_code;
                      return (
                        <option key={track.track_key} value={track.track_key}>
                          {scopeLabel} · {localizedTrackName(track.subject_code, track.subject_name, language)}
                          {track.completed != null && track.lessons != null
                            ? ` · ${Number(track.completed)} / ${Number(track.lessons)} ${t('lessons complete','पाठ पूरे')}`
                            : ''}
                        </option>
                      );
                    })}
                  </select>
                </label>
              ) : (
                <label className={styles.field}>
                  <span>{t('Released assessment result','जारी टेस्ट परिणाम')}</span>
                  <select value={attemptId} onChange={(event) => setAttemptId(event.target.value)}>
                    <option value="">{t('Choose an eligible released result','पात्र जारी परिणाम चुनें')}</option>
                    {releasedAttempts.map((attempt: any) => {
                      const scopeLabel = Number(attempt.course_id) > 0 && attempt.course_name
                        ? String(attempt.course_name)
                        : t('Class ','कक्षा ') + attempt.class_code;
                      return (
                        <option key={attempt.attempt_id} value={attempt.attempt_id}>
                          {scopeLabel} · {localizedTrackName(attempt.subject_code, attempt.subject_name, language)} — {attempt.assessment_title} · {attempt.final_score} / {attempt.max_score}
                          {attempt.certificate_status === 'ISSUED' ? ' · ' + t('Certificate already issued','प्रमाणपत्र पहले से जारी है') : ''}
                          {attempt.certificate_status === 'REVOKED' ? ' · ' + t('Certificate revoked','प्रमाणपत्र निरस्त') : ''}
                        </option>
                      );
                    })}
                  </select>
                </label>
              )}
            </div>
            {achievementType === 'ASSESSMENT_PASS' && releasedAttempts.length === 0 && (
              <p className={styles.help}>
                {t('No eligible released admin-created test results.','कोई पात्र जारी Admin-created टेस्ट परिणाम नहीं मिला।')}
                {' '}<Link href="/student/tests">{t('Check your test results','अपने टेस्ट परिणाम देखें')}</Link>
              </p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED' && (
              <p className={styles.help}>{t('Certificate already issued for this result; open it below.','इस परिणाम का प्रमाणपत्र पहले से जारी है; नीचे खोलें।')}</p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED' && (
              <p className={styles.error}>{t('This certificate was revoked and cannot be reissued.','यह प्रमाणपत्र निरस्त है और दोबारा जारी नहीं होगा।')}</p>
            )}
          </div>

          <div className={styles.rule}>
            <strong>{t('Eligibility','पात्रता')}</strong>
            {achievementType === 'SUBJECT_COMPLETION'
              ? t('Complete all published lessons in this active subject track.','इस सक्रिय विषय ट्रैक के सभी प्रकाशित पाठ पूरे करें।')
              : t(`Pass a released admin-created test at ${passThreshold}% or higher.`,`जारी Admin-created टेस्ट में कम-से-कम ${passThreshold}% अंक प्राप्त करें।`)}
          </div>
          <button className="button button-dark" type="button"
            onClick={() => void issueCertificate()}
            disabled={busy
              || (achievementType === 'SUBJECT_COMPLETION' && (!selectedTrackKey || !selectedTrack))
              || (achievementType === 'ASSESSMENT_PASS' && (!attemptId || !selectedAssessment || selectedAssessment?.certificate_status === 'REVOKED'))}>
            {busy ? t('Verifying achievement…','उपलब्धि की पुष्टि हो रही है…') : achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED'
              ? t('Open existing certificate','मौजूदा प्रमाणपत्र खोलें')
              : achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED'
                ? t('Certificate revoked','प्रमाणपत्र निरस्त')
                : t('Verify eligibility & issue certificate','पात्रता जाँचें और प्रमाणपत्र जारी करें')}
          </button>
        </section>

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>03 · {t('YOUR RECORDS','आपके रिकॉर्ड')}</span><h2>{t('Issued certificates','जारी किए गए प्रमाणपत्र')}</h2></div>
            <span className={styles.meta}>{certificates.length} {t('records','रिकॉर्ड')}</span>
          </div>
          {!certificates.length ? (
            <div className={styles.empty}><strong>{t('No certificates yet','अभी कोई प्रमाणपत्र नहीं है')}</strong><p>{t('Complete all published lessons or pass an eligible test.','सभी प्रकाशित पाठ पूरे करें या पात्र टेस्ट पास करें।')}</p></div>
          ) : (
            <div className={styles.certificateList}>
              {certificates.map((certificate) => (
                <article className={styles.certificateRow} key={certificate.id}>
                  <div className={styles.certificateIcon} aria-hidden="true">QE</div>
                  <div className={styles.certificateInfo}>
                    <strong>{certificate.achievement_title}</strong>
                    <span>{t('Class ','कक्षा ')}{certificate.class_code} · {localizedTrackName(certificate.subject_code, certificate.subject_name, certificate.language || 'en')}</span>
                    <span>{certificate.certificate_number}</span>
                    <small>{t('Issued','जारी')}: {new Date(certificate.issued_at).toLocaleDateString(locale === 'english' ? 'en-IN' : 'hi-IN')} · {certificate.language === 'hi' ? t('Hindi','हिन्दी') : 'English'}</small>
                  </div>
                  <span className={certificate.status === 'ISSUED' ? styles.valid : styles.revoked}>{certificate.status === 'ISSUED' ? t('Issued','जारी') : t('Revoked','निरस्त')}</span>
                  <div className={styles.downloads}>
                    {certificate.status === 'ISSUED' ? <>
                      <Link href={'/student/certificates?certificateId=' + certificate.id}>{t('View certificate ↗','प्रमाणपत्र देखें ↗')}</Link>
                    </> : <small>{t('Revoked · contact support if you believe this is an error.','निरस्त · इसे गलत मानते हैं तो सहायता टीम से संपर्क करें।')}</small>}
                    <Link href={'/certificate/verify?code=' + encodeURIComponent(certificate.certificate_number)} target="_blank">{t('Verify status ↗','स्थिति सत्यापित करें ↗')}</Link>
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
