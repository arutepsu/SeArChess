package chess.notation.pgn

import chess.notation.api.ParseFailure

private[pgn] trait PgnGrammar:
  def parseRecord(input: String): Either[ParseFailure, PgnRecord]