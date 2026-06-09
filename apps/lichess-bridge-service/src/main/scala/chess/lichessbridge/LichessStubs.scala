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

/** Stub LichessClient with configurable accept/decline results. Used in worker tests. */
class ControllableLichessClient(
    validateResult: Either[LichessError, Boolean] = Right(true),
    acceptResult: Either[LichessError, Unit] = Right(()),
    declineResult: Either[LichessError, Unit] = Right(())
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
