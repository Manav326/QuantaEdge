-- V206: establish a stable source-scoped course key independent of version graph and row IDs.
-- The course identity is the logical product. curriculum_program.code is version-graph scoped;
-- release IDs and assignment IDs identify workflow records, not courses.

ALTER TABLE institution_course_identity
  ADD CONSTRAINT ck_institution_course_identity_stable_key_nonblank
  CHECK (btrim(stable_course_key) <> '');

COMMENT ON COLUMN institution_course_identity.public_id IS
  'Stable globally unique public identifier for the logical course identity; unchanged across content versions.';
COMMENT ON COLUMN institution_course_identity.stable_course_key IS
  'Stable source-institution-scoped business key for the logical course; never use database row IDs or version-specific curriculum_program.code values as this key.';
COMMENT ON COLUMN curriculum_program.public_id IS
  'Public identifier for this specific immutable content graph/version; use course_identity.public_id to refer to the logical course across versions.';
COMMENT ON COLUMN curriculum_program.code IS
  'Version-graph-scoped internal code. It may change when the logical course is forked and is not the stable logical course key.';
