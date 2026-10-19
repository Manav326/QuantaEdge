-- V136: persist the course context for every assessment and allocate new public test IDs
-- from that course's registered public identity. Legacy public IDs remain immutable.
ALTER TABLE assessment
  ADD COLUMN IF NOT EXISTS course_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;

-- Preserve explicit program-owned subject tracks first.
UPDATE assessment a
SET course_id=s.program_id
FROM curriculum_subject s
JOIN curriculum_program p ON p.id=s.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
WHERE a.subject_id=s.id AND a.course_id IS NULL;

-- For class-shared legacy subjects, attach historical assessments to the matching first-class
-- CLASS_N product where available. If no such explicit mapping exists, leave the record unscoped
-- rather than making it visible in every course that happens to share the same subject.
UPDATE assessment a
SET course_id=selected.course_id
FROM curriculum_subject s
JOIN curriculum_class c ON c.id=s.class_id
CROSS JOIN LATERAL (
  SELECT p.id AS course_id
  FROM curriculum_program_subject cps
  JOIN curriculum_program p ON p.id=cps.program_id AND p.status='ACTIVE' AND p.code<>'GENERAL'
  WHERE cps.subject_id=s.id AND cps.class_id=s.class_id
    AND (s.program_id=p.id OR (s.program_id IS NULL AND p.class_id=s.class_id))
  ORDER BY CASE WHEN p.code='CLASS_' || c.code THEN 0 ELSE 1 END,p.id
  LIMIT 1
) selected
WHERE a.subject_id=s.id AND a.course_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_assessment_course_status_created
  ON assessment(course_id,status,created_at DESC,id DESC);

-- Replace the V100 identity allocator: a newly course-bound test uses the course public ID.
-- The null-course branch is retained solely for legacy maintenance/test fixtures; application
-- authoring and generated-test code must set course_id. Existing IDs are never rewritten.
CREATE OR REPLACE FUNCTION assign_assessment_public_id()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  chosen_scope varchar(8);
  allocated_sequence bigint;
  subject_class_id bigint;
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

  SELECT s.class_id INTO subject_class_id
  FROM curriculum_subject s WHERE s.id=NEW.subject_id;

  IF NEW.course_id IS NOT NULL THEN
    SELECT p.public_id INTO chosen_scope
    FROM curriculum_program p
    WHERE p.id=NEW.course_id AND p.status='ACTIVE' AND p.code<>'GENERAL';

    IF chosen_scope IS NULL THEN
      RAISE EXCEPTION 'Cannot assign a test ID without an active registered course public ID'
        USING ERRCODE = '23514';
    END IF;

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
  ELSE
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
VALUES ('schema','course-bound-assessments-v136')
ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value;
