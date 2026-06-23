package chess.tournamentservice

import cats.effect.IO
import chess.observability.StructuredLog

import scala.concurrent.duration.*

// Periodic scheduler that discovers started public tournaments and calls
// PublicTournamentBotRunner.tick() for each within a bounded limit.
//
// Lifecycle mirrors OutboxPoller: (runCycle().attempt >> IO.sleep(interval)).foreverM
// - .attempt prevents a cycle failure from crashing the fiber
// - Sequential composition guarantees no two cycles overlap
// - Cancellation is handled by the cats-effect fiber (TournamentServiceRuntime.shutdown)
//
// This is the ONLY place that drives periodic tick calls.
// The single-shot RunnerRoutes endpoint remains for manual/debug use.
//
// No chess logic lives here. No bot JWTs are produced or consumed here.
final class PublicTournamentRunnerScheduler(
    runner:         TournamentTickRunner,
    discovery:      PublicTournamentDiscoveryClient,
    intervalSecs:   Int,
    maxTournaments: Int
):

  def start(): IO[Nothing] =
    (runCycle().attempt >> IO.sleep(intervalSecs.seconds)).foreverM

  private[tournamentservice] def runCycle(): IO[Unit] =
    for
      _          <- IO(StructuredLog.info("tournament-service", "runner_scheduler_cycle_start"))
      discovered <- discovery.listRunnableTournamentIds()
      _          <- discovered match
        case Left(err) =>
          IO(StructuredLog.warn(
            "tournament-service", "runner_scheduler_discovery_failed",
            "error" -> err
          ))
        case Right(ids) =>
          val bounded = ids.take(maxTournaments)
          IO(StructuredLog.info(
            "tournament-service", "runner_scheduler_discovered",
            "total"   -> ids.size,
            "bounded" -> bounded.size
          )) >> tickAll(bounded)
    yield ()

  private def tickAll(ids: List[String]): IO[Unit] =
    ids.foldLeft(IO.unit)((acc, id) => acc >> tickOne(id))

  private def tickOne(tournamentId: String): IO[Unit] =
    runner.tick(tournamentId).attempt.flatMap {
      case Left(err) =>
        IO(StructuredLog.error(
          "tournament-service", "runner_scheduler_tick_failed",
          "tournamentId" -> tournamentId,
          "error"        -> Option(err.getMessage).getOrElse("unknown")
        ))
      case Right(result) =>
        val submitted = result.actions.count(_.status == RunnerActionStatus.Submitted)
        val skipped   = result.actions.count(_.status == RunnerActionStatus.Skipped)
        val failed    = result.actions.count(_.status == RunnerActionStatus.Failed)
        IO(StructuredLog.info(
          "tournament-service", "runner_scheduler_tick_done",
          "tournamentId" -> tournamentId,
          "round"        -> result.round,
          "gamesFound"   -> result.gamesFound,
          "submitted"    -> submitted,
          "skipped"      -> skipped,
          "failed"       -> failed
        ))
    }
