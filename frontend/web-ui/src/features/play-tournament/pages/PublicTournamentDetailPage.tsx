import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  addPublicTournamentParticipant,
  deletePublicTournament,
  getPublicTournament,
  listPublicBots,
  startPublicTournament,
} from "../../../api/publicTournamentClient";
import { getMyTournamentBots } from "../../../api/userServiceClient";
import type { PublicRegisteredBot, PublicResult, PublicTournament } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import { canDirectPublicTournaments, canManagePublicTournaments } from "../utils/publicTournamentAuth";
import "./PlayTournament.css";

// ── Utilities ─────────────────────────────────────────────────────────────────

function pct(w: number, total: number): string {
  if (total === 0) return "0%";
  return `${((w / total) * 100).toFixed(1)}%`;
}

function formatClock(limitSec: number, increment: number): string {
  const min = Math.floor(limitSec / 60);
  const sec = limitSec % 60;
  const base = sec === 0 ? `${min}m` : `${min}m${sec}s`;
  return increment > 0 ? `${base}+${increment}s` : base;
}

// ── Common sub-components ──────────────────────────────────────────────────────

function StatusBadge({ status }: { status: PublicTournament["status"] }) {
  return (
    <span className={`play-tournament-badge play-tournament-badge--${status}`}>
      {status === "created" ? "Waiting" : status === "started" ? "In Progress" : "Finished"}
    </span>
  );
}

function InfoGrid({ tournament }: { tournament: PublicTournament }) {
  const items: Array<[string, string]> = [
    ["Format", tournament.format],
    ["Clock", formatClock(tournament.clock.limit, tournament.clock.increment)],
    ["Rounds", `${tournament.nbRounds}`],
    ["Bots", `${tournament.nbPlayers}`],
    ["Round", `${tournament.round}`],
    ["Rated", tournament.rated ? "Yes" : "No"],
    ["Start position", tournament.startPosition === "standard" ? "Standard" : tournament.startPosition],
  ];
  if (tournament.winner) items.push(["Winner", tournament.winner.name]);
  return (
    <div className="play-tournament-info-grid">
      {items.map(([label, value]) => (
        <div key={label} className="play-tournament-info-item">
          <p className="play-tournament-info-label">{label}</p>
          <p className="play-tournament-info-value">{value}</p>
        </div>
      ))}
    </div>
  );
}

