package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.mapper.{GameMapper, MoveMapper, SessionMapper}
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.{
  GameNotationResponse,
  GameResponse,
  LegalMovesResponse,
  ResignRequest,
  SessionResponse,
  SubmitMoveRequest,
  SubmitMoveResponse
}
import chess.application.ApplicationError
import chess.application.GameServiceApi
import chess.application.ai.service.AITurnError
import chess.application.port.ai.AIError
import chess.application.port.repository.RepositoryError
import chess.application.query.game.GameView
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.service.{SessionError, SessionMoveError}
import chess.domain.error.DomainError
import chess.notation.api.NotationFormat
import chess.notation.fen.FenSerializer
import chess.notation.pgn.PgnNotationFacade
import org.http4s.*
import org.http4s.dsl.io.*

/** http4s routes for the `/games` resource.
  *
  * Routes:
  *   - `GET /games/{gameId}` → [[handleGetGame]] (query — game state)
  *   - `POST /games/{gameId}/moves` → [[handleSubmitMove]] (command — submit move)
  *   - `POST /games/{gameId}/resign` → [[handleResign]] (command — resign)
  *   - `POST /games/{gameId}/ai-move` → [[handleAIMove]] (command — trigger AI)
  *   - `GET /games/{gameId}/notation` → [[handleGetNotation]] (query — FEN/PGN)
  *
  * All operations are routed through [[GameServiceApi]] — the single Game Service boundary. This
  * class has one dependency instead of the previous three
  * ([[chess.application.session.service.GameSessionCommands]],
  * [[chess.application.session.service.SessionLifecycleService]], and
  * [[chess.application.port.repository.GameRepository]]).
  *
  * AI capability policy: `/games/{gameId}/ai-move` is always mounted. Runtimes without an AI client
  * return `422 AI_NOT_CONFIGURED`; configured runtimes route AI suggestions through the same
  * authoritative Game Service move path.
  *
  * This class is pure logic tested in-memory via `routes.orNotFound.run(req)`.
  */
