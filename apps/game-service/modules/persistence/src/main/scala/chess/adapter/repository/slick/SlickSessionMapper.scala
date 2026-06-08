package chess.adapter.repository.slick

import chess.application.port.repository.RepositoryError
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.model.{ExternalPlatform, GameSession, SessionLifecycle, SessionMode, SideController}

import java.sql.Timestamp

object SlickSessionMapper:

  def toRow(tables: SlickTables)(session: GameSession): tables.SlickSessionRow =
    import tables.SlickSessionRow
    val (whiteKind, whiteEngineId) = controllerColumns(session.whiteController)
    val (blackKind, blackEngineId) = controllerColumns(session.blackController)

    SlickSessionRow(
      sessionId = session.sessionId.value,
      gameId = session.gameId.value,
      mode = modeString(session.mode),
      whiteControllerKind = whiteKind,
      whiteControllerEngineId = whiteEngineId,
      blackControllerKind = blackKind,
      blackControllerEngineId = blackEngineId,
      lifecycle = lifecycleString(session.lifecycle),
      createdAt = Timestamp.from(session.createdAt),
      updatedAt = Timestamp.from(session.updatedAt),
      ownerUserId = session.ownerUserId,
      ownerNicknameSnapshot = session.ownerNicknameSnapshot
    )

  def toSession(tables: SlickTables)(row: tables.SlickSessionRow): Either[RepositoryError, GameSession] =
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
      updatedAt = row.updatedAt.toInstant,
      ownerUserId = row.ownerUserId,
      ownerNicknameSnapshot = row.ownerNicknameSnapshot
    )

  private def modeString(mode: SessionMode): String =
    mode match
      case SessionMode.HumanVsHuman       => "HumanVsHuman"
      case SessionMode.HumanVsAI          => "HumanVsAI"
      case SessionMode.AIVsAI             => "AIVsAI"
      case SessionMode.HumanVsDeployedBot => "HumanVsDeployedBot"
      case SessionMode.AiVsExternal       => "AiVsExternal"
      case SessionMode.ExternalVsExternal => "ExternalVsExternal"

  private def lifecycleString(lifecycle: SessionLifecycle): String =
    lifecycle match
      case SessionLifecycle.Created           => "Created"
      case SessionLifecycle.Active            => "Active"
      case SessionLifecycle.AwaitingPromotion => "AwaitingPromotion"
      case SessionLifecycle.Finished          => "Finished"
      case SessionLifecycle.Cancelled         => "Cancelled"

  private def controllerColumns(controller: SideController): (String, Option[String]) =
    controller match
      case SideController.HumanLocal                   => ("HumanLocal", None)
      case SideController.HumanRemote                  => ("HumanRemote", None)
      case SideController.AI(engineId)                 => ("AI", engineId)
      case SideController.External(platform, actorId)  => (s"External:${platform}", Some(actorId))
      case SideController.DeployedBot                  => ("DeployedBot", None)

  private def parseMode(value: String): Either[RepositoryError, SessionMode] =
    value match
      case "HumanVsHuman"       => Right(SessionMode.HumanVsHuman)
      case "HumanVsAI"          => Right(SessionMode.HumanVsAI)
      case "AIVsAI"             => Right(SessionMode.AIVsAI)
      case "HumanVsDeployedBot" => Right(SessionMode.HumanVsDeployedBot)
      case "AiVsExternal"       => Right(SessionMode.AiVsExternal)
      case "ExternalVsExternal" => Right(SessionMode.ExternalVsExternal)
      case other                => storageFailure(s"Unknown session mode in DB: $other")

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
      case k if k.startsWith("External:") =>
        val platformStr = k.stripPrefix("External:")
        engineId match
          case None =>
            storageFailure(s"External controller '$k' is missing actorId (engine_id column)")
          case Some(actorId) =>
            ExternalPlatform.values.find(_.toString == platformStr) match
              case Some(platform) => Right(SideController.External(platform, actorId))
              case None           => storageFailure(s"Unknown external platform in DB: $platformStr")
      case "DeployedBot" if engineId.isEmpty => Right(SideController.DeployedBot)
      case "HumanLocal" | "HumanRemote" =>
        storageFailure(s"Human controller $kind cannot have an AI engine id")
      case "DeployedBot" =>
        storageFailure("DeployedBot controller cannot have an engine id")
      case other =>
        storageFailure(s"Unknown controller in DB: $other")

  private def storageFailure[A](message: String): Either[RepositoryError, A] =
    Left(RepositoryError.StorageFailure(message))
