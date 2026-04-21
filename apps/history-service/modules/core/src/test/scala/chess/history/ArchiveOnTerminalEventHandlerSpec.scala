package chess.history

import chess.application.query.game.{GameArchiveSnapshot, GameClosure, GameView}
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason}
import chess.domain.state.GameStateFactory
import chess.notation.api.{ExportResult, NotationFormat}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

class ArchiveOnTerminalEventHandlerSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val createdAt = Instant.parse("2026-04-20T10:00:00Z")
  private val closedAt  = Instant.parse("2026-04-20T10:05:00Z")

  private def snapshot(
    gameId:    GameId,
    sessionId: SessionId,
    closure:   GameClosure = GameClosure.Draw(DrawReason.Stalemate)
  ): GameArchiveSnapshot =
    val state = GameStateFactory.initial()
    GameArchiveSnapshot(
      sessionId       = sessionId,
      gameId          = gameId,
      mode            = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal,
      closure         = closure,
      finalState      = GameView.fromState(gameId, state),
      createdAt       = createdAt,
      closedAt        = closedAt
    )

  private def materializer: ArchiveMaterializer =
    ArchiveMaterializer.withExporters(
      fenExport = _ => Right(ExportResult("fen", NotationFormat.FEN)),
      pgnExport = (_, _) => Right(ExportResult("pgn", NotationFormat.PGN))
    )

  "ArchiveOnTerminalEventHandler" should "fetch, materialize, and persist a terminal event" in {
    val gameId = GameId.random()
    val sessionId = SessionId.random()
    val repo = InMemoryArchiveRepository()
    val handler = ArchiveOnTerminalEventHandler(
      fetchSnapshot = _ => Right(snapshot(gameId, sessionId, GameClosure.Resigned(Color.White))),
      materializer = materializer,
      repository = repo
    )

    val record = handler.handle(TerminalGameEvent("game.resigned.v1", sessionId, gameId)).value

    record.gameId shouldBe gameId
    record.sessionId shouldBe sessionId
    record.closure shouldBe GameClosure.Resigned(Color.White)
    repo.findInMemory(gameId).map(_.closure) shouldBe Some(GameClosure.Resigned(Color.White))
  }

  it should "surface snapshot fetch failures without materializing" in {
    val gameId = GameId.random()
    val sessionId = SessionId.random()
    val repo = InMemoryArchiveRepository()
    val handler = ArchiveOnTerminalEventHandler(
      fetchSnapshot = _ => Left(ArchiveHandlerError.SnapshotNotFound(gameId)),
      materializer = materializer,
      repository = repo
    )

    handler.handle(TerminalGameEvent("game.finished.v1", sessionId, gameId)).left.value shouldBe
      ArchiveHandlerError.SnapshotNotFound(gameId)
    repo.size shouldBe 0
  }

  it should "surface persistence failures" in {
    val gameId = GameId.random()
    val sessionId = SessionId.random()
    val failingRepo = new ArchiveRepository:
      def upsert(record: ArchiveRecord): Either[ArchiveRepositoryError, Unit] =
        Left(ArchiveRepositoryError.StorageFailure("write failed"))

      def findByGameId(gameId: GameId): Either[ArchiveRepositoryError, Option[ArchiveRecord]] =
        Right(None)

    val handler = ArchiveOnTerminalEventHandler(
      fetchSnapshot = _ => Right(snapshot(gameId, sessionId)),
      materializer = materializer,
      repository = failingRepo
    )

    handler.handle(TerminalGameEvent("game.finished.v1", sessionId, gameId)).left.value shouldBe
      ArchiveHandlerError.PersistenceFailed("write failed")
  }
