package chess.arena.bots.uci

import chess.arena.core.MoveUci
import chess.domain.model.{Move, Position}
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState

final case class UciBestMove(value: String):
  def toLegalDomainMove(state: GameState): Either[String, Move] =
    val legalMoves = GameStateRules.legalMoves(state)
    legalMoves
      .find(move => MoveUci.encode(move) == value)
      .toRight(s"UCI bestmove '$value' is not legal in the current position")

object UciBestMove:
  private val BestMovePattern = """^bestmove\s+([a-h][1-8][a-h][1-8][qrbn]?)(?:\s+.*)?$""".r

  def parseLine(line: String): Either[String, UciBestMove] =
    line.trim match
      case BestMovePattern(moveText) =>
        validateMoveText(moveText).map(_ => UciBestMove(moveText))
      case other =>
        Left(s"Malformed UCI bestmove line: '$other'")

  private def validateMoveText(moveText: String): Either[String, Unit] =
    val fromText = moveText.substring(0, 2)
    val toText   = moveText.substring(2, 4)
    for
      _ <- Position.fromAlgebraic(fromText).left.map(_.toString)
      _ <- Position.fromAlgebraic(toText).left.map(_.toString)
      _ <- validatePromotion(moveText.drop(4))
    yield ()

  private def validatePromotion(suffix: String): Either[String, Unit] =
    suffix.toList match
      case Nil                  => Right(())
      case List('q' | 'r' | 'b' | 'n') => Right(())
      case _                    => Left(s"Unsupported UCI promotion suffix: '$suffix'")
