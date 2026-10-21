-- V128: separate learner history and identity keys by institution.
-- V126 introduced institution_id columns; this migration makes their natural keys tenant-aware.
-- Null-scoped legacy rows remain inert until an explicit, audited reconciliation identifies them.

ALTER TABLE student_concept_mastery
  ADD COLUMN IF NOT EXISTS institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE tutor_session
  ADD COLUMN IF NOT EXISTS institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE course_entitlement_transfer_audit
  ADD COLUMN IF NOT EXISTS institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

UPDATE student_concept_mastery h SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND h.institution_id IS NULL;
UPDATE tutor_session h SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND h.institution_id IS NULL;
UPDATE course_entitlement_transfer_audit h SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND h.institution_id IS NULL;

-- Remove legacy natural-key UNIQUE constraints that would merge a student's records between
-- institutions. Primary keys and globally unique public/receipt/certificate identifiers remain.
DO $tenant_keys$
DECLARE
  target record;
  constraint_row record;
BEGIN
  FOR target IN
    SELECT * FROM (VALUES
      ('student_program_enrollment', ARRAY['program_id','student_id']::text[]),
      ('student_track_enrollment', ARRAY['student_id','subject_id']::text[]),
      ('student_lesson_progress', ARRAY['lesson_id','student_id']::text[]),
      ('student_concept_mastery', ARRAY['concept_id','student_id']::text[]),
      ('student_certificate', ARRAY['achievement_key','student_id']::text[]),
      ('course_purchase', ARRAY['course_id','student_id']::text[]),
      ('parent_course_purchase_request', ARRAY['course_id','status','student_id']::text[]),
      ('student_assessment_attempt', ARRAY['assessment_id','attempt_number','student_id']::text[]),
      ('student_recorded_class_progress', ARRAY['recorded_class_id','student_id']::text[]),
      ('course_entitlement_transfer_audit', ARRAY['course_id','from_user_id','to_student_id','transfer_number']::text[])
    ) AS target_table(table_name, old_columns)
  LOOP
    FOR constraint_row IN
      SELECT c.conname
      FROM pg_constraint c
      WHERE c.conrelid = format('public.%I', target.table_name)::regclass
        AND c.contype = 'u'
        AND (
          SELECT array_agg(a.attname::text ORDER BY a.attname::text)
          FROM unnest(c.conkey) AS key(attnum)
          JOIN pg_attribute a ON a.attrelid=c.conrelid AND a.attnum=key.attnum
        ) = target.old_columns
    LOOP
      EXECUTE format('ALTER TABLE public.%I DROP CONSTRAINT %I', target.table_name, constraint_row.conname);
    END LOOP;
  END LOOP;
END
$tenant_keys$;

-- The old "one open order per student/course" and parent idempotency keys were global.
DROP INDEX IF EXISTS uq_course_payment_order_open;
DROP INDEX IF EXISTS uq_course_payment_order_purchaser_idempotency;
CREATE UNIQUE INDEX uq_course_payment_order_open_tenant_student
  ON course_payment_order(institution_id,student_id,course_id)
  WHERE institution_id IS NOT NULL AND student_id IS NOT NULL AND status IN ('CREATING','PENDING');
CREATE UNIQUE INDEX uq_course_payment_order_open_tenant_parent
  ON course_payment_order(institution_id,purchaser_user_id,course_id)
  WHERE institution_id IS NOT NULL AND purchaser_user_id IS NOT NULL AND status IN ('CREATING','PENDING');
CREATE UNIQUE INDEX uq_course_payment_order_purchaser_idempotency_tenant
  ON course_payment_order(institution_id,purchaser_user_id,course_id,client_request_key)
  WHERE institution_id IS NOT NULL AND purchaser_user_id IS NOT NULL;

-- Recreate tenant-scoped history/ownership keys. Null institution_id rows remain unscoped and
-- must not be returned by learner-facing tenant queries.
CREATE UNIQUE INDEX uq_student_program_enrollment_tenant
  ON student_program_enrollment(institution_id,student_id,program_id);
CREATE UNIQUE INDEX uq_student_track_enrollment_tenant
  ON student_track_enrollment(institution_id,student_id,subject_id);
CREATE UNIQUE INDEX uq_student_lesson_progress_tenant
  ON student_lesson_progress(institution_id,student_id,lesson_id);
CREATE UNIQUE INDEX uq_student_concept_mastery_tenant
  ON student_concept_mastery(institution_id,student_id,concept_id);
CREATE UNIQUE INDEX uq_student_certificate_achievement_tenant
  ON student_certificate(institution_id,student_id,achievement_key);
CREATE UNIQUE INDEX uq_course_purchase_student_course_tenant
  ON course_purchase(institution_id,student_id,course_id);
CREATE UNIQUE INDEX uq_course_purchase_parent_course_tenant
  ON course_purchase(institution_id,purchaser_user_id,course_id);
CREATE UNIQUE INDEX uq_parent_course_request_status_tenant
  ON parent_course_purchase_request(institution_id,guardian_user_id,student_id,course_id,status);
CREATE UNIQUE INDEX uq_student_assessment_attempt_number_tenant
  ON student_assessment_attempt(institution_id,assessment_id,student_id,attempt_number);
DROP INDEX IF EXISTS uq_assessment_attempt_in_progress;
CREATE UNIQUE INDEX uq_assessment_attempt_in_progress_tenant
  ON student_assessment_attempt(institution_id,assessment_id,student_id)
  WHERE institution_id IS NOT NULL AND status='IN_PROGRESS';
CREATE UNIQUE INDEX uq_student_recorded_class_progress_tenant
  ON student_recorded_class_progress(institution_id,student_id,recorded_class_id);
CREATE UNIQUE INDEX uq_course_entitlement_transfer_tenant
  ON course_entitlement_transfer_audit(institution_id,course_id,from_user_id,to_student_id,transfer_number);
CREATE INDEX idx_student_concept_mastery_tenant
  ON student_concept_mastery(institution_id,student_id,mastery_percent,updated_at);
CREATE INDEX idx_tutor_session_tenant
  ON tutor_session(institution_id,student_id,lesson_id,created_at DESC);

DROP INDEX IF EXISTS uq_parent_course_purchase_request_tenant_pending;
CREATE UNIQUE INDEX uq_parent_course_purchase_request_tenant_pending
  ON parent_course_purchase_request(guardian_user_id,student_id,institution_id,course_id)
  WHERE status='PENDING' AND institution_id IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-isolated-learner-history-v128')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
