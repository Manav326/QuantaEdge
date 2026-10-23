-- V177: support assessments that are first-class course assets, not forced to belong to a school subject.
-- Nullable subject/chapter targets are only for courses whose type schema explicitly permits
-- course -> test pack -> assessment. Existing subject-based tests and history retain their IDs.
ALTER TABLE assessment
  ALTER COLUMN subject_id DROP NOT NULL;

COMMENT ON COLUMN assessment.subject_id IS
  'Optional for type-driven assessment/competitive courses whose schema does not require a curriculum subject. K12 tests normally retain a course-mapped subject.';

-- Explicit test-pack import uses the content digest and course/code/version as an idempotency key.
-- A duplicate is only a no-op when the canonical payload hash matches exactly.
CREATE UNIQUE INDEX IF NOT EXISTS uq_course_assessment_pack_identity
  ON course_assessment_pack(course_id,pack_code,pack_version);

-- Ensure the schema/DB contract keeps unanswered scoring data at question-snapshot level so
-- existing immutable attempt-question snapshots remain self-contained.
COMMENT ON COLUMN assessment_question.question_snapshot IS
  'Immutable question content/answer snapshot, including section policy, scoring settings, localization, and import provenance.';
INSERT INTO app_metadata(key,value)
VALUES ('schema','standalone-assessment-support-v177')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
