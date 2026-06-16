import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import keycloak, { authEnabled } from "../../../auth/keycloak";
import Button from "../../../components/ui/Button";
import GlassPanel from "../../../components/ui/GlassPanel";
import "./UserMenu.css";

interface UserMenuProps {
  dropdownAlign?: "left" | "right";
}

export default function UserMenu({ dropdownAlign = "left" }: UserMenuProps) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: PointerEvent) => {
      if (!containerRef.current?.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener("pointerdown", onPointerDown);
    return () => document.removeEventListener("pointerdown", onPointerDown);
  }, [open]);

  const signedIn = !authEnabled || Boolean(keycloak.authenticated);
  const username = !authEnabled
    ? "Guest"
    : (keycloak.tokenParsed?.["preferred_username"] as string | undefined) ?? "-";

  const close = () => setOpen(false);
  const go = (path: string) => {
    close();
    navigate(path);
  };

  if (!signedIn) {
    return (
      <div className="user-menu" ref={containerRef}>
        <Button
          type="button"
          variant="utility"
          size="sm"
          className="user-menu__trigger"
          onClick={() => void keycloak.login({ redirectUri: window.location.href })}
        >
          Log in
        </Button>
      </div>
    );
  }

  return (
    <div className="user-menu" ref={containerRef}>
      <Button
        type="button"
        variant="utility"
        size="sm"
        className="user-menu__trigger"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
      >
        <span className="user-menu__avatar" aria-hidden="true">
          {username.charAt(0).toUpperCase()}
        </span>
        <span className="user-menu__name">{username}</span>
        <span className="user-menu__caret" aria-hidden="true">v</span>
      </Button>

      {open && (
        <GlassPanel
          className={`user-menu__dropdown${dropdownAlign === "right" ? " user-menu__dropdown--right" : ""}`}
          variant="strong"
          role="menu"
        >
          <button type="button" role="menuitem" className="user-menu__item" onClick={() => go("/settings")}>
            Profile &amp; Settings
          </button>
          <button type="button" role="menuitem" className="user-menu__item" onClick={() => go("/settings")}>
            Linked Accounts
          </button>
          <button type="button" role="menuitem" className="user-menu__item user-menu__item--disabled" disabled>
            <span>Keyboard Shortcuts</span>
            <span className="user-menu__badge">Coming soon</span>
          </button>
          <button type="button" role="menuitem" className="user-menu__item" onClick={() => go("/pieces")}>
            Piece Types
          </button>
          {authEnabled && (
            <>
              <div className="user-menu__divider" role="separator" />
              <button
                type="button"
                role="menuitem"
                className="user-menu__item user-menu__item--danger"
                onClick={() => { close(); void keycloak.logout(); }}
              >
                Logout
              </button>
            </>
          )}
        </GlassPanel>
      )}
    </div>
  );
}
