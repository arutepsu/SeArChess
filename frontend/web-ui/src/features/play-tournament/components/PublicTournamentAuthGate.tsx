import { Outlet } from "react-router-dom";
import keycloak, { authEnabled } from "../../../auth/keycloak";
import Button from "../../../components/ui/Button";
import "../pages/PlayTournament.css";

export default function PublicTournamentAuthGate() {
  if (authEnabled && !keycloak.authenticated) {
    return (
      <div className="play-tournament-page">
        <div className="play-tournament-shell">
          <div className="play-tournament-header">
            <div>
              <h1 className="play-tournament-title">Public Tournaments</h1>
              <p className="play-tournament-subtitle">
                Sign in to use the Public Tournament server.
              </p>
            </div>
          </div>
          <div style={{ display: "flex", justifyContent: "center", padding: "40px 0" }}>
            <Button
              variant="primary"
              size="lg"
              onClick={() => void keycloak.login({ redirectUri: window.location.href })}
            >
              Sign in
            </Button>
          </div>
        </div>
      </div>
    );
  }
  return <Outlet />;
}
