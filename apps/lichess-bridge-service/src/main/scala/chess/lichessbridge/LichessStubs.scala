package chess.lichessbridge

import cats.effect.IO
import cats.effect.kernel.Ref
import chess.lichessbridge.LichessError.*
import fs2.Stream

// ── LichessClient stubs ────────────────────────────────────────────────────────

/** Stub LichessClient that returns NetworkError from all methods. Used in tests that don't need Lichess. */
final class StubLichessClient extends LichessClient[IO]:
  def getBotProfile(token: String): IO[Either[LichessError, BotProfile]] =
    IO.pure(Left(NetworkError("StubLichessClient: not implemented")))
  def validateToken(token: String): IO[Either[LichessError, Boolean]] =
    IO.pure(Left(NetworkError("StubLichessClient: not implemented")))
  def challengeAi(token: String, level: Int, clockLimit: Int, clockIncrement: Int): IO[Either[LichessError, ChallengeResult]] =
    IO.pure(Left(NetworkError("StubLichessClient: not implemented")))
  def acceptChallenge(token: String, challengeId: String): IO[Either[LichessError, Unit]] =
    IO.pure(Left(NetworkError("StubLichessClient: not implemented")))
  def declineChallenge(token: String, challengeId: String, reason: String): IO[Either[LichessError, Unit]] =
    IO.pure(Left(NetworkError("StubLichessClient: not implemented")))
  def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
    IO.pure(Left(NetworkError("StubLichessClient: not implemented")))
  def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
    IO.pure(Left(NetworkError("StubLichessClient: not implemented")))

/** Stub LichessClient with configurable results. Used in worker and route tests. */
class ControllableLichessClient(
    validateResult: Either[LichessError, Boolean] = Right(true),
    acceptResult: Either[LichessError, Unit] = Right(()),
    declineResult: Either[LichessError, Unit] = Right(()),
    submitMoveResult: Either[LichessError, Unit] = Right(()),
    sendChatResult: Either[LichessError, Unit] = Right(())
) extends LichessClient[IO]:
  def getBotProfile(token: String): IO[Either[LichessError, BotProfile]] =
    IO.pure(Left(NetworkError("not used")))
  def validateToken(token: String): IO[Either[LichessError, Boolean]] =
    IO.pure(validateResult)
  def challengeAi(token: String, level: Int, clockLimit: Int, clockIncrement: Int): IO[Either[LichessError, ChallengeResult]] =
    IO.pure(Left(NetworkError("not used")))
  def acceptChallenge(token: String, challengeId: String): IO[Either[LichessError, Unit]] =
    IO.pure(acceptResult)
  def declineChallenge(token: String, challengeId: String, reason: String): IO[Either[LichessError, Unit]] =
    IO.pure(declineResult)
  def submitMove(token: String, gameId: String, move: String): IO[Either[LichessError, Unit]] =
    IO.pure(submitMoveResult)
  def sendChatMessage(token: String, gameId: String, text: String): IO[Either[LichessError, Unit]] =
    IO.pure(sendChatResult)

// ── LichessEventStream stubs ──────────────────────────────────────────────────

/** Emits a fixed list of events then terminates cleanly. */
final class StubLichessEventStream(
    events: List[Either[ParseError, LichessBotEvent]] = Nil
) extends LichessEventStream[IO]:
  def streamBotEvents(token: String): Stream[IO, Either[ParseError, LichessBotEvent]] =
    Stream.emits(events)

/** Emits events then raises the given error (for disconnect/retry testing). */
final class DisconnectingLichessEventStream(
    events: List[Either[ParseError, LichessBotEvent]],
    error: Throwable
) extends LichessEventStream[IO]:
  def streamBotEvents(token: String): Stream[IO, Either[ParseError, LichessBotEvent]] =
    Stream.emits(events) ++ Stream.raiseError[IO](error)

// ── LichessGameStream stubs ───────────────────────────────────────────────────

/** Emits a fixed list of game events then terminates cleanly. */
final class StubLichessGameStream(
    events: List[Either[ParseError, LichessGameEvent]] = Nil
) extends LichessGameStream[IO]:
  def streamGame(token: String, gameId: String): Stream[IO, Either[ParseError, LichessGameEvent]] =
    Stream.emits(events)

// ── AiServiceClient stubs ─────────────────────────────────────────────────────

/** Returns a fixed result for every suggestMove call. */
final class StubAiServiceClient(
    result: Either[AiError, UciMove] = Right(UciMove("e2e4"))
) extends AiServiceClient[IO]:
  def suggestMove(request: AiMoveRequest): IO[Either[AiError, UciMove]] =
    IO.pure(result)

/** Always raises an exception — simulates a crashed AI service. */
final class FailingAiServiceClient extends AiServiceClient[IO]:
  def suggestMove(request: AiMoveRequest): IO[Either[AiError, UciMove]] =
    IO.raiseError(RuntimeException("AI service unavailable"))

// ── UserServiceClient stubs ───────────────────────────────────────────────────

/** Always authorizes. */
final class AuthorizedUserServiceClient extends UserServiceClient[IO]:
  def authorizeChallenger(lichessUsername: String): IO[ChallengeAuthResult] =
    IO.pure(ChallengeAuthResult.Authorized)

/** Always returns NotLinked. */
final class NotLinkedUserServiceClient extends UserServiceClient[IO]:
  def authorizeChallenger(lichessUsername: String): IO[ChallengeAuthResult] =
    IO.pure(ChallengeAuthResult.NotLinked)

/** Always returns Unavailable (simulates user-service down). */
final class UnavailableUserServiceClient extends UserServiceClient[IO]:
  def authorizeChallenger(lichessUsername: String): IO[ChallengeAuthResult] =
    IO.pure(ChallengeAuthResult.Unavailable)

/** Returns a configurable result. */
final class ControllableUserServiceClient(result: ChallengeAuthResult) extends UserServiceClient[IO]:
  def authorizeChallenger(lichessUsername: String): IO[ChallengeAuthResult] =
    IO.pure(result)

// ── ChallengePolicy stubs ─────────────────────────────────────────────────────

/** Always accepts. */
final class AcceptAllChallengePolicy extends ChallengePolicy[IO]:
  def evaluate(challenge: LichessChallenge): IO[ChallengeDecision] =
    IO.pure(ChallengeDecision.Accept)

/** Always declines with a fixed reason. */
final class DeclineAllChallengePolicy(reason: DeclineReason = DeclineReason.ChallengesDisabled)
    extends ChallengePolicy[IO]:
  def evaluate(challenge: LichessChallenge): IO[ChallengeDecision] =
    IO.pure(ChallengeDecision.Decline(reason))
