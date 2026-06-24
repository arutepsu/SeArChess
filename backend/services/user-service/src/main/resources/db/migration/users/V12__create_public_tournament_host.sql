create table if not exists public_tournament_host (
  tournament_id          text        not null primary key,
  host_searchess_user_id uuid        not null,
  host_display_name      text        not null,
  created_at             timestamptz not null default now()
);
