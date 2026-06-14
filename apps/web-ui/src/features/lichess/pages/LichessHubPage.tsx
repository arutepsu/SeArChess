import { useEffect, useState } from "react";
import type { LichessActiveGameSummary, UserProfileResponse, LichessLinkCapability } from "../../../api/userServiceTypes";
import { createSearchessBotChallenge, getActiveLichessGames, upgradeLichessLink } from "../../../api/userServiceClient";
import {
  LICHESS_BOT_USERNAME,
  LICHESS_BOT_PROFILE_URL,
  type LichessBridgeStatusResponse,
  type LichessBridgePolicyResponse,
  getLichessBridgeStatus,
  getLichessBridgePolicy,
  deriveBridgeHealth,
} from "../../../api/lichessBridgeClient";
import { challengeErrorMessage } from "../../../utils/lichessErrors";
import { formatClockSeconds } from "../../../utils/timeFormat";
import Button from "../../../components/ui/Button";
import "./LichessHubPage.css";

interface LichessHubPageProps {
  profile: UserProfileResponse | null;
  onOpenSettings: () => void;
  onOpenLichessGame: (gameId: string) => void;
  onBack: () => void;
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

function deriveGameIdFromLichessUrl(url: string): string | null {
  try {
    const parsed = new URL(url);
    if (parsed.hostname !== "lichess.org" && !parsed.hostname.endsWith(".lichess.org")) {
      return null;
    }
    const firstSegment = parsed.pathname.split("/").filter(Boolean)[0] ?? "";
    return /^[A-Za-z0-9_-]{4,32}$/.test(firstSegment) ? firstSegment : null;
  } catch {
    return null;
  }
}

export default function LichessHubPage({ profile, onOpenSettings, onOpenLichessGame, onBack }: LichessHubPageProps) {
  const [bridgeStatus, setBridgeStatus] = useState<LichessBridgeStatusResponse | null>(null);
  const [bridgePolicy, setBridgePolicy] = useState<LichessBridgePolicyResponse | null>(null);
  const [bridgeLoading, setBridgeLoading] = useState(true);
  const [bridgeError, setBridgeError] = useState(false);
  const [isUpgrading, setIsUpgrading] = useState(false);
  const [upgradeError, setUpgradeError] = useState<string | null>(null);
  const [isCreatingChallenge, setIsCreatingChallenge] = useState(false);
  const [challengeError, setChallengeError] = useState<string | null>(null);
  const [createdChallengeUrl, setCreatedChallengeUrl] = useState<string | null>(null);
  const [createdGameId, setCreatedGameId] = useState<string | null>(null);
  const [activeGames, setActiveGames] = useState<LichessActiveGameSummary[] | null>(null);
  const [activeGamesLoading, setActiveGamesLoading] = useState(false);
  const [activeGamesError, setActiveGamesError] = useState<string | null>(null);

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
  const capability  = lichessLink?.capability;

  useEffect(() => {
    if (capability !== "challenge_ready") return;
    let active = true;
    setActiveGamesLoading(true);
    setActiveGamesError(null);
    getActiveLichessGames()
      .then((response) => {
        if (!active) return;
        setActiveGames(response.games);
      })
      .catch(() => {
        if (!active) return;
        setActiveGamesError("Could not check for active games. You can still create a new challenge.");
      })
      .finally(() => {
        if (active) setActiveGamesLoading(false);
      });
    return () => {
      active = false;
    };
  }, [capability]);

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
    setCreatedGameId(null);

    try {
      const response = await createSearchessBotChallenge();
      setCreatedChallengeUrl(response.url);
      setCreatedGameId(deriveGameIdFromLichessUrl(response.url));
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
            <Button onClick={onBack}>← Back to Menu</Button>
            <Button onClick={onOpenSettings}>Settings</Button>
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
                <Button onClick={onOpenSettings}>Go to Settings</Button>
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
                <dd>{formatClockSeconds(bridgePolicy.minClockSeconds)} – {formatClockSeconds(bridgePolicy.maxClockSeconds)}</dd>
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
                    ? `${formatClockSeconds(bridgePolicy.minClockSeconds)} and ${formatClockSeconds(bridgePolicy.maxClockSeconds)}`
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
                <Button onClick={onOpenSettings}>Go to Settings</Button>
              </>
            ) : lichessLink.capability === "manual_dev" ? (
              <>
                <p className="lichess-hub-muted">
                  ManualDev links cannot be upgraded. Link through Lichess OAuth in Settings.
                </p>
                <Button onClick={onOpenSettings}>Go to Settings</Button>
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
                <Button
                  disabled={isUpgrading}
                  onClick={() => void handleUpgrade()}
                >
                  {isUpgrading ? "Redirecting…" : "Upgrade to Challenge-Ready"}
                </Button>
              </>
            ) : lichessLink.capability === "challenge_ready" ? (
              <>
                {/* ── Active game check ─────────────────────────────────── */}
                {activeGamesLoading && (
                  <p className="lichess-hub-muted">Checking active Lichess games…</p>
                )}

                {!activeGamesLoading && activeGamesError !== null && (
                  <p className="lichess-hub-warning">{activeGamesError}</p>
                )}

                {!activeGamesLoading && activeGames !== null && activeGames.length > 0 ? (
                  /* Active game found */
                  <div className="lichess-hub-active-game">
                    <div className="lichess-hub-card-header">
                      <span className="lichess-hub-badge lichess-hub-badge--healthy">Active game found</span>
                    </div>
                    {activeGames.map((game) => (
                      <div key={game.gameId} className="lichess-hub-active-game-entry">
                        <p className="lichess-hub-muted">
                          {game.white.username} vs {game.black.username}
                          {game.userColor != null ? ` · You play as ${game.userColor}` : ""}
                        </p>
                        <Button onClick={() => onOpenLichessGame(game.gameId)}>
                          Continue in Searchess
                        </Button>
                        <a
                          href={game.url}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="lichess-hub-link"
                        >
                          Open on Lichess ↗
                        </a>
                      </div>
                    ))}
                  </div>
                ) : !activeGamesLoading ? (
                  /* No active game — show challenge creation */
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
                    <Button
                      disabled={isCreatingChallenge}
                      onClick={() => void handleCreateChallenge()}
                    >
                      {isCreatingChallenge ? "Creating..." : "Create Lichess Challenge"}
                    </Button>
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
                    {createdGameId !== null && (
                      <Button onClick={() => onOpenLichessGame(createdGameId)}>
                        Open in Searchess
                      </Button>
                    )}
                  </>
                ) : null}
              </>
            ) : lichessLink.capability === "expired" || lichessLink.capability === "revoked" ? (
              <>
                <p className="lichess-hub-warning">Your Lichess authorization needs to be renewed.</p>
                {upgradeError !== null && (
                  <p className="lichess-hub-warning">{upgradeError}</p>
                )}
                <Button
                  disabled={isUpgrading}
                  onClick={() => void handleUpgrade()}
                >
                  {isUpgrading ? "Redirecting…" : "Re-authorize Lichess"}
                </Button>
              </>
            ) : lichessLink.capability === "unknown" ? (
              <>
                <p className="lichess-hub-muted">
                  Lichess link status is unknown. Re-link or upgrade your account.
                </p>
                {upgradeError !== null && (
                  <p className="lichess-hub-warning">{upgradeError}</p>
                )}
                <Button
                  disabled={isUpgrading}
                  onClick={() => void handleUpgrade()}
                >
                  {isUpgrading ? "Redirecting…" : "Upgrade to Challenge-Ready"}
                </Button>
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
