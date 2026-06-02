#!/usr/bin/env bash
# LEGACY DEV HELPER — not the browser auth path.
#
# The Web UI uses Authorization Code + PKCE via keycloak-js.
# This script uses the Resource Owner Password Credentials grant (ROPC),
# which is disabled by default on the searchess-web client.
#
# To use this script you must first enable "Direct Access Grants" on the
# searchess-web client in the Keycloak admin console (http://localhost:8080).
# Do NOT enable it permanently; it is useful only for debugging token contents.
#
# Requires: Keycloak running on port 8080 (docker compose ... up keycloak).
set -euo pipefail

KEYCLOAK_URL="${KEYCLOAK_URL:-http://localhost:8080}"
REALM="searchess"
CLIENT_ID="${KC_CLIENT_ID:-searchess-web}"
USERNAME="${KC_DEMO_USER:-demo}"
PASSWORD="${KC_DEMO_PASSWORD:-demo}"

echo "NOTE: ROPC grant — for debug only. Browser auth uses PKCE, not this flow."
echo "Requesting token from ${KEYCLOAK_URL}/realms/${REALM} ..."

curl -s -X POST \
  "${KEYCLOAK_URL}/realms/${REALM}/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=${CLIENT_ID}" \
  -d "username=${USERNAME}" \
  -d "password=${PASSWORD}" \
  -d "grant_type=password" \
  | python3 -m json.tool 2>/dev/null || true
