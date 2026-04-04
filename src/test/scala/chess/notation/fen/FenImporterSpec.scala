package chess.notation.fen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import org.scalatest.OptionValues
import chess.notation.api.{
  ImportFailure, ImportResult, ImportTarget,
  NotationFormat, ParsedNotation, ParsedNotationKind,
  PositionImportMetadata, ValidationFailure
}
import chess.domain.model.{Color, GameStatus, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, GameState}

class FenImporterSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val InitialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val AfterE4Fen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  private def pos(file: Int, rank: Int) =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file $rank"))

  /** Parse a FEN string and import it into PositionTarget. Fails the test on error. */
  private def importResult(fen: String) =
    FenImporter.importNotation(FenParser.parse(fen).value, ImportTarget.PositionTarget).value

  /** Import a FEN into PositionTarget and return the GameState. Fails the test on error. */
  private def importState(fen: String): GameState =
    importResult(fen)
      .asInstanceOf[ImportResult.PositionImportResult[GameState]]
      .data

  // ── Successful import ────────────────────────────────────────────────────────

  "FenImporter" should "return PositionImportResult for a valid FEN and PositionTarget" in {
    val result = FenImporter.importNotation(FenParser.parse(InitialFen).value, ImportTarget.PositionTarget)
    result.value shouldBe a[ImportResult.PositionImportResult[?]]
  }

  it should "set sourceFormat to FEN" in {
    val result = FenImporter.importNotation(FenParser.parse(InitialFen).value, ImportTarget.PositionTarget)
    result.value.sourceFormat shouldBe NotationFormat.FEN
  }

  it should "produce an empty warnings list for a clean FEN" in {
    val result = FenImporter.importNotation(FenParser.parse(InitialFen).value, ImportTarget.PositionTarget)
    result.value.warnings shouldBe Nil
  }

  it should "import White as the current player from the initial position" in {
    importState(InitialFen).currentPlayer shouldBe Color.White
  }

  it should "import full castling rights from the initial position" in {
    importState(InitialFen).castlingRights shouldBe CastlingRights.full
  }

  it should "import no en passant state from the initial position" in {
    importState(InitialFen).enPassantState shouldBe None
  }

  it should "always set moveHistory to Nil" in {
    importState(InitialFen).moveHistory shouldBe Nil
  }

  it should "always set pendingPromotion to None" in {
    importState(InitialFen).pendingPromotion shouldBe None
  }

  it should "import the correct board contents for the initial position" in {
    val board = importState(InitialFen).board
    board.pieceAt(pos(4, 0)) shouldBe Some(Piece(Color.White, PieceType.King))
    board.pieceAt(pos(3, 0)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    board.pieceAt(pos(0, 0)) shouldBe Some(Piece(Color.White, PieceType.Rook))
    board.pieceAt(pos(4, 7)) shouldBe Some(Piece(Color.Black, PieceType.King))
    board.pieceAt(pos(3, 7)) shouldBe Some(Piece(Color.Black, PieceType.Queen))
    board.pieceAt(pos(0, 7)) shouldBe Some(Piece(Color.Black, PieceType.Rook))
  }

  it should "derive Ongoing status for the initial position" in {
    importState(InitialFen).status shouldBe GameStatus.Ongoing
  }

  it should "import Black as the current player and set en passant state after 1.e4" in {
    val state = importState(AfterE4Fen)
    state.currentPlayer shouldBe Color.Black
    val ep = state.enPassantState.value
    ep.targetSquare         shouldBe pos(4, 2)  // e3
    ep.capturablePawnSquare shouldBe pos(4, 3)  // e4
    ep.pawnColor            shouldBe Color.White
  }

  // ── Metadata: clock values ───────────────────────────────────────────────────

  it should "preserve halfmoveClock and fullmoveNumber from the initial position" in {
    val meta = importResult(InitialFen).asInstanceOf[ImportResult.PositionImportResult[?]].metadata
    meta.halfmoveClock  shouldBe Some(0)
    meta.fullmoveNumber shouldBe Some(1)
  }

  it should "preserve non-zero halfmove and fullmove counters" in {
    val fenWithClocks = "r1bqkb1r/pppp1ppp/2n2n2/4p3/2B1P3/5N2/PPPP1PPP/RNBQK2R w KQkq - 4 4"
    val result = FenImporter.importNotation(FenParser.parse(fenWithClocks).value, ImportTarget.PositionTarget)
    result.value shouldBe a[ImportResult.PositionImportResult[?]]
    val meta = result.value.asInstanceOf[ImportResult.PositionImportResult[?]].metadata
    meta.halfmoveClock  shouldBe Some(4)
    meta.fullmoveNumber shouldBe Some(4)
  }

  // ── Target incompatibility ───────────────────────────────────────────────────

  it should "return IncompatibleTarget with Fen kind when FEN is imported into GameTarget" in {
    val result = FenImporter.importNotation(FenParser.parse(InitialFen).value, ImportTarget.GameTarget)
    val err    = result.left.value.asInstanceOf[ImportFailure.IncompatibleTarget]
    err.parsedKind shouldBe ParsedNotationKind.Fen
    err.target     shouldBe ImportTarget.GameTarget
  }

  it should "return IncompatibleTarget with Pgn kind for ParsedPgn and PositionTarget" in {
    val pgn    = ParsedNotation.ParsedPgn("...", Map.empty, "1. e4 e5")
    val result = FenImporter.importNotation(pgn, ImportTarget.PositionTarget)
    val err    = result.left.value.asInstanceOf[ImportFailure.IncompatibleTarget]
    err.parsedKind shouldBe ParsedNotationKind.Pgn
    err.target     shouldBe ImportTarget.PositionTarget
  }

  // ── Semantic validation failures ─────────────────────────────────────────────

  it should "return ValidationFailure when the white king is missing" in {
    val result = FenImporter.importNotation(
      FenParser.parse("8/8/8/8/8/8/8/7k b - - 0 1").value,
      ImportTarget.PositionTarget
    )
    result.left.value shouldBe a[ValidationFailure]
    result.left.value.message should include("white king")
  }

  it should "return ValidationFailure when the black king is missing" in {
    val result = FenImporter.importNotation(
      FenParser.parse("8/8/8/8/8/8/8/4K3 w - - 0 1").value,
      ImportTarget.PositionTarget
    )
    result.left.value shouldBe a[ValidationFailure]
    result.left.value.message should include("black king")
  }

  it should "return ValidationFailure when castling right K is declared but white king is not on e1" in {
    // White king on b1 (not e1); white rook on h1; black king on h7
    val result = FenImporter.importNotation(
      FenParser.parse("8/7k/8/8/8/8/8/1K5R w K - 0 1").value,
      ImportTarget.PositionTarget
    )
    result.left.value shouldBe a[ValidationFailure]
    result.left.value.message should include("K")
  }

  it should "return ValidationFailure when castling right K is declared but the h1 rook is absent" in {
    // White king on e1; no rook on h1; black king on h7
    val result = FenImporter.importNotation(
      FenParser.parse("8/7k/8/8/8/8/8/4K3 w K - 0 1").value,
      ImportTarget.PositionTarget
    )
    result.left.value shouldBe a[ValidationFailure]
    result.left.value.message should include("K")
  }

  it should "return ValidationFailure for an en passant rank inconsistent with the active color" in {
    // White to move; en passant on rank 4 (should be rank 6 for White active)
    val result = FenImporter.importNotation(
      FenParser.parse("8/7k/8/8/8/8/8/4K3 w - e4 0 1").value,
      ImportTarget.PositionTarget
    )
    result.left.value shouldBe a[ValidationFailure]
  }
