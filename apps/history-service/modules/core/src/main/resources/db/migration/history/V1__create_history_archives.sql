create table if not exists history_archives (
  game_id         uuid        primary key,
  session_id      uuid        not null,
  record_json     text        not null,
  created_at      timestamptz not null,
  closed_at       timestamptz not null,
  materialized_at timestamptz not null
);
