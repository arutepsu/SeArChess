create table if not exists bot_challenge_sessions (
  id uuid primary key,
  requested_by_user_id uuid not null,
  requested_by_nickname_snapshot text not null,
  lichess_username text not null,
  lichess_user_id text,
  lichess_challenge_id text,
  lichess_challenge_url text,
  status text not null,
  clock_limit_seconds integer not null,
  clock_increment_seconds integer not null,
  color text not null,
  rated boolean not null default false,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  failure_reason text,
  constraint bot_challenge_sessions_unrated check (rated = false),
  constraint bot_challenge_sessions_status check (status in ('Requested', 'Sent', 'Failed')),
  constraint bot_challenge_sessions_color check (color in ('White', 'Black', 'Random'))
);

create index if not exists bot_challenge_sessions_user_created_idx
  on bot_challenge_sessions (requested_by_user_id, created_at desc);
