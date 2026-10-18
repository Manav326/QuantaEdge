-- V123: preserve existing same-class access as Class 6/7/8 course ownership.
-- Only learners who already had an ACTIVE/PAUSED GENERAL enrollment receive the matching
-- course product. Never enroll a learner into a different grade, create a price/offer, or
-- duplicate curriculum/progress records.
INSERT INTO student_program_enrollment (
  student_id, program_id, status, enrolled_at, ended_at, created_at, updated_at
)
SELECT
  st.id,
  course.id,
  CASE
    WHEN st.active = true AND legacy.status = 'ACTIVE' THEN 'ACTIVE'
    ELSE 'PAUSED'
  END,
  legacy.enrolled_at,
  NULL,
  legacy.created_at,
  now()
FROM student st
JOIN curriculum_class c
  ON c.code = st.class_code
 AND c.active = true
 AND c.code IN ('6','7','8')
JOIN curriculum_program legacy_program
  ON legacy_program.class_id = c.id
 AND legacy_program.code = 'GENERAL'
 AND legacy_program.curriculum_version = 'CURRENT'
 AND legacy_program.status = 'ACTIVE'
JOIN student_program_enrollment legacy
  ON legacy.student_id = st.id
 AND legacy.program_id = legacy_program.id
 AND legacy.status IN ('ACTIVE','PAUSED')
JOIN curriculum_program course
  ON course.class_id = c.id
 AND course.code = 'CLASS_' || c.code
 AND course.curriculum_version = 'CURRENT'
 AND course.status = 'ACTIVE'
WHERE st.active = true
ON CONFLICT (student_id, program_id) DO NOTHING;

-- A matching active class-course enrollment becomes the active learning identity. Keep the
-- GENERAL record for backward compatibility/history, but pause it so it is not a second active
-- course. Do not change a course enrollment that already existed before this migration.
UPDATE student_program_enrollment legacy
SET status = 'PAUSED',
    updated_at = now()
FROM curriculum_program legacy_program
JOIN curriculum_class c ON c.id = legacy_program.class_id
JOIN curriculum_program course
  ON course.class_id = c.id
 AND course.code = 'CLASS_' || c.code
 AND course.curriculum_version = 'CURRENT'
 AND course.status = 'ACTIVE'
WHERE legacy.program_id = legacy_program.id
  AND legacy_program.code = 'GENERAL'
  AND legacy_program.curriculum_version = 'CURRENT'
  AND legacy.status = 'ACTIVE'
  AND c.code IN ('6','7','8')
  AND EXISTS (
    SELECT 1
    FROM student_program_enrollment current_course
    JOIN student st ON st.id = current_course.student_id AND st.active = true
    WHERE current_course.student_id = legacy.student_id
      AND current_course.program_id = course.id
      AND current_course.status = 'ACTIVE'
  );

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'reconciled-legacy-class-course-access-v123')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
