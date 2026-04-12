package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.dto.{GameResponse, SubmitMoveRequest, SubmitMoveResponse}
import chess.adapter.rest.mapper.{GameMapper, MoveMapper, SessionMapper}
import chess.application.ApplicationError
import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.application.session.model.SideController
import chess.application.session.service.{SessionError, SessionMoveError, SessionService}
import org.http4s.*
import org.http4s.dsl.io.*

/** http4s routes for the `/games` resource.
 *
 *  Routes:
 *  - `GET  /games/{gameId}`        → [[handleGetGame]]
 *  - `POST /games/{gameId}/moves`  → [[handleSubmitMove]]
 *
 *  This class is pure logic tested in-memory via `routes.orNotFound.run(req)`.
 *  DTOs and mappers from `chess.adapter.rest` are reused (transport-neutral).
 */
class Http4sGameRoutes(
  sessionService: SessionService,
  gameRepository: GameRepository
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "games" / id =>
      handleGetGame(id)

    case req @ POST -> Root / "games" / id / "moves" =>
      req.bodyText.compile.string.flatMap(handleSubmitMove(id, _))
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

  /** Submit a move through the session-aware application boundary.
   *
   *  The for-comprehension carries `(Status, errorCode, message)` triples as the
   *  Left type so each step can report a precise HTTP status without losing
   *  information when errors are collapsed at the end.
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
        ctrl     = SessionMapper.parseController(req.controller)
                     .getOrElse(SideController.HumanLocal)
        session <- sessionService.getSessionByGameId(gameId)
                     .left.map(e => (Status.NotFound, "GAME_NOT_FOUND", sessionErrMsg(e)))
        pair    <- sessionService.applyMove(session, state, move, ctrl)
                     .left.map(moveErrToHttpErr)
        (nextState, nextSess) = pair
        _       <- gameRepository.save(gameId, nextState)
                     .left.map {
                       case RepositoryError.NotFound(_)         => (Status.InternalServerError, "INTERNAL_ERROR", "Game disappeared after move")
                       case RepositoryError.StorageFailure(msg) => (Status.InternalServerError, "INTERNAL_ERROR", s"Storage error after move: $msg")
                     }
      yield SubmitMoveResponse(
        game             = GameMapper.toGameResponse(gameIdStr, nextState),
        sessionLifecycle = nextSess.lifecycle.toString
      )

    result match
      case Right(resp)                   => jsonResponse(Status.Ok, SubmitMoveResponse.toJson(resp))
      case Left((status, code, message)) => jsonError(status, code, message)

  // ── error mapping ──────────────────────────────────────────────────────────

  private def moveErrToHttpErr(err: SessionMoveError): (Status, String, String) = err match
    case SessionMoveError.SessionFinished =>
      (Status.Conflict, "SESSION_FINISHED",
        "Session is already finished; no further moves are accepted")
    case SessionMoveError.UnauthorizedController(req, side) =>
      (Status.Forbidden, "UNAUTHORIZED_CONTROLLER",
        s"Controller '$req' is not authorized to move for $side")
    case SessionMoveError.DomainRejection(ApplicationError.NotPlayersTurn) =>
      (Status.UnprocessableEntity, "NOT_YOUR_TURN", "It is not your turn")
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(err)) =>
      (Status.UnprocessableEntity, "ILLEGAL_MOVE", s"Illegal move: $err")
    case SessionMoveError.PersistenceFailed(cause) =>
      (Status.InternalServerError, "INTERNAL_ERROR", s"Storage error after move: $cause")

  private def sessionErrMsg(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"
