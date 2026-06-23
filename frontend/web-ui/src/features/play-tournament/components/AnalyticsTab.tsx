import { useMemo } from "react";
import { useNavigate } from "react-router-dom";
import type { EChartsOption } from "echarts";
import type {
  PublicAnalyticsExport,
  PublicTournamentListResponse,
  TournamentGameRow,
} from "../../../api/publicTournamentTypes";
import EChart from "../../analytics/components/EChart";
import AvgGameLengthChart from "../../analytics/components/charts/AvgGameLengthChart";
import BotFamilyChart from "../../analytics/components/charts/BotFamilyChart";
import ColorPerformanceChart from "../../analytics/components/charts/ColorPerformanceChart";
import FastestWinsChart from "../../analytics/components/charts/FastestWinsChart";
import LeaderboardChart from "../../analytics/components/charts/LeaderboardChart";
import StrategyChart from "../../analytics/components/charts/StrategyChart";
import TerminationsChart from "../../analytics/components/charts/TerminationsChart";
import { baseChartOption, categoryAxis, chartColors, valueAxis } from "../../analytics/model/chartTheme";
import LoadingState from "../../../components/ui/LoadingState";
import Button from "../../../components/ui/Button";
import {
  aggregateGames,
  computePublicTournamentProgress,
  type BotStat,
} from "../utils/analyticsAggregator";
import { mergePublicTournamentExports } from "../analytics/publicTournamentAnalyticsAdapter";

// ── EChart option for game-length distribution ────────────────────────────────

function gameLengthOption(labels: string[], counts: number[]): EChartsOption {
  return {
    ...baseChartOption(),
    tooltip: { trigger: "item" as const, formatter: "{b}: {c} game{c|{c}>1?'s':''}" },
    xAxis: categoryAxis(labels),
    yAxis: valueAxis("Games"),
    series: [
      {
        type: "bar",
        data: counts,
        itemStyle: { color: chartColors.duration },
        label: {
          show: true,
          position: "top" as const,
          color: chartColors.text,
          fontSize: 11,
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          formatter: (p: any) => (typeof p.value === "number" && p.value > 0) ? String(p.value as number) : "",
        },
      },
    ],
  };
}

// ── Result helpers (mirrored from GamesTab, no import to avoid layout coupling) ─

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

// ── Small presentational pieces ───────────────────────────────────────────────

function StatCard({ label, value }: { label: string; value: string | number | null }) {
  return (
    <div className="pt-analytics-stat-card">
      <span className="pt-analytics-stat-value">{value ?? "—"}</span>
      <span className="pt-analytics-stat-label">{label}</span>
    </div>
  );
}

function SectionTitle({ children }: { children: React.ReactNode }) {
  return <p className="pt-analytics-section-title">{children}</p>;
}

