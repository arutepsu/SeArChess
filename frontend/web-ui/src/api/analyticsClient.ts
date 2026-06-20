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
} from "./analyticsTypes";
import { authHeaders } from "./client";

const analyticsBase =
  (import.meta.env.VITE_ANALYTICS_API_BASE_URL?.trim() ?? "");

async function fetchAnalytics<T>(path: string): Promise<T> {
  const headers = new Headers();
  const auth = await authHeaders();
  for (const [key, value] of Object.entries(auth)) {
    headers.set(key, value);
  }

  const response = await fetch(`${analyticsBase}${path}`, { headers });

  if (!response.ok) {
    if (response.status === 401) {
      throw new Error("You are not logged in, or your session token is missing.");
    }
    const text = await response.text().catch(() => "");
    let message = `Analytics request failed: ${response.status}`;
    try {
      const json = JSON.parse(text) as { message?: string };
      if (json.message) message = json.message;
    } catch {
      // ignore parse error
    }
    throw new Error(message);
  }

  return (await response.json()) as T;
}

function analyticsPath(section: string, runId?: string): string {
  if (runId) return `/api/analytics/runs/${encodeURIComponent(runId)}/${section}`;
  return `/api/analytics/latest/${section}`;
}

export async function listAnalyticsRuns(): Promise<AnalyticsRunSummary[]> {
  const data = await fetchAnalytics<{ runs: AnalyticsRunSummary[] }>("/api/analytics/runs");
  return data.runs;
}

export async function fetchLeaderboard(runId?: string): Promise<LeaderboardRow[]> {
  const data = await fetchAnalytics<{ rows: LeaderboardRow[] }>(analyticsPath("leaderboard", runId));
  return data.rows;
}

export async function fetchBotFamilies(runId?: string): Promise<BotFamilyRow[]> {
  const data = await fetchAnalytics<{ rows: BotFamilyRow[] }>(analyticsPath("bot-families", runId));
  return data.rows;
}

export async function fetchStrategies(runId?: string): Promise<StrategyRow[]> {
  const data = await fetchAnalytics<{ rows: StrategyRow[] }>(analyticsPath("strategies", runId));
  return data.rows;
}

export async function fetchSearchessAi(runId?: string): Promise<SearchessAiComparisonRow[]> {
  const data = await fetchAnalytics<{ rows: SearchessAiComparisonRow[] }>(analyticsPath("searchess-ai", runId));
  return data.rows;
}

export async function fetchStockfish(runId?: string): Promise<StockfishComparisonRow[]> {
  const data = await fetchAnalytics<{ rows: StockfishComparisonRow[] }>(analyticsPath("stockfish", runId));
  return data.rows;
}

export async function fetchAvgGameLength(runId?: string): Promise<AvgGameLengthRow[]> {
  const data = await fetchAnalytics<{ rows: AvgGameLengthRow[] }>(analyticsPath("avg-game-length", runId));
  return data.rows;
}

export async function fetchEloRatings(runId?: string): Promise<EloRatingsRow[]> {
  const data = await fetchAnalytics<{ rows: EloRatingsRow[] }>(analyticsPath("elo-ratings", runId));
  return data.rows;
}

export async function fetchTerminations(runId?: string): Promise<TerminationReasonRow[]> {
  const data = await fetchAnalytics<{ rows: TerminationReasonRow[] }>(analyticsPath("terminations", runId));
  return data.rows;
}

export async function fetchColorPerformance(runId?: string): Promise<ColorPerformanceRow[]> {
  const data = await fetchAnalytics<{ rows: ColorPerformanceRow[] }>(analyticsPath("color-performance", runId));
  return data.rows;
}

export async function fetchFastestWins(runId?: string): Promise<FastestWinRow[]> {
  const data = await fetchAnalytics<{ rows: FastestWinRow[] }>(analyticsPath("fastest-wins", runId));
  return data.rows;
}

export async function fetchLiveGameResults(limit = 50): Promise<LiveGameResult[]> {
  const data = await fetchAnalytics<{ rows: LiveGameResult[] }>(
    `/api/analytics/live/game-results?limit=${limit}`
  );
  return data.rows;
}
