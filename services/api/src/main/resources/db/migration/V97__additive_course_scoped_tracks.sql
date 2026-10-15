-- V97: additive course-scoped tracks and textbook-source records.
-- Legacy rows remain program_id NULL and keep their existing student-facing behavior.
-- New programs own separate Mathematics/Science subject rows, chapters, lessons and questions.

ALTER TABLE curriculum_subject
  ADD COLUMN program_id bigint;

ALTER TABLE curriculum_subject
  ADD CONSTRAINT fk_curriculum_subject_program_class
  FOREIGN KEY (program_id, class_id)
  REFERENCES curriculum_program(id, class_id) ON DELETE RESTRICT;

-- Legacy subject codes remain unique within a class. Course-scoped tracks can reuse
-- the same public subject code without reusing legacy chapter/lesson/question records.
ALTER TABLE curriculum_subject
  DROP CONSTRAINT IF EXISTS curriculum_subject_class_id_code_key;

CREATE UNIQUE INDEX uq_curriculum_subject_legacy_class_code
  ON curriculum_subject(class_id, code)
  WHERE program_id IS NULL;

CREATE UNIQUE INDEX uq_curriculum_subject_program_code
  ON curriculum_subject(program_id, code)
  WHERE program_id IS NOT NULL;

CREATE INDEX idx_curriculum_subject_program_scope
  ON curriculum_subject(program_id, class_id, active, sort_order);

-- One learner may keep GENERAL active and also enrol in independent courses.
-- The existing unique(student_id, program_id) still prevents duplicate enrolments.
DROP INDEX IF EXISTS uq_student_single_active_program;
CREATE INDEX idx_student_program_enrollment_active
  ON student_program_enrollment(student_id, status, program_id);

-- The same official book/hash may be attached to the general curriculum and to a
-- separately scoped course. Page text and approval state stay independently auditable.
ALTER TABLE textbook_source_book
  ADD COLUMN program_id bigint;

ALTER TABLE textbook_source_book
  ADD CONSTRAINT fk_textbook_source_book_program_class
  FOREIGN KEY (program_id, class_id)
  REFERENCES curriculum_program(id, class_id) ON DELETE RESTRICT;

ALTER TABLE textbook_source_book
  DROP CONSTRAINT IF EXISTS textbook_source_book_registry_book_id_source_sha256_language_key;

CREATE UNIQUE INDEX uq_textbook_source_book_legacy
  ON textbook_source_book(registry_book_id, source_sha256, language)
  WHERE program_id IS NULL;

CREATE UNIQUE INDEX uq_textbook_source_book_program
  ON textbook_source_book(program_id, registry_book_id, source_sha256, language)
  WHERE program_id IS NOT NULL;

CREATE INDEX idx_textbook_source_book_program_scope
  ON textbook_source_book(program_id, class_id, subject_id, language, status);

-- Seed the course metadata and two distinct subject tracks. Content remains absent
-- until the existing draft authoring/import/review pipeline populates these tracks.
INSERT INTO curriculum_program(class_id, code, display_name, stream_code, curriculum_version, status, notes)
SELECT c.id, 'NAVODAYA_BATCH', 'Navodaya Batch', 'NAVODAYA', 'NCERT-2026-27', 'ACTIVE',
       'Independent Class 6 entrance-preparation course using the four imported NCERT source books.'
FROM curriculum_class c
WHERE c.code = '6' AND c.active = true
ON CONFLICT (class_id, code, curriculum_version) DO UPDATE
SET display_name = EXCLUDED.display_name,
    stream_code = EXCLUDED.stream_code,
    status = 'ACTIVE',
    updated_at = now();

INSERT INTO curriculum_subject(class_id, code, display_name, sort_order, active, program_id)
SELECT c.id, v.code, v.display_name, v.sort_order, true, p.id
FROM curriculum_class c
JOIN curriculum_program p ON p.class_id = c.id
  AND p.code = 'NAVODAYA_BATCH' AND p.curriculum_version = 'NCERT-2026-27'
CROSS JOIN (VALUES ('maths','गणित',1),('science','विज्ञान',2)) AS v(code,display_name,sort_order)
WHERE c.code = '6' AND c.active = true
ON CONFLICT (program_id, code) WHERE program_id IS NOT NULL DO NOTHING;

INSERT INTO curriculum_program_subject(program_id, class_id, subject_id, is_required, sort_order)
SELECT p.id, s.class_id, s.id, true, s.sort_order
FROM curriculum_program p
JOIN curriculum_subject s ON s.program_id = p.id AND s.active = true
WHERE p.code = 'NAVODAYA_BATCH' AND p.curriculum_version = 'NCERT-2026-27'
ON CONFLICT (program_id, subject_id) DO UPDATE
SET is_required = EXCLUDED.is_required, sort_order = EXCLUDED.sort_order;

-- Separate course chapter records copied from the existing Class 6 canonical map.
-- All are DRAFT with page ranges unverified; the existing PDF splitter/review pipeline remains authoritative.
INSERT INTO curriculum_chapter(
  subject_id,code,display_name,description,sort_order,teaching_order,active,content_status,
  curriculum_source,curriculum_source_url,curriculum_source_edition,curriculum_source_pages,
  curriculum_source_verified
)
SELECT course_subject.id, source_chapter.code, source_chapter.display_name, source_chapter.description,
       source_chapter.sort_order, coalesce(source_chapter.teaching_order,source_chapter.sort_order),
       true, 'DRAFT', source_chapter.curriculum_source, source_chapter.curriculum_source_url,
       source_chapter.curriculum_source_edition, NULL, false
FROM curriculum_class c
JOIN curriculum_subject legacy_subject ON legacy_subject.class_id=c.id
  AND legacy_subject.program_id IS NULL AND legacy_subject.active=true
JOIN curriculum_chapter source_chapter ON source_chapter.subject_id=legacy_subject.id
JOIN curriculum_program p ON p.class_id=c.id AND p.code='NAVODAYA_BATCH'
  AND p.curriculum_version='NCERT-2026-27' AND p.status='ACTIVE'
JOIN curriculum_subject course_subject ON course_subject.program_id=p.id
  AND course_subject.class_id=c.id AND course_subject.code=legacy_subject.code AND course_subject.active=true
WHERE c.code='6' AND c.active=true AND source_chapter.active=true
ON CONFLICT(subject_id,code) DO NOTHING;

INSERT INTO app_metadata(key,value)
VALUES ('schema','additive-course-scoped-curriculum-v97')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
