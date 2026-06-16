import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.tsx";
import PersistenceAdminPage from "./admin/PersistenceAdminPage.tsx";
import { SessionProvider } from "./session/SessionProvider";
import keycloak, { authEnabled } from "./auth/keycloak";
import "./assets/base.css";

const container = document.getElementById("app");
if (!container) {
  throw new Error("Missing #app root element");
}

function renderApp(el: HTMLElement) {
  const root = createRoot(el);
  if (window.location.pathname === "/admin/persistence") {
    root.render(<PersistenceAdminPage onBack={() => { window.location.href = "/"; }} />);
  } else {
    root.render(
      <BrowserRouter>
        <SessionProvider>
          <App />
        </SessionProvider>
      </BrowserRouter>
    );
  }
}

if (!authEnabled) {
  renderApp(container);
} else {
  // check-sso: never blocks the app on load. An unauthenticated visitor still
  // gets a usable guest UI; protected actions trigger login individually
  // (see ensureAuthenticated in ./auth/keycloak.ts).
  keycloak
    .init({
      onLoad: "check-sso",
      pkceMethod: "S256",
      checkLoginIframe: false,
      silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
    })
    .then((authenticated: boolean) => {
      if (authenticated) {
        keycloak.onTokenExpired = () => {
          void keycloak.updateToken(30).catch(() => keycloak.clearToken());
        };
      }
      renderApp(container);
    })
    .catch(() => {
      // Keycloak unreachable: degrade to guest mode rather than blocking the app.
      renderApp(container);
    });
}
