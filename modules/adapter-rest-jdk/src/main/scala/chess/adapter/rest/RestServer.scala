package chess.adapter.rest

import chess.adapter.repository.{InMemoryGameRepository, InMemorySessionRepository}
import chess.adapter.rest.route.{GameRoutes, SessionRoutes}
import chess.application.session.service.SessionService
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/** Minimal REST HTTP server wired up with in-memory adapters.
 *
 *  === HTTP framework ===
 *  Uses the JDK's built-in `com.sun.net.httpserver.HttpServer` (zero extra
 *  dependencies).  Suitable for Phase 7–9; replace with http4s or Tapir when
 *  production concerns (streaming, typed middleware) arise.
 *
 *  === Registered contexts ===
 *  - `/sessions`  → [[SessionRoutes]] (POST /sessions, GET /sessions/{id})
 *  - `/games/`    → [[GameRoutes]]    (GET /games/{id}, POST /games/{id}/moves)
 *
 *  === State ===
 *  Both repositories are in-memory and application-port-backed.  State is lost
 *  when the JVM exits.  Durable adapters come in a later phase.
 *
 *  @param port TCP port to listen on; pass 0 for an ephemeral port (useful
 *              in tests — read the actual port from `server.getAddress.getPort`).
 */
class RestServer(port: Int = 8080):

  private val sessionRepository = InMemorySessionRepository()
  private val gameRepository    = InMemoryGameRepository()
  private val sessionService    = SessionService(sessionRepository, _ => ())

  /** Start the HTTP server and return the underlying [[HttpServer]] so callers
   *  can stop it (e.g. in tests via `server.stop(0)`).
   */
  def start(): HttpServer =
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.createContext("/sessions", SessionRoutes(sessionService, gameRepository))
    server.createContext("/games/",   GameRoutes(sessionService, gameRepository))
    server.setExecutor(null) // single-threaded default executor
    server.start()
    server
