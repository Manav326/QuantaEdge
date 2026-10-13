'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useLocale } from '../../components/LanguageProvider';
import styles from './Certificates.module.css';

const TEMPLATES = [
  { id: 'CLASSIC', name: 'Classic', description: 'Formal navy and gold frame' },
  { id: 'MODERN', name: 'Modern', description: 'Clean navy layout with orange accents' },
  { id: 'ACADEMIC', name: 'Academic', description: 'Academic green and gold frame' },
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
      setProfile(progress);
      setCertificates(Array.isArray(certs.items) ? certs.items : []);
      setEligibleAttempts(Array.isArray(eligible.items) ? eligible.items : []);
      setPassThreshold(Number(eligible.passThresholdPercent || 80));
      const curriculum = Array.isArray(progress.curriculum) ? progress.curriculum : [];
      if (!curriculum.some((item: any) => item.subject_code === subjectCode)) {
        setSubjectCode(String(curriculum[0]?.subject_code || ''));
      }
    } catch (e: any) {
      setError(e?.message || t('Unable to load certificates.','प्रमाणपत्र लोड नहीं हो सके।'));
    }
  }, [router, subjectCode, locale]);

  useEffect(() => { void refresh(); }, [refresh]);

  const tracks = useMemo(() => Array.isArray(profile?.curriculum) ? profile.curriculum : [], [profile]);
  const releasedAttempts = useMemo(() => eligibleAttempts, [eligibleAttempts]);
  const selectedAssessment = useMemo(
    () => releasedAttempts.find((attempt: any) => String(attempt.attempt_id) === attemptId),
    [releasedAttempts, attemptId],
  );

  async function issueCertificate() {
    if (achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED'
        && Number.isSafeInteger(Number(selectedAssessment.certificate_id))) {
      setError('');
      setNotice(t('This assessment already has an issued certificate. Opening the existing certificate.','इस टेस्ट का प्रमाणपत्र पहले ही जारी हो चुका है। मौजूदा प्रमाणपत्र खोला जा रहा है।'));
      window.open('/api/v1/students/me/certificates/' + Number(selectedAssessment.certificate_id) + '/view?format=pdf',
        '_blank', 'noopener,noreferrer');
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
            <span className={styles.kicker}>QUANTAEDGE · {t('ACHIEVEMENTS','उपलब्धियाँ')}</span>
            <h1>{t('Certificates','प्रमाणपत्र')}</h1>
            <p>{t('Choose a design, verify your achievement, and download your certificate as PDF or PNG.','डिज़ाइन चुनें, उपलब्धि की पात्रता जाँचें और प्रमाणपत्र PDF या PNG में डाउनलोड करें।')}</p>
          </div>
          <Link href="/student/progress" className="button button-light">← {t('Learning progress','पढ़ाई की प्रगति')}</Link>
        </header>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {notice && <div className={styles.notice} role="status">{notice}</div>}

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>01 · {t('DESIGN','डिज़ाइन')}</span><h2>{t('Choose your certificate style','प्रमाणपत्र का डिज़ाइन चुनें')}</h2></div>
            <span className={styles.meta}>{t('3 designs · PDF + PNG','3 डिज़ाइन · PDF + PNG')}</span>
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
          <p className={styles.help}>{t('Sample previews are clearly marked “SAMPLE / NOT VALID”. They are design examples, not issued certificates.','नमूने पर “SAMPLE / NOT VALID” लिखा है। ये डिज़ाइन उदाहरण हैं, जारी किए गए प्रमाणपत्र नहीं।')}</p>
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
                      {localizedTrackName(track.subject_code, track.subject_name, language)} · {Number(track.completed || 0)} / {Number(track.lessons || 0)} {t('lessons complete','पाठ पूरे')}
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
                  <span className={certificate.status === 'ISSUED' ? styles.valid : styles.revoked}>{certificate.status}</span>
                  <div className={styles.downloads}>
                    {certificate.status === 'ISSUED' ? <>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/view?format=pdf'} target="_blank" rel="noreferrer">{t('View certificate ↗','प्रमाणपत्र देखें ↗')}</a>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/download?format=pdf'}>{t('Download PDF','PDF डाउनलोड करें')}</a>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/download?format=png'}>{t('Download PNG','PNG डाउनलोड करें')}</a>
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
