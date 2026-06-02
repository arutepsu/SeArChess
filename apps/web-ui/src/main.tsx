import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.tsx";
import { SessionProvider } from "./session/SessionProvider";
import keycloak from "./auth/keycloak";
import "./assets/base.css";

const container = document.getElementById("app");
if (!container) {
  throw new Error("Missing #app root element");
}

keycloak
  .init({ onLoad: "login-required", pkceMethod: "S256" })
  .then((authenticated: boolean) => {
    if (!authenticated) return;

    keycloak.onTokenExpired = () => {
      void keycloak.updateToken(30).catch(() => void keycloak.login());
    };

    createRoot(container).render(
      <BrowserRouter>
        <SessionProvider>
          <App />
        </SessionProvider>
      </BrowserRouter>
    );
  })
  .catch(() => {
    container.textContent =
      "Auth initialization failed. Is Keycloak running on http://localhost:8080?";
  });
