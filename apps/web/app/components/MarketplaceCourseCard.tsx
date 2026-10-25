import type { ReactNode } from 'react';
import styles from './MarketplaceCourseCard.module.css';

/** Shared summary data used by student and family marketplace listings. */
export type MarketplaceCourseSubject = {
  subject_code?: string;
  subject_name?: string;
  chapters?: number;
  lessons?: number;
};

type MarketplaceCourseCardProps = {
  title: string;
  courseCode?: string;
  publicId?: string;
  curriculumVersion?: string;
  language?: 'en' | 'hi';
  priceLabel: ReactNode;
  description?: string | null;
  subjects?: MarketplaceCourseSubject[];
  courseTypeCode?: string;
  testPackCount?: number;
  assessmentCount?: number;
  assessmentQuestionCount?: number;
  status?: ReactNode;
  children: ReactNode;
};

/**
 * One common course summary shell for all marketplace audiences.
 * Audience-specific purchase, request and entitlement actions remain in the caller.
 */
export default function MarketplaceCourseCard({
  title,
  courseCode,
  publicId,
  curriculumVersion,
  language = 'en',
  priceLabel,
  description,
  subjects = [],
  courseTypeCode,
  testPackCount = 0,
  assessmentCount = 0,
  assessmentQuestionCount = 0,
  status,
  children,
}: MarketplaceCourseCardProps) {
  const chapterCount = subjects.reduce((count, subject) =>
    count + (Number.isFinite(Number(subject.chapters)) ? Number(subject.chapters) : 0), 0);
  const lessonCount = subjects.reduce((count, subject) =>
    count + (Number.isFinite(Number(subject.lessons)) ? Number(subject.lessons) : 0), 0);
  const hasCoverageCounts = subjects.some(subject =>
    subject.chapters !== undefined || subject.lessons !== undefined);
  const normalizedCourseType = String(courseTypeCode || '').trim().toUpperCase();
  const courseTypeLabel = normalizedCourseType === 'ASSESSMENT'
    ? (language === 'hi' ? 'परीक्षा एवं टेस्ट' : 'Assessment & tests')
    : normalizedCourseType === 'COMPETITIVE'
      ? (language === 'hi' ? 'प्रतियोगी परीक्षा तैयारी' : 'Competitive preparation')
      : normalizedCourseType === 'K12'
        ? (language === 'hi' ? 'विद्यालयी पढ़ाई' : 'K–12 learning')
        : '';
  const safePackCount = Number.isFinite(Number(testPackCount)) ? Math.max(0, Number(testPackCount)) : 0;
  const safeAssessmentCount = Number.isFinite(Number(assessmentCount)) ? Math.max(0, Number(assessmentCount)) : 0;
  const safeQuestionCount = Number.isFinite(Number(assessmentQuestionCount)) ? Math.max(0, Number(assessmentQuestionCount)) : 0;
  const showAssessmentSummary = normalizedCourseType === 'ASSESSMENT'
    || safePackCount > 0 || safeAssessmentCount > 0 || safeQuestionCount > 0;
  // Never expose the internal CURRENT enum as though it were a user-facing curriculum name.
  const curriculumVersionLabel = !curriculumVersion ? undefined
    : curriculumVersion.trim().toUpperCase() === 'CURRENT'
      ? (language === 'hi' ? 'वर्तमान पाठ्यक्रम' : 'Current curriculum')
      : curriculumVersion;

  return (
    <article className={styles.card} lang={language}>
      <div className={styles.top}>
        <div className={styles.mark} aria-hidden="true">✦</div>
        <div className={styles.price}>{priceLabel}</div>
      </div>

      <h2 className={styles.title}>{title}</h2>

      {(courseCode || publicId || curriculumVersion || courseTypeLabel || subjects.length > 0 || showAssessmentSummary) && (
        <div className={styles.metadata}>
          {courseCode && <span className={styles.courseCode}>{courseCode}</span>}
          {publicId && <span className={styles.publicId}>{language === 'hi' ? 'कोर्स ID' : 'Course ID'}: {publicId}</span>}
          {curriculumVersionLabel && <span className={styles.version}>{curriculumVersionLabel}</span>}
          {courseTypeLabel && <span className={styles.version}>{courseTypeLabel}</span>}
          {showAssessmentSummary && (
            <span className={styles.coverage}>
              {language === 'hi'
                ? `${safePackCount} test packs · ${safeAssessmentCount} टेस्ट · ${safeQuestionCount} प्रश्न`
                : `${safePackCount} test packs · ${safeAssessmentCount} tests · ${safeQuestionCount} questions`}
            </span>
          )}
          {subjects.length > 0 && (
            <span className={styles.coverage}>
              {language === 'hi'
                ? `${subjects.length} विषय${hasCoverageCounts ? ` · ${chapterCount} अध्याय · ${lessonCount} पाठ` : ''}`
                : `${subjects.length} subject${subjects.length === 1 ? '' : 's'}${hasCoverageCounts ? ` · ${chapterCount} chapters · ${lessonCount} lessons` : ''}`}
            </span>
          )}
        </div>
      )}

      {description && <p className={styles.description}>{description}</p>}

      {!!subjects.length && (
        <div className={styles.subjects} aria-label={language === 'hi' ? 'कोर्स के विषय' : 'Course subjects'}>
          {subjects.map((subject, index) => (
            <span key={subject.subject_code || subject.subject_name || index}>
              {subject.subject_name || subject.subject_code || (language === 'hi' ? 'विषय' : 'Subject')}
            </span>
          ))}
        </div>
      )}

      {status && <div className={styles.status}>{status}</div>}

      <div className={styles.actions}>{children}</div>
    </article>
  );
}
