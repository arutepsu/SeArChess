package chess.adapter.rest.route

import chess.adapter.rest.dto.{CreateSessionRequest, CreateSessionResponse, SessionResponse}
import chess.adapter.rest.mapper.{GameMapper, SessionMapper}
import chess.application.ChessService
import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.service.{SessionError, SessionService}
import com.sun.net.httpserver.{HttpExchange, HttpHandler}

/** HTTP handler for the `/sessions` context.
 *
 *  Routes:
 *  - `POST /sessions`          → [[handleCreate]]
 *  - `GET  /sessions/{id}`     → [[handleGet]]
 *
 *  All chess / session business logic is delegated to
 *  [[SessionService]] and [[chess.application.ChessService]]; this class only
 *  translates HTTP structures to/from application calls.
 */
class SessionRoutes(
  sessionService: SessionService,
  gameRepository: GameRepository
) extends HttpHandler:

  override def handle(exchange: HttpExchange): Unit =
    val path     = exchange.getRequestURI.getPath
    val method   = exchange.getRequestMethod
    val segments = path.split("/").filter(_.nonEmpty)

    (method, segments) match
      case ("POST", Array("sessions"))     => handleCreate(exchange)
      case ("GET",  Array("sessions", id)) => handleGet(exchange, id)
      case _                               =>
        RouteSupport.sendError(exchange, 404, "NOT_FOUND", s"No endpoint: $method $path")

  // ── handlers ──────────────────────────────────────────────────────────────

  private def handleCreate(exchange: HttpExchange): Unit =
    val result =
      for
        body    <- RouteSupport.readBody(exchange)
        req     <- CreateSessionRequest.fromJson(body)
        mode    <- SessionMapper.parseMode(req.mode)
        white   <- SessionMapper.parseController(req.whiteController)
        black   <- SessionMapper.parseController(req.blackController)
        state    = ChessService.createNewGame()
        gameId   = GameId.random()
        _       <- gameRepository.save(gameId, state)
                     .left.map {
                       case RepositoryError.NotFound(id)        => s"Game not found: $id"
                       case RepositoryError.StorageFailure(msg) => s"Storage error: $msg"
                     }
        session <- sessionService.createSession(gameId, mode, white, black)
                     .left.map(sessionErrorMessage)
      yield SessionMapper.toCreateSessionResponse(
              session,
              gameId,
              GameMapper.toGameResponse(gameId.value.toString, state)
            )

    result match
      case Right(resp) =>
        RouteSupport.sendJson(exchange, 201, CreateSessionResponse.toJson(resp))
      case Left(msg) =>
        RouteSupport.sendError(exchange, 400, "BAD_REQUEST", msg)

  private def handleGet(exchange: HttpExchange, idStr: String): Unit =
    RouteSupport.parseUUID(idStr) match
      case Left(msg) =>
        RouteSupport.sendError(exchange, 400, "BAD_REQUEST", msg)
      case Right(uuid) =>
        sessionService.getSession(SessionId(uuid)) match
          case Left(SessionError.SessionNotFound(_)) =>
            RouteSupport.sendError(exchange, 404, "SESSION_NOT_FOUND", s"Session not found: $idStr")
          case Left(err) =>
            RouteSupport.sendError(exchange, 500, "INTERNAL_ERROR", sessionErrorMessage(err))
          case Right(session) =>
            RouteSupport.sendJson(exchange, 200, SessionResponse.toJson(SessionMapper.toSessionResponse(session)))

  // ── helpers ───────────────────────────────────────────────────────────────

  private def sessionErrorMessage(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"
