alter table user_profiles add column if not exists nickname varchar(24);

-- Case-insensitive uniqueness enforced by a functional index on lower(nickname).
-- The WHERE clause excludes NULL so existing rows without a nickname are not affected.
create unique index if not exists uq_user_profiles_nickname_ci
  on user_profiles (lower(nickname))
  where nickname is not null;
