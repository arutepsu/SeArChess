package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.http4s.route.Http4sRouteSupport.*
import chess.adapter.rest.contract.dto.ArchiveSnapshotResponse
import chess.adapter.rest.contract.mapper.ArchiveSnapshotMapper
import chess.application.{ArchiveError, GameServiceApi}
import chess.application.session.model.SessionIds.GameId
import org.http4s.*
import org.http4s.dsl.io.*

class Http4sArchiveRoutes(gameService: GameServiceApi):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
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
            jsonError(Status.NotFound, "ARCHIVE_NOT_FOUND", s"Archive snapshot not found for game: $gameIdStr")
          case Left(ArchiveError.GameNotClosed(_)) =>
            jsonError(Status.Conflict, "ARCHIVE_NOT_READY", s"Archive snapshot is not available until the game is closed: $gameIdStr")
          case Left(ArchiveError.StorageFailure(msg)) =>
            jsonError(Status.InternalServerError, "INTERNAL_ERROR", msg)
