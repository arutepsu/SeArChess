package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.DomainMetricsRegistry
import chess.adapter.http4s.mapper.{GameMapper, SessionMapper}
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.{
  CreateSessionRequest,
  CreateSessionResponse,
  SessionExportEnvelope,
  SessionListResponse,
  SessionResponse,
  SessionStateResponse
}
import chess.application.GameServiceApi
import chess.application.query.game.GameView
import chess.application.session.model.SessionMode
import chess.application.session.model.SessionIds.SessionId
import chess.application.session.service.{
  PersistentSessionError,
  PersistentSessionService,
  SessionError,
  SessionSnapshotTransferError,
  SessionSnapshotTransferService
}
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

/** http4s routes for the `/sessions` resource.
  *
  * Routes:
  *   - `POST /sessions` -> [[handleCreate]] (command - create new game)
  *   - `GET /sessions` -> [[handleList]] (query - list active sessions)
  *   - `GET /sessions/{id}` -> [[handleGet]] (query - get single session)
  *   - `GET /sessions/{id}/state` -> [[handleGetState]] (query - load full persisted aggregate)
  *   - `PUT /sessions/{id}/state` -> [[handlePutState]] (command - replace persisted aggregate)
  *   - `GET /sessions/{id}/export` -> [[handleExport]] (query - snapshot export envelope)
  *   - `POST /sessions/import` -> [[handleImport]] (command - import snapshot envelope)
  *   - `POST /sessions/{id}/cancel` -> [[handleCancel]] (command - cancel session)
  *
  * Session creation/list/get/cancel route through [[GameServiceApi]]. Aggregate read/write flows
  * route through [[PersistentSessionService]] so the HTTP adapter depends only on application
  * services, never repositories.
  */
