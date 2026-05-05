package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.mapper.{GameMapper, SessionMapper}
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.{CreateSessionResponse, ImportNotationRequest, NotationResponse}
import chess.application.port.repository.{GameRepository, RepositoryError, SessionGameStore}
import chess.application.query.game.GameView
import chess.application.session.model.SessionIds.GameId
import chess.application.session.model.GameSession
import chess.domain.state.GameState
import chess.notation.api.{
  ImportResult,
  ImportTarget,
  NotationFacade,
  NotationFailure,
  NotationFormat
}
import chess.notation.fen.FenNotationFacade
import chess.notation.pgn.PgnNotationFacade
import org.http4s.*
import org.http4s.dsl.io.*

/** http4s routes for FEN/PGN notation tools.
  *
  * These routes intentionally model notation import/export only. They do not expose full session
  * backup semantics and do not use the session snapshot transfer contract. Parsing, importing, and
  * exporting remain delegated to the notation module facades; this class only translates HTTP,
  * persistence, and session creation concerns.
  */
class Http4sNotationRoutes(
    gameRepository: GameRepository,
    store: SessionGameStore
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "games" / id / "notation" / "fen" =>
      handleExport(id, NotationFormat.FEN)

    case GET -> Root / "games" / id / "notation" / "pgn" =>
      handleExport(id, NotationFormat.PGN)

    case req @ POST -> Root / "sessions" / "import-notation" =>
      req.bodyText.compile.string.flatMap(handleImport)
  }

  private def handleExport(gameIdStr: String, format: NotationFormat): IO[Response[IO]] =
    parseUUID(gameIdStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        exportNotation(GameId(uuid), format) match
          case Right(text) =>
            jsonResponse(
              Status.Ok,
              NotationResponse.toJson(NotationResponse(format.toString, text))
            )
          case Left(err) =>
            val (status, code, message) = notationErrToHttp(err, Some(gameIdStr))
            jsonError(status, code, message)

  private def handleImport(body: String): IO[Response[IO]] =
    type HttpErr = (Status, String, String)

    val result: Either[HttpErr, CreateSessionResponse] =
      for
        req <- ImportNotationRequest.fromJson(body).left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        format <- parseNotationFormat(req.format).left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        mode <- SessionMapper.parseMode(req.mode).left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        controllers <- SessionMapper
          .resolveCreateControllers(mode, req.whiteController, req.blackController)
          .left
          .map(m => (Status.BadRequest, "BAD_REQUEST", m))
        (whiteController, blackController) = controllers
        state <- importNotation(format, req.notation).left.map(notationErrToHttp(_, None))
        gameId = GameId.random()
        session = GameSession.create(gameId, mode, whiteController, blackController)
        _ <- store.save(session, state).left.map(repositoryErrToHttp(_, None))
      yield SessionMapper.toCreateSessionResponse(
        session,
        session.gameId,
        GameMapper.toGameResponse(GameView.fromState(session.gameId, state))
      )

    result match
      case Right(resp) =>
        jsonResponse(Status.Created, CreateSessionResponse.toJson(resp))
      case Left((status, code, message)) =>
        jsonError(status, code, message)

  private def parseNotationFormat(value: String): Either[String, NotationFormat] =
    value match
      case "FEN" => Right(NotationFormat.FEN)
      case "PGN" => Right(NotationFormat.PGN)
      case other => Left(s"Unsupported notation format: '$other'. Expected FEN or PGN")

  private def exportNotation(
      gameId: GameId,
      format: NotationFormat
  ): Either[NotationRouteError, String] =
    for
      state <- gameRepository.load(gameId).left.map(NotationRouteError.RepositoryFailure.apply)
      facade <- notationFacade(format)
      result <- facade
        .executeExport(state, format)
        .left
        .map(NotationRouteError.NotationFailureResult.apply)
    yield result.text

  private def importNotation(
      format: NotationFormat,
      text: String
  ): Either[NotationRouteError, GameState] =
    val trimmed = text.trim
    if trimmed.isEmpty then Left(NotationRouteError.BadInput("Notation is empty"))
    else
      val target =
        if format == NotationFormat.FEN then ImportTarget.PositionTarget
        else ImportTarget.GameTarget

      notationFacade(format)
        .flatMap { facade =>
          facade
            .parseAndImport(format, trimmed, target)
            .left
            .map(NotationRouteError.NotationFailureResult.apply)
            .flatMap(importedGameState)
        }

  private def importedGameState(
      result: ImportResult[GameState]
  ): Either[NotationRouteError, GameState] =
    result match
      case ImportResult.PositionImportResult(state, _, _, _) => Right(state)
      case ImportResult.GameImportResult(state, _, _, _, _)  => Right(state)

  private def notationFacade(
      format: NotationFormat
  ): Either[NotationRouteError, NotationFacade[GameState]] =
    format match
      case NotationFormat.FEN => Right(FenNotationFacade)
      case NotationFormat.PGN => Right(PgnNotationFacade)
      case other =>
        Left(NotationRouteError.BadInput(s"Unsupported notation format: $other"))

  private def notationErrToHttp(
      err: NotationRouteError,
      gameIdStr: Option[String]
  ): (Status, String, String) =
    err match
      case NotationRouteError.RepositoryFailure(error) =>
        repositoryErrToHttp(error, gameIdStr)
      case NotationRouteError.BadInput(message) =>
        (Status.BadRequest, "INVALID_NOTATION", message)
      case NotationRouteError.NotationFailureResult(error) =>
        (Status.BadRequest, "INVALID_NOTATION", error.message)

  private def repositoryErrToHttp(
      err: RepositoryError,
      gameIdStr: Option[String]
  ): (Status, String, String) =
    err match
      case RepositoryError.NotFound(_) =>
        (Status.NotFound, "GAME_NOT_FOUND", s"Game not found: ${gameIdStr.getOrElse("")}")
      case RepositoryError.Conflict(message) =>
        (Status.Conflict, "CONFLICT", message)
      case RepositoryError.StorageFailure(message) =>
        (Status.InternalServerError, "INTERNAL_ERROR", message)

object Http4sNotationRoutes:
  def apply(
      gameRepository: GameRepository,
      store: SessionGameStore
  ): Http4sNotationRoutes =
    new Http4sNotationRoutes(gameRepository, store)

private enum NotationRouteError:
  case BadInput(message: String)
  case NotationFailureResult(error: NotationFailure)
  case RepositoryFailure(error: RepositoryError)
