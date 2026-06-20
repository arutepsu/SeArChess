create table if not exists external_account_links (
  link_id              uuid        primary key default gen_random_uuid(),
  user_id              uuid        not null references user_profiles(user_id) on delete cascade,
  provider             varchar(50) not null,
  external_id          varchar(255),
  external_username    varchar(255) not null,
  verified             boolean      not null default false,
  verification_source  varchar(50)  not null,
  linked_at            timestamptz  not null default now(),
  constraint uq_external_account_links_user_provider unique (user_id, provider)
);

create index if not exists idx_external_account_links_user_id on external_account_links (user_id);
