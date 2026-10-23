-- V167: immutable portable-paper provenance, paper-specific negative marking and section-aware scoring.
-- Existing assessments keep zero penalty and retain their prior assessment type/score behavior.

ALTER TABLE assessment
  ADD COLUMN negative_marking_fraction numeric(6,4) NOT NULL DEFAULT 0
    CHECK (negative_marking_fraction BETWEEN 0 AND 1),
  ADD COLUMN source_package_id varchar(120),
  ADD COLUMN source_package_version varchar(40),
  ADD COLUMN source_package_sha256 char(64),
  ADD COLUMN source_exam_cycle varchar(80),
  ADD COLUMN source_url varchar(1500),
  ADD CONSTRAINT ck_assessment_source_package_identity CHECK (
    (source_package_id IS NULL AND source_package_version IS NULL AND source_package_sha256 IS NULL)
    OR
    (source_package_id IS NOT NULL AND source_package_version IS NOT NULL
      AND source_package_sha256 IS NOT NULL AND source_package_sha256 ~ '^[0-9a-f]{64}$')
  );

CREATE UNIQUE INDEX uq_assessment_portable_package_version
  ON assessment(course_id,source_package_id,source_package_version)
  WHERE course_id IS NOT NULL AND source_package_id IS NOT NULL;

ALTER TABLE student_assessment_attempt
  ADD COLUMN penalty_score numeric(10,2) NOT NULL DEFAULT 0 CHECK (penalty_score >= 0);

ALTER TABLE student_assessment_answer
  ADD COLUMN penalty_marks numeric(8,2) NOT NULL DEFAULT 0 CHECK (penalty_marks >= 0);

ALTER TABLE assessment DROP CONSTRAINT ck_assessment_type;
ALTER TABLE assessment
  ADD CONSTRAINT ck_assessment_type
    CHECK (assessment_type IN ('ADMIN_CREATED','PREVIOUS_YEAR_PAPER','TOPIC_END','CHAPTER_END'));

ALTER TABLE assessment DROP CONSTRAINT ck_assessment_generated_scope;
ALTER TABLE assessment
  ADD CONSTRAINT ck_assessment_generated_scope
    CHECK (
      (assessment_type IN ('ADMIN_CREATED','PREVIOUS_YEAR_PAPER') AND is_generated=false)
      OR (assessment_type='TOPIC_END' AND is_generated=true AND lesson_id IS NOT NULL AND chapter_id IS NOT NULL)
      OR (assessment_type='CHAPTER_END' AND is_generated=true AND lesson_id IS NULL AND chapter_id IS NOT NULL)
    );

CREATE INDEX idx_assessment_source_package_lookup
  ON assessment(course_id,source_package_id,source_package_version)
  WHERE source_package_id IS NOT NULL;

INSERT INTO app_metadata(key,value)
VALUES ('schema','portable-section-aware-assessments-v167')
ON CONFLICT(key) DO UPDATE SET value=excluded.value;
