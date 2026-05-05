package chess.adapter.repository.contract

import chess.application.port.repository.{GameRepository, RepositoryError}
import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Board, Color, DrawReason, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

trait GameRepositoryContract extends AnyFlatSpecLike with Matchers with EitherValues:

  def repositoryName: String

  def freshRepository(): GameRepository

  repositoryName should "round-trip a non-terminal GameState by GameId" in {
    val repo = freshRepository()
    val gameId = GameId.random()
    val state = richOngoingState

    repo.save(gameId, state).value

    assertSameGameState(repo.load(gameId).value, state)
  }

  it should "return NotFound for an unknown GameId" in {
    val repo = freshRepository()
    val unknown = GameId.random()

    repo.load(unknown).left.value shouldBe RepositoryError.NotFound(unknown.value.toString)
  }

  it should "replace the current state when saving the same GameId again" in {
    val repo = freshRepository()
    val gameId = GameId.random()
    val first = richOngoingState
    val replacement = terminalState(GameStatus.Resigned(Color.Black)).copy(fullmoveNumber = 19)

    repo.save(gameId, first).value
    repo.save(gameId, replacement).value

    assertSameGameState(repo.load(gameId).value, replacement)
  }

  it should "keep independent GameIds isolated" in {
    val repo = freshRepository()
    val firstId = GameId.random()
    val secondId = GameId.random()
    val first = richOngoingState
    val second = terminalState(GameStatus.Checkmate(Color.White)).copy(fullmoveNumber = 42)

    repo.save(firstId, first).value
    repo.save(secondId, second).value

    assertSameGameState(repo.load(firstId).value, first)
    assertSameGameState(repo.load(secondId).value, second)
  }

  it should "round-trip rule-critical current-state fields" in {
    val repo = freshRepository()
    val gameId = GameId.random()
    val state = richOngoingState

    repo.save(gameId, state).value

    val loaded = repo.load(gameId).value
    loaded.currentPlayer shouldBe Color.Black
    loaded.castlingRights shouldBe CastlingRights(
      whiteKingSide = false,
      whiteQueenSide = true,
      blackKingSide = true,
      blackQueenSide = false
    )
    loaded.enPassantState shouldBe Some(
      EnPassantState(pos("e3"), pos("e4"), Color.White)
    )
    loaded.halfmoveClock shouldBe 7
    loaded.fullmoveNumber shouldBe 12
    loaded.status shouldBe GameStatus.Ongoing(inCheck = true)
    loaded.moveHistory shouldBe List(
      Move(pos("e2"), pos("e4")),
      Move(pos("a7"), pos("a8"), Some(PieceType.Queen))
    )
    loaded.board shouldBe state.board
  }

  it should "round-trip terminal GameState values" in {
    val repo = freshRepository()
    val checkmateId = GameId.random()
    val drawId = GameId.random()
    val resignedId = GameId.random()
    val checkmate = terminalState(GameStatus.Checkmate(Color.White))
    val draw = terminalState(GameStatus.Draw(DrawReason.Stalemate))
    val resigned = terminalState(GameStatus.Resigned(Color.Black))

    repo.save(checkmateId, checkmate).value
    repo.save(drawId, draw).value
    repo.save(resignedId, resigned).value

    assertSameGameState(repo.load(checkmateId).value, checkmate)
    assertSameGameState(repo.load(drawId).value, draw)
    assertSameGameState(repo.load(resignedId).value, resigned)
  }

  private def richOngoingState: GameState =
    GameState(
      board = Board.empty
        .place(pos("e4"), Piece(Color.White, PieceType.Pawn))
        .place(pos("e8"), Piece(Color.Black, PieceType.King))
        .place(pos("g1"), Piece(Color.White, PieceType.King))
        .place(pos("a8"), Piece(Color.White, PieceType.Queen)),
      currentPlayer = Color.Black,
      moveHistory = List(
        Move(pos("e2"), pos("e4")),
        Move(pos("a7"), pos("a8"), Some(PieceType.Queen))
      ),
      status = GameStatus.Ongoing(inCheck = true),
      castlingRights = CastlingRights(
        whiteKingSide = false,
        whiteQueenSide = true,
        blackKingSide = true,
        blackQueenSide = false
      ),
      enPassantState = Some(EnPassantState(pos("e3"), pos("e4"), Color.White)),
      halfmoveClock = 7,
      fullmoveNumber = 12
    )

  private def terminalState(status: GameStatus): GameState =
    GameState(
      board = Board.empty
        .place(pos("h1"), Piece(Color.White, PieceType.King))
        .place(pos("h8"), Piece(Color.Black, PieceType.King))
        .place(pos("g7"), Piece(Color.White, PieceType.Queen)),
      currentPlayer = Color.White,
      moveHistory = List(Move(pos("g6"), pos("g7"))),
      status = status,
      castlingRights = CastlingRights.none,
      enPassantState = None,
      halfmoveClock = 3,
      fullmoveNumber = 18
    )

  private def assertSameGameState(actual: GameState, expected: GameState): Unit =
    actual.board shouldBe expected.board
    actual.currentPlayer shouldBe expected.currentPlayer
    actual.moveHistory shouldBe expected.moveHistory
    actual.status shouldBe expected.status
    actual.castlingRights shouldBe expected.castlingRights
    actual.enPassantState shouldBe expected.enPassantState
    actual.halfmoveClock shouldBe expected.halfmoveClock
    actual.fullmoveNumber shouldBe expected.fullmoveNumber

  private def pos(algebraic: String): Position =
    Position.fromAlgebraic(algebraic).value
