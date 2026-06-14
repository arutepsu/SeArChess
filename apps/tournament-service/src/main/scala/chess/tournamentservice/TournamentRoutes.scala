package chess.tournamentservice

import cats.effect.IO
import cats.syntax.semigroupk.*
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

final class TournamentRoutes(service: TournamentJobService):

  private val uuidPattern =
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}".r

  private def validateJobId(jobId: String): Either[String, String] =
    if uuidPattern.matches(jobId) then Right(jobId)
    else Left(s"Invalid job ID: expected UUID format (xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx), got '$jobId'")

  val operationalRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] { case GET -> Root / "health" =>
    json(Status.Ok, ujson.Obj("status" -> "ok", "service" -> "searchess-tournament-service"))
  }

  val tournamentRoutes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case GET -> Root / "api" / "tournaments" / "bots" =>
      service.listBots().flatMap(bots =>
        json(Status.Ok, ujson.Obj("bots" -> ujson.Arr(bots.map(TournamentJson.bot)*)))
      )

    case req @ POST -> Root / "api" / "tournaments" =>
      req.bodyText.compile.string.flatMap { body =>
        TournamentJson.parseCreateRequest(body) match
          case Left(msg) =>
            error(Status.BadRequest, "INVALID_TOURNAMENT_REQUEST", msg)
          case Right(create) =>
            service.create(create).flatMap {
              case Left(msg)  => error(Status.BadRequest, "INVALID_TOURNAMENT_REQUEST", msg)
              case Right(job) => json(Status.Accepted, TournamentJson.accepted(job))
            }
      }

    case GET -> Root / "api" / "tournaments" =>
      service.listJobs().flatMap(jobs =>
        json(Status.Ok, ujson.Obj("jobs" -> ujson.Arr(jobs.map(TournamentJson.jobSummary)*)))
      )

    case GET -> Root / "api" / "tournaments" / jobId =>
      validateJobId(jobId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_JOB_ID", msg)
        case Right(id) =>
          service.getJob(id).flatMap {
            case Some(job) => json(Status.Ok, TournamentJson.job(job))
            case None      => error(Status.NotFound, "JOB_NOT_FOUND", s"Tournament job not found: $id")
          }

    case POST -> Root / "api" / "tournaments" / jobId / "cancel" =>
      validateJobId(jobId) match
        case Left(msg) => error(Status.BadRequest, "INVALID_JOB_ID", msg)
        case Right(id) =>
          service.cancel(id).flatMap {
            case Some(job) => json(Status.Ok, TournamentJson.job(job))
            case None      => error(Status.NotFound, "JOB_NOT_FOUND", s"Tournament job not found: $id")
          }
  }

  val routes: HttpRoutes[IO] = operationalRoutes <+> tournamentRoutes

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )

  private def error(status: Status, code: String, message: String): IO[Response[IO]] =
    json(status, ujson.Obj("code" -> code, "message" -> message))
