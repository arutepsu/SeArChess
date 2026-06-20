package chess.tournamentservice

import cats.effect.IO
import cats.syntax.semigroupk.*
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import ujson.{Null, Obj, Str}

final class PublicImportRoutes(service: PublicImportService):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "api" / "tournaments" / "public" / "import" =>
      req.bodyText.compile.string.flatMap { body =>
        parseImportRequest(body) match
          case Left(msg) =>
            error(Status.BadRequest, "INVALID_IMPORT_REQUEST", msg)
          case Right(request) =>
            service.importTournament(request).flatMap {
              case Left(ImportError.NotFound(tid)) =>
                error(Status.NotFound, "TOURNAMENT_NOT_FOUND", s"Tournament not found on public server: $tid")
              case Left(ImportError.NotFinished(tid)) =>
                error(Status.Conflict, "TOURNAMENT_NOT_FINISHED", s"Tournament is not finished yet: $tid")
              case Left(ImportError.HttpError(status, body)) =>
                error(Status.BadGateway, "PUBLIC_SERVER_ERROR", s"Public server returned HTTP $status: ${body.take(120)}")
              case Left(ImportError.ParseError(msg)) =>
                error(Status.UnprocessableEntity, "EXPORT_PARSE_ERROR", msg)
              case Left(ImportError.NetworkError(msg)) =>
                error(Status.BadGateway, "NETWORK_ERROR", msg)
              case Right(result) =>
                json(Status.Ok, importResultJson(result))
            }
      }
  }

  private def importResultJson(r: PublicImportResult): ujson.Value =
    Obj(
      "jobId"              -> r.jobId,
      "publicTournamentId" -> r.publicTournamentId,
      "serverBaseUrl"      -> r.serverBaseUrl,
      "jsonlPath"          -> r.jsonlPath,
      "status"             -> r.status,
      "analyzeUrl"         -> s"/api/tournaments/${r.jobId}/analyze",
    )

  private def parseImportRequest(body: String): Either[String, PublicImportRequest] =
    scala.util.Try {
      val obj           = ujson.read(body).obj
      val serverBaseUrl = obj.get("serverBaseUrl").collect { case Str(s) => s.trim }.filter(_.nonEmpty)
        .toRight("serverBaseUrl is required")
      val tournamentId  = obj.get("tournamentId").collect { case Str(s) => s.trim }.filter(_.nonEmpty)
        .toRight("tournamentId is required")
      for
        url <- serverBaseUrl
        tid <- tournamentId
      yield PublicImportRequest(url, tid)
    }.toEither
      .left.map(e => s"Invalid JSON: ${Option(e.getMessage).getOrElse("unknown")}")
      .flatten

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO],
      )
    )

  private def error(status: Status, code: String, message: String): IO[Response[IO]] =
    json(status, Obj("code" -> code, "message" -> message))
