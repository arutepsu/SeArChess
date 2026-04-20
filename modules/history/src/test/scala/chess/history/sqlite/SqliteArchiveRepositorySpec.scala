package chess.history.sqlite

import chess.application.query.game.GameClosure
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.Color
import chess.history.ArchiveRecord
import org.scalatest.EitherValues.*
import org.scalatest.OptionValues.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.nio.file.Files
import java.time.Instant
import java.util.UUID

class SqliteArchiveRepositorySpec extends AnyFlatSpec with Matchers:

  "SqliteArchiveRepository" should "upsert and reload an archive record from its own SQLite file" in {
    val db = Files.createTempFile("searchess-history-", ".sqlite")
    val repo = SqliteArchiveRepository(db.toString)
    try
      val gameId = GameId(UUID.fromString("00000000-0000-0000-0000-000000000010"))
      val record = ArchiveRecord(
        gameId = gameId,
        sessionId = SessionId(UUID.fromString("00000000-0000-0000-0000-000000000011")),
        mode = SessionMode.HumanVsHuman,
        whiteController = SideController.HumanLocal,
        blackController = SideController.HumanLocal,
        closure = GameClosure.Resigned(Color.White),
        pgn = Some("[Result \"1-0\"]\n\n1-0"),
        finalFen = Some("8/8/8/8/8/8/8/4K3 w - - 0 1"),
        createdAt = Instant.parse("2026-04-20T09:00:00Z"),
        closedAt = Instant.parse("2026-04-20T09:01:00Z"),
        materializedAt = Instant.parse("2026-04-20T09:02:00Z")
      )

      repo.upsert(record).value
      val loaded = repo.findByGameId(gameId).value.value
      loaded.gameId shouldBe gameId
      loaded.pgn shouldBe record.pgn
      loaded.finalFen shouldBe record.finalFen
      repo.findRecordJson(gameId).value.value("closure")("kind").str shouldBe "Resigned"
    finally
      repo.close()
      Files.deleteIfExists(db)
  }
