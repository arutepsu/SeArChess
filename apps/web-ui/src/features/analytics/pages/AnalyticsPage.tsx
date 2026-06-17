import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import {
  fetchAvgGameLength,
  fetchBotFamilies,
  fetchColorPerformance,
  fetchEloRatings,
  fetchFastestWins,
  fetchLeaderboard,
  fetchLiveGameResults,
  fetchSearchessAi,
  fetchStockfish,
  fetchStrategies,
  fetchTerminations,
  listAnalyticsRuns,
} from "../../../api/analyticsClient";
import type {
  AnalyticsRunSummary,
  AvgGameLengthRow,
  BotFamilyRow,
  ColorPerformanceRow,
  EloRatingsRow,
  FastestWinRow,
  LeaderboardRow,
  LiveGameResult,
  SearchessAiComparisonRow,
  StockfishComparisonRow,
  StrategyRow,
  TerminationReasonRow,
} from "../../../api/analyticsTypes";
import Button from "../../../components/ui/Button";
import EmptyState from "../../../components/ui/EmptyState";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import AvgGameLengthChart from "../components/charts/AvgGameLengthChart";
import BotFamilyChart from "../components/charts/BotFamilyChart";
import ColorPerformanceChart from "../components/charts/ColorPerformanceChart";
import EloRatingsChart from "../components/charts/EloRatingsChart";
import FastestWinsChart from "../components/charts/FastestWinsChart";
import LeaderboardChart from "../components/charts/LeaderboardChart";
import SearchessAiChart from "../components/charts/SearchessAiChart";
import StockfishChart from "../components/charts/StockfishChart";
import StrategyChart from "../components/charts/StrategyChart";
import TerminationsChart from "../components/charts/TerminationsChart";
import "./AnalyticsPage.css";

type SectionState<T> =
  | { status: "loading" }
  | { status: "ok"; rows: T[] }
  | { status: "empty" }
  | { status: "error"; message: string };

