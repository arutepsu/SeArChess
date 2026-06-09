import { useEffect, useState } from "react";
import type { UserProfileResponse, LichessLinkCapability } from "../api/userServiceTypes";
import { createSearchessBotChallenge, upgradeLichessLink } from "../api/userServiceClient";
import {
  LICHESS_BOT_USERNAME,
  LICHESS_BOT_PROFILE_URL,
  type LichessBridgeStatusResponse,
  type LichessBridgePolicyResponse,
  getLichessBridgeStatus,
  getLichessBridgePolicy,
  deriveBridgeHealth,
} from "../api/lichessBridgeClient";
import "./LichessHubPage.css";

interface LichessHubPageProps {
  profile: UserProfileResponse | null;
  onOpenSettings: () => void;
  onBack: () => void;
}

function formatClock(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return s === 0 ? `${m}:00` : `${m}:${String(s).padStart(2, "0")}`;
}

function capitalize(s: string): string {
  return s.length === 0 ? s : s[0].toUpperCase() + s.slice(1);
}

function capabilityLabel(cap: LichessLinkCapability): string {
  switch (cap) {
    case "identity_only":   return "Identity only";
    case "manual_dev":      return "Manual dev link";
    case "challenge_ready": return "Challenge ready";
    case "board_play":      return "Board play";
    case "expired":         return "Expired";
    case "revoked":         return "Revoked";
    case "unknown":         return "Unknown";
  }
}

function challengeErrorMessage(error: unknown): string {
  const rawMessage = error instanceof Error ? error.message : String(error);
  let code = rawMessage.trim();

  try {
    const parsed = JSON.parse(rawMessage) as { code?: unknown };
    if (typeof parsed.code === "string") {
      code = parsed.code;
    }
  } catch {
    const match = rawMessage.match(
      /\b(NO_LICHESS_LINK|NO_CHALLENGE_READY_CAPABILITY|NO_STORED_LICHESS_TOKEN|TOKEN_ENCRYPTION_NOT_CONFIGURED|LICHESS_TOKEN_EXPIRED|INVALID_CHALLENGE_REQUEST|LICHESS_CHALLENGE_FAILED)\b/
    );
    if (match !== null) {
      code = match[1];
    }
  }

  switch (code) {
    case "NO_LICHESS_LINK":
      return "Link your Lichess account first.";
    case "NO_CHALLENGE_READY_CAPABILITY":
      return "Upgrade your Lichess permissions before creating a challenge.";
    case "NO_STORED_LICHESS_TOKEN":
      return "Your Lichess authorization is incomplete. Please re-authorize.";
    case "TOKEN_ENCRYPTION_NOT_CONFIGURED":
      return "The server is not configured for Lichess challenge creation yet.";
    case "LICHESS_TOKEN_EXPIRED":
      return "Your Lichess authorization expired. Please re-authorize.";
    case "INVALID_CHALLENGE_REQUEST":
      return "The challenge settings are invalid.";
    case "LICHESS_CHALLENGE_FAILED":
      return "Lichess could not create the challenge. Please try again.";
    default:
      return "Could not create the Lichess challenge.";
  }
}

