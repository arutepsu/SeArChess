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
import { canManagePublicTournaments } from "../utils/publicTournamentAuth";
import "./PlayTournament.css";

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

function DirectorActions({
  id,
  tournament,
  bots,
  ownedIds,
  onRefresh,
}: {
  id: string;
  tournament: PublicTournament;
  bots: PublicRegisteredBot[];
  ownedIds: Set<string>;
  onRefresh: () => void;
}) {
  const navigate = useNavigate();
  const [selectedBotId, setSelectedBotId] = useState("");
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const ownedBots = bots.filter((b) => ownedIds.has(b.id));

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

  async function handleAddParticipant() {
    if (!selectedBotId) return;
    setBusy(true);
    setActionError(null);
    try {
      await addPublicTournamentParticipant(id, selectedBotId);
      setSelectedBotId("");
      onRefresh();
    } catch (e: unknown) {
      setActionError(e instanceof Error ? e.message : "Failed to add participant.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <SectionCard title="Director Actions">
      {actionError && <ErrorState message={actionError} />}
      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", marginBottom: 16 }}>
        {tournament.status === "created" && (
          <>
            <Button variant="primary" size="sm" onClick={() => { void handleStart(); }} disabled={busy}>
              Start tournament
            </Button>
            <Button variant="secondary" size="sm" onClick={() => { void handleDelete(); }} disabled={busy}>
              Delete
            </Button>
          </>
        )}
      </div>
      {tournament.status === "created" && (
        ownedBots.length > 0 ? (
          <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
            <select
              className="play-tournament-form-select"
              value={selectedBotId}
              onChange={(e) => setSelectedBotId(e.target.value)}
              style={{ minWidth: 160 }}
            >
              <option value="">Select your bot…</option>
              {ownedBots.map((b) => (
                <option key={b.id} value={b.id}>{b.name}</option>
              ))}
            </select>
            <Button
              variant="secondary"
              size="sm"
              onClick={() => { void handleAddParticipant(); }}
              disabled={busy || !selectedBotId}
            >
              Add participant
            </Button>
          </div>
        ) : (
          <p className="play-tournament-info-label">
            You have no registered bots. Go to the{" "}
            <button
              type="button"
              className="pt-inline-link"
              onClick={() => navigate("/play-tournament")}
            >
              tournament lobby
            </button>
            {" "}to register one before adding participants.
          </p>
        )
      )}
      {tournament.status !== "created" && (
        <p className="play-tournament-info-label">
          {tournament.status === "started"
            ? "Tournament is in progress. Director actions are not available."
            : "Tournament is finished."}
        </p>
      )}
    </SectionCard>
  );
}

export default function PublicTournamentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [tournament, setTournament] = useState<PublicTournament | null>(null);
  const [bots, setBots] = useState<PublicRegisteredBot[]>([]);
  const [ownedIds, setOwnedIds] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const canManage = canManagePublicTournaments();

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

  const results = tournament?.standing?.results ?? [];

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">
              {tournament?.fullName ?? "Tournament"}
            </h1>
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
            <Button variant="secondary" size="lg" onClick={() => navigate("/play-tournament")}>
              ← Tournaments
            </Button>
          </div>
        </div>

        {loading && <LoadingState message="Loading tournament…" />}
        {error && <ErrorState message={error} />}

        {tournament && !loading && (
          <>
            <InfoGrid tournament={tournament} />

            {tournament.round > 0 && (
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

            <SectionCard title="Standings">
              <StandingsTable results={results} />
            </SectionCard>

            {canManage && (
              <DirectorActions
                id={id ?? ""}
                tournament={tournament}
                bots={bots}
                ownedIds={ownedIds}
                onRefresh={() => {
                  const active = { value: true };
                  loadTournament(active);
                }}
              />
            )}
          </>
        )}
      </div>
    </div>
  );
}
