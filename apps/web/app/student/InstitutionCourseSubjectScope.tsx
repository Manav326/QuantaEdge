'use client';

import { useTenantBranding } from '../components/TenantBrandingProvider';
import styles from './InstitutionCourseSubjectScope.module.css';
import { PrimaryCoursePicker, type EnrolledCourse, type EnrolledCourseSubject } from './CourseScope';

type Props = {
  courses: EnrolledCourse[];
  activeCourse: EnrolledCourse | null;
  courseId: number | null;
  onCourseChange: (value: string) => void;
  subjectCode: string;
  onSubjectChange: (value: string) => void;
  loading?: boolean;
  language: 'english' | 'hinglish' | string;
  includeAllSubjects?: boolean;
  showSubject?: boolean;
  courseLabel?: string;
  subjectLabel?: string;
  allSubjectsLabel?: string;
  className?: string;
};

/**
 * Shared, tenant-aware scope control for every learner surface.
 * Institution comes from the validated tenant session/branding provider; it is deliberately
 * not an arbitrary user-entered tenant ID. Course/subject options come only from the selected
 * institution's entitled course response.
 */
export default function InstitutionCourseSubjectScope({
  courses, activeCourse, courseId, onCourseChange, subjectCode, onSubjectChange,
  loading = false, language, includeAllSubjects = true, showSubject = true, courseLabel, subjectLabel,
  allSubjectsLabel, className = '',
}: Props) {
  const { brand, isResolving, institutionContextRequested } = useTenantBranding();
  const english = language === 'english';
  const institutionLabel = english ? 'Institution' : 'संस्थान';
  const resolvedName = brand?.displayName?.trim();
  const institutionName = resolvedName || (isResolving
    ? (english ? 'Resolving institution…' : 'संस्थान की पहचान हो रही है…')
    : institutionContextRequested
      ? (english ? 'Institution context unavailable' : 'संस्थान का संदर्भ उपलब्ध नहीं')
      : (english ? 'Current institution' : 'वर्तमान संस्थान'));
  const subjects: EnrolledCourseSubject[] = activeCourse?.subjects || [];
  const resolvedCourseLabel = courseLabel || (english ? 'Course' : 'कोर्स');
  const resolvedSubjectLabel = subjectLabel || (english ? 'Subject' : 'विषय');
  const resolvedAllSubjects = allSubjectsLabel || (english ? 'All subjects' : 'सभी विषय');
  const subjectIsValid = !subjectCode || subjectCode === 'ALL' || subjects.some(subject =>
    String(subject.subject_code || subject.code || '') === subjectCode
  );
  const selectedSubject = subjectIsValid ? subjectCode : (includeAllSubjects ? 'ALL' : '');

  return (
    <div className={[styles.scope, !showSubject ? styles.scopeNoSubject : '', 'qe-institution-course-subject-scope', className].filter(Boolean).join(' ')} data-course-id={courseId ?? ''}>
      <div className={styles.institution} aria-label={institutionLabel}>
        <span>{institutionLabel}</span>
        <strong>{institutionName}</strong>
      </div>
      <PrimaryCoursePicker
        courses={courses}
        courseId={courseId}
        onChange={onCourseChange}
        loading={loading}
        label={resolvedCourseLabel}
      />
      {showSubject && <label className={styles.field}>
        <span>{resolvedSubjectLabel}</span>
        <select
          value={selectedSubject || (includeAllSubjects ? 'ALL' : '')}
          disabled={loading || courseId === null || subjects.length === 0}
          onChange={event => onSubjectChange(event.target.value)}
        >
          {includeAllSubjects && <option value="ALL">{resolvedAllSubjects}</option>}
          {!includeAllSubjects && <option value="">{english ? 'Choose a subject' : 'विषय चुनें'}</option>}
          {subjects.map(subject => {
            const code = String(subject.subject_code || subject.code || '');
            if (!code) return null;
            return <option key={code} value={code}>
              {String(subject.subject_name || subject.display_name || code)}
            </option>;
          })}
        </select>
      </label>}
    </div>
  );
}
