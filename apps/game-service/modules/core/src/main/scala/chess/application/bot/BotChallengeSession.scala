package chess.application.bot

import java.time.Instant
import java.util.UUID

enum BotChallengeStatus:
  case Requested, Sent, Failed

enum BotChallengeColor:
  case White, Black, Random

final case class BotChallengeSession(
    id: UUID,
    requestedByUserId: UUID,
    requestedByNicknameSnapshot: String,
    lichessUsername: String,
    lichessUserId: Option[String],
    lichessChallengeId: Option[String],
    lichessChallengeUrl: Option[String],
    status: BotChallengeStatus,
    clockLimitSeconds: Int,
    clockIncrementSeconds: Int,
    color: BotChallengeColor,
    rated: Boolean,
    createdAt: Instant,
    updatedAt: Instant,
    failureReason: Option[String]
)
