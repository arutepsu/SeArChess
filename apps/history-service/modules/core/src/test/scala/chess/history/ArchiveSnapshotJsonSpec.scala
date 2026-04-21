package chess.history

import org.scalatest.EitherValues.*
import org.scalatest.OptionValues.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ArchiveSnapshotJsonSpec extends AnyFlatSpec with Matchers:

  private val body =
    """{
      |  "sessionId": "00000000-0000-0000-0000-000000000001",
      |  "gameId": "00000000-0000-0000-0000-000000000002",
      |  "mode": "HumanVsHuman",
      |  "whiteController": "HumanLocal",
      |  "blackController": "HumanLocal",
      |  "closure": { "kind": "Cancelled", "winner": null, "drawReason": null },
      |  "finalState": {
      |    "game": {
      |      "gameId": "00000000-0000-0000-0000-000000000002",
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

  "ArchiveSnapshotJson.fromJson" should "parse the Game Service archive payload into an application snapshot" in {
    val snapshot = ArchiveSnapshotJson.fromJson(body).value
    snapshot.gameId.value.toString shouldBe "00000000-0000-0000-0000-000000000002"
    snapshot.closure shouldBe chess.application.query.game.GameClosure.Cancelled
    snapshot.finalState.board should have size 2
    snapshot.finalState.castlingRights.whiteKingSide shouldBe false
  }

  it should "feed the materializer after parsing" in {
    val snapshot = ArchiveSnapshotJson.fromJson(body).value
    val record = ArchiveMaterializer().materialize(snapshot).value
    record.gameId shouldBe snapshot.gameId
    record.finalFen.value should include("4k3")
    record.pgn shouldBe None
  }
