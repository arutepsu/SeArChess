package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.mapper.{GameMapper, SessionMapper}
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.{
  CreateSessionRequest,
  CreateSessionResponse,
  ImportNotationRequest,
  SessionListResponse,
  SessionResponse
}
import chess.application.GameServiceApi
import chess.application.query.game.GameView
import chess.application.session.model.SessionIds.SessionId
import chess.application.session.service.SessionError
import chess.notation.api.{ImportResult, ImportTarget, NotationFormat}
import chess.notation.fen.FenNotationFacade
import chess.notation.pgn.PgnNotationFacade
import org.http4s.*
import org.http4s.dsl.io.*

/** http4s routes for the `/sessions` resource.
  *
  * Routes:
  *   - `POST /sessions` → [[handleCreate]] (command — create new game)
  *   - `GET /sessions` → [[handleList]] (query — list active sessions)
  *   - `GET /sessions/{id}` → [[handleGet]] (query — get single session)
  *   - `POST /sessions/{id}/cancel` → [[handleCancel]] (command — cancel session)
  *
  * All operations are routed through [[GameServiceApi]] — the single Game Service boundary. This
  * class has one dependency instead of the previous three
  * ([[chess.application.session.service.GameSessionCommands]],
  * [[chess.application.session.service.SessionService]], and
  * [[chess.application.port.repository.GameRepository]]).
  *
  * This class is pure logic tested in-memory via `routes.orNotFound.run(req)`.
  */
class Http4sSessionRoutes(gameService: GameServiceApi):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "sessions" =>
      req.bodyText.compile.string.flatMap(handleCreate)

    case req @ POST -> Root / "sessions" / "import" =>
      req.bodyText.compile.string.flatMap(handleImport)

    case GET -> Root / "sessions" =>
      handleList()

    case GET -> Root / "sessions" / id =>
      handleGet(id)

    case POST -> Root / "sessions" / id / "cancel" =>
      handleCancel(id)
  }

  // ── handlers ──────────────────────────────────────────────────────────────

  private def handleCreate(body: String): IO[Response[IO]] =
    val result =
      for
        req <- CreateSessionRequest.fromJson(body)
        mode <- SessionMapper.parseMode(req.mode)
        controllers <- SessionMapper.resolveCreateControllers(
          mode,
          req.whiteController,
          req.blackController
        )
        (white, black) = controllers
        pair <- gameService.createGame(mode, white, black).left.map(sessionErrMsg)
        (state, session) = pair
      yield SessionMapper.toCreateSessionResponse(
        session,
        session.gameId,
        GameMapper.toGameResponse(GameView.fromState(session.gameId, state))
      )

    result match
      case Right(resp) => jsonResponse(Status.Created, CreateSessionResponse.toJson(resp))
      case Left(msg)   => jsonError(Status.BadRequest, "BAD_REQUEST", msg)

  private def handleImport(body: String): IO[Response[IO]] =
    val result =
      for
        req <- ImportNotationRequest.fromJson(body)
        mode <- SessionMapper.parseMode(req.mode)
        controllers <- SessionMapper.resolveCreateControllers(
          mode,
          req.whiteController,
          req.blackController
        )
        (white, black) = controllers
        state <- parseImportedState(req)
        pair <- gameService.createGameFromState(state, mode, white, black).left.map(sessionErrMsg)
        (nextState, session) = pair
      yield SessionMapper.toCreateSessionResponse(
        session,
        session.gameId,
        GameMapper.toGameResponse(GameView.fromState(session.gameId, nextState))
      )

    result match
      case Right(resp) => jsonResponse(Status.Created, CreateSessionResponse.toJson(resp))
      case Left(msg)   => jsonError(Status.BadRequest, "BAD_REQUEST", msg)

  private def handleList(): IO[Response[IO]] =
    gameService.listActiveSessions() match
      case Left(err) =>
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", sessionErrMsg(err))
      case Right(sessions) =>
        val items = sessions.map(SessionMapper.toSessionResponse)
        jsonResponse(Status.Ok, SessionListResponse.toJson(SessionListResponse(items)))

  private def handleGet(idStr: String): IO[Response[IO]] =
    parseUUID(idStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        gameService.getSession(SessionId(uuid)) match
          case Left(SessionError.SessionNotFound(_)) =>
            jsonError(Status.NotFound, "SESSION_NOT_FOUND", s"Session not found: $idStr")
          case Left(err) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", sessionErrMsg(err))
          case Right(session) =>
            jsonResponse(
              Status.Ok,
              SessionResponse.toJson(SessionMapper.toSessionResponse(session))
            )

  private def handleCancel(idStr: String): IO[Response[IO]] =
    parseUUID(idStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        gameService.cancelSession(SessionId(uuid)) match
          case Left(SessionError.SessionNotFound(_)) =>
            jsonError(Status.NotFound, "SESSION_NOT_FOUND", s"Session not found: $idStr")
          case Left(SessionError.InvalidLifecycleTransition(reason)) =>
            jsonError(
              Status.Conflict,
              "SESSION_ALREADY_FINISHED",
              s"Cannot cancel a finished session: $reason"
            )
          case Left(err) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", sessionErrMsg(err))
          case Right(session) =>
            jsonResponse(
              Status.Ok,
              SessionResponse.toJson(SessionMapper.toSessionResponse(session))
            )

  // ── helpers ───────────────────────────────────────────────────────────────

  private def sessionErrMsg(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"

  private def parseImportedState(
      req: ImportNotationRequest
  ): Either[String, chess.domain.state.GameState] =
    val text = req.notation.trim
    if text.isEmpty then Left("notation is empty")
    else
      req.format.toUpperCase match
        case "FEN" =>
          for
            parsed <- FenNotationFacade
              .parse(NotationFormat.FEN, text)
              .left
              .map(_.toString)
            imported <- FenNotationFacade
              .executeImport(parsed, ImportTarget.PositionTarget)
              .left
              .map(_.toString)
            state <- imported match
              case ImportResult.PositionImportResult(data, _, _, _) => Right(data)
              case other => Left(s"FEN import did not produce a position: ${other.sourceFormat}")
          yield state
        case "PGN" =>
          for
            parsed <- PgnNotationFacade
              .parse(NotationFormat.PGN, text)
              .left
              .map(_.toString)
            imported <- PgnNotationFacade
              .executeImport(parsed, ImportTarget.GameTarget)
              .left
              .map(_.toString)
            state <- imported match
              case ImportResult.GameImportResult(data, _, _, _, _) => Right(data)
              case other => Left(s"PGN import did not produce a game: ${other.sourceFormat}")
          yield state
        case other =>
          Left(s"Unknown notation format: '$other'. Expected FEN or PGN")
