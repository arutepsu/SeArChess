package chess.adapter.http4s.route

import cats.effect.IO
import chess.adapter.rest.dto.ErrorResponse
import fs2.Stream
import org.http4s.*
import org.http4s.headers.`Content-Type`
import java.util.UUID

/** Shared response-building helpers for http4s route classes.
 *
 *  All methods return `IO[Response[IO]]` so they compose naturally with
 *  http4s's `HttpRoutes.of[IO]` blocks.
 *
 *  JSON serialisation uses ujson (already a project dependency) rather than
 *  circe, keeping the adapter self-contained.
 */
object Http4sRouteSupport:

  /** Build a JSON HTTP response with the given status and ujson body. */
  def jsonResponse(status: Status, json: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(json).getBytes("UTF-8")).covary[IO]
      )
    )

  /** Build a structured JSON error response. */
  def jsonError(status: Status, code: String, message: String): IO[Response[IO]] =
    jsonResponse(status, ErrorResponse.toJson(ErrorResponse(code, message)))

  /** Parse a UUID string.  Returns Left with a human-readable message on failure. */
  def parseUUID(s: String): Either[String, UUID] =
    try Right(UUID.fromString(s))
    catch case _: IllegalArgumentException => Left(s"Invalid UUID: '$s'")
