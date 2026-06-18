package chess.adapter.http4s.mapper

import chess.application.GameStateCommandService
import chess.application.query.game.GameView
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Color, DrawReason, GameStatus}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues

class GameMapperSpec extends AnyFlatSpec with Matchers with OptionValues:

  private val initialState = GameStateCommandService.createNewGame()
  private val fixedGameId = GameId(
    java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")
  )
  private val initialView = GameView.fromState(fixedGameId, initialState)

  "GameMapper.toGameResponse" should "set gameId from the view's GameId" in {
    GameMapper.toGameResponse(initialView).gameId shouldBe fixedGameId.value.toString
  }

  it should "report White as current player for the initial state" in {
    GameMapper.toGameResponse(initialView).currentPlayer shouldBe "White"
  }

  it should "report status Ongoing for the initial state" in {
    GameMapper.toGameResponse(initialView).status shouldBe "Ongoing"
  }

  it should "report inCheck false for the initial state" in {
    GameMapper.toGameResponse(initialView).inCheck shouldBe false
  }

  it should "have no winner for the initial state" in {
    GameMapper.toGameResponse(initialView).winner shouldBe None
  }

  it should "have no drawReason for the initial state" in {
    GameMapper.toGameResponse(initialView).drawReason shouldBe None
  }

  it should "include all 32 pieces for the initial board" in {
    GameMapper.toGameResponse(initialView).board should have size 32
  }

  it should "include correct algebraic squares for initial pieces" in {
    val squares = GameMapper.toGameResponse(initialView).board.map(_.square).toSet
    squares should contain("e1") // White king
    squares should contain("e8") // Black king
    squares should contain("e2") // White pawn
    squares should contain("e7") // Black pawn
  }

  it should "report correct fullmoveNumber from state" in {
    val view = GameView.fromState(fixedGameId, initialState.copy(fullmoveNumber = 7))
    GameMapper.toGameResponse(view).fullmoveNumber shouldBe 7
  }

  it should "report correct halfmoveClock from state" in {
    val view = GameView.fromState(fixedGameId, initialState.copy(halfmoveClock = 3))
    GameMapper.toGameResponse(view).halfmoveClock shouldBe 3
  }

  // ── GameStatus variants ────────────────────────────────────────────────────

  it should "map Ongoing(inCheck=true) to inCheck=true, status Ongoing" in {
    val view = GameView.fromState(fixedGameId, initialState.copy(status = GameStatus.Ongoing(true)))
    val resp = GameMapper.toGameResponse(view)
    resp.status shouldBe "Ongoing"
    resp.inCheck shouldBe true
    resp.winner shouldBe None
  }

  it should "map Checkmate(White) to status Checkmate, winner White" in {
    val view =
      GameView.fromState(fixedGameId, initialState.copy(status = GameStatus.Checkmate(Color.White)))
    val resp = GameMapper.toGameResponse(view)
    resp.status shouldBe "Checkmate"
    resp.winner shouldBe Some("White")
    resp.inCheck shouldBe false
  }

  it should "map Checkmate(Black) to status Checkmate, winner Black" in {
    val view =
      GameView.fromState(fixedGameId, initialState.copy(status = GameStatus.Checkmate(Color.Black)))
    GameMapper.toGameResponse(view).winner shouldBe Some("Black")
  }

  it should "map Draw(Stalemate) to status Draw, drawReason Stalemate" in {
    val view = GameView.fromState(
      fixedGameId,
      initialState.copy(status = GameStatus.Draw(DrawReason.Stalemate))
    )
    val resp = GameMapper.toGameResponse(view)
    resp.status shouldBe "Draw"
    resp.drawReason shouldBe Some("Stalemate")
    resp.winner shouldBe None
    resp.inCheck shouldBe false
  }
