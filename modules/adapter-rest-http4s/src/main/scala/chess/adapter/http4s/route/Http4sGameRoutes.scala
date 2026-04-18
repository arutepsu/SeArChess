package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.{GameResponse, SubmitMoveRequest, SubmitMoveResponse}
import chess.adapter.rest.contract.mapper.{GameMapper, MoveMapper, SessionMapper}
import chess.application.ApplicationError
import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.domain.error.DomainError
import chess.application.session.model.SessionIds.GameId
import chess.application.session.model.SideController
import chess.application.session.service.{GameSessionCommands, SessionError, SessionMoveError, SessionService}
import org.http4s.*
import org.http4s.dsl.io.*

/** http4s routes for the `/api/games` resource.
 *
 *  Routes:
 *  - `GET  /api/games/{gameId}`        → [[handleGetGame]]     (query — uses [[GameRepository]])
 *  - `POST /api/games/{gameId}/moves`  → [[handleSubmitMove]]  (command — uses [[GameSessionCommands]])
 *
 *  The dependency split is intentional:
 *  - `GET` reads game state directly from [[GameRepository]] (query path).
 *  - `POST` session lookup uses [[SessionService]] (query path), then routes the
 *    move through [[GameSessionCommands]] (command path).
 *  This keeps the command and query paths separately typed, making the future
 *  service extraction boundary visible at the adapter level.
 *
 *  This class is pure logic tested in-memory via `routes.orNotFound.run(req)`.
 *  DTOs and mappers from `chess.adapter.rest` are reused (transport-neutral).
 */
