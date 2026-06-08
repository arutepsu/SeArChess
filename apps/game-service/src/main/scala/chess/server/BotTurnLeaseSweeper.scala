package chess.server

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import chess.application.bot.BotTurnTaskRepository
import chess.observability.StructuredLog

import java.time.Instant
import scala.concurrent.duration.DurationInt

/** Periodically re-queues bot turn tasks whose leases expired after a worker crash. */
object BotTurnLeaseSweeper:

  def resource(
      repository: Option[BotTurnTaskRepository],
      intervalSeconds: Int,
      maxAttempts: Int
  ): Resource[IO, Unit] =
    repository match
      case None =>
        Resource.eval(IO(StructuredLog.info("game-service", "bot_turn_lease_sweeper_disabled"))).void
      case Some(repo) =>
        Resource.make {
          IO(
            StructuredLog.info(
              "game-service",
              "bot_turn_lease_sweeper_started",
              "intervalSeconds" -> intervalSeconds,
              "maxAttempts" -> maxAttempts
            )
          ) *> loop(repo, intervalSeconds, maxAttempts).start
        } { fiber =>
          fiber.cancel *> IO(StructuredLog.info("game-service", "bot_turn_lease_sweeper_stopped"))
        }.void

  private def loop(repository: BotTurnTaskRepository, intervalSeconds: Int, maxAttempts: Int): IO[Unit] =
    IO.sleep(intervalSeconds.seconds) *> sweepOnce(repository, Instant.now(), maxAttempts) *>
      loop(repository, intervalSeconds, maxAttempts)

  private[server] def sweepOnce(
      repository: BotTurnTaskRepository,
      now: Instant,
      maxAttempts: Int
  ): IO[(Int, Int)] =
    IO(repository.releaseExpiredLeases(now, maxAttempts)).flatMap {
      case Right(result) =>
        if result.released > 0 || result.failed > 0 then
          IO(
            StructuredLog.info(
              "game-service",
              "bot_turn_expired_leases_swept",
              "released" -> result.released,
              "failed" -> result.failed,
              "maxAttempts" -> maxAttempts,
              "now" -> now
            )
          ).as((result.released, result.failed))
        else IO.pure((0, 0))
      case Left(err) =>
        IO(
          StructuredLog.error(
            "game-service",
            "bot_turn_lease_sweep_failed",
            "error" -> err.toString,
            "now" -> now
          )
        ).as((0, 0))
    }
