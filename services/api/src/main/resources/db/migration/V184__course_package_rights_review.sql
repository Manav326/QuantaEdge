-- V184: persist HRMS source-rights review attribution and evidence-backed decision notes.
ALTER TABLE institution_course_package_import
  ADD COLUMN rights_reviewed_by_staff_id bigint REFERENCES staff_account(id) ON DELETE SET NULL,
  ADD COLUMN rights_reviewed_at timestamptz,
  ADD COLUMN rights_review_notes varchar(1000);

ALTER TABLE institution_course_package_import
  ADD CONSTRAINT ck_course_package_rights_review_complete
  CHECK (
    (rights_reviewed_at IS NULL AND rights_reviewed_by_staff_id IS NULL AND rights_review_notes IS NULL)
    OR
    (rights_reviewed_at IS NOT NULL AND rights_reviewed_by_staff_id IS NOT NULL
      AND rights_review_notes IS NOT NULL AND length(btrim(rights_review_notes)) >= 5)
  );

CREATE INDEX idx_course_package_rights_review_queue
  ON institution_course_package_import(source_institution_id,rights_reviewed_at,imported_at DESC)
  WHERE state='IMPORTED';

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-package-rights-review-v184')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
