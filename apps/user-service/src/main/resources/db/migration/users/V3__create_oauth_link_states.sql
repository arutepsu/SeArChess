create table if not exists oauth_link_states (
  state                  varchar(128)  primary key,
  user_id                uuid          not null references user_profiles(user_id) on delete cascade,
  code_verifier          varchar(256)  not null,
  redirect_after_success varchar(512)  not null,
  expires_at             timestamptz   not null,
  created_at             timestamptz   not null default now()
);

create index if not exists idx_oauth_link_states_expires_at on oauth_link_states(expires_at);
