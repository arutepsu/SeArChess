import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  deletePublicTournament,
  getCurrentTournamentIdentity,
  getPublicTournament,
  joinPublicTournamentWithBot,
  startPublicTournament,
} from "../../../api/publicTournamentClient";
import {
  getMyProfile,
  getMyTournamentBots,
  getTournamentHost,
  getTournamentParticipants,
  recordTournamentParticipant,
} from "../../../api/userServiceClient";
import type { PublicResult, PublicTournament, TournamentIdentityResponse } from "../../../api/publicTournamentTypes";
import type { OwnedTournamentBot, TournamentHostInfo, TournamentParticipant, UserProfileResponse } from "../../../api/userServiceTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import { canDirectTournament, canManagePublicTournaments } from "../utils/publicTournamentAuth";
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
    ["Round", tournament.round > 0 ? `${tournament.round}` : "—"],
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

// ── Participants section (created/waiting tournaments) ─────────────────────────
// Uses the Searchess participant registry, not standing.results, which is empty
// until the tournament-server generates pairings.

function ParticipantsSection({
  tournament,
  participants,
  myUserId,
  ownedBotIds,
  hostDisplayName,
  isDirector,
  onRefresh,
}: {
  tournament: PublicTournament;
  participants: TournamentParticipant[];
  myUserId: string | null;
  ownedBotIds: Set<string>;
  hostDisplayName: string;
  isDirector: boolean;
  onRefresh: () => void;
}) {
  const nbServer    = tournament.nbPlayers;
  const nbSearchess = participants.length;
  // Ready when at least 1 Searchess-managed bot is present and total TS participants ≥ 2
  // (external bots count toward the 2-participant minimum).
  const hasEnough   = nbSearchess >= 1 && nbServer >= 2;
  // Bots the Tournament Server has that Searchess never joined (e.g. auto-added by TS on create).
  const tsBots        = tournament.standing?.results.map((r) => r.bot) ?? [];
  const managedBotIds = new Set(participants.map((p) => p.tournamentServerBotId));
  const unknownTsBots = tsBots.filter((b) => !managedBotIds.has(b.id));

  return (
    <SectionCard title={`Joined participants (${nbSearchess})`}>
      <div style={{ display: "flex", gap: 20, flexWrap: "wrap", marginBottom: 12 }}>
        <span className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.85rem" }}>
          Host: <strong style={{ color: "var(--text-primary, #e8e8f0)" }}>{hostDisplayName}</strong>
        </span>
        <span className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.85rem" }}>
          Searchess bots: <strong style={{ color: "var(--text-primary, #e8e8f0)" }}>{nbSearchess}</strong>
          {nbServer > nbSearchess ? ` · Total joined (incl. external): ${nbServer}` : ""}
        </span>
      </div>

      {participants.length > 0 ? (
        <div className="pt-bot-list">
          {participants.map((p) => {
            const isYou      = myUserId !== null && p.searchessUserId === myUserId;
            // Host badge: current user is the director and owns this participant's bot.
            // Guests see "Other user" for host bots since the director's Searchess userId
            // cannot be resolved from tournament.createdBy without a separate lookup.
            const isHost     = isDirector && p.searchessUserId === myUserId;
            const isOwnedBot = ownedBotIds.has(p.tournamentServerBotId);
            return (
              <div
                key={`${p.tournamentId}:${p.tournamentServerBotId}`}
                className={`pt-bot-item${isYou || isOwnedBot ? " pt-bot-item--owned" : ""}`}
              >
                <span className="pt-bot-name">{p.displayName}</span>
                <span className="pt-bot-meta">{p.tournamentServerBotName}</span>
                <span style={{ flex: "1 1 auto" }} />
                {isYou   && <span className="pt-bot-badge pt-bot-badge--own">You</span>}
                {isHost  && <span className="pt-bot-badge pt-bot-badge--searchess">Host</span>}
                {!isYou && !isHost && (
                  <span className="pt-bot-badge pt-bot-badge--foreign">Other user</span>
                )}
                {isOwnedBot && <span className="pt-bot-badge pt-bot-badge--own">Your bot</span>}
              </div>
            );
          })}
        </div>
      ) : nbServer > 0 ? (
        <p className="play-tournament-empty">
          {nbServer === 1
            ? "The Tournament Server reports 1 joined bot, but Searchess has not recorded its participant name yet."
            : `The Tournament Server reports ${nbServer} joined bots, but Searchess has not recorded their participant names yet.`}
        </p>
      ) : (
        <p className="play-tournament-empty">No bots have joined yet.</p>
      )}

      {unknownTsBots.length > 0 && (
        <div style={{ marginTop: 12, padding: "10px 12px", background: "rgba(99,102,241,0.06)", borderRadius: 6, border: "1px solid rgba(99,102,241,0.2)" }}>
          <p className="play-tournament-info-label" style={{ fontWeight: 600, textTransform: "none", fontSize: "0.8rem", margin: "0 0 4px" }}>
            External Tournament Server participants ({unknownTsBots.length})
          </p>
          <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.78rem", margin: "0 0 8px" }}>
            These bots joined directly through the public Tournament Server. Searchess shows their games and results but does not control their moves.
          </p>
          <div className="pt-bot-list">
            {unknownTsBots.map((b) => (
              <div key={b.id} className="pt-bot-item">
                <span className="pt-bot-name">{b.name}</span>
                <span className="pt-bot-meta">{b.id}</span>
                <span style={{ flex: "1 1 auto" }} />
                <span className="pt-bot-badge">External</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 14 }}>
        <p
          className={`pt-detail-readiness${hasEnough ? " pt-detail-readiness--ready" : " pt-detail-readiness--waiting"}`}
          style={{ margin: 0 }}
        >
          {hasEnough
            ? "Ready to start."
            : nbServer < 2
            ? "At least 2 bots must join the Tournament Server."
            : "At least 1 Searchess-managed bot must join."}
        </p>
        <Button variant="secondary" size="sm" onClick={onRefresh}>Refresh</Button>
      </div>
    </SectionCard>
  );
}

// ── Join section (created/waiting, authenticated users) ───────────────────────

function JoinSection({
  id,
  ownedBots,
  participants,
  tournamentServerUserId,
  onRefresh,
}: {
  id: string;
  ownedBots: OwnedTournamentBot[];
  participants: TournamentParticipant[];
  tournamentServerUserId: string | null;
  onRefresh: () => void;
}) {
  const navigate = useNavigate();
  const [selectedOwnershipId, setSelectedOwnershipId] = useState("");
  const [busy, setBusy]   = useState(false);
  const [error, setError]   = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // Ownership UUIDs already recorded as participants (primary key for join identity).
  const joinedOwnershipIds = new Set(
    participants.map((p) => p.searchessBotId).filter((id): id is string => id !== null && id !== undefined)
  );
  // Fallback: tournament-server bot IDs for participant records that predate ownership UUID tracking.
  const joinedTsBotIds = new Set(participants.map((p) => p.tournamentServerBotId));

  // A bot is joinable if it has not already been recorded for this tournament.
  // Multiple owned bots may join the same tournament.
  const joinableBots = ownedBots.filter((b) =>
    !joinedOwnershipIds.has(b.searchessBotId) &&
    !joinedTsBotIds.has(b.tournamentServerBotId)
  );
  const alreadyJoined = ownedBots.filter((b) =>
    joinedOwnershipIds.has(b.searchessBotId) || joinedTsBotIds.has(b.tournamentServerBotId)
  );

  if (ownedBots.length === 0) {
    return (
      <SectionCard title="Join with one of your bots">
        <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.875rem" }}>
          Add a Searchess bot first. Go to the{" "}
          <button type="button" className="pt-inline-link" onClick={() => navigate("/play-tournament")}>
            tournament lobby
          </button>
          {" "}to register a bot before joining.
        </p>
      </SectionCard>
    );
  }

  async function handleJoin() {
    if (!selectedOwnershipId) return;
    // Resolve by ownership UUID — the exact selected bot, not by name or display order.
    const bot = ownedBots.find((b) => b.searchessBotId === selectedOwnershipId);
    if (!bot) return;

    setBusy(true);
    setError(null);
    setSuccess(null);

    let alreadyJoined = false;
    try {
      const result = await joinPublicTournamentWithBot(id, {
        tournamentServerBotId:   bot.tournamentServerBotId,
        tournamentServerBotName: bot.tournamentServerBotName,
      });
      alreadyJoined = result.alreadyJoined;
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "Failed to join tournament.");
      setBusy(false);
      return;
    }

    // Always record participant after a successful or idempotent join.
    // This repairs the Searchess registry when the Tournament Server already has the bot
    // but the participant record was never created (e.g. previous session interrupted).
    try {
      await recordTournamentParticipant(id, {
        tournamentServerBotId:   bot.tournamentServerBotId,
        tournamentServerBotName: bot.tournamentServerBotName,
        tournamentServerUserId:  tournamentServerUserId ?? undefined,
      });
    } catch {
      // Non-critical — participant list may lag until a refresh.
    }

    setSelectedOwnershipId("");
    setSuccess(alreadyJoined
      ? "Bot was already joined — participant record synced."
      : "Bot joined the tournament.");
    onRefresh();
    setBusy(false);
  }

  return (
    <SectionCard title="Join with one of your bots">
      {alreadyJoined.length > 0 && (
        <p className="play-tournament-info-label" style={{ marginBottom: 12, textTransform: "none", fontSize: "0.875rem" }}>
          Already joined: {alreadyJoined.map((b) => b.tournamentServerBotName).join(", ")}.
        </p>
      )}

      {error   && <ErrorState message={error} />}
      {success && <p className="pt-join-success">{success}</p>}

      {joinableBots.length === 0 ? (
        <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.875rem" }}>
          All your bots have already joined this tournament.
        </p>
      ) : (
        <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
          <select
            className="play-tournament-form-select"
            value={selectedOwnershipId}
            onChange={(e) => { setSelectedOwnershipId(e.target.value); setSuccess(null); }}
            disabled={busy}
            style={{ minWidth: 180 }}
          >
            <option value="">Select your bot…</option>
            {joinableBots.map((b) => (
              <option key={b.searchessBotId} value={b.searchessBotId}>{b.tournamentServerBotName}</option>
            ))}
          </select>
          <Button
            variant="primary"
            size="sm"
            disabled={busy || !selectedOwnershipId}
            onClick={() => { void handleJoin(); }}
          >
            {busy ? "Joining…" : "Join tournament"}
          </Button>
        </div>
      )}

      {joinableBots.length > 0 && participants.length === 0 && (
        <p className="play-tournament-info-label" style={{ marginTop: 10, textTransform: "none", fontSize: "0.875rem" }}>
          Be the first to join.
        </p>
      )}
      {joinableBots.length > 0 && participants.length === 1 && (
        <p className="play-tournament-info-label" style={{ marginTop: 10, textTransform: "none", fontSize: "0.875rem" }}>
          One more bot is needed before the tournament can start.
        </p>
      )}
      {joinableBots.length > 0 && participants.length >= 2 && (
        <p className="play-tournament-info-label" style={{ marginTop: 10, textTransform: "none", fontSize: "0.875rem" }}>
          This tournament has enough bots. Waiting for the host to start.
        </p>
      )}
    </SectionCard>
  );
}

// ── Director actions (created/waiting, director only) ─────────────────────────

function DirectorActions({
  id,
  participants,
  nbPlayers,
  onRefresh,
}: {
  id: string;
  participants: TournamentParticipant[];
  // Tournament Server nbPlayers for UI messaging only (may lag Searchess registry).
  nbPlayers: number;
  onRefresh: () => void;
}) {
  const navigate = useNavigate();
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const participantCount = participants.length;

  // Require ≥1 Searchess-managed bot and ≥2 total TS participants (external bots count).
  // TS-side integrity is checked by the backend gateway on start (returns 409 if a
  // Searchess bot is missing from TS).
  const canStart = participantCount >= 1 && nbPlayers >= 2;

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
      <p className="play-tournament-info-label" style={{ marginBottom: 12, textTransform: "none", fontSize: "0.875rem" }}>
        You are the tournament director. Start when all bots have joined.
      </p>
      {actionError && <ErrorState message={actionError} />}
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center", marginBottom: 10 }}>
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
        <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.875rem" }}>
          {participantCount < 1
            ? "At least one Searchess-managed bot must join before the tournament can start."
            : "At least 2 bots must join the Tournament Server before the tournament can start."}
        </p>
      )}
    </SectionCard>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function PublicTournamentDetailPage() {
  const { id }    = useParams<{ id: string }>();
  const navigate  = useNavigate();
  const canManage = canManagePublicTournaments();

  const [tournament,   setTournament]   = useState<PublicTournament | null>(null);
  const [participants, setParticipants] = useState<TournamentParticipant[]>([]);
  const [ownedBots,    setOwnedBots]    = useState<OwnedTournamentBot[]>([]);
  const [myProfile,         setMyProfile]         = useState<UserProfileResponse | null>(null);
  const [tournamentIdentity, setTournamentIdentity] = useState<TournamentIdentityResponse | null>(null);
  const [hostInfo,      setHostInfo]    = useState<TournamentHostInfo | null>(null);
  const [loading,      setLoading]      = useState(true);
  const [error,        setError]        = useState<string | null>(null);

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

  function loadParticipants(active: { value: boolean }) {
    if (!id) return;
    getTournamentParticipants(id)
      .then((list) => { if (active.value) setParticipants(list); })
      .catch(() => { /* non-critical */ });
  }

  useEffect(() => {
    const active = { value: true };
    loadTournament(active);
    loadParticipants(active);
    // Host metadata is public — load for all users so hostDisplayName is always resolved.
    if (id) {
      getTournamentHost(id)
        .then((h) => { if (active.value) setHostInfo(h); })
        .catch(() => {});
    }
    if (canManage) {
      getMyTournamentBots()
        .then((bots) => { if (active.value) setOwnedBots(bots); })
        .catch(() => {});
      getMyProfile()
        .then((p) => { if (active.value) setMyProfile(p); })
        .catch(() => {});
      // TS identity is kept as a fallback for tournaments created before Searchess host metadata existed.
      getCurrentTournamentIdentity()
        .then((identity) => { if (active.value) setTournamentIdentity(identity); })
        .catch(() => {});
    }
    return () => { active.value = false; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  // Poll silently every 5 s while the tournament is in created/waiting state.
  // Derives a primitive (status) for the deps array so the effect only re-runs on
  // real status transitions, not on every silent update to the tournament object.
  // Loading spinner is intentionally not touched — background refreshes are invisible.
  const status = tournament?.status ?? null;
  useEffect(() => {
    if (!id || status !== "created") return;
    const active = { value: true };
    const timer = setInterval(() => {
      getPublicTournament(id)
        .then((t)    => { if (active.value) setTournament(t); })
        .catch(() => {});
      getTournamentParticipants(id)
        .then((list) => { if (active.value) setParticipants(list); })
        .catch(() => {});
    }, 5000);
    return () => {
      active.value = false;
      clearInterval(timer);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, status]);

  function handleRefresh() {
    const active = { value: true };
    loadTournament(active);
    loadParticipants(active);
  }

  const results     = tournament?.standing?.results ?? [];
  const ownedBotIds = new Set(ownedBots.map((b) => b.tournamentServerBotId));

  // Primary: compare against Searchess host metadata (recorded on create, survives BOT JWT).
  // Fallback: TS identity comparison for tournaments created before host metadata was introduced.
  const isDirector = tournament !== null
    ? hostInfo !== null
        ? (myProfile?.userId ?? null) === hostInfo.hostSearchessUserId
        : canDirectTournament(tournament, tournamentIdentity?.tournamentUserId ?? null)
    : false;
  // Resolve friendly host name. Searchess host metadata is authoritative; TS identity used as fallback.
  const hostDisplayName =
    hostInfo !== null
      ? hostInfo.hostDisplayName
      : tournament !== null && tournamentIdentity?.tournamentUserId === tournament.createdBy
        ? tournamentIdentity.preferredUsername
        : (tournament?.createdBy ?? "");

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
                  participants={participants}
                  myUserId={myProfile?.userId ?? null}
                  ownedBotIds={ownedBotIds}
                  hostDisplayName={hostDisplayName}
                  isDirector={isDirector}
                  onRefresh={handleRefresh}
                />

                {!canManage ? (
                  <div className="pt-panel">
                    <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.875rem" }}>
                      Sign in to join this tournament.
                    </p>
                  </div>
                ) : (
                  <>
                    <JoinSection
                      id={id ?? ""}
                      ownedBots={ownedBots}
                      participants={participants}
                      tournamentServerUserId={tournamentIdentity?.tournamentUserId ?? null}
                      onRefresh={handleRefresh}
                    />
                    {isDirector ? (
                      <DirectorActions
                        id={id ?? ""}
                        participants={participants}
                        nbPlayers={tournament.nbPlayers}
                        onRefresh={handleRefresh}
                      />
                    ) : (
                      <div className="pt-panel">
                        <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.875rem" }}>
                          Waiting for the host ({hostDisplayName}) to start the tournament.
                        </p>
                      </div>
                    )}
                  </>
                )}
              </>
            )}

            {/* ── In-progress status + bot-runner notice ───────────────────── */}
            {tournament.status === "started" && (
              <SectionCard title="Tournament In Progress">
                {tournament.round === 0 ? (
                  <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.875rem" }}>
                    Round 1 pairings are being generated by the tournament server.
                    Refresh in a few seconds.
                  </p>
                ) : (
                  <p className="play-tournament-info-label" style={{ textTransform: "none", fontSize: "0.875rem" }}>
                    Round {tournament.round} of {tournament.nbRounds} is in progress.
                  </p>
                )}
                <p className="play-tournament-info-label" style={{ marginTop: 8, textTransform: "none", fontSize: "0.875rem" }}>
                  The Searchess bot scheduler is active. Registered Searchess bots will have
                  their moves submitted automatically each round.
                </p>
              </SectionCard>
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
