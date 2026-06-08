package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.mapper.ArchiveSnapshotMapper
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.ArchiveSnapshotResponse
import chess.application.{ArchiveError, GameServiceApi}
import chess.application.session.model.SessionIds.GameId
import org.http4s.*
import org.http4s.dsl.io.*
import org.typelevel.ci.CIString

class Http4sArchiveRoutes(
    gameService: GameServiceApi,
    userClient: Option[AuthenticatedUserClient] = None,
    historyClient: Option[HistoryArchiveClient] = None
):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ GET -> Root / "archive" / "mine" =>
      handleGetMine(req)

    case GET -> Root / "archive" / "games" / id =>
      handleGetArchive(id)
  }

  private def handleGetArchive(gameIdStr: String): IO[Response[IO]] =
    parseUUID(gameIdStr) match
      case Left(msg) =>
        jsonError(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(uuid) =>
        val gameId = GameId(uuid)
        gameService.getArchiveSnapshot(gameId) match
          case Right(snapshot) =>
            jsonResponse(
              Status.Ok,
              ArchiveSnapshotResponse.toJson(ArchiveSnapshotMapper.toResponse(snapshot))
            )
          case Left(ArchiveError.GameNotFound(_)) =>
            jsonError(
              Status.NotFound,
              "ARCHIVE_NOT_FOUND",
              s"Archive snapshot not found for game: $gameIdStr"
            )
          case Left(ArchiveError.GameNotClosed(_)) =>
            jsonError(
              Status.Conflict,
              "ARCHIVE_NOT_READY",
              s"Archive snapshot is not available until the game is closed: $gameIdStr"
            )
          case Left(ArchiveError.StorageFailure(msg)) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", msg)

  private def handleGetMine(req: Request[IO]): IO[Response[IO]] =
    (userClient, historyClient) match
      case (Some(users), Some(history)) =>
        req.headers.get(CIString("Authorization")).map(_.head.value) match
          case None => jsonError(Status.Unauthorized, "UNAUTHORIZED", "Missing Authorization header")
          case Some(authHeader) =>
            users.getCurrentUser(authHeader) match
              case Right(user) if user.onboardingRequired =>
                jsonError(Status.Forbidden, "ONBOARDING_REQUIRED", "Complete onboarding before viewing history")
              case Right(user) =>
                history.findByOwner(user.userId) match
                  case Right(body) => jsonResponse(Status.Ok, body)
                  case Left(HistoryArchiveClientError.UpstreamFailure(message)) =>
                    jsonError(Status.BadGateway, "HISTORY_SERVICE_UNAVAILABLE", message)
                  case Left(HistoryArchiveClientError.InvalidResponse(message)) =>
                    jsonError(Status.BadGateway, "HISTORY_SERVICE_INVALID_RESPONSE", message)
              case Left(AuthenticatedUserClientError.Unauthorized(message)) =>
                jsonError(Status.Unauthorized, "UNAUTHORIZED", message)
              case Left(AuthenticatedUserClientError.UpstreamFailure(message)) =>
                jsonError(Status.BadGateway, "USER_SERVICE_UNAVAILABLE", message)
              case Left(AuthenticatedUserClientError.InvalidResponse(message)) =>
                jsonError(Status.BadGateway, "USER_SERVICE_INVALID_RESPONSE", message)
      case _ =>
        jsonError(Status.InternalServerError, "AUTH_NOT_CONFIGURED", "Authenticated archive lookup is not configured")
