// Frontend-only auth gate for public tournament management actions.
// Server-side authorization is the source of truth; this is UX gating only.
//
// No director-role claim is defined in Keycloak yet.
// Current policy: require authentication; all authenticated users may attempt
// director actions — the server will reject unauthorized requests with 403.
// When a director claim is added, update this function to check the claim.
import keycloak, { authEnabled } from "../../../auth/keycloak";

export function canManagePublicTournaments(): boolean {
  if (!authEnabled) return true;
  return Boolean(keycloak.authenticated);
}
