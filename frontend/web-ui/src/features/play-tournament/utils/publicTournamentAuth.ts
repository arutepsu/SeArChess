// Frontend-only auth gates for public tournament actions.
// Server-side authorization is the source of truth; these are UX guards only.
import keycloak, { authEnabled } from "../../../auth/keycloak";

// True for any authenticated user — grants access to read and join actions.
export function canManagePublicTournaments(): boolean {
  if (!authEnabled) return true;
  return Boolean(keycloak.authenticated);
}

// True only for the tournament director / admin role — gates Start and Delete.
// Defaults to false in production (safe) until a Keycloak director role is
// configured. In dev mode (authEnabled=false) returns true so the UI can be
// tested without a real Keycloak setup.
// TODO: when a director claim is added to Keycloak, replace the body with:
//   if (!authEnabled) return true;
//   return keycloak.hasRealmRole("tournament-director");
export function canDirectPublicTournaments(): boolean {
  if (!authEnabled) return true;
  return false;
}
