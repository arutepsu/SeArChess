package chess.application.ai.service

import chess.application.ai.policy.AITurnPolicy
import chess.application.event.AppEvent
import chess.application.port.ai.{AIError, AiMoveSuggestionClient, AIRequestContext}
import chess.application.port.event.EventPublisher
import chess.application.session.model.{GameSession, SideController}
import chess.application.session.service.GameSessionCommands
import chess.domain.model.Move
import chess.domain.rules.GameStateRules
import chess.domain.state.GameState
import java.time.Instant

/** Application service that drives a single AI-controlled move.
 *
 *  === Responsibilities ===
 *  - Verify the current turn belongs to an AI controller via [[AITurnPolicy]].
 *  - Request a move candidate from [[AiMoveSuggestionClient]].
 *  - Feed the candidate through [[GameSessionCommands.submitMove]] — the game-session
 *    command boundary — so that domain validation, session-lifecycle persistence,
 *    game-state persistence, and event publication all apply to AI moves in exactly
 *    the same way as to human moves.
 *  - Publish [[AppEvent.AITurnRequested]], [[AppEvent.AITurnCompleted]], or
 *    [[AppEvent.AITurnFailed]] as appropriate via [[EventPublisher]].
 *
 *  The [[SideController.AI]] value passed to [[GameSessionCommands.submitMove]]
 *  satisfies the existing [[chess.application.session.policy.ActorControlPolicy]]
 *  check: any `AI(_)` matches any `AI(_)` regardless of engine id.
 *
 *  === Explicitly not responsible for ===
 *  - AI-vs-AI looping or scheduling
 *  - Choosing or registering engine implementations
 *  - Async or background processing
 *
 *  @param provider  outbound port for AI move suggestion
 *  @param commands  game-session command boundary
 *  @param publisher outbound port for event publication
 */
class AITurnService(
  provider:  AiMoveSuggestionClient,
  commands:  GameSessionCommands,
  publisher: EventPublisher
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
      val aiContext = AIRequestContext.fromSession(session, state)
      val outcome =
        for
          response <- provider.suggestMove(aiContext)
                        .left.map(AITurnError.ProviderFailure(_))
          _        <- validateSuggestedMove(state, response.move)
          result   <- commands.submitMove(
                        session    = session,
                        state      = state,
                        move       = response.move,
                        controller = SideController.AI(aiContext.engineId),
                        now        = now
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

  private def validateSuggestedMove(state: GameState, move: Move): Either[AITurnError, Unit] =
    if GameStateRules.legalMoves(state).contains(move) then Right(())
    else Left(AITurnError.IllegalSuggestedMove(move))

  private def reasonFor(err: AITurnError): String = err match
    case AITurnError.ProviderFailure(AIError.NoLegalMove)        => "no legal moves available"
    case AITurnError.ProviderFailure(AIError.Unavailable(msg))    => s"provider unavailable: $msg"
    case AITurnError.ProviderFailure(AIError.Timeout(msg))        => s"provider timeout: $msg"
    case AITurnError.ProviderFailure(AIError.MalformedResponse(msg)) => s"malformed provider response: $msg"
    case AITurnError.ProviderFailure(AIError.EngineFailure(msg)) => s"engine failure: $msg"
    case AITurnError.IllegalSuggestedMove(move)                   => s"illegal AI suggestion: $move"
    case AITurnError.MoveFailed(cause)                           => s"move rejected: $cause"
    case AITurnError.NotAITurn                                   => "not AI turn"
    case AITurnError.NotConfigured                               => "AI not configured"
    case AITurnError.SessionLookupFailed(cause)                  => s"session lookup failed: $cause"
    case AITurnError.GameStateLookupFailed(cause)                => s"game state lookup failed: $cause"
