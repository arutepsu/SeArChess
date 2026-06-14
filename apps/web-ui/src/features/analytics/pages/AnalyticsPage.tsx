import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import {
  fetchAvgGameLength,
  fetchBotFamilies,
  fetchLeaderboard,
  fetchSearchessAi,
  fetchStockfish,
  fetchStrategies,
  listAnalyticsRuns,
} from "../../../api/analyticsClient";
import type {
  AnalyticsRunSummary,
  AvgGameLengthRow,
  BotFamilyRow,
  LeaderboardRow,
  SearchessAiComparisonRow,
  StockfishComparisonRow,
  StrategyRow,
} from "../../../api/analyticsTypes";
import Button from "../../../components/ui/Button";
import EmptyState from "../../../components/ui/EmptyState";
import ErrorState from "../../../components/ui/ErrorState";
import LoadingState from "../../../components/ui/LoadingState";
import SectionCard from "../../../components/ui/SectionCard";
import AvgGameLengthChart from "../components/charts/AvgGameLengthChart";
import BotFamilyChart from "../components/charts/BotFamilyChart";
import LeaderboardChart from "../components/charts/LeaderboardChart";
import SearchessAiChart from "../components/charts/SearchessAiChart";
import StockfishChart from "../components/charts/StockfishChart";
import StrategyChart from "../components/charts/StrategyChart";
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

function LeaderboardTable({ rows }: { rows: LeaderboardRow[] }) {
  return (
    <table className="analytics-table">
      <thead>
        <tr>
          <th>#</th>
          <th>Bot</th>
          <th>Score</th>
          <th>W</th>
          <th>D</th>
          <th>L</th>
          <th>Games</th>
          <th>Avg ply</th>
          <th>Win %</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r, i) => (
          <tr key={r.botId}>
            <td className="analytics-td-num">{i + 1}</td>
            <td className="analytics-td-name">{r.botId}</td>
            <td className="analytics-td-num">{r.totalScore}</td>
            <td className="analytics-td-num">{r.wins}</td>
            <td className="analytics-td-num">{r.draws}</td>
            <td className="analytics-td-num">{r.losses}</td>
            <td className="analytics-td-num">{r.gamesPlayed}</td>
            <td className="analytics-td-num">{r.avgPly.toFixed(1)}</td>
            <td className="analytics-td-num">{pct(r.winRate)}</td>
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
      <Table rows={state.rows} />
    </>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

export default function AnalyticsPage() {
  const navigate = useNavigate();

  const [runs, setRuns] = useState<AnalyticsRunSummary[]>([]);
  const [runsLoading, setRunsLoading] = useState(true);
  const [runsError, setRunsError] = useState<string | null>(null);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);

  const [leaderboard, setLeaderboard] = useState<SectionState<LeaderboardRow>>({ status: "loading" });
  const [botFamilies, setBotFamilies] = useState<SectionState<BotFamilyRow>>({ status: "loading" });
  const [strategies, setStrategies] = useState<SectionState<StrategyRow>>({ status: "loading" });
  const [searchessAi, setSearchessAi] = useState<SectionState<SearchessAiComparisonRow>>({ status: "loading" });
  const [stockfish, setStockfish] = useState<SectionState<StockfishComparisonRow>>({ status: "loading" });
  const [avgGameLength, setAvgGameLength] = useState<SectionState<AvgGameLengthRow>>({ status: "loading" });

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
          setSelectedRunId(data[0].runId);
        } else {
          setLeaderboard(empty);
          setBotFamilies(empty);
          setStrategies(empty);
          setSearchessAi(empty);
          setStockfish(empty);
          setAvgGameLength(empty);
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
      });

    return () => { active = false; };
  }, []);

  // Effect 2: fetch all sections whenever selected run changes
  useEffect(() => {
    if (!selectedRunId) return;
    let active = true;

    setLeaderboard({ status: "loading" });
    setBotFamilies({ status: "loading" });
    setStrategies({ status: "loading" });
    setSearchessAi({ status: "loading" });
    setStockfish({ status: "loading" });
    setAvgGameLength({ status: "loading" });

    void Promise.allSettled([
      fetchLeaderboard(selectedRunId),
      fetchBotFamilies(selectedRunId),
      fetchStrategies(selectedRunId),
      fetchSearchessAi(selectedRunId),
      fetchStockfish(selectedRunId),
      fetchAvgGameLength(selectedRunId),
    ]).then(([lbRes, famRes, stratRes, aiRes, sfRes, avgRes]) => {
      if (!active) return;
      applyResult(lbRes, setLeaderboard);
      applyResult(famRes, setBotFamilies);
      applyResult(stratRes, setStrategies);
      applyResult(aiRes, setSearchessAi);
      applyResult(sfRes, setStockfish);
      applyResult(avgRes, setAvgGameLength);
    });

    return () => { active = false; };
  }, [selectedRunId]);

  const selectedRun = runs.find((r) => r.runId === selectedRunId);

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
                onChange={(e) => setSelectedRunId(e.target.value)}
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

        <div className="analytics-sections">

          <SectionCard className="analytics-card" title="Leaderboard">
            {renderSection(leaderboard, LeaderboardTable, LeaderboardChart)}
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

          <SectionCard className="analytics-card" title="Average Game Length by Pairing">
            {renderSection(avgGameLength, AvgGameLengthTable, AvgGameLengthChart)}
          </SectionCard>

        </div>
      </div>
    </div>
  );
}
