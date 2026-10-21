-- Human-reviewed page ranges are distinct from machine-discovered title candidates.
-- These ranges are review metadata only and never publish a learning document.
alter table learning_book_intake
  add column if not exists chapter_ranges jsonb not null default '[]'::jsonb,
  add column if not exists ranges_reviewed_by_staff_id bigint,
  add column if not exists ranges_reviewed_at timestamptz,
  add column if not exists ranges_review_notes varchar(2000);

comment on column learning_book_intake.chapter_ranges is
  'Explicitly selected chapter-to-PDF page ranges; may seed draft resources but never publishes.';
