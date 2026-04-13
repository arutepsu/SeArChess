package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.dto.{CreateSessionRequest, CreateSessionResponse, SessionResponse}
import chess.adapter.rest.mapper.{GameMapper, SessionMapper}
import chess.application.session.model.SessionIds.SessionId
import chess.application.session.service.{SessionError, SessionGameService}
import org.http4s.*
import org.http4s.dsl.io.*

/** http4s routes for the `/sessions` resource.
 *
 *  Routes:
 *  - `POST /sessions`       → [[handleCreate]]
 *  - `GET  /sessions/{id}`  → [[handleGet]]
 *
 *  This class is pure logic: no I/O beyond effect-wrapping of synchronous
 *  application calls.  It is tested in-memory via `routes.orNotFound.run(req)`.
 *
 *  DTOs and mappers from `chess.adapter.rest` are reused deliberately — they
 *  are transport-neutral (ujson-based case classes) and there is no reason to
 *  duplicate them for the http4s adapter.
 */
class Http4sSessionRoutes(sessionGameService: SessionGameService):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "sessions" =>
      req.bodyText.compile.string.flatMap(handleCreate)

    case GET -> Root / "sessions" / id =>
      handleGet(id)
  }

  // ── handlers ──────────────────────────────────────────────────────────────

  /** Create a session through the unified application mutation boundary.
   *
   *  [[SessionGameService.newGame]] atomically creates a fresh session, generates
   *  a [[chess.application.session.model.SessionIds.GameId]], and persists the
   *  initial [[chess.domain.state.GameState]] before returning.
   */
  private def handleCreate(body: String): IO[Response[IO]] =
    val result =
      for
        req           <- CreateSessionRequest.fromJson(body)
        mode          <- SessionMapper.parseMode(req.mode)
        white         <- SessionMapper.parseController(req.whiteController)
        black         <- SessionMapper.parseController(req.blackController)
        pair          <- sessionGameService.newGame(mode, white, black)
                           .left.map(sessionErrMsg)
        (state, session) = pair
      yield SessionMapper.toCreateSessionResponse(
              session,
              session.gameId,
              GameMapper.toGameResponse(session.gameId.value.toString, state)
            )

    result match
      case Right(resp) => jsonResponse(Status.Created, CreateSessionResponse.toJson(resp))
      case Left(msg)   => jsonError(Status.BadRequest, "BAD_REQUEST", msg)

  private def handleGet(idStr: String): IO[Response[IO]] =
    parseUUID(idStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        sessionGameService.getSession(SessionId(uuid)) match
          case Left(SessionError.SessionNotFound(_)) =>
            jsonError(Status.NotFound, "SESSION_NOT_FOUND", s"Session not found: $idStr")
          case Left(err) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", sessionErrMsg(err))
          case Right(session) =>
            jsonResponse(Status.Ok, SessionResponse.toJson(SessionMapper.toSessionResponse(session)))

  // ── helpers ───────────────────────────────────────────────────────────────

  private def sessionErrMsg(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"
