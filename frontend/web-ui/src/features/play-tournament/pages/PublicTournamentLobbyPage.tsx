import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { listPublicTournaments } from "../../../api/publicTournamentClient";
import type { PublicTournamentInfo, PublicTournamentListResponse } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import { canManagePublicTournaments } from "../utils/publicTournamentAuth";
import "./PlayTournament.css";

function formatClock(limitSec: number, increment: number): string {
  const min = Math.floor(limitSec / 60);
  const sec = limitSec % 60;
  const base = sec === 0 ? `${min}m` : `${min}m${sec}s`;
  return increment > 0 ? `${base}+${increment}s` : base;
}

function TournamentCard({ t, onClick }: { t: PublicTournamentInfo; onClick: () => void }) {
  return (
    <button type="button" className="play-tournament-card" onClick={onClick}>
      <p className="play-tournament-card-name">{t.fullName}</p>
      <p className="play-tournament-card-meta">
        <span>{t.format}</span>
        <span>{formatClock(t.clock.limit, t.clock.increment)}</span>
        <span>{t.nbRounds} rounds</span>
        <span>{t.nbPlayers} bots</span>
        {t.rated && <span>Rated</span>}
      </p>
    </button>
  );
}

function TournamentGroup({
  label,
  statusClass,
  items,
  onSelect,
}: {
  label: string;
  statusClass: string;
  items: PublicTournamentInfo[];
  onSelect: (id: string) => void;
}) {
  if (items.length === 0) return null;
  return (
    <div className="play-tournament-group">
      <h2 className="play-tournament-group-title">
        <span className={`play-tournament-badge play-tournament-badge--${statusClass}`}>
          {label}
        </span>
      </h2>
      <div className="play-tournament-grid">
        {items.map((t) => (
          <TournamentCard key={t.id} t={t} onClick={() => onSelect(t.id)} />
        ))}
      </div>
    </div>
  );
}

export default function PublicTournamentLobbyPage() {
  const navigate = useNavigate();
  const [data, setData] = useState<PublicTournamentListResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const canCreate = canManagePublicTournaments();

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    listPublicTournaments()
      .then((d) => { if (active) setData(d); })
      .catch((e: unknown) => {
        if (active)
          setError(e instanceof Error ? e.message : "Failed to load tournaments");
      })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const total =
    (data?.created.length ?? 0) +
    (data?.started.length ?? 0) +
    (data?.finished.length ?? 0);

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">Public Tournaments</h1>
            <p className="play-tournament-subtitle">
              Bot tournaments running on the public tournament server.
            </p>
          </div>
          <div style={{ display: "flex", gap: 10, flexWrap: "wrap", alignItems: "center" }}>
            {canCreate ? (
              <Button variant="primary" size="lg" onClick={() => navigate("/play-tournament/create")}>
                + Create tournament
              </Button>
            ) : (
              <span className="pt-auth-note">Sign in to create or manage tournaments.</span>
            )}
            <Button variant="secondary" size="lg" onClick={() => navigate("/")}>
              ← Back
            </Button>
          </div>
        </div>

        {loading && <LoadingState message="Loading tournaments…" />}
        {error && <ErrorState message={error} />}

        {data && !loading && (
          <>
            <TournamentGroup
              label="In progress"
              statusClass="started"
              items={data.started}
              onSelect={(id) => navigate(`/play-tournament/${id}`)}
            />
            <TournamentGroup
              label="Waiting to start"
              statusClass="created"
              items={data.created}
              onSelect={(id) => navigate(`/play-tournament/${id}`)}
            />
            <TournamentGroup
              label="Finished"
              statusClass="finished"
              items={data.finished}
              onSelect={(id) => navigate(`/play-tournament/${id}`)}
            />
            {total === 0 && (
              <p className="play-tournament-empty">
                No tournaments found on the public server.
              </p>
            )}
          </>
        )}
      </div>
    </div>
  );
}