class Http4sSessionRoutes(
    gameService: GameServiceApi,
    persistentSessionService: PersistentSessionService,
    snapshotTransferService: SessionSnapshotTransferService,
    metrics: DomainMetricsRegistry = new DomainMetricsRegistry(),
    userClient: Option[AuthenticatedUserClient] = None
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case req @ POST -> Root / "sessions" =>
      req.bodyText.compile.string.flatMap(handleCreate(req, None, _))

    case req @ POST -> Root / "sessions" / "human-vs-human" =>
      req.bodyText.compile.string.flatMap(handleCreate(req, Some(SessionMode.HumanVsHuman), _))

    case req @ POST -> Root / "sessions" / "human-vs-ai" =>
      req.bodyText.compile.string.flatMap(handleCreate(req, Some(SessionMode.HumanVsAI), _))

    case req @ POST -> Root / "sessions" / "ai-vs-ai" =>
      req.bodyText.compile.string.flatMap(handleCreate(req, Some(SessionMode.AIVsAI), _))

    case req @ POST -> Root / "sessions" / "import" =>
      req.bodyText.compile.string.flatMap(handleImport)

    case GET -> Root / "sessions" =>
      handleList()

    case req @ GET -> Root / "sessions" / "mine" =>
      handleListMine(req)

    case GET -> Root / "sessions" / id =>
      handleGet(id)

    case GET -> Root / "sessions" / id / "state" =>
      handleGetState(id)

    case req @ PUT -> Root / "sessions" / id / "state" =>
      req.bodyText.compile.string.flatMap(handlePutState(id, _))

    case GET -> Root / "sessions" / id / "export" =>
      handleExport(id)

    case POST -> Root / "sessions" / id / "cancel" =>
      handleCancel(id)
  }

  private def handleCreate(req0: Request[IO], fixedMode: Option[SessionMode], body: String): IO[Response[IO]] =
    val result: Either[(Status, String, String), CreateSessionResponse] =
      for
        authUser <- resolveOwnerIfConfigured(req0)
        req <- CreateSessionRequest.fromJson(body).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        mode <- fixedMode match
          case Some(expectedMode) =>
            req.mode match
              case Some(rawMode) if rawMode != expectedMode.toString =>
                Left(
                  (Status.BadRequest, "BAD_REQUEST", s"Mode mismatch for this endpoint: expected ${expectedMode.toString}, got $rawMode")
                )
              case _ => Right(expectedMode)
          case None => SessionMapper.parseMode(req.mode).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        controllers <- SessionMapper.resolveCreateControllers(
          mode,
          req.whiteController,
          req.blackController
        ).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        (white, black) = controllers
        pair <- gameService
          .createGame(
            mode,
            white,
            black,
            ownerUserId = authUser.map(_.userId),
            ownerNicknameSnapshot = authUser.flatMap(_.nickname)
          )
          .left
          .map(err => (Status.BadRequest, "BAD_REQUEST", sessionErrMsg(err)))
        (state, session) = pair
      yield SessionMapper.toCreateSessionResponse(
        session,
        session.gameId,
        GameMapper.toGameResponse(GameView.fromState(session.gameId, state))
      )

    result match
      case Right(resp) =>
        metrics.recordSessionCreated()
        jsonResponse(Status.Created, CreateSessionResponse.toJson(resp))
      case Left((status, code, msg)) =>
        jsonError(status, code, msg)

  private def handleImport(body: String): IO[Response[IO]] =
    val result: Either[(Status, String, String), SessionStateResponse] =
      for
        dto <- SessionExportEnvelope
          .fromJson(body)
          .left
          .map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        envelope <- SessionMapper
          .toSessionSnapshotEnvelope(dto)
          .left
          .map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        imported <- snapshotTransferService
          .importSnapshot(envelope)
          .left
          .map(snapshotErrToHttpErr)
      yield SessionMapper.toSessionStateResponse(imported)

    result match
      case Right(resp)                   => jsonResponse(Status.Created, SessionStateResponse.toJson(resp))
      case Left((status, code, message)) => jsonError(status, code, message)

  private def handleList(): IO[Response[IO]] =
    gameService.listActiveSessions() match
      case Left(err) =>
        jsonError(Status.InternalServerError, "INTERNAL_ERROR", sessionErrMsg(err))
      case Right(sessions) =>
        val items = sessions.map(SessionMapper.toSessionResponse)
        jsonResponse(Status.Ok, SessionListResponse.toJson(SessionListResponse(items)))

  private def handleListMine(req: Request[IO]): IO[Response[IO]] =
    resolveRequiredOwner(req) match
      case Left((status, code, message)) => jsonError(status, code, message)
      case Right(user) =>
        gameService.listSessionsByOwner(user.userId) match
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

  private def handleGetState(idStr: String): IO[Response[IO]] =
    parseUUID(idStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        val startNs = System.nanoTime()
        val result  = persistentSessionService.loadAggregate(SessionId(uuid))
        metrics.recordFetchState((System.nanoTime() - startNs).toDouble / 1e9)
        result match
          case Left(err) =>
            val (status, code, message) = persistentErrToHttpErr(err, idStr)
            jsonError(status, code, message)
          case Right(aggregate) =>
            jsonResponse(
              Status.Ok,
              SessionStateResponse.toJson(SessionMapper.toSessionStateResponse(aggregate))
            )

  private def handlePutState(idStr: String, body: String): IO[Response[IO]] =
    type HttpErr = (Status, String, String)

    val result =
      for
        uuid <- parseUUID(idStr).left.map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        dto <- SessionStateResponse
          .fromJson(body)
          .left
          .map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        aggregate <- SessionMapper
          .toPersistentSessionAggregate(dto)
          .left
          .map(msg => (Status.BadRequest, "BAD_REQUEST", msg))
        saved <- persistentSessionService
          .saveAggregate(SessionId(uuid), aggregate)
          .left
          .map(persistentErrToHttpErr(_, idStr))
      yield saved

    result match
      case Left((status, code, message)) =>
        jsonError(status, code, message)
      case Right(saved) =>
        jsonResponse(
          Status.Ok,
          SessionStateResponse.toJson(SessionMapper.toSessionStateResponse(saved))
        )

  private def handleExport(idStr: String): IO[Response[IO]] =
    parseUUID(idStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        snapshotTransferService.exportSnapshot(SessionId(uuid)) match
          case Left(err) =>
            val (status, code, message) = snapshotErrToHttpErr(err)
            jsonError(status, code, message)
          case Right(envelope) =>
            jsonResponse(
              Status.Ok,
              SessionExportEnvelope.toJson(SessionMapper.toSessionExportEnvelope(envelope))
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

  private def sessionErrMsg(err: SessionError): String = err match
    case SessionError.SessionNotFound(id)           => s"Session not found: ${id.value}"
    case SessionError.GameSessionNotFound(id)       => s"Game session not found: ${id.value}"
    case SessionError.PersistenceFailed(cause)      => s"Storage error: $cause"
    case SessionError.InvalidLifecycleTransition(r) => s"Invalid lifecycle transition: $r"

  private def resolveOwnerIfConfigured(
      req: Request[IO]
  ): Either[(Status, String, String), Option[AuthenticatedSearchessUser]] =
    userClient match
      case None => Right(None)
      case Some(_) => resolveRequiredOwner(req).map(Some(_))

  private def resolveRequiredOwner(
      req: Request[IO]
  ): Either[(Status, String, String), AuthenticatedSearchessUser] =
    userClient match
      case None => Left((Status.InternalServerError, "AUTH_NOT_CONFIGURED", "Authenticated user client is not configured"))
      case Some(client) =>
        req.headers.get(CIString("Authorization")).map(_.head.value) match
          case None => Left((Status.Unauthorized, "UNAUTHORIZED", "Missing Authorization header"))
          case Some(authHeader) =>
            client.getCurrentUser(authHeader) match
              case Right(user) if user.onboardingRequired =>
                Left((Status.Forbidden, "ONBOARDING_REQUIRED", "Complete onboarding before creating games"))
              case Right(user) => Right(user)
              case Left(AuthenticatedUserClientError.Unauthorized(message)) =>
                Left((Status.Unauthorized, "UNAUTHORIZED", message))
              case Left(AuthenticatedUserClientError.UpstreamFailure(message)) =>
                Left((Status.BadGateway, "USER_SERVICE_UNAVAILABLE", message))
              case Left(AuthenticatedUserClientError.InvalidResponse(message)) =>
                Left((Status.BadGateway, "USER_SERVICE_INVALID_RESPONSE", message))

  private def persistentErrToHttpErr(
      err: PersistentSessionError,
      idStr: String
  ): (Status, String, String) =
    err match
      case PersistentSessionError.NotFound(_) =>
        (Status.NotFound, "SESSION_NOT_FOUND", s"Session not found: $idStr")
      case PersistentSessionError.BadInput(message) =>
        (Status.BadRequest, "BAD_REQUEST", message)
      case PersistentSessionError.Conflict(message) =>
        (Status.Conflict, "CONFLICT", message)
      case PersistentSessionError.AggregateInconsistent(message) =>
        (Status.InternalServerError, "INTERNAL_ERROR", message)
      case PersistentSessionError.StorageFailure(message) =>
        (Status.InternalServerError, "INTERNAL_ERROR", message)

  private def snapshotErrToHttpErr(
      err: SessionSnapshotTransferError
  ): (Status, String, String) =
    err match
      case SessionSnapshotTransferError.NotFound(message) =>
        (Status.NotFound, "SESSION_NOT_FOUND", message)
      case SessionSnapshotTransferError.BadInput(message) =>
        (Status.BadRequest, "BAD_REQUEST", message)
      case SessionSnapshotTransferError.Conflict(message) =>
        (Status.Conflict, "CONFLICT", message)
      case SessionSnapshotTransferError.StorageFailure(message) =>
        (Status.InternalServerError, "INTERNAL_ERROR", message)
