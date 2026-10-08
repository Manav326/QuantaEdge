-- V24: source-backed question provenance is conditional.
-- QuantaEdge must never fabricate third-party/board provenance merely to satisfy
-- an audit. Author-created questions remain valid learning content. When
-- source-backed questions are imported, their source/year/format provenance is
-- validated separately by the question provenance model.

update lesson_requirement
set required=false,
    min_count=0,
    description='External source provenance is mandatory for source-backed questions when used; author-created practice remains valid without fabricated provenance.'
where requirement_code='SOURCE_TAGGED_QUESTIONS';

insert into app_metadata(key,value)
values ('schema','conditional-source-question-provenance-v24')
on conflict(key) do update set value=excluded.value;
