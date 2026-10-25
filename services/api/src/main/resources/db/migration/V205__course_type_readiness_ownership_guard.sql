-- V205: require active course type + source identity and reject cross-owner release history.
-- V204 centralized typed answer review. This migration adds the tenant ownership invariant to the
-- common course-type availability gate so source submission, HRMS quality approval, assignments,
-- Marketplace launch, checkout and learner discovery all fail closed on an inconsistent graph.
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
    AND NOT EXISTS (
      SELECT 1
      FROM institution_course_release conflicting_release
      WHERE conflicting_release.program_id=p.id
        AND conflicting_release.source_institution_id<>identity.source_institution_id
    )
$qe_course_type_marketplace_ready$;

COMMENT ON FUNCTION course_marketplace_content_type_ready(bigint) IS
  'Canonical cross-tenant course readiness: only active registered types and active institution-owned identities matching the course type pass. All existing release rows must belong to the identity source institution. K12 requires published lessons in every active mapped subject; ASSESSMENT/ASSESSMENT_ONLY requires all assessments and packs published with valid approved typed question and answer snapshots. Unsupported or inconsistent graphs fail closed.';

INSERT INTO app_metadata(key,value)
VALUES ('schema','marketplace-course-type-readiness-ownership-v205')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
