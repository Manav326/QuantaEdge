-- Durable classification and two-way reconciliation state for uploaded complete-book PDFs.
-- This registry is intentionally separate from publication and never changes student visibility.
create table if not exists learning_book_intake (
  pdf_asset_id bigint primary key references learning_pdf_asset(id) on delete restrict,
  category varchar(32) not null check (category in (
    'MAIN_TEXTBOOK','PART','SUPPLEMENTARY','WORKBOOK','TEACHER_GUIDE','REFERENCE','OTHER'
  )),
  class_id bigint not null references curriculum_class(id) on delete restrict,
  subject_id bigint not null references curriculum_subject(id) on delete restrict,
  language varchar(2) not null check (language in ('hi','en')),
  part_label varchar(80),
  status varchar(24) not null default 'PENDING'
    check (status in ('PENDING','ANALYZED','NEEDS_REVIEW','REVIEWED','BLOCKED')),
  inventory jsonb not null default '{"chapters":[],"other_sections":[],"warnings":[]}'::jsonb,
  reconciliation jsonb not null default '{"missing_from_book":[],"extra_in_book":[],"ambiguous":[],"page_boundary_warnings":[]}'::jsonb,
  analysis_version integer not null default 1,
  analysis_error text,
  analyzed_at timestamptz,
  reviewed_by_staff_id bigint,
  reviewed_at timestamptz,
  review_notes varchar(2000),
  created_by_staff_id bigint,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_learning_book_intake_track
  on learning_book_intake(class_id, subject_id, language, category, status);
create index if not exists idx_learning_book_intake_status
  on learning_book_intake(status, updated_at desc);

comment on table learning_book_intake is
  'Admin classification and independent book-inventory reconciliation; this table does not publish content.';
