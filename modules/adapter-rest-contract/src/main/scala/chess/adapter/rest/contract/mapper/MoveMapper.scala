package chess.adapter.rest.contract.mapper

import chess.adapter.rest.contract.dto.SubmitMoveRequest
import chess.domain.model.{Move, PieceType, Position}

/** Maps a [[SubmitMoveRequest]] to a domain [[Move]].
 *
 *  Parsing responsibility lives here, not in the route layer.
 *  Chess legality is not checked here — that remains the domain's concern.
 */
object MoveMapper:

  /** Convert a [[SubmitMoveRequest]] into a domain [[Move]].
   *
   *  Returns Left with a human-readable message on parse failure:
   *  - invalid algebraic square notation
   *  - unrecognised or illegal promotion piece (King / Pawn / unknown)
   */
  def toDomain(req: SubmitMoveRequest): Either[String, Move] =
    for
      from  <- Position.fromAlgebraic(req.from)
                 .left.map(_ => s"Invalid 'from' square: '${req.from}'")
      to    <- Position.fromAlgebraic(req.to)
                 .left.map(_ => s"Invalid 'to' square: '${req.to}'")
      promo <- req.promotion match
                 case None    => Right(None)
                 case Some(p) => parsePieceType(p).map(Some(_))
    yield Move(from, to, promo)

  /** Parse a promotion piece name.
   *
   *  Only the four legal promotion targets are accepted.
   *  King and Pawn are rejected with an explicit message.
   */
  private[mapper] def parsePieceType(s: String): Either[String, PieceType] =
    s match
      case "Queen"  => Right(PieceType.Queen)
      case "Rook"   => Right(PieceType.Rook)
      case "Bishop" => Right(PieceType.Bishop)
      case "Knight" => Right(PieceType.Knight)
      case other    => Left(s"Invalid promotion piece: '$other'. Must be Queen, Rook, Bishop, or Knight")
