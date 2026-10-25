-- V200: keep Marketplace publish-readiness generic for every ASSESSMENT course.
-- TRE's exact three-paper inventory is enforced only by tre4_source_package_ready during the
-- TRE import/source-submission workflow. A different assessment course has its own pack structure.
-- A paid assessment-only course can be listed only when every pack and every assessment is
-- published and the generic question/answer review gate passes.
CREATE OR REPLACE FUNCTION assessment_only_course_publish_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT coalesce(assessment_only_course_ready(p_program_id),false)
    AND EXISTS (
      SELECT 1
      FROM course_assessment_pack pack
      WHERE pack.course_id=p_program_id
        AND pack.status='PUBLISHED'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM course_assessment_pack pack
      WHERE pack.course_id=p_program_id
        AND pack.status<>'PUBLISHED'
    )
    AND EXISTS (
      SELECT 1
      FROM assessment a
      WHERE a.course_id=p_program_id
        AND a.status='PUBLISHED'
    )
    AND NOT EXISTS (
      SELECT 1
      FROM assessment a
      WHERE a.course_id=p_program_id
        AND a.status<>'PUBLISHED'
    );
$$;

INSERT INTO app_metadata(key,value)
VALUES ('schema','generic-assessment-marketplace-publish-readiness-v200')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
