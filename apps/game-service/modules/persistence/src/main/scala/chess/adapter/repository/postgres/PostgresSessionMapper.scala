package chess.adapter.repository.postgres

import chess.application.port.repository.RepositoryError
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}

import java.sql.Timestamp

private[postgres] object PostgresSessionMapper:

  def toRow(session: GameSession): PostgresSessionRow =
    val (whiteKind, whiteEngineId) = controllerColumns(session.whiteController)
    val (blackKind, blackEngineId) = controllerColumns(session.blackController)

    PostgresSessionRow(
      sessionId = session.sessionId.value,
      gameId = session.gameId.value,
      mode = modeString(session.mode),
      whiteControllerKind = whiteKind,
      whiteControllerEngineId = whiteEngineId,
      blackControllerKind = blackKind,
      blackControllerEngineId = blackEngineId,
      lifecycle = lifecycleString(session.lifecycle),
      createdAt = Timestamp.from(session.createdAt),
      updatedAt = Timestamp.from(session.updatedAt)
    )

  def toSession(row: PostgresSessionRow): Either[RepositoryError, GameSession] =
    for
      mode <- parseMode(row.mode)
      whiteController <- parseController(row.whiteControllerKind, row.whiteControllerEngineId)
      blackController <- parseController(row.blackControllerKind, row.blackControllerEngineId)
      lifecycle <- parseLifecycle(row.lifecycle)
    yield GameSession(
      sessionId = SessionId(row.sessionId),
      gameId = GameId(row.gameId),
      mode = mode,
      whiteController = whiteController,
      blackController = blackController,
      lifecycle = lifecycle,
      createdAt = row.createdAt.toInstant,
      updatedAt = row.updatedAt.toInstant
    )

  private def modeString(mode: SessionMode): String =
    mode match
      case SessionMode.HumanVsHuman => "HumanVsHuman"
      case SessionMode.HumanVsAI    => "HumanVsAI"
      case SessionMode.AIVsAI       => "AIVsAI"

  private def lifecycleString(lifecycle: SessionLifecycle): String =
    lifecycle match
      case SessionLifecycle.Created           => "Created"
      case SessionLifecycle.Active            => "Active"
      case SessionLifecycle.AwaitingPromotion => "AwaitingPromotion"
      case SessionLifecycle.Finished          => "Finished"
      case SessionLifecycle.Cancelled         => "Cancelled"

  private def controllerColumns(controller: SideController): (String, Option[String]) =
    controller match
      case SideController.HumanLocal  => ("HumanLocal", None)
      case SideController.HumanRemote => ("HumanRemote", None)
      case SideController.AI(engineId) => ("AI", engineId)

  private def parseMode(value: String): Either[RepositoryError, SessionMode] =
    value match
      case "HumanVsHuman" => Right(SessionMode.HumanVsHuman)
      case "HumanVsAI"    => Right(SessionMode.HumanVsAI)
      case "AIVsAI"       => Right(SessionMode.AIVsAI)
      case other          => storageFailure(s"Unknown session mode in DB: $other")

  private def parseLifecycle(value: String): Either[RepositoryError, SessionLifecycle] =
    value match
      case "Created"           => Right(SessionLifecycle.Created)
      case "Active"            => Right(SessionLifecycle.Active)
      case "AwaitingPromotion" => Right(SessionLifecycle.AwaitingPromotion)
      case "Finished"          => Right(SessionLifecycle.Finished)
      case "Cancelled"         => Right(SessionLifecycle.Cancelled)
      case other               => storageFailure(s"Unknown lifecycle in DB: $other")

  private def parseController(
      kind: String,
      engineId: Option[String]
  ): Either[RepositoryError, SideController] =
    kind match
      case "HumanLocal" if engineId.isEmpty  => Right(SideController.HumanLocal)
      case "HumanRemote" if engineId.isEmpty => Right(SideController.HumanRemote)
      case "AI"                              => Right(SideController.AI(engineId))
      case "HumanLocal" | "HumanRemote" =>
        storageFailure(s"Human controller $kind cannot have an AI engine id")
      case other =>
        storageFailure(s"Unknown controller in DB: $other")

  private def storageFailure[A](message: String): Either[RepositoryError, A] =
    Left(RepositoryError.StorageFailure(message))
