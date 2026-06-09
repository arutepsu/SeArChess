package chess.lichessbridge

import java.time.Instant

// ── Bot event stream types ─────────────────────────────────────────────────────
// Received from GET /api/stream/event (persistent NDJSON stream)

enum LichessBotEvent:
  case ChallengeCreated(challenge: LichessChallenge)
  case ChallengeCanceled(challengeId: String)
  case GameStart(game: LichessGameRef)
  case GameFinish(game: LichessGameRef)
  case Unknown(eventType: String, raw: String)

final case class LichessChallenge(
    challengeId: String,
    challengerUsername: String,
    variant: String,
    speed: String,
    rated: Boolean,
    timeControl: LichessTimeControl,
    color: Option[String]
)

final case class LichessTimeControl(
    tcType: String,
    limit: Option[Int],     // total clock seconds (None for unlimited/correspondence)
    increment: Option[Int]  // per-move increment in seconds
)

final case class LichessGameRef(
    gameId: String,
    url: String,
    opponent: String,
    side: Option[String]  // "white" | "black" — present when known
)

// ── Game event stream types ────────────────────────────────────────────────────
// Received from GET /api/board/game/stream/{gameId} (NDJSON stream per active game)

enum LichessGameEvent:
  case GameFull(
      id: String,
      white: String,
      black: String,
      initialFen: String,
      moves: String,
      wtime: Int,
      btime: Int,
      status: String
  )
  case GameState(moves: String, wtime: Int, btime: Int, status: String)
  case ChatLine(username: String, text: String)
  case OpponentGone(gone: Boolean, claimWinInSeconds: Option[Int])
  case Unknown(eventType: String, raw: String)

// ── Active game metadata ───────────────────────────────────────────────────────
// Safe to expose in /internal/lichess/status — no token, no PII beyond opponent name

final case class ActiveGame(
    gameId: String,
    opponent: String,
    side: Option[String],
    startedAt: Instant
)

// ── Parse error ────────────────────────────────────────────────────────────────

final case class ParseError(message: String, raw: String)
