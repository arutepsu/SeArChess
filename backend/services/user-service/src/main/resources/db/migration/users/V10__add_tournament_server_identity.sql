-- Rename existing columns to make the identity namespace explicit.
-- bot_id and bot_name are Tournament Server identities; user_id is the Searchess user UUID.
alter table public_tournament_participant rename column bot_id   to tournament_server_bot_id;
alter table public_tournament_participant rename column bot_name to tournament_server_bot_name;
alter table public_tournament_participant rename column user_id  to searchess_user_id;

-- Rename the user_id index to match the renamed column.
alter index if exists idx_ptp_user_id rename to idx_ptp_searchess_user_id;

-- Add Searchess-internal bot identity (FK to tournament_bot_ownership.id).
-- NULL for rows that existed before this migration.
alter table public_tournament_participant
  add column if not exists searchess_bot_id          uuid null,
  add column if not exists tournament_server_user_id text null;

alter table public_tournament_participant
  add constraint fk_ptp_searchess_bot_id
    foreign key (searchess_bot_id) references tournament_bot_ownership(id);
