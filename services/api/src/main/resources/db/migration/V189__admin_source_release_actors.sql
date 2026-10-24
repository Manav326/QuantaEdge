-- V189: allow a properly authorised Education Admin staff actor to perform the
-- source institution's release submission/approval inside Admin. Existing institution-admin
-- actors and release history remain valid; Marketplace publication remains HRMS-only.
ALTER TABLE institution_course_release
  ADD COLUMN submitted_by_staff_id bigint REFERENCES staff_account(id) ON DELETE SET NULL,
  ADD COLUMN origin_approved_by_staff_id bigint REFERENCES staff_account(id) ON DELETE SET NULL;

DO $$
DECLARE constraint_row record;
BEGIN
  FOR constraint_row IN
    SELECT conname
    FROM pg_constraint
    WHERE conrelid='institution_course_release'::regclass
      AND contype='c'
      AND pg_get_constraintdef(oid) ILIKE '%origin_approved_by_admin_id IS NOT NULL%'
  LOOP
    EXECUTE format('ALTER TABLE institution_course_release DROP CONSTRAINT %I', constraint_row.conname);
  END LOOP;
END $$;

ALTER TABLE institution_course_release
  ADD CONSTRAINT institution_course_release_origin_actor_check
  CHECK (
    release_state <> 'SHAREABLE'
    OR (
      (
        (origin_approved_by_admin_id IS NOT NULL AND origin_approved_by_staff_id IS NULL)
        OR (origin_approved_by_admin_id IS NULL AND origin_approved_by_staff_id IS NOT NULL)
      )
      AND origin_approved_at IS NOT NULL
      AND shareable_at IS NOT NULL
    )
  );

CREATE INDEX idx_institution_course_release_submitted_staff
  ON institution_course_release(submitted_by_staff_id)
  WHERE submitted_by_staff_id IS NOT NULL;
CREATE INDEX idx_institution_course_release_origin_approved_staff
  ON institution_course_release(origin_approved_by_staff_id)
  WHERE origin_approved_by_staff_id IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','admin-institution-release-workflow-v189')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
