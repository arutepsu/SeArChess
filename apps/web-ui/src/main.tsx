import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import App from "./App.tsx";
import { SessionProvider } from "./session/SessionProvider";
import "./assets/base.css";

const container = document.getElementById("app");
if (!container) {
  throw new Error("Missing #app root element");
}

createRoot(container).render(
  <BrowserRouter>
    <SessionProvider>
      <App />
    </SessionProvider>
  </BrowserRouter>
);