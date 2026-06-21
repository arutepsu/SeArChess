import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  getPublicTournament,
  getPublicTournamentRound,
} from "../../../api/publicTournamentClient";
import type {
  PublicRoundGame,
  PublicRoundPairings,
  PublicTournament,
} from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import "./PlayTournament.css";

function gameResult(game: PublicRoundGame): string {
  if (game.status === "started" || game.status === "playing") return "In progress";
  if (game.winner === "white") return "1–0";
  if (game.winner === "black") return "0–1";
  if (game.winner === null && game.status !== "created" && game.status !== "pending") return "½–½";
  return game.status;
}

export default function PublicTournamentRoundPage() {
  const { id, round } = useParams<{ id: string; round: string }>();
  const navigate = useNavigate();
  const roundNum = Math.max(1, parseInt(round ?? "1", 10) || 1);

  const [tournament, setTournament] = useState<PublicTournament | null>(null);
  const [pairings, setPairings] = useState<PublicRoundPairings | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let active = true;
    setLoading(true);
    setError(null);
    setPairings(null);

    Promise.all([
      getPublicTournament(id).catch(() => null as PublicTournament | null),
      getPublicTournamentRound(id, roundNum),
    ])
      .then(([t, p]) => {
        if (!active) return;
        if (t) setTournament(t);
        setPairings(p);
      })
      .catch((e: unknown) => {
        if (active)
          setError(e instanceof Error ? e.message : "Failed to load round pairings");
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [id, roundNum]);

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">
              {tournament?.fullName ?? "Tournament"} — Round {roundNum}
            </h1>
            <p className="play-tournament-subtitle">Round pairings</p>
          </div>
          <Button
            variant="secondary"
            size="lg"
            onClick={() => navigate(`/play-tournament/${id ?? ""}`)}
          >
            ← Tournament
          </Button>
        </div>

        {loading && <LoadingState message="Loading round pairings…" />}
        {error && <ErrorState message={error} />}

        {pairings && !loading && (
          <>
            {pairings.games.length === 0 ? (
              <p className="play-tournament-empty">No games in this round yet.</p>
            ) : (
              <table className="play-tournament-table">
                <thead>
                  <tr>
                    <th className="play-tournament-table-num">#</th>
                    <th>White</th>
                    <th>Black</th>
                    <th className="play-tournament-table-num">Result</th>
                    <th className="play-tournament-table-num">Watch</th>
                  </tr>
                </thead>
                <tbody>
                  {pairings.games.map((game, i) => (
                    <tr key={game.gameId}>
                      <td className="play-tournament-table-num">{i + 1}</td>
                      <td className="play-tournament-table-name">{game.white.name}</td>
                      <td className="play-tournament-table-name">{game.black.name}</td>
                      <td className="play-tournament-table-num">{gameResult(game)}</td>
                      <td className="play-tournament-table-num">
                        <button
                          type="button"
                          className="play-tournament-event-link"
                          onClick={() =>
                            navigate(
                              `/play-tournament/${id ?? ""}/game/${game.gameId}`
                            )
                          }
                        >
                          Watch →
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </>
        )}
      </div>
    </div>
  );
}
