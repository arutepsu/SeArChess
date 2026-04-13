package chess.adapter.http4s

import cats.effect.{IO, Resource}
import cats.syntax.semigroupk.*
import chess.adapter.http4s.route.{Http4sGameRoutes, Http4sSessionRoutes}
import chess.application.port.repository.GameRepository
import chess.application.session.service.SessionGameService
import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Server

/** Ember-based HTTP server for the chess REST API.
 *
 *  This is the authoritative REST adapter for the project.  It replaces the
 *  JDK-`HttpServer`-based spike in `chess.adapter.rest`.
 *
 *  === Design ===
 *  - `sessionService` and `gameRepository` are injected by the caller (typically
 *    `chess.Main`), so this class depends only on `application` ports and does
 *    not hard-code any persistence implementation.
 *  - Routes are composed via [[Router]] so each route class works on its own
 *    path prefix independently.
 *  - The server lifecycle is expressed as `Resource[IO, Server]`, the standard
 *    Cats Effect pattern for bracketed resources.  Callers acquire the server
 *    inside `Resource.use` (or `.allocated` for ScalaTest suites) and the
 *    server is cleanly stopped when the resource is released.
 *
 *  === Ports ===
 *  Pass `port = 0` to bind to an OS-assigned ephemeral port.  The actual
 *  port can be read from `server.address.port.value` after acquisition.
 *
 *  @param sessionGameService unified application mutation boundary
 *  @param gameRepository    application-level game state port
 *  @param port              TCP port; 0 for ephemeral (useful in tests)
 */
class Http4sServer(
  sessionGameService: SessionGameService,
  gameRepository:     GameRepository,
  port:               Int = 8080
):

  private val combinedRoutes =
    Http4sSessionRoutes(sessionGameService).routes <+>
    Http4sGameRoutes(sessionGameService, gameRepository).routes

  /** Acquire the bound server as a `Resource`.  The server is stopped when
   *  the resource is released.
   */
  def resource: Resource[IO, Server] =
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"0.0.0.0")
      .withPort(Port.fromInt(port).getOrElse(port"8080"))
      .withHttpApp(combinedRoutes.orNotFound)
      .build
