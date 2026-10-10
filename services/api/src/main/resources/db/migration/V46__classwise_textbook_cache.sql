-- V46: record the exact class partition for each cached source-ingestion job.
-- Existing V45 rows may remain NULL; every new cache job writes its grade explicitly.
alter table source_ingestion_job
  add column if not exists cache_class_no integer;

do $class_cache_no$
begin
  if not exists (
    select 1 from pg_constraint where conname = 'ck_source_ingestion_job_cache_class_no'
  ) then
    alter table source_ingestion_job
      add constraint ck_source_ingestion_job_cache_class_no
      check (cache_class_no is null or cache_class_no between 6 and 12);
  end if;
end $class_cache_no$;

create index if not exists idx_source_ingestion_job_cache_class_book
  on source_ingestion_job(cache_class_no,cache_medium,cache_book_id)
  where cache_book_id is not null;