class Http4sGameRoutes(
  commands:       GameSessionCommands,
  sessionService: SessionService,
  gameRepository: GameRepository
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "api" / "games" / id =>
      handleGetGame(id)

    case req @ POST -> Root / "api" / "games" / id / "moves" =>
      req.bodyText.compile.string.flatMap(handleSubmitMove(id, _))

    case POST -> Root / "api" / "games" / id / "undo" =>
      handleUndo(id)

    case POST -> Root / "api" / "games" / id / "redo" =>
      handleRedo(id)
  }

  // ── handlers ──────────────────────────────────────────────────────────────

  private def handleGetGame(gameIdStr: String): IO[Response[IO]] =
    parseUUID(gameIdStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        val gameId = GameId(uuid)
        gameRepository.load(gameId) match
          case Left(RepositoryError.NotFound(_)) =>
            jsonError(Status.NotFound, "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
          case Left(RepositoryError.StorageFailure(msg)) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", msg)
          case Right(state) =>
            jsonResponse(Status.Ok, GameResponse.toJson(GameMapper.toGameResponse(gameIdStr, state)))

  /** Submit a move through the game-session command boundary.
   *
   *  [[GameSessionCommands.submitMove]] handles domain validation, session-lifecycle
   *  persistence, game-state persistence, and event publication atomically from the
   *  adapter's perspective.  The route only parses the request, resolves the session
   *  via the query path ([[SessionService.getSessionByGameId]]), and maps the result
   *  to an HTTP response.
   */
  private def handleSubmitMove(gameIdStr: String, body: String): IO[Response[IO]] =
    type HttpErr = (Status, String, String)

    val result: Either[HttpErr, SubmitMoveResponse] =
      for
        uuid    <- parseUUID(gameIdStr)
                     .left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        gameId   = GameId(uuid)
        state   <- gameRepository.load(gameId)
                     .left.map {
                       case RepositoryError.NotFound(_)         => (Status.NotFound,            "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
                       case RepositoryError.StorageFailure(msg) => (Status.InternalServerError, "INTERNAL_ERROR", msg)
                     }
        req     <- SubmitMoveRequest.fromJson(body)
                     .left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        move    <- MoveMapper.toDomain(req)
                     .left.map(m => (Status.UnprocessableEntity, "INVALID_MOVE", m))
        ctrl    <- SessionMapper.parseController(req.controller)
                     .left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        session <- sessionService.getSessionByGameId(gameId)
                     .left.map(e => (Status.NotFound, "GAME_NOT_FOUND", sessionErrMsg(e)))
        pair    <- commands.submitMove(session, state, move, ctrl)
                     .left.map(moveErrToHttpErr)
        (nextState, nextSess) = pair
      yield SubmitMoveResponse(
        game             = GameMapper.toGameResponse(gameIdStr, nextState),
        sessionLifecycle = nextSess.lifecycle.toString
      )

    result match
      case Right(resp)                   => jsonResponse(Status.Ok, SubmitMoveResponse.toJson(resp))
      case Left((status, code, message)) => jsonError(status, code, message)

  private def handleUndo(gameIdStr: String): IO[Response[IO]] =
    handleUndoRedo(gameIdStr, isUndo = true)

  private def handleRedo(gameIdStr: String): IO[Response[IO]] =
    handleUndoRedo(gameIdStr, isUndo = false)

  private def handleUndoRedo(gameIdStr: String, isUndo: Boolean): IO[Response[IO]] =
    type HttpErr = (Status, String, String)

    val result: Either[HttpErr, GameResponse] =
      for
        uuid    <- parseUUID(gameIdStr)
                     .left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        gameId   = GameId(uuid)
        session <- sessionService.getSessionByGameId(gameId)
                     .left.map(e => (Status.NotFound, "GAME_NOT_FOUND", sessionErrMsg(e)))
        pair    <- (if isUndo then commands.undoMove(session.sessionId)
                    else commands.redoMove(session.sessionId))
                     .left.map(err => undoRedoErrToHttpErr(err, isUndo))
        (nextState, _) = pair
      yield GameMapper.toGameResponse(gameIdStr, nextState)

    result match
      case Right(resp)                   => jsonResponse(Status.Ok, GameResponse.toJson(resp))
      case Left((status, code, message)) => jsonError(status, code, message)

  // ── error mapping ──────────────────────────────────────────────────────────

  private def moveErrToHttpErr(err: SessionMoveError): (Status, String, String) = err match
    case SessionMoveError.SessionFinished =>
      (Status.Conflict, "GAME_FINISHED",
        "Game is already finished; no further moves are accepted")
    case SessionMoveError.UnauthorizedController(req, side) =>
      (Status.Forbidden, "UNAUTHORIZED_CONTROLLER",
        s"Controller '$req' is not authorized to move for $side")
    case SessionMoveError.DomainRejection(ApplicationError.NotPlayersTurn) =>
      (Status.UnprocessableEntity, "NOT_YOUR_TURN", "It is not your turn")
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(DomainError.MissingPromotionChoice)) =>
      (Status.UnprocessableEntity, "PROMOTION_REQUIRED",
        "A promotion piece must be specified for this move")
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(err)) =>
      (Status.UnprocessableEntity, "ILLEGAL_MOVE", s"Illegal move: $err")
    case SessionMoveError.PersistenceFailed(cause) =>
      (Status.InternalServerError, "INTERNAL_ERROR", s"Storage error after move: $cause")

  private def undoRedoErrToHttpErr(err: SessionMoveError, isUndo: Boolean): (Status, String, String) = err match
    case SessionMoveError.SessionFinished =>
      (Status.Conflict, "GAME_FINISHED",
        "Game is already finished; undo/redo is not available")
    case SessionMoveError.UnauthorizedController(req, side) =>
      (Status.Forbidden, "UNAUTHORIZED_CONTROLLER",
        s"Controller '$req' is not authorized to move for $side")
    case SessionMoveError.DomainRejection(ApplicationError.NotPlayersTurn) =>
      (Status.UnprocessableEntity, "NOT_YOUR_TURN", "It is not your turn")
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(DomainError.MissingPromotionChoice)) =>
      (Status.UnprocessableEntity, "PROMOTION_REQUIRED",
        "A promotion piece must be specified for this move")
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(err)) =>
      (Status.UnprocessableEntity, "ILLEGAL_MOVE", s"Illegal move: $err")
    case SessionMoveError.PersistenceFailed(SessionError.PersistenceFailed(RepositoryError.StorageFailure(msg)))
        if isUndo && msg == "No past moves to undo" =>
      (Status.Conflict, "UNDO_NOT_AVAILABLE", msg)
    case SessionMoveError.PersistenceFailed(SessionError.PersistenceFailed(RepositoryError.StorageFailure(msg)))
        if !isUndo && msg == "No future moves to redo" =>
      (Status.Conflict, "REDO_NOT_AVAILABLE", msg)
    case SessionMoveError.PersistenceFailed(cause) =>
      (Status.InternalServerError, "INTERNAL_ERROR", s"Storage error after undo/redo: $cause")

  private def sessionErrMsg(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"
