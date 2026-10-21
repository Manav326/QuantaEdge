-- V161: restore the selected-course relationship for legacy certificates where it is provable.
-- V98 added student_certificate.program_id but deliberately preserved historical certificates.
-- Learner history must keep its certificate identity, issuance timestamp, and stored artifact.
-- Only attach a certificate when its exact source class + subject map to one current Class 6-8
-- course product. Unmapped/ambiguous records stay untouched rather than being guessed.

WITH unambiguous_course AS (
  SELECT cert.id AS certificate_id, MIN(program.id) AS course_id
  FROM student_certificate cert
  JOIN curriculum_class source_class
    ON source_class.code = cert.class_code
   AND source_class.active = true
  JOIN curriculum_subject source_subject
    ON source_subject.id = cert.subject_id
   AND source_subject.class_id = source_class.id
   AND source_subject.active = true
  JOIN curriculum_program_subject course_subject
    ON course_subject.class_id = source_class.id
   AND course_subject.subject_id = source_subject.id
  JOIN curriculum_program program
    ON program.id = course_subject.program_id
   AND program.class_id = source_class.id
   AND program.code = 'CLASS_' || source_class.code
   AND program.curriculum_version = 'CURRENT'
   AND program.status = 'ACTIVE'
  WHERE cert.program_id IS NULL
  GROUP BY cert.id
  HAVING COUNT(DISTINCT program.id) = 1
)
UPDATE student_certificate cert
SET program_id = match.course_id
FROM unambiguous_course match
WHERE cert.id = match.certificate_id
  AND cert.program_id IS NULL;

-- Intentionally do not regenerate or rewrite PDF/PNG files, certificate numbers, issue dates,
-- revocation state, or hashes. The existing issued artifact remains the authority.

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'backfill-legacy-certificate-course-identity-v161')
ON CONFLICT(key) DO UPDATE SET value = EXCLUDED.value;
