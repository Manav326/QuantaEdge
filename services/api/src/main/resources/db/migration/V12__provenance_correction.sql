-- V12: provenance correction.
-- V11 generated author-created practice questions. They must never be represented as
-- textbook or previous-exam questions. This migration makes that provenance explicit.

insert into content_source(source_kind,title,provider,source_url,edition,language,status)
values (
  'AUTHOR_CREATED',
  'QuantaEdge original curriculum-aligned practice',
  'QuantaEdge',
  null,
  'v1',
  'hi',
  'REFERENCE'
)
on conflict(source_kind,title,edition) do update
set provider=excluded.provider,status='REFERENCE';

update question
set source_kind='AUTHOR_CREATED',
    source_title='QuantaEdge original curriculum-aligned practice',
    source_ref='author-created:chapter:'||coalesce(source_ref,'unknown'),
    source_year=null,
    board=null,
    tags=coalesce((
      select jsonb_agg(x)
      from jsonb_array_elements_text(coalesce(tags,'[]'::jsonb)) x
      where x not in ('textbook-aligned','scert-chapter-'||coalesce(source_ref,''))
    ), '[]'::jsonb)
where source_kind='TEXTBOOK_ALIGNED'
  and (tags ? 'author-created' or tags ? 'authored-aligned' or tags ? 'textbook-aligned');

insert into app_metadata(key,value)
values ('schema','provenance-correction-v12')
on conflict(key) do update set value=excluded.value;
