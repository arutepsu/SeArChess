package chess.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.{EitherValues, OptionValues}
import chess.application.ApplicationError.*
import chess.domain.error.DomainError
import chess.domain.model.*
import chess.domain.event.DomainEvent
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import chess.application.ChessCommand.*

class GameStateCommandServiceSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues:

  private val a1 = Position.from(0, 0).value
  private val a2 = Position.from(0, 1).value
  private val whitePawn = Piece(Color.White, PieceType.Pawn)
  private val blackPawn = Piece(Color.Black, PieceType.Pawn)

  // ── createNewGame ──────────────────────────────────────────────────────────

  "GameStateCommandService.createNewGame" should "start with White to move" in {
    GameStateCommandService.createNewGame().currentPlayer shouldBe Color.White
  }

  it should "start with the standard initial board" in {
    GameStateCommandService.createNewGame().board shouldBe Board.initial
  }

  it should "start with an empty move history" in {
    GameStateCommandService.createNewGame().moveHistory shouldBe Nil
  }

  it should "start with status Ongoing(false)" in {
    GameStateCommandService.createNewGame().status shouldBe GameStatus.Ongoing(false)
  }

  // ── applyMove: success ─────────────────────────────────────────────────────

  "GameStateCommandService.applyMove" should "update the board when the current player moves their own piece" in {
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = GameStateCommandService.applyMove(state, Move(a1, a2)).value
    result.board.pieceAt(a2) shouldBe Some(whitePawn)
    result.board.pieceAt(a1) shouldBe None
  }

  it should "switch the current player after a successful move" in {
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = GameStateCommandService.applyMove(state, Move(a1, a2)).value
    result.currentPlayer shouldBe Color.Black
  }

  it should "append the move to history after a successful move" in {
    val move = Move(a1, a2)
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = GameStateCommandService.applyMove(state, move).value
    result.moveHistory shouldBe List(move)
  }

  it should "evaluate status for the next player after a successful move" in {
    // white pawn a1→a2, black king at e8 — result: black's turn, not in check, has moves
    val state = GameStateCommandService
      .createNewGame()
      .copy(board =
        Board.empty
          .place(a1, whitePawn)
          .place(Position.fromAlgebraic("e8").value, Piece(Color.Black, PieceType.King))
      )
    val result = GameStateCommandService.applyMove(state, Move(a1, a2)).value
    result.status shouldBe GameStatus.Ongoing(false)
  }

  // ── applyMove: turn enforcement ────────────────────────────────────────────

  it should "fail with NotPlayersTurn when moving a piece belonging to the other player" in {
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty.place(a1, blackPawn))
    GameStateCommandService.applyMove(state, Move(a1, a2)).left.value shouldBe NotPlayersTurn
  }

  it should "leave the state unchanged after a NotPlayersTurn failure" in {
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty.place(a1, blackPawn))
    GameStateCommandService.applyMove(state, Move(a1, a2))
    state.currentPlayer shouldBe Color.White
    state.moveHistory shouldBe Nil
  }

  // ── applyMove: domain failure ──────────────────────────────────────────────

  it should "wrap a domain error as DomainFailure when the source square is empty" in {
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty)
    val result = GameStateCommandService.applyMove(state, Move(a1, a2))
    result.left.value shouldBe a[DomainFailure]
    result.left.value.asInstanceOf[DomainFailure].error shouldBe a[DomainError.EmptySourceSquare]
  }

  it should "leave the state unchanged after a DomainFailure" in {
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty)
    GameStateCommandService.applyMove(state, Move(a1, a2))
    state.currentPlayer shouldBe Color.White
    state.moveHistory shouldBe Nil
  }

  // ── handleCommand ──────────────────────────────────────────────────────────

  "GameStateCommandService.handleCommand" should "reset state on NewGame" in {
    val dirty = GameState(
      Board.empty.place(a1, whitePawn),
      Color.Black,
      List(Move(a1, a2)),
      GameStatus.Ongoing(false)
    )
    val result = GameStateCommandService.handleCommand(dirty, NewGame).value
    result.currentPlayer shouldBe Color.White
    result.board shouldBe Board.initial
    result.moveHistory shouldBe Nil
  }

  it should "delegate MakeMove to applyMove" in {
    val state = GameStateCommandService.createNewGame().copy(board = Board.empty.place(a1, whitePawn))
    val result = GameStateCommandService.handleCommand(state, MakeMove(Move(a1, a2))).value
    result.board.pieceAt(a2) shouldBe Some(whitePawn)
    result.currentPlayer shouldBe Color.Black
  }

  // ── applyMove: inline promotion workflow ──────────────────────────────────

  it should "fail with MissingPromotionChoice when a pawn reaches the last rank without specifying a promotion piece" in {
    val a7 = Position.from(0, 6).value
    val a8 = Position.from(0, 7).value
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = Board.empty.place(a7, Piece(Color.White, PieceType.Pawn))
      )
    val result = GameStateCommandService.applyMove(state, Move(a7, a8))
    result.left.value shouldBe DomainFailure(DomainError.MissingPromotionChoice)
  }

  it should "apply inline promotion when a pawn reaches the last rank with a promotion piece" in {
    val a7 = Position.from(0, 6).value
    val a8 = Position.from(0, 7).value
    val e8 = Position.fromAlgebraic("e8").value
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = Board.empty
          .place(a7, Piece(Color.White, PieceType.Pawn))
          .place(e8, Piece(Color.Black, PieceType.King))
      )
    val result = GameStateCommandService.applyMove(state, Move(a7, a8, Some(PieceType.Queen))).value
    result.board.pieceAt(a8) shouldBe Some(Piece(Color.White, PieceType.Queen))
    result.currentPlayer shouldBe Color.Black
    result.moveHistory shouldBe List(Move(a7, a8, Some(PieceType.Queen)))
  }

  // ── castling rights ────────────────────────────────────────────────────────

  "GameStateCommandService.createNewGame" should "start with full castling rights" in {
    GameStateCommandService.createNewGame().castlingRights shouldBe CastlingRights.full
  }

  "GameStateCommandService.applyMove" should "clear white king-side and queen-side rights after the white king moves" in {
    val e1 = Position.fromAlgebraic("e1").value
    val f1 = Position.fromAlgebraic("f1").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state =
      GameStateCommandService.createNewGame().copy(board = board, castlingRights = CastlingRights.full)
    val result = GameStateCommandService.applyMove(state, Move(e1, f1)).value
    result.castlingRights.whiteKingSide shouldBe false
    result.castlingRights.whiteQueenSide shouldBe false
    result.castlingRights.blackKingSide shouldBe true
    result.castlingRights.blackQueenSide shouldBe true
  }

  it should "apply white king-side castling and place king on g1, rook on f1" in {
    val e1 = Position.fromAlgebraic("e1").value
    val g1 = Position.fromAlgebraic("g1").value
    val f1 = Position.fromAlgebraic("f1").value
    val h1 = Position.fromAlgebraic("h1").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(h1, Piece(Color.White, PieceType.Rook))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state =
      GameStateCommandService.createNewGame().copy(board = board, castlingRights = CastlingRights.full)
    val result = GameStateCommandService.applyMove(state, Move(e1, g1)).value
    result.board.pieceAt(g1) shouldBe Some(Piece(Color.White, PieceType.King))
    result.board.pieceAt(f1) shouldBe Some(Piece(Color.White, PieceType.Rook))
    result.board.pieceAt(e1) shouldBe None
    result.board.pieceAt(h1) shouldBe None
    result.currentPlayer shouldBe Color.Black
  }

  it should "apply black queen-side castling and place king on c8, rook on d8" in {
    val e8 = Position.fromAlgebraic("e8").value
    val c8 = Position.fromAlgebraic("c8").value
    val d8 = Position.fromAlgebraic("d8").value
    val a8 = Position.fromAlgebraic("a8").value
    val e1 = Position.fromAlgebraic("e1").value
    val board = Board.empty
      .place(e8, Piece(Color.Black, PieceType.King))
      .place(a8, Piece(Color.Black, PieceType.Rook))
      .place(e1, Piece(Color.White, PieceType.King))
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = board,
        currentPlayer = Color.Black,
        castlingRights = CastlingRights.full
      )
    val result = GameStateCommandService.applyMove(state, Move(e8, c8)).value
    result.board.pieceAt(c8) shouldBe Some(Piece(Color.Black, PieceType.King))
    result.board.pieceAt(d8) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    result.board.pieceAt(e8) shouldBe None
    result.board.pieceAt(a8) shouldBe None
    result.currentPlayer shouldBe Color.White
  }

  // ── en passant: state lifecycle ────────────────────────────────────────────

  "GameStateCommandService.applyMove" should "create enPassantState after a white double pawn advance" in {
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e2, Piece(Color.White, PieceType.Pawn))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state = GameStateCommandService.createNewGame().copy(board = board)
    val result = GameStateCommandService.applyMove(state, Move(e2, e4)).value
    result.enPassantState shouldBe Some(EnPassantState(e3, e4, Color.White))
  }

  it should "create enPassantState after a black double pawn advance" in {
    val d7 = Position.fromAlgebraic("d7").value
    val d5 = Position.fromAlgebraic("d5").value
    val d6 = Position.fromAlgebraic("d6").value
    val e1 = Position.fromAlgebraic("e1").value
    val board = Board.empty
      .place(d7, Piece(Color.Black, PieceType.Pawn))
      .place(e1, Piece(Color.White, PieceType.King))
    val state = GameStateCommandService.createNewGame().copy(board = board, currentPlayer = Color.Black)
    val result = GameStateCommandService.applyMove(state, Move(d7, d5)).value
    result.enPassantState shouldBe Some(EnPassantState(d6, d5, Color.Black))
  }

  it should "clear enPassantState after any non-qualifying move" in {
    // State carries active en passant but White makes a king move instead
    val e1 = Position.fromAlgebraic("e1").value
    val f1 = Position.fromAlgebraic("f1").value
    val e8 = Position.fromAlgebraic("e8").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val ep = EnPassantState(e3, e4, Color.Black)
    val state = GameStateCommandService.createNewGame().copy(board = board, enPassantState = Some(ep))
    val result = GameStateCommandService.applyMove(state, Move(e1, f1)).value
    result.enPassantState shouldBe None
  }

  it should "clear enPassantState after a successful en passant capture" in {
    val d4 = Position.fromAlgebraic("d4").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(d4, Piece(Color.Black, PieceType.Pawn))
      .place(e4, Piece(Color.White, PieceType.Pawn))
      .place(e8, Piece(Color.Black, PieceType.King))
    val ep = EnPassantState(e3, e4, Color.White)
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = board,
        currentPlayer = Color.Black,
        enPassantState = Some(ep)
      )
    val result = GameStateCommandService.applyMove(state, Move(d4, e3)).value
    result.enPassantState shouldBe None
  }

  it should "clear enPassantState after castling" in {
    val e1 = Position.fromAlgebraic("e1").value
    val g1 = Position.fromAlgebraic("g1").value
    val h1 = Position.fromAlgebraic("h1").value
    val e8 = Position.fromAlgebraic("e8").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(h1, Piece(Color.White, PieceType.Rook))
      .place(e8, Piece(Color.Black, PieceType.King))
    val ep = EnPassantState(e3, e4, Color.Black)
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = board,
        castlingRights = CastlingRights.full,
        enPassantState = Some(ep)
      )
    val result = GameStateCommandService.applyMove(state, Move(e1, g1)).value
    result.enPassantState shouldBe None
  }

  it should "clear enPassantState after an inline promotion move" in {
    val a7 = Position.fromAlgebraic("a7").value
    val a8 = Position.fromAlgebraic("a8").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val e8 = Position.fromAlgebraic("e8").value
    val ep = EnPassantState(e3, e4, Color.Black)
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = Board.empty
          .place(a7, Piece(Color.White, PieceType.Pawn))
          .place(e8, Piece(Color.Black, PieceType.King)),
        enPassantState = Some(ep)
      )
    val result = GameStateCommandService.applyMove(state, Move(a7, a8, Some(PieceType.Queen))).value
    result.enPassantState shouldBe None
  }

  it should "evaluate status considering en passant availability after a double pawn advance" in {
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e2, Piece(Color.White, PieceType.Pawn))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state = GameStateCommandService.createNewGame().copy(board = board)
    val result = GameStateCommandService.applyMove(state, Move(e2, e4)).value
    result.status shouldBe GameStatus.Ongoing(false)
  }

  it should "switch back to White after Black makes a successful move" in {
    val blackPawnPos = Position.fromAlgebraic("e7").value
    val blackTarget = Position.fromAlgebraic("e6").value
    val whiteKing = Position.fromAlgebraic("e1").value
    val blackKing = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(a1, whitePawn)
      .place(blackPawnPos, blackPawn)
      .place(whiteKing, Piece(Color.White, PieceType.King))
      .place(blackKing, Piece(Color.Black, PieceType.King))
    val afterWhite = GameStateCommandService
      .applyMove(
        GameState(board, Color.White, Nil, GameStatus.Ongoing(false)),
        Move(a1, a2)
      )
      .value
    afterWhite.currentPlayer shouldBe Color.Black
    val afterBlack = GameStateCommandService.applyMove(afterWhite, Move(blackPawnPos, blackTarget)).value
    afterBlack.currentPlayer shouldBe Color.White
  }

  // ── legalTargetsFrom ──────────────────────────────────────────────────────

  "GameStateCommandService.legalTargetsFrom" should "return the two forward squares for a pawn on its starting rank" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e3 = Position.fromAlgebraic("e3").value
    val e4 = Position.fromAlgebraic("e4").value
    GameStateCommandService.legalTargetsFrom(state, e2) shouldBe Set(e3, e4)
  }

  it should "return an empty set for an empty square" in {
    val state = GameStateCommandService.createNewGame()
    val e4 = Position.fromAlgebraic("e4").value
    GameStateCommandService.legalTargetsFrom(state, e4) shouldBe empty
  }

  it should "return an empty set for an opponent's piece when it is not their turn" in {
    val state = GameStateCommandService.createNewGame() // White to move
    val e7 = Position.fromAlgebraic("e7").value
    GameStateCommandService.legalTargetsFrom(state, e7) shouldBe empty
  }

  // ── applyMoveWithEvents ────────────────────────────────────────────────────

  "GameStateCommandService.applyMoveWithEvents" should "return Right(ApplyMoveResult) for a legal move" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    GameStateCommandService.applyMoveWithEvents(state, Move(e2, e4)).isRight shouldBe true
  }

  it should "return Left for an illegal move" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e5 = Position.fromAlgebraic("e5").value // pawn can't jump three squares
    GameStateCommandService.applyMoveWithEvents(state, Move(e2, e5)).isLeft shouldBe true
  }

  it should "update state fields the same way applyMove does" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(e2, e4)).value
    result.state.currentPlayer shouldBe Color.Black
    result.state.moveHistory shouldBe List(Move(e2, e4))
    result.state.status shouldBe GameStatus.Ongoing(false)
  }

  it should "always emit MoveApplied as the first event" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(e2, e4)).value
    result.events.head shouldBe DomainEvent.MoveApplied(Move(e2, e4))
  }

  it should "emit MoveApplied and MoveExecuted (and nothing else) for a quiet move that leaves status Ongoing" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val move = Move(e2, e4)
    val result = GameStateCommandService.applyMoveWithEvents(state, move).value
    result.events.count(_.isInstanceOf[DomainEvent.MoveApplied]) shouldBe 1
    result.events.count(_.isInstanceOf[DomainEvent.MoveExecuted]) shouldBe 1
    result.events should have size 2
  }

  it should "emit PieceCaptured when a piece is taken at the destination square" in {
    val a5 = Position.fromAlgebraic("a5").value
    val h1 = Position.fromAlgebraic("h1").value
    val h8 = Position.fromAlgebraic("h8").value
    val board = Board.empty
      .place(a1, Piece(Color.White, PieceType.Rook))
      .place(a5, Piece(Color.Black, PieceType.Pawn))
      .place(h1, Piece(Color.White, PieceType.King))
      .place(h8, Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(a1, a5)).value
    result.events should contain(DomainEvent.PieceCaptured(Piece(Color.Black, PieceType.Pawn), a5))
  }

  it should "emit CheckDeclared and GameStatusChanged when the move gives check" in {
    // White queen d1→d7 puts black king on d8 in check (clear d-file, no other pieces).
    val d1 = Position.fromAlgebraic("d1").value
    val d7 = Position.fromAlgebraic("d7").value
    val d8 = Position.fromAlgebraic("d8").value
    val board = Board.empty
      .place(d1, Piece(Color.White, PieceType.Queen))
      .place(d8, Piece(Color.Black, PieceType.King))
      .place(a1, Piece(Color.White, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(d1, d7)).value
    result.events should contain(DomainEvent.CheckDeclared(Color.Black))
    result.events should contain(DomainEvent.GameStatusChanged(GameStatus.Ongoing(true)))
    result.state.status shouldBe GameStatus.Ongoing(true)
  }

  it should "return Left(MissingPromotionChoice) when a pawn reaches the last rank with no promotion piece" in {
    val e7 = Position.fromAlgebraic("e7").value
    val e8 = Position.fromAlgebraic("e8").value
    val h8 = Position.fromAlgebraic("h8").value
    val board = Board.empty
      .place(e7, Piece(Color.White, PieceType.Pawn))
      .place(a1, Piece(Color.White, PieceType.King))
      .place(h8, Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    GameStateCommandService.applyMoveWithEvents(state, Move(e7, e8)).left.value shouldBe
      DomainError.MissingPromotionChoice
  }

  it should "emit MoveApplied, Promoted, and MoveExecuted for an inline promotion move" in {
    val e7 = Position.fromAlgebraic("e7").value
    val e8 = Position.fromAlgebraic("e8").value
    val h8 = Position.fromAlgebraic("h8").value
    val board = Board.empty
      .place(e7, Piece(Color.White, PieceType.Pawn))
      .place(a1, Piece(Color.White, PieceType.King))
      .place(h8, Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(e7, e8, Some(PieceType.Queen))).value
    result.events should contain(DomainEvent.MoveApplied(Move(e7, e8, Some(PieceType.Queen))))
    result.events should contain(DomainEvent.Promoted(e8, Color.White, PieceType.Queen))
    result.events.last shouldBe a[DomainEvent.MoveExecuted]
    result.state.board.pieceAt(e8) shouldBe Some(Piece(Color.White, PieceType.Queen))
  }

  // ── MoveExecuted ───────────────────────────────────────────────────────────

  "GameStateCommandService.applyMoveWithEvents" should "emit MoveExecuted as the last event for a normal move" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(e2, e4)).value
    result.events.last shouldBe a[DomainEvent.MoveExecuted]
  }

  it should "populate core MoveExecuted fields correctly for a normal pawn advance" in {
    val state = GameStateCommandService.createNewGame()
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(e2, e4)).value
    val evt = result.events.collectFirst { case e: DomainEvent.MoveExecuted => e }.value
    evt.piece shouldBe PieceType.Pawn
    evt.color shouldBe Color.White
    evt.from shouldBe e2
    evt.to shouldBe e4
    evt.capture shouldBe None
    evt.promotion shouldBe None
    evt.check shouldBe false
    evt.checkmate shouldBe false
    evt.stalemate shouldBe false
  }

  it should "populate capture in MoveExecuted when a piece is taken" in {
    val a5 = Position.fromAlgebraic("a5").value
    val h1 = Position.fromAlgebraic("h1").value
    val h8 = Position.fromAlgebraic("h8").value
    val board = Board.empty
      .place(a1, Piece(Color.White, PieceType.Rook))
      .place(a5, Piece(Color.Black, PieceType.Pawn))
      .place(h1, Piece(Color.White, PieceType.King))
      .place(h8, Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(a1, a5)).value
    val evt = result.events.collectFirst { case e: DomainEvent.MoveExecuted => e }.value
    evt.capture shouldBe defined
    evt.capture.map(_.piece) shouldBe Some(PieceType.Pawn)
    evt.capture.map(_.color) shouldBe Some(Color.Black)
  }

  it should "populate promotion and MoveExecuted for an inline promotion move" in {
    val a7 = Position.fromAlgebraic("a7").value
    val a8 = Position.fromAlgebraic("a8").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(a7, Piece(Color.White, PieceType.Pawn))
      .place(a1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(a7, a8, Some(PieceType.Queen))).value
    val evt = result.events.collectFirst { case e: DomainEvent.MoveExecuted => e }.value
    evt.piece shouldBe PieceType.Pawn
    evt.color shouldBe Color.White
    evt.promotion shouldBe Some(PieceType.Queen)
    evt.from shouldBe a7
    evt.to shouldBe a8
  }

  it should "emit CheckDeclared before GameStatusChanged when promotion results in check" in {
    // White pawn on b7 promotes to queen on b8, putting black king on d8 in check.
    val b7 = Position.fromAlgebraic("b7").value
    val b8 = Position.fromAlgebraic("b8").value
    val d8 = Position.fromAlgebraic("d8").value
    val board = Board.empty
      .place(b7, Piece(Color.White, PieceType.Pawn))
      .place(d8, Piece(Color.Black, PieceType.King))
      .place(a1, Piece(Color.White, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(b7, b8, Some(PieceType.Queen))).value
    result.state.status shouldBe GameStatus.Ongoing(true)
    val checkIdx = result.events.indexWhere(_.isInstanceOf[DomainEvent.CheckDeclared])
    val statusIdx = result.events.indexWhere(_.isInstanceOf[DomainEvent.GameStatusChanged])
    checkIdx should be >= 0
    statusIdx should be >= 0
    checkIdx should be < statusIdx
  }

  // ── MoveExecuted enrichment: castling, en passant, promotion capture ────────

  "GameStateCommandService.applyMoveWithEvents" should "set castling=KingSide in MoveExecuted for white kingside castling" in {
    val e1 = Position.fromAlgebraic("e1").value
    val g1 = Position.fromAlgebraic("g1").value
    val h1 = Position.fromAlgebraic("h1").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(h1, Piece(Color.White, PieceType.Rook))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state =
      GameStateCommandService.createNewGame().copy(board = board, castlingRights = CastlingRights.full)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(e1, g1)).value
    val evt = result.events.collectFirst { case e: DomainEvent.MoveExecuted => e }.value
    import chess.domain.event.CastlingSide
    evt.castling shouldBe Some(CastlingSide.KingSide)
  }

  it should "set castling=QueenSide in MoveExecuted for black queenside castling" in {
    val e8 = Position.fromAlgebraic("e8").value
    val c8 = Position.fromAlgebraic("c8").value
    val a8 = Position.fromAlgebraic("a8").value
    val e1 = Position.fromAlgebraic("e1").value
    val board = Board.empty
      .place(e8, Piece(Color.Black, PieceType.King))
      .place(a8, Piece(Color.Black, PieceType.Rook))
      .place(e1, Piece(Color.White, PieceType.King))
    val state = GameStateCommandService
      .createNewGame()
      .copy(board = board, currentPlayer = Color.Black, castlingRights = CastlingRights.full)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(e8, c8)).value
    val evt = result.events.collectFirst { case e: DomainEvent.MoveExecuted => e }.value
    import chess.domain.event.CastlingSide
    evt.castling shouldBe Some(CastlingSide.QueenSide)
  }

  it should "emit PieceCaptured and set MoveExecuted.enPassant=true for an en passant capture" in {
    val d4 = Position.fromAlgebraic("d4").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(d4, Piece(Color.Black, PieceType.Pawn))
      .place(e4, Piece(Color.White, PieceType.Pawn))
      .place(e8, Piece(Color.Black, PieceType.King))
    val ep = EnPassantState(e3, e4, Color.White)
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = board,
        currentPlayer = Color.Black,
        enPassantState = Some(ep)
      )
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(d4, e3)).value
    // PieceCaptured must be emitted at the captured pawn's actual square (e4, not e3)
    result.events should contain(DomainEvent.PieceCaptured(Piece(Color.White, PieceType.Pawn), e4))
    val evt = result.events.collectFirst { case e: DomainEvent.MoveExecuted => e }.value
    evt.enPassant shouldBe true
    evt.capture shouldBe defined
    evt.capture.map(_.piece) shouldBe Some(PieceType.Pawn)
    evt.capture.map(_.color) shouldBe Some(Color.White)
    evt.capture.map(_.at) shouldBe Some(e4) // captured pawn's square, not move.to (e3)
  }

  it should "set capture in MoveExecuted when a pawn promotes via a diagonal capture" in {
    // White pawn on b7 captures black rook on c8 and promotes to Queen.
    val b7 = Position.fromAlgebraic("b7").value
    val c8 = Position.fromAlgebraic("c8").value
    val e1 = Position.fromAlgebraic("e1").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(b7, Piece(Color.White, PieceType.Pawn))
      .place(c8, Piece(Color.Black, PieceType.Rook))
      .place(e1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(b7, c8, Some(PieceType.Queen))).value
    val evt = result.events.collectFirst { case e: DomainEvent.MoveExecuted => e }.value
    evt.promotion shouldBe Some(PieceType.Queen)
    evt.capture shouldBe defined
    evt.capture.map(_.piece) shouldBe Some(PieceType.Rook)
    evt.capture.map(_.color) shouldBe Some(Color.Black)
    evt.capture.map(_.at) shouldBe Some(c8)
  }

  it should "not emit GameStatusChanged when status does not change after a promotion" in {
    // Promote to Queen on a8 — black king on e5 is not on rank 8, file a, or the a8-h1 diagonal,
    // so no check results and status remains Ongoing both before and after.
    val a7 = Position.fromAlgebraic("a7").value
    val a8 = Position.fromAlgebraic("a8").value
    val e5 = Position.fromAlgebraic("e5").value
    val board = Board.empty
      .place(a7, Piece(Color.White, PieceType.Pawn))
      .place(a1, Piece(Color.White, PieceType.King))
      .place(e5, Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateCommandService.applyMoveWithEvents(state, Move(a7, a8, Some(PieceType.Queen))).value
    result.state.status shouldBe GameStatus.Ongoing(false)
    result.events.exists(_.isInstanceOf[DomainEvent.GameStatusChanged]) shouldBe false
  }

  // ── halfmove clock ─────────────────────────────────────────────────────────

  "GameStateCommandService.applyMove" should "reset halfmoveClock on a pawn move" in {
    val state = GameStateCommandService.createNewGame().copy(halfmoveClock = 5)
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    GameStateCommandService.applyMove(state, Move(e2, e4)).value.halfmoveClock shouldBe 0
  }

  it should "increment halfmoveClock on a non-pawn non-capture move" in {
    val e1 = Position.fromAlgebraic("e1").value
    val f1 = Position.fromAlgebraic("f1").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state = GameStateCommandService.createNewGame().copy(board = board, halfmoveClock = 3)
    GameStateCommandService.applyMove(state, Move(e1, f1)).value.halfmoveClock shouldBe 4
  }

  it should "increment fullmoveNumber after Black moves" in {
    val e1 = Position.fromAlgebraic("e1").value
    val e8 = Position.fromAlgebraic("e8").value
    val d7 = Position.fromAlgebraic("d7").value
    val d6 = Position.fromAlgebraic("d6").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
      .place(d7, Piece(Color.Black, PieceType.Pawn))
    val state = GameStateCommandService
      .createNewGame()
      .copy(
        board = board,
        currentPlayer = Color.Black,
        fullmoveNumber = 5
      )
    GameStateCommandService.applyMove(state, Move(d7, d6)).value.fullmoveNumber shouldBe 6
  }

  it should "not increment fullmoveNumber after White moves" in {
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val state = GameStateCommandService.createNewGame().copy(fullmoveNumber = 5)
    GameStateCommandService.applyMove(state, Move(e2, e4)).value.fullmoveNumber shouldBe 5
  }
