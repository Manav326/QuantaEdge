-- V13: make provenance explicit for the V11 author-created assessment spine.
-- V11 used textbook metadata as curriculum alignment, not as proof that the
-- questions came from the textbook. Never expose those questions as source-backed.
update question
set source_kind='AUTHOR_CREATED',
    source_title='QuantaEdge original curriculum-aligned practice',
    source_ref='author-created:'||coalesce(source_ref,'unknown'),
    source_year=null,
    board=null,
    tags=jsonb_build_array('author-created','curriculum-aligned')
where source_kind='TEXTBOOK_ALIGNED'
  and (
    tags ? 'authored-aligned'
    or tags ? 'textbook-aligned'
  );

insert into app_metadata(key,value)
values ('schema','provenance-correction-v13')
on conflict(key) do update set value=excluded.value;