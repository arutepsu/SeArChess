import type { TournamentGameRow } from "../../../api/publicTournamentTypes";

// ── Exported types ─────────────────────────────────────────────────────────────

export interface BotStat {
  botId: string;
  botName: string;
  games: number;
  wins: number;
  draws: number;
  losses: number;
  score: number;      // wins + 0.5 * draws
  winRate: number;    // 0-1
  avgPly: number | null;
}

export interface TerminationRow {
  reason: string;
  count: number;
  pct: number; // 0-100
}

export interface LengthBucket {
  label: string;
  count: number;
}

export interface GlobalSummary {
  totalFinished: number;
  decisive: number;
  draws: number;
  avgPly: number | null;
  shortestPly: number | null;
  longestPly: number | null;
  uniqueBots: number;
  tournaments: number;
  excludedLive: number;
}

export interface MySummary {
  games: number;
  wins: number;
  draws: number;
  losses: number;
  scoreRate: number; // (wins + 0.5*draws) / games
  winRate: number;   // wins / games
  avgPly: number | null;
}

export interface AnalyticsData {
  global: GlobalSummary;
  mySummary: MySummary | null;
  bots: BotStat[];
  terminations: TerminationRow[];
  lengthBuckets: LengthBucket[];
}

// ── Internal accumulators ──────────────────────────────────────────────────────

interface BotAccum {
  botId: string;
  botName: string;
  games: number;
  wins: number;
  draws: number;
  losses: number;
  score: number;
  plySum: number;
  plyCount: number;
}

// ── Bucket boundaries ──────────────────────────────────────────────────────────

const BUCKETS: Array<{ label: string; min: number; max: number }> = [
  { label: "0-20",   min: 0,   max: 20  },
  { label: "21-40",  min: 21,  max: 40  },
  { label: "41-60",  min: 41,  max: 60  },
  { label: "61-100", min: 61,  max: 100 },
  { label: "101+",   min: 101, max: Infinity },
];

function bucketLabel(ply: number): string {
  for (const b of BUCKETS) {
    if (ply >= b.min && ply <= b.max) return b.label;
  }
  return "101+";
}

// ── Main aggregation ───────────────────────────────────────────────────────────

export function aggregateGames(
  rows: TournamentGameRow[],
  ownedIds: Set<string>
): AnalyticsData {
  // Only finished games have reliable, complete data.
  const finished = rows.filter((r) => r.source === "analytics");
  const excludedLive = rows.length - finished.length;

  const botAccums = new Map<string, BotAccum>();
  const terminationMap = new Map<string, number>();
  const bucketCounts: Record<string, number> = {};
  for (const b of BUCKETS) bucketCounts[b.label] = 0;
  const tournamentIds = new Set<string>();

  let plySum = 0;
  let plyCount = 0;
  let shortestPly: number | null = null;
  let longestPly: number | null = null;
  let decisive = 0;
  let draws = 0;

  // My bots per-side accumulators (counts each bot-game-slot separately)
  let myGames = 0;
  let myWins = 0;
  let myDraws = 0;
  let myLosses = 0;
  let myPlySum = 0;
  let myPlyCount = 0;

  function ensureBot(id: string, name: string): BotAccum {
    let a = botAccums.get(id);
    if (!a) {
      a = { botId: id, botName: name, games: 0, wins: 0, draws: 0, losses: 0, score: 0, plySum: 0, plyCount: 0 };
      botAccums.set(id, a);
    }
    return a;
  }

  for (const row of finished) {
    tournamentIds.add(row.tournamentId);

    // Termination reason
    const reason = row.terminationReason?.trim() || "unknown";
    terminationMap.set(reason, (terminationMap.get(reason) ?? 0) + 1);

    // Ply tracking
    if (row.totalPly !== null) {
      plySum += row.totalPly;
      plyCount++;
      if (shortestPly === null || row.totalPly < shortestPly) shortestPly = row.totalPly;
      if (longestPly === null || row.totalPly > longestPly) longestPly = row.totalPly;
      const label = bucketLabel(row.totalPly);
      bucketCounts[label] = (bucketCounts[label] ?? 0) + 1;
    }

    // Result counters
    if (row.winner !== null) decisive++; else draws++;

    // Per-bot stats
    const white = ensureBot(row.whiteBotId, row.whiteBotName);
    const black = ensureBot(row.blackBotId, row.blackBotName);

    white.games++;
    black.games++;
    if (row.totalPly !== null) {
      white.plySum += row.totalPly; white.plyCount++;
      black.plySum += row.totalPly; black.plyCount++;
    }

    if (row.winner === "white") {
      white.wins++; white.score += 1;
      black.losses++;
    } else if (row.winner === "black") {
      black.wins++; black.score += 1;
      white.losses++;
    } else {
      white.draws++; white.score += 0.5;
      black.draws++; black.score += 0.5;
    }

    // My bots — count each owned side separately
    const whiteSideWins = row.winner === "white";
    const blackSideWins = row.winner === "black";

    if (ownedIds.has(row.whiteBotId)) {
      myGames++;
      if (row.totalPly !== null) { myPlySum += row.totalPly; myPlyCount++; }
      if (whiteSideWins) myWins++;
      else if (blackSideWins) myLosses++;
      else myDraws++;
    }
    if (ownedIds.has(row.blackBotId)) {
      myGames++;
      if (row.totalPly !== null) { myPlySum += row.totalPly; myPlyCount++; }
      if (blackSideWins) myWins++;
      else if (whiteSideWins) myLosses++;
      else myDraws++;
    }
  }

  // Finalize bot stats
  const bots: BotStat[] = Array.from(botAccums.values()).map((a) => ({
    botId:   a.botId,
    botName: a.botName,
    games:   a.games,
    wins:    a.wins,
    draws:   a.draws,
    losses:  a.losses,
    score:   a.score,
    winRate: a.games > 0 ? a.wins / a.games : 0,
    avgPly:  a.plyCount > 0 ? Math.round(a.plySum / a.plyCount) : null,
  }));
  bots.sort((a, b) => b.score - a.score || b.winRate - a.winRate);

  // Termination breakdown sorted by count descending
  const total = finished.length;
  const terminations: TerminationRow[] = Array.from(terminationMap.entries())
    .sort((a, b) => b[1] - a[1])
    .map(([reason, count]) => ({ reason, count, pct: total > 0 ? (count / total) * 100 : 0 }));

  // Length buckets
  const lengthBuckets: LengthBucket[] = BUCKETS.map((b) => ({
    label: b.label,
    count: bucketCounts[b.label] ?? 0,
  }));

  // Global summary
  const global: GlobalSummary = {
    totalFinished: finished.length,
    decisive,
    draws,
    avgPly: plyCount > 0 ? Math.round(plySum / plyCount) : null,
    shortestPly,
    longestPly,
    uniqueBots: botAccums.size,
    tournaments: tournamentIds.size,
    excludedLive,
  };

  // My summary
  const mySummary: MySummary | null = ownedIds.size === 0 ? null : {
    games: myGames,
    wins:  myWins,
    draws: myDraws,
    losses: myLosses,
    scoreRate: myGames > 0 ? (myWins + 0.5 * myDraws) / myGames : 0,
    winRate:   myGames > 0 ? myWins / myGames : 0,
    avgPly:    myPlyCount > 0 ? Math.round(myPlySum / myPlyCount) : null,
  };

  return { global, mySummary, bots, terminations, lengthBuckets };
}
