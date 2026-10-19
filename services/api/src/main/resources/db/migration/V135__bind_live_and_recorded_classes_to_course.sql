-- V135: bind every new live class and recording to exactly one institution course.
-- Historical rows are backfilled only when the tenant's released course mapping is
-- unambiguous. Ambiguous records remain unbound and are hidden from course views until remapped.

ALTER TABLE learning_class_session
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;
ALTER TABLE recorded_class
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

WITH candidates AS (
  SELECT cs.id AS session_id, min(p.id) AS course_id
  FROM learning_class_session cs
  JOIN curriculum_subject s ON s.id=cs.subject_id AND s.active=true
  JOIN curriculum_program_subject cps ON cps.subject_id=s.id AND cps.class_id=s.class_id
  JOIN curriculum_program p ON p.id=cps.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN institution_course_assignment a ON a.institution_id=cs.institution_id AND a.status='ASSIGNED'
  JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
  WHERE cs.institution_id IS NOT NULL
    AND (s.program_id=p.id OR s.program_id IS NULL)
  GROUP BY cs.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE learning_class_session cs
SET course_id=c.course_id
FROM candidates c
WHERE cs.id=c.session_id AND cs.course_id IS NULL;

-- A recording linked to a bound live session inherits only that exact course and tenant.
UPDATE recorded_class rc
SET course_id=cs.course_id
FROM learning_class_session cs
WHERE rc.course_id IS NULL AND rc.class_session_id=cs.id
  AND cs.course_id IS NOT NULL
  AND rc.institution_id=cs.institution_id
  AND rc.subject_id=cs.subject_id;

WITH candidates AS (
  SELECT rc.id AS recording_id, min(p.id) AS course_id
  FROM recorded_class rc
  JOIN curriculum_subject s ON s.id=rc.subject_id AND s.active=true
  JOIN curriculum_program_subject cps ON cps.subject_id=s.id AND cps.class_id=s.class_id
  JOIN curriculum_program p ON p.id=cps.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN institution_course_assignment a ON a.institution_id=rc.institution_id AND a.status='ASSIGNED'
  JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
  WHERE rc.institution_id IS NOT NULL
    AND (s.program_id=p.id OR s.program_id IS NULL)
  GROUP BY rc.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE recorded_class rc
SET course_id=c.course_id
FROM candidates c
WHERE rc.id=c.recording_id AND rc.course_id IS NULL;

CREATE INDEX idx_learning_class_session_tenant_course_time
  ON learning_class_session(institution_id,course_id,starts_at DESC,status);
CREATE INDEX idx_recorded_class_tenant_course_status
  ON recorded_class(institution_id,course_id,status,created_at DESC);

CREATE OR REPLACE FUNCTION qe_require_course_scoped_class_content()
RETURNS trigger
LANGUAGE plpgsql
AS $course_scope$
BEGIN
  IF NEW.institution_id IS NULL OR NEW.institution_id<1 THEN
    RAISE EXCEPTION 'INSTITUTION_SCOPE_REQUIRED: live classes and recordings must belong to an institution'
      USING ERRCODE='23514';
  END IF;
  IF NEW.course_id IS NULL OR NEW.course_id<1 THEN
    RAISE EXCEPTION 'COURSE_SCOPE_REQUIRED: live classes and recordings must belong to one course'
      USING ERRCODE='23514';
  END IF;
  IF NOT EXISTS (
    SELECT 1
    FROM curriculum_program p
    JOIN curriculum_program_subject cps ON cps.program_id=p.id AND cps.subject_id=NEW.subject_id
    JOIN curriculum_subject s ON s.id=cps.subject_id AND s.class_id=cps.class_id AND s.active=true
    JOIN institution_course_assignment a ON a.institution_id=NEW.institution_id AND a.status='ASSIGNED'
    JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
    WHERE p.id=NEW.course_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
      AND (s.program_id=p.id OR s.program_id IS NULL)
  ) THEN
    RAISE EXCEPTION 'COURSE_SUBJECT_SCOPE_MISMATCH: the subject is not mapped to an active course assigned to this institution'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$course_scope$;

CREATE TRIGGER trg_learning_class_session_course_scope
  BEFORE INSERT ON learning_class_session
  FOR EACH ROW EXECUTE FUNCTION qe_require_course_scoped_class_content();
CREATE TRIGGER trg_recorded_class_course_scope
  BEFORE INSERT ON recorded_class
  FOR EACH ROW EXECUTE FUNCTION qe_require_course_scoped_class_content();

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-bound-live-and-recorded-classes-v135')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
