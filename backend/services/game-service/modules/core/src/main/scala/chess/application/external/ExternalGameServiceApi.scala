package chess.application.external

import chess.application.session.model.{ExternalPlatform, SessionMode}
import chess.domain.model.{Color, GameStatus}
import java.time.Instant

/** A caller identity that has already been verified by the route layer (e.g. via API key lookup).
  *
  * The service trusts this value without re-verifying credentials. The route layer is responsible
  * for mapping bearer tokens to a [[VerifiedExternalCaller]] before invoking service methods.
  */
final case class VerifiedExternalCaller(platform: ExternalPlatform, actorId: String)

/** Input to [[ExternalGameServiceApi.startExternalGame]].
  *
  * @param platform          which external platform hosts the game
  * @param externalGameId    the game's identifier on the external platform
  * @param mode              must be [[SessionMode.AiVsExternal]] or [[SessionMode.ExternalVsExternal]]
  * @param ourActorId        platform actor ID of the bot/caller; stored in the binding for auth
  * @param ourColor          for [[SessionMode.AiVsExternal]]: the color the internal AI plays;
  *                          for [[SessionMode.ExternalVsExternal]]: the color our actor controls
  * @param opponentActorId   the other side's actor ID on the external platform
  * @param now               wall-clock instant for record creation timestamps
  */
final case class StartExternalGameRequest(
    platform: ExternalPlatform,
    externalGameId: String,
    mode: SessionMode,
    ourActorId: String,
    ourColor: Color,
    opponentActorId: String,
    now: Instant = Instant.now()
)

/** What the caller should do next after a move ingestion or AI move completes. */
enum NextAction:
  /** The side to move is externally controlled; await the next move from the external stream. */
  case AwaitExternalMove
  /** The side to move is AI-controlled; call [[ExternalGameServiceApi.requestAiMove]] next. */
  case TriggerAI
  /** The game has reached a terminal state. */
  case GameFinished

/** Result of [[ExternalGameServiceApi.ingestExternalMoves]]. */
final case class IngestMovesResult(
    lastProcessedPly: Int,
    currentPlayer: Color,
    gameStatus: GameStatus,
    nextAction: NextAction
)

/** Result of [[ExternalGameServiceApi.requestAiMove]]. */
final case class AiMoveResult(
    uciMove: String,
    lastProcessedPly: Int,
    gameStatus: GameStatus,
    nextAction: NextAction
)

/** Public boundary for external-game lifecycle operations.
  *
  * All operations identify the game by `(platform, externalGameId)` — the external platform's own
  * identifier — so callers never need to know internal [[chess.application.session.model.SessionIds.GameId]]
  * or [[chess.application.session.model.SessionIds.SessionId]] values.
  *
  * ===Thread model===
  * Each operation is synchronous and returns `Either`. Async scheduling is an adapter concern.
  *
  * ===What is NOT covered here===
  *   - REST routing or HTTP adaptation (Phase C)
  *   - Persistence adapters (Phase C)
  *   - Lichess bot event-stream handling (Phase E)
  */
trait ExternalGameServiceApi:

  /** Create a new Game Service session bound to an external game, or return the existing binding
    * if one already exists for `(platform, externalGameId)`.
    *
    * Session controllers are derived from `request.mode` and `request.ourColor`:
    *   - [[SessionMode.AiVsExternal]], ourColor=White → white=AI(), black=External(opponent)
    *   - [[SessionMode.AiVsExternal]], ourColor=Black → white=External(opponent), black=AI()
    *   - [[SessionMode.ExternalVsExternal]], ourColor=White → white=External(our), black=External(opponent)
    *   - [[SessionMode.ExternalVsExternal]], ourColor=Black → white=External(opponent), black=External(our)
    */
  def startExternalGame(
      request: StartExternalGameRequest
  ): Either[ExternalGameError, ExternalGameBinding]

  /** Retrieve the binding for the given external game. Returns [[ExternalGameError.BindingNotFound]]
    * if no binding exists.
    */
  def getExternalGame(
      platform: ExternalPlatform,
      externalGameId: String
  ): Either[ExternalGameError, ExternalGameBinding]

  /** Apply any moves from the cumulative `uciMoves` string that have not yet been processed.
    *
    * The Lichess game stream delivers the full move history on every `gameState` event; this
    * operation is designed for that format. Moves up to `binding.lastProcessedPly` are skipped.
    * Remaining moves are applied in order through the same pure session/domain path as normal moves.
    * Each applied move is persisted atomically with a `lastProcessedPly` advance.
    *
    * Returns a no-op result (current state, same ply) when all incoming moves are already
    * processed.
    *
    * @param caller    verified identity of the calling bot/service
    * @param uciMoves  space-separated cumulative UCI move list (e.g. `"e2e4 e7e5 d2d4"`)
    */
  def ingestExternalMoves(
      caller: VerifiedExternalCaller,
      platform: ExternalPlatform,
      externalGameId: String,
      uciMoves: String,
      now: Instant = Instant.now()
  ): Either[ExternalGameError, IngestMovesResult]

  /** Ask the internal AI to generate and apply a move for the AI-controlled side.
    *
    * Only valid when the current side to move is [[chess.application.session.model.SideController.AI]].
    * The move is persisted atomically with a `lastProcessedPly` advance so subsequent
    * [[ingestExternalMoves]] calls skip it correctly.
    *
    * Returns the move in UCI notation for the caller to forward to the external platform.
    */
  def requestAiMove(
      caller: VerifiedExternalCaller,
      platform: ExternalPlatform,
      externalGameId: String,
      now: Instant = Instant.now()
  ): Either[ExternalGameError, AiMoveResult]

  /** Mark the binding as Finished and advance the session lifecycle to Finished.
    *
    * Intended for game-end signals from the external platform (checkmate confirmed, resignation,
    * draw agreement, time forfeit). The domain game state is not modified; only the session
    * lifecycle and binding status are updated.
    */
  def finishExternalGame(
      caller: VerifiedExternalCaller,
      platform: ExternalPlatform,
      externalGameId: String,
      now: Instant = Instant.now()
  ): Either[ExternalGameError, ExternalGameBinding]
