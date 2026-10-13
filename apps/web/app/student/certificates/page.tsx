'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import styles from './Certificates.module.css';

const TEMPLATES = [
  { id: 'CLASSIC', name: 'Classic', description: 'Formal navy and gold frame' },
  { id: 'MODERN', name: 'Modern', description: 'Clean navy layout with orange accents' },
  { id: 'ACADEMIC', name: 'Academic', description: 'Academic green and gold frame' },
];

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
  const [profile, setProfile] = useState<any>(null);
  const [certificates, setCertificates] = useState<Certificate[]>([]);
  const [eligibleAttempts, setEligibleAttempts] = useState<any[]>([]);
  const [passThreshold, setPassThreshold] = useState(80);
  const [selectedTemplate, setSelectedTemplate] = useState('CLASSIC');
  const [achievementType, setAchievementType] = useState<'SUBJECT_COMPLETION' | 'ASSESSMENT_PASS'>('SUBJECT_COMPLETION');
  const [subjectCode, setSubjectCode] = useState('maths');
  const [attemptId, setAttemptId] = useState('');
  const [language, setLanguage] = useState('hi');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const languageInitialized = useRef(false);

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
      if (curriculum.length && !curriculum.some((item: any) => item.subject_code === subjectCode)) {
        setSubjectCode(String(curriculum[0].subject_code || 'maths'));
      }
      if (!languageInitialized.current) {
        const accountLanguage = String(progress.language || '').toLowerCase();
        setLanguage(accountLanguage === 'en' || accountLanguage === 'english' ? 'en' : 'hi');
        languageInitialized.current = true;
      }
    } catch (e: any) {
      setError(e?.message || 'Unable to load certificates.');
    }
  }, [router, subjectCode]);

  useEffect(() => { void refresh(); }, [refresh]);

  const tracks = useMemo(() => Array.isArray(profile?.curriculum) ? profile.curriculum : [], [profile]);
  const releasedAttempts = useMemo(() => eligibleAttempts, [eligibleAttempts]);

  async function issueCertificate() {
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
              <select value={language} onChange={(event) => setLanguage(event.target.value)}>
                <option value="hi">हिन्दी (Hindi)</option>
                <option value="en">English</option>
              </select>
            </label>
            {achievementType === 'SUBJECT_COMPLETION' ? (
              <label className={styles.field}>
                <span>Subject track</span>
                <select value={subjectCode} onChange={(event) => setSubjectCode(event.target.value)}>
                  {tracks.map((track: any) => (
                    <option key={track.subject_code} value={track.subject_code}>
                      {track.subject_name || track.subject_code} · {Number(track.completed || 0)} / {Number(track.lessons || 0)} lessons complete
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
                      {attempt.assessment_title} · {attempt.final_score} / {attempt.max_score}
                    </option>
                  ))}
                </select>
              </label>
            )}
          </div>
          <div className={styles.rule}>
            <strong>Eligibility rule</strong>
            {achievementType === 'SUBJECT_COMPLETION'
              ? 'All currently published lessons in your active subject track must be completed. The server checks your actual progress records.'
              : 'Only an admin-created, non-generated assessment can qualify (not practice, topic-end or chapter-end tests). The result must be released, meet the ' + passThreshold + '% pass threshold, and all currently published lessons in that subject must be completed.'}
          </div>
          <button className="button button-dark" type="button"
            onClick={() => void issueCertificate()} disabled={busy || (achievementType === 'ASSESSMENT_PASS' && !attemptId)}>
            {busy ? 'Verifying achievement…' : 'Verify eligibility & issue certificate'}
          </button>
        </section>

        <section className={styles.panel}>
          <div className={styles.sectionHead}>
            <div><span className={styles.eyebrow}>03 · YOUR RECORDS</span><h2>Issued certificates</h2></div>
            <span className={styles.meta}>{certificates.length} records</span>
          </div>
          {!certificates.length ? (
            <div className={styles.empty}><strong>No certificates yet</strong><p>Complete all published lessons in a subject, or pass an eligible admin-created assessment after completing that subject. Practice and generated topic/chapter tests do not issue test certificates.</p></div>
          ) : (
            <div className={styles.certificateList}>
              {certificates.map((certificate) => (
                <article className={styles.certificateRow} key={certificate.id}>
                  <div className={styles.certificateIcon} aria-hidden="true">QE</div>
                  <div className={styles.certificateInfo}>
                    <strong>{certificate.achievement_title}</strong>
                    <span>{certificate.certificate_number} · {certificate.template_id}</span>
                    <small>Issued {new Date(certificate.issued_at).toLocaleDateString()} · {certificate.language === 'hi' ? 'Hindi' : 'English'}</small>
                  </div>
                  <span className={certificate.status === 'ISSUED' ? styles.valid : styles.revoked}>{certificate.status}</span>
                  <div className={styles.downloads}>
                    {certificate.status === 'ISSUED' ? <>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/view?format=pdf'} target="_blank" rel="noreferrer">View certificate ↗</a>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/download?format=pdf'}>Download PDF</a>
                      <a href={'/api/v1/students/me/certificates/' + certificate.id + '/download?format=png'}>Download PNG</a>
                      <Link href={'/certificate/verify?code=' + encodeURIComponent(certificate.certificate_number)} target="_blank">Verify ↗</Link>
                    </> : <small>Revoked · contact support if you believe this is an error.</small>}
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
