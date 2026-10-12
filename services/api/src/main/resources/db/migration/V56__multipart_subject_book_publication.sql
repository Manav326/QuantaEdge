-- V56: support separately published textbook parts (e.g. NCERT Class 7/8 Mathematics Parts I and II).
-- Existing single-volume books keep book_part=NULL and retain their current student/admin behavior.
alter table learning_document
  add column if not exists book_part integer;

do $book_part_constraint$
begin
  if not exists (
    select 1 from pg_constraint where conname='ck_learning_document_book_part'
  ) then
    alter table learning_document
      add constraint ck_learning_document_book_part
      check (
        book_part is null
        or (scope='SUBJECT_BOOK' and book_part between 1 and 9)
      );
  end if;
end
$book_part_constraint$;

-- Distinguish a legacy whole-book slot from numbered parts per subject and language.
drop index if exists uq_learning_document_published_book;
create unique index uq_learning_document_published_book
  on learning_document(subject_id, language, coalesce(book_part, 0))
  where scope='SUBJECT_BOOK' and status='PUBLISHED';

insert into app_metadata(key,value)
values ('schema','multipart-subject-book-publication-v56')
on conflict(key) do update set value=excluded.value;
