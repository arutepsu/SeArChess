package chess.lichessbridge

import cats.effect.{Fiber, IO, Ref}
import cats.effect.kernel.Resource
import chess.observability.StructuredLog
import fs2.Stream

import java.time.Instant
import scala.concurrent.duration.*
import scala.util.control.NonFatal

/** Background worker that manages the Lichess bot event stream, challenge handling, and per-game loops.
  *
  * Phase 2B-1 behaviour:
  *   - Validates the token once at startup; aborts gracefully if invalid.
  *   - Opens a persistent NDJSON stream to /api/stream/event.
  *   - On ChallengeCreated: evaluates DefaultChallengePolicy, then accepts or declines.
  *   - On GameStart/GameFinish: updates the WorkerState active-game map.
  *   - Reconnects with exponential backoff on transient errors (max 30 s).
  *   - Auth errors are fatal — the stream is not restarted.
  *
  * Phase 2B-2 additions:
  *   - On GameStart: calls GameFiberManager.startGame to launch per-game stream consumer.
  *   - On GameFinish: calls GameFiberManager.stopGame before removing game from state.
  *   - On shutdown: GameFiberManager.cancelAll() is called after the event-stream fiber is cancelled.
  */
object LichessBridgeWorker:

  private val InitialRetryDelay = 1.second
  private val MaxRetryDelay     = 30.seconds

  /** Returns a cats-effect Resource whose value is a join handle (IO[Unit]).
    * The join completes when the background stream terminates.
    * The Resource release cancels all fibers (event-stream + all game loops).
    * If the bridge is disabled or no token is configured, returns a no-op resource immediately.
    */
  def resource(
      config: LichessBridgeConfig,
      lichessClient: LichessClient[IO],
      eventStream: LichessEventStream[IO],
      policy: ChallengePolicy[IO],
      gameFiberMgr: GameFiberManager,
      stateRef: Ref[IO, WorkerState]
  ): Resource[IO, IO[Unit]] =
    if !config.enabled then
      Resource.eval(
        IO.delay(StructuredLog.info("lichess-bridge-service", "worker_disabled"))
          .as(IO.unit)
      )
    else
      config.lichessBotToken match
        case None =>
          Resource.eval(
            IO.delay(StructuredLog.warn("lichess-bridge-service", "worker_no_token",
              "hint" -> "Set LICHESS_BOT_TOKEN to enable the worker"))
              .as(IO.unit)
          )
        case Some(token) =>
          startWorker(config, token, lichessClient, eventStream, policy, gameFiberMgr, stateRef)

  // ── Private helpers ─────────────────────────────────────────────────────────

  private def startWorker(
      config: LichessBridgeConfig,
      token: String,
      lichessClient: LichessClient[IO],
      eventStream: LichessEventStream[IO],
      policy: ChallengePolicy[IO],
      gameFiberMgr: GameFiberManager,
      stateRef: Ref[IO, WorkerState]
  ): Resource[IO, IO[Unit]] =
    Resource
      .make(acquireWorker(config, token, lichessClient, eventStream, policy, gameFiberMgr, stateRef))(
        fiber =>
          IO.delay(StructuredLog.info("lichess-bridge-service", "worker_stopping")) >>
          fiber.cancel >>
          gameFiberMgr.cancelAll() >>
          stateRef.update(_.stopped) >>
          IO.delay(StructuredLog.info("lichess-bridge-service", "worker_stopped"))
      )
      .map(fiber => fiber.joinWithNever.void)

  private def acquireWorker(
      config: LichessBridgeConfig,
      token: String,
      lichessClient: LichessClient[IO],
      eventStream: LichessEventStream[IO],
      policy: ChallengePolicy[IO],
      gameFiberMgr: GameFiberManager,
      stateRef: Ref[IO, WorkerState]
  ): IO[Fiber[IO, Throwable, Unit]] =
    IO.delay(StructuredLog.info("lichess-bridge-service", "worker_validating_token")) >>
    lichessClient.validateToken(token).flatMap {
      case Left(err) =>
        IO.delay(StructuredLog.error("lichess-bridge-service", "worker_token_invalid", "error" -> err.toString)) >>
        stateRef.update(_.withError(s"Token validation failed: $err")) >>
        IO.unit.start
      case Right(_) =>
        IO.delay(StructuredLog.info("lichess-bridge-service", "worker_token_valid")) >>
        stateRef.update(_.started) >>
        eventStreamLoop(config, token, lichessClient, eventStream, policy, gameFiberMgr, stateRef, InitialRetryDelay)
          .compile
          .drain
          .start
    }

  private def eventStreamLoop(
      config: LichessBridgeConfig,
      token: String,
      lichessClient: LichessClient[IO],
      eventStream: LichessEventStream[IO],
      policy: ChallengePolicy[IO],
      gameFiberMgr: GameFiberManager,
      stateRef: Ref[IO, WorkerState],
      retryDelay: FiniteDuration
  ): Stream[IO, Unit] =
    eventStream
      .streamBotEvents(token)
      .evalMap { result =>
        stateRef.update(_.withEvent(Instant.now())) >>
        (result match
          case Left(err) =>
            IO.delay(StructuredLog.warn("lichess-bridge-service", "event_parse_error", "error" -> err.message))
          case Right(event) =>
            handleBotEvent(config, token, lichessClient, policy, gameFiberMgr, stateRef, event)
        )
      }
      .handleErrorWith {
        case _: LichessAuthException =>
          Stream.eval(
            stateRef.update(_.withError("Fatal: Lichess auth error. Check LICHESS_BOT_TOKEN.")) >>
            IO.delay(StructuredLog.error("lichess-bridge-service", "stream_auth_fatal"))
          )

        case e: LichessRateLimitException =>
          val delay = e.retryAfterSeconds.fold(retryDelay)(s => s.seconds).min(MaxRetryDelay)
          Stream.eval(
            stateRef.update(_.withError(s"Rate limited, retrying in ${delay.toSeconds}s")) >>
            IO.delay(StructuredLog.warn("lichess-bridge-service", "stream_rate_limited",
              "delaySeconds" -> delay.toSeconds))
          ) >>
          Stream.sleep[IO](delay) >>
          eventStreamLoop(config, token, lichessClient, eventStream, policy, gameFiberMgr, stateRef, delay)

        case NonFatal(e) =>
          val nextDelay = (retryDelay * 2).min(MaxRetryDelay)
          Stream.eval(
            stateRef.update(_.withError(e.getMessage.take(100))) >>
            IO.delay(StructuredLog.warn("lichess-bridge-service", "stream_disconnect",
              "error"      -> e.getMessage,
              "retryInSec" -> retryDelay.toSeconds))
          ) >>
          Stream.sleep[IO](retryDelay) >>
          eventStreamLoop(config, token, lichessClient, eventStream, policy, gameFiberMgr, stateRef, nextDelay)
      }

  private def handleBotEvent(
      config: LichessBridgeConfig,
      token: String,
      lichessClient: LichessClient[IO],
      policy: ChallengePolicy[IO],
      gameFiberMgr: GameFiberManager,
      stateRef: Ref[IO, WorkerState],
      event: LichessBotEvent
  ): IO[Unit] =
    event match
      case LichessBotEvent.ChallengeCreated(challenge) =>
        policy.evaluate(challenge).flatMap {
          case ChallengeDecision.Accept =>
            lichessClient.acceptChallenge(token, challenge.challengeId).flatMap {
              case Right(_) =>
                IO.delay(StructuredLog.info("lichess-bridge-service", "challenge_accepted",
                  "challengeId" -> challenge.challengeId,
                  "challenger"  -> challenge.challengerUsername))
              case Left(err) =>
                IO.delay(StructuredLog.warn("lichess-bridge-service", "challenge_accept_failed",
                  "challengeId" -> challenge.challengeId,
                  "error"       -> err.toString))
            }
          case ChallengeDecision.Decline(reason) =>
            val reasonStr = DeclineReason.toLichessString(reason)
            lichessClient.declineChallenge(token, challenge.challengeId, reasonStr).flatMap {
              case Right(_) =>
                IO.delay(StructuredLog.info("lichess-bridge-service", "challenge_declined",
                  "challengeId" -> challenge.challengeId,
                  "reason"      -> reasonStr))
              case Left(err) =>
                IO.delay(StructuredLog.warn("lichess-bridge-service", "challenge_decline_failed",
                  "challengeId" -> challenge.challengeId,
                  "error"       -> err.toString))
            }
        }

      case LichessBotEvent.GameStart(game) =>
        stateRef.update(_.addGame(ActiveGame(game.gameId, game.opponent, game.side, Instant.now()))) >>
        gameFiberMgr.startGame(game) >>
        IO.delay(StructuredLog.info("lichess-bridge-service", "game_started",
          "gameId"   -> game.gameId,
          "opponent" -> game.opponent,
          "side"     -> game.side.getOrElse("unknown")))

      case LichessBotEvent.GameFinish(game) =>
        gameFiberMgr.stopGame(game.gameId) >>
        stateRef.update(_.removeGame(game.gameId)) >>
        IO.delay(StructuredLog.info("lichess-bridge-service", "game_finished",
          "gameId" -> game.gameId))

      case LichessBotEvent.ChallengeCanceled(id) =>
        IO.delay(StructuredLog.info("lichess-bridge-service", "challenge_canceled",
          "challengeId" -> id))

      case LichessBotEvent.Unknown(t, _) =>
        IO.delay(StructuredLog.info("lichess-bridge-service", "unknown_event", "type" -> t))
