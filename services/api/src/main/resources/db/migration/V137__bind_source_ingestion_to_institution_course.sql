-- V137: bind admin source-ingestion jobs to the institution and exact course they prepare.
-- Historic jobs are assigned to QuantaEdge as the legacy destination tenant. Their course is
-- backfilled only when one active released course maps to that exact subject. Ambiguous jobs
-- remain historical/unbound and are excluded from new course-scoped queues.
ALTER TABLE source_ingestion_job
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT,
  ADD COLUMN course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

DO $require_quantaedge$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM institution_tenant
    WHERE tenant_code='QUANTA-EDGE' AND status='ACTIVE'
  ) THEN
    RAISE EXCEPTION 'V136 requires the active QUANTA-EDGE destination institution.';
  END IF;
END
$require_quantaedge$;

UPDATE source_ingestion_job j
SET institution_id=t.id
FROM institution_tenant t
WHERE t.tenant_code='QUANTA-EDGE'
  AND t.status='ACTIVE'
  AND j.institution_id IS NULL;

WITH candidates AS (
  SELECT j.id AS job_id,min(p.id) AS course_id
  FROM source_ingestion_job j
  JOIN curriculum_subject s ON s.id=j.subject_id AND s.active=true
  JOIN curriculum_program_subject cps ON cps.subject_id=s.id AND cps.class_id=s.class_id
  JOIN curriculum_program p ON p.id=cps.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  JOIN institution_course_assignment a ON a.institution_id=j.institution_id AND a.status='ASSIGNED'
  JOIN institution_course_release r ON r.id=a.course_release_id AND r.program_id=p.id
  WHERE j.course_id IS NULL AND j.institution_id IS NOT NULL
    AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  GROUP BY j.id
  HAVING count(DISTINCT p.id)=1
)
UPDATE source_ingestion_job j
SET course_id=c.course_id
FROM candidates c
WHERE j.id=c.job_id AND j.course_id IS NULL;

ALTER TABLE source_ingestion_job
  ALTER COLUMN institution_id SET NOT NULL;

CREATE INDEX idx_source_ingestion_job_tenant_course_status
  ON source_ingestion_job(institution_id,course_id,status,created_at DESC,id DESC);

CREATE OR REPLACE FUNCTION qe_require_source_ingestion_course_scope()
RETURNS trigger
LANGUAGE plpgsql
AS $source_course_scope$
BEGIN
  IF NEW.institution_id IS NULL OR NEW.institution_id<1 THEN
    RAISE EXCEPTION 'INSTITUTION_SCOPE_REQUIRED: source ingestion must belong to an institution'
      USING ERRCODE='23514';
  END IF;
  IF NEW.course_id IS NULL OR NEW.course_id<1 THEN
    RAISE EXCEPTION 'COURSE_SCOPE_REQUIRED: new source ingestion jobs must belong to one selected course'
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
      AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  ) THEN
    RAISE EXCEPTION 'COURSE_SUBJECT_SCOPE_MISMATCH: source subject is not mapped to the selected institution course'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$source_course_scope$;

CREATE TRIGGER trg_source_ingestion_job_course_scope
  BEFORE INSERT OR UPDATE OF institution_id,course_id,subject_id
  ON source_ingestion_job
  FOR EACH ROW EXECUTE FUNCTION qe_require_source_ingestion_course_scope();

INSERT INTO app_metadata(key,value)
VALUES ('schema','source-ingestion-institution-course-v137')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
