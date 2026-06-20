package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.{
  AiMoveResponse,
  ExternalGameResponse,
  FinishExternalGameRequest,
  IngestMovesRequest,
  IngestMovesResponse,
  StartExternalGameRequest as StartExternalGameRequestDto
}
import chess.application.ai.service.AITurnError
import chess.application.external.{
  AiMoveResult,
  BindingStatus,
  ExternalGameBinding,
  ExternalGameError,
  ExternalGameServiceApi,
  IngestMovesResult,
  NextAction,
  StartExternalGameRequest as StartExternalGameCommand,
  VerifiedExternalCaller
}
import chess.application.port.ai.AIError
import chess.application.port.repository.RepositoryError
import chess.application.session.model.{ExternalPlatform, SessionMode}
import chess.application.session.service.{SessionError, SessionMoveError}
import chess.domain.model.{Color, DrawReason, GameStatus}
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

final case class BotCredentials(apiKey: String, caller: VerifiedExternalCaller)

final class ExternalGameRouteAuth(credentials: List[BotCredentials]):
  private val ApiKeyHeader = CIString("X-Bot-Api-Key")
  private val byKey        = credentials.map(c => c.apiKey -> c.caller).toMap

  def verify(req: Request[IO]): Either[String, VerifiedExternalCaller] =
    apiKey(req) match
      case None      => Left("Bot API key is required.")
      case Some(key) => byKey.get(key).toRight("Bot API key is invalid.")

  private def apiKey(req: Request[IO]): Option[String] =
    req.headers
      .get(ApiKeyHeader)
      .map(_.head.value)
      .orElse(
        req.headers
          .get(CIString("Authorization"))
          .map(_.head.value)
          .collect { case bearer if bearer.startsWith("Bearer ") => bearer.drop("Bearer ".length) }
      )
      .map(_.trim)
      .filter(_.nonEmpty)

/** Dedicated REST adapter for externally hosted games.
  *
  * These routes are intentionally separate from `/sessions` and `/games`; all commands go through
  * [[ExternalGameServiceApi]], which owns external binding authorization and durable state changes.
  */
