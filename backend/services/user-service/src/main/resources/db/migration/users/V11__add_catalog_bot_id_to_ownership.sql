-- Add Searchess catalog bot id to tournament_bot_ownership.
-- NULL for custom bots (registered via CreateBotSection) and for rows that predate this migration.
-- Populated by the frontend when a user registers a Searchess catalog bot entry.
alter table tournament_bot_ownership
  add column if not exists catalog_bot_id text null;
