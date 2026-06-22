import { useMemo } from "react";
import type { EChartsOption } from "echarts";
import type { TournamentGameRow } from "../../../api/publicTournamentTypes";
import EChart from "../../analytics/components/EChart";
import { baseChartOption, categoryAxis, chartColors, valueAxis } from "../../analytics/model/chartTheme";
import LoadingState from "../../../components/ui/LoadingState";
import Button from "../../../components/ui/Button";
import { aggregateGames, type BotStat } from "../utils/analyticsAggregator";

// ── EChart option for game-length distribution ────────────────────────────────

function gameLengthOption(
  labels: string[],
  counts: number[]
): EChartsOption {
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

function BotTable({
  bots,
  ownedIds,
}: {
  bots: BotStat[];
  ownedIds: Set<string>;
}) {
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
  ownedIds: Set<string>;
  loading: boolean;
  errors: string[];
  canManage: boolean;
  onRefresh: () => void;
}

// ── Component ─────────────────────────────────────────────────────────────────

export default function AnalyticsTab({ games, ownedIds, loading, errors, canManage, onRefresh }: Props) {
  const data = useMemo(
    () => aggregateGames(games, ownedIds),
    // Re-run when games array reference changes (set by loadGames) or ownedIds changes
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [games, ownedIds]
  );

  if (loading) return <LoadingState message="Loading game data for analytics…" />;

  const { global: g, mySummary: my, bots, terminations, lengthBuckets } = data;

  // ── Empty / no finished games ────────────────────────────────────────────────
  if (g.totalFinished === 0 && games.length === 0) {
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

  if (g.totalFinished === 0) {
    return (
      <div className="pt-placeholder">
        <p className="pt-placeholder-title">No finished games yet</p>
        <p className="pt-placeholder-body">
          Analytics are derived from completed tournaments.
          {g.excludedLive > 0 && ` ${g.excludedLive} live game${g.excludedLive !== 1 ? "s" : ""} are excluded until the tournament finishes.`}
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

      {/* ── Data status ──────────────────────────────────────────────────── */}
      <div className="pt-analytics-status">
        <span>
          {g.totalFinished} finished game{g.totalFinished !== 1 ? "s" : ""} from{" "}
          {g.tournaments} tournament{g.tournaments !== 1 ? "s" : ""}
          {g.excludedLive > 0 && (
            <> · <span className="pt-analytics-excluded">{g.excludedLive} live excluded</span></>
          )}
        </span>
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

      {/* ── Global summary ────────────────────────────────────────────────── */}
      <div className="pt-analytics-section">
        <SectionTitle>Overview</SectionTitle>
        <div className="pt-analytics-summary-grid">
          <StatCard label="Finished games"  value={g.totalFinished} />
          <StatCard label="Decisive"        value={g.decisive} />
          <StatCard label="Draws"           value={g.draws} />
          <StatCard label="Unique bots"     value={g.uniqueBots} />
          <StatCard label="Avg ply"         value={g.avgPly} />
          <StatCard label="Shortest game"   value={g.shortestPly !== null ? `${g.shortestPly} ply` : null} />
          <StatCard label="Longest game"    value={g.longestPly  !== null ? `${g.longestPly} ply`  : null} />
          <StatCard label="Draw rate"
            value={g.totalFinished > 0 ? `${((g.draws / g.totalFinished) * 100).toFixed(1)}%` : null}
          />
        </div>
      </div>

      {/* ── My bots summary ──────────────────────────────────────────────── */}
      {my !== null && (
        <div className="pt-analytics-section">
          <SectionTitle>My Bots</SectionTitle>
          {my.games === 0 ? (
            <p className="play-tournament-empty">
              None of your bots have played a finished game yet. Start a Quick Test tournament to generate data.
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

      {/* ── Bot performance table ─────────────────────────────────────────── */}
      <div className="pt-analytics-section">
        <SectionTitle>Bot Performance</SectionTitle>
        <BotTable bots={bots} ownedIds={ownedIds} />
      </div>

      {/* ── Termination reasons ───────────────────────────────────────────── */}
      {terminations.length > 0 && (
        <div className="pt-analytics-section">
          <SectionTitle>Termination Reasons</SectionTitle>
          <div className="pt-analytics-terminations">
            {terminations.map((t) => (
              <div key={t.reason} className="pt-analytics-term-row">
                <span className="pt-analytics-term-reason">{t.reason}</span>
                <div className="pt-analytics-term-bar-wrap">
                  <div
                    className="pt-analytics-term-bar"
                    style={{ width: `${Math.max(t.pct, 2)}%` }}
                  />
                </div>
                <span className="pt-analytics-term-count">
                  {t.count} ({t.pct.toFixed(1)}%)
                </span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* ── Game-length distribution ──────────────────────────────────────── */}
      <div className="pt-analytics-section">
        <SectionTitle>Game Length Distribution (ply)</SectionTitle>
        <EChart
          option={gameLengthOption(chartLabels, chartCounts)}
          height={220}
          empty={chartEmpty}
          emptyMessage="No ply data available."
        />
      </div>

      {/* ── Limitations note ──────────────────────────────────────────────── */}
      <p className="pt-analytics-note">
        Analytics include only finished tournaments available through the public server export (up to 10 most recent).
        Live and in-progress games are excluded until the tournament finishes.
      </p>
    </div>
  );
}
