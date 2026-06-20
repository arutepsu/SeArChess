alter table external_account_links
  add column token_encrypted  bytea,
  add column token_scopes     varchar(512),
  add column token_stored_at  timestamptz;
