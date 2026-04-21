package chess.history

import chess.application.query.game.{GameArchiveSnapshot, GameClosure, GameView}
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, DrawReason, GameStatus}
import chess.domain.state.{GameState, GameStateFactory}
import chess.notation.api.{
  ExportFailure,
  ExportResult,
  ImportResult,
  ImportTarget,
  NotationFailure,
  NotationFormat
}
import chess.notation.pgn.PgnNotationFacade
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.time.Instant

/** Tests for [[ArchiveMaterializer.materialize]].
  *
  * Covers result derivation for all [[GameClosure]] variants, PGN header assembly, FEN population,
  * cancelled-game edge cases, and error propagation when the injected notation exporters fail.
  */
class ArchiveMaterializerSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val mat = ArchiveMaterializer()

  // ── Fixture helpers ──────────────────────────────────────────────────────────

  private val fixedNow = Instant.parse("2026-04-20T10:00:00Z")

  private def snapshot(
      closure: GameClosure,
      state: GameState = GameStateFactory.initial(),
      createdAt: Instant = fixedNow
  ): GameArchiveSnapshot =
    val gameId = GameId.random()
    GameArchiveSnapshot(
      sessionId = SessionId.random(),
      gameId = gameId,
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal,
      closure = closure,
      finalState = GameView.fromState(gameId, state),
      createdAt = createdAt,
      closedAt = createdAt.plusSeconds(120)
    )

  /** Import a PGN string to a GameState with a non-empty move history. */
  private def stateFromPgn(pgn: String): GameState =
    PgnNotationFacade
      .parseAndImport(NotationFormat.PGN, pgn, ImportTarget.GameTarget)
      .value
      .asInstanceOf[ImportResult.GameImportResult[GameState]]
      .data

  // ── Resigned ─────────────────────────────────────────────────────────────────

  "ArchiveMaterializer.materialize" should "produce an ArchiveRecord for a resigned game" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Resigned(Color.White))
    val snap = snapshot(GameClosure.Resigned(Color.White), state)

    val record = mat.materialize(snap, fixedNow).value

    record.closure shouldBe GameClosure.Resigned(Color.White)
    record.pgn shouldBe defined
    record.finalFen shouldBe defined
    record.gameId shouldBe snap.gameId
    record.sessionId shouldBe snap.sessionId
    record.materializedAt shouldBe fixedNow
  }

  it should "embed 1-0 result tag in PGN when White wins by resignation" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Resigned(Color.White))
    val record = mat.materialize(snapshot(GameClosure.Resigned(Color.White), state)).value
    record.pgn.value should include("""[Result "1-0"]""")
    record.pgn.value should endWith("1-0")
  }

  it should "embed 0-1 result tag in PGN when Black wins by resignation" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Resigned(Color.Black))
    val record = mat.materialize(snapshot(GameClosure.Resigned(Color.Black), state)).value
    record.pgn.value should include("""[Result "0-1"]""")
    record.pgn.value should endWith("0-1")
  }

  // ── Checkmate ────────────────────────────────────────────────────────────────

  it should "produce an ArchiveRecord for a checkmate game" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Checkmate(Color.White))
    val record = mat.materialize(snapshot(GameClosure.Checkmate(Color.White), state)).value

    record.closure shouldBe GameClosure.Checkmate(Color.White)
    record.pgn shouldBe defined
    record.finalFen shouldBe defined
  }

  it should "embed 1-0 result tag in PGN for Checkmate(White)" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Checkmate(Color.White))
    val record = mat.materialize(snapshot(GameClosure.Checkmate(Color.White), state)).value
    record.pgn.value should include("""[Result "1-0"]""")
    record.pgn.value should endWith("1-0")
  }

  it should "embed 0-1 result tag in PGN for Checkmate(Black)" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Checkmate(Color.Black))
    val record = mat.materialize(snapshot(GameClosure.Checkmate(Color.Black), state)).value
    record.pgn.value should include("""[Result "0-1"]""")
    record.pgn.value should endWith("0-1")
  }

  // ── Draw ─────────────────────────────────────────────────────────────────────

  it should "produce an ArchiveRecord for a draw game" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Draw(DrawReason.Stalemate))
    val record = mat.materialize(snapshot(GameClosure.Draw(DrawReason.Stalemate), state)).value

    record.closure shouldBe GameClosure.Draw(DrawReason.Stalemate)
    record.pgn shouldBe defined
    record.finalFen shouldBe defined
  }

  it should "embed 1/2-1/2 result tag in PGN for a draw" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Draw(DrawReason.Stalemate))
    val record = mat.materialize(snapshot(GameClosure.Draw(DrawReason.Stalemate), state)).value
    record.pgn.value should include("""[Result "1/2-1/2"]""")
    record.pgn.value should endWith("1/2-1/2")
  }

  // ── Cancelled with moves ─────────────────────────────────────────────────────

  it should "produce PGN for a cancelled game that has moves" in {
    val stateWithMoves = stateFromPgn("1. e4 e5 *")
    val record = mat.materialize(snapshot(GameClosure.Cancelled, stateWithMoves)).value

    record.closure shouldBe GameClosure.Cancelled
    record.pgn shouldBe defined
    record.pgn.value should include("1. e4")
    record.pgn.value should endWith("*")
    record.pgn.value should include("""[Result "*"]""")
  }

  it should "populate finalFen for a cancelled game with moves" in {
    val stateWithMoves = stateFromPgn("1. e4 *")
    val record = mat.materialize(snapshot(GameClosure.Cancelled, stateWithMoves)).value
    record.finalFen shouldBe defined
  }

  // ── Cancelled without moves ───────────────────────────────────────────────────

  it should "set pgn to None for a cancelled game with no moves" in {
    val record = mat.materialize(snapshot(GameClosure.Cancelled)).value
    record.pgn shouldBe None
    record.closure shouldBe GameClosure.Cancelled
  }

  it should "still populate finalFen for a cancelled game with no moves" in {
    val record = mat.materialize(snapshot(GameClosure.Cancelled)).value
    record.finalFen shouldBe defined
  }

  // ── PGN header structure ──────────────────────────────────────────────────────

  it should "include the Seven Tag Roster headers in PGN" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Resigned(Color.White))
    val record = mat.materialize(snapshot(GameClosure.Resigned(Color.White), state)).value
    val pgn = record.pgn.value

    pgn should include("[Event")
    pgn should include("[Site")
    pgn should include("[Date")
    pgn should include("[Round")
    pgn should include("[White")
    pgn should include("[Black")
    pgn should include("[Result")
  }

  it should "format the Date tag from createdAt in yyyy.MM.dd format" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Resigned(Color.White))
    val snap = snapshot(GameClosure.Resigned(Color.White), state, createdAt = fixedNow)
    val record = mat.materialize(snap).value
    record.pgn.value should include("""[Date "2026.04.20"]""")
  }

  it should "emit headers before movetext with a blank-line separator" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Resigned(Color.White))
    val record = mat.materialize(snapshot(GameClosure.Resigned(Color.White), state)).value
    val pgn = record.pgn.value
    val headerEnd = pgn.indexOf("\n\n")
    headerEnd should be > 0
    pgn.substring(0, headerEnd) should startWith("[Event")
  }

  // ── FEN content ──────────────────────────────────────────────────────────────

  it should "produce the standard starting-position FEN for an initial-state game" in {
    val state = GameStateFactory.initial().copy(status = GameStatus.Resigned(Color.White))
    val record = mat.materialize(snapshot(GameClosure.Resigned(Color.White), state)).value
    // Standard starting FEN — verifies FEN round-trip is exact
    record.finalFen.value shouldBe "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  }

  // ── ArchiveRecord metadata ────────────────────────────────────────────────────

  it should "copy session/game identity fields from the snapshot" in {
    val snap = snapshot(GameClosure.Cancelled)
    val record = mat.materialize(snap, fixedNow).value

    record.gameId shouldBe snap.gameId
    record.sessionId shouldBe snap.sessionId
    record.mode shouldBe snap.mode
    record.whiteController shouldBe snap.whiteController
    record.blackController shouldBe snap.blackController
    record.createdAt shouldBe snap.createdAt
    record.closedAt shouldBe snap.closedAt
    record.materializedAt shouldBe fixedNow
  }

  // ── Error propagation ────────────────────────────────────────────────────────

  it should "return FenExportFailed when the FEN exporter fails" in {
    val failingFen: GameState => Either[NotationFailure, ExportResult] =
      _ => Left(ExportFailure.SerializationError("board", "injected FEN failure"))
    val failMat = ArchiveMaterializer.withExporters(failingFen, PgnNotationFacade.exportWithHeaders)

    val result = failMat.materialize(snapshot(GameClosure.Cancelled))
    result.left.value shouldBe a[ArchiveMaterializeError.FenExportFailed]
  }

  it should "return PgnExportFailed when the PGN exporter fails" in {
    val realFen: GameState => Either[NotationFailure, ExportResult] =
      s => chess.notation.fen.FenNotationFacade.executeExport(s, NotationFormat.FEN)
    val failingPgn: (GameState, Seq[(String, String)]) => Either[NotationFailure, ExportResult] =
      (_, _) => Left(ExportFailure.SerializationError("move", "injected PGN failure"))
    val failMat = ArchiveMaterializer.withExporters(realFen, failingPgn)

    // Use a resigned state with a move so PGN derivation is attempted
    val state = stateFromPgn("1. e4 *")
    val snap = snapshot(
      GameClosure.Resigned(Color.White),
      state.copy(status = GameStatus.Resigned(Color.White))
    )
    val result = failMat.materialize(snap)
    result.left.value shouldBe a[ArchiveMaterializeError.PgnExportFailed]
  }

  it should "not attempt PGN export when FEN export fails (fail fast)" in {
    var pgnCalled = false
    val failingFen: GameState => Either[NotationFailure, ExportResult] =
      _ => Left(ExportFailure.SerializationError("board", "injected"))
    val trackingPgn: (GameState, Seq[(String, String)]) => Either[NotationFailure, ExportResult] =
      (s, h) => { pgnCalled = true; PgnNotationFacade.exportWithHeaders(s, h) }
    val failMat = ArchiveMaterializer.withExporters(failingFen, trackingPgn)

    failMat.materialize(snapshot(GameClosure.Cancelled))
    pgnCalled shouldBe false
  }