class Http4sGameRoutes(gameService: GameServiceApi):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "games" / id =>
      handleGetGame(id)

    case GET -> Root / "games" / id / "legal-moves" =>
      handleGetLegalMoves(id)

    case GET -> Root / "games" / id / "notation" =>
      handleGetNotation(id)

    case req @ POST -> Root / "games" / id / "moves" =>
      req.bodyText.compile.string.flatMap(handleSubmitMove(id, _))

    case req @ POST -> Root / "games" / id / "resign" =>
      req.bodyText.compile.string.flatMap(handleResign(id, _))

    case POST -> Root / "games" / id / "ai-move" =>
      handleAIMove(id)
  }

  // ── handlers ──────────────────────────────────────────────────────────────

  private def handleGetGame(gameIdStr: String): IO[Response[IO]] =
    parseUUID(gameIdStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        gameService.getGame(GameId(uuid)) match
          case Left(RepositoryError.NotFound(_)) =>
            jsonError(Status.NotFound, "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
          case Left(RepositoryError.StorageFailure(msg)) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", msg)
          case Right(view) =>
            jsonResponse(Status.Ok, GameResponse.toJson(GameMapper.toGameResponse(view)))

  private def handleGetLegalMoves(gameIdStr: String): IO[Response[IO]] =
    parseUUID(gameIdStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        gameService.getLegalMoves(GameId(uuid)) match
          case Left(RepositoryError.NotFound(_)) =>
            jsonError(Status.NotFound, "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
          case Left(RepositoryError.StorageFailure(msg)) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", msg)
          case Right(view) =>
            jsonResponse(
              Status.Ok,
              LegalMovesResponse.toJson(GameMapper.toLegalMovesResponse(view))
            )

  private def handleGetNotation(gameIdStr: String): IO[Response[IO]] =
    parseUUID(gameIdStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        gameService.getGame(GameId(uuid)) match
          case Left(RepositoryError.NotFound(_)) =>
            jsonError(Status.NotFound, "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
          case Left(RepositoryError.StorageFailure(msg)) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", msg)
          case Right(view) =>
            val state = view.toGameState
            val fenResult = FenSerializer.exportNotation(state, NotationFormat.FEN)
            val pgnResult = PgnNotationFacade.executeExport(state, NotationFormat.PGN)

            (fenResult, pgnResult) match
              case (Right(fen), Right(pgn)) =>
                jsonResponse(
                  Status.Ok,
                  GameNotationResponse.toJson(GameNotationResponse(fen.text, pgn.text))
                )
              case (Left(err), _) =>
                jsonError(Status.InternalServerError, "NOTATION_FAILED", s"FEN export failed: $err")
              case (_, Left(err)) =>
                jsonError(Status.InternalServerError, "NOTATION_FAILED", s"PGN export failed: $err")

  /** Submit a move through the game service boundary.
    *
    * Session and state loading is now handled by [[GameServiceApi.submitMove]]; the route only
    * parses the request body and maps the result to HTTP.
    */
  private def handleSubmitMove(gameIdStr: String, body: String): IO[Response[IO]] =
    type HttpErr = (Status, String, String)

    val result: Either[HttpErr, SubmitMoveResponse] =
      for
        uuid <- parseUUID(gameIdStr).left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        gameId = GameId(uuid)
        req <- SubmitMoveRequest.fromJson(body).left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        move <- MoveMapper
          .toDomain(req)
          .left
          .map(m => (Status.UnprocessableEntity, "INVALID_MOVE", m))
        ctrl <- SessionMapper
          .parseController(req.controller)
          .left
          .map(m => (Status.BadRequest, "BAD_REQUEST", m))
        pair <- gameService.submitMove(gameId, move, ctrl).left.map(moveErrToHttpErr)
        (nextState, nextSess) = pair
      yield SubmitMoveResponse(
        game = GameMapper.toGameResponse(GameView.fromState(gameId, nextState)),
        sessionLifecycle = nextSess.lifecycle.toString
      )

    result match
      case Right(resp)                   => jsonResponse(Status.Ok, SubmitMoveResponse.toJson(resp))
      case Left((status, code, message)) => jsonError(status, code, message)

  /** Resign the game on behalf of the current player's controller.
    *
    * Request body: `{"side": "White"}` or `{"side": "Black"}`. The [[gameIdStr]] is used to resolve
    * the session via [[GameServiceApi.getSessionByGameId]].
    */
  private def handleResign(gameIdStr: String, body: String): IO[Response[IO]] =
    type HttpErr = (Status, String, String)

    val result: Either[HttpErr, SubmitMoveResponse] =
      for
        uuid <- parseUUID(gameIdStr).left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        gameId = GameId(uuid)
        session <- gameService
          .getSessionByGameId(gameId)
          .left
          .map(e => (Status.NotFound, "GAME_NOT_FOUND", sessionErrMsg(e)))
        req2 <- ResignRequest.fromJson(body).left.map(m => (Status.BadRequest, "BAD_REQUEST", m))
        side <- SessionMapper
          .parseSide(req2.side)
          .left
          .map(m => (Status.BadRequest, "BAD_REQUEST", m))
        pair <- gameService.resignGame(session.sessionId, side).left.map(e => resignErrToHttpErr(e))
        (nextState, nextSess) = pair
      yield SubmitMoveResponse(
        game = GameMapper.toGameResponse(GameView.fromState(gameId, nextState)),
        sessionLifecycle = nextSess.lifecycle.toString
      )

    result match
      case Right(resp)                   => jsonResponse(Status.Ok, SubmitMoveResponse.toJson(resp))
      case Left((status, code, message)) => jsonError(status, code, message)

  /** Ask the AI to generate and apply a move for the current player.
    *
    * Uses [[GameServiceApi.triggerAIMoveByGameId]] so that the game-to-session lookup happens
    * exactly once inside the service boundary rather than twice (once here to convert the URL game
    * ID, and again inside the service). The route stays thin: parse the ID, call the service, map
    * the error.
    */
  private def handleAIMove(gameIdStr: String): IO[Response[IO]] =
    parseUUID(gameIdStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        val gameId = GameId(uuid)
        gameService.triggerAIMoveByGameId(gameId) match
          case Right((nextState, nextSess)) =>
            jsonResponse(
              Status.Ok,
              SubmitMoveResponse.toJson(
                SubmitMoveResponse(
                  game = GameMapper.toGameResponse(GameView.fromState(gameId, nextState)),
                  sessionLifecycle = nextSess.lifecycle.toString
                )
              )
            )
          case Left(err) =>
            val (status, code, message) = aiErrToHttpErr(err, gameIdStr)
            jsonError(status, code, message)

  // ── error mapping ──────────────────────────────────────────────────────────

  private def moveErrToHttpErr(err: SessionMoveError): (Status, String, String) = err match
    case SessionMoveError.SessionFinished =>
      (Status.Conflict, "GAME_FINISHED", "Game is already finished; no further moves are accepted")
    case SessionMoveError.UnauthorizedController(req, side) =>
      (
        Status.Forbidden,
        "UNAUTHORIZED_CONTROLLER",
        s"Controller '$req' is not authorized to move for $side"
      )
    case SessionMoveError.DomainRejection(ApplicationError.NotPlayersTurn) =>
      (Status.UnprocessableEntity, "NOT_YOUR_TURN", "It is not your turn")
    case SessionMoveError.DomainRejection(
          ApplicationError.DomainFailure(DomainError.MissingPromotionChoice)
        ) =>
      (
        Status.UnprocessableEntity,
        "PROMOTION_REQUIRED",
        "A promotion piece must be specified for this move"
      )
    case SessionMoveError.DomainRejection(ApplicationError.DomainFailure(err)) =>
      (Status.UnprocessableEntity, "ILLEGAL_MOVE", s"Illegal move: $err")
    case SessionMoveError.PersistenceFailed(SessionError.GameSessionNotFound(_)) =>
      (Status.NotFound, "GAME_NOT_FOUND", "Game or session not found")
    case SessionMoveError.PersistenceFailed(SessionError.SessionNotFound(_)) =>
      (Status.NotFound, "SESSION_NOT_FOUND", "Session not found")
    case SessionMoveError.PersistenceFailed(cause) =>
      (Status.InternalServerError, "INTERNAL_ERROR", s"Storage error: $cause")

  /** Map [[AITurnError]] to (HTTP status, error code, message).
    *
    * Status rationale for [[AITurnError.NotConfigured]]: 422 is used because the request is
    * syntactically valid but cannot be processed in this deployment configuration. 503 would imply
    * temporary unavailability (AI could come back); 501 (Not Implemented) would also be defensible.
    * 422 is consistent with the other "command not applicable in current state" errors in this API.
    */
  private def aiErrToHttpErr(err: AITurnError, gameIdStr: String): (Status, String, String) =
    err match
      case AITurnError.NotConfigured =>
        (
          Status.UnprocessableEntity,
          "AI_NOT_CONFIGURED",
          "This deployment has no AI provider configured"
        )
      case AITurnError.NotAITurn =>
        (Status.UnprocessableEntity, "NOT_AI_TURN", "It is not the AI's turn")
      case AITurnError.SessionLookupFailed(SessionError.GameSessionNotFound(_)) =>
        (Status.NotFound, "GAME_NOT_FOUND", s"Game not found: $gameIdStr")
      case AITurnError.SessionLookupFailed(_) =>
        (Status.InternalServerError, "INTERNAL_ERROR", "Failed to load session for AI turn")
      case AITurnError.GameStateLookupFailed(RepositoryError.NotFound(_)) =>
        (Status.NotFound, "GAME_NOT_FOUND", s"Game state not found: $gameIdStr")
      case AITurnError.GameStateLookupFailed(_) =>
        (Status.InternalServerError, "INTERNAL_ERROR", "Failed to load game state for AI turn")
      case AITurnError.ProviderFailure(AIError.MalformedResponse(err)) =>
        (
          Status.UnprocessableEntity,
          "AI_MOVE_REJECTED",
          s"AI provider returned malformed move data: $err"
        )
      case AITurnError.ProviderFailure(err @ AIError.Unavailable(_)) =>
        (Status.ServiceUnavailable, "AI_PROVIDER_FAILED", s"AI provider failed: $err")
      case AITurnError.ProviderFailure(err @ AIError.Timeout(_)) =>
        (Status.ServiceUnavailable, "AI_PROVIDER_FAILED", s"AI provider failed: $err")
      case AITurnError.ProviderFailure(err @ AIError.EngineFailure(_)) =>
        (Status.ServiceUnavailable, "AI_PROVIDER_FAILED", s"AI provider failed: $err")
      case AITurnError.ProviderFailure(err @ AIError.NoLegalMove) =>
        (Status.ServiceUnavailable, "AI_PROVIDER_FAILED", s"AI provider failed: $err")
      case AITurnError.IllegalSuggestedMove(move) =>
        (
          Status.UnprocessableEntity,
          "AI_MOVE_REJECTED",
          s"AI move rejected: illegal suggestion $move"
        )
      case AITurnError.MoveFailed(cause) =>
        (Status.UnprocessableEntity, "AI_MOVE_REJECTED", s"AI move rejected: $cause")

  private def resignErrToHttpErr(err: SessionError): (Status, String, String) = err match
    case SessionError.InvalidLifecycleTransition(_) =>
      (Status.Conflict, "GAME_ALREADY_FINISHED", "Cannot resign a game that is already finished")
    case SessionError.SessionNotFound(id) =>
      (Status.NotFound, "SESSION_NOT_FOUND", s"Session not found: ${id.value}")
    case SessionError.GameSessionNotFound(id) =>
      (Status.NotFound, "GAME_NOT_FOUND", s"Game session not found: ${id.value}")
    case SessionError.PersistenceFailed(cause) =>
      (Status.InternalServerError, "INTERNAL_ERROR", s"Storage error: $cause")

  private def sessionErrMsg(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"
