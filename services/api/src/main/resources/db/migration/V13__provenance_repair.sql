-- V13: repair provenance for databases that already applied V12 before the
-- V11 provenance correction. No question is allowed to masquerade as a textbook
-- or previous-exam question unless it came through a verified import pipeline.

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
where source_kind='TEXTBOOK_ALIGNED'
   or tags ? 'authored-aligned'
   or tags ? 'textbook-aligned';

insert into app_metadata(key,value)
values ('schema','provenance-correction-v13')
on conflict(key) do update set value=excluded.value;
