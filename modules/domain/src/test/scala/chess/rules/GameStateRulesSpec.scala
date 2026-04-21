package chess.domain.rules

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.error.DomainError
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, GameState, GameStateFactory}

class GameStateRulesSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def algPos(alg: String): Position =
    Position.fromAlgebraic(alg).getOrElse(throw AssertionError(s"Bad algebraic: $alg"))

  private def freshState: GameState = GameStateFactory.initial()

  // ── legalTargetsFrom ───────────────────────────────────────────────────────

  "GameStateRules.legalTargetsFrom" should "delegate to LegalMoveGenerator for the initial position" in {
    val state = freshState
    val e2 = algPos("e2")
    val result = GameStateRules.legalTargetsFrom(state, e2)
    result shouldBe Set(algPos("e3"), algPos("e4"))
  }

  it should "return empty when there is no current-player piece at the position" in {
    val state = freshState
    GameStateRules.legalTargetsFrom(state, algPos("e4")) shouldBe empty
  }

  // ── legalMovesFrom ─────────────────────────────────────────────────────────

  "GameStateRules.legalMovesFrom" should "return Move objects with destinations for a starting pawn" in {
    val state = freshState
    val moves = GameStateRules.legalMovesFrom(state, algPos("e2"))
    moves.map(_.to) shouldBe Set(algPos("e3"), algPos("e4"))
    moves.foreach(_.promotion shouldBe None)
  }

  it should "expand a promotion pawn to four Move objects" in {
    val a7 = algPos("a7")
    val a8 = algPos("a8")
    val board = Board.empty
      .place(a7, Piece(Color.White, PieceType.Pawn))
      .place(algPos("a1"), Piece(Color.White, PieceType.King))
      .place(algPos("e8"), Piece(Color.Black, PieceType.King))
    val state = freshState.copy(board = board)
    val moves = GameStateRules.legalMovesFrom(state, a7)
    val promoMoves = moves.filter(_.to == a8)
    promoMoves should have size 4
    promoMoves.flatMap(_.promotion) shouldBe Set(
      PieceType.Queen,
      PieceType.Rook,
      PieceType.Bishop,
      PieceType.Knight
    )
  }

  it should "return empty for a square with no current-player piece" in {
    val state = freshState
    GameStateRules.legalMovesFrom(state, algPos("e4")) shouldBe empty
  }

  // ── legalMoves ─────────────────────────────────────────────────────────────

  "GameStateRules.legalMoves" should "return 20 legal moves for the initial position" in {
    // 8 pawns × 2 squares + 2 knights × 2 squares = 20
    val state = freshState
    GameStateRules.legalMoves(state) should have size 20
  }

  it should "return an empty set when the current player is in checkmate" in {
    // Scholar's mate — black king in checkmate
    val wK = Piece(Color.White, PieceType.King)
    val wQ = Piece(Color.White, PieceType.Queen)
    val wR = Piece(Color.White, PieceType.Rook)
    val bK = Piece(Color.Black, PieceType.King)
    val board = Board.empty
      .place(algPos("a8"), bK)
      .place(algPos("a1"), wR)
      .place(algPos("b6"), wQ)
      .place(algPos("h1"), wK)
    val state = GameState(board, Color.Black, Nil, GameStatus.Ongoing(true), CastlingRights.none)
    GameStateRules.legalMoves(state) shouldBe empty
  }

  // ── evaluateStatus ─────────────────────────────────────────────────────────

  "GameStateRules.evaluateStatus" should "return Ongoing(false) for the initial position" in {
    val state = freshState
    GameStateRules.evaluateStatus(state) shouldBe GameStatus.Ongoing(false)
  }

  // ── applyMove ──────────────────────────────────────────────────────────────

  "GameStateRules.applyMove" should "return the next GameState after a legal move" in {
    val state = freshState
    val e2 = algPos("e2")
    val e4 = algPos("e4")
    val result = GameStateRules.applyMove(state, Move(e2, e4))
    result.isRight shouldBe true
    val next = result.value
    next.currentPlayer shouldBe Color.Black
    next.board.pieceAt(e4) shouldBe Some(Piece(Color.White, PieceType.Pawn))
    next.board.pieceAt(e2) shouldBe None
  }

  it should "set en passant state after a two-square white pawn advance" in {
    val state = freshState
    val result = GameStateRules.applyMove(state, Move(algPos("e2"), algPos("e4")))
    result.value.enPassantState.isDefined shouldBe true
    result.value.enPassantState
      .getOrElse(fail("expected enPassantState"))
      .targetSquare shouldBe algPos("e3")
  }

  it should "set en passant state after a two-square black pawn advance" in {
    val state0 = freshState
    val state1 = GameStateRules.applyMove(state0, Move(algPos("e2"), algPos("e4"))).value
    val state2 = GameStateRules.applyMove(state1, Move(algPos("e7"), algPos("e5"))).value
    state2.enPassantState.isDefined shouldBe true
    state2.enPassantState.getOrElse(fail("expected enPassantState")).targetSquare shouldBe algPos(
      "e6"
    )
  }

  it should "clear en passant state after a non-double-pawn move" in {
    val state0 = freshState
    val state1 = GameStateRules.applyMove(state0, Move(algPos("e2"), algPos("e4"))).value
    // black makes a single-step pawn move; en passant opportunity disappears
    val state2 = GameStateRules.applyMove(state1, Move(algPos("e7"), algPos("e6"))).value
    state2.enPassantState shouldBe None
  }

  it should "reset halfmove clock on a pawn move" in {
    val state = freshState.copy(halfmoveClock = 5)
    val result = GameStateRules.applyMove(state, Move(algPos("e2"), algPos("e4")))
    result.value.halfmoveClock shouldBe 0
  }

  it should "increment halfmove clock on a non-pawn non-capture move" in {
    // Move white knight; no capture
    val state = freshState.copy(halfmoveClock = 3)
    val result = GameStateRules.applyMove(state, Move(algPos("g1"), algPos("f3")))
    result.value.halfmoveClock shouldBe 4
  }

  it should "increment fullmove number after black moves" in {
    val state0 = freshState // White, move 1
    val state1 =
      GameStateRules.applyMove(state0, Move(algPos("e2"), algPos("e4"))).value // Black, move 1
    state1.fullmoveNumber shouldBe 1
    val state2 =
      GameStateRules.applyMove(state1, Move(algPos("e7"), algPos("e5"))).value // White, move 2
    state2.fullmoveNumber shouldBe 2
  }

  it should "return Left(MissingPromotionChoice) when a pawn reaches the last rank with no promotion piece" in {
    val a7 = algPos("a7")
    val a8 = algPos("a8")
    val board = Board.empty
      .place(a7, Piece(Color.White, PieceType.Pawn))
      .place(algPos("a1"), Piece(Color.White, PieceType.King))
      .place(algPos("e8"), Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    GameStateRules
      .applyMove(state, Move(a7, a8))
      .left
      .value shouldBe DomainError.MissingPromotionChoice
  }

  it should "apply an inline promotion move correctly" in {
    val a7 = algPos("a7")
    val a8 = algPos("a8")
    val board = Board.empty
      .place(a7, Piece(Color.White, PieceType.Pawn))
      .place(algPos("a1"), Piece(Color.White, PieceType.King))
      .place(algPos("e8"), Piece(Color.Black, PieceType.King))
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    val result = GameStateRules.applyMove(state, Move(a7, a8, Some(PieceType.Queen)))
    result.value.board.pieceAt(a8) shouldBe Some(Piece(Color.White, PieceType.Queen))
  }
