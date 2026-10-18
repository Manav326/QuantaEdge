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
  -- Include every existing learner whose legacy class_code previously exposed this content,
  -- whether or not the learner happened to have a GENERAL enrollment row.
  SELECT st.id AS student_id, target.id AS course_id,
    COALESCE(ge.status, 'ACTIVE') AS general_status, st.active AS student_active,
    EXISTS (
      SELECT 1 FROM student_program_enrollment current_enrolment
      JOIN curriculum_program current_program ON current_program.id = current_enrolment.program_id
      WHERE current_enrolment.student_id = st.id
        AND current_enrolment.program_id <> target.id
        AND current_enrolment.status = 'ACTIVE'
        AND current_program.code <> 'GENERAL'
    ) AS has_other_active_course
  FROM student st
  JOIN curriculum_class c ON c.code = st.class_code AND c.code IN ('6','7','8')
  LEFT JOIN curriculum_program general ON general.class_id = c.id
    AND general.code = 'GENERAL' AND general.curriculum_version = 'CURRENT'
  LEFT JOIN student_program_enrollment ge ON ge.student_id = st.id AND ge.program_id = general.id
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

-- Active course enrollment must have active subject tracks, as in the normal
-- course activation API. This restores old track states only for learners whose matching
-- course is actively selected; paused learners remain untouched.
INSERT INTO student_track_enrollment(student_id, subject_id, status)
SELECT spe.student_id, s.id, 'ACTIVE'
FROM student_program_enrollment spe
JOIN curriculum_program p ON p.id = spe.program_id AND p.code IN ('CLASS_6','CLASS_7','CLASS_8')
JOIN curriculum_program_subject ps ON ps.program_id = p.id
JOIN curriculum_subject s ON s.id = ps.subject_id AND s.program_id = p.id AND s.active = true
WHERE spe.status = 'ACTIVE'
ON CONFLICT (student_id, subject_id) DO UPDATE
SET status = 'ACTIVE', updated_at = now()
WHERE student_track_enrollment.status <> 'ACTIVE';

-- Keep the former class_code-based access contract for learners created after V122:
-- new accounts and class promotions receive the matching course automatically. If another
-- course is currently active it retains focus and the class course starts paused.
CREATE OR REPLACE FUNCTION ensure_default_class_course_enrollment()
RETURNS trigger LANGUAGE plpgsql AS $
DECLARE
  target_course_id bigint;
  previous_class_course_id bigint;
  has_other_active_course boolean;
  desired_status varchar(20);
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF OLD.class_code IS NOT DISTINCT FROM NEW.class_code
       AND OLD.active IS NOT DISTINCT FROM NEW.active THEN
      RETURN NEW;
    END IF;

    IF OLD.class_code IS DISTINCT FROM NEW.class_code
       AND OLD.class_code IN ('6','7','8') THEN
      SELECT p.id INTO previous_class_course_id
      FROM curriculum_program p
      WHERE p.class_id IS NULL AND p.code = 'CLASS_' || OLD.class_code
        AND p.curriculum_version = 'CURRENT';
      IF previous_class_course_id IS NOT NULL THEN
        UPDATE student_program_enrollment
        SET status='PAUSED', updated_at=now()
        WHERE student_id=NEW.id AND program_id=previous_class_course_id AND status='ACTIVE';
      END IF;
    END IF;
  END IF;

  IF NEW.active IS DISTINCT FROM TRUE OR NEW.class_code NOT IN ('6','7','8') THEN
    RETURN NEW;
  END IF;

  SELECT p.id INTO target_course_id
  FROM curriculum_program p
  WHERE p.class_id IS NULL AND p.code = 'CLASS_' || NEW.class_code
    AND p.curriculum_version = 'CURRENT' AND p.status = 'ACTIVE';
  IF target_course_id IS NULL THEN
    RETURN NEW;
  END IF;

  -- GENERAL is historical compatibility state, not the learner's current course.
  UPDATE student_program_enrollment spe
  SET status='PAUSED', updated_at=now()
  FROM curriculum_program p
  WHERE spe.student_id=NEW.id AND spe.program_id=p.id
    AND spe.status='ACTIVE' AND p.code='GENERAL';

  SELECT EXISTS (
    SELECT 1
    FROM student_program_enrollment e
    JOIN curriculum_program p ON p.id=e.program_id AND p.status='ACTIVE'
    WHERE e.student_id=NEW.id AND e.status='ACTIVE'
      AND e.program_id<>target_course_id AND p.code<>'GENERAL'
  ) INTO has_other_active_course;

  desired_status := CASE WHEN has_other_active_course THEN 'PAUSED' ELSE 'ACTIVE' END;

  INSERT INTO student_program_enrollment(
    student_id,program_id,status,enrolled_at,ended_at,created_at,updated_at
  ) VALUES (NEW.id,target_course_id,desired_status,now(),NULL,now(),now())
  ON CONFLICT (student_id,program_id) DO UPDATE SET
    status=EXCLUDED.status,
    enrolled_at=CASE
      WHEN student_program_enrollment.status IN ('ACTIVE','PAUSED')
        THEN student_program_enrollment.enrolled_at
      ELSE now()
    END,
    ended_at=NULL,updated_at=now();

  IF desired_status='ACTIVE' THEN
    INSERT INTO student_track_enrollment(student_id,subject_id,status)
    SELECT NEW.id,s.id,'ACTIVE'
    FROM curriculum_program_subject ps
    JOIN curriculum_subject s ON s.id=ps.subject_id
      AND s.program_id=ps.program_id AND s.class_id=ps.class_id AND s.active=true
    WHERE ps.program_id=target_course_id
    ON CONFLICT (student_id,subject_id) DO UPDATE
    SET status='ACTIVE',updated_at=now()
    WHERE student_track_enrollment.status<>'ACTIVE';
  END IF;

  RETURN NEW;
END;
$;

DROP TRIGGER IF EXISTS trg_default_class_course_enrollment ON student;
CREATE TRIGGER trg_default_class_course_enrollment
AFTER INSERT OR UPDATE ON student
FOR EACH ROW EXECUTE FUNCTION ensure_default_class_course_enrollment();

INSERT INTO app_metadata(key, value)
VALUES ('schema', 'class-6-8-course-catalogue-and-content-scope-v122')
ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value;
