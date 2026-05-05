package chess.adapter.repository.contract

import chess.application.port.repository.{
  GameRepository,
  RepositoryError,
  SessionGameStore,
  SessionRepository
}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpecLike
import org.scalatest.matchers.should.Matchers

import java.time.Instant

trait SessionGameStoreContract extends AnyFlatSpecLike with Matchers with EitherValues:

  final case class StoreFixture(
      sessionRepository: SessionRepository,
      gameRepository: GameRepository,
      store: SessionGameStore
  )

  def storeName: String

  def freshStore(): StoreFixture

  storeName should "persist session and game state as one successful logical write" in {
    val fixture = freshStore()
    val session = freshSession()
    val state = richState

    fixture.store.save(session, state).value

    fixture.sessionRepository.load(session.sessionId).value shouldBe session
    assertSameGameState(fixture.gameRepository.load(session.gameId).value, state)
  }

  it should "replace both session and game state for the same session/game association" in {
    val fixture = freshStore()
    val session = freshSession()
    val initialState = richState
    val updatedSession = session.copy(
      lifecycle = SessionLifecycle.Active,
      updatedAt = Instant.parse("2024-01-01T00:01:00Z")
    )
    val updatedState = richState.copy(
      currentPlayer = Color.White,
      status = GameStatus.Resigned(Color.Black),
      fullmoveNumber = 22
    )

    fixture.store.save(session, initialState).value
    fixture.store.save(updatedSession, updatedState).value

    fixture.sessionRepository.load(session.sessionId).value shouldBe updatedSession
    assertSameGameState(fixture.gameRepository.load(session.gameId).value, updatedState)
  }

  it should "propagate duplicate GameId ownership conflicts" in {
    val fixture = freshStore()
    val gameId = GameId.random()
    val firstSession = freshSession(gameId)
    val conflictingSession = freshSession(gameId).copy(sessionId = SessionId.random())

    fixture.store.save(firstSession, richState).value

    fixture.store
      .save(conflictingSession, richState.copy(fullmoveNumber = 99))
      .left
      .value shouldBe a[RepositoryError.Conflict]
  }

  it should "not make the game-state side visible when the session write is rejected" in {
    val fixture = freshStore()
    val gameId = GameId.random()
    val firstSession = freshSession(gameId)
    val firstState = richState
    val conflictingSession = freshSession(gameId).copy(sessionId = SessionId.random())
    val conflictingState = richState.copy(fullmoveNumber = 99)

    fixture.store.save(firstSession, firstState).value
    fixture.store.save(conflictingSession, conflictingState).left.value shouldBe
      a[RepositoryError.Conflict]

    fixture.sessionRepository.load(firstSession.sessionId).value shouldBe firstSession
    fixture.sessionRepository.load(conflictingSession.sessionId).left.value shouldBe
      RepositoryError.NotFound(conflictingSession.sessionId.value.toString)
    assertSameGameState(fixture.gameRepository.load(gameId).value, firstState)
  }

  private def freshSession(gameId: GameId = GameId.random()): GameSession =
    GameSession.create(
      gameId = gameId,
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanLocal,
      now = Instant.parse("2024-01-01T00:00:00Z")
    )

  private def richState: GameState =
    GameState(
      board = Board.empty
        .place(pos("e4"), Piece(Color.White, PieceType.Pawn))
        .place(pos("e8"), Piece(Color.Black, PieceType.King))
        .place(pos("g1"), Piece(Color.White, PieceType.King)),
      currentPlayer = Color.Black,
      moveHistory = List(Move(pos("e2"), pos("e4"))),
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
