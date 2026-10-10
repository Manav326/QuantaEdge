-- Durable content-review history and learner-selected practice sessions.
create table if not exists question_review_history (
  id bigserial primary key,
  question_id bigint not null,
  lesson_id bigint,
  event_type varchar(40) not null,
  previous_status varchar(20),
  new_status varchar(20),
  actor_staff_id bigint,
  actor_role varchar(40),
  change_reason varchar(1200),
  before_snapshot jsonb,
  after_snapshot jsonb,
  occurred_at timestamptz not null default now()
);

create index if not exists idx_question_review_history_question_time
  on question_review_history(question_id, occurred_at desc, id desc);
create index if not exists idx_question_review_history_status_time
  on question_review_history(new_status, occurred_at desc);

-- Seed a baseline so every existing question has a visible starting point.
-- Previous decisions cannot be reconstructed if they were not recorded before this migration.
insert into question_review_history(
  question_id, lesson_id, event_type, previous_status, new_status, change_reason, after_snapshot, occurred_at
)
select q.id, q.lesson_id, 'BASELINE', null, q.review_status,
       coalesce(nullif(btrim(q.review_notes), ''),
         'Status at history rollout; earlier decisions were not recorded.'),
       to_jsonb(q) || jsonb_build_object(
         'options', coalesce((
           select jsonb_agg(jsonb_build_object(
             'option_key', qo.option_key, 'label', qo.label,
             'is_correct', qo.is_correct, 'sort_order', qo.sort_order
           ) order by qo.sort_order)
           from question_option qo where qo.question_id=q.id
         ), '[]'::jsonb)
       ),
       now()
from question q
where not exists (
  select 1 from question_review_history h
  where h.question_id=q.id and h.event_type='BASELINE'
);

create table if not exists student_practice_session (
  id bigserial primary key,
  student_id bigint not null references student(id),
  status varchar(20) not null default 'IN_PROGRESS'
    check (status in ('IN_PROGRESS','COMPLETED','ABANDONED')),
  requested_question_count integer not null check (requested_question_count between 1 and 100),
  selected_question_count integer not null default 0 check (selected_question_count between 0 and 100),
  question_types jsonb not null default '[]'::jsonb,
  topic_selections jsonb not null default '[]'::jsonb,
  created_at timestamptz not null default now(),
  completed_at timestamptz
);

create index if not exists idx_student_practice_session_student_created
  on student_practice_session(student_id, created_at desc);

create table if not exists student_practice_session_question (
  practice_session_id bigint not null references student_practice_session(id) on delete cascade,
  question_id bigint not null,
  sequence_no integer not null check (sequence_no > 0),
  question_snapshot jsonb not null,
  answered_at timestamptz,
  primary key (practice_session_id, question_id),
  unique (practice_session_id, sequence_no)
);

create index if not exists idx_practice_session_question_question
  on student_practice_session_question(question_id);

alter table student_question_attempt
  add column if not exists practice_session_id bigint references student_practice_session(id) on delete set null;

create index if not exists idx_student_question_attempt_practice_session
  on student_question_attempt(practice_session_id, answered_at)
  where practice_session_id is not null;

insert into app_metadata(key,value) values ('schema','question-history-and-practice-sessions-v40')
on conflict(key) do update set value=excluded.value;
