// Frontend-only auth gates for public tournament actions.
// Server-side authorization is the source of truth; these are UX guards only.
import keycloak, { authEnabled } from "../../../auth/keycloak";

// True for any authenticated user — grants access to read and join actions.
export function canManagePublicTournaments(): boolean {
  if (!authEnabled) return true;
  return Boolean(keycloak.authenticated);
}

// True if the current user is the creator of the given tournament.
// Compares tournament.createdBy (Tournament Server username mapped from Keycloak
// preferred_username via the gateway withDirector handler) to the local Keycloak claim.
// In dev mode (authEnabled=false) always returns true so director UX is testable.
export function canDirectTournament(tournament: { createdBy: string }): boolean {
  if (!authEnabled) return true;
  const username = keycloak.tokenParsed?.["preferred_username"] as string | undefined;
  if (!username) return false;
  return tournament.createdBy === username;
}