function pct(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`;
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function analyticsErrorMessage(message: string): string {
  const lower = message.toLowerCase();
  if (lower.includes("unavailable") || lower.includes("does not exist") || lower.includes("failed")) {
    return "Analytics data unavailable. Run Spark analytics with PostgreSQL output enabled first.";
  }
  return message;
}

function liveResultsErrorMessage(message: string): string {
  const lower = message.toLowerCase();
  if (lower.includes("unavailable") || lower.includes("does not exist") || lower.includes("failed")) {
    return "Live results not yet available. Start the Spark streaming job to populate this table.";
  }
  return message;
}

function shortId(id: string): string {
  return id.length > 14 ? `${id.slice(0, 8)}…` : id;
}

function LiveGameResultsSummary({ rows }: { rows: LiveGameResult[] }) {
  const whiteWins = rows.filter((r) => r.winner === "White").length;
  const blackWins = rows.filter((r) => r.winner === "Black").length;
  const draws = rows.filter((r) => !r.winner).length;
  return (
    <div className="live-results-summary">
      <span className="live-results-stat"><span className="live-results-stat-num">{rows.length}</span> games</span>
      <span className="live-results-stat"><span className="live-results-stat-num">{whiteWins}</span> White wins</span>
      <span className="live-results-stat"><span className="live-results-stat-num">{blackWins}</span> Black wins</span>
      <span className="live-results-stat"><span className="live-results-stat-num">{draws}</span> draws</span>
    </div>
  );
}

function LiveGameResultsTable({ rows }: { rows: LiveGameResult[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Time</th>
          <th>Result</th>
          <th>Winner</th>
          <th>Draw reason</th>
          <th>Game ID</th>
          <th>Session</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.eventId}>
            <td className="analytics-td-num">{new Date(r.occurredAt).toLocaleString()}</td>
            <td>{r.result}</td>
            <td>{r.winner ?? "—"}</td>
            <td>{r.drawReason ?? "—"}</td>
            <td className="analytics-td-name" title={r.aggregateId}>{shortId(r.aggregateId)}</td>
            <td className="analytics-td-name" title={r.sessionId ?? ""}>{r.sessionId ? shortId(r.sessionId) : "—"}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function applyResult<T>(
  result: PromiseSettledResult<T[]>,
  setter: (s: SectionState<T>) => void
): void {
  if (result.status === "fulfilled") {
    setter(result.value.length > 0 ? { status: "ok", rows: result.value } : { status: "empty" });
  } else {
    const msg = result.reason instanceof Error ? result.reason.message : "Unknown error";
    setter({ status: "error", message: msg });
  }
}

// ── Tables ────────────────────────────────────────────────────────────────────

function BotStatisticsTable({ rows }: { rows: LeaderboardRow[] }) {
  const ranked = [...rows].sort((a, b) => b.totalScore - a.totalScore);
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Rank</th>
          <th>Bot</th>
          <th>Games</th>
          <th>Wins</th>
          <th>Draws</th>
          <th>Losses</th>
          <th>Win rate</th>
          <th>Score</th>
          <th>Avg game length</th>
        </tr>
      </thead>
      <tbody>
        {ranked.map((r, i) => (
          <tr key={r.botId}>
            <td className="analytics-td-num">{i + 1}</td>
            <td className="analytics-td-name">{r.botId}</td>
            <td className="analytics-td-num">{r.gamesPlayed}</td>
            <td className="analytics-td-num">{r.wins}</td>
            <td className="analytics-td-num">{r.draws}</td>
            <td className="analytics-td-num">{r.losses}</td>
            <td className="analytics-td-num">{pct(r.winRate)}</td>
            <td className="analytics-td-num">{r.totalScore}</td>
            <td className="analytics-td-num">{r.avgPly.toFixed(1)} ply</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function BotFamilyTable({ rows }: { rows: BotFamilyRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Family</th>
          <th>Score</th>
          <th>W</th>
          <th>D</th>
          <th>L</th>
          <th>Games</th>
          <th>Win %</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.family}>
            <td className="analytics-td-name">{r.family}</td>
            <td className="analytics-td-num">{r.totalScore}</td>
            <td className="analytics-td-num">{r.wins}</td>
            <td className="analytics-td-num">{r.draws}</td>
            <td className="analytics-td-num">{r.losses}</td>
            <td className="analytics-td-num">{r.games}</td>
            <td className="analytics-td-num">{pct(r.winRate)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function StrategyTable({ rows }: { rows: StrategyRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Strategy</th>
          <th>Score</th>
          <th>W</th>
          <th>D</th>
          <th>L</th>
          <th>Games</th>
          <th>Win %</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.strategyType}>
            <td className="analytics-td-name">{r.strategyType}</td>
            <td className="analytics-td-num">{r.totalScore}</td>
            <td className="analytics-td-num">{r.wins}</td>
            <td className="analytics-td-num">{r.draws}</td>
            <td className="analytics-td-num">{r.losses}</td>
            <td className="analytics-td-num">{r.games}</td>
            <td className="analytics-td-num">{pct(r.winRate)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function SearchessAiTable({ rows }: { rows: SearchessAiComparisonRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Opponent</th>
          <th>Family</th>
          <th>Games</th>
          <th>AI wins</th>
          <th>Draws</th>
          <th>Losses</th>
          <th>Score</th>
          <th>Avg length</th>
          <th>Win %</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.opponentBotId}>
            <td className="analytics-td-name">{r.opponentBotId}</td>
            <td>{r.opponentFamily}</td>
            <td className="analytics-td-num">{r.games}</td>
            <td className="analytics-td-num">{r.searchessAiWins}</td>
            <td className="analytics-td-num">{r.draws}</td>
            <td className="analytics-td-num">{r.losses}</td>
            <td className="analytics-td-num">{r.score}</td>
            <td className="analytics-td-num">{r.avgGameLength.toFixed(1)}</td>
            <td className="analytics-td-num">{pct(r.winRate)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function StockfishTable({ rows }: { rows: StockfishComparisonRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Bot</th>
          <th>Strategy</th>
          <th>Games</th>
          <th>Wins</th>
          <th>Draws</th>
          <th>Score</th>
          <th>Avg length</th>
          <th>Win %</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={r.botId}>
            <td className="analytics-td-name">{r.botId}</td>
            <td>{r.strategyType}</td>
            <td className="analytics-td-num">{r.games}</td>
            <td className="analytics-td-num">{r.wins}</td>
            <td className="analytics-td-num">{r.draws}</td>
            <td className="analytics-td-num">{r.totalScore}</td>
            <td className="analytics-td-num">{r.avgGameLength.toFixed(1)}</td>
            <td className="analytics-td-num">{pct(r.winRate)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function AvgGameLengthTable({ rows }: { rows: AvgGameLengthRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>White</th>
          <th>Black</th>
          <th>Games</th>
          <th>Avg ply</th>
          <th>Avg duration</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.whiteBotId}-${r.blackBotId}`}>
            <td className="analytics-td-name">{r.whiteBotId}</td>
            <td className="analytics-td-name">{r.blackBotId}</td>
            <td className="analytics-td-num">{r.gamesPlayed}</td>
            <td className="analytics-td-num">{r.avgTotalPly.toFixed(1)}</td>
            <td className="analytics-td-num">{formatMs(r.avgDurationMs)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function signed(value: number): string {
  return value >= 0 ? `+${value.toFixed(1)}` : value.toFixed(1);
}

function EloRatingsTable({ rows }: { rows: EloRatingsRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Bot</th>
          <th>Rating</th>
          <th>Change</th>
          <th>Games</th>
          <th>W</th>
          <th>D</th>
          <th>L</th>
          <th>Avg opponent</th>
        </tr>
      </thead>
      <tbody>
        {[...rows].sort((a, b) => b.rating - a.rating).map((r) => (
          <tr key={r.botId}>
            <td className="analytics-td-name">{r.botId}</td>
            <td className="analytics-td-num">{r.rating.toFixed(1)}</td>
            <td className="analytics-td-num">{signed(r.ratingChange)}</td>
            <td className="analytics-td-num">{r.gamesPlayed}</td>
            <td className="analytics-td-num">{r.wins}</td>
            <td className="analytics-td-num">{r.draws}</td>
            <td className="analytics-td-num">{r.losses}</td>
            <td className="analytics-td-num">{r.averageOpponentRating.toFixed(1)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function TerminationsTable({ rows }: { rows: TerminationReasonRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Reason</th>
          <th>Count</th>
        </tr>
      </thead>
      <tbody>
        {[...rows].sort((a, b) => b.count - a.count).map((r) => (
          <tr key={r.terminationReason}>
            <td className="analytics-td-name">{r.terminationReason}</td>
            <td className="analytics-td-num">{r.count}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function FastestWinsTable({ rows }: { rows: FastestWinRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Winner</th>
          <th>Decisive games</th>
          <th>Avg ply (lower)</th>
          <th>Min ply</th>
          <th>Avg duration</th>
        </tr>
      </thead>
      <tbody>
        {[...rows].sort((a, b) => a.avgWinPly - b.avgWinPly).map((r) => (
          <tr key={r.winnerBotId}>
            <td className="analytics-td-name">{r.winnerBotId}</td>
            <td className="analytics-td-num">{r.decisiveGames}</td>
            <td className="analytics-td-num">{r.avgWinPly.toFixed(1)}</td>
            <td className="analytics-td-num">{r.minWinPly}</td>
            <td className="analytics-td-num">{formatMs(r.avgWinDurationMs)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ColorPerformanceTable({ rows }: { rows: ColorPerformanceRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>Bot</th>
          <th>White games</th>
          <th>White wins</th>
          <th>White score</th>
          <th>Black games</th>
          <th>Black wins</th>
          <th>Black score</th>
        </tr>
      </thead>
      <tbody>
        {[...rows].sort((a, b) => a.botId.localeCompare(b.botId)).map((r) => (
          <tr key={r.botId}>
            <td className="analytics-td-name">{r.botId}</td>
            <td className="analytics-td-num">{r.gamesAsWhite}</td>
            <td className="analytics-td-num">{r.whiteWins}</td>
            <td className="analytics-td-num">{r.whiteScore.toFixed(1)}</td>
            <td className="analytics-td-num">{r.gamesAsBlack}</td>
            <td className="analytics-td-num">{r.blackWins}</td>
            <td className="analytics-td-num">{r.blackScore.toFixed(1)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

// ── Section renderer ──────────────────────────────────────────────────────────

function renderSection<T>(
  state: SectionState<T>,
  Table: React.ComponentType<{ rows: T[] }>,
  Chart: React.ComponentType<{ rows: T[] }>
): React.ReactNode {
  if (state.status === "loading") return <LoadingState />;
  if (state.status === "error") return <ErrorState message={analyticsErrorMessage(state.message)} />;
  if (state.status === "empty") return <EmptyState />;
  return (
    <>
      <Chart rows={state.rows} />
      <div className="analytics-chart-divider" />
      <div className="analytics-table-wrapper">
        <Table rows={state.rows} />
      </div>
    </>
  );
}

function renderChartOnly<T>(
  state: SectionState<T>,
  Chart: React.ComponentType<{ rows: T[] }>
): React.ReactNode {
  if (state.status === "loading") return <LoadingState />;
  if (state.status === "error") return <ErrorState message={analyticsErrorMessage(state.message)} />;
  if (state.status === "empty") return <EmptyState />;
  return <Chart rows={state.rows} />;
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function AnalyticsPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const runIdFilter = searchParams.get("runId");

  const [runs, setRuns] = useState<AnalyticsRunSummary[]>([]);
  const [runsLoading, setRunsLoading] = useState(true);
  const [runsError, setRunsError] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(runIdFilter);

  const [liveGameResults, setLiveGameResults] = useState<SectionState<LiveGameResult>>({ status: "loading" });

  const [leaderboard, setLeaderboard] = useState<SectionState<LeaderboardRow>>({ status: "loading" });
  const [botFamilies, setBotFamilies] = useState<SectionState<BotFamilyRow>>({ status: "loading" });
  const [strategies, setStrategies] = useState<SectionState<StrategyRow>>({ status: "loading" });
  const [searchessAi, setSearchessAi] = useState<SectionState<SearchessAiComparisonRow>>({ status: "loading" });
  const [stockfish, setStockfish] = useState<SectionState<StockfishComparisonRow>>({ status: "loading" });
  const [avgGameLength, setAvgGameLength] = useState<SectionState<AvgGameLengthRow>>({ status: "loading" });
  const [eloRatings, setEloRatings] = useState<SectionState<EloRatingsRow>>({ status: "loading" });
  const [terminations, setTerminations] = useState<SectionState<TerminationReasonRow>>({ status: "loading" });
  const [colorPerformance, setColorPerformance] = useState<SectionState<ColorPerformanceRow>>({ status: "loading" });
  const [fastestWins, setFastestWins] = useState<SectionState<FastestWinRow>>({ status: "loading" });

  // Effect 1: load runs once on mount, auto-select newest
  useEffect(() => {
    let active = true;
    const empty = { status: "empty" as const };
    const errState = (msg: string) => ({ status: "error" as const, message: msg });

    listAnalyticsRuns()
      .then((data) => {
        if (!active) return;
        setRuns(data);
        setRunsLoading(false);
        if (data.length > 0) {
          if (!runIdFilter) setSelectedRunId(data[0].runId);
        } else {
          setLeaderboard(empty);
          setBotFamilies(empty);
          setStrategies(empty);
          setSearchessAi(empty);
          setStockfish(empty);
          setAvgGameLength(empty);
          setEloRatings(empty);
          setTerminations(empty);
          setColorPerformance(empty);
          setFastestWins(empty);
        }
      })
      .catch((e: unknown) => {
        if (!active) return;
        const msg = e instanceof Error ? e.message : "Failed to load analytics runs";
        setRunsError(msg);
        setRunsLoading(false);
        setLeaderboard(errState(msg));
        setBotFamilies(errState(msg));
        setStrategies(errState(msg));
        setSearchessAi(errState(msg));
        setStockfish(errState(msg));
        setAvgGameLength(errState(msg));
        setEloRatings(errState(msg));
        setTerminations(errState(msg));
        setColorPerformance(errState(msg));
        setFastestWins(errState(msg));
      });

    return () => { active = false; };
  }, []);

  // Effect 2: load live game results on mount (independent of run selection)
  useEffect(() => {
    let active = true;
    fetchLiveGameResults(50)
      .then((rows) => {
        if (!active) return;
        setLiveGameResults(rows.length > 0 ? { status: "ok", rows } : { status: "empty" });
      })
      .catch((e: unknown) => {
        if (!active) return;
        const msg = e instanceof Error ? e.message : "Failed to load live game results";
        setLiveGameResults({ status: "error", message: msg });
      });
    return () => { active = false; };
  }, []);

  // Effect 3: fetch all sections whenever selected run changes
  useEffect(() => {
    if (!selectedRunId) return;
    let active = true;

    setLeaderboard({ status: "loading" });
    setBotFamilies({ status: "loading" });
    setStrategies({ status: "loading" });
    setSearchessAi({ status: "loading" });
    setStockfish({ status: "loading" });
    setAvgGameLength({ status: "loading" });
    setEloRatings({ status: "loading" });
    setTerminations({ status: "loading" });
    setColorPerformance({ status: "loading" });
    setFastestWins({ status: "loading" });

    void Promise.allSettled([
      fetchLeaderboard(selectedRunId),
      fetchBotFamilies(selectedRunId),
      fetchStrategies(selectedRunId),
      fetchSearchessAi(selectedRunId),
      fetchStockfish(selectedRunId),
      fetchAvgGameLength(selectedRunId),
      fetchEloRatings(selectedRunId),
      fetchTerminations(selectedRunId),
      fetchFastestWins(selectedRunId),
      fetchColorPerformance(selectedRunId),
    ]).then(([lbRes, famRes, stratRes, aiRes, sfRes, avgRes, eloRes, termRes, fastRes, colorRes]) => {
      if (!active) return;
      applyResult(lbRes, setLeaderboard);
      applyResult(famRes, setBotFamilies);
      applyResult(stratRes, setStrategies);
      applyResult(aiRes, setSearchessAi);
      applyResult(sfRes, setStockfish);
      applyResult(avgRes, setAvgGameLength);
      applyResult(eloRes, setEloRatings);
      applyResult(termRes, setTerminations);
      applyResult(fastRes, setFastestWins);
      applyResult(colorRes, setColorPerformance);
    });

    return () => { active = false; };
  }, [selectedRunId]);

  const selectedRun = runs.find((r) => r.runId === selectedRunId);

  const clearRunFilter = () => {
    const next = new URLSearchParams(searchParams);
    next.delete("runId");
    setSearchParams(next);
    if (runs.length > 0) setSelectedRunId(runs[0].runId);
  };

  const handleRunSelect = (runId: string) => {
    setSelectedRunId(runId);
    if (runIdFilter) {
      const next = new URLSearchParams(searchParams);
      next.delete("runId");
      setSearchParams(next);
    }
  };

  return (
    <div className="analytics-page">
      <div className="analytics-shell">

        <div className="analytics-header">
          <h1 className="analytics-title">Bot Evaluation Analytics</h1>
          <Button
            variant="secondary"
            size="lg"
            className="analytics-back-btn"
            onClick={() => navigate("/")}
          >
            ← Back
          </Button>
        </div>

        <div className="analytics-run-bar">
          {runsLoading ? (
            <span className="analytics-run-meta">Loading runs…</span>
          ) : runsError ? (
            <span className="analytics-run-meta analytics-run-meta--error">{runsError}</span>
          ) : runs.length === 0 ? (
            <span className="analytics-run-meta analytics-run-meta--none">
              No analytics runs found. Run Spark analytics with PostgreSQL output enabled first.
            </span>
          ) : (
            <>
              <label className="analytics-run-label" htmlFor="run-select">
                Run
              </label>
              <select
                id="run-select"
                className="analytics-run-select"
                value={selectedRunId ?? ""}
                onChange={(e) => handleRunSelect(e.target.value)}
              >
                {runs.map((r) => (
                  <option key={r.runId} value={r.runId}>
                    {r.runId.slice(0, 8)}… · {new Date(r.createdAt).toLocaleString()}
                  </option>
                ))}
              </select>
              {selectedRun && (
                <span className="analytics-run-source" title={selectedRun.sourcePath}>
                  {selectedRun.sourcePath}
                </span>
              )}
            </>
          )}
        </div>

        {runIdFilter && (
          <div className="analytics-context-bar">
            Showing analytics for tournament run <code>{runIdFilter.slice(0, 8)}…</code>
            <button type="button" className="analytics-context-clear" onClick={clearRunFilter}>
              Show global analytics
            </button>
          </div>
        )}

        <SectionCard className="analytics-card analytics-card--full analytics-live-section" title="Live Completed Games">
          {liveGameResults.status === "loading" ? (
            <LoadingState />
          ) : liveGameResults.status === "error" ? (
            <ErrorState message={liveResultsErrorMessage(liveGameResults.message)} />
          ) : liveGameResults.status === "empty" ? (
            <EmptyState message="No completed games yet. Results appear here after the Spark streaming job processes game events." />
          ) : (
            <>
              <LiveGameResultsSummary rows={liveGameResults.rows} />
              <div className="analytics-table-wrapper">
                <LiveGameResultsTable rows={liveGameResults.rows} />
              </div>
            </>
          )}
        </SectionCard>

        <SectionCard className="analytics-card analytics-card--full" title="Bot Statistics">
          {leaderboard.status === "loading" ? (
            <LoadingState />
          ) : leaderboard.status === "error" ? (
            <ErrorState message={analyticsErrorMessage(leaderboard.message)} />
          ) : leaderboard.status === "empty" ? (
            <EmptyState message="No bot statistics available yet." />
          ) : (
            <div className="analytics-table-wrapper">
              <BotStatisticsTable rows={leaderboard.rows} />
            </div>
          )}
        </SectionCard>

        <div className="analytics-sections">

          <SectionCard className="analytics-card analytics-card--full" title="Leaderboard">
            {renderChartOnly(leaderboard, LeaderboardChart)}
          </SectionCard>

          <SectionCard className="analytics-card analytics-card--full" title="Elo Ratings">
            {renderSection(eloRatings, EloRatingsTable, EloRatingsChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="Bot Family Comparison">
            {renderSection(botFamilies, BotFamilyTable, BotFamilyChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="Strategy Comparison">
            {renderSection(strategies, StrategyTable, StrategyChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="Searchess AI vs Opponents">
            {renderSection(searchessAi, SearchessAiTable, SearchessAiChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="vs Stockfish Variants">
            {renderSection(stockfish, StockfishTable, StockfishChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="Termination Reasons">
            {renderSection(terminations, TerminationsTable, TerminationsChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="Fastest Winning Bots">
            {renderSection(fastestWins, FastestWinsTable, FastestWinsChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="Color Performance">
            {renderSection(colorPerformance, ColorPerformanceTable, ColorPerformanceChart)}
          </SectionCard>

          <SectionCard className="analytics-card" title="Average Game Length by Pairing">
            {renderSection(avgGameLength, AvgGameLengthTable, AvgGameLengthChart)}
          </SectionCard>

        </div>
      </div>
    </div>
  );
}
