package chess.userservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.userservice.application.ExternalAccountLinkRepository
import chess.userservice.domain.ExternalAccountLink
import org.http4s.*
import org.http4s.dsl.io.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

class InternalLichessRoutesSpec extends AnyFlatSpec with Matchers:

  private val testApiKey = "test-internal-key-abc123"

  private def makeApp(links: List[ExternalAccountLink] = Nil, apiKey: String = testApiKey): HttpApp[IO] =
    val repo   = InMemLinkRepo(links)
    val routes = InternalLichessRoutes(repo, apiKey)
    routes.routes.orNotFound

  private def apiKeyHeader: Header.Raw =
    Header.Raw(org.typelevel.ci.CIString("X-Internal-Api-Key"), testApiKey)

  private def getAuth(username: String, withApiKey: Boolean = true): Response[IO] =
    val base = Request[IO](method = Method.GET, uri = Uri.unsafeFromString(s"/internal/lichess/challenge-auth/$username"))
    val req  = if withApiKey then base.putHeaders(apiKeyHeader) else base
    makeApp(links = List(linkedUser("chess_player"), linkedUser("anotheruser"))).run(req).unsafeRunSync()

  private def linkedUser(username: String): ExternalAccountLink =
    ExternalAccountLink(
      linkId             = UUID.randomUUID(),
      userId             = UUID.randomUUID(),
      provider           = "Lichess",
      externalId         = Some(username),
      externalUsername   = username,
      verified           = true,
      verificationSource = "OAuthPKCE",
      linkedAt           = Instant.now()
    )

  // ── Allowed (linked user) ─────────────────────────────────────────────────────

  "GET /internal/lichess/challenge-auth/{username}" should "return 200 allowed=true for a linked user" in {
    val res  = getAuth("chess_player")
    val body = ujson.read(res.bodyText.compile.string.unsafeRunSync())
    res.status              shouldBe Status.Ok
    body("allowed").bool    shouldBe true
    body("reason").str      shouldBe "linked_user"
    body("lichessUsername").str shouldBe "chess_player"
    body.obj.contains("searchessUserId") shouldBe true
  }

  it should "be case-insensitive for the Lichess username lookup" in {
    val res  = getAuth("Chess_Player")
    val body = ujson.read(res.bodyText.compile.string.unsafeRunSync())
    res.status           shouldBe Status.Ok
    body("allowed").bool shouldBe true
  }

  // ── Denied (not linked) ───────────────────────────────────────────────────────

  it should "return 200 allowed=false for an unknown username" in {
    val res  = getAuth("random_stranger")
    val body = ujson.read(res.bodyText.compile.string.unsafeRunSync())
    res.status              shouldBe Status.Ok
    body("allowed").bool    shouldBe false
    body("reason").str      shouldBe "not_linked"
    body("lichessUsername").str shouldBe "random_stranger"
  }

  // ── Auth: missing header ──────────────────────────────────────────────────────

  it should "return 401 when X-Internal-Api-Key header is missing" in {
    val res = getAuth("anyone", withApiKey = false)
    res.status shouldBe Status.Unauthorized
  }

  // ── Auth: wrong key ───────────────────────────────────────────────────────────

  it should "return 401 when X-Internal-Api-Key is wrong" in {
    val wrongKey = Header.Raw(org.typelevel.ci.CIString("X-Internal-Api-Key"), "wrong-key")
    val req = Request[IO](method = Method.GET,
      uri = Uri.unsafeFromString("/internal/lichess/challenge-auth/anyone"))
      .putHeaders(wrongKey)
    val res = makeApp().run(req).unsafeRunSync()
    res.status shouldBe Status.Unauthorized
  }

  // ── Auth: server key not configured ──────────────────────────────────────────

  it should "return 401 when server apiKey is empty (not configured)" in {
    val req = Request[IO](method = Method.GET,
      uri = Uri.unsafeFromString("/internal/lichess/challenge-auth/anyone"))
      .putHeaders(apiKeyHeader)
    val res = makeApp(apiKey = "").run(req).unsafeRunSync()
    res.status shouldBe Status.Unauthorized
  }

  // ── Response does not expose internal details ─────────────────────────────────

  it should "never include the api key in any response body" in {
    val res  = getAuth("chess_player")
    val body = res.bodyText.compile.string.unsafeRunSync()
    body should not include testApiKey
  }

  // ── ManualDev (unverified) link is still allowed ─────────────────────────────

  it should "return allowed=true for an unverified ManualDev link" in {
    val unverifiedLink = ExternalAccountLink(
      linkId             = UUID.randomUUID(),
      userId             = UUID.randomUUID(),
      provider           = "Lichess",
      externalId         = None,
      externalUsername   = "dev_user",
      verified           = false,
      verificationSource = "ManualDev",
      linkedAt           = Instant.now()
    )
    val req = Request[IO](method = Method.GET,
      uri = Uri.unsafeFromString("/internal/lichess/challenge-auth/dev_user"))
      .putHeaders(apiKeyHeader)
    val res  = makeApp(links = List(unverifiedLink)).run(req).unsafeRunSync()
    val body = ujson.read(res.bodyText.compile.string.unsafeRunSync())
    res.status              shouldBe Status.Ok
    body("allowed").bool    shouldBe true
    body("verificationSource").str shouldBe "ManualDev"
  }

  // ── In-memory repository for tests ───────────────────────────────────────────

  private class InMemLinkRepo(initial: List[ExternalAccountLink]) extends ExternalAccountLinkRepository:
    private val store = new ConcurrentHashMap[String, ExternalAccountLink]()
    initial.foreach(l => store.put(s"${l.userId}:${l.provider}", l))

    override def findAllByUserId(userId: UUID): Either[String, List[ExternalAccountLink]] =
      Right(store.values.asScala.filter(_.userId == userId).toList)

    override def findByUserAndProvider(userId: UUID, provider: String): Either[String, Option[ExternalAccountLink]] =
      Right(Option(store.get(s"$userId:$provider")))

    override def findByLichessUsername(username: String): Either[String, Option[ExternalAccountLink]] =
      Right(store.values.asScala.find(l =>
        l.provider == "Lichess" && l.externalUsername.equalsIgnoreCase(username)
      ))

    override def insert(link: ExternalAccountLink): Either[String, Unit] =
      store.put(s"${link.userId}:${link.provider}", link)
      Right(())

    override def update(link: ExternalAccountLink): Either[String, Unit] =
      store.put(s"${link.userId}:${link.provider}", link)
      Right(())

    override def delete(userId: UUID, provider: String): Either[String, Unit] =
      store.remove(s"$userId:$provider")
      Right(())
