-- V205: use the registered, active course type and exact delivery-mode pairing
-- for every Marketplace decision. K12 and ASSESSMENT keep separate content contracts;
-- COMPETITIVE remains blocked until its own publisher and readiness contract exist.
CREATE OR REPLACE FUNCTION course_marketplace_content_type_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $qe_marketplace_type_ready$
  SELECT coalesce(
    CASE
      WHEN p.status <> 'ACTIVE' OR p.code='GENERAL' THEN false
      WHEN type_registry.code IS NULL OR type_registry.status <> 'ACTIVE' THEN false
      WHEN p.course_type_code='ASSESSMENT'
        AND coalesce(mode.delivery_mode,'LEARNING_CONTENT')='ASSESSMENT_ONLY'
        THEN coalesce(assessment_only_course_publish_ready(p.id),false)
      WHEN p.course_type_code='K12'
        AND coalesce(mode.delivery_mode,'LEARNING_CONTENT')='LEARNING_CONTENT'
        AND EXISTS (
          SELECT 1
          FROM curriculum_program_subject ps
          JOIN curriculum_subject s ON s.id=ps.subject_id
            AND s.class_id=ps.class_id AND s.active=true
            AND (s.program_id=ps.program_id OR s.program_id IS NULL)
          WHERE ps.program_id=p.id
        )
        AND NOT EXISTS (
          SELECT 1
          FROM curriculum_program_subject ps
          JOIN curriculum_subject s ON s.id=ps.subject_id
            AND s.class_id=ps.class_id AND s.active=true
            AND (s.program_id=ps.program_id OR s.program_id IS NULL)
          WHERE ps.program_id=p.id
            AND NOT EXISTS (
              SELECT 1
              FROM curriculum_chapter ch
              JOIN lesson l ON l.chapter_id=ch.id
              WHERE ch.subject_id=s.id
                AND ch.active=true AND ch.content_status='PUBLISHED'
                AND l.active=true AND l.status='PUBLISHED'
            )
        )
        THEN true
      ELSE false
    END,
    false
  )
  FROM curriculum_program p
  LEFT JOIN course_type_registry type_registry ON type_registry.code=p.course_type_code
  LEFT JOIN curriculum_program_delivery_mode mode ON mode.program_id=p.id
  WHERE p.id=p_program_id
$qe_marketplace_type_ready$;

COMMENT ON FUNCTION course_marketplace_content_type_ready(bigint) IS
  'Canonical Marketplace readiness gate. Requires an active course and active registered type, exact type/delivery pairing, complete published/reviewed K12 content or published/reviewed assessment packs, and fails closed for unsupported types such as COMPETITIVE.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','active-course-type-marketplace-readiness-v205')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
