import Keycloak from "keycloak-js";

const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL ?? "http://localhost:8080",
  realm: import.meta.env.VITE_KEYCLOAK_REALM ?? "searchess",
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID ?? "searchess-web",
});

export const authEnabled = import.meta.env.VITE_AUTH_ENABLED !== "false";

/**
 * Gate for protected actions (e.g. starting a game). Returns true if the
 * caller may proceed; otherwise kicks off a login redirect and returns false.
 */
export function ensureAuthenticated(): boolean {
  if (!authEnabled) return true;
  if (keycloak.authenticated) return true;
  void keycloak.login({ redirectUri: window.location.href });
  return false;
}

export default keycloak;