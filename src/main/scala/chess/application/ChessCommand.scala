package chess.application

import chess.domain.model.{Move, PieceType}

sealed trait ChessCommand
case class  MakeMove(move: Move)          extends ChessCommand
case class  Promote(pieceType: PieceType) extends ChessCommand
case object NewGame                       extends ChessCommand
