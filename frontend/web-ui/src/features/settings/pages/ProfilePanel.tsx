import { useEffect, useRef, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import type { ExternalAccountLinkDto, UserProfileResponse } from "../../../api/userServiceTypes";
import {
  deleteLichessLink,
  getMyProfile,
  patchProfile,
  setManualLichessLink,
  startLichessLink
} from "../../../api/userServiceClient";
import Button from "../../../components/ui/Button";
import GlassPanel from "../../../components/ui/GlassPanel";
import "./ProfilePanel.css";

interface ProfilePanelProps {
  onBack: () => void;
}

function initialsFor(profile: UserProfileResponse | null): string {
  const source = profile?.nickname || profile?.displayName || profile?.email || "Searchess";
  return source
    .split(/[ _.-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join("") || "S";
}

function formatDate(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleDateString();
}

function capabilityLabel(link: ExternalAccountLinkDto): string {
  switch (link.capability) {
    case "challenge_ready":
      return "Challenge ready";
    case "board_play":
      return "Board play";
    case "identity_only":
      return "Identity only";
    case "manual_dev":
      return "Manual dev";
    case "expired":
      return "Expired";
    case "revoked":
      return "Revoked";
    default:
      return "Linked";
  }
}

export default function ProfilePanel({ onBack }: ProfilePanelProps) {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const initialLichessResult = useRef(searchParams.get("lichess"));

  const [profile, setProfile] = useState<UserProfileResponse | null>(null);
  const [loading, setLoading] = useState(true);
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
      } else if (lichessResult === "upgraded") {
        setLichessNotice({ kind: "success", text: "Lichess permissions upgraded successfully." });
      } else {
        setLichessNotice({ kind: "error", text: "Lichess linking failed or was cancelled. Please try again." });
      }
    }

    setLoading(true);
    setLoadError(null);
    getMyProfile()
      .then(setProfile)
      .catch((err: unknown) => {
        setLoadError(err instanceof Error ? err.message : "Failed to load profile");
      })
      .finally(() => setLoading(false));
  }, [setSearchParams]);

  const lichessLink = profile?.links.find((l) => l.provider === "Lichess") ?? null;
  const displayName = profile?.nickname || profile?.displayName || "Searchess player";
  const accountStatus = profile?.onboardingRequired ? "Setup needed" : "Active";

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

  const startNicknameEdit = () => {
    setNicknameInput(profile?.nickname ?? "");
    setEditingNickname(true);
    setNicknameNotice(null);
  };

  return (
    <main className="profile-page">
      <div className="profile-shell">
        <header className="profile-header">
          <div>
            <p className="profile-kicker">Account</p>
            <h1>Profile &amp; Settings</h1>
            <p>Manage your Searchess identity, linked accounts, and game preferences.</p>
          </div>
          <Button variant="secondary" onClick={onBack}>Back</Button>
        </header>

        {loading ? (
          <GlassPanel as="section" className="profile-state-card" variant="strong" aria-live="polite">
            <h2>Loading profile</h2>
            <p>Fetching your Searchess account details.</p>
          </GlassPanel>
        ) : loadError ? (
          <GlassPanel as="section" className="profile-state-card" variant="strong" role="alert">
            <h2>Profile not available</h2>
            <p>We could not load your profile information yet.</p>
            <p className="profile-state-card__detail">{loadError}</p>
            <div className="profile-actions-row">
              <Button variant="secondary" onClick={onBack}>Back</Button>
            </div>
          </GlassPanel>
        ) : profile ? (
          <div className="profile-layout">
            <div className="profile-main-column">
              <GlassPanel as="section" className="profile-card profile-identity-card" variant="strong">
                <div className="profile-person">
                  <div className="profile-avatar" aria-hidden="true">{initialsFor(profile)}</div>
                  <div>
                    <span className={`profile-status profile-status--${profile.onboardingRequired ? "setup" : "active"}`}>
                      {accountStatus}
                    </span>
                    <h2>{displayName}</h2>
                    <p>{profile.email ?? "No email address available"}</p>
                  </div>
                </div>

                <dl className="profile-details">
                  <div>
                    <dt>Searchess username</dt>
                    <dd>{profile.nickname ?? "Not set yet"}</dd>
                  </div>
                  <div>
                    <dt>Login name</dt>
                    <dd>{profile.displayName || "Not available"}</dd>
                  </div>
                  <div>
                    <dt>User ID</dt>
                    <dd className="profile-monospace">{profile.userId}</dd>
                  </div>
                </dl>

                {profile.onboardingRequired ? (
                  <div className="profile-callout">
                    <strong>Finish account setup</strong>
                    <span>Choose a Searchess username before starting connected account workflows.</span>
                    <Button variant="secondary" size="sm" onClick={() => navigate("/onboarding")}>
                      Complete onboarding
                    </Button>
                  </div>
                ) : null}

                <div className="profile-edit-block">
                  {editingNickname ? (
                    <div className="profile-form">
                      <label htmlFor="nickname-edit-input">New Searchess username</label>
                      <input
                        id="nickname-edit-input"
                        type="text"
                        value={nicknameInput}
                        disabled={saving}
                        maxLength={24}
                        placeholder="3-24 chars: letters, digits, _ or -"
                        onChange={(e) => setNicknameInput(e.currentTarget.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") void handleSaveNickname();
                        }}
                        autoFocus
                      />
                      <div className="profile-actions-row">
                        <Button
                          size="sm"
                          variant="primary"
                          disabled={saving || nicknameInput.trim().length < 3}
                          onClick={() => void handleSaveNickname()}
                        >
                          {saving ? "Saving..." : "Save"}
                        </Button>
                        <Button
                          size="sm"
                          variant="secondary"
                          disabled={saving}
                          onClick={() => setEditingNickname(false)}
                        >
                          Cancel
                        </Button>
                      </div>
                    </div>
                  ) : (
                    <Button variant="secondary" onClick={startNicknameEdit} disabled={saving}>
                      Edit profile
                    </Button>
                  )}

                  {nicknameNotice ? (
                    <p className={`profile-notice profile-notice--${nicknameNotice.kind}`}>{nicknameNotice.text}</p>
                  ) : null}
                </div>
              </GlassPanel>

              <GlassPanel
                as="section"
                className="profile-card profile-linked-card"
                variant="default"
                aria-label="Connected accounts"
              >
                <div className="profile-section-heading">
                  <div>
                    <p className="profile-kicker">Linked services</p>
                    <h2>Connected accounts</h2>
                  </div>
                  <span className={`profile-pill ${lichessLink?.verified ? "profile-pill--success" : ""}`}>
                    {lichessLink ? "Connected" : "Not connected"}
                  </span>
                </div>

                {lichessLink ? (
                  <article className="profile-link-card">
                    <div>
                      <h3>Lichess</h3>
                      <p>@{lichessLink.externalUsername}</p>
                    </div>
                    <dl>
                      <div>
                        <dt>Status</dt>
                        <dd>{lichessLink.verified ? "Verified" : lichessLink.verificationSource}</dd>
                      </div>
                      <div>
                        <dt>Capability</dt>
                        <dd>{capabilityLabel(lichessLink)}</dd>
                      </div>
                      <div>
                        <dt>Linked</dt>
                        <dd>{formatDate(lichessLink.linkedAt)}</dd>
                      </div>
                    </dl>
                  </article>
                ) : (
                  <div className="profile-empty">
                    <h3>No linked services yet</h3>
                    <p>Connect Lichess to use linked bot tools and account-aware chess workflows.</p>
                  </div>
                )}

                <div className="profile-actions-row">
                  <Button variant="primary" disabled={saving} onClick={() => void handleLinkLichess()}>
                    {lichessLink?.verified ? "Re-link Lichess" : "Link Lichess"}
                  </Button>
                  {lichessLink ? (
                    <Button variant="danger" disabled={saving} onClick={() => void handleDeleteLink()}>
                      Remove link
                    </Button>
                  ) : null}
                </div>

                <details className="profile-dev-details">
                  <summary>Manual dev link</summary>
                  <div className="profile-form">
                    <label htmlFor="lichess-username-input">Set Lichess username</label>
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
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={saving || !lichessInput.trim()}
                      onClick={() => void handleSaveLink()}
                    >
                      {lichessLink ? "Update" : "Save"}
                    </Button>
                  </div>
                </details>

                {lichessNotice ? (
                  <p className={`profile-notice profile-notice--${lichessNotice.kind}`}>{lichessNotice.text}</p>
                ) : null}
              </GlassPanel>
            </div>
          </div>
        ) : (
          <GlassPanel as="section" className="profile-state-card" variant="strong">
            <h2>Profile not available</h2>
            <p>We could not load your profile information yet.</p>
            <div className="profile-actions-row">
              <Button variant="secondary" onClick={onBack}>Back</Button>
            </div>
          </GlassPanel>
        )}
      </div>
    </main>
  );
}
