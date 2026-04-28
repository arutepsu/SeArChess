import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.tsx";
import PersistenceAdminPage from "./admin/PersistenceAdminPage.tsx";
import { SessionProvider } from "./session/SessionProvider";
import "./assets/base.css";

const container = document.getElementById("app");
if (!container) {
  throw new Error("Missing #app root element");
}

const root = createRoot(container);

if (window.location.pathname === "/admin/persistence") {
  root.render(<PersistenceAdminPage onBack={() => { window.location.href = "/"; }} />);
} else {
  root.render(
    <SessionProvider>
      <App />
    </SessionProvider>
  );
}
