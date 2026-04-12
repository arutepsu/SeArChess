package chess.adapter.rest.route

import chess.adapter.rest.dto.{GameResponse, SubmitMoveRequest, SubmitMoveResponse}
import chess.adapter.rest.mapper.{GameMapper, MoveMapper, SessionMapper}
import chess.application.ApplicationError
import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.model.SideController
import chess.application.session.service.{SessionError, SessionMoveError, SessionService}
import com.sun.net.httpserver.{HttpExchange, HttpHandler}

/** HTTP handler for the `/games/` context.
 *
 *  Routes:
 *  - `GET  /games/{gameId}`        → [[handleGetGame]]
 *  - `POST /games/{gameId}/moves`  → [[handleSubmitMove]]
 *
 *  All chess / session business logic is delegated to [[SessionService]] and
 *  the domain layer; this class only translates HTTP structures to/from
 *  application calls.
 */
class GameRoutes(
  sessionService: SessionService,
  gameRepository: GameRepository
) extends HttpHandler:

  override def handle(exchange: HttpExchange): Unit =
    val path     = exchange.getRequestURI.getPath
    val method   = exchange.getRequestMethod
    val segments = path.split("/").filter(_.nonEmpty)

    (method, segments) match
      case ("GET",  Array("games", id))          => handleGetGame(exchange, id)
      case ("POST", Array("games", id, "moves")) => handleSubmitMove(exchange, id)
      case _ =>
        RouteSupport.sendError(exchange, 404, "NOT_FOUND", s"No endpoint: $method $path")

  // ── handlers ──────────────────────────────────────────────────────────────

  private def handleGetGame(exchange: HttpExchange, gameIdStr: String): Unit =
    RouteSupport.parseUUID(gameIdStr) match
      case Left(msg) =>
        RouteSupport.sendError(exchange, 400, "BAD_REQUEST", msg)
      case Right(uuid) =>
        val gameId = GameId(uuid)
        gameRepository.load(gameId) match
          case Left(RepositoryError.NotFound(_)) =>
            RouteSupport.sendError(exchange, 404, "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
          case Left(RepositoryError.StorageFailure(msg)) =>
            RouteSupport.sendError(exchange, 500, "INTERNAL_ERROR", msg)
          case Right(state) =>
            RouteSupport.sendJson(exchange, 200,
              GameResponse.toJson(GameMapper.toGameResponse(gameIdStr, state)))

  /** Submit a move through the session-aware application boundary.
   *
   *  The for-comprehension uses `Either[(Int, String, String), _]` as its
   *  type so each step can carry an explicit HTTP status code, error code,
   *  and message without losing information when errors are collapsed later.
   */
  private def handleSubmitMove(exchange: HttpExchange, gameIdStr: String): Unit =
    // Left type: (httpStatus, errorCode, message)
    type HttpErr = (Int, String, String)

    val result: Either[HttpErr, SubmitMoveResponse] =
      for
        uuid    <- RouteSupport.parseUUID(gameIdStr)
                     .left.map(m => (400, "BAD_REQUEST", m))
        gameId   = GameId(uuid)
        state   <- gameRepository.load(gameId)
                     .left.map {
                       case RepositoryError.NotFound(_)         => (404, "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
                       case RepositoryError.StorageFailure(msg) => (500, "INTERNAL_ERROR", msg)
                     }
        body    <- RouteSupport.readBody(exchange)
                     .left.map(m => (400, "BAD_REQUEST", m))
        req     <- SubmitMoveRequest.fromJson(body)
                     .left.map(m => (400, "BAD_REQUEST", m))
        move    <- MoveMapper.toDomain(req)
                     .left.map(m => (422, "INVALID_MOVE", m))
        ctrl     = SessionMapper.parseController(req.controller)
                     .getOrElse(SideController.HumanLocal)
        session <- sessionService.getSessionByGameId(gameId)
                     .left.map(e => (404, "GAME_NOT_FOUND", sessionErrMsg(e)))
        pair    <- sessionService.applyMove(session, state, move, ctrl)
                     .left.map(moveErrToHttpErr)
        (nextState, nextSess) = pair
        _       <- gameRepository.save(gameId, nextState)
                     .left.map {
                       case RepositoryError.NotFound(_)         => (500, "INTERNAL_ERROR", "Game disappeared after move")
                       case RepositoryError.StorageFailure(msg) => (500, "INTERNAL_ERROR", s"Storage error after move: $msg")
                     }
      yield
        SubmitMoveResponse(
          game             = GameMapper.toGameResponse(gameIdStr, nextState),
          sessionLifecycle = nextSess.lifecycle.toString
        )

    result match
      case Right(resp)               => RouteSupport.sendJson(exchange, 200, SubmitMoveResponse.toJson(resp))
      case Left((status, code, msg)) => RouteSupport.sendError(exchange, status, code, msg)

  // ── error mapping ──────────────────────────────────────────────────────────

  /** Map a [[SessionMoveError]] to an explicit (httpStatus, errorCode, message) triple. */
  private def moveErrToHttpErr(err: SessionMoveError): (Int, String, String) = err match
    case SessionMoveError.SessionFinished =>
      (409, "SESSION_FINISHED",
        "Session is already finished; no further moves are accepted")
    case SessionMoveError.UnauthorizedController(req, side) =>
      (403, "UNAUTHORIZED_CONTROLLER",
        s"Controller '$req' is not authorized to move for $side")
    case SessionMoveError.DomainRejection(ApplicationError.NotPlayersTurn) =>
      (422, "NOT_YOUR_TURN", "It is not your turn")
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(err)) =>
      (422, "ILLEGAL_MOVE", s"Illegal move: $err")
    case SessionMoveError.PersistenceFailed(cause) =>
      (500, "INTERNAL_ERROR", s"Storage error after move: $cause")

  private def sessionErrMsg(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"
