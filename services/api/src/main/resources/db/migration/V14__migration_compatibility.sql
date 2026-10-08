-- V14: compatibility repair for legacy author-created questions.
-- V13 exists on main and handles the same legacy provenance tags. This migration
-- is intentionally versioned higher so the feature branch never introduces a
-- second V13 when it is merged with main.

update question
set source_kind='AUTHOR_CREATED',
    source_title='QuantaEdge original curriculum-aligned practice',
    source_ref=case
      when source_ref is null or source_ref='' then 'author-created:unknown'
      when source_ref like 'author-created:%' then source_ref
      else 'author-created:'||source_ref
    end,
    source_year=null,
    board=null,
    tags=(
      coalesce((
        select jsonb_agg(x)
        from jsonb_array_elements_text(coalesce(tags,'[]'::jsonb)) x
        where x not in ('authored-aligned','textbook-aligned','author-created','curriculum-aligned')
      ), '[]'::jsonb)
      || jsonb_build_array('author-created','curriculum-aligned')
    )
where tags ? 'authored-aligned'
   or tags ? 'textbook-aligned';

insert into app_metadata(key,value)
values ('schema','migration-compatibility-v14')
on conflict(key) do update set value=excluded.value;
