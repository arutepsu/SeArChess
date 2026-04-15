package chess

import cats.effect.IO
import chess.config.AppMode
import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/** Operational health endpoint for the chess server.
 *
 *  Owned by `bootstrap-server` as a runtime operations concern, intentionally
 *  separate from the chess business REST API in
 *  [[chess.adapter.http4s.Http4sApp]].  Business routes must not be added here.
 *
 *  === Endpoint ===
 *  {{{
 *    GET /health → 200 OK
 *    Content-Type: application/json
 *
 *    {"status":"ok","mode":"desktop"}
 *  }}}
 *
 *  The `mode` field reflects the runtime [[AppMode]] from config.  No deep
 *  dependency checks are performed; the response indicates the server process
 *  is alive and has completed startup.
 */
object HealthRoutes:

  def routes(mode: AppMode): HttpRoutes[IO] =
    val body = s"""{"status":"ok","mode":"${mode.toString.toLowerCase}"}"""
    HttpRoutes.of[IO]:
      case GET -> Root / "health" =>
        Ok(body).map(_.withContentType(`Content-Type`(MediaType.application.json)))
