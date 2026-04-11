package chess.adapter.rest.mapper

import chess.application.ChessService
import chess.domain.model.{Color, DrawReason, GameStatus}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues

class GameMapperSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val initialState = ChessService.createNewGame()
  private val gameIdStr    = "test-game-id"

  "GameMapper.toGameResponse" should "set gameId from the supplied string" in {
    GameMapper.toGameResponse(gameIdStr, initialState).gameId shouldBe gameIdStr
  }

  it should "report White as current player for the initial state" in {
    GameMapper.toGameResponse(gameIdStr, initialState).currentPlayer shouldBe "White"
  }

  it should "report status Ongoing for the initial state" in {
    GameMapper.toGameResponse(gameIdStr, initialState).status shouldBe "Ongoing"
  }

  it should "report inCheck false for the initial state" in {
    GameMapper.toGameResponse(gameIdStr, initialState).inCheck shouldBe false
  }

  it should "have no winner for the initial state" in {
    GameMapper.toGameResponse(gameIdStr, initialState).winner shouldBe None
  }

  it should "have no drawReason for the initial state" in {
    GameMapper.toGameResponse(gameIdStr, initialState).drawReason shouldBe None
  }

  it should "include all 32 pieces for the initial board" in {
    GameMapper.toGameResponse(gameIdStr, initialState).board should have size 32
  }

  it should "include correct algebraic squares for initial pieces" in {
    val squares = GameMapper.toGameResponse(gameIdStr, initialState).board.map(_.square).toSet
    squares should contain("e1")  // White king
    squares should contain("e8")  // Black king
    squares should contain("e2")  // White pawn
    squares should contain("e7")  // Black pawn
  }

  it should "report correct fullmoveNumber from state" in {
    val state = initialState.copy(fullmoveNumber = 7)
    GameMapper.toGameResponse(gameIdStr, state).fullmoveNumber shouldBe 7
  }

  it should "report correct halfmoveClock from state" in {
    val state = initialState.copy(halfmoveClock = 3)
    GameMapper.toGameResponse(gameIdStr, state).halfmoveClock shouldBe 3
  }

  // ── GameStatus variants ────────────────────────────────────────────────────

  it should "map Ongoing(inCheck=true) to inCheck=true, status Ongoing" in {
    val state = initialState.copy(status = GameStatus.Ongoing(true))
    val resp  = GameMapper.toGameResponse(gameIdStr, state)
    resp.status  shouldBe "Ongoing"
    resp.inCheck shouldBe true
    resp.winner  shouldBe None
  }

  it should "map Checkmate(White) to status Checkmate, winner White" in {
    val state = initialState.copy(status = GameStatus.Checkmate(Color.White))
    val resp  = GameMapper.toGameResponse(gameIdStr, state)
    resp.status  shouldBe "Checkmate"
    resp.winner  shouldBe Some("White")
    resp.inCheck shouldBe false
  }

  it should "map Checkmate(Black) to status Checkmate, winner Black" in {
    val state = initialState.copy(status = GameStatus.Checkmate(Color.Black))
    GameMapper.toGameResponse(gameIdStr, state).winner shouldBe Some("Black")
  }

  it should "map Draw(Stalemate) to status Draw, drawReason Stalemate" in {
    val state = initialState.copy(status = GameStatus.Draw(DrawReason.Stalemate))
    val resp  = GameMapper.toGameResponse(gameIdStr, state)
    resp.status     shouldBe "Draw"
    resp.drawReason shouldBe Some("Stalemate")
    resp.winner     shouldBe None
    resp.inCheck    shouldBe false
  }
