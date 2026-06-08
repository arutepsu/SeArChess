package chess.adapter.repository.slick

import _root_.slick.jdbc.JdbcProfile

import java.sql.Timestamp
import java.util.UUID

final class SlickTables(val profile: JdbcProfile, schema: Option[String] = None):
  import profile.api.*

  final case class SlickSessionRow(
      sessionId: UUID,
      gameId: UUID,
      mode: String,
      whiteControllerKind: String,
      whiteControllerEngineId: Option[String],
      blackControllerKind: String,
      blackControllerEngineId: Option[String],
      lifecycle: String,
      createdAt: Timestamp,
      updatedAt: Timestamp,
      ownerUserId: Option[UUID],
      ownerNicknameSnapshot: Option[String]
  )

  final class SlickSessionTable(tag: Tag)
      extends Table[SlickSessionRow](tag, schema, "sessions"):

    def sessionId = column[UUID]("session_id", O.PrimaryKey)

    def gameId = column[UUID]("game_id", O.Unique)

    def mode = column[String]("mode")

    def whiteControllerKind = column[String]("white_controller_kind")

    def whiteControllerEngineId = column[Option[String]]("white_controller_engine_id")

    def blackControllerKind = column[String]("black_controller_kind")

    def blackControllerEngineId = column[Option[String]]("black_controller_engine_id")

    def lifecycle = column[String]("lifecycle")

    def createdAt = column[Timestamp]("created_at")

    def updatedAt = column[Timestamp]("updated_at")

    def ownerUserId = column[Option[UUID]]("owner_user_id")

    def ownerNicknameSnapshot = column[Option[String]]("owner_nickname_snapshot")

    def * =
      (
        sessionId,
        gameId,
        mode,
        whiteControllerKind,
        whiteControllerEngineId,
        blackControllerKind,
        blackControllerEngineId,
        lifecycle,
        createdAt,
        updatedAt,
        ownerUserId,
        ownerNicknameSnapshot
      ).mapTo[SlickSessionRow]

  final case class SlickGameStateRow(
      gameId: UUID,
      stateJson: String
  )

  final class SlickGameStateTable(tag: Tag)
      extends Table[SlickGameStateRow](tag, schema, "game_states"):

    def gameId = column[UUID]("game_id", O.PrimaryKey)

    def stateJson = column[String]("state_json")

    def * = (gameId, stateJson).mapTo[SlickGameStateRow]

  val Sessions = TableQuery[SlickSessionTable]

  val GameStates = TableQuery[SlickGameStateTable]

  // ── BotTurnTask ───────────────────────────────────────────────────────────

  final case class SlickBotTurnTaskRow(
      id: UUID,
      sessionId: UUID,
      gameId: UUID,
      botActorId: String,
      sideToMove: String,
      status: String,
      leaseUntil: Option[Timestamp],
      attemptCount: Int,
      lastError: Option[String],
      createdAt: Timestamp,
      updatedAt: Timestamp
  )

  final class SlickBotTurnTaskTable(tag: Tag)
      extends Table[SlickBotTurnTaskRow](tag, schema, "bot_turn_tasks"):

    def id           = column[UUID]("id", O.PrimaryKey)
    def sessionId    = column[UUID]("session_id")
    def gameId       = column[UUID]("game_id")
    def botActorId   = column[String]("bot_actor_id")
    def sideToMove   = column[String]("side_to_move")
    def status       = column[String]("status")
    def leaseUntil   = column[Option[Timestamp]]("lease_until")
    def attemptCount = column[Int]("attempt_count")
    def lastError    = column[Option[String]]("last_error")
    def createdAt    = column[Timestamp]("created_at")
    def updatedAt    = column[Timestamp]("updated_at")

    def * = (
      id, sessionId, gameId, botActorId, sideToMove, status,
      leaseUntil, attemptCount, lastError, createdAt, updatedAt
    ).mapTo[SlickBotTurnTaskRow]

  val BotTurnTasks = TableQuery[SlickBotTurnTaskTable]
