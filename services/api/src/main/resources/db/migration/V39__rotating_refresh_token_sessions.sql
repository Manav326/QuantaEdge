-- Separate short-lived access credentials from rotating, persistent refresh credentials.
alter table auth_session
  add column if not exists refresh_token_hash varchar(128),
  add column if not exists refresh_expires_at timestamptz;

create unique index if not exists uq_auth_session_refresh_token
  on auth_session(refresh_token_hash)
  where refresh_token_hash is not null;

create index if not exists idx_auth_session_refresh_expiry
  on auth_session(refresh_expires_at,revoked_at)
  where refresh_token_hash is not null;

insert into app_metadata(key,value) values ('schema','rotating-refresh-token-sessions-v39')
on conflict(key) do update set value=excluded.value;
