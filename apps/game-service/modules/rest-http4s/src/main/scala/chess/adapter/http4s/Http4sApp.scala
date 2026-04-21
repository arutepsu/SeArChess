package chess.adapter.http4s

import cats.effect.IO
import cats.syntax.semigroupk.*
import chess.adapter.http4s.route.{Http4sArchiveRoutes, Http4sGameRoutes, Http4sSessionRoutes}
import chess.application.GameServiceApi
import org.http4s.HttpApp

/** REST adapter surface for the chess API.
  *
  * Composes route classes into a single [[HttpApp]] ready to be mounted on any http4s-compatible
  * server. Server lifecycle (host, port, Ember binding, and resource acquisition) is the
  * responsibility of the composition root (`game-service/Main`).
  *
  * Both route classes depend only on [[GameServiceApi]] — the single Game Service boundary. This
  * replaces the previous three-dependency split
  * ([[chess.application.session.service.GameSessionCommands]],
  * [[chess.application.session.service.SessionService]],
  * [[chess.application.port.repository.GameRepository]]).
  *
  * @param gameService
  *   the Game Service boundary (commands + queries)
  */
class Http4sApp(gameService: GameServiceApi):

  private val combinedRoutes =
    Http4sSessionRoutes(gameService).routes <+>
      Http4sGameRoutes(gameService).routes <+>
      Http4sArchiveRoutes(gameService).routes

  /** Combined [[HttpApp]] for all REST routes.
    *
    * Mount this on an http4s server in the composition root. Unmatched requests receive a 404 Not
    * Found response via `orNotFound`.
    */
  def httpApp: HttpApp[IO] = combinedRoutes.orNotFound
