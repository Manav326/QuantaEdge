'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { useLocale } from '../../components/LanguageProvider';
import styles from './Certificates.module.css';

const TEMPLATES = [
  { id: 'CLASSIC', name: 'QuantaEdge Academic', description: 'The approved QuantaEdge certificate design' },
];

function localizedTrackName(code: string | undefined, name: string | undefined, language: string) {
  const normalized = String(code || '').trim().toLowerCase();
  if (['maths', 'math', 'mathematics'].includes(normalized)) return language === 'hi' ? 'गणित' : 'Mathematics';
  if (normalized === 'science') return language === 'hi' ? 'विज्ञान' : 'Science';
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

export default function StudentCertificatesPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const { locale, certificateLanguage, setCertificateLanguage } = useLocale();
  const [profile, setProfile] = useState<any>(null);
  const [certificates, setCertificates] = useState<Certificate[]>([]);
  const [eligibleAttempts, setEligibleAttempts] = useState<any[]>([]);
  const [passThreshold, setPassThreshold] = useState(80);
  const [selectedTemplate, setSelectedTemplate] = useState('CLASSIC');
  const [achievementType, setAchievementType] = useState<'SUBJECT_COMPLETION' | 'ASSESSMENT_PASS'>('SUBJECT_COMPLETION');
  const [subjectCode, setSubjectCode] = useState('');
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
    try {
      const [progressResponse, certificateResponse, eligibleResponse] = await Promise.all([
        fetch('/api/v1/students/me', { cache: 'no-store', credentials: 'same-origin' }),
        fetch('/api/v1/students/me/certificates', { cache: 'no-store', credentials: 'same-origin' }),
        fetch('/api/v1/students/me/certificates/eligible-assessments', { cache: 'no-store', credentials: 'same-origin' }),
      ]);
      if ([401, 403].includes(progressResponse.status) || [401, 403].includes(certificateResponse.status) || [401, 403].includes(eligibleResponse.status)) {
        router.replace('/login/student');
        return;
      }
      const progress = await progressResponse.json().catch(() => ({}));
      const certs = await certificateResponse.json().catch(() => ({}));
      const eligible = await eligibleResponse.json().catch(() => ({}));
      if (!progressResponse.ok) throw new Error(progress.message || t('Unable to load your learning progress.','आपकी पढ़ाई की प्रगति लोड नहीं हो सकी।'));
      if (!certificateResponse.ok) throw new Error(certs.message || t('Unable to load your certificates.','आपके प्रमाणपत्र लोड नहीं हो सके।'));
      if (!eligibleResponse.ok) throw new Error(eligible.message || t('Unable to load eligible assessment results.','पात्र टेस्ट परिणाम लोड नहीं हो सके।'));
      const curriculum = Array.isArray(progress.curriculum) ? progress.curriculum : [];
      const eligibleItems = Array.isArray(eligible.items) ? eligible.items : [];
      const certificateItems = Array.isArray(certs.items) ? certs.items : [];
      const tracksByCode = new Map<string, any>();
      curriculum.forEach((track: any) => {
        const code = String(track.subject_code || '').trim().toLowerCase();
        if (code) tracksByCode.set(code, track);
      });
      // Certificate eligibility is an independent server query over active student tracks.
      // Use it when the dashboard metrics payload omits its curriculum list.
      eligibleItems.forEach((attempt: any) => {
        const code = String(attempt.subject_code || '').trim().toLowerCase();
        if (code && !tracksByCode.has(code)) {
          tracksByCode.set(code, {
            class_code: attempt.class_code,
            subject_code: code,
            subject_name: attempt.subject_name,
            completed: null,
            lessons: null,
          });
        }
      });
      // Already-issued records are a final fallback, so prior achievements stay discoverable
      // even if learning-progress metrics temporarily omit the current curriculum.
      certificateItems.forEach((certificate: Certificate) => {
        const code = String(certificate.subject_code || '').trim().toLowerCase();
        if (code && !tracksByCode.has(code)) {
          tracksByCode.set(code, {
            class_code: certificate.class_code,
            subject_code: code,
            subject_name: certificate.subject_name || certificate.achievement_title,
            completed: null,
            lessons: null,
          });
        }
      });
      const availableTracks = Array.from(tracksByCode.values());
      setProfile({ ...progress, curriculum: availableTracks });
      setCertificates(certificateItems);
      setEligibleAttempts(eligibleItems);
      setPassThreshold(Number(eligible.passThresholdPercent || 80));
      if (!availableTracks.some((item: any) => String(item.subject_code || '').toLowerCase() === subjectCode.toLowerCase())) {
        setSubjectCode(String(availableTracks[0]?.subject_code || ''));
      }
    } catch (e: any) {
      setError(e?.message || t('Unable to load certificates.','प्रमाणपत्र लोड नहीं हो सके।'));
    }
  }, [router, subjectCode, locale]);

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
      const body: Record<string, unknown> = { achievementType, templateId: selectedTemplate, language };
      if (achievementType === 'SUBJECT_COMPLETION') body.subjectCode = subjectCode;
      else body.assessmentAttemptId = Number(attemptId);
      const response = await fetch('/api/v1/students/me/certificates/issue', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify(body),
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.message || t('Certificate could not be issued.','प्रमाणपत्र जारी नहीं हो सका।'));
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
            <p>{t('Your approved QuantaEdge certificate design is used for every language. Verify your achievement and download the certificate as PDF or PNG.','हर भाषा में स्वीकृत QuantaEdge प्रमाणपत्र डिज़ाइन का उपयोग होता है। अपनी उपलब्धि सत्यापित करें और प्रमाणपत्र PDF या PNG में डाउनलोड करें।')}</p>
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
            <div><span className={styles.eyebrow}>01 · {t('DESIGN','डिज़ाइन')}</span><h2>{t('Approved certificate design','स्वीकृत प्रमाणपत्र डिज़ाइन')}</h2></div>
            <span className={styles.meta}>{t('One approved design · PDF + PNG','एक स्वीकृत डिज़ाइन · PDF + PNG')}</span>
          </div>
          <div className={styles.templateGrid}>
            {TEMPLATES.map((template) => (
              <div key={template.id} className={styles.templateCardWrap}>
                <button type="button"
                  className={styles.templateCard + (selectedTemplate === template.id ? ' ' + styles.selected : '')}
                  onClick={() => setSelectedTemplate(template.id)}
                  aria-pressed={selectedTemplate === template.id}>
                  <img className={styles.preview}
                    src={'/api/v1/certificates/samples/' + template.id + '?format=png&language=' + language}
                    alt={template.name + ' certificate design preview, marked as sample'} />
                  <span className={styles.templateName}>{template.name}</span>
                  <span className={styles.templateDescription}>{template.description}</span>
                </button>
                <a className={styles.previewLink}
                  href={'/api/v1/certificates/samples/' + template.id + '?format=pdf&language=' + language}
                  target="_blank" rel="noreferrer">{t('Open sample PDF ↗','नमूना PDF खोलें ↗')}</a>
              </div>
            ))}
          </div>
        </section>

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>02 · {t('ELIGIBILITY','पात्रता')}</span><h2>{t('Request an earned certificate','पात्रता के अनुसार प्रमाणपत्र माँगें')}</h2></div>
            <span className={styles.meta}>{t('Verified learning records','सत्यापित पढ़ाई के रिकॉर्ड')}</span>
          </div>
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
              <small className={styles.help}>
                {locale !== 'english'
                  ? 'यह प्रमाणपत्र की भाषा, कक्षा/विषय शीर्षक और विवरण चुनता है। परीक्षा का मूल नाम उसी भाषा में रहेगा जिसमें वह बनाया गया था।'
                  : 'This controls certificate language, class/subject heading, and explanatory text. Assessment titles remain in the language in which they were authored.'}
              </small>
            </label>
            {achievementType === 'SUBJECT_COMPLETION' ? (
              <label className={styles.field}>
                <span>{t('Subject track','विषय ट्रैक')}</span>
                <select value={subjectCode} onChange={(event) => setSubjectCode(event.target.value)}>
                  {!tracks.length && <option value="">{t('No active subject track available','कोई सक्रिय विषय ट्रैक उपलब्ध नहीं')}</option>}
                  {tracks.map((track: any) => (
                    <option key={track.subject_code} value={track.subject_code}>
                      {localizedTrackName(track.subject_code, track.subject_name, language)}{track.completed != null && track.lessons != null ? ` · ${Number(track.completed)} / ${Number(track.lessons)} ${t('lessons complete','पाठ पूरे')}` : ''}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <label className={styles.field}>
                <span>{t('Released assessment result','जारी किया गया टेस्ट परिणाम')}</span>
                <select value={attemptId} onChange={(event) => setAttemptId(event.target.value)}>
                  <option value="">{t('Choose a qualifying released result','पात्र जारी परिणाम चुनें')}</option>
                  {releasedAttempts.map((attempt: any) => (
                    <option key={attempt.attempt_id} value={attempt.attempt_id}>
                      {t('Class ','कक्षा ')}{attempt.class_code} · {localizedTrackName(attempt.subject_code, attempt.subject_name, language)} — {attempt.assessment_title} · {attempt.final_score} / {attempt.max_score}
                      {attempt.certificate_status === 'ISSUED' ? ' · ' + t('Certificate already issued','प्रमाणपत्र जारी हो चुका है') : ''}
                      {attempt.certificate_status === 'REVOKED' ? ' · ' + t('Certificate revoked','प्रमाणपत्र निरस्त') : ''}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {achievementType === 'ASSESSMENT_PASS' && releasedAttempts.length === 0 && (
              <p className={styles.help}>
                {locale === 'english'
                  ? `No qualifying released assessment results were found. The result must be released, meet the ${passThreshold}% threshold, belong to an active subject track, and come from an admin-created test (not a generated or practice test).`
                  : `कोई पात्र जारी टेस्ट परिणाम नहीं मिला। परिणाम जारी होना चाहिए, ${passThreshold}% की सीमा पूरी करनी चाहिए, सक्रिय विषय ट्रैक से संबंधित होना चाहिए और एडमिन द्वारा बनाया गया टेस्ट होना चाहिए (generated या practice test नहीं)।`}
                {' '}<Link href="/student/tests">{t('Check your test results','अपने टेस्ट परिणाम देखें')}</Link>. {t('A certificate issued from the test-result screen should remain listed here after refresh.','टेस्ट परिणाम स्क्रीन से जारी प्रमाणपत्र रिफ्रेश करने के बाद भी यहाँ दिखना चाहिए।')}
              </p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED' && (
              <p className={styles.help}>{t('A certificate has already been issued for this result. You can open the existing certificate; a duplicate will not be created.','इस परिणाम का प्रमाणपत्र पहले ही जारी हो चुका है। मौजूदा प्रमाणपत्र खोला जा सकता है; दूसरा प्रमाणपत्र नहीं बनेगा।')}</p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED' && (
              <p className={styles.error}>{t('The existing certificate is revoked. It cannot be replaced automatically.','मौजूदा प्रमाणपत्र निरस्त है। इसे अपने-आप बदला नहीं जा सकता।')}</p>
            )}
          </div>
          <div className={styles.rule}>
            <strong>{t('Eligibility rule','पात्रता नियम')}</strong>
            {achievementType === 'SUBJECT_COMPLETION'
              ? t('All currently published lessons in your active subject track must be completed. The server checks your actual progress records.','आपके सक्रिय विषय ट्रैक के सभी प्रकाशित पाठ पूरे होने चाहिए। सर्वर आपके वास्तविक प्रगति रिकॉर्ड की जाँच करता है।')
              : t(`Only an admin-created, non-generated assessment can qualify (not practice, topic-end or chapter-end tests). The result must be released, meet the ${passThreshold}% pass threshold, and belong to an active subject track. Completing every published lesson is not required for an assessment-pass certificate.`,`केवल एडमिन द्वारा बनाया गया non-generated टेस्ट पात्र हो सकता है (practice, topic-end या chapter-end टेस्ट नहीं)। परिणाम जारी होना चाहिए, ${passThreshold}% की पास सीमा पूरी करनी चाहिए और सक्रिय विषय ट्रैक से संबंधित होना चाहिए। टेस्ट पास प्रमाणपत्र के लिए सभी प्रकाशित पाठ पूरे करना आवश्यक नहीं है।`)}
          </div>
          <button className="button button-dark" type="button"
            onClick={() => void issueCertificate()}
            disabled={busy
              || (achievementType === 'SUBJECT_COMPLETION' && (!subjectCode || !tracks.some((track: any) => track.subject_code === subjectCode)))
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
            <div className={styles.empty}><strong>{t('No certificates yet','अभी कोई प्रमाणपत्र नहीं है')}</strong><p>{t('Earn a subject-completion certificate by completing all published lessons in an active subject track, or earn an assessment-pass certificate by passing a released, eligible admin-created test. Assessment-pass certificates do not require completing every lesson first.','सक्रिय विषय ट्रैक के सभी प्रकाशित पाठ पूरे करके subject-completion प्रमाणपत्र या जारी पात्र एडमिन टेस्ट पास करके assessment-pass प्रमाणपत्र प्राप्त करें। टेस्ट पास प्रमाणपत्र के लिए पहले सभी पाठ पूरे करना आवश्यक नहीं है।')}</p></div>
          ) : (
            <div className={styles.certificateList}>
              {certificates.map((certificate) => (
                <article className={styles.certificateRow} key={certificate.id}>
                  <div className={styles.certificateIcon} aria-hidden="true">QE</div>
                  <div className={styles.certificateInfo}>
                    <strong>{certificate.achievement_title}</strong>
                    <span>{t('Class ','कक्षा ')}{certificate.class_code} · {localizedTrackName(certificate.subject_code, certificate.subject_name, certificate.language || 'en')}</span>
                    <span>{certificate.certificate_number} · {certificate.template_id}</span>
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
