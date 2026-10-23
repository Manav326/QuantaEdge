-- V186: assessment lifecycle metadata is not course content.
-- Keep assessment definitions, scoring, question snapshots and pack resources immutable once
-- released, while allowing the permissioned lifecycle to publish/archive assessments and update
-- pack status. The fields excluded here are operational metadata only; any content-field change
-- still falls through to the V180 COURSE_VERSION_IMMUTABLE guard.
CREATE OR REPLACE FUNCTION reject_published_course_graph_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_payloads jsonb;
  v_row jsonb;
  v_program_id bigint;
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

INSERT INTO app_metadata(key,value)
VALUES ('schema','assessment-lifecycle-metadata-v186')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
