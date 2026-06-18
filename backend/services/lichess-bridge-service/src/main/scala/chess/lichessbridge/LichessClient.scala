package chess.lichessbridge

import cats.effect.IO

/** Lichess HTTP API client trait (request/response only — streaming is in LichessEventStream/LichessGameStream).
  *
  * Phase 2A:   getBotProfile, validateToken, challengeAi.
  * Phase 2B-1: acceptChallenge, declineChallenge.
  * Phase 2B-2: submitMove.
  */
trait LichessClient[F[_]]:
  def getBotProfile(token: String): F[Either[LichessError, BotProfile]]
  def validateToken(token: String): F[Either[LichessError, Boolean]]
  def challengeAi(
      token: String,
      level: Int = 1,
      clockLimit: Int = 300,
      clockIncrement: Int = 0
  ): F[Either[LichessError, ChallengeResult]]
  def acceptChallenge(token: String, challengeId: String): F[Either[LichessError, Unit]]
  def declineChallenge(token: String, challengeId: String, reason: String): F[Either[LichessError, Unit]]
  /** POST /api/board/game/{gameId}/move/{move}
    * Token is passed as Authorization header and never echoed in error messages.
    */
  def submitMove(token: String, gameId: String, move: String): F[Either[LichessError, Unit]]
  /** POST /api/bot/game/{gameId}/chat with room=player form body.
    * Chat failures must never surface to callers as thrown exceptions.
    */
  def sendChatMessage(token: String, gameId: String, text: String): F[Either[LichessError, Unit]]

// ── Domain types ──────────────────────────────────────────────────────────────

final case class BotProfile(
    id: String,
    username: String,
    title: Option[String],
    isBot: Boolean
)

final case class ChallengeResult(gameId: String, gameUrl: String)

// ── Error enum ────────────────────────────────────────────────────────────────

enum LichessError:
  case Unauthorized(message: String)
  case RateLimited(retryAfterSeconds: Option[Int])
  case NetworkError(message: String)
  case UnexpectedResponse(status: Int, body: String)
