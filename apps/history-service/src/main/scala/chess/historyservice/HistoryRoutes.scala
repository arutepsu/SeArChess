package chess.historyservice

import cats.effect.IO
import cats.syntax.semigroupk.*
import chess.adapter.event.GameHistoryIngestionContract
import chess.application.session.model.SessionIds.GameId
import chess.history.*
import chess.history.sqlite.SqliteArchiveRepository
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import java.util.UUID

class HistoryRoutes(
  ingestion:  HistoryIngestionService,
  repository: SqliteArchiveRepository,
  acceptLegacyIngestionPath: Boolean = false
):

  /** Operational liveness only; no upstream/dependency checks. */
  val operationalRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "health" =>
      json(Status.Ok, ujson.Obj(
        "status"                     -> "ok",
        "service"                    -> "searchess-history-service",
        "check"                      -> "process-liveness",
        "gameServiceDependency"      -> "optional-for-health",
        "downstreamIngestionPath"    -> GameHistoryIngestionContract.GameEventsPath,
        "legacyIngestionPathEnabled" -> acceptLegacyIngestionPath,
        "archiveReadAudience"        -> "internal-for-now"
      ))
  }

  /** History-owned archive query surface. Internal for now; not routed through the public edge. */
  val internalArchiveRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "archives" / gameId =>
      handleGetArchive(gameId)
  }

  /** Downstream ingestion surface called by Game Service outbox forwarding. */
  val downstreamIngestionRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "internal" / "events" / "game" =>
      req.bodyText.compile.string.flatMap(handleEvent)
  }

  /** Temporary compatibility alias for the pre-boundary-audit ingestion path. Disabled unless explicitly configured. */
  val legacyDownstreamIngestionRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "events" / "game" =>
      req.bodyText.compile.string.flatMap(handleEvent)
  }

  val routes: HttpRoutes[IO] =
    val baseRoutes = operationalRoutes <+> internalArchiveRoutes <+> downstreamIngestionRoutes
    if acceptLegacyIngestionPath then baseRoutes <+> legacyDownstreamIngestionRoutes else baseRoutes

  private def handleEvent(body: String): IO[Response[IO]] =
    ingestion.ingestEventJson(body) match
      case Right(record) =>
        json(Status.Created, ArchiveRecordJson.toJson(record))
      case Left(HistoryIngestionError.InvalidEvent(msg)) =>
        error(Status.BadRequest, "INVALID_EVENT", msg)
      case Left(HistoryIngestionError.ArchiveFetchFailed(GameArchiveClientError.NotFound(id))) =>
        error(Status.NotFound, "ARCHIVE_NOT_FOUND", s"Game Service has no archive snapshot for game: ${id.value}")
      case Left(HistoryIngestionError.ArchiveFetchFailed(GameArchiveClientError.NotReady(id))) =>
        error(Status.Conflict, "ARCHIVE_NOT_READY", s"Game Service archive snapshot is not ready for game: ${id.value}")
      case Left(HistoryIngestionError.ArchiveFetchFailed(err)) =>
        error(Status.BadGateway, "GAME_SERVICE_FETCH_FAILED", err.toString)
      case Left(HistoryIngestionError.MaterializationFailed(err)) =>
        error(Status.UnprocessableEntity, "MATERIALIZATION_FAILED", err.toString)
      case Left(HistoryIngestionError.PersistenceFailed(msg)) =>
        error(Status.InternalServerError, "PERSISTENCE_FAILED", msg)

  private def handleGetArchive(gameIdStr: String): IO[Response[IO]] =
    parseGameId(gameIdStr) match
      case Left(msg) => error(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(gameId) =>
        repository.findRecordJson(gameId) match
          case Right(Some(record)) => json(Status.Ok, record)
          case Right(None)         => error(Status.NotFound, "ARCHIVE_NOT_FOUND", s"History archive not found for game: $gameIdStr")
          case Left(err)           => error(Status.InternalServerError, "PERSISTENCE_FAILED", err.toString)

  private def parseGameId(raw: String): Either[String, GameId] =
    try Right(GameId(UUID.fromString(raw)))
    catch case _: IllegalArgumentException => Left(s"Invalid UUID: '$raw'")

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(Response[IO](
      status = status,
      headers = Headers(`Content-Type`(MediaType.application.json)),
      body = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
    ))

  private def error(status: Status, code: String, message: String): IO[Response[IO]] =
    json(status, ujson.Obj("code" -> code, "message" -> message))
