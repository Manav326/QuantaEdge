-- V126: add institution ownership to learner history and live/recorded activity.
-- The columns remain nullable while legacy and platform/system write paths are migrated.
-- Every row that predates the tenant contract is QuantaEdge history and is assigned only to
-- QUANTA-EDGE. New tenant-aware write paths must always set institution_id explicitly.

DO $require_legacy_tenant$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM institution_tenant WHERE tenant_code='QUANTA-EDGE') THEN
    RAISE EXCEPTION 'V126 requires the QUANTA-EDGE tenant from V125.';
  END IF;
END
$require_legacy_tenant$;

ALTER TABLE student_program_enrollment
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_track_enrollment
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

ALTER TABLE student_lesson_progress
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_question_attempt
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE learning_session
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_practice_session
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_diagnostic_attempt
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_assessment_attempt
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_certificate
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

ALTER TABLE learning_class_session
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE recorded_class
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_recorded_class_progress
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

-- These tables did not persist tenant context at creation time. Their pre-V120 history is
-- platform/QuantaEdge history; use one explicit one-time mapping, not current membership inference.
UPDATE student_program_enrollment SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_track_enrollment SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_lesson_progress SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_question_attempt SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE learning_session SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_practice_session SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_diagnostic_attempt SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_assessment_attempt SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_certificate SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE learning_class_session SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE recorded_class SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;
UPDATE student_recorded_class_progress SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND institution_id IS NULL;

CREATE INDEX idx_student_program_enrollment_institution
  ON student_program_enrollment(institution_id,student_id,status,program_id);
CREATE INDEX idx_student_track_enrollment_institution
  ON student_track_enrollment(institution_id,student_id,status,subject_id);
CREATE INDEX idx_student_lesson_progress_institution
  ON student_lesson_progress(institution_id,student_id,lesson_id,status);
CREATE INDEX idx_student_question_attempt_institution
  ON student_question_attempt(institution_id,student_id,answered_at DESC);
CREATE INDEX idx_learning_session_institution
  ON learning_session(institution_id,student_id,started_at DESC);
CREATE INDEX idx_student_practice_session_institution
  ON student_practice_session(institution_id,student_id,created_at DESC);
CREATE INDEX idx_student_diagnostic_attempt_institution
  ON student_diagnostic_attempt(institution_id,student_id,started_at DESC);
CREATE INDEX idx_student_assessment_attempt_institution
  ON student_assessment_attempt(institution_id,student_id,assessment_id,created_at DESC);
CREATE INDEX idx_student_certificate_institution
  ON student_certificate(institution_id,student_id,issued_at DESC);
CREATE INDEX idx_learning_class_session_institution
  ON learning_class_session(institution_id,subject_id,starts_at,status);
CREATE INDEX idx_recorded_class_institution
  ON recorded_class(institution_id,subject_id,status,created_at DESC);
CREATE INDEX idx_student_recorded_progress_institution
  ON student_recorded_class_progress(institution_id,student_id,updated_at DESC);

INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-scoped-learning-history-v126')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
