// Frontend-only auth gates for public tournament actions.
// Server-side authorization is the source of truth; these are UX guards only.
import keycloak, { authEnabled } from "../../../auth/keycloak";

// True for any authenticated user — grants access to read and join actions.
export function canManagePublicTournaments(): boolean {
  if (!authEnabled) return true;
  return Boolean(keycloak.authenticated);
}

// True if the current user is the creator of the given tournament.
// tournament.createdBy is the tournament-server's own user ID (e.g. "usr_eda95654"),
// not the Keycloak preferred_username. Pass the tournamentUserId from
// getCurrentTournamentIdentity() for a correct comparison.
// In dev mode (authEnabled=false) always returns true so director UX is testable.
export function canDirectTournament(
  tournament: { createdBy: string },
  tournamentUserId: string | null
): boolean {
  if (!authEnabled) return true;
  if (!tournamentUserId) return false;
  return tournament.createdBy === tournamentUserId;
}
