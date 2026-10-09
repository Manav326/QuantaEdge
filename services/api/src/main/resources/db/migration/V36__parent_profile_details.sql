-- Parent profile details used by the parent dashboard and account settings.
alter table user_account
  add column if not exists profile_image_data_url text,
  add column if not exists email varchar(254),
  add column if not exists city varchar(100),
  add column if not exists state varchar(100),
  add column if not exists occupation varchar(120),
  add column if not exists organization varchar(180),
  add column if not exists preferred_language varchar(30) not null default 'English',
  add column if not exists profile_updated_at timestamptz not null default now();

insert into app_metadata(key,value) values ('schema','parent-profile-details-v36')
on conflict (key) do update set value=excluded.value;
