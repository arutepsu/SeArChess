import { useNavigate } from "react-router-dom";
import type { TournamentGameRow } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import LoadingState from "../../../components/ui/LoadingState";

// ── Helpers ───────────────────────────────────────────────────────────────────

function resultLabel(row: TournamentGameRow): string {
  if (row.winner === "white") return "1-0";
  if (row.winner === "black") return "0-1";
  if (row.source === "analytics") return "½-½";
  const s = row.status.toLowerCase();
  if (s === "draw" || s === "stalemate" || s === "finished") return "½-½";
  return "⋯";
}

function resultClass(row: TournamentGameRow): string {
  if (row.winner === "white") return "pt-games-result--w";
  if (row.winner === "black") return "pt-games-result--b";
  if (resultLabel(row) === "½-½") return "pt-games-result--d";
  return "";
}

function truncate(s: string, n: number): string {
  return s.length > n ? s.slice(0, n) + "…" : s;
}

// ── Component ─────────────────────────────────────────────────────────────────

interface Props {
  games: TournamentGameRow[];
  ownedIds: Set<string>;
  loading: boolean;
  errors: string[];
  filterOwned: boolean;
  onRefresh: () => void;
}

export default function GamesTab({ games, ownedIds, loading, errors, filterOwned, onRefresh }: Props) {
  const navigate = useNavigate();

  const visible = filterOwned
    ? games.filter((r) => ownedIds.has(r.whiteBotId) || ownedIds.has(r.blackBotId))
    : games;

  if (loading) return <LoadingState message="Loading games…" />;

  return (
    <div>
      {/* ── Empty state ────────────────────────────────────────────────────── */}
      {!loading && visible.length === 0 && (
        <div className="pt-placeholder">
          {filterOwned ? (
            <>
              <p className="pt-placeholder-title">No games with your bots yet</p>
              <p className="pt-placeholder-body">
                Start a Quick Test tournament with your own bots to see games here.
              </p>
            </>
          ) : (
            <>
              <p className="pt-placeholder-title">No games found yet</p>
              <p className="pt-placeholder-body">
                Start a Quick Test tournament first, or wait for a tournament to finish so analytics become available.
              </p>
            </>
          )}
          <div style={{ marginTop: 12 }}>
            <Button variant="secondary" size="sm" onClick={onRefresh}>Refresh</Button>
          </div>
        </div>
      )}

      {/* ── Game table ─────────────────────────────────────────────────────── */}
      {visible.length > 0 && (
        <>
          <div className="pt-games-count">
            {visible.length} game{visible.length !== 1 ? "s" : ""}
            {filterOwned ? " with your bots" : ""}
            {" "}
            <button type="button" className="pt-inline-link" onClick={onRefresh}>Refresh</button>
          </div>

          <div className="pt-games-scroll">
            <table className="play-tournament-table pt-games-table">
              <thead>
                <tr>
                  <th>Tournament</th>
                  <th className="play-tournament-table-num">Rnd</th>
                  <th>White</th>
                  <th>Black</th>
                  <th className="play-tournament-table-num">Result</th>
                  <th className="play-tournament-table-num">Ply</th>
                  <th className="play-tournament-table-num">Data</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {visible.map((row) => {
                  const whiteOwned = ownedIds.has(row.whiteBotId);
                  const blackOwned = ownedIds.has(row.blackBotId);
                  return (
                    <tr key={`${row.tournamentId}:${row.gameId}`}>
                      <td className="pt-games-name-col" title={row.tournamentName}>
                        {truncate(row.tournamentName, 28)}
                        {" "}
                        <span className={`play-tournament-badge play-tournament-badge--${row.tournamentStatus}`}
                              style={{ fontSize: "0.65rem", padding: "1px 5px" }}>
                          {row.tournamentStatus === "started" ? "live" : row.tournamentStatus}
                        </span>
                      </td>
                      <td className="play-tournament-table-num">{row.round}</td>
                      <td className="pt-games-bot-col">
                        {row.whiteBotName}
                        {whiteOwned && <span className="pt-bot-badge pt-bot-badge--own" style={{ marginLeft: 6, fontSize: "0.62rem" }}>You</span>}
                      </td>
                      <td className="pt-games-bot-col">
                        {row.blackBotName}
                        {blackOwned && <span className="pt-bot-badge pt-bot-badge--own" style={{ marginLeft: 6, fontSize: "0.62rem" }}>You</span>}
                      </td>
                      <td className={`play-tournament-table-num pt-games-result ${resultClass(row)}`}>
                        {resultLabel(row)}
                      </td>
                      <td className="play-tournament-table-num pt-games-ply">
                        {row.totalPly !== null ? row.totalPly : "—"}
                      </td>
                      <td className="play-tournament-table-num">
                        <span className={`pt-games-source-badge pt-games-source-badge--${row.source}`}>
                          {row.source === "analytics" ? "full" : "live"}
                        </span>
                      </td>
                      <td>
                        <button
                          type="button"
                          className="pt-inline-link"
                          style={{ fontSize: "0.8rem" }}
                          onClick={() =>
                            row.gameId
                              ? navigate(`/play-tournament/${row.tournamentId}/game/${row.gameId}`)
                              : navigate(`/play-tournament/${row.tournamentId}`)
                          }
                        >
                          {row.gameId ? "Replay" : "View"}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </>
      )}

      {/* ── Partial errors ─────────────────────────────────────────────────── */}
      {errors.length > 0 && (
        <details className="pt-games-errors">
          <summary className="pt-games-errors-summary">
            {errors.length} tournament{errors.length !== 1 ? "s" : ""} could not be loaded
          </summary>
          <ul className="pt-games-errors-list">
            {errors.map((e, i) => <li key={i}>{e}</li>)}
          </ul>
        </details>
      )}
    </div>
  );
}
