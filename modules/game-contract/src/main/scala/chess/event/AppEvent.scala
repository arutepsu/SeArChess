package chess.application.event

import chess.application.session.model.{SessionLifecycle, SessionMode, SideController}
import chess.application.session.model.SessionIds.{GameId, SessionId}
import chess.domain.model.{Color, GameStatus, Move}

/** Application-layer events emitted by services when meaningful state transitions occur.
  *
  * These are distinct from [[chess.domain.event.DomainEvent]]: domain events describe chess-rule
  * facts (piece moved, check declared, etc.); application events describe orchestration facts
  * (session created, AI move requested, game lifecycle changed) and always carry session/game
  * identity.
  *
  * ===Design intent===
  *   - Payloads carry identifiers and essential facts; no HTTP DTOs or GUI objects.
  *   - Events describe facts that have already happened or were meaningfully attempted.
  *   - Subscribers must not rely on a guaranteed ordering across different event types.
  *   - Adding a new case class to this file is the only change needed to introduce a new event — no
  *     subscriber plumbing changes required in this phase.
  *
  * Every event carries both a [[SessionIds.SessionId]] and a [[SessionIds.GameId]] so transport
  * adapters (e.g. WebSocket) can route by either identifier without an exhaustive pattern match.
  *
  * ===Event categories===
  *
  * '''State-transition events''' — always forwarded to transport adapters; meaningful to any UI
  * that tracks the game. Each one reflects a durable change to game or session state that has
  * already been persisted:
  *   - [[SessionCreated]] — new session ready for play
  *   - [[SessionLifecycleChanged]] — lifecycle phase transition (e.g. Created → Active)
  *   - [[MoveApplied]] — a move was successfully applied and persisted
  *   - [[PromotionPending]] — pawn on back rank; promotion choice required
  *   - [[GameFinished]] — terminal position reached (checkmate or draw)
  *   - [[GameResigned]] — a player resigned; game state records the winner
  *   - [[SessionCancelled]] — session cancelled administratively; game state unchanged
  *
  * '''Interaction/audit events''' — record attempts that were made but did not result in a state
  * change. Useful for observability and audit trails; subscribers must not update game state in
  * response to these:
  *   - [[MoveRejected]] — a move was attempted and rejected by domain rules
  *
  * '''AI monitoring events''' — forwarded to all transport adapters but primarily for
  * observability. A UI may use them for "AI thinking…" indicators; they do not change the canonical
  * game state and do not replace a REST re-fetch:
  *   - [[AITurnRequested]] — AI turn guard passed; provider is being called
  *   - [[AITurnCompleted]] — AI provider returned a move; move was applied
  *   - [[AITurnFailed]] — AI turn attempt failed (provider error or illegal move)
  */
sealed trait AppEvent:
  def sessionId: SessionId
  def gameId: GameId

object AppEvent:

  /** Published when a new [[chess.application.session.model.GameSession]] has been persisted and is
    * ready for play.
    */
  final case class SessionCreated(
      sessionId: SessionId,
      gameId: GameId,
      mode: SessionMode,
      whiteController: SideController,
      blackController: SideController
  ) extends AppEvent

  /** Published when the session lifecycle phase changes.
    *
    * Carries both the previous and next phase so subscribers can react to specific transitions
    * (e.g. `Active → Finished`) without reconstructing history.
    */
  final case class SessionLifecycleChanged(
      sessionId: SessionId,
      gameId: GameId,
      from: SessionLifecycle,
      to: SessionLifecycle
  ) extends AppEvent

  /** Published when a move has been successfully applied and the new state has been determined.
    *
    * `playerWhoMoved` is the color that *made* the move (i.e. the player whose turn it was *before*
    * the move was applied). After the move the turn belongs to the opposite color — use
    * [[chess.domain.state.GameState.currentPlayer]] from a REST re-fetch to obtain the player to
    * move next.
    */
  final case class MoveApplied(
      sessionId: SessionId,
      gameId: GameId,
      move: Move,
      playerWhoMoved: Color
  ) extends AppEvent

  /** Published when a pawn has reached the back rank and the session is waiting for a promotion
    * piece choice before the move can be committed.
    */
  final case class PromotionPending(
      sessionId: SessionId,
      gameId: GameId
  ) extends AppEvent

  /** Published when a move results in a terminal game state (checkmate or draw).
    *
    * Always accompanies a [[MoveApplied]] event in the same operation. `status` is guaranteed to be
    * a terminal variant ([[chess.domain.model.GameStatus.Checkmate]] or
    * [[chess.domain.model.GameStatus.Draw]]).
    */
  final case class GameFinished(
      sessionId: SessionId,
      gameId: GameId,
      status: GameStatus
  ) extends AppEvent

  /** Published when [[chess.application.ai.service.AITurnService]] has verified the current turn is
    * AI-controlled and is about to request a move from the provider.
    */
  final case class AITurnRequested(
      sessionId: SessionId,
      gameId: GameId,
      currentPlayer: Color
  ) extends AppEvent

  /** Published when the AI provider returned a candidate and the move was successfully applied
    * through the normal session move path.
    */
  final case class AITurnCompleted(
      sessionId: SessionId,
      gameId: GameId,
      move: Move
  ) extends AppEvent

  /** Published when an AI turn that was requested (after the guard check passed) could not be
    * completed — either the provider failed or the proposed move was rejected by the session/domain
    * path.
    *
    * Not published when the turn guard itself fails (e.g. not AI's turn), since no turn was
    * actually requested in that case.
    */
  final case class AITurnFailed(
      sessionId: SessionId,
      gameId: GameId,
      reason: String
  ) extends AppEvent

  /** '''Interaction/audit event''' — published when a move was attempted and rejected by domain
    * rules (illegal move, wrong turn, promotion required, etc.).
    *
    * This is NOT a state-transition event: the game state does not change when a move is rejected.
    * It exists solely for observability — audit trails, client-side "your move was illegal"
    * indicators, and monitoring of malformed requests. Subscribers must not update game state in
    * response to this event.
    *
    * Published only for [[chess.application.session.service.SessionMoveError.DomainRejection]]. Not
    * published for:
    *   - Infrastructure failures (persistence errors, session not found) — caller errors
    *   - Unauthorized-controller rejections — caller policy errors, not game events
    */
  final case class MoveRejected(
      sessionId: SessionId,
      gameId: GameId,
      move: Move,
      reason: String
  ) extends AppEvent

  /** Published when a player explicitly resigns, ending the game in favour of [[winner]]. Always
    * accompanies a [[SessionLifecycleChanged]] event (lifecycle → Finished).
    */
  final case class GameResigned(
      sessionId: SessionId,
      gameId: GameId,
      winner: Color
  ) extends AppEvent

  /** Published when a session is cancelled before a terminal game state is reached. The associated
    * game state is left unchanged (no winner recorded).
    */
  final case class SessionCancelled(
      sessionId: SessionId,
      gameId: GameId
  ) extends AppEvent
