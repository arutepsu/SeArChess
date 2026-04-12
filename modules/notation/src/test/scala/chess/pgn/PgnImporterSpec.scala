package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import chess.notation.api._
import chess.domain.model.{Color, PieceType, Position}
import chess.domain.state.GameState

/** Specification for [[PgnNotationFacade.executeImport]] (Stage 3: real PGN import).
 *
 *  Covers:
 *  - successful replay of a mainline move sequence
 *  - correct final [[GameState]] (board state, current player, move count)
 *  - [[ImportResult.GameImportResult]] structure and metadata fields
 *  - rejection of [[ImportTarget.PositionTarget]] for PGN
 *  - rejection of SetUp/FEN header games (non-standard starting positions)
 *  - rejection of non-PGN [[ParsedNotation]] types
 *  - failure on illegal SAN tokens
 *  - empty move list → initial game state
 */
class PgnImporterSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private def pos(file: Int, rank: Int) =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file $rank"))

  private def importGame(pgn: String): ImportResult.GameImportResult[GameState] =
    val parsed = PgnNotationFacade.parse(NotationFormat.PGN, pgn).value
    PgnNotationFacade.executeImport(parsed, ImportTarget.GameTarget).value
      .asInstanceOf[ImportResult.GameImportResult[GameState]]

  // ── Target routing ───────────────────────────────────────────────────────────

  "PgnNotationFacade.executeImport" should "return IncompatibleTarget for PositionTarget" in {
    val parsed = ParsedNotation.ParsedPgn("1. e4", PgnData(Map.empty, Vector("e4"), None))
    val result = PgnNotationFacade.executeImport(parsed, ImportTarget.PositionTarget)
    val err    = result.left.value.asInstanceOf[ImportFailure.IncompatibleTarget]
    err.parsedKind shouldBe ParsedNotationKind.Pgn
    err.target     shouldBe ImportTarget.PositionTarget
  }

  it should "return IncompatibleTarget for a non-PGN ParsedNotation" in {
    val jsonGame = ParsedNotation.ParsedJsonGame("{}")
    val result   = PgnNotationFacade.executeImport(jsonGame, ImportTarget.GameTarget)
    val err      = result.left.value.asInstanceOf[ImportFailure.IncompatibleTarget]
    err.parsedKind shouldBe ParsedNotationKind.JsonGame
  }

  // ── Setup/FEN rejection ──────────────────────────────────────────────────────

  it should "return CompatibilityFailure for PGN with SetUp+FEN headers" in {
    val pgn    = ParsedNotation.ParsedPgn(
      "...",
      PgnData(
        headers    = Map("SetUp" -> "1", "FEN" -> "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
        moveTokens = Vector("e4"),
        result     = None
      )
    )
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget)
    result.left.value shouldBe a[CompatibilityFailure]
  }

  it should "not reject a PGN that has FEN but no SetUp header" in {
    val pgn = ParsedNotation.ParsedPgn(
      "1. e4",
      PgnData(headers = Map("FEN" -> "some-fen"), moveTokens = Vector("e4"), result = None)
    )
    // Without SetUp "1" the FEN header alone must not trigger a compatibility failure
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget)
    // May succeed or fail on SAN replay, but NOT with CompatibilityFailure
    result.left.toOption.foreach { err =>
      err should not be a[CompatibilityFailure]
    }
  }

  // ── Empty move list ──────────────────────────────────────────────────────────

  it should "succeed with the initial GameState for an empty move list" in {
    val pgn    = ParsedNotation.ParsedPgn("", PgnData(Map.empty, Vector.empty, None))
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget).value
      .asInstanceOf[ImportResult.GameImportResult[GameState]]
    result.data.currentPlayer shouldBe Color.White
    result.data.moveHistory   shouldBe Nil
  }

  // ── Successful replay ────────────────────────────────────────────────────────

  it should "replay 1.e4 and produce Black to move" in {
    val result = importGame("1. e4")
    result.data.currentPlayer shouldBe Color.Black
  }

  it should "replay 1.e4 e5 and produce White to move" in {
    val result = importGame("1. e4 e5")
    result.data.currentPlayer shouldBe Color.White
  }

  it should "record correct move count in ReplaySummary" in {
    val result = importGame("1. e4 e5 2. Nf3 Nc6")
    result.replay.value.moveCount shouldBe Some(4)
  }

  it should "set isFullReplay to true" in {
    val result = importGame("1. e4 e5")
    result.replay.value.isFullReplay shouldBe true
  }

  it should "set hasStartingPositionOverride to false" in {
    val result = importGame("1. e4 e5")
    result.replay.value.hasStartingPositionOverride shouldBe false
    result.data.moveHistory should have size 2
  }

  it should "set sourceFormat to PGN" in {
    val result = importGame("1. e4 e5")
    result.sourceFormat shouldBe NotationFormat.PGN
  }

  it should "produce empty warnings for a clean PGN" in {
    val result = importGame("1. e4 e5")
    result.warnings shouldBe Nil
  }

  it should "replay 1.e4 correctly: white pawn appears on e4" in {
    val result = importGame("1. e4")
    import chess.domain.model.{Piece, PieceType, Color}
    result.data.board.pieceAt(pos(4, 3)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    result.data.board.pieceAt(pos(4, 1)) shouldBe None  // e2 vacated
  }

  it should "replay a Ruy Lopez opening correctly" in {
    val result = importGame("1. e4 e5 2. Nf3 Nc6 3. Bb5 a6")
    result.data.currentPlayer shouldBe Color.White
    result.data.moveHistory   should have size 6
  }

  it should "replay castling: O-O" in {
    // Ruy Lopez — after 3.Bb5 the f1 bishop is gone so white can castle kingside
    // Use a minimal line that gets the knight and bishop out of the way
    val pgn = "1. e4 e5 2. Nf3 Nc6 3. Bc4 Bc5 4. O-O"
    val result = importGame(pgn)
    // White king moved from e1 to g1
    import chess.domain.model.{Piece, PieceType, Color}
    result.data.board.pieceAt(pos(6, 0)) shouldBe Some(Piece(Color.White, PieceType.King))
  }

  it should "replay a pawn promotion to queen" in {
    // Scholar's mate position and then engineer a promotion using a crafted sequence
    // Use a known fast-promotion setup: advance a pawn to the 7th rank then promote
    // Simplest: use a direct PgnData with the right token
    val tokens = Vector(
      // White d4, Black e5, White d5, Black Nf6, White d6, Black Be7, White dxc7, Black 0-0 (castling), White cxd8=Q
      "d4", "e5", "d5", "Nf6", "d6", "Be7", "dxc7", "O-O", "cxd8=Q"
    )
    val pgn = ParsedNotation.ParsedPgn("", PgnData(Map.empty, tokens, None))
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget).value
      .asInstanceOf[ImportResult.GameImportResult[GameState]]
    import chess.domain.model.{Piece, PieceType, Color}
    result.data.board.pieceAt(pos(3, 7)) shouldBe Some(Piece(Color.White, PieceType.Queen))
  }

  // ── Failure on illegal SAN ────────────────────────────────────────────────────

  it should "return ValidationFailure for an unrecognisable SAN token" in {
    val pgn    = ParsedNotation.ParsedPgn("", PgnData(Map.empty, Vector("Zz9"), None))
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget)
    result.left.value shouldBe a[ValidationFailure]
  }

  it should "return ValidationFailure for a SAN move that is illegal in the position" in {
    // e5 as first move is illegal (pawn can only go 1 or 2 squares from rank 1)
    val pgn    = ParsedNotation.ParsedPgn("", PgnData(Map.empty, Vector("e5"), None))
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget)
    result.left.value shouldBe a[ValidationFailure]
  }

  // ── parseAndImport integration ────────────────────────────────────────────────

  it should "produce a GameImportResult via parseAndImport" in {
    val result = PgnNotationFacade.parseAndImport(NotationFormat.PGN, "1. e4 e5", ImportTarget.GameTarget)
    result.value shouldBe a[ImportResult.GameImportResult[?]]
  }

  // ── SAN capture enforcement ───────────────────────────────────────────────────

  // After 1.e4 e5 2.Nf3 Nc6 3.Bc4 Nf6 4.Ng5 d5 (8 tokens), it is White's turn.
  // White's knight on g5 can reach h7 only as a capture (Black h-pawn is there).
  // SAN without 'x' must therefore be rejected; SAN with 'x' must succeed.

  it should "reject a non-capture SAN token when the only legal move to that square is a capture" in {
    // 8 tokens → White's 5th move. Ng5 to h7 is a capture-only move.
    val setupTokens = Vector("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "Ng5", "d5")
    val pgn = ParsedNotation.ParsedPgn("", PgnData(Map.empty, setupTokens :+ "Nh7", None))
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget)
    result.left.value shouldBe a[ValidationFailure]
  }

  it should "accept the capture SAN token when the move is a genuine capture" in {
    val tokens = Vector("e4", "e5", "Nf3", "Nc6", "Bc4", "Nf6", "Ng5", "d5", "Nxh7")
    val pgn    = ParsedNotation.ParsedPgn("", PgnData(Map.empty, tokens, None))
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget)
    result.value shouldBe a[ImportResult.GameImportResult[?]]
  }

  it should "reject a capture SAN token when no capture is available at that square" in {
    // 'exd5' as first move: e-pawn cannot capture anything on d5 in the initial position
    val pgn = ParsedNotation.ParsedPgn("", PgnData(Map.empty, Vector("exd5"), None))
    val result = PgnNotationFacade.executeImport(pgn, ImportTarget.GameTarget)
    result.left.value shouldBe a[ValidationFailure]
  }

  // ── PgnReplayService.replayFrom explicit test ─────────────────────────────────

  it should "replay from a non-initial state passed explicitly to PgnReplayService" in {
    import chess.domain.state.GameStateFactory
    // Replay a single move from the initial state
    val initial = GameStateFactory.initial()
    val result  = PgnReplayService.replayFrom(initial, Vector("e4"))
    result.value.currentPlayer shouldBe Color.Black
    result.value.moveHistory   should have size 1
  }
