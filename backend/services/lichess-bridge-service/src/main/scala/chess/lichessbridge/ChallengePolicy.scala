package chess.lichessbridge

import cats.effect.IO
import cats.effect.kernel.Ref

// ── Decision types ─────────────────────────────────────────────────────────────

enum ChallengeDecision:
  case Accept
  case Decline(reason: DeclineReason)

enum DeclineReason:
  case BridgeDisabled
  case ChallengesDisabled
  case MaxGamesReached
  case RatedNotAllowed
  case VariantNotAllowed(variant: String)
  case ClockOutOfRange(limitSeconds: Option[Int])
  case ChallengerNotAllowed(username: String)
  case LinkedUserRequired(reason: String)

object DeclineReason:
  // Maps to Lichess's decline reason API values.
  // See https://lichess.org/api#tag/Challenges/operation/challengeDecline
  def toLichessString(reason: DeclineReason): String = reason match
    case BridgeDisabled            => "generic"
    case ChallengesDisabled        => "generic"
    case MaxGamesReached           => "later"
    case RatedNotAllowed           => "casual"
    case VariantNotAllowed(_)      => "standard"
    case ClockOutOfRange(Some(s)) if s < 180 => "tooFast"
    case ClockOutOfRange(_)        => "tooSlow"
    case ChallengerNotAllowed(_)   => "generic"
    case LinkedUserRequired(_)     => "generic"

// ── Policy interface ───────────────────────────────────────────────────────────

trait ChallengePolicy[F[_]]:
  def evaluate(challenge: LichessChallenge): F[ChallengeDecision]

// ── Default conservative policy ───────────────────────────────────────────────

final class DefaultChallengePolicy(
    config: LichessBridgeConfig,
    stateRef: Ref[IO, WorkerState],
    userServiceClient: UserServiceClient[IO]
) extends ChallengePolicy[IO]:

  def evaluate(challenge: LichessChallenge): IO[ChallengeDecision] =
    if !config.enabled then
      IO.pure(ChallengeDecision.Decline(DeclineReason.BridgeDisabled))
    else if !config.acceptChallenges then
      IO.pure(ChallengeDecision.Decline(DeclineReason.ChallengesDisabled))
    else
      stateRef.get.flatMap { state =>
        evaluatePure(challenge, state.activeGames.size) match
          case ChallengeDecision.Accept if config.requireLinkedChallenger =>
            userServiceClient.authorizeChallenger(challenge.challengerUsername).map {
              case ChallengeAuthResult.Authorized  => ChallengeDecision.Accept
              case ChallengeAuthResult.NotLinked   =>
                ChallengeDecision.Decline(DeclineReason.LinkedUserRequired("not_linked"))
              case ChallengeAuthResult.Unavailable =>
                ChallengeDecision.Decline(DeclineReason.LinkedUserRequired("user_service_unavailable"))
            }
          case decision => IO.pure(decision)
      }

  private def evaluatePure(challenge: LichessChallenge, activeCount: Int): ChallengeDecision =
    if activeCount >= config.maxConcurrentGames then
      ChallengeDecision.Decline(DeclineReason.MaxGamesReached)
    else if challenge.rated && !config.acceptRated then
      ChallengeDecision.Decline(DeclineReason.RatedNotAllowed)
    else if !config.allowedVariants.contains(challenge.variant) then
      ChallengeDecision.Decline(DeclineReason.VariantNotAllowed(challenge.variant))
    else if !clockInRange(challenge.timeControl) then
      ChallengeDecision.Decline(DeclineReason.ClockOutOfRange(challenge.timeControl.limit))
    else if !config.requireLinkedChallenger &&
            config.allowedChallengers.nonEmpty &&
            !config.allowedChallengers.contains(challenge.challengerUsername.toLowerCase) then
      ChallengeDecision.Decline(DeclineReason.ChallengerNotAllowed(challenge.challengerUsername))
    else
      ChallengeDecision.Accept

  private def clockInRange(tc: LichessTimeControl): Boolean =
    tc.limit match
      case None        => false
      case Some(limit) => limit >= config.minClockSeconds && limit <= config.maxClockSeconds
