package chess.notation.fen

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.notation.api.ParsedNotation
import chess.domain.model.{Color, DrawReason, GameStatus, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState}
import org.scalatest.OptionValues

/** Unit tests for [[FenToGameStateMapper]].
  *
  * Uses [[FenParser.parse]] to obtain valid [[chess.notation.api.FenData]] values without
  * hand-constructing them.
  */
class FenToGameStateMapperSpec
    extends AnyFlatSpec
    with Matchers
    with EitherValues
    with OptionValues:

  private def data(fen: String) =
    FenParser.parse(fen).value.asInstanceOf[ParsedNotation.ParsedFen].data

  private def map(fen: String) = FenToGameStateMapper.map(data(fen))

  private def pos(file: Int, rank: Int) =
    Position.from(file, rank).getOrElse(throw AssertionError(s"Bad pos: $file $rank"))

  // ── Board contents ───────────────────────────────────────────────────────────

  "FenToGameStateMapper" should "populate the board for the initial position" in {
    val state = map("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1")
    // White back rank
    state.board.pieceAt(pos(0, 0)) shouldBe Some(Piece(Color.White, PieceType.Rook))
    state.board.pieceAt(pos(1, 0)) shouldBe Some(Piece(Color.White, PieceType.Knight))
    state.board.pieceAt(pos(2, 0)) shouldBe Some(Piece(Color.White, PieceType.Bishop))
    state.board.pieceAt(pos(3, 0)) shouldBe Some(Piece(Color.White, PieceType.Queen))
    state.board.pieceAt(pos(4, 0)) shouldBe Some(Piece(Color.White, PieceType.King))
    state.board.pieceAt(pos(5, 0)) shouldBe Some(Piece(Color.White, PieceType.Bishop))
    state.board.pieceAt(pos(6, 0)) shouldBe Some(Piece(Color.White, PieceType.Knight))
    state.board.pieceAt(pos(7, 0)) shouldBe Some(Piece(Color.White, PieceType.Rook))
    // White pawns
    (0 to 7).foreach { f =>
      state.board.pieceAt(pos(f, 1)) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    }
    // Empty middle ranks
    state.board.pieceAt(pos(0, 2)) shouldBe None
    state.board.pieceAt(pos(4, 4)) shouldBe None
    // Black pawns
    (0 to 7).foreach { f =>
      state.board.pieceAt(pos(f, 6)) shouldBe Some(Piece(Color.Black, PieceType.Pawn))
    }
    // Black back rank
    state.board.pieceAt(pos(0, 7)) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    state.board.pieceAt(pos(4, 7)) shouldBe Some(Piece(Color.Black, PieceType.King))
    state.board.pieceAt(pos(7, 7)) shouldBe Some(Piece(Color.Black, PieceType.Rook))
  }

  it should "place all six black piece types correctly" in {
    // rank 8 = "kqrbnp11": black king, queen, rook, bishop, knight, pawn, 2 empty
    val state = map("kqrbnp11/8/8/8/8/8/8/K7 w - - 0 1")
    state.board.pieceAt(pos(0, 7)) shouldBe Some(Piece(Color.Black, PieceType.King))
    state.board.pieceAt(pos(1, 7)) shouldBe Some(Piece(Color.Black, PieceType.Queen))
    state.board.pieceAt(pos(2, 7)) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    state.board.pieceAt(pos(3, 7)) shouldBe Some(Piece(Color.Black, PieceType.Bishop))
    state.board.pieceAt(pos(4, 7)) shouldBe Some(Piece(Color.Black, PieceType.Knight))
    state.board.pieceAt(pos(5, 7)) shouldBe Some(Piece(Color.Black, PieceType.Pawn))
  }

  // ── Active color ─────────────────────────────────────────────────────────────

  it should "map 'w' to White as the current player" in {
    map("8/7k/8/8/8/8/8/4K3 w - - 0 1").currentPlayer shouldBe Color.White
  }

  it should "map 'b' to Black as the current player" in {
    map("8/7k/8/8/8/8/8/4K3 b - - 0 1").currentPlayer shouldBe Color.Black
  }

  // ── Castling rights ──────────────────────────────────────────────────────────

  it should "map all four castling rights as true from 'KQkq'" in {
    val cr = map("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").castlingRights
    cr shouldBe CastlingRights.full
  }

  it should "map '-' to no castling rights" in {
    val cr = map("8/7k/8/8/8/8/8/4K3 w - - 0 1").castlingRights
    cr shouldBe CastlingRights.none
  }

  it should "map partial castling 'Kq'" in {
    val cr = map("r3k2r/8/8/8/8/8/8/4K2R w Kq - 0 1").castlingRights
    cr.whiteKingSide shouldBe true
    cr.whiteQueenSide shouldBe false
    cr.blackKingSide shouldBe false
    cr.blackQueenSide shouldBe true
  }

  // ── En passant ────────────────────────────────────────────────────────────────

  it should "produce None when en passant is '-'" in {
    map("8/7k/8/8/8/8/8/4K3 w - - 0 1").enPassantState shouldBe None
  }

  it should "produce a correct EnPassantState when Black is to move (White advanced)" in {
    // After 1.e4: target = e3, White's pawn is on e4
    val state = map("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1")
    val ep = state.enPassantState.value
    ep.targetSquare shouldBe pos(4, 2) // e3
    ep.capturablePawnSquare shouldBe pos(4, 3) // e4
    ep.pawnColor shouldBe Color.White
  }

  it should "produce a correct EnPassantState when White is to move (Black advanced)" in {
    // After 1...d5: target = d6, Black's pawn is on d5
    val state = map("rnbqkbnr/ppp1pppp/8/3p4/8/8/PPPPPPPP/RNBQKBNR w KQkq d6 0 1")
    val ep = state.enPassantState.value
    ep.targetSquare shouldBe pos(3, 5) // d6
    ep.capturablePawnSquare shouldBe pos(3, 4) // d5
    ep.pawnColor shouldBe Color.Black
  }

  // ── Mandatory snapshot fields ────────────────────────────────────────────────

  it should "always produce an empty move history" in {
    map("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").moveHistory shouldBe Nil
  }

  // ── Clock fields ─────────────────────────────────────────────────────────────

  it should "thread halfmoveClock from FEN data into GameState" in {
    map("8/7k/8/8/8/8/8/4K3 w - - 5 10").halfmoveClock shouldBe 5
  }

  it should "thread fullmoveNumber from FEN data into GameState" in {
    map("8/7k/8/8/8/8/8/4K3 w - - 5 10").fullmoveNumber shouldBe 10
  }

  it should "set halfmoveClock to 0 for the initial position" in {
    map("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").halfmoveClock shouldBe 0
  }

  it should "set fullmoveNumber to 1 for the initial position" in {
    map("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").fullmoveNumber shouldBe 1
  }

  // ── Status derivation ────────────────────────────────────────────────────────

  it should "derive Ongoing status for the initial position" in {
    map("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1").status shouldBe GameStatus
      .Ongoing(false)
  }

  it should "derive Check status for a position where the king is in check" in {
    // White queen on e5 gives check to black king on e8
    val state = map("4k3/8/8/4Q3/8/8/8/4K3 b - - 0 1")
    state.status shouldBe GameStatus.Ongoing(true)
  }
