import { useEffect, useState } from "react";
import type { UserProfileResponse } from "../api/userServiceTypes";
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

export default function LichessHubPage({ profile, onOpenSettings, onBack }: LichessHubPageProps) {
  const [bridgeStatus, setBridgeStatus] = useState<LichessBridgeStatusResponse | null>(null);
  const [bridgePolicy, setBridgePolicy] = useState<LichessBridgePolicyResponse | null>(null);
  const [bridgeLoading, setBridgeLoading] = useState(true);
  const [bridgeError, setBridgeError] = useState(false);

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
  const isAccepting = bridgeStatus?.workerRunning === true && bridgeStatus?.acceptChallenges === true;

  return (
    <div className="lichess-hub-page">
      <section className="panel lichess-hub-panel">

        <header className="lichess-hub-header">
          <div className="lichess-hub-header-text">
            <h1>Play on Lichess</h1>
            <p className="lichess-hub-subtitle">
              Challenge the Searchess Bot today. Future Lichess OAuth features will appear here.
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
                <p className="lichess-hub-capability">Capability: identity only</p>
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

          {/* ── Future: Play with my Lichess account ──────────────────────── */}
          <section className="lichess-hub-card" aria-label="Play with my Lichess account">
            <div className="lichess-hub-card-header">
              <h2>Play with my Lichess account</h2>
              <span className="lichess-hub-badge">Coming Soon</span>
            </div>
            <p className="lichess-hub-muted">
              Create or track Lichess games from Searchess using your own Lichess account and
              future OAuth permissions.
            </p>
            <p className="lichess-hub-muted">
              Your moves, your games — Searchess AI will not play under your personal account.
            </p>
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
