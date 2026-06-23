import {
  getPublicTournament,
  getPublicTournamentAnalyticsExport,
  getPublicTournamentRound,
} from "../../../api/publicTournamentClient";
import {
  isAnalyticsNotReady,
  type PublicAnalyticsExport,
  type PublicAnalyticsExportGame,
  type PublicRoundGame,
  type PublicTournamentInfo,
  type PublicTournamentListResponse,
  type TournamentGameRow,
} from "../../../api/publicTournamentTypes";

// Process at most this many finished tournaments (newest-last in the array).
const FINISHED_LIMIT = 10;

function analyticsGameToRow(g: PublicAnalyticsExportGame, t: PublicTournamentInfo): TournamentGameRow {
  return {
    gameId:           g.gameId,
    tournamentId:     g.tournamentId,
    tournamentName:   t.fullName,
    tournamentStatus: "finished",
    round:            g.round,
    whiteBotId:       g.whiteBotId,
    whiteBotName:     g.whiteBotName,
    blackBotId:       g.blackBotId,
    blackBotName:     g.blackBotName,
    status:           "finished",
    winner:           g.winner,
    winnerBotId:      g.winnerBotId,
    winnerBotName:
      g.winner === "white" ? g.whiteBotName :
      g.winner === "black" ? g.blackBotName :
      null,
    terminationReason: g.terminationReason || null,
    totalPly:          g.totalPly,
    source:            "analytics",
  };
}

function roundGameToRow(g: PublicRoundGame, round: number, t: PublicTournamentInfo): TournamentGameRow {
  return {
    gameId:           g.gameId,
    tournamentId:     t.id,
    tournamentName:   t.fullName,
    tournamentStatus: "started",
    round,
    whiteBotId:       g.white.id,
    whiteBotName:     g.white.name,
    blackBotId:       g.black.id,
    blackBotName:     g.black.name,
    status:           g.status,
    winner:           g.winner,
    winnerBotId:
      g.winner === "white" ? g.white.id :
      g.winner === "black" ? g.black.id :
      null,
    winnerBotName:
      g.winner === "white" ? g.white.name :
      g.winner === "black" ? g.black.name :
      null,
    terminationReason: null,
    totalPly:          null,
    source:            "round",
  };
}

export interface GameLoadResult {
  games: TournamentGameRow[];
  errors: string[];
  exports: PublicAnalyticsExport[];
}

export async function loadTournamentGames(
  data: PublicTournamentListResponse
): Promise<GameLoadResult> {
  const games: TournamentGameRow[] = [];
  const errors: string[] = [];
  const exports: PublicAnalyticsExport[] = [];

  // ── Finished tournaments: analytics export ────────────────────────────────
  // Slice the last N to prefer newer tournaments when the list is long.
  const finished = data.finished.slice(-FINISHED_LIMIT);
  const analyticsSettled = await Promise.allSettled(
    finished.map((t) => getPublicTournamentAnalyticsExport(t.id))
  );

  analyticsSettled.forEach((result, i) => {
    const t = finished[i];
    if (result.status === "fulfilled") {
      const ar = result.value;
      if (isAnalyticsNotReady(ar)) {
        errors.push(`"${t.fullName}": analytics not yet available`);
      } else {
        ar.games.forEach((g) => games.push(analyticsGameToRow(g, t)));
        exports.push(ar);
      }
    } else {
      const msg = result.reason instanceof Error ? result.reason.message : String(result.reason);
      errors.push(`"${t.fullName}": ${msg}`);
    }
  });

  // ── Started tournaments: round pairings ───────────────────────────────────
  for (const t of data.started) {
    let roundCount = 0;
    try {
      const detail = await getPublicTournament(t.id);
      roundCount = detail.round;
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e);
      errors.push(`"${t.fullName}" detail: ${msg}`);
      continue;
    }

    if (roundCount < 1) continue;

    const roundNums = Array.from({ length: roundCount }, (_, i) => i + 1);
    const roundSettled = await Promise.allSettled(
      roundNums.map((r) => getPublicTournamentRound(t.id, r))
    );

    roundSettled.forEach((result, i) => {
      const round = roundNums[i];
      if (result.status === "fulfilled") {
        result.value.games.forEach((g) => games.push(roundGameToRow(g, round, t)));
      } else {
        const msg = result.reason instanceof Error ? result.reason.message : String(result.reason);
        errors.push(`"${t.fullName}" round ${round}: ${msg}`);
      }
    });
  }

  return { games, errors, exports };
}
