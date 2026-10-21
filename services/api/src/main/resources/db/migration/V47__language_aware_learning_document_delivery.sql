-- V47: preserve separate Hindi and English editions through student PDF delivery.
-- Source-ingestion already knows each source language; V41 did not persist it per assignment.
alter table learning_document
  add column if not exists language varchar(2) not null default 'hi';

do $learning_document_language$
begin
  if not exists (
    select 1 from pg_constraint where conname='ck_learning_document_language'
  ) then
    alter table learning_document
      add constraint ck_learning_document_language
      check (language in ('hi','en'));
  end if;
end $learning_document_language$;

-- Recover language from the linked source where the PDF asset carries that association.
update learning_document d
set language = case when lower(coalesce(cs.language,'')) in ('en','english') then 'en' else 'hi' end
from learning_pdf_asset a
join content_source cs on cs.id=a.source_content_id
where d.pdf_asset_id=a.id;

-- Backfill chapter documents from the exact ingestion job that produced them.
update learning_document d
set language = case when lower(trim(j.language)) in ('en','english') then 'en' else 'hi' end
from source_ingestion_chapter ch
join source_ingestion_job j on j.id=ch.job_id
where ch.learning_document_id=d.id;

-- Backfill complete-book records from their matching ingestion job.
update learning_document d
set language = case when lower(trim(j.language)) in ('en','english') then 'en' else 'hi' end
from source_ingestion_job j
where d.scope='SUBJECT_BOOK' and d.pdf_asset_id=j.book_asset_id and j.book_asset_id is not null;

-- Each language has its own publication slot per subject book and chapter.
drop index if exists uq_learning_document_published_book;
create unique index uq_learning_document_published_book
  on learning_document(subject_id,language)
  where scope='SUBJECT_BOOK' and status='PUBLISHED';

drop index if exists uq_learning_document_published_chapter;
create unique index uq_learning_document_published_chapter
  on learning_document(chapter_id,language)
  where scope='CHAPTER_PDF' and status='PUBLISHED';

create index if not exists idx_learning_document_subject_language_status
  on learning_document(subject_id,language,status);

insert into app_metadata(key,value)
values ('schema','language-aware-learning-document-delivery-v47')
on conflict(key) do update set value=excluded.value;
