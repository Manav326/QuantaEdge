-- Establish truthful provenance fields for curriculum-aligned assessment items.
-- The questions seeded by earlier migrations are QuantaEdge-authored practice,
-- not copied textbook questions. Keep source attribution explicit.
alter table question add column if not exists source_kind varchar(40) not null default 'AUTHOR_CREATED';
alter table question add column if not exists source_title varchar(300);
alter table question add column if not exists source_ref varchar(500);
alter table question add column if not exists source_year integer;
alter table question add column if not exists board varchar(80);
alter table question add column if not exists tags jsonb not null default '[]'::jsonb;

update question
set source_kind = 'AUTHOR_CREATED',
    source_title = coalesce(source_title, 'QuantaEdge original curriculum-aligned practice'),
    source_ref = coalesce(source_ref, 'author-created:quantaedge-seed'),
    source_year = null,
    board = null,
    tags = case
      when jsonb_typeof(tags) = 'array' and tags <> '[]'::jsonb then tags
      else jsonb_build_array('author-created', 'curriculum-aligned')
    end
where source_kind is null
   or source_kind <> 'AUTHOR_CREATED'
   or source_title is null
   or source_ref is null
   or tags = '[]'::jsonb;

insert into app_metadata(key, value)
values ('schema', 'question-content-provenance-v9')
on conflict (key) do update set value = excluded.value;
