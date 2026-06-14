import { useNavigate } from "react-router-dom";
import keycloak from "../../../auth/keycloak";
import Button from "../../../components/ui/Button";
import "./AuthBar.css";

const authEnabled = import.meta.env.VITE_AUTH_ENABLED !== "false";

export default function AuthBar() {
  const navigate = useNavigate();

  if (!authEnabled) {
    return (
      <div className="auth-bar">
        <span className="auth-bar__disabled-label">Auth disabled</span>
        <Button size="sm" variant="ghost" onClick={() => navigate("/analytics")}>
          Analytics
        </Button>
      </div>
    );
  }

  const username = keycloak.tokenParsed?.["preferred_username"] as string | undefined;

  return (
    <div className="auth-bar">
      <span>{username ?? "—"}</span>
      <Button size="sm" variant="ghost" onClick={() => navigate("/analytics")}>
        Analytics
      </Button>
      <Button size="sm" variant="ghost" onClick={() => navigate("/settings")}>
        Profile
      </Button>
      <Button size="sm" variant="ghost" onClick={() => void keycloak.logout()}>
        Logout
      </Button>
    </div>
  );
}
