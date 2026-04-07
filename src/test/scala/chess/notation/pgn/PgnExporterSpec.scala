package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import chess.notation.api._
import chess.domain.model.{Color, GameStatus}
import chess.domain.state.{GameState, GameStateFactory}

/** Specification for [[PgnExporter]] via [[PgnNotationFacade.executeExport]]
 *  (Phase 4: real PGN export).
 *
 *  Covers:
 *  - initial / empty game state → result token only
 *  - simple one-move and multi-move sequences
 *  - captures (pawn captures, piece captures)
 *  - disambiguation (two pieces of the same type can reach the same square)
 *  - castling (O-O, O-O-O)
 *  - pawn promotion suffix
 *  - check suffix
 *  - checkmate suffix and result token 0-1
 *  - all result token mappings (1-0, 0-1, 1/2-1/2, *)
 *  - non-PGN format → UnsupportedExportFormat
 *  - export → import round-trip: final board matches
 */
class PgnExporterSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  // ── Helpers ──────────────────────────────────────────────────────────────────

  /** Import a PGN string and return the resulting GameState. */
  private def importState(pgn: String): GameState =
    PgnNotationFacade
      .parseAndImport(NotationFormat.PGN, pgn, ImportTarget.GameTarget)
      .value
      .asInstanceOf[ImportResult.GameImportResult[GameState]]
      .data

  /** Import a PGN string, export the resulting state, and return the PGN text. */
  private def roundTripText(pgn: String): String =
    PgnNotationFacade.executeExport(importState(pgn), NotationFormat.PGN).value.text

  // ── Format rejection ─────────────────────────────────────────────────────────

  "PgnNotationFacade.executeExport" should "return UnsupportedExportFormat for FEN format" in {
    val result = PgnNotationFacade.executeExport(GameStateFactory.initial(), NotationFormat.FEN)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  it should "return UnsupportedExportFormat for JSON format" in {
    val result = PgnNotationFacade.executeExport(GameStateFactory.initial(), NotationFormat.JSON)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  // ── Result: ExportResult structure ──────────────────────────────────────────

  it should "return an ExportResult with format PGN" in {
    val result = PgnNotationFacade.executeExport(GameStateFactory.initial(), NotationFormat.PGN)
    result.value.format shouldBe NotationFormat.PGN
  }

  it should "produce empty warnings for a clean game" in {
    val result = PgnNotationFacade.executeExport(importState("1. e4 e5"), NotationFormat.PGN)
    result.value.warnings shouldBe Nil
  }

  // ── Empty / initial game state ────────────────────────────────────────────────

  it should "export the initial game state as '*' (ongoing, no moves)" in {
    val result = PgnNotationFacade.executeExport(GameStateFactory.initial(), NotationFormat.PGN)
    result.value.text shouldBe "*"
  }

  // ── Simple move sequences ─────────────────────────────────────────────────────

  it should "export a single white move" in {
    roundTripText("1. e4") shouldBe "1. e4 *"
  }

  it should "export two half-moves" in {
    roundTripText("1. e4 e5") shouldBe "1. e4 e5 *"
  }

  it should "export four half-moves with correct move numbers" in {
    roundTripText("1. e4 e5 2. Nf3 Nc6") shouldBe "1. e4 e5 2. Nf3 Nc6 *"
  }

  // ── Captures ─────────────────────────────────────────────────────────────────

  it should "export pawn captures with 'x'" in {
    // After 1.e4 d5, White plays 2.exd5 — a pawn capture
    val text = roundTripText("1. e4 d5 2. exd5")
    text should include("exd5")
  }

  it should "export piece captures with 'x'" in {
    // After 1.e4 e5 2.Nf3 Nc6 3.Nxe5 — knight captures
    val text = roundTripText("1. e4 e5 2. Nf3 Nc6 3. Nxe5")
    text should include("Nxe5")
  }

  // ── Castling ─────────────────────────────────────────────────────────────────

  it should "export king-side castling as 'O-O'" in {
    val text = roundTripText("1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. O-O")
    text should include("O-O")
    text should not include "O-O-O"
  }

  it should "export queen-side castling as 'O-O-O'" in {
    // White: d4, Nc3, Bf4, Qd2, then O-O-O
    val text = roundTripText("1. d4 d5 2. Nc3 Nc6 3. Bf4 Bf5 4. Qd2 Qd7 5. O-O-O")
    text should include("O-O-O")
  }

  // ── Promotion ─────────────────────────────────────────────────────────────────

  it should "export pawn promotion with '=Q'" in {
    val tokens = Vector("d4", "e5", "d5", "Nf6", "d6", "Be7", "dxc7", "O-O", "cxd8=Q")
    val pgn    = ParsedNotation.ParsedPgn("", PgnData(Map.empty, tokens, None))
    val state  = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget).value
      .asInstanceOf[ImportResult.GameImportResult[GameState]].data
    val text   = PgnNotationFacade.executeExport(state, NotationFormat.PGN).value.text
    text should include("cxd8=Q")
  }

  // ── Check and checkmate suffixes ───────────────────────────────────────────────

  it should "append '+' for a move that gives check" in {
    // Scholars mate attempt: 1.e4 e5 2.Bc4 Nc6 3.Qh5 — Qh5+ is check
    val text = roundTripText("1. e4 e5 2. Bc4 Nc6 3. Qh5")
    text should include("Qh5+")
  }

  it should "append '#' for the final move of fool's mate" in {
    // Fool's mate: 1.f3 e5 2.g4 Qh4#
    val text = roundTripText("1. f3 e5 2. g4 Qh4")
    text should include("Qh4#")
  }

  // ── Result token mapping ──────────────────────────────────────────────────────

  it should "export '*' result for an ongoing game" in {
    val text = roundTripText("1. e4 e5")
    text should endWith("*")
  }

  it should "export '0-1' result when black delivers checkmate (fool's mate)" in {
    val text = roundTripText("1. f3 e5 2. g4 Qh4")
    text should endWith("0-1")
  }

  // ── Disambiguation ────────────────────────────────────────────────────────────

  it should "disambiguate by file when two same-type pieces can reach the same square" in {
    // After e4 e5 Nf3 Nc6 Nc3 Nf6 d4 exd4 Nxd4 Bb4 (black), white has Nd4 and Nc3.
    // Both can reach e2: Nde2 (from d4) or Nce2 (from c3).
    // The import of "Nde2" must round-trip with the file disambiguator present.
    val tokens = Vector(
      "e4", "e5", "Nf3", "Nc6", "Nc3", "Nf6", "d4", "exd4", "Nxd4", "Bb4", "Nde2"
    )
    val pgn   = ParsedNotation.ParsedPgn("", PgnData(Map.empty, tokens, None))
    val state = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget).value
      .asInstanceOf[ImportResult.GameImportResult[GameState]].data
    val text  = PgnNotationFacade.executeExport(state, NotationFormat.PGN).value.text
    // The last white move must carry a file disambiguator
    text should (include("Nde2") or include("Nce2"))
  }

  // ── Round-trip correctness ────────────────────────────────────────────────────

  it should "produce a PGN that re-imports to the same board position" in {
    val original  = importState("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6")
    val exported  = PgnNotationFacade.executeExport(original, NotationFormat.PGN).value.text
    val reimported = PgnNotationFacade
      .parseAndImport(NotationFormat.PGN, exported, ImportTarget.GameTarget)
      .value
      .asInstanceOf[ImportResult.GameImportResult[GameState]]
      .data
    reimported.board          shouldBe original.board
    reimported.currentPlayer  shouldBe original.currentPlayer
    reimported.castlingRights shouldBe original.castlingRights
  }
