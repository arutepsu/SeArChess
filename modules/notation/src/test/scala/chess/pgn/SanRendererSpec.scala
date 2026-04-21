package chess.notation.pgn

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.EitherValues
import chess.domain.model.{Board, Color, Move, Piece, PieceType, Position}
import chess.domain.state.{GameState, GameStateFactory}
import chess.notation.api.ExportFailure

/** Unit tests for [[SanRenderer.render]].
  *
  * SanRenderer is private[pgn]; this spec lives in the same package.
  *
  * Branches targeted:
  *   1. render - no piece at move.from => SerializationError("move.from", ...) 2. render -
  *      applyMove fails => SerializationError("move", ...) 3. pieceChar King => "K" 4. pieceChar
  *      Rook => "R" 5. disambiguate by rank => rank digit suffix 6. castling O-O-O => "O-O-O" 7.
  *      promotion to R, B, N => "=R", "=B", "=N" 8. check suffix "+" 9. checkmate suffix "#"
  */
class SanRendererSpec extends AnyFlatSpec with Matchers with EitherValues:

  // ── Helpers ───────────────────────────────────────────────────────────────

  private def pos(algebraic: String): Position =
    Position
      .fromAlgebraic(algebraic)
      .getOrElse(throw AssertionError(s"Bad algebraic: $algebraic"))

  private def mv(from: String, to: String, promo: Option[PieceType] = None): Move =
    Move(pos(from), pos(to), promo)

  private def stateAfter(tokens: String*): GameState =
    PgnReplayService
      .replayFrom(GameStateFactory.initial(), tokens.toVector)
      .getOrElse(throw AssertionError(s"Replay failed for: ${tokens.mkString(" ")}"))

  /** Minimal position with a White pawn on c7 and c8 empty, ready for a straight promotion. Kings
    * are placed so no side is in check.
    *
    * Note: the earlier sequence "dxc7" / "O-O" leaves Black's c8 bishop on c8, blocking the
    * straight push. This direct setup avoids that ambiguity.
    */
  private val promoState: GameState =
    val board = Board.empty
      .place(pos("c7"), Piece(Color.White, PieceType.Pawn))
      .place(pos("a1"), Piece(Color.White, PieceType.King))
      .place(pos("h6"), Piece(Color.Black, PieceType.King))
    GameStateFactory.initial().copy(board = board)

  // ── 1. No piece at move.from ──────────────────────────────────────────────

  "SanRenderer.render" should "return SerializationError when no piece exists at move.from" in {
    val state = GameStateFactory.initial()
    val result = SanRenderer.render(state, mv("e4", "e5"))
    val err = result.left.value.asInstanceOf[ExportFailure.SerializationError]
    err.field shouldBe "move.from"
    err.message should include("e4")
  }

  it should "include the from-square in the SerializationError message" in {
    val state = GameStateFactory.initial()
    val result = SanRenderer.render(state, mv("d5", "d6"))
    result.left.value.message should include("d5")
  }

  // ── 2. applyMove failure (MissingPromotionChoice) ─────────────────────────

  it should "return SerializationError with field 'move' when applyMove fails" in {
    val result = SanRenderer.render(promoState, mv("c7", "c8"))
    val err = result.left.value.asInstanceOf[ExportFailure.SerializationError]
    err.field shouldBe "move"
    err.message should include("c7")
  }
  // ── 3. King prefix ────────────────────────────────────────────────────────

  it should "prefix King moves with 'K'" in {
    val state = stateAfter("e4", "e5", "Nf3", "Nc6", "Be2", "Nf6", "O-O")
    val result = SanRenderer.render(state, mv("g1", "h1"))
    result.value shouldBe "Kh1"
  }

  // ── 4. Rook prefix ───────────────────────────────────────────────────────

  it should "prefix Rook moves with 'R'" in {
    val state = stateAfter("e4", "e5", "Nf3", "Nc6", "Be2", "Nf6", "O-O")
    val result = SanRenderer.render(state, mv("f1", "e1"))
    result.value shouldBe "Re1"
  }

  // ── 5. Rank disambiguation ────────────────────────────────────────────────
  // White has Ra1 (rank=0) and Ra3 (rank=2); both can reach a2.
  // The rivals share the mover's file (a-file), so file letter alone is
  // ambiguous.  Mover rank (0) differs from all rivals' ranks (2), so a
  // rank digit suffices.  Expected: "R1a2".
  //
  // Note: "a4 / a5 / Ra3" does NOT leave Ra1 intact — the a1 rook moves to
  // a3, leaving a1 empty.  A direct board setup is used instead.

  it should "use rank digit when two same-type pieces are on the same file" in {
    val board = Board.empty
      .place(pos("a1"), Piece(Color.White, PieceType.Rook))
      .place(pos("a3"), Piece(Color.White, PieceType.Rook))
      .place(pos("e1"), Piece(Color.White, PieceType.King))
      .place(pos("e8"), Piece(Color.Black, PieceType.King))
    val state = GameStateFactory.initial().copy(board = board)
    val result = SanRenderer.render(state, mv("a1", "a2"))
    result.value shouldBe "R1a2"
  }

  // ── 6. Queen-side castling ────────────────────────────────────────────────

  it should "render queen-side castling as 'O-O-O'" in {
    val state = stateAfter("d4", "d5", "Nc3", "Nc6", "Bf4", "Bf5", "Qd2", "Qd7")
    val result = SanRenderer.render(state, mv("e1", "c1"))
    result.value shouldBe "O-O-O"
  }

  it should "render king-side castling as 'O-O'" in {
    val state = stateAfter("e4", "e5", "Nf3", "d6", "Be2", "Be7")
    val result = SanRenderer.render(state, mv("e1", "g1"))
    result.value shouldBe "O-O"
  }

  // ── 7. Promotion to R / B / N ─────────────────────────────────────────────

  it should "render pawn promotion to Rook as '=R'" in {
    val result = SanRenderer.render(promoState, mv("c7", "c8", Some(PieceType.Rook)))
    result.value should include("=R")
    result.value should startWith("c8")
  }

  it should "render pawn promotion to Bishop as '=B'" in {
    val result = SanRenderer.render(promoState, mv("c7", "c8", Some(PieceType.Bishop)))
    result.value should include("=B")
  }

  it should "render pawn promotion to Knight as '=N'" in {
    val result = SanRenderer.render(promoState, mv("c7", "c8", Some(PieceType.Knight)))
    result.value should include("=N")
  }

  // ── 8. Check suffix "+" ───────────────────────────────────────────────────

  it should "append '+' for a move that gives check" in {
    val state = stateAfter("e4", "d5")
    val result = SanRenderer.render(state, mv("f1", "b5"))
    result.value shouldBe "Bb5+"
  }

  // ── 9. Checkmate suffix "#" ───────────────────────────────────────────────

  it should "append '#' for the final move of fool's mate" in {
    val state = stateAfter("f3", "e5", "g4")
    val result = SanRenderer.render(state, mv("d8", "h4"))
    result.value shouldBe "Qh4#"
  }

  // ── Piece chars: Queen, Bishop, Knight ───────────────────────────────────

  it should "prefix Queen moves with 'Q'" in {
    val state = stateAfter("d4", "d5")
    val result = SanRenderer.render(state, mv("d1", "d3"))
    result.value shouldBe "Qd3"
  }

  it should "prefix Bishop moves with 'B'" in {
    val state = stateAfter("e4", "e5")
    val result = SanRenderer.render(state, mv("f1", "c4"))
    result.value shouldBe "Bc4"
  }

  it should "prefix Knight moves with 'N'" in {
    val state = GameStateFactory.initial()
    val result = SanRenderer.render(state, mv("g1", "f3"))
    result.value shouldBe "Nf3"
  }

  // ── No suffix for quiet move ──────────────────────────────────────────────

  it should "return no suffix for a quiet move that leaves the game ongoing" in {
    val state = GameStateFactory.initial()
    val result = SanRenderer.render(state, mv("e2", "e4"))
    result.value shouldBe "e4"
  }
