-- Connect live-class scheduling to the authoritative HRMS teaching assignment when one is selected.
alter table learning_class_session
  add column teaching_assignment_id bigint references hr_teaching_assignment(id) on delete restrict;
create index idx_learning_class_session_teaching_assignment
  on learning_class_session(teaching_assignment_id)
  where teaching_assignment_id is not null;
