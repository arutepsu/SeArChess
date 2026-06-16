package chess.arena.bots.heuristic

import chess.arena.core.BotProfile
import chess.arena.events.BotFamily
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.{CastlingRights, GameState, GameStateFactory}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MaterialGreedyBotSpec extends AnyFlatSpec with Matchers:

  private val profile = BotProfile("material-greedy", BotFamily.Heuristic, "material-greedy", "none", "none")

  private def pos(s: String): Position =
    Position.fromAlgebraic(s) match
      case Right(p) => p
      case Left(_)  => fail(s"Invalid position: $s")

  /** Board with White Queen at d4, Black Pawn at c5 (value 1), Black Rook at e5 (value 5).
    * White Queen can capture c5 or e5. MaterialGreedyBot must prefer e5 (Rook = 5).
    * Kings present to satisfy the domain rules engine.
    */
  private def stateWithTwoCaptures(): GameState =
    val board = Board.empty
      .place(pos("a1"), Piece(Color.White, PieceType.King))
      .place(pos("h8"), Piece(Color.Black, PieceType.King))
      .place(pos("d4"), Piece(Color.White, PieceType.Queen))
      .place(pos("c5"), Piece(Color.Black, PieceType.Pawn))
      .place(pos("e5"), Piece(Color.Black, PieceType.Rook))
    GameState(
      board          = board,
      currentPlayer  = Color.White,
      moveHistory    = Nil,
      status         = GameStatus.Ongoing(inCheck = false),
      castlingRights = CastlingRights.none,
      enPassantState = None
    )

  "MaterialGreedyBot" should "select a legal move from the initial position (no captures)" in {
    val bot   = MaterialGreedyBot(profile, seed = Some(1L))
    val state = GameStateFactory.initial()
    val move  = bot.selectMove(state)
    GameStateRules.legalMoves(state) should contain(move)
  }

  it should "always capture the Rook (value 5) over the Pawn (value 1) when both are available" in {
    val state     = stateWithTwoCaptures()
    val rookCapture = Move(pos("d4"), pos("e5"))
    GameStateRules.legalMoves(state) should contain(rookCapture)

    (1L to 20L).foreach { seed =>
      val move = MaterialGreedyBot(profile, seed = Some(seed)).selectMove(state)
      move shouldBe rookCapture
    }
  }
