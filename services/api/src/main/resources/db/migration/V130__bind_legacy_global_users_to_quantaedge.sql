-- V130: eliminate legacy/global learner identities by binding them to QuantaEdge.
-- This runs once on the deployed pre-existing dataset. It does not assign users created after
-- this migration to QUANTA-EDGE. Existing explicit tenant memberships are retained; learners
-- carrying pre-tenant history now owned by QUANTA-EDGE receive a QuantaEdge membership so that
-- their moved history remains available in its correct destination tenant.

DO $require_qe$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM institution_tenant
    WHERE tenant_code='QUANTA-EDGE' AND status='ACTIVE'
  ) THEN
    RAISE EXCEPTION 'V130 requires the active QUANTA-EDGE destination tenant.';
  END IF;
END
$require_qe$;

WITH qe AS (
  SELECT id FROM institution_tenant WHERE tenant_code='QUANTA-EDGE' AND status='ACTIVE'
),
legacy_learners AS (
  SELECT DISTINCT st.id AS student_id, qe.id AS institution_id
  FROM student st CROSS JOIN qe
  WHERE st.active=true AND st.environment='PRODUCTION'
    AND (
      -- Existing learner profiles with no tenant relationship were global identities.
      NOT EXISTS (
        SELECT 1 FROM institution_membership m
        WHERE m.student_id=st.id AND m.status='ACTIVE'
      )
      -- V125/V126 migrated these former global records to QuantaEdge. Keep users able to
      -- access their moved history even if they subsequently joined another institution.
      OR EXISTS (SELECT 1 FROM student_program_enrollment x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_track_enrollment x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_lesson_progress x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_question_attempt x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_assessment_attempt x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_practice_session x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_diagnostic_attempt x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_certificate x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_recorded_class_progress x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_concept_mastery x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM tutor_session x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM course_purchase x WHERE x.student_id=st.id AND x.institution_id=qe.id)
      OR EXISTS (SELECT 1 FROM student_course_access_grant x WHERE x.student_id=st.id AND x.institution_id=qe.id)
    )
),
inserted_memberships AS (
  INSERT INTO institution_membership(
    institution_id,student_id,join_code_id,status,joined_at,left_at,created_at,updated_at
  )
  SELECT institution_id,student_id,NULL,'ACTIVE',now(),NULL,now(),now()
  FROM legacy_learners
  ON CONFLICT(institution_id,student_id) DO NOTHING
  RETURNING id,institution_id,student_id
)
INSERT INTO institution_membership_event(
  institution_id,membership_id,student_id,event_type,details
)
SELECT institution_id,id,student_id,'MEMBERSHIP_CREATED',
       jsonb_build_object('source','LEGACY_GLOBAL_TO_QUANTAEDGE','migration','V130')
FROM inserted_memberships;

-- Child-creation idempotency is institution-bound too. Requests made by the old global system
-- are legacy QuantaEdge requests; this prevents the same request key from returning a child in
-- another institution after migration.
ALTER TABLE guardian_child_creation_request
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

UPDATE guardian_child_creation_request req
SET institution_id=qe.id
FROM institution_tenant qe
WHERE qe.tenant_code='QUANTA-EDGE' AND req.institution_id IS NULL;

ALTER TABLE guardian_child_creation_request
  ALTER COLUMN institution_id SET NOT NULL;
CREATE INDEX idx_guardian_child_creation_tenant
  ON guardian_child_creation_request(institution_id,guardian_user_id,created_at DESC);

INSERT INTO app_metadata(key,value)
VALUES ('schema','legacy-global-learners-bound-to-quantaedge-v130')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
