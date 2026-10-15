-- V100: additive, course-scoped public test IDs with a transactional monotonic sequence.
-- Existing assessment IDs, attempt references, snapshots, results and certificates are unchanged.
-- Legacy assessments intentionally retain NULL public IDs; only newly inserted assessments
-- receive the new externally displayable identifier.

CREATE TABLE assessment_public_id_sequence (
  scope_public_id varchar(8) PRIMARY KEY
    REFERENCES public_identity_registry(public_id) ON DELETE RESTRICT,
  last_sequence bigint NOT NULL CHECK (last_sequence > 0),
  updated_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE assessment
  ADD COLUMN public_id varchar(40),
  ADD COLUMN public_id_scope varchar(8),
  ADD COLUMN public_id_sequence bigint,
  ADD CONSTRAINT fk_assessment_public_id_scope
    FOREIGN KEY(public_id_scope) REFERENCES public_identity_registry(public_id) ON DELETE RESTRICT,
  ADD CONSTRAINT uq_assessment_public_id UNIQUE(public_id),
  ADD CONSTRAINT uq_assessment_scope_sequence UNIQUE(public_id_scope, public_id_sequence),
  ADD CONSTRAINT chk_assessment_public_id_complete CHECK (
    (public_id IS NULL AND public_id_scope IS NULL AND public_id_sequence IS NULL)
    OR
    (public_id IS NOT NULL AND public_id_scope IS NOT NULL AND public_id_sequence > 0
      AND public_id = public_id_scope || '-' || public_id_sequence::text)
  );

CREATE OR REPLACE FUNCTION assign_assessment_public_id()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  chosen_scope varchar(8);
  allocated_sequence bigint;
BEGIN
  IF TG_OP = 'UPDATE' THEN
    IF NEW.public_id IS DISTINCT FROM OLD.public_id
       OR NEW.public_id_scope IS DISTINCT FROM OLD.public_id_scope
       OR NEW.public_id_sequence IS DISTINCT FROM OLD.public_id_sequence THEN
      RAISE EXCEPTION 'Assessment public ID is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
  END IF;

  SELECT CASE
           WHEN s.program_id IS NULL THEN c.public_id
           ELSE p.public_id
         END
    INTO chosen_scope
  FROM curriculum_subject s
  JOIN curriculum_class c ON c.id=s.class_id
  LEFT JOIN curriculum_program p ON p.id=s.program_id
  WHERE s.id=NEW.subject_id;

  IF chosen_scope IS NULL THEN
    RAISE EXCEPTION 'Cannot assign a public test ID without a registered class/course public ID'
      USING ERRCODE = '23514';
  END IF;

  -- The single-row upsert serializes concurrent creations for the same course.
  -- Sequence state survives assessment archive/deletion, so IDs are never recycled.
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

CREATE TRIGGER trg_assessment_public_identity
BEFORE INSERT OR UPDATE ON assessment
FOR EACH ROW EXECUTE FUNCTION assign_assessment_public_id();

CREATE INDEX idx_assessment_public_id_scope
  ON assessment(public_id_scope,public_id_sequence DESC)
  WHERE public_id IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','course-scoped-test-public-ids-v100')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
