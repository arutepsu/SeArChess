package chess.adapter.repository.testcontainers

import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.application.session.model.{GameSession, SessionLifecycle, SessionMode, SideController}
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.state.{CastlingRights, EnPassantState, GameState}
import org.scalatest.EitherValues

import java.time.Instant
import java.util.UUID

trait TestPersistenceSamples extends EitherValues:

  def sampleSession(
      sessionId: String = "00000000-0000-0000-0000-00000000a001",
      gameId: String = "10000000-0000-0000-0000-00000000a001",
      lifecycle: SessionLifecycle = SessionLifecycle.Active
  ): GameSession =
    GameSession(
      sessionId = SessionId(UUID.fromString(sessionId)),
      gameId = GameId(UUID.fromString(gameId)),
      mode = SessionMode.HumanVsHuman,
      whiteController = SideController.HumanLocal,
      blackController = SideController.HumanRemote,
      lifecycle = lifecycle,
      createdAt = Instant.parse("2024-01-01T00:00:00Z"),
      updatedAt = Instant.parse("2024-01-01T00:05:00Z")
    )

  def sampleState(fullmoveNumber: Int = 12): GameState =
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
      fullmoveNumber = fullmoveNumber
    )

  private def pos(algebraic: String): Position =
    Position.fromAlgebraic(algebraic).value
