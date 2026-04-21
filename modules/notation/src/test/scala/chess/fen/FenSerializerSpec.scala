package chess.notation.fen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState, GameStateFactory}
import chess.notation.api.{ExportFailure, ExportResult, NotationFormat}

/** Tests for [[FenSerializer]] and the export path through [[FenNotationFacade]].
  *
  * Covers: initial position round-trip, empty board, piece casing, empty-square compression, active
  * color, castling rights, en passant, field ordering, clock fields, format rejection, and
  * FenNotationFacade wiring.
  */
class FenSerializerSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val InitialFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val freshState = GameStateFactory.initial()

  private def pos(file: Int, rank: Int): Position =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: ($file, $rank)"))

  private def baseState(
      board: Board = Board.empty,
      currentPlayer: Color = Color.White,
      castlingRights: CastlingRights = CastlingRights.none,
      enPassantState: Option[EnPassantState] = None,
      halfmoveClock: Int = 0,
      fullmoveNumber: Int = 1
  ): GameState =
    GameState(
      board = board,
      currentPlayer = currentPlayer,
      moveHistory = Nil,
      status = GameStatus.Ongoing(inCheck = false),
      castlingRights = castlingRights,
      enPassantState = enPassantState,
      halfmoveClock = halfmoveClock,
      fullmoveNumber = fullmoveNumber
    )

  // ── Initial position round-trip ────────────────────────────────────────────

  "FenSerializer.serialize" should "produce the standard FEN for the starting position" in {
    FenSerializer.serialize(freshState) shouldBe InitialFen
  }

  // ── Empty board ────────────────────────────────────────────────────────────

  it should "encode all-empty ranks as 8/8/8/8/8/8/8/8" in {
    val state = baseState()
    val fen = FenSerializer.serialize(state)
    fen.split(' ')(0) shouldBe "8/8/8/8/8/8/8/8"
  }

  // ── Six-field ordering ──────────────────────────────────────────────────────

  it should "produce exactly six space-separated fields" in {
    FenSerializer.serialize(freshState).split(' ') should have length 6
  }

  it should "place piece placement in field 0" in {
    val fen = FenSerializer.serialize(freshState)
    fen.split(' ')(0) should include("/")
  }

  it should "place active color in field 1" in {
    FenSerializer.serialize(freshState).split(' ')(1) shouldBe "w"
  }

  it should "place castling rights in field 2" in {
    FenSerializer.serialize(freshState).split(' ')(2) shouldBe "KQkq"
  }

  it should "place en passant in field 3" in {
    FenSerializer.serialize(freshState).split(' ')(3) shouldBe "-"
  }

  it should "place halfmove clock in field 4" in {
    FenSerializer.serialize(freshState).split(' ')(4) shouldBe "0"
  }

  it should "place fullmove number in field 5" in {
    FenSerializer.serialize(freshState).split(' ')(5) shouldBe "1"
  }

  // ── Piece casing ────────────────────────────────────────────────────────────

  it should "encode White pieces as uppercase letters" in {
    val board = Board.empty
      .place(pos(4, 0), Piece(Color.White, PieceType.King))
      .place(pos(4, 1), Piece(Color.White, PieceType.Pawn))
    val fen = FenSerializer.serializePiecePlacement(baseState(board = board))
    fen should include("K")
    fen should include("P")
    fen should not include "k"
    fen should not include "p"
  }

  it should "encode Black pieces as lowercase letters" in {
    val board = Board.empty
      .place(pos(4, 7), Piece(Color.Black, PieceType.King))
      .place(pos(4, 6), Piece(Color.Black, PieceType.Pawn))
    val fen = FenSerializer.serializePiecePlacement(baseState(board = board))
    fen should include("k")
    fen should include("p")
    fen should not include "K"
    fen should not include "P"
  }

  it should "use Q/q for queens, R/r for rooks, B/b for bishops, N/n for knights" in {
    val board = Board.empty
      .place(pos(0, 0), Piece(Color.White, PieceType.Queen))
      .place(pos(1, 0), Piece(Color.White, PieceType.Rook))
      .place(pos(2, 0), Piece(Color.White, PieceType.Bishop))
      .place(pos(3, 0), Piece(Color.White, PieceType.Knight))
      .place(pos(0, 7), Piece(Color.Black, PieceType.Queen))
      .place(pos(1, 7), Piece(Color.Black, PieceType.Rook))
      .place(pos(2, 7), Piece(Color.Black, PieceType.Bishop))
      .place(pos(3, 7), Piece(Color.Black, PieceType.Knight))
    val fen = FenSerializer.serializePiecePlacement(baseState(board = board))
    fen should (include("Q") and include("R") and include("B") and include("N"))
    fen should (include("q") and include("r") and include("b") and include("n"))
  }

  // ── Empty-square compression ────────────────────────────────────────────────

  it should "compress a full empty rank to the digit 8" in {
    val board = Board.empty.place(pos(4, 0), Piece(Color.White, PieceType.King))
    val fen = FenSerializer.serializePiecePlacement(baseState(board = board))
    // rank 8 through rank 2 are fully empty → each encodes as "8"
    val ranks = fen.split('/')
    ranks(0) shouldBe "8" // rank 8 (index 0 in the FEN, rank index 7)
    ranks(1) shouldBe "8"
  }

  it should "compress consecutive empty squares within a rank to a single digit" in {
    // Place one piece at file a (0), file h (7) — rank 0
    val board = Board.empty
      .place(pos(0, 0), Piece(Color.White, PieceType.Rook))
      .place(pos(7, 0), Piece(Color.White, PieceType.Rook))
    val fen = FenSerializer.serializePiecePlacement(baseState(board = board))
    val rank1 = fen.split('/').last // last rank in FEN = rank 0
    rank1 shouldBe "R6R"
  }

  it should "not emit a zero for an empty prefix or suffix of a rank" in {
    // Single piece in the middle of rank 0
    val board = Board.empty.place(pos(3, 0), Piece(Color.White, PieceType.King))
    val rank1 = FenSerializer.serializePiecePlacement(baseState(board = board)).split('/').last
    rank1 shouldBe "3K4"
  }

  // ── Active color ────────────────────────────────────────────────────────────

  "FenSerializer.serializeActiveColor" should "return 'w' for White" in {
    FenSerializer.serializeActiveColor(Color.White) shouldBe "w"
  }

  it should "return 'b' for Black" in {
    FenSerializer.serializeActiveColor(Color.Black) shouldBe "b"
  }

  // ── Castling rights ─────────────────────────────────────────────────────────

  "FenSerializer.serializeCastlingRights" should "return 'KQkq' for full rights" in {
    FenSerializer.serializeCastlingRights(CastlingRights.full) shouldBe "KQkq"
  }

  it should "return '-' when no rights are available" in {
    FenSerializer.serializeCastlingRights(CastlingRights.none) shouldBe "-"
  }

  it should "return 'KQkq' in standard order for all combinations" in {
    FenSerializer.serializeCastlingRights(CastlingRights(true, false, true, false)) shouldBe "Kk"
    FenSerializer.serializeCastlingRights(CastlingRights(false, true, false, true)) shouldBe "Qq"
    FenSerializer.serializeCastlingRights(CastlingRights(true, true, false, false)) shouldBe "KQ"
    FenSerializer.serializeCastlingRights(CastlingRights(false, false, true, true)) shouldBe "kq"
    FenSerializer.serializeCastlingRights(CastlingRights(true, false, false, false)) shouldBe "K"
    FenSerializer.serializeCastlingRights(CastlingRights(false, false, false, true)) shouldBe "q"
  }

  // ── En passant ──────────────────────────────────────────────────────────────

  "FenSerializer.serializeEnPassant" should "return '-' when no en passant is active" in {
    FenSerializer.serializeEnPassant(None) shouldBe "-"
  }

  it should "return the target square notation when en passant is active" in {
    val target = pos(4, 2) // e3
    val capturable = pos(4, 3) // e4 (the pawn that moved)
    val ep = EnPassantState(
      targetSquare = target,
      capturablePawnSquare = capturable,
      pawnColor = Color.White
    )
    FenSerializer.serializeEnPassant(Some(ep)) shouldBe target.toString
  }

  // ── Clock fields ────────────────────────────────────────────────────────────

  "FenSerializer.serialize" should "write halfmoveClock as field 4" in {
    val state = baseState(halfmoveClock = 7)
    FenSerializer.serialize(state).split(' ')(4) shouldBe "7"
  }

  it should "write fullmoveNumber as field 5" in {
    val state = baseState(fullmoveNumber = 42)
    FenSerializer.serialize(state).split(' ')(5) shouldBe "42"
  }

  it should "round-trip clock fields from a parsed FEN" in {
    val fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 12 34"
    val state = FenImporter
      .importNotation(FenParser.parse(fen).value, chess.notation.api.ImportTarget.PositionTarget)
      .value
      .asInstanceOf[chess.notation.api.ImportResult.PositionImportResult[GameState]]
      .data
    val exported = FenSerializer.serialize(state).split(' ')
    exported(4) shouldBe "12"
    exported(5) shouldBe "34"
  }

  // ── FEN round-trip ──────────────────────────────────────────────────────────

  it should "round-trip the initial position through parse → import → serialize" in {
    val state = FenImporter
      .importNotation(
        FenParser.parse(InitialFen).value,
        chess.notation.api.ImportTarget.PositionTarget
      )
      .value
      .asInstanceOf[chess.notation.api.ImportResult.PositionImportResult[GameState]]
      .data
    FenSerializer.serialize(state) shouldBe InitialFen
  }

  it should "round-trip after-e4 position (with en passant target)" in {
    val afterE4 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
    val state = FenImporter
      .importNotation(
        FenParser.parse(afterE4).value,
        chess.notation.api.ImportTarget.PositionTarget
      )
      .value
      .asInstanceOf[chess.notation.api.ImportResult.PositionImportResult[GameState]]
      .data
    FenSerializer.serialize(state) shouldBe afterE4
  }

  // ── exportNotation: format rejection ───────────────────────────────────────

  "FenSerializer.exportNotation" should "return Right(ExportResult) for NotationFormat.FEN" in {
    val result = FenSerializer.exportNotation(freshState, NotationFormat.FEN)
    result.value.format shouldBe NotationFormat.FEN
    result.value.text should not be empty
  }

  it should "return Left(UnsupportedExportFormat) for NotationFormat.PGN" in {
    val result = FenSerializer.exportNotation(freshState, NotationFormat.PGN)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  it should "return Left(UnsupportedExportFormat) for NotationFormat.JSON" in {
    val result = FenSerializer.exportNotation(freshState, NotationFormat.JSON)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  it should "include the rejected format in the UnsupportedExportFormat failure" in {
    val result = FenSerializer.exportNotation(freshState, NotationFormat.PGN)
    result.left.value
      .asInstanceOf[ExportFailure.UnsupportedExportFormat]
      .format shouldBe NotationFormat.PGN
  }

  // ── FenNotationFacade.parse: non-FEN format rejection ──────────────────────

  "FenNotationFacade.parse" should "succeed for NotationFormat.FEN" in {
    val result = FenNotationFacade.parse(NotationFormat.FEN, InitialFen)
    result.value should not be null
  }

  it should "return Left(StructuralError) for NotationFormat.PGN" in {
    import chess.notation.api.ParseFailure
    val result = FenNotationFacade.parse(NotationFormat.PGN, "1. e4 e5 *")
    result.left.value shouldBe a[ParseFailure.StructuralError]
    result.left.value.message should include("PGN")
  }

  it should "return Left(StructuralError) for NotationFormat.JSON" in {
    import chess.notation.api.ParseFailure
    val result = FenNotationFacade.parse(NotationFormat.JSON, "{}")
    result.left.value shouldBe a[ParseFailure.StructuralError]
    result.left.value.message should include("JSON")
  }

  // ── FenNotationFacade.executeExport ─────────────────────────────────────────

  "FenNotationFacade.executeExport" should "succeed for NotationFormat.FEN and return non-empty text" in {
    val result = FenNotationFacade.executeExport(freshState, NotationFormat.FEN)
    result.value.text should not be empty
    result.value.format shouldBe NotationFormat.FEN
  }

  it should "return the standard starting-position FEN for a fresh game" in {
    val result = FenNotationFacade.executeExport(freshState, NotationFormat.FEN)
    result.value.text shouldBe InitialFen
  }

  it should "return Left(UnsupportedExportFormat) for NotationFormat.PGN" in {
    val result = FenNotationFacade.executeExport(freshState, NotationFormat.PGN)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }

  it should "return Left(UnsupportedExportFormat) for NotationFormat.JSON" in {
    val result = FenNotationFacade.executeExport(freshState, NotationFormat.JSON)
    result.left.value shouldBe a[ExportFailure.UnsupportedExportFormat]
  }
