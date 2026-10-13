-- V92: persist one-time production initialization state for the HRMS bootstrap credential.
create table if not exists auth_bootstrap_state (
  bootstrap_key varchar(64) primary key,
  initialized boolean not null default false,
  updated_at timestamptz not null default now()
);

insert into auth_bootstrap_state(bootstrap_key, initialized)
values ('HRMS_ADMIN_CREDENTIALS', false)
on conflict(bootstrap_key) do nothing;
