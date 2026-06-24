alter table users.public_tournament_host
  add column host_keycloak_sub                 text null,
  add column director_tournament_server_bot_id   text null,
  add column director_tournament_server_bot_name text null;
