-- Performance indexes for strict curriculum auditing.
-- These indexes do not alter audit semantics; they accelerate the existing
-- chapter/lesson/question/content count queries used by CurriculumAuditController.

create index if not exists idx_lesson_chapter_active_status
  on lesson(chapter_id, active, status);

create index if not exists idx_chapter_concept_chapter_status
  on chapter_concept(chapter_id, status);

create index if not exists idx_lesson_requirement_chapter_required
  on lesson_requirement(chapter_id, required);

create index if not exists idx_lesson_block_lesson_active_type
  on lesson_block(lesson_id, active, block_type);

create index if not exists idx_chapter_source_chapter_coverage
  on chapter_source(chapter_id, coverage_status);

create index if not exists idx_question_lesson_active_review_type
  on question(lesson_id, active, review_status, question_type);

create index if not exists idx_question_lesson_active_review_source
  on question(lesson_id, active, review_status, source_kind);
