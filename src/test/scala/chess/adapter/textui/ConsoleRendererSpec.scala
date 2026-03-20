package chess.adapter.textui

import chess.application.{ApplicationError, ChessService}
import chess.domain.error.DomainError
import chess.domain.model.{Color, GameStatus, Position}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConsoleRendererSpec extends AnyFlatSpec with Matchers with EitherValues:

  private val newGame = ChessService.createNewGame()
  private def pos(alg: String): Position = Position.fromAlgebraic(alg).value

  // ── Board rendering ────────────────────────────────────────────────────────

  "renderBoard" should "include rank labels 1 through 8" in {
    val board = ConsoleRenderer.renderBoard(newGame)
    (1 to 8).foreach(r => board should include(r.toString))
  }

  it should "include file labels a through h" in {
    ConsoleRenderer.renderBoard(newGame) should include("a b c d e f g h")
  }

  it should "show white pieces as uppercase" in {
    val board = ConsoleRenderer.renderBoard(newGame)
    board should include("R")
    board should include("N")
    board should include("B")
    board should include("Q")
    board should include("K")
    board should include("P")
  }

  it should "show black pieces as lowercase" in {
    val board = ConsoleRenderer.renderBoard(newGame)
    board should include("r")
    board should include("n")
    board should include("b")
    board should include("q")
    board should include("k")
    board should include("p")
  }

  it should "show dots for empty squares" in {
    ConsoleRenderer.renderBoard(newGame) should include(".")
  }

  // ── Status rendering ───────────────────────────────────────────────────────

  "renderStatus" should "show White as current player on a new game" in {
    ConsoleRenderer.renderStatus(newGame) should include("White")
  }

  it should "show Black when it is Black's turn" in {
    ConsoleRenderer.renderStatus(newGame.copy(currentPlayer = Color.Black)) should include("Black")
  }

  it should "show Ongoing status" in {
    ConsoleRenderer.renderStatus(newGame) should include("Ongoing")
  }

  it should "show Check status" in {
    ConsoleRenderer.renderStatus(newGame.copy(status = GameStatus.Check)) should include("Check!")
  }

  it should "show Checkmate status" in {
    ConsoleRenderer.renderStatus(newGame.copy(status = GameStatus.Checkmate)) should include("Checkmate!")
  }

  it should "show Stalemate status" in {
    ConsoleRenderer.renderStatus(newGame.copy(status = GameStatus.Stalemate)) should include("Stalemate")
  }

  it should "show move count" in {
    ConsoleRenderer.renderStatus(newGame) should include("0")
  }

  // ── Welcome / help ─────────────────────────────────────────────────────────

  "renderWelcome" should "mention SeArChess" in {
    ConsoleRenderer.renderWelcome() should include("SeArChess")
  }

  "renderHelp" should "list all supported commands" in {
    val help = ConsoleRenderer.renderHelp()
    help should include("new")
    help should include("move")
    help should include("show")
    help should include("help")
    help should include("quit")
  }

  // ── Parse error rendering ──────────────────────────────────────────────────

  "renderParseError" should "render EmptyInput" in {
    ConsoleRenderer.renderParseError(InputParseError.EmptyInput) should not be empty
  }

  it should "render UnknownCommand including the offending token" in {
    ConsoleRenderer.renderParseError(InputParseError.UnknownCommand("foo")) should include("foo")
  }

  it should "render WrongArgumentCount including the command name" in {
    ConsoleRenderer.renderParseError(InputParseError.WrongArgumentCount("move")) should include("move")
  }

  // ── Application error rendering ────────────────────────────────────────────

  "renderApplicationError" should "render NotPlayersTurn" in {
    ConsoleRenderer.renderApplicationError(ApplicationError.NotPlayersTurn) should not be empty
  }

  it should "render DomainFailure wrapping EmptySourceSquare" in {
    val err = ApplicationError.DomainFailure(DomainError.EmptySourceSquare(pos("e2")))
    ConsoleRenderer.renderApplicationError(err) should include("e2")
  }

  it should "render DomainFailure wrapping IllegalMove" in {
    val err = ApplicationError.DomainFailure(DomainError.IllegalMove(pos("e2"), pos("e5")))
    ConsoleRenderer.renderApplicationError(err) should (include("e2") and include("e5"))
  }

  it should "render DomainFailure wrapping BlockedPath" in {
    val err = ApplicationError.DomainFailure(DomainError.BlockedPath(pos("a1"), pos("a8")))
    ConsoleRenderer.renderApplicationError(err) should (include("a1") and include("a8"))
  }

  it should "render DomainFailure wrapping OccupiedByOwnPiece" in {
    val err = ApplicationError.DomainFailure(DomainError.OccupiedByOwnPiece(pos("e2")))
    ConsoleRenderer.renderApplicationError(err) should include("e2")
  }

  it should "render DomainFailure wrapping SameSquare" in {
    val err = ApplicationError.DomainFailure(DomainError.SameSquare)
    ConsoleRenderer.renderApplicationError(err) should not be empty
  }

  it should "render DomainFailure wrapping KingInCheck" in {
    val err = ApplicationError.DomainFailure(DomainError.KingInCheck)
    ConsoleRenderer.renderApplicationError(err) should not be empty
  }

  it should "render DomainFailure wrapping OutOfBounds" in {
    val err = ApplicationError.DomainFailure(DomainError.OutOfBounds(9, 9))
    ConsoleRenderer.renderApplicationError(err) should not be empty
  }

  it should "render DomainFailure wrapping InvalidPositionString" in {
    val err = ApplicationError.DomainFailure(DomainError.InvalidPositionString("zz"))
    ConsoleRenderer.renderApplicationError(err) should include("zz")
  }
