-- V198: subjectless assessment rows are valid only for explicitly assessment-only courses.
-- Keep course ID allocation and course/subject boundary enforcement strict for every other course.
CREATE OR REPLACE FUNCTION assign_assessment_public_id()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  chosen_scope varchar(8);
  allocated_sequence bigint;
  subject_class_id bigint;
  selected_course_type varchar(40);
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.course_id IS DISTINCT FROM OLD.course_id THEN
      RAISE EXCEPTION 'Assessment course assignment is immutable' USING ERRCODE = '23514';
    END IF;
    IF NEW.public_id IS DISTINCT FROM OLD.public_id
       OR NEW.public_id_scope IS DISTINCT FROM OLD.public_id_scope
       OR NEW.public_id_sequence IS DISTINCT FROM OLD.public_id_sequence THEN
      RAISE EXCEPTION 'Assessment public ID is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;

  IF NEW.course_id IS NOT NULL THEN
    SELECT p.public_id,p.course_type_code
      INTO chosen_scope,selected_course_type
    FROM curriculum_program p
    WHERE p.id=NEW.course_id AND p.status='ACTIVE' AND p.code<>'GENERAL';

    IF chosen_scope IS NULL THEN
      RAISE EXCEPTION 'Cannot assign a test ID without an active registered course public ID'
        USING ERRCODE = '23514';
    END IF;

    IF NEW.subject_id IS NULL THEN
      IF selected_course_type IS NULL OR selected_course_type NOT IN ('ASSESSMENT','COMPETITIVE') THEN
        RAISE EXCEPTION 'A subjectless assessment requires an assessment-only or competitive course'
          USING ERRCODE = '23514';
      END IF;
    ELSE
      SELECT s.class_id INTO subject_class_id
      FROM curriculum_subject s WHERE s.id=NEW.subject_id;

      IF NOT EXISTS (
        SELECT 1
        FROM curriculum_program p
        JOIN curriculum_program_subject cps ON cps.program_id=p.id
          AND cps.subject_id=NEW.subject_id AND cps.class_id=subject_class_id
        JOIN curriculum_subject s ON s.id=cps.subject_id AND s.class_id=cps.class_id
        WHERE p.id=NEW.course_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
          AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
      ) THEN
        RAISE EXCEPTION 'Assessment subject is not mapped to the selected course'
          USING ERRCODE = '23514';
      END IF;
    END IF;
  ELSE
    IF NEW.subject_id IS NULL THEN
      RAISE EXCEPTION 'Legacy tests require a registered subject identity'
        USING ERRCODE = '23514';
    END IF;

    SELECT s.class_id INTO subject_class_id
    FROM curriculum_subject s WHERE s.id=NEW.subject_id;
    SELECT CASE WHEN s.program_id IS NULL THEN c.public_id ELSE p.public_id END
      INTO chosen_scope
    FROM curriculum_subject s
    JOIN curriculum_class c ON c.id=s.class_id
    LEFT JOIN curriculum_program p ON p.id=s.program_id
    WHERE s.id=NEW.subject_id;

    IF chosen_scope IS NULL THEN
      RAISE EXCEPTION 'Cannot assign a legacy test ID without a registered curriculum identity'
        USING ERRCODE = '23514';
    END IF;
  END IF;

  INSERT INTO assessment_public_id_sequence(scope_public_id,last_sequence,updated_at)
  VALUES (chosen_scope,1,now())
  ON CONFLICT(scope_public_id) DO UPDATE
    SET last_sequence=assessment_public_id_sequence.last_sequence+1,
        updated_at=now()
  RETURNING last_sequence INTO allocated_sequence;

  NEW.public_id_scope := chosen_scope;
  NEW.public_id_sequence := allocated_sequence;
  NEW.public_id := chosen_scope || '-' || allocated_sequence::text;
  RETURN NEW;
END;
$$;

INSERT INTO app_metadata(key,value)
VALUES ('schema','subjectless-assessment-only-course-tests-v198')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
