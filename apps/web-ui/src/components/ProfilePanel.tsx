import { useEffect, useState } from "react";
import type { UserProfileResponse } from "../api/userServiceTypes";
import { deleteLichessLink, getMyProfile, setManualLichessLink } from "../api/userServiceClient";
import "./ProfilePanel.css";

interface ProfilePanelProps {
  onBack: () => void;
}

export default function ProfilePanel({ onBack }: ProfilePanelProps) {
  const [profile, setProfile] = useState<UserProfileResponse | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [lichessInput, setLichessInput] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveNotice, setSaveNotice] = useState<{ kind: "success" | "error"; text: string } | null>(null);

  useEffect(() => {
    getMyProfile()
      .then(setProfile)
      .catch((err: unknown) => {
        setLoadError(err instanceof Error ? err.message : "Failed to load profile");
      });
  }, []);

  const lichessLink = profile?.links.find((l) => l.provider === "Lichess") ?? null;

  const handleSaveLink = async () => {
    const username = lichessInput.trim();
    if (!username) return;
    setSaving(true);
    setSaveNotice(null);
    try {
      const link = await setManualLichessLink({ lichessUsername: username });
      setProfile((prev) =>
        prev
          ? {
              ...prev,
              links: [
                ...prev.links.filter((l) => l.provider !== "Lichess"),
                link,
              ],
            }
          : prev
      );
      setSaveNotice({ kind: "success", text: `Lichess username set to "${link.externalUsername}".` });
      setLichessInput("");
    } catch (err) {
      setSaveNotice({ kind: "error", text: err instanceof Error ? err.message : "Save failed" });
    } finally {
      setSaving(false);
    }
  };

  const handleDeleteLink = async () => {
    setSaving(true);
    setSaveNotice(null);
    try {
      await deleteLichessLink();
      setProfile((prev) =>
        prev ? { ...prev, links: prev.links.filter((l) => l.provider !== "Lichess") } : prev
      );
      setSaveNotice({ kind: "success", text: "Lichess link removed." });
    } catch (err) {
      setSaveNotice({ kind: "error", text: err instanceof Error ? err.message : "Delete failed" });
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="profile-panel">
      <h2>Profile &amp; Account Links</h2>
      <p className="subtitle">Manage your Searchess identity and linked external accounts.</p>

      {loadError ? (
        <p className="profile-notice error">{loadError}</p>
      ) : !profile ? (
        <p className="profile-notice">Loading profile…</p>
      ) : (
        <>
          <section className="profile-section">
            <h3>Searchess Account</h3>
            <div className="profile-field">
              <span className="label">Display name</span>
              <span>{profile.displayName}</span>
            </div>
            {profile.email ? (
              <div className="profile-field">
                <span className="label">Email</span>
                <span>{profile.email}</span>
              </div>
            ) : null}
            <div className="profile-field">
              <span className="label">User ID</span>
              <span style={{ fontSize: "0.75rem", fontFamily: "monospace", color: "#666" }}>
                {profile.userId}
              </span>
            </div>
          </section>

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

            <div className="manual-link-form">
              <label htmlFor="lichess-username-input">
                {lichessLink ? "Update Lichess username" : "Set Lichess username (dev only)"}
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
              <div style={{ display: "flex", gap: "0.5rem" }}>
                <button type="button" disabled={saving || !lichessInput.trim()} onClick={() => void handleSaveLink()}>
                  {lichessLink ? "Update" : "Save"}
                </button>
                {lichessLink ? (
                  <button
                    type="button"
                    disabled={saving}
                    style={{ color: "#c88", borderColor: "rgba(200,100,100,0.3)" }}
                    onClick={() => void handleDeleteLink()}
                  >
                    Remove
                  </button>
                ) : null}
              </div>
              {saveNotice ? (
                <p className={`profile-notice ${saveNotice.kind}`}>{saveNotice.text}</p>
              ) : null}
            </div>
          </section>
        </>
      )}

      <button type="button" className="profile-back-btn" onClick={onBack}>
        ← Back
      </button>
    </div>
  );
}
