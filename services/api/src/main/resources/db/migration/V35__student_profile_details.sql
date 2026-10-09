-- Student-owned profile details; do not store exact home addresses or date of birth.
alter table student
  add column if not exists profile_image_data_url text,
  add column if not exists city varchar(100),
  add column if not exists state varchar(100),
  add column if not exists school_name varchar(180),
  add column if not exists school_medium varchar(40),
  add column if not exists favorite_subject varchar(30),
  add column if not exists learning_goal varchar(300),
  add column if not exists profile_updated_at timestamptz not null default now();

insert into app_metadata(key,value) values ('schema','student-profile-details-v35')
on conflict (key) do update set value=excluded.value;
