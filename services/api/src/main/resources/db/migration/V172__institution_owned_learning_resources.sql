-- V172: give new generic learning resources an explicit institution owner.
-- Legacy resources remain null/read-only until a safe source owner can be proved from existing
-- course assignments. Do not guess ownership from the current viewer or creator staff ID.
ALTER TABLE learning_resource
  ADD COLUMN owner_institution_id bigint REFERENCES institution_tenant(id) ON DELETE RESTRICT;

-- Backfill only when every current non-archived curriculum attachment resolves to exactly one
-- source institution. Ambiguous and unattached legacy records intentionally remain unowned.
WITH attachment_sources AS (
  SELECT a.resource_id, r.source_institution_id,
         count(DISTINCT r.source_institution_id) OVER (PARTITION BY a.resource_id) AS source_count
  FROM learning_resource_assignment a
  JOIN curriculum_chapter ch ON ch.id=a.chapter_id
  JOIN curriculum_subject s ON s.id=ch.subject_id
  JOIN curriculum_program_subject ps ON ps.subject_id=s.id AND ps.class_id=s.class_id
  JOIN institution_course_release r ON r.program_id=ps.program_id
  WHERE a.status<>'ARCHIVED'
), unambiguous AS (
  SELECT resource_id,min(source_institution_id) AS owner_institution_id
  FROM attachment_sources
  GROUP BY resource_id
  HAVING max(source_count)=1
)
UPDATE learning_resource r
SET owner_institution_id=u.owner_institution_id
FROM unambiguous u
WHERE r.id=u.resource_id AND r.owner_institution_id IS NULL;

-- Existing recorded-class resources have a direct tenant-owned source; use it only when present.
UPDATE learning_resource r
SET owner_institution_id=rc.institution_id
FROM recorded_class rc
WHERE r.recorded_class_id=rc.id
  AND rc.institution_id IS NOT NULL
  AND r.owner_institution_id IS NULL;

CREATE INDEX idx_learning_resource_owner_status
  ON learning_resource(owner_institution_id,status,resource_type,created_at DESC);

COMMENT ON COLUMN learning_resource.owner_institution_id IS
  'Institution that owns metadata and future revisions of this resource. Null indicates legacy/ambiguous ownership and is read-only in institution-admin authoring until HRMS resolves it.';
INSERT INTO app_metadata(key,value)
VALUES ('schema','institution-owned-learning-resources-v172')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
