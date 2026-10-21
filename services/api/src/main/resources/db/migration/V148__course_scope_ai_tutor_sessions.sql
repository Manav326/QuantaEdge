-- V148: add course ownership to AI tutor sessions.
-- New sessions must have explicit institution + course scope. Historical sessions are
-- attributed only when exactly one released course in that institution maps the source lesson;
-- ambiguous/unscoped sessions are retained with course_id NULL and never guessed into a course.

ALTER TABLE tutor_session
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

WITH candidates AS (
  SELECT ts.id AS session_id, min(p.id) AS course_id
  FROM tutor_session ts
  JOIN lesson l ON l.id=ts.lesson_id
  JOIN curriculum_chapter ch ON ch.id=l.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id
  JOIN student_program_enrollment spe ON spe.student_id=ts.student_id
    AND spe.institution_id=ts.institution_id AND spe.status IN ('ACTIVE','PAUSED','ENDED')
  JOIN curriculum_program p ON p.id=spe.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN curriculum_program_subject cps ON cps.program_id=p.id
    AND cps.subject_id=s.id AND cps.class_id=s.class_id
  JOIN institution_course_assignment assignment ON assignment.institution_id=ts.institution_id
    AND assignment.status='ASSIGNED'
  JOIN institution_course_release release ON release.id=assignment.course_release_id
    AND release.program_id=p.id
  WHERE ts.institution_id IS NOT NULL AND ts.course_id IS NULL
    AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  GROUP BY ts.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE tutor_session ts SET course_id=c.course_id
FROM candidates c WHERE c.session_id=ts.id AND ts.course_id IS NULL;

CREATE INDEX idx_tutor_session_course_owner
  ON tutor_session(institution_id,student_id,course_id,lesson_id,created_at DESC,id DESC);

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-scoped-ai-tutor-sessions-v148')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
