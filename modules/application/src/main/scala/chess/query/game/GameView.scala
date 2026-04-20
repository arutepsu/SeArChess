package chess.application.query.game

import chess.application.session.model.SessionIds.GameId
import chess.domain.model.{Board, Color, GameStatus, Move, Piece, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.{CastlingRights, EnPassantState, GameState}

/** Application read model for the current state of an active or finished game.
 *
 *  This is intentionally not a REST DTO. It belongs to the Game Service
 *  application boundary and can be mapped by any inbound adapter.
 *
 *  [[legalMoves]] is pre-computed from the domain rules so that adapter
 *  mappers do not need to call [[GameStateRules]] directly. For terminal
 *  positions (Checkmate, Draw, Resigned) the set is empty.
 *
 *  [[castlingRights]] and [[enPassantState]] are included for exact FEN
 *  reconstruction: a future [[chess.notation.fen.FenSerializer]] call requires
 *  the full position, not just the board and move history.
 *
 *  @param gameId          stable identifier for the game record
 *  @param currentPlayer   side whose turn it is (or whose turn it was at game end)
 *  @param status          current game status
 *  @param board           all occupied squares at the current position (position-piece pairs)
 *  @param moveHistory     ordered list of moves played so far
 *  @param castlingRights  which castling moves are still legally available
 *  @param enPassantState  en passant target if the previous move was a two-square pawn advance
 *  @param halfmoveClock   half-moves since last capture or pawn advance
 *  @param fullmoveNumber  incremented after each Black move (starts at 1)
 *  @param legalMoves      legal moves for [[currentPlayer]] in this position
 */
final case class GameView(
  gameId:         GameId,
  currentPlayer:  Color,
  status:         GameStatus,
  board:          Seq[(Position, Piece)],
  moveHistory:    List[Move],
  castlingRights: CastlingRights,
  enPassantState: Option[EnPassantState],
  halfmoveClock:  Int,
  fullmoveNumber: Int,
  legalMoves:     Set[Move]
):
  /** Reconstruct a [[GameState]] from this view.
   *
   *  Round-trips cleanly when `this` was produced by [[GameView.fromState]].
   *  The reconstructed [[chess.domain.model.Board]] is built by folding over
   *  [[board]]; field order in the sparse map is not guaranteed to match the
   *  original, but piece placement is exact.
   */
  def toGameState: GameState =
    val reconstructedBoard = board.foldLeft(Board.empty) { case (b, (pos, piece)) =>
      b.place(pos, piece)
    }
    GameState(
      board          = reconstructedBoard,
      currentPlayer  = currentPlayer,
      moveHistory    = moveHistory,
      status         = status,
      castlingRights = castlingRights,
      enPassantState = enPassantState,
      halfmoveClock  = halfmoveClock,
      fullmoveNumber = fullmoveNumber
    )

object GameView:
  def fromState(gameId: GameId, state: GameState): GameView =
    GameView(
      gameId         = gameId,
      currentPlayer  = state.currentPlayer,
      status         = state.status,
      board          = state.board.pieces,
      moveHistory    = state.moveHistory,
      castlingRights = state.castlingRights,
      enPassantState = state.enPassantState,
      halfmoveClock  = state.halfmoveClock,
      fullmoveNumber = state.fullmoveNumber,
      legalMoves     = GameStateRules.legalMoves(state)
    )
