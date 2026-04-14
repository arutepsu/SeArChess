package chess.notation.fen

import chess.notation.api.ParseFailure

private[fen] trait FenGrammar:
  def parseRecord(input: String): Either[ParseFailure, FenRecord]