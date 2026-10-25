-- V205: preserve V204's active registered type/identity checks and also require
-- every source-owned release for a course to agree with that identity's owning institution.
-- This keeps Marketplace lifecycle gates tenant-bound; inconsistent/legacy cross-tenant graphs fail closed.
CREATE OR REPLACE FUNCTION course_marketplace_content_type_ready(p_program_id bigint)
RETURNS boolean
LANGUAGE sql
STABLE
AS $qe_course_type_marketplace_ready$
  SELECT CASE
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
  END
  FROM curriculum_program p
  JOIN course_type_registry registered_type
    ON registered_type.code=p.course_type_code AND registered_type.status='ACTIVE'
  JOIN institution_course_identity identity
    ON identity.id=p.course_identity_id AND identity.status='ACTIVE'
   AND identity.course_type_code=p.course_type_code
  LEFT JOIN curriculum_program_delivery_mode mode ON mode.program_id=p.id
  WHERE p.id=p_program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
    -- First-release readiness must work before an initial DRAFT release exists.
    -- Any existing release that conflicts with the registered source institution still fails closed.
    AND NOT EXISTS (
      SELECT 1 FROM institution_course_release conflicting_release
      WHERE conflicting_release.program_id=p.id
        AND conflicting_release.source_institution_id<>identity.source_institution_id
    )
$qe_course_type_marketplace_ready$;


COMMENT ON FUNCTION course_marketplace_content_type_ready(bigint) IS
  'Canonical course-type readiness gate: active K12 learning requires published lessons in every active mapped subject; active ASSESSMENT/ASSESSMENT_ONLY requires all test packs and assessments published with valid approved question/answer snapshots. The course and registered type/identity must be active, program/identity types must match, and every release must match the identity source institution. Unsupported, mismatched, inactive or ambiguous graphs fail closed.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','active-course-type-marketplace-readiness-v205')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
