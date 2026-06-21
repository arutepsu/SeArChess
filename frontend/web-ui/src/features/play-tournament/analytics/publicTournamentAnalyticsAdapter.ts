import type {
  AvgGameLengthRow,
  BotFamilyRow,
  ColorPerformanceRow,
  FastestWinRow,
  LeaderboardRow,
  StrategyRow,
  TerminationReasonRow,
} from "../../../api/analyticsTypes";
import type { PublicAnalyticsExport } from "../../../api/publicTournamentTypes";

// Normalized analytics model: the shared row types the existing ECharts components consume.
// Produced by adapting a PublicAnalyticsExport without touching the local bot tournament domain.
export interface PublicTournamentNormalizedAnalytics {
  leaderboard: LeaderboardRow[];
  botFamilies: BotFamilyRow[];
  strategies: StrategyRow[];
  terminations: TerminationReasonRow[];
  colorPerformance: ColorPerformanceRow[];
  fastestWins: FastestWinRow[];
  avgGameLength: AvgGameLengthRow[];
}

// Pure adapter: no side effects, no network calls, no external dependencies.
export function adaptPublicTournamentAnalytics(
  src: PublicAnalyticsExport,
): PublicTournamentNormalizedAnalytics {
  const { standings, games } = src;

  // ── Pre-compute per-bot average ply from the game list ──────────────────────
  const botPlyAccum = new Map<string, { sum: number; count: number }>();
  for (const g of games) {
    for (const name of [g.whiteBotName, g.blackBotName]) {
      const acc = botPlyAccum.get(name) ?? { sum: 0, count: 0 };
      acc.sum += g.totalPly;
      acc.count += 1;
      botPlyAccum.set(name, acc);
    }
  }

  // ── 1. LeaderboardRow ────────────────────────────────────────────────────────
  const leaderboard: LeaderboardRow[] = standings.map((s) => {
    const acc = botPlyAccum.get(s.botName);
    return {
      botId: s.botName,
      totalScore: s.points,
      wins: s.wins,
      draws: s.draws,
      losses: s.losses,
      gamesPlayed: s.nbGames,
      avgPly: acc && acc.count > 0 ? acc.sum / acc.count : 0,
      winRate: s.nbGames > 0 ? s.wins / s.nbGames : 0,
    };
  });

  // ── 2. BotFamilyRow (group standings by botFamily) ───────────────────────────
  const botFamilies = groupedRows(
    standings,
    (s) => s.botFamily ?? "(unknown)",
    (s) => ({ games: s.nbGames, wins: s.wins, draws: s.draws, losses: s.losses, totalScore: s.points }),
    (family, agg) => ({ family, ...agg, winRate: agg.games > 0 ? agg.wins / agg.games : 0 } satisfies BotFamilyRow),
  );

  // ── 3. StrategyRow (group standings by botStrategyType) ──────────────────────
  const strategies = groupedRows(
    standings,
    (s) => s.botStrategyType ?? "(unknown)",
    (s) => ({ games: s.nbGames, wins: s.wins, draws: s.draws, losses: s.losses, totalScore: s.points }),
    (strategyType, agg) => ({ strategyType, ...agg, winRate: agg.games > 0 ? agg.wins / agg.games : 0 } satisfies StrategyRow),
  );

  // ── 4. TerminationReasonRow (count by terminationReason) ─────────────────────
  const termMap = new Map<string, number>();
  for (const g of games) {
    termMap.set(g.terminationReason, (termMap.get(g.terminationReason) ?? 0) + 1);
  }
  const terminations: TerminationReasonRow[] = [...termMap.entries()].map(
    ([terminationReason, count]) => ({ terminationReason, count }),
  );

  // ── 5. ColorPerformanceRow (per bot, derived from game results) ───────────────
  type ColorAccum = {
    gamesAsWhite: number; whiteWins: number; whiteDraw: number;
    gamesAsBlack: number; blackWins: number; blackDraw: number;
  };
  const colorMap = new Map<string, ColorAccum>();
  const ensureColor = (name: string): ColorAccum => {
    if (!colorMap.has(name)) {
      colorMap.set(name, {
        gamesAsWhite: 0, whiteWins: 0, whiteDraw: 0,
        gamesAsBlack: 0, blackWins: 0, blackDraw: 0,
      });
    }
    return colorMap.get(name)!;
  };
  for (const g of games) {
    const w = ensureColor(g.whiteBotName);
    const b = ensureColor(g.blackBotName);
    w.gamesAsWhite += 1;
    b.gamesAsBlack += 1;
    if (g.winner === "white") {
      w.whiteWins += 1;
    } else if (g.winner === "black") {
      b.blackWins += 1;
    } else {
      w.whiteDraw += 1;
      b.blackDraw += 1;
    }
  }
  const colorPerformance: ColorPerformanceRow[] = [...colorMap.entries()].map(([botId, c]) => ({
    botId,
    gamesAsWhite: c.gamesAsWhite,
    whiteWins: c.whiteWins,
    whiteScore: c.whiteWins + c.whiteDraw * 0.5,
    gamesAsBlack: c.gamesAsBlack,
    blackWins: c.blackWins,
    blackScore: c.blackWins + c.blackDraw * 0.5,
  }));

  // ── 6. FastestWinRow (per winner bot, decisive games only) ───────────────────
  const fastMap = new Map<string, { plies: number[]; durations: number[] }>();
  for (const g of games) {
    if (!g.winner) continue;
    const winnerName = g.winner === "white" ? g.whiteBotName : g.blackBotName;
    const entry = fastMap.get(winnerName) ?? { plies: [], durations: [] };
    entry.plies.push(g.totalPly);
    if (g.durationMillis !== null) entry.durations.push(g.durationMillis);
    fastMap.set(winnerName, entry);
  }
  const fastestWins: FastestWinRow[] = [...fastMap.entries()].map(([winnerBotId, { plies, durations }]) => ({
    winnerBotId,
    decisiveGames: plies.length,
    avgWinPly: plies.reduce((s, p) => s + p, 0) / plies.length,
    minWinPly: Math.min(...plies),
    avgWinDurationMs:
      durations.length > 0 ? durations.reduce((s, d) => s + d, 0) / durations.length : 0,
  }));

  // ── 7. AvgGameLengthRow (per white-black name pairing) ───────────────────────
  type PairingAccum = { plies: number[]; durations: number[]; gamesPlayed: number };
  const pairingMap = new Map<string, PairingAccum>();
  for (const g of games) {
    const key = `${g.whiteBotName}\0${g.blackBotName}`;
    const entry = pairingMap.get(key) ?? { plies: [], durations: [], gamesPlayed: 0 };
    entry.plies.push(g.totalPly);
    if (g.durationMillis !== null) entry.durations.push(g.durationMillis);
    entry.gamesPlayed += 1;
    pairingMap.set(key, entry);
  }
  const avgGameLength: AvgGameLengthRow[] = [...pairingMap.entries()].map(
    ([key, { plies, durations, gamesPlayed }]) => {
      const sep = key.indexOf("\0");
      return {
        whiteBotId: key.slice(0, sep),
        blackBotId: key.slice(sep + 1),
        gamesPlayed,
        avgTotalPly: plies.reduce((s, p) => s + p, 0) / plies.length,
        avgDurationMs:
          durations.length > 0 ? durations.reduce((s, d) => s + d, 0) / durations.length : 0,
      };
    },
  );

  return { leaderboard, botFamilies, strategies, terminations, colorPerformance, fastestWins, avgGameLength };
}

// ── Generic helper ─────────────────────────────────────────────────────────────

type NumericAgg = { games: number; wins: number; draws: number; losses: number; totalScore: number };

function groupedRows<S, R>(
  items: S[],
  key: (s: S) => string,
  extract: (s: S) => NumericAgg,
  build: (key: string, agg: NumericAgg) => R,
): R[] {
  const acc = new Map<string, NumericAgg>();
  for (const s of items) {
    const k = key(s);
    const e = acc.get(k) ?? { games: 0, wins: 0, draws: 0, losses: 0, totalScore: 0 };
    const v = extract(s);
    e.games += v.games;
    e.wins += v.wins;
    e.draws += v.draws;
    e.losses += v.losses;
    e.totalScore += v.totalScore;
    acc.set(k, e);
  }
  return [...acc.entries()].map(([k, v]) => build(k, v));
}
