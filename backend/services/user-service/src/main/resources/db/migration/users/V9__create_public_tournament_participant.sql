create table if not exists public_tournament_participant (
  tournament_id text         not null,
  bot_id        text         not null,
  bot_name      text         not null,
  user_id       uuid         not null references user_profiles(user_id) on delete cascade,
  display_name  text         not null,
  joined_at     timestamptz  not null default now(),
  constraint pk_public_tournament_participant primary key (tournament_id, bot_id)
);

create index if not exists idx_ptp_tournament_id on public_tournament_participant (tournament_id);
create index if not exists idx_ptp_user_id on public_tournament_participant (user_id);
