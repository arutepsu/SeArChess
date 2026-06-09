alter table external_account_links
  add column capability varchar(30) not null default 'unknown';

update external_account_links
set capability = case
  when verification_source = 'ManualDev' then 'manual_dev'
  when verification_source = 'OAuthPKCE' then 'identity_only'
  else 'unknown'
end;
