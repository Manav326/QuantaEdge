-- V59: keep per-assessment leaderboard reads efficient as attempt history grows.
-- The partial index only covers final, released attempts that can appear in rankings.
create index if not exists idx_assessment_attempt_released_leaderboard
  on student_assessment_attempt(assessment_id, student_id, final_score desc, submitted_at asc)
  include (started_at, max_score, id)
  where status='RELEASED'
    and final_score is not null
    and submitted_at is not null
    and started_at is not null;

insert into app_metadata(key,value)
values ('schema','assessment-leaderboard-index-v59')
on conflict(key) do update set value=excluded.value;
