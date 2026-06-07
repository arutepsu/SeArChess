package chess.adapter.repository.mongo

import chess.application.port.repository.RepositoryError
import chess.application.session.model.{ExternalPlatform, GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import org.bson.Document

import java.time.Instant
import java.util.UUID

private[mongo] object MongoSessionMapper:

  def toDocument(session: GameSession): Document =
    Document()
      .append("_id", session.sessionId.value.toString)
      .append("sessionId", session.sessionId.value.toString)
      .append("gameId", session.gameId.value.toString)
      .append("mode", modeString(session.mode))
      .append("whiteController", controllerDocument(session.whiteController))
      .append("blackController", controllerDocument(session.blackController))
      .append("lifecycle", lifecycleString(session.lifecycle))
      .append("createdAt", session.createdAt.toString)
      .append("updatedAt", session.updatedAt.toString)
      .append("ownerUserId", session.ownerUserId.map(_.toString).orNull)
      .append("ownerNicknameSnapshot", session.ownerNicknameSnapshot.orNull)

  def toSession(document: Document): Either[RepositoryError, GameSession] =
    for
      sessionId <- parseUuid(document, "sessionId")
      gameId <- parseUuid(document, "gameId")
      mode <- parseMode(document.getString("mode"))
      whiteController <- parseController(document.get("whiteController", classOf[Document]))
      blackController <- parseController(document.get("blackController", classOf[Document]))
      lifecycle <- parseLifecycle(document.getString("lifecycle"))
      createdAt <- parseInstant(document, "createdAt")
      updatedAt <- parseInstant(document, "updatedAt")
      ownerUserId <- parseOptionalUuid(document, "ownerUserId")
    yield GameSession(
      sessionId = SessionId(sessionId),
      gameId = GameId(gameId),
      mode = mode,
      whiteController = whiteController,
      blackController = blackController,
      lifecycle = lifecycle,
      createdAt = createdAt,
      updatedAt = updatedAt,
      ownerUserId = ownerUserId,
      ownerNicknameSnapshot = Option(document.getString("ownerNicknameSnapshot"))
    )

  private def modeString(mode: SessionMode): String =
    mode match
      case SessionMode.HumanVsHuman       => "HumanVsHuman"
      case SessionMode.HumanVsAI          => "HumanVsAI"
      case SessionMode.AIVsAI             => "AIVsAI"
      case SessionMode.AiVsExternal       => "AiVsExternal"
      case SessionMode.ExternalVsExternal => "ExternalVsExternal"

  private def lifecycleString(lifecycle: SessionLifecycle): String =
    lifecycle match
      case SessionLifecycle.Created           => "Created"
      case SessionLifecycle.Active            => "Active"
      case SessionLifecycle.AwaitingPromotion => "AwaitingPromotion"
      case SessionLifecycle.Finished          => "Finished"
      case SessionLifecycle.Cancelled         => "Cancelled"

  private def controllerDocument(controller: SideController): Document =
    controller match
      case SideController.HumanLocal   =>
        Document("kind", "HumanLocal")
      case SideController.HumanRemote  =>
        Document("kind", "HumanRemote")
      case SideController.AI(engineId) =>
        val doc = Document("kind", "AI")
        engineId.foreach(doc.append("engineId", _))
        doc
      case SideController.External(platform, actorId) =>
        Document("kind", "External")
          .append("platform", platform.toString)
          .append("actorId", actorId)

  private def parseMode(value: String): Either[RepositoryError, SessionMode] =
    value match
      case "HumanVsHuman"       => Right(SessionMode.HumanVsHuman)
      case "HumanVsAI"          => Right(SessionMode.HumanVsAI)
      case "AIVsAI"             => Right(SessionMode.AIVsAI)
      case "AiVsExternal"       => Right(SessionMode.AiVsExternal)
      case "ExternalVsExternal" => Right(SessionMode.ExternalVsExternal)
      case other                => storageFailure(s"Unknown session mode in Mongo document: $other")

  private def parseLifecycle(value: String): Either[RepositoryError, SessionLifecycle] =
    value match
      case "Created"           => Right(SessionLifecycle.Created)
      case "Active"            => Right(SessionLifecycle.Active)
      case "AwaitingPromotion" => Right(SessionLifecycle.AwaitingPromotion)
      case "Finished"          => Right(SessionLifecycle.Finished)
      case "Cancelled"         => Right(SessionLifecycle.Cancelled)
      case other               => storageFailure(s"Unknown lifecycle in Mongo document: $other")

  private def parseController(document: Document): Either[RepositoryError, SideController] =
    Option(document) match
      case None => storageFailure("Missing controller document")
      case Some(value) =>
        val kind = value.getString("kind")
        val engineId = Option(value.getString("engineId"))
        kind match
          case "HumanLocal" if engineId.isEmpty  => Right(SideController.HumanLocal)
          case "HumanRemote" if engineId.isEmpty => Right(SideController.HumanRemote)
          case "AI"                              => Right(SideController.AI(engineId))
          case "External" =>
            val platform = Option(value.getString("platform"))
            val actorId  = Option(value.getString("actorId"))
            (platform, actorId) match
              case (Some(p), Some(a)) =>
                ExternalPlatform.values.find(_.toString == p) match
                  case Some(ep) => Right(SideController.External(ep, a))
                  case None     => storageFailure(s"Unknown external platform in Mongo document: $p")
              case _ =>
                storageFailure("External controller document missing 'platform' or 'actorId' field")
          case "HumanLocal" | "HumanRemote" =>
            storageFailure(s"Human controller $kind cannot have an AI engine id")
          case other =>
            storageFailure(s"Unknown controller in Mongo document: $other")

  private def parseUuid(document: Document, field: String): Either[RepositoryError, UUID] =
    try Right(UUID.fromString(document.getString(field)))
    catch case e: RuntimeException => storageFailure(s"Invalid UUID in $field: ${e.getMessage}")

  private def parseOptionalUuid(document: Document, field: String): Either[RepositoryError, Option[UUID]] =
    Option(document.getString(field)) match
      case None => Right(None)
      case Some(value) =>
        try Right(Some(UUID.fromString(value)))
        catch case e: RuntimeException => storageFailure(s"Invalid UUID in $field: ${e.getMessage}")

  private def parseInstant(document: Document, field: String): Either[RepositoryError, Instant] =
    try Right(Instant.parse(document.getString(field)))
    catch case e: RuntimeException => storageFailure(s"Invalid Instant in $field: ${e.getMessage}")

  private def storageFailure[A](message: String): Either[RepositoryError, A] =
    Left(RepositoryError.StorageFailure(message))