function BotTable({ bots, ownedIds }: { bots: BotStat[]; ownedIds: Set<string> }) {
  if (bots.length === 0)
    return <p className="play-tournament-empty">No bot data yet.</p>;

  return (
    <div className="pt-games-scroll">
      <table className="play-tournament-table">
        <thead>
          <tr>
            <th>Bot</th>
            <th className="play-tournament-table-num">G</th>
            <th className="play-tournament-table-num">W</th>
            <th className="play-tournament-table-num">D</th>
            <th className="play-tournament-table-num">L</th>
            <th className="play-tournament-table-num">Score</th>
            <th className="play-tournament-table-num">Win%</th>
            <th className="play-tournament-table-num">Avg ply</th>
          </tr>
        </thead>
        <tbody>
          {bots.map((b) => (
            <tr key={b.botId}>
              <td>
                {b.botName}
                {ownedIds.has(b.botId) && (
                  <span className="pt-bot-badge pt-bot-badge--own" style={{ marginLeft: 8, fontSize: "0.62rem" }}>
                    You
                  </span>
                )}
              </td>
              <td className="play-tournament-table-num">{b.games}</td>
              <td className="play-tournament-table-num">{b.wins}</td>
              <td className="play-tournament-table-num">{b.draws}</td>
              <td className="play-tournament-table-num">{b.losses}</td>
              <td className="play-tournament-table-num">{b.score.toFixed(1)}</td>
              <td className="play-tournament-table-num">{(b.winRate * 100).toFixed(1)}%</td>
              <td className="play-tournament-table-num">{b.avgPly ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

// ── Props ─────────────────────────────────────────────────────────────────────

interface Props {
  games: TournamentGameRow[];
  exports: PublicAnalyticsExport[];
  tournamentList: PublicTournamentListResponse | null;
  ownedIds: Set<string>;
  loading: boolean;
  errors: string[];
  canManage: boolean;
  onRefresh: () => void;
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function AnalyticsTab({
  games,
  exports,
  tournamentList,
  ownedIds,
  loading,
  errors,
  canManage,
  onRefresh,
}: Props) {
  const navigate = useNavigate();

  const data = useMemo(
    () => aggregateGames(games, ownedIds),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [games, ownedIds]
  );

  const chartData = useMemo(
    () => mergePublicTournamentExports(exports),
    [exports]
  );

  const progressData = useMemo(
    () => computePublicTournamentProgress(tournamentList, games),
    [tournamentList, games]
  );

  const allFamiliesUnknown =
    chartData !== null &&
    chartData.botFamilies.length > 0 &&
    chartData.botFamilies.every((r) => r.family === "(unknown)");

  const allStrategiesUnknown =
    chartData !== null &&
    chartData.strategies.length > 0 &&
    chartData.strategies.every((r) => r.strategyType === "(unknown)");

  if (loading) return <LoadingState message="Loading game data for analytics…" />;

  const { global: g, mySummary: my, bots, lengthBuckets } = data;

  const hasRunningOrWaiting = progressData.waitingCount > 0 || progressData.runningCount > 0;
  const hasAnyData = g.totalFinished > 0 || games.length > 0 || hasRunningOrWaiting;

  // ── Nothing at all loaded yet ────────────────────────────────────────────────
  if (!hasAnyData) {
    return (
      <div className="pt-placeholder">
        <p className="pt-placeholder-title">No data yet</p>
        <p className="pt-placeholder-body">
          Game data is not loaded. Open All Games or hit Refresh.
        </p>
        <div style={{ marginTop: 12 }}>
          <Button variant="secondary" size="sm" onClick={onRefresh}>Refresh</Button>
        </div>
      </div>
    );
  }

  const chartLabels = lengthBuckets.map((b) => b.label);
  const chartCounts = lengthBuckets.map((b) => b.count);
  const chartEmpty  = chartCounts.every((c) => c === 0);

  return (
    <div className="pt-analytics-panel">

      {/* ── Top bar: refresh + finished-data count ───────────────────────── */}
      <div className="pt-analytics-status">
        {g.totalFinished > 0 ? (
          <span>
            {g.totalFinished} finished game{g.totalFinished !== 1 ? "s" : ""} from{" "}
            {g.tournaments} tournament{g.tournaments !== 1 ? "s" : ""}
            {g.excludedLive > 0 && (
              <> · <span className="pt-analytics-excluded">{g.excludedLive} live excluded</span></>
            )}
          </span>
        ) : (
          <span />
        )}
        <button type="button" className="pt-inline-link" onClick={onRefresh}>
          Refresh
        </button>
      </div>

      {/* ── Partial load errors ───────────────────────────────────────────── */}
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

      {/* ═══════════════════════════════════════════════════════════════════ */}
      {/* ── PROGRESS SECTION ─────────────────────────────────────────────── */}
      {/* ═══════════════════════════════════════════════════════════════════ */}
      <div className="pt-analytics-section">
        <SectionTitle>Tournament Progress</SectionTitle>

        {/* Tournament status cards — always visible */}
        <div className="pt-analytics-summary-grid">
          <StatCard label="Waiting"  value={progressData.waitingCount} />
          <StatCard label="Running"  value={progressData.runningCount} />
          <StatCard label="Finished" value={progressData.finishedCount} />
        </div>

        {/* No running or waiting tournaments */}
        {!hasRunningOrWaiting && (
          <p className="play-tournament-empty">
            No public tournaments are currently waiting or running.
          </p>
        )}

        {/* Running tournaments but no game data loaded yet */}
        {hasRunningOrWaiting && progressData.runningCount > 0 && progressData.knownGames === 0 && (
          <p className="play-tournament-empty">
            Games may still be preparing, or round data is not available yet.
          </p>
        )}

        {/* Running tournaments with game data */}
        {hasRunningOrWaiting && progressData.knownGames > 0 && (
          <>
            {/* Game-level stat cards */}
            <div className="pt-analytics-summary-grid">
              <StatCard label="Known games" value={progressData.knownGames} />
              <StatCard label="Completed"   value={progressData.completedGames} />
              <StatCard label="In progress" value={progressData.activeGames} />
              {progressData.currentRoundMax !== null && (
                <StatCard
                  label="Round"
                  value={
                    progressData.nbRoundsMax !== null
                      ? `${progressData.currentRoundMax} / ${progressData.nbRoundsMax}`
                      : String(progressData.currentRoundMax)
                  }
                />
              )}
            </div>

            {/* Honest progress bar: completed of discovered, always labelled estimated */}
            {progressData.progressPercent !== undefined && (
              <div>
                <p style={{ fontSize: "0.78rem", color: "var(--text-secondary, #9090a8)", margin: "0 0 6px" }}>
                  Known completed games: {progressData.completedGames} of {progressData.knownGames} discovered
                  {" "}— estimated
                </p>
                <div className="pt-analytics-term-bar-wrap">
                  <div
                    className="pt-analytics-term-bar"
                    style={{ width: `${Math.min(100, progressData.progressPercent).toFixed(1)}%` }}
                  />
                </div>
              </div>
            )}

            {/* Latest games table */}
            <div>
              <p style={{
                fontSize: "0.72rem",
                fontWeight: 600,
                letterSpacing: "0.06em",
                textTransform: "uppercase" as const,
                color: "var(--text-secondary, #9090a8)",
                margin: "4px 0 8px",
              }}>
                Latest Games
              </p>
              {progressData.latestGames.length === 0 ? (
                <p className="play-tournament-empty">No games discovered yet.</p>
              ) : (
                <div className="pt-games-scroll">
                  <table className="play-tournament-table">
                    <thead>
                      <tr>
                        <th>Tournament</th>
                        <th className="play-tournament-table-num">Rnd</th>
                        <th>White</th>
                        <th>Black</th>
                        <th className="play-tournament-table-num">Result</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      {progressData.latestGames.map((row) => (
                        <tr key={`${row.tournamentId}:${row.gameId}`}>
                          <td
                            className="pt-games-name-col"
                            title={row.tournamentName}
                          >
                            {row.tournamentName.length > 22
                              ? row.tournamentName.slice(0, 22) + "…"
                              : row.tournamentName}
                          </td>
                          <td className="play-tournament-table-num">{row.round}</td>
                          <td className="pt-games-bot-col">{row.whiteBotName}</td>
                          <td className="pt-games-bot-col">{row.blackBotName}</td>
                          <td className={`play-tournament-table-num pt-games-result ${resultClass(row)}`}>
                            {resultLabel(row)}
                          </td>
                          <td>
                            {row.gameId && (
                              <button
                                type="button"
                                className="pt-inline-link"
                                style={{ fontSize: "0.8rem" }}
                                onClick={() =>
                                  navigate(`/play-tournament/${row.tournamentId}/game/${row.gameId}`)
                                }
                              >
                                Replay
                              </button>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>

      {/* ═══════════════════════════════════════════════════════════════════ */}
      {/* ── FINISHED ANALYTICS SECTION ───────────────────────────────────── */}
      {/* ═══════════════════════════════════════════════════════════════════ */}

      {g.totalFinished === 0 ? (
        <p className="pt-analytics-note">
          Finished public tournament analytics will appear here after tournaments complete.
          {g.excludedLive > 0 && (
            ` ${g.excludedLive} live game${g.excludedLive !== 1 ? "s" : ""} excluded until the tournament finishes.`
          )}
        </p>
      ) : (
        <>
          {/* Overview */}
          <div className="pt-analytics-section">
            <SectionTitle>Overview</SectionTitle>
            <div className="pt-analytics-summary-grid">
              <StatCard label="Finished games" value={g.totalFinished} />
              <StatCard label="Decisive"       value={g.decisive} />
              <StatCard label="Draws"          value={g.draws} />
              <StatCard label="Unique bots"    value={g.uniqueBots} />
              <StatCard label="Avg ply"        value={g.avgPly} />
              <StatCard label="Shortest game"  value={g.shortestPly !== null ? `${g.shortestPly} ply` : null} />
              <StatCard label="Longest game"   value={g.longestPly  !== null ? `${g.longestPly} ply`  : null} />
              <StatCard label="Draw rate"
                value={g.totalFinished > 0 ? `${((g.draws / g.totalFinished) * 100).toFixed(1)}%` : null}
              />
            </div>
          </div>

          {/* My Bots */}
          {my !== null && (
            <div className="pt-analytics-section">
              <SectionTitle>My Bots</SectionTitle>
              {my.games === 0 ? (
                <p className="play-tournament-empty">
                  None of your bots have played a finished game yet.
                </p>
              ) : (
                <div className="pt-analytics-summary-grid">
                  <StatCard label="Games played" value={my.games} />
                  <StatCard label="Wins"         value={my.wins} />
                  <StatCard label="Draws"        value={my.draws} />
                  <StatCard label="Losses"       value={my.losses} />
                  <StatCard label="Score rate"   value={`${(my.scoreRate * 100).toFixed(1)}%`} />
                  <StatCard label="Win rate"     value={`${(my.winRate * 100).toFixed(1)}%`} />
                  <StatCard label="Avg ply"      value={my.avgPly} />
                </div>
              )}
            </div>
          )}
          {my === null && ownedIds.size === 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>My Bots</SectionTitle>
              <p className="play-tournament-empty">
                {canManage
                  ? "Register a bot in the My Bots tab to track your own performance here."
                  : "Sign in and register a bot to track your own performance here."}
              </p>
            </div>
          )}

          {/* Bot performance table */}
          <div className="pt-analytics-section">
            <SectionTitle>Bot Performance</SectionTitle>
            <BotTable bots={bots} ownedIds={ownedIds} />
          </div>

          {/* Leaderboard chart */}
          {chartData !== null && chartData.leaderboard.length > 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>Leaderboard</SectionTitle>
              <LeaderboardChart rows={chartData.leaderboard} />
            </div>
          )}

          {/* Termination reasons */}
          {chartData !== null && chartData.terminations.length > 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>Termination Reasons</SectionTitle>
              <TerminationsChart rows={chartData.terminations} />
            </div>
          )}

          {/* Game-length distribution */}
          <div className="pt-analytics-section">
            <SectionTitle>Game Length Distribution (ply)</SectionTitle>
            <EChart
              option={gameLengthOption(chartLabels, chartCounts)}
              height={220}
              empty={chartEmpty}
              emptyMessage="No ply data available."
            />
          </div>

          {/* Color performance */}
          {chartData !== null && chartData.colorPerformance.length > 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>Color Performance</SectionTitle>
              <ColorPerformanceChart rows={chartData.colorPerformance} />
            </div>
          )}

          {/* Average game length by pairing */}
          {chartData !== null && chartData.avgGameLength.length > 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>Average Game Length by Pairing</SectionTitle>
              <AvgGameLengthChart rows={chartData.avgGameLength} />
            </div>
          )}

          {/* Fastest winning bots */}
          {chartData !== null && chartData.fastestWins.length > 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>Fastest Winning Bots</SectionTitle>
              <FastestWinsChart rows={chartData.fastestWins} />
            </div>
          )}

          {/* Bot family comparison (conditional) */}
          {chartData !== null && !allFamiliesUnknown && chartData.botFamilies.length > 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>Bot Family Comparison</SectionTitle>
              <BotFamilyChart rows={chartData.botFamilies} />
            </div>
          )}

          {/* Strategy comparison (conditional) */}
          {chartData !== null && !allStrategiesUnknown && chartData.strategies.length > 0 && (
            <div className="pt-analytics-section">
              <SectionTitle>Strategy Comparison</SectionTitle>
              <StrategyChart rows={chartData.strategies} />
            </div>
          )}

          {/* Limitations note */}
          <p className="pt-analytics-note">
            Analytics include only finished tournaments available through the public server export (up to 10 most recent).
            Live and in-progress games are excluded until the tournament finishes.
            Elo ratings, Searchess AI comparison, and Stockfish comparison are not available in the tournament-server export.
          </p>
        </>
      )}
    </div>
  );
}
