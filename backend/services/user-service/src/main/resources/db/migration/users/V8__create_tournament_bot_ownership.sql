create table if not exists tournament_bot_ownership (
  id         uuid         primary key default gen_random_uuid(),
  user_id    uuid         not null references user_profiles(user_id) on delete cascade,
  bot_id     varchar(255) not null,
  bot_name   varchar(255) not null,
  created_at timestamptz  not null default now(),
  constraint uq_tournament_bot_ownership unique (user_id, bot_id)
);

create index if not exists idx_tournament_bot_ownership_user_id on tournament_bot_ownership (user_id);