export default function LichessHubPage({ profile, onOpenSettings, onBack }: LichessHubPageProps) {
  const [bridgeStatus, setBridgeStatus] = useState<LichessBridgeStatusResponse | null>(null);
  const [bridgePolicy, setBridgePolicy] = useState<LichessBridgePolicyResponse | null>(null);
  const [bridgeLoading, setBridgeLoading] = useState(true);
  const [bridgeError, setBridgeError] = useState(false);
  const [isUpgrading, setIsUpgrading] = useState(false);
  const [upgradeError, setUpgradeError] = useState<string | null>(null);
  const [isCreatingChallenge, setIsCreatingChallenge] = useState(false);
  const [challengeError, setChallengeError] = useState<string | null>(null);
  const [createdChallengeUrl, setCreatedChallengeUrl] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    Promise.all([getLichessBridgeStatus(), getLichessBridgePolicy()])
      .then(([status, policy]) => {
        if (!active) return;
        setBridgeStatus(status);
        setBridgePolicy(policy);
      })
      .catch(() => {
        if (!active) return;
        setBridgeError(true);
      })
      .finally(() => {
        if (active) setBridgeLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const lichessLink = profile?.links.find((link) => link.provider === "Lichess") ?? null;
  const bridgeHealth = deriveBridgeHealth(bridgeStatus, bridgeError);

  const handleUpgrade = async () => {
    setIsUpgrading(true);
    setUpgradeError(null);
    try {
      const { authorizationUrl } = await upgradeLichessLink("challenge_ready");
      window.location.href = authorizationUrl;
    } catch {
      setUpgradeError("Could not start Lichess upgrade. Please try again or check the server configuration.");
      setIsUpgrading(false);
    }
  };

  const handleCreateChallenge = async () => {
    setIsCreatingChallenge(true);
    setChallengeError(null);
    setCreatedChallengeUrl(null);

    try {
      const response = await createSearchessBotChallenge();
      setCreatedChallengeUrl(response.url);
      window.open(response.url, "_blank", "noopener,noreferrer");
    } catch (error) {
      setChallengeError(challengeErrorMessage(error));
    } finally {
      setIsCreatingChallenge(false);
    }
  };

  const isAccepting = bridgeStatus?.workerRunning === true && bridgeStatus?.acceptChallenges === true;

  return (
    <div className="lichess-hub-page">
      <section className="panel lichess-hub-panel">

        <header className="lichess-hub-header">
          <div className="lichess-hub-header-text">
            <h1>Play on Lichess</h1>
            <p className="lichess-hub-subtitle">
              Challenge the Searchess Bot on Lichess today. You can also upgrade your linked Lichess account for upcoming Searchess-created challenge flows.
            </p>
          </div>
          <div className="lichess-hub-actions">
            <button type="button" onClick={onBack}>← Back to Menu</button>
            <button type="button" onClick={onOpenSettings}>Settings</button>
          </div>
        </header>

        <div className="lichess-hub-grid">

          {/* ── Linked Lichess account ─────────────────────────────────────── */}
          <section className="lichess-hub-card" aria-label="Linked Lichess account">
            <h2>Linked Lichess account</h2>

            {profile === null ? (
              <p className="lichess-hub-muted">Loading linked account…</p>
            ) : lichessLink === null ? (
              <>
                <p className="lichess-hub-warning">
                  No Lichess account linked. Link your Lichess account in Settings before
                  challenging the Searchess Bot.
                </p>
                <button type="button" onClick={onOpenSettings}>Go to Settings</button>
              </>
            ) : lichessLink.verificationSource === "ManualDev" ? (
              <>
                <p className="lichess-hub-username">@{lichessLink.externalUsername}</p>
                <span className="lichess-hub-badge lichess-hub-badge--degraded">Unverified dev link</span>
                <p className="lichess-hub-muted">
                  This link is useful for development, but OAuth verification is recommended
                  for live Lichess features.
                </p>
              </>
            ) : (
              <>
                <p className="lichess-hub-username">@{lichessLink.externalUsername}</p>
                <span className="lichess-hub-badge lichess-hub-badge--healthy">Verified via Lichess OAuth</span>
                <p className="lichess-hub-muted">
                  Linked {new Date(lichessLink.linkedAt).toLocaleDateString()}
                </p>
                <p className="lichess-hub-capability">Capability: {capabilityLabel(lichessLink.capability)}</p>
              </>
            )}
          </section>

          {/* ── Challenge Searchess Bot ────────────────────────────────────── */}
          <section className="lichess-hub-card lichess-hub-card--primary" aria-label="Challenge Searchess Bot on Lichess">
            <h2>Challenge Searchess Bot on Lichess</h2>

            <div className="lichess-hub-bot-identity">
              <span className="lichess-hub-muted">Searchess BOT account:</span>
              <strong>{LICHESS_BOT_USERNAME}</strong>
              <a
                href={LICHESS_BOT_PROFILE_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="lichess-hub-link"
              >
                View on Lichess ↗
              </a>
            </div>

            <div className="lichess-hub-status-row">
              {bridgeLoading ? (
                <span className="lichess-hub-badge lichess-hub-badge--unknown">Loading…</span>
              ) : (
                <span className={`lichess-hub-badge lichess-hub-badge--${bridgeHealth.variant}`}>
                  {bridgeHealth.label}
                </span>
              )}
              {bridgeStatus !== null && (
                <span className="lichess-hub-muted">
                  Active games: {bridgeStatus.activeGamesCount} / {bridgeStatus.maxConcurrentGames}
                </span>
              )}
            </div>

            {bridgePolicy !== null && (
              <dl className="lichess-hub-policy">
                <dt>Rating mode</dt>
                <dd>{bridgePolicy.acceptRated ? "Rated and unrated" : "Unrated only"}</dd>
                <dt>Variant</dt>
                <dd>
                  {bridgePolicy.allowedVariants.length === 1
                    ? `${capitalize(bridgePolicy.allowedVariants[0])} only`
                    : bridgePolicy.allowedVariants.map(capitalize).join(", ") || "Any"}
                </dd>
                <dt>Clock range</dt>
                <dd>{formatClock(bridgePolicy.minClockSeconds)} – {formatClock(bridgePolicy.maxClockSeconds)}</dd>
              </dl>
            )}

            {bridgeStatus !== null && !isAccepting && (
              <p className="lichess-hub-warning">
                The Searchess Bot is not accepting challenges right now.
              </p>
            )}

            {lichessLink !== null ? (
              <ol className="lichess-hub-steps">
                <li>Sign in on Lichess as @{lichessLink.externalUsername}.</li>
                <li>
                  Open {LICHESS_BOT_USERNAME}'s profile on Lichess.{" "}
                  <a
                    href={LICHESS_BOT_PROFILE_URL}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="lichess-hub-link"
                  >
                    Open profile ↗
                  </a>
                </li>
                <li>Click Challenge.</li>
                <li>
                  Choose Unrated, Standard chess, and a clock between{" "}
                  {bridgePolicy !== null
                    ? `${formatClock(bridgePolicy.minClockSeconds)} and ${formatClock(bridgePolicy.maxClockSeconds)}`
                    : "3:00 and 10:00"}.
                </li>
                <li>
                  Send the challenge. Searchess accepts automatically when your linked account
                  matches.
                </li>
              </ol>
            ) : (
              <p className="lichess-hub-muted">
                Link your Lichess account in Settings first. Then challenge {LICHESS_BOT_USERNAME}{" "}
                from that account on Lichess.
              </p>
            )}
          </section>

          {/* ── Play with my Lichess account ──────────────────────────────── */}
          <section className="lichess-hub-card" aria-label="Play with my Lichess account">
            <h2>Play with my Lichess account</h2>

            {profile === null ? (
              <p className="lichess-hub-muted">Loading…</p>
            ) : lichessLink === null ? (
              <>
                <p className="lichess-hub-muted">Link your Lichess account first.</p>
                <button type="button" onClick={onOpenSettings}>Go to Settings</button>
              </>
            ) : lichessLink.capability === "manual_dev" ? (
              <>
                <p className="lichess-hub-muted">
                  ManualDev links cannot be upgraded. Link through Lichess OAuth in Settings.
                </p>
                <button type="button" onClick={onOpenSettings}>Go to Settings</button>
              </>
            ) : lichessLink.capability === "identity_only" ? (
              <>
                <p className="lichess-hub-muted">
                  Your Lichess identity is verified, but Searchess does not yet have permission
                  to create challenges for you.
                </p>
                {upgradeError !== null && (
                  <p className="lichess-hub-warning">{upgradeError}</p>
                )}
                <button
                  type="button"
                  disabled={isUpgrading}
                  onClick={() => void handleUpgrade()}
                >
                  {isUpgrading ? "Redirecting…" : "Upgrade to Challenge-Ready"}
                </button>
              </>
            ) : lichessLink.capability === "challenge_ready" ? (
              <>
                <p className="lichess-hub-muted">
                  Ready to create a Lichess challenge against the Searchess BOT account.
                </p>
                <p className="lichess-hub-muted">
                  The game will open on lichess.org. Searchess will control arutepsu2 through
                  the BOT bridge.
                </p>
                {challengeError !== null && (
                  <p className="lichess-hub-warning">{challengeError}</p>
                )}
                <button
                  type="button"
                  disabled={isCreatingChallenge}
                  onClick={() => void handleCreateChallenge()}
                >
                  {isCreatingChallenge ? "Creating..." : "Create Lichess Challenge"}
                </button>
                {createdChallengeUrl !== null && (
                  <p className="lichess-hub-muted">
                    Challenge created.{" "}
                    <a
                      href={createdChallengeUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="lichess-hub-link"
                    >
                      Open on Lichess ↗
                    </a>
                  </p>
                )}
              </>
            ) : lichessLink.capability === "expired" || lichessLink.capability === "revoked" ? (
              <>
                <p className="lichess-hub-warning">Your Lichess authorization needs to be renewed.</p>
                {upgradeError !== null && (
                  <p className="lichess-hub-warning">{upgradeError}</p>
                )}
                <button
                  type="button"
                  disabled={isUpgrading}
                  onClick={() => void handleUpgrade()}
                >
                  {isUpgrading ? "Redirecting…" : "Re-authorize Lichess"}
                </button>
              </>
            ) : lichessLink.capability === "unknown" ? (
              <>
                <p className="lichess-hub-muted">
                  Lichess link status is unknown. Re-link or upgrade your account.
                </p>
                {upgradeError !== null && (
                  <p className="lichess-hub-warning">{upgradeError}</p>
                )}
                <button
                  type="button"
                  disabled={isUpgrading}
                  onClick={() => void handleUpgrade()}
                >
                  {isUpgrading ? "Redirecting…" : "Upgrade to Challenge-Ready"}
                </button>
              </>
            ) : lichessLink.capability === "board_play" ? (
              <p className="lichess-hub-muted">
                Board-play authorization detected, but in-Searchess Lichess board is not
                implemented yet.
              </p>
            ) : null}
          </section>

          {/* ── Future: Searchess Bot matches ─────────────────────────────── */}
          <section className="lichess-hub-card" aria-label="Searchess Bot matches">
            <div className="lichess-hub-card-header">
              <h2>Searchess Bot matches</h2>
              <span className="lichess-hub-badge">Coming Soon</span>
            </div>
            <p className="lichess-hub-muted">
              Future operator/demo mode for Searchess Bot games against Lichess AI or other BOT
              accounts.
            </p>
            <p className="lichess-hub-muted">
              This uses the Searchess BOT account, not your personal Lichess account.
            </p>
          </section>

        </div>
      </section>
    </div>
  );
}
