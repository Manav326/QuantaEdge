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
  status,
  children,
}: MarketplaceCourseCardProps) {
  const chapterCount = subjects.reduce((count, subject) =>
    count + (Number.isFinite(Number(subject.chapters)) ? Number(subject.chapters) : 0), 0);
  const lessonCount = subjects.reduce((count, subject) =>
    count + (Number.isFinite(Number(subject.lessons)) ? Number(subject.lessons) : 0), 0);
  const hasCoverageCounts = subjects.some(subject =>
    subject.chapters !== undefined || subject.lessons !== undefined);

  return (
    <article className={styles.card} lang={language}>
      <div className={styles.top}>
        <div className={styles.mark} aria-hidden="true">✦</div>
        <div className={styles.price}>{priceLabel}</div>
      </div>

      <h2 className={styles.title}>{title}</h2>

      {(courseCode || publicId || curriculumVersion || subjects.length > 0) && (
        <div className={styles.metadata}>
          {courseCode && <span className={styles.courseCode}>{courseCode}</span>}
          {publicId && <span className={styles.publicId}>{language === 'hi' ? 'कोर्स ID' : 'Course ID'}: {publicId}</span>}
          {curriculumVersion && <span className={styles.version}>{curriculumVersion}</span>}
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
