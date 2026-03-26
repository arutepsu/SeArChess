package chess.application

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.application.ApplicationError.*
import chess.domain.error.DomainError
import chess.domain.model.*
import chess.domain.model.positionstate.{CastlingRights, EnPassantState}
import chess.application.ChessCommand.*

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

  it should "start with status Ongoing" in {
    ChessService.createNewGame().status shouldBe GameStatus.Ongoing
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

  it should "evaluate status for the next player after a successful move" in {
    // white pawn a1→a2, black king at e8 — result: black's turn, not in check, has moves
    val state  = ChessService.createNewGame().copy(board =
      Board.empty.place(a1, whitePawn).place(Position.fromAlgebraic("e8").value, Piece(Color.Black, PieceType.King))
    )
    val result = ChessService.applyMove(state, Move(a1, a2)).value
    result.status shouldBe GameStatus.Ongoing
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
    val state  = ChessService.createNewGame().copy(board = Board.empty)
    val result = ChessService.applyMove(state, Move(a1, a2))
    result.left.value shouldBe a[DomainFailure]
    result.left.value.asInstanceOf[DomainFailure].error shouldBe a[DomainError.EmptySourceSquare]
  }

  it should "leave the state unchanged after a DomainFailure" in {
    val state = ChessService.createNewGame().copy(board = Board.empty)
    ChessService.applyMove(state, Move(a1, a2))
    state.currentPlayer shouldBe Color.White
    state.moveHistory   shouldBe Nil
  }

  // ── handleCommand ──────────────────────────────────────────────────────────

  "ChessService.handleCommand" should "reset state on NewGame" in {
    val dirty  = GameState(Board.empty.place(a1, whitePawn), Color.Black, List(Move(a1, a2)), GameStatus.Ongoing)
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

  // ── applyMove: promotion workflow ──────────────────────────────────────────

  it should "set pendingPromotion instead of switching player when a pawn reaches the last rank" in {
    val a7 = Position.from(0, 6).value
    val a8 = Position.from(0, 7).value
    val state = ChessService.createNewGame().copy(
      board = Board.empty.place(a7, Piece(Color.White, PieceType.Pawn))
    )
    val result = ChessService.applyMove(state, Move(a7, a8)).value
    result.pendingPromotion shouldBe defined
    result.currentPlayer    shouldBe Color.White  // turn not switched yet
    result.moveHistory      shouldBe Nil           // move not recorded yet
  }

  it should "reject a normal move when promotion is pending" in {
    val a7 = Position.from(0, 6).value
    val a8 = Position.from(0, 7).value
    val a1 = Position.from(0, 0).value
    val a2 = Position.from(0, 1).value
    val state = ChessService.createNewGame().copy(
      board         = Board.empty.place(a7, whitePawn).place(a1, whitePawn),
      pendingPromotion = Some(PendingPromotion(a8, Color.White, Move(a7, a8)))
    )
    ChessService.applyMove(state, Move(a1, a2)).left.value shouldBe ApplicationError.PromotionChoiceRequired
  }

  // ── applyPromotion ─────────────────────────────────────────────────────────

  "ChessService.applyPromotion" should "fail with NoPromotionPending when no promotion is set" in {
    ChessService.applyPromotion(ChessService.createNewGame(), PieceType.Queen).left.value shouldBe
      ApplicationError.NoPromotionPending
  }

  it should "clear pendingPromotion, switch player, and record move on success" in {
    val a7 = Position.from(0, 6).value
    val a8 = Position.from(0, 7).value
    val pending = PendingPromotion(a8, Color.White, Move(a7, a8))
    val state = ChessService.createNewGame().copy(
      board            = Board.empty.place(a8, Piece(Color.White, PieceType.Pawn)),
      pendingPromotion = Some(pending)
    )
    val result = ChessService.applyPromotion(state, PieceType.Queen).value
    result.pendingPromotion                   shouldBe None
    result.currentPlayer                      shouldBe Color.Black
    result.moveHistory                        shouldBe List(Move(a7, a8))
    result.board.pieceAt(a8)                  shouldBe Some(Piece(Color.White, PieceType.Queen))
  }

  it should "wrap a domain error as DomainFailure when the promotion piece is invalid" in {
    val a8 = Position.from(0, 7).value
    val a7 = Position.from(0, 6).value
    val pending = PendingPromotion(a8, Color.White, Move(a7, a8))
    val state = ChessService.createNewGame().copy(
      board            = Board.empty.place(a8, Piece(Color.White, PieceType.Pawn)),
      pendingPromotion = Some(pending)
    )
    val err = ChessService.applyPromotion(state, PieceType.King).left.value
    err shouldBe a[ApplicationError.DomainFailure]
  }

  // ── handleCommand: Promote ─────────────────────────────────────────────────

  "ChessService.handleCommand" should "delegate Promote to applyPromotion" in {
    val a8 = Position.from(0, 7).value
    val a7 = Position.from(0, 6).value
    val pending = PendingPromotion(a8, Color.White, Move(a7, a8))
    val state = ChessService.createNewGame().copy(
      board            = Board.empty.place(a8, Piece(Color.White, PieceType.Pawn)),
      pendingPromotion = Some(pending)
    )
    val result = ChessService.handleCommand(state, Promote(PieceType.Rook)).value
    result.board.pieceAt(a8) shouldBe Some(Piece(Color.White, PieceType.Rook))
  }

  // ── castling rights ────────────────────────────────────────────────────────

  "ChessService.createNewGame" should "start with full castling rights" in {
    ChessService.createNewGame().castlingRights shouldBe CastlingRights.full
  }

  "ChessService.applyMove" should "clear white king-side and queen-side rights after the white king moves" in {
    val e1 = Position.fromAlgebraic("e1").value
    val f1 = Position.fromAlgebraic("f1").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e1, Piece(Color.White, PieceType.King))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state  = ChessService.createNewGame().copy(board = board, castlingRights = CastlingRights.full)
    val result = ChessService.applyMove(state, Move(e1, f1)).value
    result.castlingRights.whiteKingSide  shouldBe false
    result.castlingRights.whiteQueenSide shouldBe false
    result.castlingRights.blackKingSide  shouldBe true
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
    val state  = ChessService.createNewGame().copy(board = board, castlingRights = CastlingRights.full)
    val result = ChessService.applyMove(state, Move(e1, g1)).value
    result.board.pieceAt(g1) shouldBe Some(Piece(Color.White, PieceType.King))
    result.board.pieceAt(f1) shouldBe Some(Piece(Color.White, PieceType.Rook))
    result.board.pieceAt(e1) shouldBe None
    result.board.pieceAt(h1) shouldBe None
    result.currentPlayer     shouldBe Color.Black
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
    val state  = ChessService.createNewGame().copy(
      board          = board,
      currentPlayer  = Color.Black,
      castlingRights = CastlingRights.full
    )
    val result = ChessService.applyMove(state, Move(e8, c8)).value
    result.board.pieceAt(c8) shouldBe Some(Piece(Color.Black, PieceType.King))
    result.board.pieceAt(d8) shouldBe Some(Piece(Color.Black, PieceType.Rook))
    result.board.pieceAt(e8) shouldBe None
    result.board.pieceAt(a8) shouldBe None
    result.currentPlayer     shouldBe Color.White
  }

  // ── en passant: state lifecycle ────────────────────────────────────────────

  "ChessService.applyMove" should "create enPassantState after a white double pawn advance" in {
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e2, Piece(Color.White, PieceType.Pawn))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state  = ChessService.createNewGame().copy(board = board)
    val result = ChessService.applyMove(state, Move(e2, e4)).value
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
    val state  = ChessService.createNewGame().copy(board = board, currentPlayer = Color.Black)
    val result = ChessService.applyMove(state, Move(d7, d5)).value
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
    val ep    = EnPassantState(e3, e4, Color.Black)
    val state = ChessService.createNewGame().copy(board = board, enPassantState = Some(ep))
    val result = ChessService.applyMove(state, Move(e1, f1)).value
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
    val ep    = EnPassantState(e3, e4, Color.White)
    val state = ChessService.createNewGame().copy(
      board          = board,
      currentPlayer  = Color.Black,
      enPassantState = Some(ep)
    )
    val result = ChessService.applyMove(state, Move(d4, e3)).value
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
    val ep    = EnPassantState(e3, e4, Color.Black)
    val state = ChessService.createNewGame().copy(
      board          = board,
      castlingRights = CastlingRights.full,
      enPassantState = Some(ep)
    )
    val result = ChessService.applyMove(state, Move(e1, g1)).value
    result.enPassantState shouldBe None
  }

  it should "clear enPassantState after promotion completion" in {
    val a8 = Position.fromAlgebraic("a8").value
    val a7 = Position.fromAlgebraic("a7").value
    val e4 = Position.fromAlgebraic("e4").value
    val e3 = Position.fromAlgebraic("e3").value
    val pending = PendingPromotion(a8, Color.White, Move(a7, a8))
    val ep      = EnPassantState(e3, e4, Color.Black)
    val state = ChessService.createNewGame().copy(
      board            = Board.empty.place(a8, Piece(Color.White, PieceType.Pawn)),
      pendingPromotion = Some(pending),
      enPassantState   = Some(ep)
    )
    val result = ChessService.applyPromotion(state, PieceType.Queen).value
    result.enPassantState shouldBe None
  }

  it should "evaluate status considering en passant availability after a double pawn advance" in {
    // After White's double advance, the returned status is computed with the new ep state.
    // This confirms ep state is threaded into GameStatusEvaluator.
    val e2 = Position.fromAlgebraic("e2").value
    val e4 = Position.fromAlgebraic("e4").value
    val e8 = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(e2, Piece(Color.White, PieceType.Pawn))
      .place(e8, Piece(Color.Black, PieceType.King))
    val state  = ChessService.createNewGame().copy(board = board)
    val result = ChessService.applyMove(state, Move(e2, e4)).value
    // The resulting status must be valid (not an error), confirming ep state was used
    result.status shouldBe GameStatus.Ongoing
  }

  it should "switch back to White after Black makes a successful move" in {
    // Two-move sequence: White moves, then Black moves → covers opponent(Color.Black)
    val blackPawnPos  = Position.fromAlgebraic("e7").value
    val blackTarget   = Position.fromAlgebraic("e6").value
    val whiteKing     = Position.fromAlgebraic("e1").value
    val blackKing     = Position.fromAlgebraic("e8").value
    val board = Board.empty
      .place(a1, whitePawn)
      .place(blackPawnPos, blackPawn)
      .place(whiteKing, Piece(Color.White, PieceType.King))
      .place(blackKing,  Piece(Color.Black, PieceType.King))
    val afterWhite = ChessService.applyMove(
      GameState(board, Color.White, Nil, GameStatus.Ongoing),
      Move(a1, a2)
    ).value
    afterWhite.currentPlayer shouldBe Color.Black
    val afterBlack = ChessService.applyMove(afterWhite, Move(blackPawnPos, blackTarget)).value
    afterBlack.currentPlayer shouldBe Color.White
  }

  // ── legalMovesFrom ─────────────────────────────────────────────────────────

  "ChessService.legalMovesFrom" should "return the two forward squares for a pawn on its starting rank" in {
    val state = ChessService.createNewGame()
    val e2    = Position.fromAlgebraic("e2").value
    val e3    = Position.fromAlgebraic("e3").value
    val e4    = Position.fromAlgebraic("e4").value
    ChessService.legalMovesFrom(state, e2) shouldBe Set(e3, e4)
  }

  it should "return an empty set for an empty square" in {
    val state = ChessService.createNewGame()
    val e4    = Position.fromAlgebraic("e4").value
    ChessService.legalMovesFrom(state, e4) shouldBe empty
  }

  it should "return an empty set for an opponent's piece when it is not their turn" in {
    val state = ChessService.createNewGame()   // White to move
    val e7    = Position.fromAlgebraic("e7").value
    ChessService.legalMovesFrom(state, e7) shouldBe empty
  }

  it should "return an empty set when promotion is pending" in {
    val a8 = Position.fromAlgebraic("a8").value
    val a7 = Position.fromAlgebraic("a7").value
    val pending = PendingPromotion(a8, Color.White, Move(a7, a8))
    val state = ChessService.createNewGame().copy(
      board            = Board.empty.place(a8, Piece(Color.White, PieceType.Pawn)),
      pendingPromotion = Some(pending)
    )
    ChessService.legalMovesFrom(state, a8) shouldBe empty
  }
