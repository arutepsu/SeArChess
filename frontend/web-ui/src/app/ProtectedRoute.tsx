import { useEffect } from "react";
import { Outlet } from "react-router-dom";
import keycloak, { authEnabled } from "../auth/keycloak";

/**
 * Layout route that redirects unauthenticated users to Keycloak login.
 *
 * Usage in React Router v6:
 *   <Route element={<ProtectedRoute />}>
 *     <Route path="/game" element={<GamePage />} />
 *   </Route>
 *
 * When authEnabled is false (local dev modes) the guard is bypassed and
 * children render normally.  keycloak.authenticated is safe to read
 * synchronously here because main.tsx awaits keycloak.init() before
 * calling renderApp().
 */
export default function ProtectedRoute() {
  const isAuthenticated = !authEnabled || Boolean(keycloak.authenticated);

  useEffect(() => {
    if (!isAuthenticated) {
      void keycloak.login({ redirectUri: window.location.href });
    }
  }, [isAuthenticated]);

  if (isAuthenticated) {
    return <Outlet />;
  }

  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        height: "100vh",
        color: "var(--color-text-muted, #888)",
        fontSize: "1rem",
      }}
    >
      Redirecting to sign in…
    </div>
  );
}
