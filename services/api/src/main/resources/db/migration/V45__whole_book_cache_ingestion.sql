-- V45: allow admin source ingestion to review whole books directly from the mounted GHCR cache.
-- The complete source stays on the read-only cache volume; only reviewed chapter PDFs enter PostgreSQL.
alter table source_ingestion_job
  add column if not exists cache_medium varchar(20),
  add column if not exists cache_book_id varchar(240),
  add column if not exists cache_sha256 varchar(64);

do $cache_reference$
begin
  if not exists (select 1 from pg_constraint where conname='ck_source_ingestion_job_cache_reference') then
    alter table source_ingestion_job add constraint ck_source_ingestion_job_cache_reference
      check (
        (cache_medium is null and cache_book_id is null and cache_sha256 is null)
        or
        (cache_medium is not null and cache_medium in ('hindi','english')
          and cache_book_id is not null and length(cache_book_id) between 1 and 240
          and cache_sha256 is not null and cache_sha256 ~ '^[0-9a-f]{64}
      );
  end if;
end $cache_reference$;

create index if not exists idx_source_ingestion_job_cache_book
  on source_ingestion_job(cache_medium,cache_book_id)
  where cache_book_id is not null;
)
      );
  end if;
end $cache_reference$;

create index if not exists idx_source_ingestion_job_cache_book
  on source_ingestion_job(cache_medium,cache_book_id)
  where cache_book_id is not null;
