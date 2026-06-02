create table if not exists external_game_bindings (
  binding_id         uuid        primary key,
  platform           text        not null,
  external_game_id   text        not null,
  internal_game_id   uuid        not null references game_states(game_id),
  session_id         uuid        not null references sessions(session_id),
  our_actor_id       text        not null,
  status             text        not null,
  status_reason      text,
  last_processed_ply integer     not null default 0,
  created_at         timestamptz not null,
  updated_at         timestamptz not null,
  constraint external_bindings_platform_game_key unique (platform, external_game_id)
);

create index if not exists external_bindings_status_idx
  on external_game_bindings (status);
