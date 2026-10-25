-- V202: one course-type availability gate shared by the learner Marketplace and checkout.
-- K12 retains its curriculum publication path. ASSESSMENT requires its generic test-pack readiness
-- contract; TRE-specific inventory stays in tre4_source_package_ready. COMPETITIVE is explicitly
-- not launchable until its registered section/topic/question-bank hierarchy has a runtime importer
-- and matching Marketplace readiness contract, rather than accidentally inheriting K12 checks.
CREATE OR REPLACE FUNCTION course_marketplace_content_type_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT CASE
    WHEN p.course_type_code='ASSESSMENT'
      AND coalesce(mode.delivery_mode,'LEARNING_CONTENT')='ASSESSMENT_ONLY'
      THEN coalesce(assessment_only_course_publish_ready(p.id),false)
    WHEN p.course_type_code='K12'
      AND coalesce(mode.delivery_mode,'LEARNING_CONTENT')<>'ASSESSMENT_ONLY'
      THEN true
    ELSE false
  END
  FROM curriculum_program p
  LEFT JOIN curriculum_program_delivery_mode mode ON mode.program_id=p.id
  WHERE p.id=p_program_id
$$;

COMMENT ON FUNCTION course_marketplace_content_type_ready(bigint) IS
  'Type-safe Marketplace readiness: K12 uses its approved curriculum release; ASSESSMENT uses published/reviewed test packs; unsupported or mismatched types fail closed.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','marketplace-course-type-readiness-v202')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
