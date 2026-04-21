package chess.server.http

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.MediaType
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

/** Operational health endpoint for the chess server.
  *
  * Owned by `game-service` as a runtime operations concern, intentionally separate from the chess
  * business REST API in [[chess.adapter.http4s.Http4sApp]]. Business routes must not be added here.
  *
  * ===Endpoint===
  * {{{
  *    GET /health -> 200 OK
  *    Content-Type: application/json
  *
  *    {"status":"ok","service":"searchess-game-service","check":"process-liveness"}
  * }}}
  *
  * No deep dependency checks are performed; the response indicates the server process is alive and
  * has completed startup.
  */
object HealthRoutes:

  private val HealthSegment = GameServiceHttpSurface.PublicHealthPath.stripPrefix("/")

  def routes: HttpRoutes[IO] =
    HttpRoutes.of[IO]:
      case GET -> Root / `HealthSegment` =>
        Ok(
          """{"status":"ok","service":"searchess-game-service","check":"process-liveness","optionalDependencies":["ai-service","history-service"]}"""
        )
          .map(_.withContentType(`Content-Type`(MediaType.application.json)))
