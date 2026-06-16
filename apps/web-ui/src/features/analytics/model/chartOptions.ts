import type { EChartsOption } from "echarts";
import type {
  AvgGameLengthRow,
  BotFamilyRow,
  ColorPerformanceRow,
  EloRatingsRow,
  FastestWinRow,
  LeaderboardRow,
  SearchessAiComparisonRow,
  StockfishComparisonRow,
  StrategyRow,
  TerminationReasonRow,
} from "../../../api/analyticsTypes";
import { baseChartOption, categoryAxis, chartColors, valueAxis } from "./chartTheme";

function fixed(value: number): string {
  return value.toFixed(1);
}

function pct(value: number): string {
  return `${(value * 100).toFixed(1)}%`;
}

function formatMs(ms: number): string {
  if (ms < 1000) return `${Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

function horizontalBarOption(
  labels: string[],
  values: number[],
  seriesName: string,
  color: string
): EChartsOption {
  return {
    ...baseChartOption(),
    xAxis: valueAxis(),
    yAxis: categoryAxis(labels, true),
    series: [
      {
        name: seriesName,
        type: "bar",
        data: values,
        itemStyle: {
          color,
          borderRadius: [0, 4, 4, 0],
        },
        label: {
          show: true,
          position: "right",
          color: chartColors.text,
          formatter: "{c}",
        },
      },
    ],
  };
}

function stackedOutcomeOption(
  labels: string[],
  wins: number[],
  draws: number[],
  losses: number[]
): EChartsOption {
  return {
    ...baseChartOption(),
    xAxis: valueAxis("games"),
    yAxis: categoryAxis(labels, true),
    series: [
      {
        name: "Wins",
        type: "bar",
        stack: "outcomes",
        data: wins,
        itemStyle: { color: chartColors.wins, borderRadius: [0, 0, 0, 0] },
      },
      {
        name: "Draws",
        type: "bar",
        stack: "outcomes",
        data: draws,
        itemStyle: { color: chartColors.draws },
      },
      {
        name: "Losses",
        type: "bar",
        stack: "outcomes",
        data: losses,
        itemStyle: { color: chartColors.losses, borderRadius: [0, 4, 4, 0] },
      },
    ],
  };
}

export function leaderboardOption(rows: LeaderboardRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.totalScore - a.totalScore);
  return horizontalBarOption(
    sorted.map((row) => row.botId),
    sorted.map((row) => row.totalScore),
    "Score",
    chartColors.score
  );
}

export function eloRatingsOption(rows: EloRatingsRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.rating - a.rating);
  return horizontalBarOption(
    sorted.map((row) => row.botId),
    sorted.map((row) => row.rating),
    "Rating",
    chartColors.rating
  );
}

export function botFamilyOption(rows: BotFamilyRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.totalScore - a.totalScore);
  return stackedOutcomeOption(
    sorted.map((row) => `${row.family} (${pct(row.winRate)})`),
    sorted.map((row) => row.wins),
    sorted.map((row) => row.draws),
    sorted.map((row) => row.losses)
  );
}

export function strategyOption(rows: StrategyRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.totalScore - a.totalScore);
  return stackedOutcomeOption(
    sorted.map((row) => `${row.strategyType} (${pct(row.winRate)})`),
    sorted.map((row) => row.wins),
    sorted.map((row) => row.draws),
    sorted.map((row) => row.losses)
  );
}

export function searchessAiOption(rows: SearchessAiComparisonRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.score - a.score);
  return horizontalBarOption(
    sorted.map((row) => `${row.opponentBotId} (${row.opponentFamily})`),
    sorted.map((row) => row.score),
    "AI score",
    chartColors.secondary
  );
}

export function stockfishOption(rows: StockfishComparisonRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.totalScore - a.totalScore);
  return horizontalBarOption(
    sorted.map((row) => `${row.botId} (${row.strategyType})`),
    sorted.map((row) => row.totalScore),
    "Score",
    chartColors.score
  );
}

export function terminationsOption(rows: TerminationReasonRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.count - a.count);
  return horizontalBarOption(
    sorted.map((row) => row.terminationReason),
    sorted.map((row) => row.count),
    "Games",
    chartColors.duration
  );
}

export function fastestWinsOption(rows: FastestWinRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => a.avgWinPly - b.avgWinPly);
  return horizontalBarOption(
    sorted.map((row) => `${row.winnerBotId} (min ${row.minWinPly})`),
    sorted.map((row) => row.avgWinPly),
    "Avg winning ply",
    chartColors.wins
  );
}

export function colorPerformanceOption(rows: ColorPerformanceRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => a.botId.localeCompare(b.botId));
  return {
    ...baseChartOption(),
    xAxis: categoryAxis(sorted.map((row) => row.botId)),
    yAxis: valueAxis("score"),
    series: [
      {
        name: "White score",
        type: "bar",
        data: sorted.map((row) => row.whiteScore),
        itemStyle: { color: chartColors.rating, borderRadius: [4, 4, 0, 0] },
      },
      {
        name: "Black score",
        type: "bar",
        data: sorted.map((row) => row.blackScore),
        itemStyle: { color: chartColors.secondary, borderRadius: [4, 4, 0, 0] },
      },
    ],
  };
}

export function avgGameLengthOption(rows: AvgGameLengthRow[]): EChartsOption {
  const sorted = [...rows].sort((a, b) => b.avgTotalPly - a.avgTotalPly).slice(0, 10);
  return {
    ...horizontalBarOption(
      sorted.map((row) => `${row.whiteBotId} vs ${row.blackBotId}`),
      sorted.map((row) => row.avgTotalPly),
      "Avg ply",
      chartColors.duration
    ),
    tooltip: {
      ...baseChartOption().tooltip,
      formatter: (params: { dataIndex: number } | Array<{ dataIndex: number }>) => {
        const point = Array.isArray(params) ? params[0] : params;
        const row = sorted[point.dataIndex];
        if (!row) return "";
        return [
          `${row.whiteBotId} vs ${row.blackBotId}`,
          `Games: ${row.gamesPlayed}`,
          `Avg ply: ${fixed(row.avgTotalPly)}`,
          `Avg duration: ${formatMs(row.avgDurationMs)}`,
        ].join("<br />");
      },
    },
  };
}
