-- V98: courses use a stable, class-independent identity.
-- curriculum_subject.class_id continues to describe the source/curriculum level of its content;
-- it is not a restriction on which learners may enrol in the course.
-- Existing GENERAL/class curriculum rows and class-based routes remain unchanged.

ALTER TABLE curriculum_subject
  DROP CONSTRAINT fk_curriculum_subject_program_class;
ALTER TABLE curriculum_subject
  ADD CONSTRAINT fk_curriculum_subject_program
  FOREIGN KEY (program_id) REFERENCES curriculum_program(id) ON DELETE RESTRICT;

ALTER TABLE curriculum_program_subject
  DROP CONSTRAINT fk_program_subject_program_class;
ALTER TABLE curriculum_program_subject
  ADD CONSTRAINT fk_program_subject_program
  FOREIGN KEY (program_id) REFERENCES curriculum_program(id) ON DELETE CASCADE;

ALTER TABLE textbook_source_book
  DROP CONSTRAINT fk_textbook_source_book_program_class;
ALTER TABLE textbook_source_book
  ADD CONSTRAINT fk_textbook_source_book_program
  FOREIGN KEY (program_id) REFERENCES curriculum_program(id) ON DELETE RESTRICT;

ALTER TABLE curriculum_program
  ALTER COLUMN class_id DROP NOT NULL;

CREATE UNIQUE INDEX uq_curriculum_program_global_code_version
  ON curriculum_program(code, curriculum_version)
  WHERE class_id IS NULL;

-- Keep the existing program row, identity and every enrollment/subscription unchanged.
UPDATE curriculum_program
SET class_id = NULL,
    notes = 'Independent entrance-preparation course. Learner eligibility depends on the course enrollment; subject class_id identifies the source curriculum level only.',
    updated_at = now()
WHERE code = 'NAVODAYA_BATCH' AND curriculum_version = 'NCERT-2026-27';

-- Certificates retain their original legacy fields while recording the course relationship by ID.
ALTER TABLE student_certificate
  ADD COLUMN program_id bigint REFERENCES curriculum_program(id) ON DELETE RESTRICT;
CREATE INDEX idx_student_certificate_program
  ON student_certificate(program_id, student_id, issued_at DESC)
  WHERE program_id IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','class-independent-course-identity-v98')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
