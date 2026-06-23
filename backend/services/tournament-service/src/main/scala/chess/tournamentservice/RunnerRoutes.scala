package chess.tournamentservice

import cats.effect.IO
import fs2.Stream
import org.http4s.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

// Security boundary: POST /api/runner/... is an INTERNAL-ONLY route.
// Envoy does not forward /api/runner/* paths to tournament-service from the public edge,
// so this endpoint is not reachable externally in the current deployment.
// If tournament-service is ever exposed publicly, this route MUST require
// service-to-service authentication before any external caller can invoke it.
final class RunnerRoutes(runner: Option[PublicTournamentBotRunner]):

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {

    case POST -> Root / "api" / "runner" / "tournament" / tournamentId / "tick" =>
      runner match
        case None =>
          json(Status.ServiceUnavailable, ujson.Obj(
            "code"    -> "RUNNER_NOT_CONFIGURED",
            "message" -> "Tournament runner is not configured (missing TOURNAMENT_SERVER_BASE_URL, TOURNAMENT_USER_SERVICE_BASE_URL, or TOURNAMENT_GATEWAY_INTERNAL_BASE_URL)"
          ))
        case Some(r) =>
          r.tick(tournamentId).flatMap { result =>
            json(Status.Ok, tickResultJson(result))
          }
  }

  private def tickResultJson(r: PublicTournamentRunnerTickResult): ujson.Value =
    ujson.Obj(
      "tournamentId" -> r.tournamentId,
      "round"        -> r.round,
      "gamesFound"   -> r.gamesFound,
      "actions"      -> ujson.Arr(r.actions.map(actionJson)*)
    )

  private def actionJson(a: RunnerAction): ujson.Value =
    ujson.Obj(
      "gameId"                -> a.gameId,
      "tournamentServerBotId" -> a.tournamentServerBotId,
      "uciMove"               -> a.uciMove.map(ujson.Str(_)).getOrElse(ujson.Null),
      "status"                -> (a.status match
        case RunnerActionStatus.Submitted => "submitted"
        case RunnerActionStatus.Skipped   => "skipped"
        case RunnerActionStatus.Failed    => "failed"
      ),
      "reason" -> a.reason.map(ujson.Str(_)).getOrElse(ujson.Null)
    )

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](
        status  = status,
        headers = Headers(`Content-Type`(MediaType.application.json)),
        body    = Stream.emits(ujson.write(body).getBytes("UTF-8")).covary[IO]
      )
    )
