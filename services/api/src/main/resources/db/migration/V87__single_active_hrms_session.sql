-- V87: enforce one active HRMS session per employee at the database level.
with ranked_sessions as (
  select id,
         row_number() over (
           partition by staff_id
           order by last_seen_at desc nulls last, created_at desc, id desc
         ) as position
  from auth_session
  where workspace_key='HRMS'
    and staff_id is not null
    and revoked_at is null
)
update auth_session s
set revoked_at=now()
from ranked_sessions r
where s.id=r.id and r.position>1;

create unique index if not exists uq_auth_session_one_active_hrms_per_staff
  on auth_session(staff_id)
  where workspace_key='HRMS'
    and staff_id is not null
    and revoked_at is null;
