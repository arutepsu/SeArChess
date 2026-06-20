package chess.adapter.http4s

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.http4s.route.{BotCredentials, ExternalGameRouteAuth, Http4sExternalGameRoutes}
import chess.application.external.{
  AiMoveResult,
  BindingId,
  BindingStatus,
  ExternalGameBinding,
  ExternalGameError,
  ExternalGameServiceApi,
  IngestMovesResult,
  NextAction,
  StartExternalGameRequest,
  VerifiedExternalCaller
}
import chess.application.session.model.{ExternalPlatform, SessionMode}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, GameStatus}
import fs2.Stream
import org.http4s.*
import org.http4s.implicits.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.typelevel.ci.CIString
import java.time.Instant

class Http4sExternalGameRoutesSpec extends AnyFlatSpec with Matchers:

  private val OwnerCaller = VerifiedExternalCaller(ExternalPlatform.Lichess, "bot-1")
  private val OtherCaller = VerifiedExternalCaller(ExternalPlatform.Lichess, "bot-2")

  private def makeRoutes(service: FakeExternalGameService = FakeExternalGameService()): HttpApp[IO] =
    Http4sExternalGameRoutes(
      service,
      ExternalGameRouteAuth(
        List(
          BotCredentials("secret", OwnerCaller),
          BotCredentials("other-secret", OtherCaller)
        )
      )
    ).routes.orNotFound

  private def run(routes: HttpApp[IO], req: Request[IO]): Response[IO] =
    routes.run(req).unsafeRunSync()

  private def bodyJson(resp: Response[IO]): ujson.Value =
    ujson.read(resp.bodyText.compile.string.unsafeRunSync())

  private def jsonRequest(method: Method, uri: Uri, body: String, key: Option[String] = Some("secret")) =
    val base = Request[IO](method, uri)
      .withBodyStream(Stream.emits(body.getBytes("UTF-8")).covary[IO])
    key match
      case Some(value) => base.withHeaders(Header.Raw(CIString("X-Bot-Api-Key"), value))
      case None        => base

  private def getRequest(uri: Uri, key: Option[String] = Some("secret")) =
    val base = Request[IO](Method.GET, uri)
    key match
      case Some(value) => base.withHeaders(Header.Raw(CIString("X-Bot-Api-Key"), value))
      case None        => base

  private val StartBody =
    """{"platform":"lichess","externalGameId":"game-1","mode":"AiVsExternal","ourColor":"White","opponentActorId":"opponent-1"}"""

  "external-game auth" should "return 401 when the API key is missing" in {
    val resp = run(makeRoutes(), jsonRequest(Method.POST, uri"/external-games", StartBody, None))

    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  it should "return 401 when the API key is invalid" in {
    val resp = run(makeRoutes(), jsonRequest(Method.POST, uri"/external-games", StartBody, Some("bad")))

    resp.status shouldBe Status.Unauthorized
    bodyJson(resp)("code").str shouldBe "UNAUTHORIZED"
  }

  "POST /external-games" should "start an external game and return binding data" in {
    val resp = run(makeRoutes(), jsonRequest(Method.POST, uri"/external-games", StartBody))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("platform").str shouldBe "Lichess"
    json("externalGameId").str shouldBe "game-1"
    json("ourActorId").str shouldBe "bot-1"
    json("status").str shouldBe "Active"
  }

  "GET /external-games/{platform}/{externalGameId}" should "return an existing binding" in {
    val service = FakeExternalGameService()
    val routes = makeRoutes(service)
    run(routes, jsonRequest(Method.POST, uri"/external-games", StartBody))

    val resp = run(routes, getRequest(uri"/external-games/lichess/game-1"))

    resp.status shouldBe Status.Ok
    bodyJson(resp)("externalGameId").str shouldBe "game-1"
  }

  it should "return 404 for a missing binding" in {
    val resp = run(makeRoutes(), getRequest(uri"/external-games/lichess/missing"))

    resp.status shouldBe Status.NotFound
    bodyJson(resp)("code").str shouldBe "EXTERNAL_GAME_NOT_FOUND"
  }

  "POST /external-games/{platform}/{externalGameId}/moves" should "ingest moves and return nextAction" in {
    val service = FakeExternalGameService()
    val routes = makeRoutes(service)
    run(routes, jsonRequest(Method.POST, uri"/external-games", StartBody))

    val resp = run(
      routes,
      jsonRequest(Method.POST, uri"/external-games/lichess/game-1/moves", """{"uciMoves":"e2e4"}""")
    )

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("lastProcessedPly").num.toInt shouldBe 1
    json("nextAction").str shouldBe "TriggerAI"
  }

  it should "return 400 for a malformed body" in {
    val service = FakeExternalGameService()
    val routes = makeRoutes(service)
    run(routes, jsonRequest(Method.POST, uri"/external-games", StartBody))

    val resp =
      run(routes, jsonRequest(Method.POST, uri"/external-games/lichess/game-1/moves", "not json"))

    resp.status shouldBe Status.BadRequest
    bodyJson(resp)("code").str shouldBe "BAD_REQUEST"
  }

  it should "return 403 when the verified caller does not own the binding" in {
    val service = FakeExternalGameService()
    val routes = makeRoutes(service)
    run(routes, jsonRequest(Method.POST, uri"/external-games", StartBody))

    val resp = run(
      routes,
      jsonRequest(
        Method.POST,
        uri"/external-games/lichess/game-1/moves",
        """{"uciMoves":"e2e4"}""",
        Some("other-secret")
      )
    )

    resp.status shouldBe Status.Forbidden
    bodyJson(resp)("code").str shouldBe "FORBIDDEN"
  }

  "POST /external-games/{platform}/{externalGameId}/ai-move" should "map success to 200" in {
    val service = FakeExternalGameService()
    val routes = makeRoutes(service)
    run(routes, jsonRequest(Method.POST, uri"/external-games", StartBody))

    val resp = run(routes, jsonRequest(Method.POST, uri"/external-games/lichess/game-1/ai-move", "{}"))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("uciMove").str shouldBe "e7e5"
    json("gameStatus").str shouldBe "Ongoing"
  }

  "POST /external-games/{platform}/{externalGameId}/finish" should "map success to 200" in {
    val service = FakeExternalGameService()
    val routes = makeRoutes(service)
    run(routes, jsonRequest(Method.POST, uri"/external-games", StartBody))

    val resp = run(routes, jsonRequest(Method.POST, uri"/external-games/lichess/game-1/finish", "{}"))

    resp.status shouldBe Status.Ok
    val json = bodyJson(resp)
    json("status").str shouldBe "Finished"
    json("externalGameId").str shouldBe "game-1"
  }

  private final class FakeExternalGameService extends ExternalGameServiceApi:
    private var bindings: Map[(ExternalPlatform, String), ExternalGameBinding] = Map.empty

    def startExternalGame(
        request: StartExternalGameRequest
    ): Either[ExternalGameError, ExternalGameBinding] =
      val key = (request.platform, request.externalGameId)
      bindings.get(key) match
        case Some(binding) => Right(binding)
        case None =>
          val now = Instant.parse("2026-06-02T00:00:00Z")
          val binding = ExternalGameBinding(
            bindingId = BindingId.random(),
            platform = request.platform,
            externalGameId = request.externalGameId,
            internalGameId = GameId.random(),
            sessionId = SessionId.random(),
            ourActorId = request.ourActorId,
            status = BindingStatus.Active,
            lastProcessedPly = 0,
            createdAt = now,
            updatedAt = now
          )
          bindings = bindings.updated(key, binding)
          Right(binding)

    def getExternalGame(
        platform: ExternalPlatform,
        externalGameId: String
    ): Either[ExternalGameError, ExternalGameBinding] =
      bindings
        .get((platform, externalGameId))
        .toRight(ExternalGameError.BindingNotFound(platform, externalGameId))

    def ingestExternalMoves(
        caller: VerifiedExternalCaller,
        platform: ExternalPlatform,
        externalGameId: String,
        uciMoves: String,
        now: Instant
    ): Either[ExternalGameError, IngestMovesResult] =
      owned(caller, platform, externalGameId).map { _ =>
        IngestMovesResult(
          lastProcessedPly = uciMoves.split("\\s+").filter(_.nonEmpty).length,
          currentPlayer = Color.Black,
          gameStatus = GameStatus.Ongoing(false),
          nextAction = NextAction.TriggerAI
        )
      }

    def requestAiMove(
        caller: VerifiedExternalCaller,
        platform: ExternalPlatform,
        externalGameId: String,
        now: Instant
    ): Either[ExternalGameError, AiMoveResult] =
      owned(caller, platform, externalGameId).map { _ =>
        AiMoveResult(
          uciMove = "e7e5",
          lastProcessedPly = 2,
          gameStatus = GameStatus.Ongoing(false),
          nextAction = NextAction.AwaitExternalMove
        )
      }

    def finishExternalGame(
        caller: VerifiedExternalCaller,
        platform: ExternalPlatform,
        externalGameId: String,
        now: Instant
    ): Either[ExternalGameError, ExternalGameBinding] =
      owned(caller, platform, externalGameId).map { binding =>
        val finished = binding.copy(status = BindingStatus.Finished, updatedAt = now)
        bindings = bindings.updated((platform, externalGameId), finished)
        finished
      }

    private def owned(
        caller: VerifiedExternalCaller,
        platform: ExternalPlatform,
        externalGameId: String
    ): Either[ExternalGameError, ExternalGameBinding] =
      getExternalGame(platform, externalGameId).flatMap { binding =>
        if caller.platform == binding.platform && caller.actorId == binding.ourActorId then Right(binding)
        else Left(ExternalGameError.Unauthorized(caller.actorId, binding.ourActorId))
      }

  private object FakeExternalGameService:
    def apply(): FakeExternalGameService = new FakeExternalGameService
