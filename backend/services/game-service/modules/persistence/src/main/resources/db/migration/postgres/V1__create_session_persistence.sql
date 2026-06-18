create table if not exists sessions (
  session_id uuid primary key,
  game_id uuid not null,
  mode text not null,
  white_controller_kind text not null,
  white_controller_engine_id text,
  black_controller_kind text not null,
  black_controller_engine_id text,
  lifecycle text not null,
  created_at timestamp not null,
  updated_at timestamp not null,
  constraint sessions_game_id_key unique (game_id)
);

create table if not exists game_states (
  game_id uuid primary key,
  state_json text not null
);

create index if not exists sessions_lifecycle_idx
  on sessions (lifecycle);
