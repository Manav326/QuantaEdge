-- V206: establish a stable source-scoped course key independent of version graph and row IDs.
-- The course identity is the logical product. curriculum_program.code is version-graph scoped;
-- release IDs and assignment IDs identify workflow records, not courses.

ALTER TABLE institution_course_identity
  ADD CONSTRAINT ck_institution_course_identity_stable_key_nonblank
  CHECK (btrim(stable_course_key) <> '');

WITH identity_roots AS (
  SELECT i.id AS identity_id,i.source_institution_id,i.stable_course_key,
    CASE
      WHEN left(root.code,length('I'||i.source_institution_id::text||'_'))=
           'I'||i.source_institution_id::text||'_'
        THEN substr(root.code,length('I'||i.source_institution_id::text||'_')+1)
      ELSE root.code
    END AS canonical_key
  FROM institution_course_identity i
  JOIN LATERAL (
    SELECT p.code
    FROM curriculum_program p
    WHERE p.course_identity_id=i.id
    ORDER BY p.content_version_no,p.id
    LIMIT 1
  ) root ON true
), candidates AS (
  SELECT r.*,
    count(*) OVER (PARTITION BY r.source_institution_id,r.canonical_key) AS same_key_count
  FROM identity_roots r
)
UPDATE institution_course_identity i
SET stable_course_key=c.canonical_key,updated_at=now()
FROM candidates c
WHERE i.id=c.identity_id
  AND c.same_key_count=1
  AND c.canonical_key<>i.stable_course_key
  AND (
    i.stable_course_key LIKE 'legacy-program-%'
    OR i.stable_course_key='I'||i.source_institution_id::text||'_'||c.canonical_key
  )
  AND NOT EXISTS (
    SELECT 1
    FROM institution_course_identity other
    WHERE other.source_institution_id=i.source_institution_id
      AND other.stable_course_key=c.canonical_key
      AND other.id<>i.id
  );

COMMENT ON COLUMN institution_course_identity.public_id IS
  'Stable globally unique public identifier for the logical course identity; unchanged across content versions.';
COMMENT ON COLUMN institution_course_identity.stable_course_key IS
  'Stable source-institution-scoped business key for the logical course; never use database row IDs or version-specific curriculum_program.code values as this key.';
COMMENT ON COLUMN curriculum_program.public_id IS
  'Public identifier for this specific immutable content graph/version; use course_identity.public_id to refer to the logical course across versions.';
COMMENT ON COLUMN curriculum_program.code IS
  'Version-graph-scoped internal code. It may change when the logical course is forked and is not the stable logical course key.';
