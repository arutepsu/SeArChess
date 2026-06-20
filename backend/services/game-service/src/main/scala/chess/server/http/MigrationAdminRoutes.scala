package chess.server.http

import cats.effect.IO
import chess.application.migration.{MigrationMode, MigrationReport}
import chess.server.migration.{Backend, MigrationCommand, MigrationReportFormatter, ReportFormat}
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

/** Internal admin route for triggering cross-database migrations via HTTP.
  *
  * Disabled by default (MIGRATION_ADMIN_ENABLED=false). Intended for demo and operator use only.
  * For production deployments, authentication and authorization controls must be added before
  * exposing this route. Do not route through the public Envoy proxy.
  *
  * Route: POST /admin/migrations
  *
  * @param runMigration
  *   synchronous migration runner — injected so tests can substitute a stub without real databases.
  *   In production wiring this is [[chess.server.migration.MigrationCliRunner.runForReport]].
  */
class MigrationAdminRoutes(
    adminToken: String,
    runMigration: MigrationCommand => Either[String, MigrationReport]
):

  private val RequiredConfirmation  = "MIGRATE"
  private val DefaultBatchSize      = 100
  private val AdminTokenHeader      = CIString("X-Admin-Token")

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case req @ POST -> Root / "admin" / "migrations" =>
      req.headers.get(AdminTokenHeader).map(_.head.value) match
        case Some(t) if t == adminToken =>
          req.bodyText.compile.string.flatMap(handleMigrate)
        case Some(_) =>
          error(Status.Unauthorized, "UNAUTHORIZED", "Admin token is invalid.")
        case None =>
          error(Status.Unauthorized, "UNAUTHORIZED", "X-Admin-Token header is required.")
  }

  private def handleMigrate(body: String): IO[Response[IO]] =
    parseAndValidate(body) match
      case Left(msg) =>
        error(Status.BadRequest, "BAD_REQUEST", msg)
      case Right(command) =>
        IO.blocking(runMigration(command)).flatMap {
          case Left(msg)     => error(Status.InternalServerError, "MIGRATION_FAILED", msg)
          case Right(report) => rawJson(Status.Ok, MigrationReportFormatter.format(report, ReportFormat.Json))
        }

  private[http] def parseAndValidate(body: String): Either[String, MigrationCommand] =
    parseBody(body).flatMap { json =>
      for
        sourceStr <- requiredStr(json, "source")
        targetStr <- requiredStr(json, "target")
        modeStr   <- requiredStr(json, "mode")
        source    <- Backend.parse(sourceStr)
        target    <- Backend.parse(targetStr)
        _         <- Either.cond(source != target, (), "source and target must differ")
        mode      <- parseMigrationMode(modeStr)
        batchSize  = optInt(json, "batchSize", DefaultBatchSize)
        _         <- Either.cond(batchSize > 0, (), "batchSize must be a positive integer")
        vae        = optBool(json, "validateAfterExecute", default = false)
        _         <- validateVAE(mode, vae)
        confirm    = optStr(json, "confirmation")
        _         <- validateConfirmation(mode, confirm)
      yield MigrationCommand(source, target, mode, batchSize, ReportFormat.Json, vae)
    }

  private def parseBody(body: String): Either[String, ujson.Obj] =
    try
      ujson.read(body) match
        case obj: ujson.Obj => Right(obj)
        case _              => Left("Request body must be a JSON object")
    catch case _: Exception => Left("Request body is not valid JSON")

  private def requiredStr(json: ujson.Obj, key: String): Either[String, String] =
    json.value.get(key).flatMap(_.strOpt) match
      case Some(v) if v.nonEmpty => Right(v)
      case Some(_)               => Left(s"$key must be a non-empty string")
      case None                  => Left(s"$key is required")

  private def optStr(json: ujson.Obj, key: String): Option[String] =
    json.value.get(key).flatMap(_.strOpt).filter(_.nonEmpty)

  private def optInt(json: ujson.Obj, key: String, default: Int): Int =
    json.value.get(key).flatMap(v => v.numOpt.map(_.toInt)).getOrElse(default)

  private def optBool(json: ujson.Obj, key: String, default: Boolean): Boolean =
    json.value.get(key).fold(default) { v =>
      try v.bool
      catch case _: Exception => default
    }

  private def parseMigrationMode(value: String): Either[String, MigrationMode] =
    value.trim.toLowerCase match
      case "dry-run"       => Right(MigrationMode.DryRun)
      case "execute"       => Right(MigrationMode.Execute)
      case "validate-only" => Right(MigrationMode.ValidateOnly)
      case other           => Left(s"Unsupported mode: $other. Use dry-run, execute, or validate-only")

  private def validateVAE(mode: MigrationMode, vae: Boolean): Either[String, Unit] =
    mode match
      case MigrationMode.Execute => Right(())
      case _ if vae              => Left("validateAfterExecute is only valid with execute mode")
      case _                     => Right(())

  private def validateConfirmation(mode: MigrationMode, confirmation: Option[String]): Either[String, Unit] =
    mode match
      case MigrationMode.Execute =>
        confirmation match
          case Some(c) if c == RequiredConfirmation => Right(())
          case Some(c) => Left(s"""execute mode requires confirmation "$RequiredConfirmation", got "$c"""")
          case None    => Left(s"""execute mode requires confirmation "$RequiredConfirmation"""")
      case _ => Right(())

  private def rawJson(status: Status, jsonString: String): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body = Stream.emits(jsonString.getBytes("UTF-8")).covary[IO]
      )
    )

  private def error(status: Status, code: String, message: String): IO[Response[IO]] =
    rawJson(status, ujson.write(ujson.Obj("code" -> code, "message" -> message)))
