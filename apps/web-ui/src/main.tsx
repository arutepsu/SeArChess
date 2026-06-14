import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.tsx";
import PersistenceAdminPage from "./admin/PersistenceAdminPage.tsx";
import { SessionProvider } from "./session/SessionProvider";
import keycloak from "./auth/keycloak";
import "./assets/base.css";

const container = document.getElementById("app");
if (!container) {
  throw new Error("Missing #app root element");
}

const authEnabled = import.meta.env.VITE_AUTH_ENABLED !== "false";

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
  keycloak
    .init({ onLoad: "login-required", pkceMethod: "S256" })
    .then((authenticated: boolean) => {
      if (!authenticated) return;

      keycloak.onTokenExpired = () => {
        void keycloak.updateToken(30).catch(() => void keycloak.login());
      };

      renderApp(container);
    })
    .catch(() => {
      const keycloakUrl = import.meta.env.VITE_KEYCLOAK_URL ?? "http://localhost:8080";
      container.textContent = `Auth initialization failed. Is Keycloak reachable at ${keycloakUrl}?`;
    });
}
