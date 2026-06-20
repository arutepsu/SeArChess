create table if not exists user_profiles (
  user_id          uuid        primary key default gen_random_uuid(),
  keycloak_subject varchar(255) not null,
  display_name     varchar(255) not null,
  email            varchar(255),
  created_at       timestamptz  not null default now(),
  updated_at       timestamptz  not null default now(),
  constraint uq_user_profiles_keycloak_subject unique (keycloak_subject)
);
