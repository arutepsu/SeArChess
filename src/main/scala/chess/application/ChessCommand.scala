package chess.application

import chess.domain.model.Move

sealed trait ChessCommand
case class MakeMove(move: Move) extends ChessCommand
case object NewGame             extends ChessCommand