class Http4sExternalGameRoutes(
    externalGameService: ExternalGameServiceApi,
    auth: ExternalGameRouteAuth
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "external-games" =>
      withCaller(req) { caller =>
        req.bodyText.compile.string.flatMap(handleStart(caller, _))
      }

    case req @ GET -> Root / "external-games" / platform / externalGameId =>
      withCaller(req) { _ =>
        parsePlatform(platform) match
          case Left(msg) => jsonError(Status.BadRequest, "BAD_REQUEST", msg)
          case Right(p) =>
            IO.blocking(externalGameService.getExternalGame(p, externalGameId)).flatMap {
              case Right(binding) => jsonResponse(Status.Ok, ExternalGameResponse.toJson(toDto(binding)))
              case Left(err)      => errorResponse(err)
            }
        end match
      }

    case req @ POST -> Root / "external-games" / platform / externalGameId / "moves" =>
      withCaller(req) { caller =>
        req.bodyText.compile.string.flatMap(handleMoves(caller, platform, externalGameId, _))
      }

    case req @ POST -> Root / "external-games" / platform / externalGameId / "ai-move" =>
      withCaller(req) { caller =>
        parsePlatform(platform) match
          case Left(msg) => jsonError(Status.BadRequest, "BAD_REQUEST", msg)
          case Right(p) =>
            IO.blocking(externalGameService.requestAiMove(caller, p, externalGameId)).flatMap {
              case Right(result) => jsonResponse(Status.Ok, AiMoveResponse.toJson(toDto(result)))
              case Left(err)     => errorResponse(err)
            }
        end match
      }

    case req @ POST -> Root / "external-games" / platform / externalGameId / "finish" =>
      withCaller(req) { caller =>
        req.bodyText.compile.string.flatMap(handleFinish(caller, platform, externalGameId, _))
      }
  }

  private def withCaller(
      req: Request[IO]
  )(handle: VerifiedExternalCaller => IO[Response[IO]]): IO[Response[IO]] =
    auth.verify(req) match
      case Right(caller) => handle(caller)
      case Left(msg)     => jsonError(Status.Unauthorized, "UNAUTHORIZED", msg)

  private def handleStart(caller: VerifiedExternalCaller, body: String): IO[Response[IO]] =
    val result =
      for
        dto <- StartExternalGameRequestDto
          .fromJson(body)
          .left
          .map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        platform <- parsePlatform(dto.platform).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        _ <- Either
          .cond(platform == caller.platform, (), "Caller is not authorized for this platform.")
          .left
          .map(msg => (Status.Forbidden, "FORBIDDEN", msg))
        mode <- parseExternalMode(dto.mode).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        color <- parseColor(dto.ourColor).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
      yield StartExternalGameCommand(
        platform = platform,
        externalGameId = dto.externalGameId,
        mode = mode,
        ourActorId = caller.actorId,
        ourColor = color,
        opponentActorId = dto.opponentActorId
      )

    result match
      case Left((status, code, message)) => jsonError(status, code, message)
      case Right(command) =>
        IO.blocking(externalGameService.startExternalGame(command)).flatMap {
          case Right(binding) => jsonResponse(Status.Ok, ExternalGameResponse.toJson(toDto(binding)))
          case Left(err)      => errorResponse(err)
        }

  private def handleMoves(
      caller: VerifiedExternalCaller,
      platform: String,
      externalGameId: String,
      body: String
  ): IO[Response[IO]] =
    val result =
      for
        p <- parsePlatform(platform).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        dto <- IngestMovesRequest
          .fromJson(body)
          .left
          .map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
      yield (p, dto)

    result match
      case Left((status, code, message)) => jsonError(status, code, message)
      case Right((p, dto)) =>
        IO.blocking(externalGameService.ingestExternalMoves(caller, p, externalGameId, dto.uciMoves))
          .flatMap {
            case Right(result) => jsonResponse(Status.Ok, IngestMovesResponse.toJson(toDto(result)))
            case Left(err)     => errorResponse(err)
          }

  private def handleFinish(
      caller: VerifiedExternalCaller,
      platform: String,
      externalGameId: String,
      body: String
  ): IO[Response[IO]] =
    val result =
      for
        p <- parsePlatform(platform).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        _ <- FinishExternalGameRequest
          .fromJson(body)
          .left
          .map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
      yield p

    result match
      case Left((status, code, message)) => jsonError(status, code, message)
      case Right(p) =>
        IO.blocking(externalGameService.finishExternalGame(caller, p, externalGameId)).flatMap {
          case Right(binding) => jsonResponse(Status.Ok, ExternalGameResponse.toJson(toDto(binding)))
          case Left(err)      => errorResponse(err)
        }

  private def parsePlatform(value: String): Either[String, ExternalPlatform] =
    value.trim.toLowerCase match
      case "lichess" => Right(ExternalPlatform.Lichess)
      case other     => Left(s"Unsupported external platform: $other")

  private def parseExternalMode(value: String): Either[String, SessionMode] =
    value.trim match
      case "AiVsExternal"      => Right(SessionMode.AiVsExternal)
      case "ExternalVsExternal" => Right(SessionMode.ExternalVsExternal)
      case other =>
        Left(s"Unsupported external game mode: $other. Use AiVsExternal or ExternalVsExternal")

  private def parseColor(value: String): Either[String, Color] =
    value.trim.toLowerCase match
      case "white" => Right(Color.White)
      case "black" => Right(Color.Black)
      case other   => Left(s"Unsupported color: $other")

  private def errorResponse(err: ExternalGameError): IO[Response[IO]] =
    val (status, code, message) = err match
      case ExternalGameError.BindingNotFound(platform, externalGameId) =>
        (Status.NotFound, "EXTERNAL_GAME_NOT_FOUND", s"External game not found: $platform/$externalGameId")
      case ExternalGameError.AlreadyExists(platform, externalGameId) =>
        (Status.Conflict, "EXTERNAL_GAME_ALREADY_EXISTS", s"External game already exists: $platform/$externalGameId")
      case ExternalGameError.Unauthorized(callerActorId, expectedActorId) =>
        (Status.Forbidden, "FORBIDDEN", s"Caller $callerActorId is not authorized for actor $expectedActorId.")
      case ExternalGameError.BindingNotActive(current) =>
        (Status.Conflict, "EXTERNAL_GAME_NOT_ACTIVE", s"External game binding is not active: ${bindingStatus(current)._1}")
      case ExternalGameError.InvalidExternalMoveFormat(move, reason) =>
        (Status.UnprocessableEntity, "INVALID_EXTERNAL_MOVE", s"Invalid external move '$move': $reason")
      case ExternalGameError.InvalidRequest(reason) if reason.toLowerCase.contains("not ai turn") =>
        (Status.Conflict, "NOT_AI_TURN", reason)
      case ExternalGameError.InvalidRequest(reason) =>
        (Status.UnprocessableEntity, "INVALID_EXTERNAL_GAME_REQUEST", reason)
      case ExternalGameError.SessionFailure(cause) =>
        sessionErrorToHttp(cause)
      case ExternalGameError.RepositoryFailure(cause) =>
        repositoryErrorToHttp(cause)
      case ExternalGameError.AiFailure(cause) =>
        aiErrorToHttp(cause)
      case ExternalGameError.MoveRejected(cause) =>
        moveErrorToHttp(cause)

    jsonError(status, code, message)

  private def repositoryErrorToHttp(err: RepositoryError): (Status, String, String) = err match
    case RepositoryError.NotFound(id)        => (Status.NotFound, "NOT_FOUND", s"Record not found: $id")
    case RepositoryError.Conflict(message)   => (Status.Conflict, "CONFLICT", message)
    case RepositoryError.StorageFailure(msg) => (Status.InternalServerError, "INTERNAL_ERROR", msg)

  private def sessionErrorToHttp(err: SessionError): (Status, String, String) = err match
    case SessionError.SessionNotFound(id)      => (Status.NotFound, "SESSION_NOT_FOUND", s"Session not found: ${id.value}")
    case SessionError.GameSessionNotFound(id)  => (Status.NotFound, "SESSION_NOT_FOUND", s"Game session not found: ${id.value}")
    case SessionError.PersistenceFailed(cause) => repositoryErrorToHttp(cause)
    case SessionError.InvalidLifecycleTransition(reason) =>
      (Status.Conflict, "SESSION_LIFECYCLE_CONFLICT", reason)

  private def aiErrorToHttp(err: AITurnError): (Status, String, String) = err match
    case AITurnError.NotConfigured =>
      (Status.ServiceUnavailable, "AI_NOT_CONFIGURED", "AI is not configured.")
    case AITurnError.NotAITurn =>
      (Status.Conflict, "NOT_AI_TURN", "It is not the AI-controlled side's turn.")
    case AITurnError.ProviderFailure(AIError.Unavailable(msg)) =>
      (Status.ServiceUnavailable, "AI_UNAVAILABLE", msg)
    case AITurnError.ProviderFailure(AIError.Timeout(msg)) =>
      (Status.ServiceUnavailable, "AI_TIMEOUT", msg)
    case AITurnError.ProviderFailure(cause) =>
      (Status.UnprocessableEntity, "AI_MOVE_FAILED", cause.toString)
    case AITurnError.IllegalSuggestedMove(move) =>
      (Status.UnprocessableEntity, "AI_ILLEGAL_MOVE", s"AI suggested illegal move: $move")
    case AITurnError.MoveFailed(cause) =>
      moveErrorToHttp(cause)
    case AITurnError.SessionLookupFailed(cause) =>
      sessionErrorToHttp(cause)
    case AITurnError.GameStateLookupFailed(cause) =>
      repositoryErrorToHttp(cause)
    case AITurnError.InvalidMaxPlies(value) =>
      (Status.UnprocessableEntity, "INVALID_AI_REQUEST", s"Invalid max plies: $value")

  private def moveErrorToHttp(err: SessionMoveError): (Status, String, String) = err match
    case SessionMoveError.SessionFinished =>
      (Status.Conflict, "SESSION_ALREADY_FINISHED", "Session is finished.")
    case SessionMoveError.UnauthorizedController(_, _) =>
      (Status.Forbidden, "FORBIDDEN", "Controller is not authorized for the side to move.")
    case SessionMoveError.DomainRejection(cause) =>
      (Status.UnprocessableEntity, "MOVE_REJECTED", cause.toString)
    case SessionMoveError.PersistenceFailed(cause) =>
      sessionErrorToHttp(cause)

  private def toDto(binding: ExternalGameBinding): ExternalGameResponse =
    val (status, reason) = bindingStatus(binding.status)
    ExternalGameResponse(
      bindingId = binding.bindingId.value.toString,
      platform = binding.platform.toString,
      externalGameId = binding.externalGameId,
      internalGameId = binding.internalGameId.value.toString,
      sessionId = binding.sessionId.value.toString,
      ourActorId = binding.ourActorId,
      status = status,
      statusReason = reason,
      lastProcessedPly = binding.lastProcessedPly,
      createdAt = binding.createdAt.toString,
      updatedAt = binding.updatedAt.toString
    )

  private def toDto(result: IngestMovesResult): IngestMovesResponse =
    IngestMovesResponse(
      lastProcessedPly = result.lastProcessedPly,
      currentPlayer = result.currentPlayer.toString,
      gameStatus = gameStatus(result.gameStatus),
      nextAction = nextAction(result.nextAction)
    )

  private def toDto(result: AiMoveResult): AiMoveResponse =
    AiMoveResponse(
      uciMove = result.uciMove,
      lastProcessedPly = result.lastProcessedPly,
      gameStatus = gameStatus(result.gameStatus),
      nextAction = nextAction(result.nextAction)
    )

  private def bindingStatus(status: BindingStatus): (String, Option[String]) = status match
    case BindingStatus.Active         => ("Active", None)
    case BindingStatus.Finished       => ("Finished", None)
    case BindingStatus.Failed(reason) => ("Failed", Some(reason))

  private def gameStatus(status: GameStatus): String = status match
    case GameStatus.Ongoing(_)         => "Ongoing"
    case GameStatus.Checkmate(winner)  => s"Checkmate($winner)"
    case GameStatus.Draw(reason)       => s"Draw(${drawReason(reason)})"
    case GameStatus.Resigned(winner)   => s"Resigned($winner)"

  private def drawReason(reason: DrawReason): String = reason.toString

  private def nextAction(action: NextAction): String = action match
    case NextAction.AwaitExternalMove => "AwaitExternalMove"
    case NextAction.TriggerAI         => "TriggerAI"
    case NextAction.GameFinished      => "GameFinished"
