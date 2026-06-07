alter table sessions
  add column if not exists owner_user_id uuid,
  add column if not exists owner_nickname_snapshot text;

create index if not exists sessions_owner_user_id_idx
  on sessions (owner_user_id);