function StandingsTable({ results }: { results: PublicResult[] }) {
  if (results.length === 0)
    return <p className="play-tournament-empty">No standings yet.</p>;
  return (
    <table className="play-tournament-table">
      <thead>
        <tr>
          <th>#</th>
          <th>Bot</th>
          <th className="play-tournament-table-num">Score</th>
          <th className="play-tournament-table-num">Games</th>
          <th className="play-tournament-table-num">W</th>
          <th className="play-tournament-table-num">D</th>
          <th className="play-tournament-table-num">L</th>
          <th className="play-tournament-table-num">Win %</th>
          <th className="play-tournament-table-num">Tiebreak</th>
        </tr>
      </thead>
      <tbody>
        {results.map((r) => (
          <tr key={r.bot.id}>
            <td className="play-tournament-table-num">{r.rank}</td>
            <td className="play-tournament-table-name">{r.bot.name}</td>
            <td className="play-tournament-table-num">{r.points}</td>
            <td className="play-tournament-table-num">{r.nbGames}</td>
            <td className="play-tournament-table-num">{r.wins}</td>
            <td className="play-tournament-table-num">{r.draws}</td>
            <td className="play-tournament-table-num">{r.losses}</td>
            <td className="play-tournament-table-num">{pct(r.wins, r.nbGames)}</td>
            <td className="play-tournament-table-num">{r.tieBreak.toFixed(2)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ── Participants section (created tournaments) ─────────────────────────────────

function ParticipantsSection({
  tournament,
  participants,
  ownedIds,
}: {
  tournament: PublicTournament;
  participants: PublicResult[];
  ownedIds: Set<string>;
}) {
  // nbPlayers from the tournament info is the authoritative count.
  // participants (standing.results) may or may not be populated for the "created" state
  // depending on the Tournament Server — if empty, fall back to the count only.
  const nbJoined = participants.length > 0 ? participants.length : tournament.nbPlayers;
  const hasEnough = nbJoined >= 2;

  return (
    <SectionCard title={`Participants (${nbJoined})`}>
      {participants.length > 0 ? (
        <div className="pt-bot-list">
          {participants.map((r) => {
            const isOwned = ownedIds.has(r.bot.id);
            return (
              <div key={r.bot.id} className={`pt-bot-item${isOwned ? " pt-bot-item--owned" : ""}`}>
                <span className="pt-bot-name">{r.bot.name}</span>
                <span className="pt-bot-meta" style={{ flex: "1 1 auto" }} />
                <span className={`pt-bot-badge ${isOwned ? "pt-bot-badge--own" : "pt-bot-badge--foreign"}`}>
                  {isOwned ? "Your bot" : "Other team"}
                </span>
              </div>
            );
          })}
        </div>
      ) : tournament.nbPlayers > 0 ? (
        <p className="play-tournament-info-label">
          {tournament.nbPlayers} bot{tournament.nbPlayers !== 1 ? "s" : ""} joined.
          Participant list will be visible once the tournament starts.
        </p>
      ) : (
        <p className="play-tournament-empty">No bots have joined yet.</p>
      )}

      <p className={`pt-detail-readiness${hasEnough ? " pt-detail-readiness--ready" : " pt-detail-readiness--waiting"}`}>
        {hasEnough ? "Ready to start." : "At least 2 bots are required to start."}
      </p>
    </SectionCard>
  );
}

// ── Join section (created tournaments, authenticated users) ────────────────────

function JoinSection({
  id,
  tournament,
  bots,
  ownedIds,
  participants,
  onRefresh,
}: {
  id: string;
  tournament: PublicTournament;
  bots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  participants: PublicResult[];
  onRefresh: () => void;
}) {
  const navigate = useNavigate();
  const [selectedBotId, setSelectedBotId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  const joinedIds     = new Set(participants.map((r) => r.bot.id));
  const ownedBots     = bots.filter((b) => ownedIds.has(b.id));
  const joinableBots  = ownedBots.filter((b) => !joinedIds.has(b.id));
  const alreadyJoined = ownedBots.filter((b) => joinedIds.has(b.id));
  const opponents     = participants.filter((r) => !ownedIds.has(r.bot.id));

  // Format-specific opponent hint
  const isRoundRobin = tournament.format === "league" || tournament.format === "groupStage";
  const opponentsNote = isRoundRobin
    ? "In this format your bot plays every listed participant."
    : "Exact pairings are decided when rounds are generated.";

  if (ownedBots.length === 0) {
    return (
      <SectionCard title="Join with one of your bots">
        <p className="play-tournament-info-label">
          You have no registered bots yet. Go to the{" "}
          <button type="button" className="pt-inline-link" onClick={() => navigate("/play-tournament")}>
            tournament lobby
          </button>
          {" "}to add a Searchess bot before joining.
        </p>
      </SectionCard>
    );
  }

  async function handleJoin() {
    if (!selectedBotId) return;
    setBusy(true);
    setError(null);
    setSuccess(null);
    try {
      await addPublicTournamentParticipant(id, selectedBotId);
      setSelectedBotId("");
      setSuccess("Bot joined successfully!");
      onRefresh();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to join tournament.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard title="Join with one of your bots">
      {alreadyJoined.length > 0 && (
        <p className="play-tournament-info-label" style={{ marginBottom: 12 }}>
          Already joined: {alreadyJoined.map((b) => b.name).join(", ")}.
        </p>
      )}

      {error && <ErrorState message={error} />}
      {success && <p className="pt-join-success">{success}</p>}

      {joinableBots.length === 0 ? (
        <p className="play-tournament-info-label">All your bots have already joined this tournament.</p>
      ) : (
        <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
          <select
            className="play-tournament-form-select"
            value={selectedBotId}
            onChange={(e) => { setSelectedBotId(e.target.value); setSuccess(null); }}
            disabled={busy}
            style={{ minWidth: 180 }}
          >
            <option value="">Select your bot…</option>
            {joinableBots.map((b) => (
              <option key={b.id} value={b.id}>{b.name}</option>
            ))}
          </select>
          <Button
            variant="primary"
            size="sm"
            disabled={busy || !selectedBotId}
            onClick={() => { void handleJoin(); }}
          >
            {busy ? "Joining…" : "Join tournament"}
          </Button>
        </div>
      )}

      {participants.length === 0 && joinableBots.length > 0 && (
        <p className="play-tournament-info-label" style={{ marginTop: 10 }}>
          Be the first to join. At least 2 bots are needed to start.
        </p>
      )}

      {participants.length === 1 && joinableBots.length > 0 && (
        <p className="play-tournament-info-label" style={{ marginTop: 10 }}>
          One more bot is needed. Joining would make the tournament ready to start.
        </p>
      )}

      {opponents.length > 0 && (
        <div className="pt-opponents-section">
          <p className="pt-bot-section-label">Possible opponents after start</p>
          <p className="play-tournament-info-label" style={{ marginBottom: 8 }}>{opponentsNote}</p>
          <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
            {opponents.map((r) => (
              <span key={r.bot.id} className="pt-bot-badge pt-bot-badge--foreign">{r.bot.name}</span>
            ))}
          </div>
        </div>
      )}

      {participants.length === 1 && joinableBots.length === 0 && (
        <p className="play-tournament-info-label" style={{ marginTop: 10 }}>
          No opponent yet — one more bot must join before the tournament can start.
        </p>
      )}
    </SectionCard>
  );
}

// ── Director actions (created tournaments) ─────────────────────────────────────

function DirectorActions({
  id,
  tournament,
  onRefresh,
}: {
  id: string;
  tournament: PublicTournament;
  onRefresh: () => void;
}) {
  const navigate = useNavigate();
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const canStart = tournament.nbPlayers >= 2;

  async function handleStart() {
    setBusy(true);
    setActionError(null);
    try {
      await startPublicTournament(id);
      onRefresh();
    } catch (e: unknown) {
      setActionError(e instanceof Error ? e.message : "Failed to start tournament.");
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete() {
    if (!window.confirm("Delete this tournament? This cannot be undone.")) return;
    setBusy(true);
    setActionError(null);
    try {
      await deletePublicTournament(id);
      navigate("/play-tournament");
    } catch (e: unknown) {
      setActionError(e instanceof Error ? e.message : "Failed to delete tournament.");
      setBusy(false);
    }
  }

  return (
    <SectionCard title="Director Actions">
      <p className="play-tournament-info-label" style={{ marginBottom: 12 }}>
        Only the tournament director can start or delete this tournament.
      </p>
      {actionError && <ErrorState message={actionError} />}
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
        <Button
          variant="primary"
          size="sm"
          disabled={busy || !canStart}
          onClick={() => { void handleStart(); }}
        >
          Start tournament
        </Button>
        <Button
          variant="secondary"
          size="sm"
          disabled={busy}
          onClick={() => { void handleDelete(); }}
        >
          Delete
        </Button>
      </div>
      {!canStart && (
        <p className="play-tournament-info-label" style={{ marginTop: 10 }}>
          At least 2 bots must join before the tournament can start.
        </p>
      )}
    </SectionCard>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function PublicTournamentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [tournament, setTournament] = useState<PublicTournament | null>(null);
  const [bots, setBots]             = useState<PublicRegisteredBot[]>([]);
  const [ownedIds, setOwnedIds]     = useState<Set<string>>(new Set());
  const [loading, setLoading]       = useState(true);
  const [error, setError]           = useState<string | null>(null);
  const canManage = canManagePublicTournaments();
  const canDirect = canDirectPublicTournaments();

  function loadTournament(active: { value: boolean }) {
    if (!id) return;
    setLoading(true);
    setError(null);
    getPublicTournament(id)
      .then((t) => { if (active.value) setTournament(t); })
      .catch((e: unknown) => {
        if (active.value)
          setError(e instanceof Error ? e.message : "Failed to load tournament");
      })
      .finally(() => { if (active.value) setLoading(false); });
  }

  useEffect(() => {
    const active = { value: true };
    loadTournament(active);
    listPublicBots()
      .then((b) => { if (active.value) setBots(b); })
      .catch(() => { /* bots list is optional */ });
    if (canManage) {
      getMyTournamentBots()
        .then((owned) => { if (active.value) setOwnedIds(new Set(owned.map((o) => o.botId))); })
        .catch(() => { /* ownership list is optional */ });
    }
    return () => { active.value = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  function handleRefresh() {
    const active = { value: true };
    loadTournament(active);
  }

  const results = tournament?.standing?.results ?? [];

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">

        {/* ── Header ─────────────────────────────────────────────────────────── */}
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">{tournament?.fullName ?? "Tournament"}</h1>
            {tournament && (
              <p className="play-tournament-subtitle">
                <StatusBadge status={tournament.status} />
              </p>
            )}
          </div>
          <div style={{ display: "flex", gap: "10px", flexWrap: "wrap", flexShrink: 0 }}>
            {tournament?.status === "started" && (
              <Button
                variant="primary"
                size="sm"
                onClick={() => navigate(`/play-tournament/${id ?? ""}/live`)}
              >
                Watch live
              </Button>
            )}
            {tournament?.status === "finished" && (
              <>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => navigate(`/play-tournament/${id ?? ""}/results`)}
                >
                  Full results
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => navigate(`/play-tournament/${id ?? ""}/analytics`)}
                >
                  Analytics
                </Button>
              </>
            )}
            <Button variant="secondary" size="sm" onClick={() => navigate("/play-tournament")}>
              ← Tournaments
            </Button>
          </div>
        </div>

        {loading && <LoadingState message="Loading tournament…" />}
        {error && <ErrorState message={error} />}

        {tournament && !loading && (
          <>
            <InfoGrid tournament={tournament} />

            {/* ── Waiting tournament sections ──────────────────────────────── */}
            {tournament.status === "created" && (
              <>
                <ParticipantsSection
                  tournament={tournament}
                  participants={results}
                  ownedIds={ownedIds}
                />

                {canManage ? (
                  <>
                    <JoinSection
                      id={id ?? ""}
                      tournament={tournament}
                      bots={bots}
                      ownedIds={ownedIds}
                      participants={results}
                      onRefresh={handleRefresh}
                    />
                    {canDirect ? (
                      <DirectorActions
                        id={id ?? ""}
                        tournament={tournament}
                        onRefresh={handleRefresh}
                      />
                    ) : (
                      <div className="pt-panel">
                        <p className="play-tournament-info-label">
                          Only the tournament director can start or delete this tournament.
                        </p>
                      </div>
                    )}
                  </>
                ) : (
                  <div className="pt-panel" style={{ marginTop: 0 }}>
                    <p className="play-tournament-info-label">
                      Sign in to join this tournament with your bots.
                      Only the tournament director can start or delete it.
                    </p>
                  </div>
                )}
              </>
            )}

            {/* ── In-progress / finished sections ─────────────────────────── */}
            {tournament.status !== "created" && tournament.round > 0 && (
              <SectionCard title="Rounds">
                <div className="pt-round-nav">
                  {Array.from({ length: tournament.round }, (_, i) => i + 1).map((n) => (
                    <Button
                      key={n}
                      variant="secondary"
                      size="sm"
                      onClick={() => navigate(`/play-tournament/${id ?? ""}/round/${n}`)}
                    >
                      Round {n}
                    </Button>
                  ))}
                </div>
              </SectionCard>
            )}

            {tournament.status !== "created" && (
              <SectionCard title="Standings">
                <StandingsTable results={results} />
              </SectionCard>
            )}
          </>
        )}
      </div>
    </div>
  );
}
