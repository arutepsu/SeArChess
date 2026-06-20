package chess.arena.bots.ai

import chess.adapter.ai.remote.RemoteAiMoveDto
import chess.domain.model.{Move, PieceType}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

object AiSquareNotation:

  def moveToDto(move: Move): RemoteAiMoveDto =
    RemoteAiMoveDto(
      from      = move.from.toString,
      to        = move.to.toString,
      promotion = move.promotion.map(promotionChar)
    )

  def dtoToLegalMove(dto: RemoteAiMoveDto, state: GameState): Either[String, Move] =
    GameStateRules.legalMoves(state)
      .find(m =>
        m.from.toString                == dto.from &&
        m.to.toString                  == dto.to   &&
        m.promotion.map(promotionChar) == dto.promotion
      )
      .toRight(
        s"AI returned move (${dto.from}->${dto.to} promo=${dto.promotion}) is not legal in this position"
      )

  private def promotionChar(pt: PieceType): String = pt match
    case PieceType.Queen  => "q"
    case PieceType.Rook   => "r"
    case PieceType.Bishop => "b"
    case PieceType.Knight => "n"
    case _                => ""
