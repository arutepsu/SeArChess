import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import {
  getPublicTournamentAnalyticsExport,
  getPublicTournamentResults,
} from "../../../api/publicTournamentClient";
import type {
  PublicAnalyticsExport,
  PublicResult,
} from "../../../api/publicTournamentTypes";
import { isAnalyticsNotReady } from "../../../api/publicTournamentTypes";
import Button from "../../../components/ui/Button";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import "./PlayTournament.css";

function pct(w: number, total: number): string {
  if (total === 0) return "0%";
  return `${((w / total) * 100).toFixed(1)}%`;
}

function ResultsTable({ results }: { results: PublicResult[] }) {
  if (results.length === 0)
    return <p className="play-tournament-empty">No results available.</p>;
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

function AnalyticsExportSummary({ data }: { data: PublicAnalyticsExport }) {
  const { standings, games } = data;
  const totalGames = games.length;
  const decisive = games.filter((g) => g.winner !== null).length;
  const draws = totalGames - decisive;
  const avgPly =
    totalGames > 0
      ? (games.reduce((s, g) => s + g.totalPly, 0) / totalGames).toFixed(1)
      : "—";

  return (
    <div className="play-tournament-info-grid" style={{ marginBottom: 24 }}>
      {[
        ["Total games", String(totalGames)],
        ["Decisive", String(decisive)],
        ["Draws", String(draws)],
        ["Avg ply", avgPly],
        ["Bots", String(standings.length)],
        ["Format", data.format],
        ["Rounds", String(data.nbRounds)],
        ["Rated", data.rated ? "Yes" : "No"],
      ].map(([label, value]) => (
        <div key={label} className="play-tournament-info-item">
          <p className="play-tournament-info-label">{label}</p>
          <p className="play-tournament-info-value">{value}</p>
        </div>
      ))}
    </div>
  );
}

function AnalyticsStandingsTable({ data }: { data: PublicAnalyticsExport }) {
  const { standings } = data;
  if (standings.length === 0)
    return <p className="play-tournament-empty">No standings in export.</p>;
  return (
    <table className="play-tournament-table">
      <thead>
        <tr>
          <th>#</th>
          <th>Bot</th>
          <th>Family</th>
          <th className="play-tournament-table-num">Score</th>
          <th className="play-tournament-table-num">Games</th>
          <th className="play-tournament-table-num">W</th>
          <th className="play-tournament-table-num">D</th>
          <th className="play-tournament-table-num">L</th>
          <th className="play-tournament-table-num">Win %</th>
        </tr>
      </thead>
      <tbody>
        {standings.map((s) => (
          <tr key={s.botId}>
            <td className="play-tournament-table-num">{s.rank}</td>
            <td className="play-tournament-table-name">{s.botName}</td>
            <td>{s.botFamily ?? "—"}</td>
            <td className="play-tournament-table-num">{s.points}</td>
            <td className="play-tournament-table-num">{s.nbGames}</td>
            <td className="play-tournament-table-num">{s.wins}</td>
            <td className="play-tournament-table-num">{s.draws}</td>
            <td className="play-tournament-table-num">{s.losses}</td>
            <td className="play-tournament-table-num">{pct(s.wins, s.nbGames)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

export default function PublicTournamentResultsPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();

  const [results, setResults] = useState<PublicResult[]>([]);
  const [analyticsExport, setAnalyticsExport] = useState<PublicAnalyticsExport | null>(null);
  const [analyticsNotReady, setAnalyticsNotReady] = useState<string | null>(null);

  const [resultsLoading, setResultsLoading] = useState(true);
  const [analyticsLoading, setAnalyticsLoading] = useState(true);
  const [resultsError, setResultsError] = useState<string | null>(null);
  const [analyticsError, setAnalyticsError] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    let active = true;

    setResultsLoading(true);
    getPublicTournamentResults(id)
      .then((r) => { if (active) setResults(r); })
      .catch((e: unknown) => {
        if (active)
          setResultsError(e instanceof Error ? e.message : "Failed to load results");
      })
      .finally(() => { if (active) setResultsLoading(false); });

    setAnalyticsLoading(true);
    getPublicTournamentAnalyticsExport(id)
      .then((r) => {
        if (!active) return;
        if (isAnalyticsNotReady(r)) {
          setAnalyticsNotReady(r.message);
        } else {
          setAnalyticsExport(r);
        }
      })
      .catch((e: unknown) => {
        if (active)
          setAnalyticsError(
            e instanceof Error ? e.message : "Failed to load analytics export"
          );
      })
      .finally(() => { if (active) setAnalyticsLoading(false); });

    return () => { active = false; };
  }, [id]);

  return (
    <div className="play-tournament-page">
      <div className="play-tournament-shell">
        <div className="play-tournament-header">
          <div>
            <h1 className="play-tournament-title">Tournament Results</h1>
            <p className="play-tournament-subtitle">Final standings and analytics export.</p>
          </div>
          <Button
            variant="secondary"
            size="lg"
            onClick={() => navigate(`/play-tournament/${id ?? ""}`)}
          >
            ← Tournament
          </Button>
        </div>

        <SectionCard title="Final Standings">
          {resultsLoading ? (
            <LoadingState message="Loading results…" />
          ) : resultsError ? (
            <ErrorState message={resultsError} />
          ) : (
            <ResultsTable results={results} />
          )}
        </SectionCard>

        <SectionCard title="Analytics Export">
          {analyticsLoading ? (
            <LoadingState message="Loading analytics…" />
          ) : analyticsError ? (
            <ErrorState message={analyticsError} />
          ) : analyticsNotReady ? (
            <div className="play-tournament-not-ready">{analyticsNotReady}</div>
          ) : analyticsExport ? (
            <>
              <AnalyticsExportSummary data={analyticsExport} />
              <AnalyticsStandingsTable data={analyticsExport} />
            </>
          ) : null}
        </SectionCard>
      </div>
    </div>
  );
}
