package chess.historyservice

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import chess.adapter.event.{AppEventSerializer, DurableHistoryEventPublisher, GameHistoryIngestionContract, HistoryOutboxForwarder, SqliteHistoryEventOutbox}
import chess.application.event.AppEvent
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.history.{ArchiveMaterializer, HistoryIngestionService, RemoteGameArchiveClient}
import chess.history.sqlite.SqliteArchiveRepository
import com.sun.net.httpserver.HttpServer
import fs2.Stream
import org.http4s.{Method, Request, Status, Uri}
import org.scalatest.OptionValues.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.UUID

class GameOutboxToHistoryIntegrationSpec extends AnyFlatSpec with Matchers:

  private val sessionId = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000101"))
  private val gameId    = GameId(UUID.fromString("00000000-0000-0000-0000-000000000102"))

  "Game outbox to History ingestion" should
    "retain a terminal event after failed delivery and later materialize it through History HTTP" in {
      val gameArchiveServer = archiveServer(archiveSnapshotJson)
      gameArchiveServer.start()

      val outboxDb = Files.createTempFile("searchess-game-outbox-integration-", ".sqlite")
      val historyDb = Files.createTempFile("searchess-history-integration-", ".sqlite")
      val outbox = SqliteHistoryEventOutbox(outboxDb.toString)
      val historyRepo = SqliteArchiveRepository(historyDb.toString)

      try
        val archiveBaseUrl = s"http://127.0.0.1:${gameArchiveServer.getAddress.getPort}"
        val ingestion = HistoryIngestionService(
          archiveClient = RemoteGameArchiveClient(archiveBaseUrl, timeoutMillis = 2000),
          materializer  = ArchiveMaterializer(),
          repository    = historyRepo
        )
        val historyHttp = HistoryRoutes(ingestion, historyRepo).routes.orNotFound

        DurableHistoryEventPublisher(outbox).publish(AppEvent.SessionCancelled(sessionId, gameId))

        val failingForwarder = HistoryOutboxForwarder(
          outbox         = outbox,
          historyBaseUrl = "http://history.local:8081",
          timeoutMillis  = 2000,
          sendJson       = (_, _, _) => throw RuntimeException("history unavailable")
        )
        val failedDrain = failingForwarder.runOnce()

        val afterFailure = outbox.pending(10).toOption.get
        afterFailure should have size 1
        failedDrain.attempted shouldBe 1
        failedDrain.delivered shouldBe 0
        failedDrain.failed shouldBe 1
        afterFailure.head.attempts shouldBe 1
        afterFailure.head.lastAttemptedAt.value should not be null
        afterFailure.head.lastError.value should include ("history unavailable")

        val recoveringForwarder = HistoryOutboxForwarder(
          outbox         = outbox,
          historyBaseUrl = "http://history.local:8081",
          timeoutMillis  = 2000,
          sendJson       = (_, json, _) => postToHistory(historyHttp, json)
        )
        val recoveredDrain = recoveringForwarder.runOnce()

        recoveredDrain.attempted shouldBe 1
        recoveredDrain.delivered shouldBe 1
        recoveredDrain.failed shouldBe 0
        outbox.pending(10).toOption.get shouldBe empty

        val archived = historyRepo.findByGameId(gameId).toOption.get.value
        archived.gameId shouldBe gameId
        archived.sessionId shouldBe sessionId
        archived.finalFen.value should include ("4k3")
        archived.pgn shouldBe None

        val getResp = historyHttp.run(
          Request[IO](Method.GET, Uri.unsafeFromString(s"/archives/${gameId.value}"))
        ).unsafeRunSync()
        getResp.status shouldBe Status.Ok
      finally
        outbox.close()
        historyRepo.close()
        gameArchiveServer.stop(0)
        Files.deleteIfExists(outboxDb)
        Files.deleteIfExists(historyDb)
    }

  it should "treat duplicate terminal event delivery as idempotent on History" in {
    val gameArchiveServer = archiveServer(archiveSnapshotJson)
    gameArchiveServer.start()

    val historyDb = Files.createTempFile("searchess-history-duplicate-", ".sqlite")
    val historyRepo = SqliteArchiveRepository(historyDb.toString)

    try
      val archiveBaseUrl = s"http://127.0.0.1:${gameArchiveServer.getAddress.getPort}"
      val ingestion = HistoryIngestionService(
        archiveClient = RemoteGameArchiveClient(archiveBaseUrl, timeoutMillis = 2000),
        materializer  = ArchiveMaterializer(),
        repository    = historyRepo
      )
      val historyHttp = HistoryRoutes(ingestion, historyRepo).routes.orNotFound
      val payload = AppEventSerializer.serialize(AppEvent.SessionCancelled(sessionId, gameId)).value

      postToHistory(historyHttp, payload)
      gameArchiveServer.stop(0)
      postToHistory(historyHttp, payload)

      val archived = historyRepo.findByGameId(gameId).toOption.get.value
      archived.gameId shouldBe gameId
      archived.sessionId shouldBe sessionId
    finally
      historyRepo.close()
      gameArchiveServer.stop(0)
      Files.deleteIfExists(historyDb)
  }

  private def postToHistory(historyHttp: org.http4s.HttpApp[IO], json: String): Unit =
    val req = Request[IO](
      method = Method.POST,
      uri    = Uri.unsafeFromString(GameHistoryIngestionContract.GameEventsPath),
      body   = Stream.emits(json.getBytes(StandardCharsets.UTF_8)).covary[IO]
    )
    val resp = historyHttp.run(req).unsafeRunSync()
    if !resp.status.isSuccess then
      val body = resp.bodyText.compile.string.unsafeRunSync()
      throw RuntimeException(s"History returned HTTP ${resp.status.code}: $body")

  private def archiveServer(body: String): HttpServer =
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(s"/archive/games/${gameId.value}", exchange =>
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length.toLong)
      val os = exchange.getResponseBody
      try os.write(bytes)
      finally os.close()
    )
    server

  private def archiveSnapshotJson: String =
    s"""{
       |  "sessionId": "${sessionId.value}",
       |  "gameId": "${gameId.value}",
       |  "mode": "HumanVsHuman",
       |  "whiteController": "HumanLocal",
       |  "blackController": "HumanLocal",
       |  "closure": { "kind": "Cancelled", "winner": null, "drawReason": null },
       |  "finalState": {
       |    "game": {
       |      "gameId": "${gameId.value}",
       |      "currentPlayer": "White",
       |      "status": "Ongoing",
       |      "inCheck": false,
       |      "winner": null,
       |      "drawReason": null,
       |      "fullmoveNumber": 1,
       |      "halfmoveClock": 0,
       |      "board": [
       |        { "square": "e1", "color": "White", "pieceType": "King" },
       |        { "square": "e8", "color": "Black", "pieceType": "King" }
       |      ],
       |      "moveHistory": [],
       |      "lastMove": null,
       |      "promotionPending": false,
       |      "legalTargetsByFrom": {}
       |    },
       |    "castlingRights": {
       |      "whiteKingSide": false,
       |      "whiteQueenSide": false,
       |      "blackKingSide": false,
       |      "blackQueenSide": false
       |    },
       |    "enPassant": null
       |  },
       |  "createdAt": "2026-04-20T09:00:00Z",
       |  "closedAt": "2026-04-20T09:01:00Z"
       |}""".stripMargin
