-- V129: make auxiliary learner responses/progress tenant-specific.
-- All auxiliary learner history from the pre-institution global system belongs to the legacy
-- QuantaEdge institution. This is a one-time explicit legacy-source mapping, not inference from
-- current memberships. The migration refuses to continue if the destination tenant is missing.

ALTER TABLE student_structured_question_response
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_structured_activity_response
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_learning_resource_progress
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;
ALTER TABLE student_learning_document_progress
  ADD COLUMN institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

DO $require_qe_tenant$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM institution_tenant WHERE tenant_code='QUANTA-EDGE' AND status='ACTIVE') THEN
    RAISE EXCEPTION 'V129 requires the active QUANTA-EDGE legacy destination tenant.';
  END IF;
END
$require_qe_tenant$;

-- Move every row in these formerly global progress tables to QuantaEdge exactly once.
-- Rows already scoped by a prior tenant are preserved.
UPDATE student_structured_question_response h SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND h.institution_id IS NULL;
UPDATE student_structured_activity_response h SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND h.institution_id IS NULL;
UPDATE student_learning_resource_progress h SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND h.institution_id IS NULL;
UPDATE student_learning_document_progress h SET institution_id=t.id
FROM institution_tenant t WHERE t.tenant_code='QUANTA-EDGE' AND h.institution_id IS NULL;

ALTER TABLE student_structured_question_response ALTER COLUMN institution_id SET NOT NULL;
ALTER TABLE student_structured_activity_response ALTER COLUMN institution_id SET NOT NULL;
ALTER TABLE student_learning_resource_progress ALTER COLUMN institution_id SET NOT NULL;
ALTER TABLE student_learning_document_progress ALTER COLUMN institution_id SET NOT NULL;
ALTER TABLE student_recorded_class_progress ALTER COLUMN institution_id SET NOT NULL;

-- Stable IDs remain the row identity. These historical keys were global to a student and must
-- become institution-specific so the same person can make independent progress in two tenants.
ALTER TABLE student_structured_question_response
  DROP CONSTRAINT IF EXISTS uq_student_structured_question_response;
ALTER TABLE student_structured_activity_response
  DROP CONSTRAINT IF EXISTS uq_student_structured_activity_response;
ALTER TABLE student_learning_resource_progress
  DROP CONSTRAINT IF EXISTS student_learning_resource_progress_pkey;
ALTER TABLE student_learning_document_progress
  DROP CONSTRAINT IF EXISTS student_learning_document_progress_pkey;
-- V128 added the tenant unique index but left V43's old primary key in place. Remove it or a
-- student still cannot keep independent playback state for the same recording in two tenants.
ALTER TABLE student_recorded_class_progress
  DROP CONSTRAINT IF EXISTS student_recorded_class_progress_pkey;

-- Restore primary-key guarantees while allowing independent history per tenant.
ALTER TABLE student_learning_resource_progress
  ADD CONSTRAINT student_learning_resource_progress_tenant_pkey
  PRIMARY KEY(institution_id,student_id,resource_id);
ALTER TABLE student_learning_document_progress
  ADD CONSTRAINT student_learning_document_progress_tenant_pkey
  PRIMARY KEY(institution_id,student_id,learning_document_id);
ALTER TABLE student_recorded_class_progress
  ADD CONSTRAINT student_recorded_class_progress_tenant_pkey
  PRIMARY KEY(institution_id,student_id,recorded_class_id);

CREATE UNIQUE INDEX uq_structured_question_response_tenant
  ON student_structured_question_response(institution_id,student_id,artifact_id,artifact_sha256,question_id);
CREATE UNIQUE INDEX uq_structured_activity_response_tenant
  ON student_structured_activity_response(institution_id,student_id,artifact_id,artifact_sha256,block_id);
CREATE UNIQUE INDEX uq_learning_resource_progress_tenant
  ON student_learning_resource_progress(institution_id,student_id,resource_id);
CREATE UNIQUE INDEX uq_learning_document_progress_tenant
  ON student_learning_document_progress(institution_id,student_id,learning_document_id);

CREATE INDEX idx_structured_question_response_tenant_latest
  ON student_structured_question_response(institution_id,student_id,artifact_id,last_submitted_at DESC);
CREATE INDEX idx_structured_activity_response_tenant_latest
  ON student_structured_activity_response(institution_id,student_id,artifact_id,last_saved_at DESC);
CREATE INDEX idx_learning_resource_progress_tenant_updated
  ON student_learning_resource_progress(institution_id,student_id,updated_at DESC);
CREATE INDEX idx_learning_document_progress_tenant_updated
  ON student_learning_document_progress(institution_id,student_id,updated_at DESC);

-- Fail closed if an old/unscoped writer tries to create tenant-owned history. NOT NULL constraints
-- provide the database guarantee; triggers make failures diagnostic and apply consistently on insert.
CREATE OR REPLACE FUNCTION qe_require_tenant_for_new_learner_history()
RETURNS trigger
LANGUAGE plpgsql
AS $tenant_guard$
BEGIN
  IF NEW.institution_id IS NULL THEN
    RAISE EXCEPTION 'TENANT_SCOPE_REQUIRED: new learner history must include institution_id'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END
$tenant_guard$;

CREATE TRIGGER trg_structured_question_response_tenant
  BEFORE INSERT ON student_structured_question_response
  FOR EACH ROW EXECUTE FUNCTION qe_require_tenant_for_new_learner_history();
CREATE TRIGGER trg_structured_activity_response_tenant
  BEFORE INSERT ON student_structured_activity_response
  FOR EACH ROW EXECUTE FUNCTION qe_require_tenant_for_new_learner_history();
CREATE TRIGGER trg_learning_resource_progress_tenant
  BEFORE INSERT ON student_learning_resource_progress
  FOR EACH ROW EXECUTE FUNCTION qe_require_tenant_for_new_learner_history();
CREATE TRIGGER trg_learning_document_progress_tenant
  BEFORE INSERT ON student_learning_document_progress
  FOR EACH ROW EXECUTE FUNCTION qe_require_tenant_for_new_learner_history();
CREATE TRIGGER trg_recorded_class_progress_tenant
  BEFORE INSERT ON student_recorded_class_progress
  FOR EACH ROW EXECUTE FUNCTION qe_require_tenant_for_new_learner_history();

INSERT INTO app_metadata(key,value)
VALUES ('schema','tenant-scoped-auxiliary-learner-history-v129')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
