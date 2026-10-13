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
      if (!progressResponse.ok) throw new Error(progress.message || 'Unable to load your learning progress.');
      if (!certificateResponse.ok) throw new Error(certs.message || 'Unable to load your certificates.');
      if (!eligibleResponse.ok) throw new Error(eligible.message || 'Unable to load eligible assessment results.');
      setProfile(progress);
      setCertificates(Array.isArray(certs.items) ? certs.items : []);
      setEligibleAttempts(Array.isArray(eligible.items) ? eligible.items : []);
      setPassThreshold(Number(eligible.passThresholdPercent || 80));
      const curriculum = Array.isArray(progress.curriculum) ? progress.curriculum : [];
      if (!curriculum.some((item: any) => item.subject_code === subjectCode)) {
        setSubjectCode(String(curriculum[0]?.subject_code || ''));
      }
    } catch (e: any) {
      setError(e?.message || 'Unable to load certificates.');
    }
  }, [router, subjectCode]);

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
      setNotice('This assessment already has an issued certificate. Opening the existing certificate.');
      window.open('/api/v1/students/me/certificates/' + Number(selectedAssessment.certificate_id) + '/view?format=pdf',
        '_blank', 'noopener,noreferrer');
      return;
    }
    if (achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED') {
      setError('A certificate for this assessment has been revoked and cannot be reissued. Contact support if you believe this is an error.');
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
      if (!response.ok) throw new Error(result.message || 'Certificate could not be issued.');
      setNotice('Your certificate is ready. Both PDF and PNG are available below.');
      await refresh();
    } catch (e: any) {
      setError(e?.message || 'Certificate could not be issued.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="app-shell">
      <section className={styles.page}>
        <header className={styles.header}>
          <div>
            <span className={styles.kicker}>QUANTAEDGE · ACHIEVEMENTS</span>
            <h1>Certificates</h1>
            <p>Choose a design, verify your achievement, and download your certificate as PDF or PNG.</p>
          </div>
          <Link href="/student/progress" className="button button-light">← Learning progress</Link>
        </header>

        {error && <div className={styles.error} role="alert">{error}</div>}
        {notice && <div className={styles.notice} role="status">{notice}</div>}

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>01 · DESIGN</span><h2>Choose your certificate style</h2></div>
            <span className={styles.meta}>3 designs · PDF + PNG</span>
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
                  target="_blank" rel="noreferrer">Open sample PDF ↗</a>
              </div>
            ))}
          </div>
          <p className={styles.help}>Sample previews are clearly marked “SAMPLE / NOT VALID”. They are design examples, not issued certificates.</p>
        </section>

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>02 · ELIGIBILITY</span><h2>Request an earned certificate</h2></div>
            <span className={styles.meta}>Verified learning records</span>
          </div>
          <div className={styles.formGrid}>
            <label className={styles.field}>
              <span>Achievement type</span>
              <select value={achievementType} onChange={(event) => setAchievementType(event.target.value as any)}>
                <option value="SUBJECT_COMPLETION">Subject learning completion</option>
                <option value="ASSESSMENT_PASS">Assessment result ({passThreshold}% or higher)</option>
              </select>
            </label>
            <label className={styles.field}>
              <span>Certificate language</span>
              <select value={language} onChange={(event) => setCertificateLanguage(event.target.value as 'hi' | 'en')}>
                <option value="hi">हिन्दी (Hindi)</option>
                <option value="en">English</option>
              </select>
              <small className={styles.help}>
                {language === 'hi'
                  ? 'यह प्रमाणपत्र की भाषा, कक्षा/विषय शीर्षक और विवरण चुनता है। परीक्षा का मूल नाम उसी भाषा में रहेगा जिसमें वह बनाया गया था।'
                  : 'This controls certificate language, class/subject heading, and explanatory text. Assessment titles remain in the language in which they were authored.'}
              </small>
            </label>
            {achievementType === 'SUBJECT_COMPLETION' ? (
              <label className={styles.field}>
                <span>Subject track</span>
                <select value={subjectCode} onChange={(event) => setSubjectCode(event.target.value)}>
                  {!tracks.length && <option value="">{language === 'hi' ? 'कोई सक्रिय विषय ट्रैक उपलब्ध नहीं' : 'No active subject track available'}</option>}
                  {tracks.map((track: any) => (
                    <option key={track.subject_code} value={track.subject_code}>
                      {localizedTrackName(track.subject_code, track.subject_name, language)} · {Number(track.completed || 0)} / {Number(track.lessons || 0)} {language === 'hi' ? 'पाठ पूरे' : 'lessons complete'}
                    </option>
                  ))}
                </select>
              </label>
            ) : (
              <label className={styles.field}>
                <span>Released assessment result</span>
                <select value={attemptId} onChange={(event) => setAttemptId(event.target.value)}>
                  <option value="">Choose a qualifying released result</option>
                  {releasedAttempts.map((attempt: any) => (
                    <option key={attempt.attempt_id} value={attempt.attempt_id}>
                      {language === 'hi' ? 'कक्षा ' : 'Class '}{attempt.class_code} · {localizedTrackName(attempt.subject_code, attempt.subject_name, language)} — {attempt.assessment_title} · {attempt.final_score} / {attempt.max_score}
                      {attempt.certificate_status === 'ISSUED' ? ' · Certificate already issued' : ''}
                      {attempt.certificate_status === 'REVOKED' ? ' · Certificate revoked' : ''}
                    </option>
                  ))}
                </select>
              </label>
            )}
            {achievementType === 'ASSESSMENT_PASS' && releasedAttempts.length === 0 && (
              <p className={styles.help}>
                No qualifying released assessment results were found. The result must be released, meet the {passThreshold}% threshold, belong to an active subject track, and come from an admin-created test (not a generated or practice test).
                {' '}<Link href="/student/tests">Check your test results</Link>. A certificate issued from the test-result screen should remain listed here after refresh.
              </p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED' && (
              <p className={styles.help}>A certificate has already been issued for this result. You can open the existing certificate; a duplicate will not be created.</p>
            )}
            {achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED' && (
              <p className={styles.error}>The existing certificate is revoked. It cannot be replaced automatically.</p>
            )}
          </div>
          <div className={styles.rule}>
            <strong>Eligibility rule</strong>
            {achievementType === 'SUBJECT_COMPLETION'
              ? 'All currently published lessons in your active subject track must be completed. The server checks your actual progress records.'
              : 'Only an admin-created, non-generated assessment can qualify (not practice, topic-end or chapter-end tests). The result must be released, meet the ' + passThreshold + '% pass threshold, and belong to an active subject track. Completing every published lesson is not required for an assessment-pass certificate.'}
          </div>
          <button className="button button-dark" type="button"
            onClick={() => void issueCertificate()}
            disabled={busy
              || (achievementType === 'SUBJECT_COMPLETION' && (!subjectCode || !tracks.some((track: any) => track.subject_code === subjectCode)))
              || (achievementType === 'ASSESSMENT_PASS' && (!attemptId || !selectedAssessment || selectedAssessment?.certificate_status === 'REVOKED'))}>
            {busy ? 'Verifying achievement…' : achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'ISSUED'
              ? 'Open existing certificate'
              : achievementType === 'ASSESSMENT_PASS' && selectedAssessment?.certificate_status === 'REVOKED'
                ? 'Certificate revoked'
                : 'Verify eligibility & issue certificate'}
          </button>
        </section>

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>03 · YOUR RECORDS</span><h2>Issued certificates</h2></div>
            <span className={styles.meta}>{certificates.length} records</span>
          </div>
          {!certificates.length ? (
            <div className={styles.empty}><strong>No certificates yet</strong><p>Earn a subject-completion certificate by completing all published lessons in an active subject track, or earn an assessment-pass certificate by passing a released, eligible admin-created test. Assessment-pass certificates do not require completing every lesson first.</p></div>
          ) : (
            <div className={styles.certificateList}>
              {certificates.map((certificate) => (
                <article className={styles.certificateRow} key={certificate.id}>
                  <div className={styles.certificateIcon} aria-hidden="true">QE</div>
                  <div className={styles.certificateInfo}>
                    <strong>{certificate.achievement_title}</strong>
                    <span>{(certificate.language || 'en') === 'hi' ? 'कक्षा ' : 'Class '}{certificate.class_code} · {localizedTrackName(certificate.subject_code, certificate.subject_name, certificate.language || 'en')}</span>
                    <span>{certificate.certificate_number} · {certificate.template_id}</span>
                    <small>Issued {new Date(certificate.issued_at).toLocaleDateString()} · {certificate.language === 'hi' ? 'Hindi' : 'English'}</small>
                  </div>
                  <span className={certificate.status === 'ISSUED' ? styles.valid : styles.revoked}>{certificate.status}</span>
                  <div className={styles.downloads}>
                    {certificate.status === 'ISSUED' ? <>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/view?format=pdf'} target="_blank" rel="noreferrer">View certificate ↗</a>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/download?format=pdf'}>Download PDF</a>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/download?format=png'}>Download PNG</a>
                    </> : <small>Revoked · contact support if you believe this is an error.</small>}
                    <Link href={'/certificate/verify?code=' + encodeURIComponent(certificate.certificate_number)} target="_blank">Verify status ↗</Link>
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
