-- V122: promote the existing Class 6–8 curriculum into explicit learner courses.
-- Preserve canonical subject/chapter/lesson/question/document IDs and existing learning history.
-- Listing a course is separate from publishing an offer; prices are never inferred or seeded.

INSERT INTO public_identity_prefix_registry(prefix, entity_type, description, active)
VALUES ('CRS', 'PROGRAM', 'Direct learner course', true)
ON CONFLICT (prefix) DO UPDATE
SET description = EXCLUDED.description, active = true
WHERE public_identity_prefix_registry.entity_type = 'PROGRAM';

INSERT INTO curriculum_program(
  class_id, code, display_name, stream_code, curriculum_version, status, notes, public_id_prefix
)
SELECT NULL, 'CLASS_' || c.code, 'Class ' || c.code || ' Course', NULL, 'CURRENT', 'ACTIVE',
       'Direct learner course. Canonical subject and learning record IDs are preserved; access is governed by course enrollment or entitlement.',
       'CRS'
FROM curriculum_class c
WHERE c.code IN ('6','7','8') AND c.active = true
ON CONFLICT (code, curriculum_version) WHERE class_id IS NULL
DO UPDATE SET display_name = EXCLUDED.display_name, status = 'ACTIVE',
  notes = EXCLUDED.notes, updated_at = now();

-- Reassign existing canonical subjects in place; never clone or delete learning records.
-- Existing GENERAL relationship rows are preserved as historical references. Because content
-- scope now follows curriculum_subject.program_id, they do not grant access to this course.
UPDATE curriculum_subject s
SET program_id = p.id
FROM curriculum_class c
JOIN curriculum_program p ON p.class_id IS NULL
  AND p.code = 'CLASS_' || c.code AND p.curriculum_version = 'CURRENT' AND p.status = 'ACTIVE'
WHERE s.class_id = c.id AND s.program_id IS NULL AND c.code IN ('6','7','8');

INSERT INTO curriculum_program_subject(program_id, class_id, subject_id, is_required, sort_order)
SELECT s.program_id, s.class_id, s.id, true, s.sort_order
FROM curriculum_subject s
JOIN curriculum_class c ON c.id = s.class_id AND c.code IN ('6','7','8')
JOIN curriculum_program p ON p.id = s.program_id AND p.code = 'CLASS_' || c.code AND p.class_id IS NULL
WHERE s.active = true
ON CONFLICT (program_id, subject_id) DO UPDATE
SET is_required = EXCLUDED.is_required, sort_order = EXCLUDED.sort_order;

UPDATE textbook_source_book b
SET program_id = s.program_id
FROM curriculum_subject s
JOIN curriculum_class c ON c.id = s.class_id AND c.code IN ('6','7','8')
WHERE b.subject_id = s.id AND b.program_id IS NULL AND s.program_id IS NOT NULL;

-- Preserve existing GENERAL access. A current independent course keeps focus if one exists.
WITH legacy_learners AS (
  SELECT st.id AS student_id, target.id AS course_id, ge.status AS general_status,
    st.active AS student_active,
    EXISTS (
      SELECT 1 FROM student_program_enrollment current_enrolment
      JOIN curriculum_program current_program ON current_program.id = current_enrolment.program_id
      WHERE current_enrolment.student_id = st.id
        AND current_enrolment.status = 'ACTIVE' AND current_program.code <> 'GENERAL'
    ) AS has_other_active_course
  FROM student st
  JOIN curriculum_class c ON c.code = st.class_code AND c.code IN ('6','7','8')
  JOIN curriculum_program general ON general.class_id = c.id AND general.code = 'GENERAL'
  JOIN student_program_enrollment ge ON ge.student_id = st.id AND ge.program_id = general.id
    AND ge.status IN ('ACTIVE','PAUSED')
  JOIN curriculum_program target ON target.class_id IS NULL
    AND target.code = 'CLASS_' || c.code AND target.curriculum_version = 'CURRENT' AND target.status = 'ACTIVE'
)
INSERT INTO student_program_enrollment(student_id, program_id, status, enrolled_at, ended_at, created_at, updated_at)
SELECT student_id, course_id,
  CASE WHEN student_active AND general_status = 'ACTIVE' AND NOT has_other_active_course THEN 'ACTIVE' ELSE 'PAUSED' END,
  now(), NULL, now(), now()
FROM legacy_learners
ON CONFLICT (student_id, program_id) DO NOTHING;

-- Restore only missing active track links; existing statuses and history are left alone.
INSERT INTO student_track_enrollment(student_id, subject_id, status)
SELECT spe.student_id, s.id, 'ACTIVE'
FROM student_program_enrollment spe
JOIN curriculum_program p ON p.id = spe.program_id AND p.code IN ('CLASS_6','CLASS_7','CLASS_8')
JOIN curriculum_program_subject ps ON ps.program_id = p.id
JOIN curriculum_subject s ON s.id = ps.subject_id AND s.program_id = p.id AND s.active = true
WHERE spe.status = 'ACTIVE'
ON CONFLICT (student_id, subject_id) DO NOTHING;

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'class-6-8-course-catalogue-and-content-scope-v122')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
