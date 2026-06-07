alter table history_archives
  add column if not exists owner_user_id uuid,
  add column if not exists owner_nickname_snapshot text,
  add column if not exists source text not null default 'Local';

create index if not exists history_archives_owner_user_id_idx
  on history_archives (owner_user_id);
