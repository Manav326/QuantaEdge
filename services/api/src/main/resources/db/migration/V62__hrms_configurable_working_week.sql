-- Configurable standard working week for leave-day calculations.
alter table hr_organization
  add column working_days_per_week smallint not null default 5
  check (working_days_per_week in (5,6));
