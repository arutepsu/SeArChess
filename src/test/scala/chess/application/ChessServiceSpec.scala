package chess.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.application.ApplicationError.*
import chess.domain.error.DomainError
import chess.domain.model.*

class ChessServiceSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val a1        = Position.from(0, 0).value
  private val a2        = Position.from(0, 1).value
  private val whitePawn = Piece(Color.White, PieceType.Pawn)
  private val blackPawn = Piece(Color.Black, PieceType.Pawn)

  // ── createNewGame ──────────────────────────────────────────────────────────

  "ChessService.createNewGame" should "start with White to move" in {
    ChessService.createNewGame().currentPlayer shouldBe Color.White
  }

  it should "start with the standard initial board" in {
    ChessService.createNewGame().board shouldBe Board.initial
  }

  it should "start with an empty move history" in {
    ChessService.createNewGame().moveHistory shouldBe Nil
  }

  // ── applyMove: success ─────────────────────────────────────────────────────

  "ChessService.applyMove" should "update the board when the current player moves their own piece" in {
    val state  = ChessService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = ChessService.applyMove(state, Move(a1, a2)).value
    result.board.pieceAt(a2) shouldBe Some(whitePawn)
    result.board.pieceAt(a1) shouldBe None
  }

  it should "switch the current player after a successful move" in {
    val state  = ChessService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = ChessService.applyMove(state, Move(a1, a2)).value
    result.currentPlayer shouldBe Color.Black
  }

  it should "append the move to history after a successful move" in {
    val move   = Move(a1, a2)
    val state  = ChessService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = ChessService.applyMove(state, move).value
    result.moveHistory shouldBe List(move)
  }

  // ── applyMove: turn enforcement ────────────────────────────────────────────

  it should "fail with NotPlayersTurn when moving a piece belonging to the other player" in {
    val state  = ChessService.createNewGame().copy(board = Board.empty.place(a1, blackPawn))
    ChessService.applyMove(state, Move(a1, a2)).left.value shouldBe NotPlayersTurn
  }

  it should "leave the state unchanged after a NotPlayersTurn failure" in {
    val state = ChessService.createNewGame().copy(board = Board.empty.place(a1, blackPawn))
    ChessService.applyMove(state, Move(a1, a2))
    state.currentPlayer shouldBe Color.White
    state.moveHistory   shouldBe Nil
  }

  // ── applyMove: domain failure ──────────────────────────────────────────────

  it should "wrap a domain error as DomainFailure when the source square is empty" in {
    val state  = ChessService.createNewGame()
    val result = ChessService.applyMove(state, Move(a1, a2))
    result.left.value shouldBe a[DomainFailure]
    result.left.value.asInstanceOf[DomainFailure].error shouldBe a[DomainError.EmptySourceSquare]
  }

  it should "leave the state unchanged after a DomainFailure" in {
    val state = ChessService.createNewGame()
    ChessService.applyMove(state, Move(a1, a2))
    state.currentPlayer shouldBe Color.White
    state.moveHistory   shouldBe Nil
  }

  // ── handleCommand ──────────────────────────────────────────────────────────

  "ChessService.handleCommand" should "reset state on NewGame" in {
    val dirty  = GameState(Board.empty.place(a1, whitePawn), Color.Black, List(Move(a1, a2)))
    val result = ChessService.handleCommand(dirty, NewGame).value
    result.currentPlayer shouldBe Color.White
    result.board         shouldBe Board.initial
    result.moveHistory   shouldBe Nil
  }

  it should "delegate MakeMove to applyMove" in {
    val state  = ChessService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = ChessService.handleCommand(state, MakeMove(Move(a1, a2))).value
    result.board.pieceAt(a2) shouldBe Some(whitePawn)
    result.currentPlayer     shouldBe Color.Black
  }
