package chess.domain.rules.evaluation

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.model.*
import chess.domain.state.{CastlingRights, EnPassantState}

class GameStatusEvaluatorSpec extends AnyFlatSpec with Matchers with EitherValues:

  private def at(alg: String): Position = Position.fromAlgebraic(alg).value

  private def boardWith(placements: (String, Piece)*): Board =
    placements.foldLeft(Board.empty) { case (b, (sq, p)) => b.place(at(sq), p) }

  private val wK = Piece(Color.White, PieceType.King)
  private val wQ = Piece(Color.White, PieceType.Queen)
  private val wR = Piece(Color.White, PieceType.Rook)
  private val wP = Piece(Color.White, PieceType.Pawn)
  private val bK = Piece(Color.Black, PieceType.King)
  private val bR = Piece(Color.Black, PieceType.Rook)

  // ── Ongoing ────────────────────────────────────────────────────────────────

  "GameStatusEvaluator.evaluate" should "return Ongoing(false) when not in check and has legal moves" in {
    // white king + pawn, black king far away — white not in check, pawn can move
    val board = boardWith("e1" -> wK, "e2" -> wP, "e8" -> bK)
    GameStatusEvaluator.evaluate(board, Color.White) shouldBe GameStatus.Ongoing(false)
  }

  // ── Check ──────────────────────────────────────────────────────────────────

  it should "return Ongoing(true) when king is in check but has an escape square" in {
    // black rook on e8 checks white king on e1; king can escape to d1 or f1
    val board = boardWith("e1" -> wK, "e8" -> bR)
    GameStatusEvaluator.evaluate(board, Color.White) shouldBe GameStatus.Ongoing(true)
  }

  // ── Checkmate ──────────────────────────────────────────────────────────────

  it should "return Checkmate(White) when black king is in check with no legal move" in {
    // black king a8, white rook a1 (check on a-file), white queen b6 (covers a7, b7, b8)
    // escape squares: a7 (rook+queen), b7 (queen), b8 (queen) — all covered
    val board = boardWith("a8" -> bK, "a1" -> wR, "b6" -> wQ, "h1" -> wK)
    GameStatusEvaluator.evaluate(board, Color.Black) shouldBe GameStatus.Checkmate(Color.White)
  }

  // ── Stalemate ──────────────────────────────────────────────────────────────

  it should "return Draw(Stalemate) when king is not in check but has no legal move" in {
    // white king f6, white queen g6, black king h8
    // h8 not in check; escape squares: h7 (king f6), g7 (king f6 + queen g6), g8 (queen g6 via g-file)
    val board = boardWith("h8" -> bK, "f6" -> wK, "g6" -> wQ)
    GameStatusEvaluator.evaluate(board, Color.Black) shouldBe GameStatus.Draw(DrawReason.Stalemate)
  }

  // ── hasAnyLegalMove ────────────────────────────────────────────────────────

  "GameStatusEvaluator.hasAnyLegalMove" should "return true when a legal move exists" in {
    val board = boardWith("e1" -> wK, "e2" -> wP)
    GameStatusEvaluator.hasAnyLegalMove(board, Color.White) shouldBe true
  }

  it should "return false in a checkmate position" in {
    val board = boardWith("a8" -> bK, "a1" -> wR, "b6" -> wQ, "h1" -> wK)
    GameStatusEvaluator.hasAnyLegalMove(board, Color.Black) shouldBe false
  }

  it should "return false in a stalemate position" in {
    val board = boardWith("h8" -> bK, "f6" -> wK, "g6" -> wQ)
    GameStatusEvaluator.hasAnyLegalMove(board, Color.Black) shouldBe false
  }

  // ── evaluate(state) overload ───────────────────────────────────────────────

  "GameStatusEvaluator.evaluate(state)" should "return the same result as the board/color overload" in {
    import chess.domain.state.{CastlingRights, EnPassantState, GameState}
    val board = boardWith("e1" -> wK, "e2" -> wP, "e8" -> bK)
    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false), CastlingRights.none)
    GameStatusEvaluator.evaluate(state) shouldBe GameStatusEvaluator.evaluate(board, Color.White)
  }

  it should "include en passant moves when checking for legal moves" in {
    // White pawn e5 can capture en passant on d6 (Black pawn just advanced d7→d5).
    // d6 is empty — without ep state, e5→d6 is an illegal diagonal-to-empty move.
    // With ep state, it is legal.
    val bP = Piece(Color.Black, PieceType.Pawn)
    val ep = EnPassantState(at("d6"), at("d5"), Color.Black)
    val board =
      boardWith("e5" -> wP, "d5" -> bP, "e1" -> Piece(Color.White, PieceType.King), "e8" -> bK)
    GameStatusEvaluator.hasAnyLegalMove(
      board,
      Color.White,
      CastlingRights.none,
      Some(ep)
    ) shouldBe true
  }
