package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.*
import chess.adapter.repository.InMemoryBotChallengeSessionRepository
import chess.application.bot.BotChallengeColor
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

import java.util.UUID

class Http4sBotChallengeRoutesSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000301")

  private def run(app: HttpApp[IO], req: Request[IO]): Response[IO] =
    app.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def app(
      user: Either[AuthenticatedUserClientError, AuthenticatedSearchessUser],
      bot: RecordingBotChallengeClient = RecordingBotChallengeClient()
  ): (HttpApp[IO], InMemoryBotChallengeSessionRepository, RecordingBotChallengeClient) =
    val repo = InMemoryBotChallengeSessionRepository()
    val routes = Http4sBotChallengeRoutes(repo, Some(StaticUserClient(user)), Some(bot)).routes.orNotFound
    (routes, repo, bot)

  private def request(body: String = """{"clockLimitSeconds":300,"clockIncrementSeconds":3,"color":"random","rated":false}"""): Request[IO] =
    Request[IO](Method.POST, uri"/bot/challenges")
      .putHeaders(Header.Raw(CIString("Authorization"), "Bearer token"))
      .withEntity(body)

  private def validUser(lichessUsername: String = "linked_player"): AuthenticatedSearchessUser =
    AuthenticatedSearchessUser(
      userId = ownerId,
      nickname = Some("searchessNick"),
      onboardingRequired = false,
      links = List(
        AuthenticatedExternalAccountLink(
          provider = "Lichess",
          externalId = Some("lichess-id"),
          externalUsername = lichessUsername,
          verified = true,
          verificationSource = "OAuth"
        )
      )
    )

  "POST /bot/challenges" should "reject missing auth" in {
    val (http, _, _) = app(Right(validUser()))

    val resp = run(http, Request[IO](Method.POST, uri"/bot/challenges").withEntity("{}"))

    resp.status shouldBe Status.Unauthorized
  }

  it should "reject onboarding-required users" in {
    val (http, _, _) = app(Right(validUser().copy(onboardingRequired = true)))

    val resp = run(http, request())

    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "ONBOARDING_REQUIRED"
  }

  it should "reject users without a verified Lichess link" in {
    val user = validUser().copy(links = Nil)
    val (http, _, _) = app(Right(user))

    val resp = run(http, request())

    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "LICHESS_LINK_REQUIRED"
  }

  it should "reject rated challenge requests" in {
    val (http, _, _) = app(Right(validUser()))

    val resp = run(http, request("""{"clockLimitSeconds":300,"clockIncrementSeconds":3,"color":"white","rated":true}"""))

    resp.status shouldBe Status.UnprocessableEntity
  }

  it should "create a challenge session and call lichess-bot with the verified Lichess username" in {
    val bot = RecordingBotChallengeClient()
    val (http, repo, _) = app(Right(validUser("verified_name")), bot)

    val resp = run(
      http,
      request("""{"clockLimitSeconds":600,"clockIncrementSeconds":5,"color":"black","rated":false,"lichessUsername":"attacker"}""")
    )
    val json = bodyJson(resp)

    resp.status shouldBe Status.Created
    json("status").str shouldBe "Sent"
    json("lichessUsername").str shouldBe "verified_name"
    bot.lastRequest.value.lichessUsername shouldBe "verified_name"
    bot.lastRequest.value.color shouldBe BotChallengeColor.Black
    repo.findById(UUID.fromString(json("id").str)).toOption.flatten.value.status.toString shouldBe "Sent"
  }

  it should "mark the challenge session Failed when lichess-bot fails" in {
    val bot = RecordingBotChallengeClient(result = Left(LichessBotChallengeClientError.Unavailable("bot down")))
    val (http, repo, _) = app(Right(validUser()), bot)

    val resp = run(http, request())

    resp.status shouldBe Status.ServiceUnavailable
    val stored = repo.all.head
    stored.status.toString shouldBe "Failed"
    stored.failureReason.value should include("bot down")
  }

private final class StaticUserClient(
    user: Either[AuthenticatedUserClientError, AuthenticatedSearchessUser]
) extends AuthenticatedUserClient:
  override def getCurrentUser(authHeader: String): Either[AuthenticatedUserClientError, AuthenticatedSearchessUser] =
    user

private final class RecordingBotChallengeClient(
    result: Either[LichessBotChallengeClientError, LichessBotChallengeResponse] =
      Right(LichessBotChallengeResponse("challenge-123", Some("https://lichess.org/challenge-123"), "created"))
) extends LichessBotChallengeClient:
  var lastRequest: Option[LichessBotChallengeRequest] = None

  override def createChallenge(
      request: LichessBotChallengeRequest
  ): Either[LichessBotChallengeClientError, LichessBotChallengeResponse] =
    lastRequest = Some(request)
    result
