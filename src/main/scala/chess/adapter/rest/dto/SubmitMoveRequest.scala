package chess.adapter.rest.dto

/** Request body for POST /games/{gameId}/moves.
 *
 *  @param from       source square in algebraic notation (e.g. "e2")
 *  @param to         target square in algebraic notation (e.g. "e4")
 *  @param promotion  promotion piece type when a pawn reaches the back rank;
 *                    one of "Queen" | "Rook" | "Bishop" | "Knight"
 *  @param controller the controller submitting the move; defaults to
 *                    "HumanLocal" when absent.  Used by
 *                    [[chess.application.session.policy.ActorControlPolicy]]
 *                    to verify turn ownership.
 */
final case class SubmitMoveRequest(
  from:       String,
  to:         String,
  promotion:  Option[String],
  controller: Option[String]
)

object SubmitMoveRequest:
  /** Parse from a JSON string.
   *
   *  `from` and `to` are required; returns Left if either is absent or the
   *  JSON is malformed.
   */
  def fromJson(body: String): Either[String, SubmitMoveRequest] =
    try
      val obj = ujson.read(body).obj
      Right(SubmitMoveRequest(
        from       = obj("from").str,
        to         = obj("to").str,
        promotion  = obj.get("promotion").map(_.str),
        controller = obj.get("controller").map(_.str)
      ))
    catch case _: Exception => Left("Malformed JSON or missing required fields 'from' / 'to'")
