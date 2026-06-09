package chess.lichessbridge

import cats.effect.{Fiber, IO, Ref}
import chess.observability.StructuredLog

/** Manages one cats-effect Fiber per active Lichess game.
  *
  * Each fiber runs a GameLoopHandler stream that consumes the Lichess per-game NDJSON stream,
  * detects bot turns, calls the AI service, and submits moves.
  *
  * Invariants:
  *   - At most one fiber per gameId (startGame is a no-op for duplicate gameIds).
  *   - stopGame cancels the fiber and removes it from the registry.
  *   - cancelAll cancels all fibers; called during service shutdown.
  */
final class GameFiberManager(
    stateRef: Ref[IO, WorkerState],
    fiberRef: Ref[IO, Map[String, Fiber[IO, Throwable, Unit]]],
    token: String,
    botUsername: Option[String],
    gameStream: LichessGameStream[IO],
    aiClient: AiServiceClient[IO],
    lichessClient: LichessClient[IO]
):

  def startGame(game: LichessGameRef): IO[Unit] =
    fiberRef.get.flatMap { fibers =>
      if fibers.contains(game.gameId) then
        IO.delay(StructuredLog.warn("lichess-bridge-service", "game_fiber_duplicate",
          "gameId" -> game.gameId))
      else
        GameLoopHandler(token, game, botUsername, gameStream, aiClient, lichessClient, stateRef)
          .run
          .start
          .flatMap(fiber => fiberRef.update(_ + (game.gameId -> fiber)))
    }

  def stopGame(gameId: String): IO[Unit] =
    fiberRef.modify { fibers =>
      fibers.get(gameId) match
        case Some(fiber) => (fibers - gameId, fiber.cancel)
        case None        => (fibers, IO.unit)
    }.flatten >>
    IO.delay(StructuredLog.info("lichess-bridge-service", "game_fiber_stopped",
      "gameId" -> gameId))

  def cancelAll(): IO[Unit] =
    fiberRef.modify { fibers =>
      val cancelAllFibers = fibers.values.foldLeft(IO.unit) { (acc, f) => acc >> f.cancel }
      (Map.empty[String, Fiber[IO, Throwable, Unit]], cancelAllFibers)
    }.flatten >>
    IO.delay(StructuredLog.info("lichess-bridge-service", "all_game_fibers_cancelled"))

object GameFiberManager:
  def create(
      stateRef: Ref[IO, WorkerState],
      token: String,
      botUsername: Option[String],
      gameStream: LichessGameStream[IO],
      aiClient: AiServiceClient[IO],
      lichessClient: LichessClient[IO]
  ): IO[GameFiberManager] =
    Ref.of[IO, Map[String, Fiber[IO, Throwable, Unit]]](Map.empty).map { fiberRef =>
      new GameFiberManager(stateRef, fiberRef, token, botUsername, gameStream, aiClient, lichessClient)
    }
