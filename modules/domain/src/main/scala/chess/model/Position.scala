package chess.domain.model

import chess.domain.error.DomainError

/** An immutable, validated board position.
  *
  * file: 0 = 'a' .. 7 = 'h' rank: 0 = '1' .. 7 = '8'
  *
  * The primary constructor is private so callers must go through the safe factory methods in the
  * companion object.
  */
final case class Position private (file: Int, rank: Int):
  /** Algebraic label, e.g. Position(4, 1) → "e2".
    *
    * Returns a pre-allocated string from [[Position.algebraicLabels]] — avoids a new String
    * allocation on every call, which matters in the response-mapping hot path where toString is
    * invoked once per square for the board, once per move for history, and twice per legal move
    * for the legalTargetsByFrom map.
    */
  override def toString: String = Position.algebraicLabels(file)(rank)

object Position:

  /** Pre-allocated algebraic labels for all 64 board squares, indexed by [file][rank].
    *
    * Indexed as algebraicLabels(file)(rank) where file 0–7 corresponds to a–h and rank 0–7
    * corresponds to 1–8. Initialised once at class-loading time; never mutated.
    */
  val algebraicLabels: Array[Array[String]] =
    Array.tabulate(8, 8)((file, rank) => s"${('a' + file).toChar}${rank + 1}")

  private val ValidRange = 0 to 7

  /** Create a Position from numeric coordinates. Returns Left(OutOfBounds) if either coordinate is
    * outside 0–7.
    */
  def from(file: Int, rank: Int): Either[DomainError, Position] =
    if ValidRange.contains(file) && ValidRange.contains(rank)
    then Right(new Position(file, rank))
    else Left(DomainError.OutOfBounds(file, rank))

  /** Parse a two-character algebraic string such as "e2".
    *
    * Accepts exactly two characters where:
    *   - first char is a letter 'a'–'h'
    *   - second char is a digit '1'–'8'
    */
  def fromAlgebraic(s: String): Either[DomainError, Position] =
    s.toList match
      case List(fileChar, rankChar)
          if fileChar >= 'a' && fileChar <= 'h'
            && rankChar >= '1' && rankChar <= '8' =>
        Right(new Position(fileChar - 'a', rankChar - '1'))
      case _ =>
        Left(DomainError.InvalidPositionString(s))
