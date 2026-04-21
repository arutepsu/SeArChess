import { createRoot } from "react-dom/client";
import App from "./App.tsx";
import { SessionProvider } from "./session/SessionProvider";
import "./assets/base.css";

const container = document.getElementById("app");
if (!container) {
  throw new Error("Missing #app root element");
}

createRoot(container).render(
  <SessionProvider>
    <App />
  </SessionProvider>
);