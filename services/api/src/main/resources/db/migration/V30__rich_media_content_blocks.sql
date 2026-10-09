-- V30: support audio and animation as first-class teaching blocks without modifying prior migrations.
alter table lesson_block
  drop constraint if exists lesson_block_block_type_check;

alter table lesson_block
  add constraint lesson_block_block_type_check check (block_type in (
    'EXPLANATION','IMAGE','DIAGRAM','VIDEO','AUDIO','ANIMATION','QUESTION','MCQ','TRUE_FALSE','MATCH','ORDER',
    'INPUT','HINT','AI_HELP','SUMMARY','CHALLENGE','PREREQUISITE','WORKED_EXAMPLE',
    'GUIDED_PRACTICE','INDEPENDENT_PRACTICE','RECAP'
  ));
