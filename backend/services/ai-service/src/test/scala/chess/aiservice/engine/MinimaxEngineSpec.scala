package chess.aiservice.engine

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues
import chess.domain.model.*
import chess.domain.state.GameState
import chess.adapter.ai.remote.RemoteAiMoveDto

class MinimaxEngineSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val whiteKing = Piece(Color.White, PieceType.King)
  private val blackKing = Piece(Color.Black, PieceType.King)
  private val whiteQueen = Piece(Color.White, PieceType.Queen)
  private val blackQueen = Piece(Color.Black, PieceType.Queen)
  private val whiteKnight = Piece(Color.White, PieceType.Knight)

  private def pos(s: String): Position =
    Position.fromAlgebraic(s) match
      case Right(p) => p
      case Left(_) =>
        val Right(p) = Position.from(0, 0): @unchecked
        p

  "MinimaxEngine" should "prefer capturing an opponent's Queen for free" in {
    val board = Board.empty
      .place(pos("e1"), whiteKing)
      .place(pos("d1"), whiteQueen)
      .place(pos("e8"), blackKing)
      .place(pos("d4"), blackQueen)

    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false))
    
    val legalMoves = List(
      RemoteAiMoveDto("d1", "d4"), // Captures queen
      RemoteAiMoveDto("d1", "d2"), // Quiet move
      RemoteAiMoveDto("e1", "d2")  // King moves
    )

    val result = MinimaxEngine.selectBestMove(state, legalMoves, 3)
    result shouldBe Some(RemoteAiMoveDto("d1", "d4"))
  }

  it should "prefer developing a Knight to the center rather than the rim" in {
    val board = Board.empty
      .place(pos("e1"), whiteKing)
      .place(pos("b1"), whiteKnight)
      .place(pos("e8"), blackKing)

    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false))

    val legalMoves = List(
      RemoteAiMoveDto("b1", "a3"), // Na3
      RemoteAiMoveDto("b1", "c3")  // Nc3
    )

    val result = MinimaxEngine.selectBestMove(state, legalMoves, 3)
    result shouldBe Some(RemoteAiMoveDto("b1", "c3"))
  }

  it should "find checkmate in one" in {
    val board = Board.empty
      .place(pos("e6"), whiteKing)
      .place(pos("c7"), whiteQueen)
      .place(pos("e8"), blackKing)

    val state = GameState(board, Color.White, Nil, GameStatus.Ongoing(false))

    val legalMoves = List(
      RemoteAiMoveDto("c7", "f7"), // Qf7 (not mate, king escapes to d8)
      RemoteAiMoveDto("c7", "c8"), // Qc8# (checkmate)
      RemoteAiMoveDto("c7", "d7")  // Qd7+ (not mate)
    )

    val result = MinimaxEngine.selectBestMove(state, legalMoves, 3)
    result shouldBe Some(RemoteAiMoveDto("c7", "c8"))
  }
