package chess.application.ai.service

import chess.adapter.event.NoOpEventPublisher
import chess.application.ai.policy.AITurnPolicy
import chess.application.event.AppEvent
import chess.application.port.ai.{AIError, AIProvider}
import chess.application.port.event.EventPublisher
import chess.application.session.model.{GameSession, SideController}
import chess.application.session.service.SessionService
import chess.domain.state.GameState
import java.time.Instant

/** Application service that drives a single AI-controlled move.
 *
 *  === Responsibilities ===
 *  - Verify the current turn belongs to an AI controller via [[AITurnPolicy]].
 *  - Request a move candidate from [[AIProvider]].
 *  - Feed the candidate through [[SessionService.applyMove]] so that the same
 *    session and domain validation applies to AI moves as to human moves.
 *  - Publish [[AppEvent.AITurnRequested]], [[AppEvent.AITurnCompleted]], or
 *    [[AppEvent.AITurnFailed]] as appropriate via [[EventPublisher]].
 *
 *  The [[SideController.AI]] value passed to [[SessionService.applyMove]]
 *  satisfies the existing [[chess.application.session.policy.ActorControlPolicy]]
 *  check: any `AI(_)` matches any `AI(_)` regardless of engine id.
 *
 *  === Explicitly not responsible for ===
 *  - AI-vs-AI looping or scheduling
 *  - Choosing or registering engine implementations
 *  - Async or background processing
 *
 *  @param provider       outbound port for AI move generation
 *  @param sessionService application service for session-aware move application
 *  @param publisher      outbound port for event publication; defaults to
 *                        [[NoOpEventPublisher]] so existing callers require no change
 */
class AITurnService(
  provider:       AIProvider,
  sessionService: SessionService,
  publisher:      EventPublisher = NoOpEventPublisher
):

  /** Ask the AI to make a move for the current player in `state`.
   *
   *  On success returns the updated `(GameState, GameSession)` pair, identical
   *  in structure to what [[SessionService.applyMove]] returns for a human move.
   *
   *  Events published:
   *  - [[AppEvent.AITurnRequested]] — after the AI-turn guard passes, before the
   *    provider is called.
   *  - [[AppEvent.AITurnCompleted]] — when the move is successfully applied.
   *  - [[AppEvent.AITurnFailed]] — when the provider or move path fails (but NOT
   *    when the guard itself fails, since no turn was actually requested then).
   *
   *  The caller is responsible for loading `session` and `state` beforehand;
   *  this service does not perform repository lookups.
   *
   *  @param session current session (must already be persisted)
   *  @param state   current chess state
   *  @param now     wall-clock instant forwarded to the session lifecycle update
   */
  def requestAIMove(
    session: GameSession,
    state:   GameState,
    now:     Instant = Instant.now()
  ): Either[AITurnError, (GameState, GameSession)] =
    guardAITurn(session, state).flatMap { _ =>
      publisher.publish(AppEvent.AITurnRequested(session.sessionId, session.gameId, state.currentPlayer))
      val outcome =
        for
          response <- provider.suggestMove(state)
                        .left.map(AITurnError.ProviderFailure(_))
          result   <- sessionService.applyMove(
                        session              = session,
                        state                = state,
                        move                 = response.move,
                        requestingController = SideController.AI(),
                        now                  = now
                      ).left.map(AITurnError.MoveFailed(_))
        yield (result, response.move)
      outcome match
        case Right(((nextState, updatedSession), move)) =>
          publisher.publish(AppEvent.AITurnCompleted(session.sessionId, session.gameId, move))
          Right((nextState, updatedSession))
        case Left(err) =>
          publisher.publish(AppEvent.AITurnFailed(session.sessionId, session.gameId, reasonFor(err)))
          Left(err)
    }

  private def guardAITurn(session: GameSession, state: GameState): Either[AITurnError, Unit] =
    if AITurnPolicy.isAITurn(session, state.currentPlayer) then Right(())
    else Left(AITurnError.NotAITurn)

  private def reasonFor(err: AITurnError): String = err match
    case AITurnError.ProviderFailure(AIError.NoLegalMove)        => "no legal moves available"
    case AITurnError.ProviderFailure(AIError.EngineFailure(msg)) => s"engine failure: $msg"
    case AITurnError.MoveFailed(cause)                           => s"move rejected: $cause"
    case AITurnError.NotAITurn                                   => "not AI turn"
