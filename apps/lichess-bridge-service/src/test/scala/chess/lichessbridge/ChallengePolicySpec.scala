package chess.lichessbridge

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ChallengePolicySpec extends AnyFlatSpec with Matchers:

  // ── Fixtures ──────────────────────────────────────────────────────────────────

  private val baseConfig = LichessBridgeConfig(
    enabled                  = true,
    lichessApiBaseUrl        = "https://lichess.org",
    lichessBotUsername       = Some("testbot"),
    lichessBotToken          = Some("lip_test"),
    aiServiceUrl             = "http://ai-service:8765",
    maxConcurrentGames       = 2,
    host                     = "0.0.0.0",
    port                     = 8090,
    acceptChallenges         = true,
    allowedChallengers       = Set.empty,
    acceptRated              = false,
    allowedVariants          = Set("standard"),
    minClockSeconds          = 180,
    maxClockSeconds          = 600,
    requireLinkedChallenger  = false   // existing tests exercise static-allowlist path
  )

  private val standardCasualChallenge = LichessChallenge(
    challengeId        = "ch1",
    challengerUsername = "player1",
    variant            = "standard",
    speed              = "blitz",
    rated              = false,
    timeControl        = LichessTimeControl("clock", Some(300), Some(0)),
    color              = None
  )

  private def makePolicy(
      config: LichessBridgeConfig = baseConfig,
      activeGames: Map[String, ActiveGame] = Map.empty,
      userServiceClient: UserServiceClient[IO] = AuthorizedUserServiceClient()
  ): DefaultChallengePolicy =
    val stateRef = IO.ref(WorkerState(running = true, activeGames, None, None)).unsafeRunSync()
    DefaultChallengePolicy(config, stateRef, userServiceClient)

  private def eval(policy: ChallengePolicy[IO], challenge: LichessChallenge): ChallengeDecision =
    policy.evaluate(challenge).unsafeRunSync()

  // ── Accept cases ──────────────────────────────────────────────────────────────

  "DefaultChallengePolicy" should "accept a safe casual standard challenge" in {
    eval(makePolicy(), standardCasualChallenge) shouldBe ChallengeDecision.Accept
  }

  it should "accept when allowedChallengers is empty (accept all)" in {
    val policy = makePolicy(baseConfig.copy(allowedChallengers = Set.empty))
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Accept
  }

  it should "accept when challenger is in the allowlist" in {
    val policy = makePolicy(baseConfig.copy(allowedChallengers = Set("player1", "player2")))
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Accept
  }

  it should "accept a rated challenge when acceptRated=true" in {
    val policy    = makePolicy(baseConfig.copy(acceptRated = true))
    val challenge = standardCasualChallenge.copy(rated = true)
    eval(policy, challenge) shouldBe ChallengeDecision.Accept
  }

  // ── Decline: bridge/challenges disabled ──────────────────────────────────────

  it should "decline when bridge is disabled" in {
    val policy = makePolicy(baseConfig.copy(enabled = false))
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Decline(DeclineReason.BridgeDisabled)
  }

  it should "decline when LICHESS_ACCEPT_CHALLENGES=false" in {
    val policy = makePolicy(baseConfig.copy(acceptChallenges = false))
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Decline(DeclineReason.ChallengesDisabled)
  }

  // ── Decline: game count ───────────────────────────────────────────────────────

  it should "decline when max concurrent games reached" in {
    val active = Map(
      "g1" -> ActiveGame("g1", "opp1", Some("white"), Instant.now()),
      "g2" -> ActiveGame("g2", "opp2", Some("black"), Instant.now())
    )
    val policy = makePolicy(activeGames = active)  // maxConcurrentGames = 2
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Decline(DeclineReason.MaxGamesReached)
  }

  // ── Decline: rated ────────────────────────────────────────────────────────────

  it should "decline a rated challenge when acceptRated=false" in {
    val policy    = makePolicy(baseConfig.copy(acceptRated = false))
    val challenge = standardCasualChallenge.copy(rated = true)
    eval(policy, challenge) shouldBe ChallengeDecision.Decline(DeclineReason.RatedNotAllowed)
  }

  // ── Decline: variant ──────────────────────────────────────────────────────────

  it should "decline a chess960 challenge when only standard is allowed" in {
    val policy    = makePolicy(baseConfig.copy(allowedVariants = Set("standard")))
    val challenge = standardCasualChallenge.copy(variant = "chess960")
    eval(policy, challenge) shouldBe ChallengeDecision.Decline(DeclineReason.VariantNotAllowed("chess960"))
  }

  it should "accept a chess960 challenge when chess960 is in the allowed variants" in {
    val policy    = makePolicy(baseConfig.copy(allowedVariants = Set("standard", "chess960")))
    val challenge = standardCasualChallenge.copy(variant = "chess960")
    eval(policy, challenge) shouldBe ChallengeDecision.Accept
  }

  // ── Decline: clock ────────────────────────────────────────────────────────────

  it should "decline a bullet game (60s) below the minimum clock" in {
    val challenge = standardCasualChallenge.copy(
      timeControl = LichessTimeControl("clock", Some(60), Some(0))
    )
    eval(makePolicy(), challenge) match
      case ChallengeDecision.Decline(DeclineReason.ClockOutOfRange(Some(60))) => succeed
      case other                                                               => fail(s"Expected ClockOutOfRange, got $other")
  }

  it should "decline a classical game (3600s) above the maximum clock" in {
    val challenge = standardCasualChallenge.copy(
      timeControl = LichessTimeControl("clock", Some(3600), Some(0))
    )
    eval(makePolicy(), challenge) match
      case ChallengeDecision.Decline(DeclineReason.ClockOutOfRange(Some(3600))) => succeed
      case other                                                                 => fail(s"Expected ClockOutOfRange, got $other")
  }

  it should "decline a correspondence / unlimited game (no limit)" in {
    val challenge = standardCasualChallenge.copy(
      timeControl = LichessTimeControl("unlimited", None, None)
    )
    eval(makePolicy(), challenge) match
      case ChallengeDecision.Decline(DeclineReason.ClockOutOfRange(None)) => succeed
      case other                                                           => fail(s"Expected ClockOutOfRange, got $other")
  }

  it should "accept exactly at the minimum clock boundary" in {
    val challenge = standardCasualChallenge.copy(
      timeControl = LichessTimeControl("clock", Some(180), Some(0))
    )
    eval(makePolicy(), challenge) shouldBe ChallengeDecision.Accept
  }

  it should "accept exactly at the maximum clock boundary" in {
    val challenge = standardCasualChallenge.copy(
      timeControl = LichessTimeControl("clock", Some(600), Some(0))
    )
    eval(makePolicy(), challenge) shouldBe ChallengeDecision.Accept
  }

  // ── Decline: challenger allowlist ─────────────────────────────────────────────

  it should "decline a non-allowlisted challenger when allowlist is configured" in {
    val policy    = makePolicy(baseConfig.copy(allowedChallengers = Set("trusted1")))
    val challenge = standardCasualChallenge.copy(challengerUsername = "stranger")
    eval(policy, challenge) shouldBe ChallengeDecision.Decline(DeclineReason.ChallengerNotAllowed("stranger"))
  }

  it should "compare challenger names case-insensitively" in {
    val policy    = makePolicy(baseConfig.copy(allowedChallengers = Set("trusted1")))
    val challenge = standardCasualChallenge.copy(challengerUsername = "Trusted1")
    eval(policy, challenge) shouldBe ChallengeDecision.Accept
  }

  // ── DeclineReason.toLichessString ─────────────────────────────────────────────

  "DeclineReason.toLichessString" should "map BridgeDisabled to generic" in {
    DeclineReason.toLichessString(DeclineReason.BridgeDisabled) shouldBe "generic"
  }

  it should "map MaxGamesReached to later" in {
    DeclineReason.toLichessString(DeclineReason.MaxGamesReached) shouldBe "later"
  }

  it should "map RatedNotAllowed to casual" in {
    DeclineReason.toLichessString(DeclineReason.RatedNotAllowed) shouldBe "casual"
  }

  it should "map VariantNotAllowed to standard" in {
    DeclineReason.toLichessString(DeclineReason.VariantNotAllowed("chess960")) shouldBe "standard"
  }

  it should "map ClockOutOfRange(too slow) to tooSlow" in {
    DeclineReason.toLichessString(DeclineReason.ClockOutOfRange(Some(3600))) shouldBe "tooSlow"
  }

  it should "map ClockOutOfRange(too fast) to tooFast" in {
    DeclineReason.toLichessString(DeclineReason.ClockOutOfRange(Some(60))) shouldBe "tooFast"
  }

  it should "map LinkedUserRequired to generic" in {
    DeclineReason.toLichessString(DeclineReason.LinkedUserRequired("not_linked")) shouldBe "generic"
  }

  // ── Phase 3B: requireLinkedChallenger=true path ───────────────────────────────

  private val linkedConfig = baseConfig.copy(requireLinkedChallenger = true)

  "DefaultChallengePolicy with requireLinkedChallenger=true" should
    "accept a challenge from a linked user when all policy checks pass" in {
    val policy = makePolicy(linkedConfig, userServiceClient = AuthorizedUserServiceClient())
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Accept
  }

  it should "decline a challenge from an unlinked user (user-service returns NotLinked)" in {
    val policy = makePolicy(linkedConfig, userServiceClient = NotLinkedUserServiceClient())
    eval(policy, standardCasualChallenge) shouldBe
      ChallengeDecision.Decline(DeclineReason.LinkedUserRequired("not_linked"))
  }

  it should "decline a challenge when user-service is unavailable (fail closed)" in {
    val policy = makePolicy(linkedConfig, userServiceClient = UnavailableUserServiceClient())
    eval(policy, standardCasualChallenge) shouldBe
      ChallengeDecision.Decline(DeclineReason.LinkedUserRequired("user_service_unavailable"))
  }

  it should "still decline a rated challenge before calling user-service" in {
    val policy    = makePolicy(linkedConfig, userServiceClient = UnavailableUserServiceClient())
    val challenge = standardCasualChallenge.copy(rated = true)
    eval(policy, challenge) shouldBe ChallengeDecision.Decline(DeclineReason.RatedNotAllowed)
  }

  it should "still decline a wrong-variant challenge before calling user-service" in {
    val policy    = makePolicy(linkedConfig, userServiceClient = UnavailableUserServiceClient())
    val challenge = standardCasualChallenge.copy(variant = "chess960")
    eval(policy, challenge) shouldBe ChallengeDecision.Decline(DeclineReason.VariantNotAllowed("chess960"))
  }

  it should "still decline when max games reached before calling user-service" in {
    val active = Map(
      "g1" -> ActiveGame("g1", "opp1", Some("white"), java.time.Instant.now()),
      "g2" -> ActiveGame("g2", "opp2", Some("black"), java.time.Instant.now())
    )
    val policy = makePolicy(linkedConfig, activeGames = active, userServiceClient = UnavailableUserServiceClient())
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Decline(DeclineReason.MaxGamesReached)
  }

  it should "decline all challenges when LICHESS_ACCEPT_CHALLENGES=false regardless of user-service" in {
    val policy = makePolicy(linkedConfig.copy(acceptChallenges = false),
      userServiceClient = AuthorizedUserServiceClient())
    eval(policy, standardCasualChallenge) shouldBe ChallengeDecision.Decline(DeclineReason.ChallengesDisabled)
  }

  it should "not call user-service when LICHESS_ACCEPT_CHALLENGES=false" in {
    var called = false
    val trackingClient = new UserServiceClient[IO]:
      def authorizeChallenger(username: String): IO[ChallengeAuthResult] =
        IO { called = true } >> IO.pure(ChallengeAuthResult.Authorized)
    val policy = makePolicy(linkedConfig.copy(acceptChallenges = false), userServiceClient = trackingClient)
    eval(policy, standardCasualChallenge)
    called shouldBe false
  }

  // ── requireLinkedChallenger=false falls back to existing allowlist behavior ──

  it should "use static allowedChallengers list when requireLinkedChallenger=false" in {
    val config = baseConfig.copy(requireLinkedChallenger = false, allowedChallengers = Set("trusted1"))
    val policy = makePolicy(config, userServiceClient = UnavailableUserServiceClient())
    val allowed = standardCasualChallenge.copy(challengerUsername = "trusted1")
    val blocked = standardCasualChallenge.copy(challengerUsername = "stranger")
    eval(policy, allowed) shouldBe ChallengeDecision.Accept
    eval(policy, blocked) shouldBe ChallengeDecision.Decline(DeclineReason.ChallengerNotAllowed("stranger"))
  }
