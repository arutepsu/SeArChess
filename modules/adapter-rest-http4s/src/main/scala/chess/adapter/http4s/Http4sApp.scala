package chess.adapter.http4s

import cats.effect.IO
import cats.syntax.semigroupk.*
import chess.adapter.http4s.route.{Http4sGameRoutes, Http4sSessionRoutes}
import chess.application.port.repository.GameRepository
import chess.application.session.service.{GameSessionCommands, SessionService}
import org.http4s.HttpApp

/** REST adapter surface for the chess API.
 *
 *  Composes route classes into a single [[HttpApp]] ready to be mounted on any
 *  http4s-compatible server.  Server lifecycle (host, port, Ember binding, and
 *  resource acquisition) is the responsibility of the composition root
 *  (`bootstrap-server/Main`).
 *
 *  The command/query split is explicit:
 *  - `commands` drives all state-mutating routes ([[Http4sSessionRoutes]] POST,
 *    [[Http4sGameRoutes]] POST) via the [[GameSessionCommands]] boundary.
 *  - `sessionService` serves read-only session lookups (GET routes).
 *  - `gameRepository` serves read-only game-state lookups (GET routes).
 *
 *  @param commands       game-session command boundary (write path)
 *  @param sessionService session read/query service
 *  @param gameRepository application-level game-state read port
 */
class Http4sApp(
  commands:       GameSessionCommands,
  sessionService: SessionService,
  gameRepository: GameRepository
):

  private val combinedRoutes =
    Http4sSessionRoutes(commands, sessionService).routes <+>
    Http4sGameRoutes(commands, sessionService, gameRepository).routes

  /** Combined [[HttpApp]] for all REST routes.
   *
   *  Mount this on an http4s server in the composition root.  Unmatched
   *  requests receive a 404 Not Found response via `orNotFound`.
   */
  def httpApp: HttpApp[IO] = combinedRoutes.orNotFound
