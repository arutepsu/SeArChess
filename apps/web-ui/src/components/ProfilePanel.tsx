import { useEffect, useRef, useState } from "react";
import { useSearchParams } from "react-router-dom";
import type { UserProfileResponse } from "../api/userServiceTypes";
import { deleteLichessLink, getMyProfile, patchProfile, setManualLichessLink, startLichessLink } from "../api/userServiceClient";
import "./ProfilePanel.css";

interface ProfilePanelProps {
  onBack: () => void;
}

export default function ProfilePanel({ onBack }: ProfilePanelProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const initialLichessResult = useRef(searchParams.get("lichess"));

  const [profile, setProfile] = useState<UserProfileResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [lichessInput, setLichessInput] = useState("");
  const [nicknameInput, setNicknameInput] = useState("");
  const [editingNickname, setEditingNickname] = useState(false);
  const [saving, setSaving] = useState(false);
  const [lichessNotice, setLichessNotice] = useState<{ kind: "success" | "error"; text: string } | null>(null);
  const [nicknameNotice, setNicknameNotice] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  useEffect(() => {
    const lichessResult = initialLichessResult.current;
    if (lichessResult) {
      setSearchParams({}, { replace: true });
      if (lichessResult === "linked") {
        setLichessNotice({ kind: "success", text: "Lichess account verified and linked." });
      } else {
        setLichessNotice({ kind: "error", text: "Lichess linking failed or was cancelled. Please try again." });
      }
    }

    getMyProfile()
      .then(setProfile)
      .catch((err: unknown) => {
        setLoadError(err instanceof Error ? err.message : "Failed to load profile");
      });
  }, [setSearchParams]);

  const lichessLink = profile?.links.find((l) => l.provider === "Lichess") ?? null;

  const handleSaveNickname = async () => {
    const trimmed = nicknameInput.trim();
    if (!trimmed) return;
    setSaving(true);
    setNicknameNotice(null);
    try {
      const updated = await patchProfile({ nickname: trimmed });
      setProfile(updated);
      setNicknameInput("");
      setEditingNickname(false);
      setNicknameNotice({ kind: "success", text: `Searchess username changed to "${trimmed}".` });
    } catch (err) {
      const msg = err instanceof Error ? err.message : "Failed to save nickname";
      setNicknameNotice({
        kind: "error",
        text: msg.includes("already taken") ? `"${nicknameInput.trim()}" is already taken. Please choose another.` : msg,
      });
    } finally {
      setSaving(false);
    }
  };

  const handleLinkLichess = async () => {
    setSaving(true);
    setLichessNotice(null);
    try {
      const { authorizationUrl } = await startLichessLink();
      window.location.href = authorizationUrl;
    } catch (err) {
      setLichessNotice({ kind: "error", text: err instanceof Error ? err.message : "Failed to start Lichess linking" });
      setSaving(false);
    }
  };

  const handleSaveLink = async () => {
    const username = lichessInput.trim();
    if (!username) return;
    setSaving(true);
    setLichessNotice(null);
    try {
      const link = await setManualLichessLink({ lichessUsername: username });
      setProfile((prev) =>
        prev
          ? { ...prev, links: [...prev.links.filter((l) => l.provider !== "Lichess"), link] }
          : prev
      );
      setLichessNotice({ kind: "success", text: `Lichess username set to "${link.externalUsername}" (manual dev link).` });
      setLichessInput("");
    } catch (err) {
      setLichessNotice({ kind: "error", text: err instanceof Error ? err.message : "Save failed" });
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteLink = async () => {
    setSaving(true);
    setLichessNotice(null);
    try {
      await deleteLichessLink();
      setProfile((prev) =>
        prev ? { ...prev, links: prev.links.filter((l) => l.provider !== "Lichess") } : prev
      );
      setLichessNotice({ kind: "success", text: "Lichess link removed." });
    } catch (err) {
      setLichessNotice({ kind: "error", text: err instanceof Error ? err.message : "Delete failed" });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="profile-panel">
      <h2>Profile &amp; Settings</h2>
      <p className="subtitle">Manage your Searchess identity and linked external accounts.</p>

      {loadError ? (
        <p className="profile-notice error">{loadError}</p>
      ) : !profile ? (
        <p className="profile-notice">Loading…</p>
      ) : (
        <>
          {/* ── Searchess Identity ─────────────────────────────────────────── */}
          <section className="profile-section">
            <h3>Searchess Identity</h3>

            <div className="profile-field">
              <span className="label">Searchess username</span>
              {profile.nickname ? (
                <span>
                  {profile.nickname}
                  <button
                    type="button"
                    style={{ fontSize: "0.75rem", padding: "0.1rem 0.4rem", marginLeft: "0.6rem" }}
                    disabled={saving}
                    onClick={() => {
                      setNicknameInput(profile.nickname ?? "");
                      setEditingNickname(true);
                      setNicknameNotice(null);
                    }}
                  >
                    Change
                  </button>
                </span>
              ) : (
                <span style={{ color: "#e07070", fontSize: "0.85rem" }}>
                  Not set —{" "}
                  <a href="/onboarding" style={{ color: "#e09050" }}>
                    complete onboarding
                  </a>
                </span>
              )}
            </div>

            {editingNickname && (
              <div className="manual-link-form" style={{ marginBottom: "0.5rem" }}>
                <label htmlFor="nickname-edit-input">New Searchess username</label>
                <input
                  id="nickname-edit-input"
                  type="text"
                  value={nicknameInput}
                  disabled={saving}
                  maxLength={24}
                  placeholder="3–24 chars: letters, digits, _ or -"
                  onChange={(e) => setNicknameInput(e.currentTarget.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") void handleSaveNickname();
                  }}
                  autoFocus
                />
                <div style={{ display: "flex", gap: "0.5rem", marginTop: "0.25rem" }}>
                  <button
                    type="button"
                    disabled={saving || nicknameInput.trim().length < 3}
                    onClick={() => void handleSaveNickname()}
                  >
                    {saving ? "Saving…" : "Save"}
                  </button>
                  <button type="button" disabled={saving} onClick={() => setEditingNickname(false)}>
                    Cancel
                  </button>
                </div>
              </div>
            )}

            {nicknameNotice ? (
              <p className={`profile-notice ${nicknameNotice.kind}`}>{nicknameNotice.text}</p>
            ) : null}

            {profile.email ? (
              <div className="profile-field">
                <span className="label">Login email</span>
                <span>{profile.email}</span>
              </div>
            ) : null}

            <div className="profile-field">
              <span className="label">User ID</span>
              <span style={{ fontSize: "0.75rem", fontFamily: "monospace", color: "#666" }}>
                {profile.userId}
              </span>
            </div>

            <details style={{ marginTop: "0.5rem" }}>
              <summary style={{ color: "#666", fontSize: "0.8rem", cursor: "pointer" }}>
                Login account details
              </summary>
              <div className="profile-field" style={{ marginTop: "0.4rem" }}>
                <span className="label" style={{ fontSize: "0.8rem" }}>Login username</span>
                <span style={{ fontSize: "0.85rem", color: "#888" }}>{profile.displayName}</span>
              </div>
            </details>
          </section>

          {/* ── Lichess Account ────────────────────────────────────────────── */}
          <section className="profile-section">
            <h3>Lichess Account</h3>

            {lichessLink ? (
              <div className="link-card">
                <div className="link-provider">
                  Lichess
                  {lichessLink.verified ? (
                    <span className="verified-badge">verified</span>
                  ) : (
                    <span className="unverified-badge">{lichessLink.verificationSource}</span>
                  )}
                </div>
                <div className="link-username">@{lichessLink.externalUsername}</div>
                <div className="link-meta">
                  Linked {new Date(lichessLink.linkedAt).toLocaleDateString()}
                </div>
              </div>
            ) : (
              <p style={{ color: "#777", fontSize: "0.9rem", marginBottom: "0.75rem" }}>
                No Lichess account linked.
              </p>
            )}

            <div style={{ display: "flex", gap: "0.5rem", marginBottom: "0.75rem", flexWrap: "wrap" }}>
              <button type="button" disabled={saving} onClick={() => void handleLinkLichess()}>
                {lichessLink?.verified ? "Re-link Lichess account" : "Link Lichess account"}
              </button>
              {lichessLink ? (
                <button
                  type="button"
                  disabled={saving}
                  style={{ color: "#c88", borderColor: "rgba(200,100,100,0.3)" }}
                  onClick={() => void handleDeleteLink()}
                >
                  Remove link
                </button>
              ) : null}
            </div>

            <details style={{ marginTop: "0.5rem" }}>
              <summary style={{ color: "#666", fontSize: "0.85rem", cursor: "pointer" }}>
                Manual dev link (local only)
              </summary>
              <div className="manual-link-form" style={{ marginTop: "0.5rem" }}>
                <label htmlFor="lichess-username-input">
                  Set Lichess username (ManualDev — bypasses OAuth)
                </label>
                <input
                  id="lichess-username-input"
                  type="text"
                  placeholder="e.g. alice_chess"
                  value={lichessInput}
                  disabled={saving}
                  onChange={(e) => setLichessInput(e.currentTarget.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") void handleSaveLink();
                  }}
                />
                <button
                  type="button"
                  disabled={saving || !lichessInput.trim()}
                  onClick={() => void handleSaveLink()}
                >
                  {lichessLink ? "Update" : "Save"}
                </button>
              </div>
            </details>

            {lichessNotice ? (
              <p className={`profile-notice ${lichessNotice.kind}`}>{lichessNotice.text}</p>
            ) : null}
          </section>
        </>
      )}

      <button type="button" className="profile-back-btn" onClick={onBack}>
        ← Back
      </button>
    </div>
  );
}
