import keycloak from "../auth/keycloak";

export default function AuthBar() {
  const username = keycloak.tokenParsed?.["preferred_username"] as
    | string
    | undefined;

  return (
    <div
      style={{
        position: "fixed",
        top: "8px",
        right: "12px",
        zIndex: 1000,
        display: "flex",
        gap: "8px",
        alignItems: "center",
        background: "rgba(0,0,0,0.55)",
        padding: "4px 10px",
        borderRadius: "4px",
        color: "#d0d0d0",
        fontSize: "13px",
        fontFamily: "sans-serif",
      }}
    >
      <span>{username ?? "—"}</span>
      <button
        type="button"
        onClick={() => void keycloak.logout()}
        style={{
          background: "transparent",
          border: "1px solid #777",
          color: "#aaa",
          cursor: "pointer",
          padding: "2px 8px",
          borderRadius: "3px",
          fontSize: "12px",
        }}
      >
        Logout
      </button>
    </div>
  );
}
