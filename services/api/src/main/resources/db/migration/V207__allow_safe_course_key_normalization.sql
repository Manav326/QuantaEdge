-- V207: normalization of an interim/legacy course key is identity metadata, not a content edit.
-- All graph content remains protected. Only allow the exact, unambiguous key normalization
-- performed by V206: legacy-program-<id> or I<source-institution-id>_<canonical-key>
-- to the key derived from the oldest graph's code. Ownership, course type, name, status,
-- public ID and every other identity field must remain unchanged.

CREATE OR REPLACE FUNCTION reject_published_course_graph_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_payloads jsonb;
  v_row jsonb;
  v_program_id bigint;
  v_canonical_key text;
  v_prefix text;
BEGIN
  IF TG_OP='UPDATE' AND TG_TABLE_NAME='assessment'
     AND (to_jsonb(OLD) - ARRAY['status','is_current','published_by_staff_id','published_at','updated_at'])
       = (to_jsonb(NEW) - ARRAY['status','is_current','published_by_staff_id','published_at','updated_at']) THEN
    RETURN NEW;
  END IF;

  IF TG_OP='UPDATE' AND TG_TABLE_NAME='course_assessment_pack'
     AND (to_jsonb(OLD) - ARRAY['status','published_by_staff_id','published_at','updated_at'])
       = (to_jsonb(NEW) - ARRAY['status','published_by_staff_id','published_at','updated_at']) THEN
    RETURN NEW;
  END IF;

  IF TG_OP='UPDATE' AND TG_TABLE_NAME='institution_course_identity'
     AND OLD.stable_course_key IS DISTINCT FROM NEW.stable_course_key
     AND (to_jsonb(OLD) - ARRAY['stable_course_key','updated_at'])
       = (to_jsonb(NEW) - ARRAY['stable_course_key','updated_at']) THEN
    v_prefix := 'I'||OLD.source_institution_id::text||'_';

    SELECT CASE
      WHEN left(root.code,length(v_prefix))=v_prefix
        THEN substr(root.code,length(v_prefix)+1)
      ELSE root.code
    END
    INTO v_canonical_key
    FROM curriculum_program root
    WHERE root.course_identity_id=OLD.id
    ORDER BY root.content_version_no,root.id
    LIMIT 1;

    IF v_canonical_key IS NOT NULL
       AND NEW.stable_course_key=v_canonical_key
       AND (
         OLD.stable_course_key LIKE 'legacy-program-%'
         OR OLD.stable_course_key=v_prefix||v_canonical_key
       )
       AND NOT EXISTS (
         SELECT 1
         FROM institution_course_identity other
         WHERE other.source_institution_id=OLD.source_institution_id
           AND other.stable_course_key=NEW.stable_course_key
           AND other.id<>OLD.id
       ) THEN
      RETURN NEW;
    END IF;
  END IF;

  IF TG_OP='UPDATE' THEN
    v_payloads := jsonb_build_array(to_jsonb(OLD),to_jsonb(NEW));
  ELSIF TG_OP='DELETE' THEN
    v_payloads := jsonb_build_array(to_jsonb(OLD));
  ELSE
    v_payloads := jsonb_build_array(to_jsonb(NEW));
  END IF;

  FOR v_row IN SELECT value FROM jsonb_array_elements(v_payloads) AS rows(value)
  LOOP
    FOR v_program_id IN
      SELECT DISTINCT ids.program_id
      FROM qe_course_program_ids_for_row(TG_TABLE_NAME,v_row) ids
      WHERE ids.program_id IS NOT NULL
    LOOP
      IF EXISTS (
        SELECT 1 FROM institution_course_release r
        WHERE r.program_id=v_program_id
          AND r.release_state IN ('IN_QUALITY_REVIEW','QUALITY_APPROVED','SHAREABLE','LEGACY_MIGRATED','WITHDRAWN')
      ) OR EXISTS (
        SELECT 1 FROM institution_marketplace_offer o
        WHERE o.course_id=v_program_id
          AND (o.status='PUBLISHED' OR o.hrms_published_by_staff_id IS NOT NULL)
      ) THEN
        RAISE EXCEPTION 'COURSE_VERSION_IMMUTABLE: content in course version % has entered the release/publication workflow. Create a new draft version; the existing graph cannot be edited in place.',v_program_id
          USING ERRCODE='23514';
      END IF;
    END LOOP;
  END LOOP;

  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END
$$;


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

INSERT INTO app_metadata(key,value)
VALUES ('schema','safe-canonical-course-key-normalization-v207')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
