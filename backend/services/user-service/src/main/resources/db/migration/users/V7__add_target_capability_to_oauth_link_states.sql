alter table oauth_link_states
  add column target_capability varchar(30) not null default 'identity_only';
