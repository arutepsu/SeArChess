package chess.adapter.rest.contract.dto

import ujson.Value

/** Canonical response body for creating a playable game via POST /sessions.
 *
 *  Bundles the new session metadata with the initial game state so the
 *  client has everything it needs to start interacting without a second
 *  round-trip.
 *
 *  @param session session metadata
 *  @param game    full initial game snapshot; wire field name remains "game"
 */
final case class CreateGameResponse(session: SessionResponse, game: GameSnapshot)

object CreateGameResponse:
  def toJson(r: CreateGameResponse): Value =
    ujson.Obj(
      "session" -> SessionResponse.toJson(r.session),
      "game"    -> GameSnapshot.toJson(r.game)
    )

/** Temporary source-compatible alias for pre-cleanup DTO naming.
 *
 *  New code should use [[CreateGameResponse]]. The JSON wire shape is unchanged.
 */
type CreateSessionResponse = CreateGameResponse

object CreateSessionResponse:
  def apply(session: SessionResponse, game: GameSnapshot): CreateGameResponse =
    CreateGameResponse(session, game)

  def toJson(r: CreateGameResponse): Value =
    CreateGameResponse.toJson(r)
