package chess.lichessbot

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString

class LichessChallengeRoutesSpec extends AnyFlatSpec with Matchers with OptionValues:

  private def run(client: RecordingChallengeClient, req: Request[IO]): Response[IO] =
    LichessChallengeRoutes("secret", client).routes.orNotFound.run(req).unsafeRunSync()

  private def request(body: String): Request[IO] =
    Request[IO](Method.POST, uri"/challenge")
      .putHeaders(Header.Raw(CIString("X-Bot-Api-Key"), "secret"))
      .withEntity(body)

  "POST /challenge" should "reject missing X-Bot-Api-Key" in {
    val resp = LichessChallengeRoutes("secret", RecordingChallengeClient()).routes.orNotFound
      .run(Request[IO](Method.POST, uri"/challenge").withEntity("{}"))
      .unsafeRunSync()

    resp.status shouldBe Status.Unauthorized
  }

  it should "reject wrong X-Bot-Api-Key" in {
    val resp = LichessChallengeRoutes("secret", RecordingChallengeClient()).routes.orNotFound
      .run(
        Request[IO](Method.POST, uri"/challenge")
          .putHeaders(Header.Raw(CIString("X-Bot-Api-Key"), "wrong"))
          .withEntity("{}")
      )
      .unsafeRunSync()

    resp.status shouldBe Status.Unauthorized
  }

  it should "reject rated challenges" in {
    val resp = run(
      RecordingChallengeClient(),
      request("""{"lichessUsername":"alice","clockLimitSeconds":300,"clockIncrementSeconds":3,"color":"random","rated":true}""")
    )

    resp.status shouldBe Status.UnprocessableEntity
  }

  it should "pass valid standard challenge commands to the Lichess client" in {
    val client = RecordingChallengeClient()
    val resp = run(
      client,
      request("""{"lichessUsername":"alice","clockLimitSeconds":600,"clockIncrementSeconds":5,"color":"white","rated":false,"variant":"standard"}""")
    )
    val body = ujson.read(resp.bodyText.compile.string.unsafeRunSync())

    resp.status shouldBe Status.Created
    body("challengeId").str shouldBe "abc123"
    client.last.value shouldBe ChallengeCommand("alice", 600, 5, "white", rated = false, "standard")
  }

  "HttpLichessChallengeClient" should "build the official Lichess form fields" in {
    val client = HttpLichessChallengeClient("token")
    val body = client.formBody(ChallengeCommand("alice", 300, 3, "random", rated = false))

    body should include("rated=false")
    body should include("clock.limit=300")
    body should include("clock.increment=3")
    body should include("color=random")
    body should include("variant=standard")
  }

private final class RecordingChallengeClient(
    result: Either[LichessChallengeError, ChallengeCreated] =
      Right(ChallengeCreated("abc123", Some("https://lichess.org/abc123"), "created"))
) extends LichessChallengeClient:
  var last: Option[ChallengeCommand] = None

  override def create(command: ChallengeCommand): Either[LichessChallengeError, ChallengeCreated] =
    last = Some(command)
    result
