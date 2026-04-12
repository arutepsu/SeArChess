package chess.domain.model

import chess.domain.error.DomainError

/** An immutable, validated board position.
 *
 *  file: 0 = 'a' .. 7 = 'h'
 *  rank: 0 = '1' .. 7 = '8'
 *
 *  The primary constructor is private so callers must go through the
 *  safe factory methods in the companion object.
 */
final case class Position private (file: Int, rank: Int):
  /** Algebraic label, e.g. Position(4, 1) → "e2" */
  override def toString: String =
    s"${('a' + file).toChar}${rank + 1}"

object Position:

  private val ValidRange = 0 to 7

  /** Create a Position from numeric coordinates.
   *  Returns Left(OutOfBounds) if either coordinate is outside 0–7.
   */
  def from(file: Int, rank: Int): Either[DomainError, Position] =
    if ValidRange.contains(file) && ValidRange.contains(rank)
    then Right(new Position(file, rank))
    else Left(DomainError.OutOfBounds(file, rank))

  /** Parse a two-character algebraic string such as "e2".
   *
   *  Accepts exactly two characters where:
   *    - first char is a letter 'a'–'h'
   *    - second char is a digit '1'–'8'
   */
  def fromAlgebraic(s: String): Either[DomainError, Position] =
    s.toList match
      case List(fileChar, rankChar)
          if fileChar >= 'a' && fileChar <= 'h'
          && rankChar  >= '1' && rankChar  <= '8' =>
        Right(new Position(fileChar - 'a', rankChar - '1'))
      case _ =>
        Left(DomainError.InvalidPositionString(s))
