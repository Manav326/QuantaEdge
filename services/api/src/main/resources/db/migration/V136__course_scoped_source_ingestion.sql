-- V136: bind textbook source-ingestion jobs to one institution-assigned course.
-- Historical global jobs are moved to the legacy QuantaEdge tenant. A historical job that
-- cannot be resolved to exactly one assigned course remains visible only to migration tooling;
-- all new jobs must identify their institution and course.
ALTER TABLE source_ingestion_job
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT,
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

DO $require_qe_source_jobs$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM institution_tenant
    WHERE tenant_code='QUANTA-EDGE' AND status='ACTIVE'
  ) THEN
    RAISE EXCEPTION 'V136 requires the active QUANTA-EDGE legacy destination tenant.';
  END IF;
END
$require_qe_source_jobs$;

UPDATE source_ingestion_job j
SET institution_id=tenant.id
FROM institution_tenant tenant
WHERE tenant.tenant_code='QUANTA-EDGE' AND j.institution_id IS NULL;

-- Resolve only unambiguous course mappings. Do not guess a course when a legacy shared
-- curriculum subject is mapped into multiple courses.
WITH eligible_course_subject AS (
  SELECT DISTINCT s.id AS subject_id, p.id AS course_id
  FROM curriculum_subject s
  JOIN curriculum_program_subject mapping
    ON mapping.subject_id=s.id AND mapping.class_id=s.class_id
  JOIN curriculum_program p
    ON p.id=mapping.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN institution_course_assignment assignment
    ON assignment.status='ASSIGNED'
  JOIN institution_course_release release
    ON release.id=assignment.course_release_id AND release.program_id=p.id
       AND release.release_state NOT IN ('WITHDRAWN','REJECTED')
  JOIN institution_tenant tenant ON tenant.id=assignment.institution_id
    AND tenant.tenant_code='QUANTA-EDGE' AND tenant.status='ACTIVE'
  WHERE s.active=true
    AND (s.program_id=p.id OR s.program_id IS NULL)
),
candidate_counts AS (
  SELECT j.id AS job_id, min(eligible.course_id) AS course_id,
         count(DISTINCT eligible.course_id) AS course_count
  FROM source_ingestion_job j
  JOIN eligible_course_subject eligible ON eligible.subject_id=j.subject_id
  GROUP BY j.id
)
UPDATE source_ingestion_job j
SET course_id=c.course_id
FROM candidate_counts c
WHERE c.job_id=j.id AND c.course_count=1 AND j.course_id IS NULL;

ALTER TABLE source_ingestion_job
  ALTER COLUMN institution_id SET NOT NULL;

CREATE INDEX idx_source_ingestion_job_tenant_course
  ON source_ingestion_job(institution_id,course_id,updated_at DESC,id DESC);

-- Reject new/unscoped jobs and verify the course-to-subject mapping in the database as well as
-- the API. Existing unresolved legacy rows can still receive status-only maintenance updates.
CREATE OR REPLACE FUNCTION qe_guard_source_ingestion_course_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $source_job_scope$
BEGIN
  IF TG_OP='UPDATE'
     AND NEW.course_id IS NOT DISTINCT FROM OLD.course_id
     AND NEW.institution_id IS NOT DISTINCT FROM OLD.institution_id THEN
    RETURN NEW;
  END IF;

  IF NEW.institution_id IS NULL OR NEW.course_id IS NULL THEN
    RAISE EXCEPTION 'COURSE_SCOPE_REQUIRED: source ingestion requires institution_id and course_id'
      USING ERRCODE='23514';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM institution_course_assignment assignment
    JOIN institution_course_release release
      ON release.id=assignment.course_release_id
    WHERE assignment.institution_id=NEW.institution_id
      AND assignment.status='ASSIGNED'
      AND release.program_id=NEW.course_id
      AND release.release_state NOT IN ('WITHDRAWN','REJECTED')
  ) THEN
    RAISE EXCEPTION 'COURSE_SCOPE_INVALID: source ingestion course is not assigned to this institution'
      USING ERRCODE='23514';
  END IF;

  IF NOT EXISTS (
    SELECT 1
    FROM curriculum_program_subject mapping
    JOIN curriculum_program p ON p.id=mapping.program_id
      AND p.status='ACTIVE' AND p.code<>'GENERAL'
    JOIN curriculum_subject s ON s.id=mapping.subject_id
      AND s.class_id=mapping.class_id AND s.active=true
    WHERE mapping.program_id=NEW.course_id
      AND s.id=NEW.subject_id
      AND (s.program_id=p.id OR s.program_id IS NULL)
  ) THEN
    RAISE EXCEPTION 'COURSE_SCOPE_INVALID: source ingestion subject is not mapped to the selected course'
      USING ERRCODE='23514';
  END IF;

  RETURN NEW;
END
$source_job_scope$;

CREATE TRIGGER trg_source_ingestion_job_course_scope
  BEFORE INSERT OR UPDATE OF institution_id,course_id,subject_id
  ON source_ingestion_job
  FOR EACH ROW EXECUTE FUNCTION qe_guard_source_ingestion_course_scope();

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-scoped-source-ingestion-v136')
ON CONFLICT (key) DO UPDATE SET value=excluded.value;
