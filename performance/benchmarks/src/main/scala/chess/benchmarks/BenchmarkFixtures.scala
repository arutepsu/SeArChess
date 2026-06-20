package chess.benchmarks

import chess.adapter.repository.{
  InMemoryGameRepository,
  InMemorySessionGameStore,
  InMemorySessionRepository
}
import chess.application.DefaultGameService
import chess.application.event.AppEvent
import chess.application.port.event.EventPublisher
import chess.application.session.model.{SessionMode, SideController}
import chess.application.session.service.{SessionGameCommandService, SessionLifecycleService}
import chess.domain.error.DomainError
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, PieceType, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.{CastlingRights, GameState, GameStateFactory}

object BenchmarkFixtures:

  final case class ServiceFixture(
      service: DefaultGameService,
      session: chess.application.session.model.GameSession,
      gameId: chess.application.session.model.SessionIds.GameId,
      openingMove: Move
  )

  private object NoOpEventPublisher extends EventPublisher:
    override def publish(event: AppEvent): Unit = ()

  def pos(square: String): Position =
    Position
      .fromAlgebraic(square)
      .fold(error => throw IllegalArgumentException(error.toString), identity)

  def move(from: String, to: String, promotion: Option[PieceType] = None): Move =
    Move(pos(from), pos(to), promotion)

  def initialState: GameState =
    GameStateFactory.initial()

  def midgameState: GameState =
    applyMoves(
      initialState,
      List(
        move("e2", "e4"),
        move("e7", "e5"),
        move("g1", "f3"),
        move("b8", "c6")
      )
    )

  def checkPressureState: GameState =
    val board = Board.empty
      .place(pos("a8"), Piece(Color.Black, PieceType.King))
      .place(pos("a1"), Piece(Color.White, PieceType.Rook))
      .place(pos("b6"), Piece(Color.White, PieceType.Queen))
      .place(pos("h1"), Piece(Color.White, PieceType.King))

    GameState(
      board = board,
      currentPlayer = Color.Black,
      moveHistory = Nil,
      status = GameStatus.Ongoing(inCheck = true),
      castlingRights = CastlingRights.none
    )

  def captureReadyState: GameState =
    applyMoves(
      initialState,
      List(
        move("e2", "e4"),
        move("d7", "d5")
      )
    )

  def promotionReadyState: GameState =
    val board = Board.empty
      .place(pos("a7"), Piece(Color.White, PieceType.Pawn))
      .place(pos("a1"), Piece(Color.White, PieceType.King))
      .place(pos("e8"), Piece(Color.Black, PieceType.King))

    GameState(
      board = board,
      currentPlayer = Color.White,
      moveHistory = Nil,
      status = GameStatus.Ongoing(inCheck = false),
      castlingRights = CastlingRights.none
    )

  def freshServiceFixture(): ServiceFixture =
    val sessionRepo = new InMemorySessionRepository
    val gameRepo = new InMemoryGameRepository
    val store = new InMemorySessionGameStore(sessionRepo, gameRepo)
    val sessionLifecycleService = new SessionLifecycleService(sessionRepo, NoOpEventPublisher)
    val commands = new SessionGameCommandService(
      sessionLifecycleService,
      store,
      NoOpEventPublisher
    )
    val service = DefaultGameService(
      commands = commands,
      sessionLifecycleService = sessionLifecycleService,
      gameRepository = gameRepo,
      publisher = NoOpEventPublisher
    )
    val (_, session) = service
      .createGame(SessionMode.HumanVsHuman, SideController.HumanLocal, SideController.HumanLocal)
      .fold(error => throw IllegalStateException(error.toString), identity)

    ServiceFixture(service, session, session.gameId, move("e2", "e4"))

  private def applyMoves(state: GameState, moves: List[Move]): GameState =
    moves.foldLeft(state) { (current, nextMove) =>
      GameStateRules
        .applyMove(current, nextMove)
        .fold(error => throw IllegalStateException(describe(error, nextMove)), identity)
    }

  private def describe(error: DomainError, move: Move): String =
    s"Fixture move ${move.from}-${move.to} failed: $error"
