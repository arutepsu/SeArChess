package chess.lichessbridge

import chess.domain.model.PieceType
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

/** Detects hanging opponent pieces in the current position.
  *
  * A piece is "hanging" if the bot can capture it and the opponent cannot immediately
  * recapture on the same square.  Picks the most valuable hanging piece.
  */
object BlunderDetector:

  def detect(state: GameState): Option[String] =
    val freeCaptures = GameStateRules.legalMoves(state)
      .filter(m => state.board.pieceAt(m.to).isDefined)
      .filter { m =>
        GameStateRules.applyMove(state, m) match
          case Right(afterCapture) =>
            !GameStateRules.legalMoves(afterCapture).exists(_.to == m.to)
          case Left(_) => false
      }
    freeCaptures.toList
      .flatMap(m => state.board.pieceAt(m.to).map(p => (m, p.pieceType)))
      .sortBy { case (_, pt) => -pieceValue(pt) }
      .headOption
      .flatMap { case (_, pt) => messageFor(pt) }

  private def pieceValue(pt: PieceType): Int = pt match
    case PieceType.Queen  => 9
    case PieceType.Rook   => 5
    case PieceType.Bishop => 3
    case PieceType.Knight => 3
    case PieceType.Pawn   => 1
    case _                => 0

  private def messageFor(pt: PieceType): Option[String] = pt match
    case PieceType.Queen  => Some("Hoppla, deine Dame steht im Visier!")
    case PieceType.Rook   => Some("Oh, da hast du deinen Turm ungeschützt gelassen.")
    case PieceType.Bishop => Some("Achtung, dein Läufer ist in Gefahr!")
    case PieceType.Knight => Some("Dein Springer steht etwas unglücklich, oder?")
    case PieceType.Pawn   => Some("Ups, ein freier Bauer!")
    case _                => None
