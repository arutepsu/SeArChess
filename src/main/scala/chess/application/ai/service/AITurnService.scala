package chess.application.ai.service

import chess.application.ai.policy.AITurnPolicy
import chess.application.port.ai.AIProvider
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
 *  @param provider      outbound port for AI move generation
 *  @param sessionService application service for session-aware move application
 */
class AITurnService(provider: AIProvider, sessionService: SessionService):

  /** Ask the AI to make a move for the current player in `state`.
   *
   *  On success returns the updated `(GameState, GameSession)` pair, identical
   *  in structure to what [[SessionService.applyMove]] returns for a human move.
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
    for
      _        <- guardAITurn(session, state)
      response <- provider.suggestMove(state)
                    .left.map(AITurnError.ProviderFailure(_))
      result   <- sessionService.applyMove(
                    session              = session,
                    state                = state,
                    move                 = response.move,
                    requestingController = SideController.AI(),
                    now                  = now
                  ).left.map(AITurnError.MoveFailed(_))
    yield result

  private def guardAITurn(session: GameSession, state: GameState): Either[AITurnError, Unit] =
    if AITurnPolicy.isAITurn(session, state.currentPlayer) then Right(())
    else Left(AITurnError.NotAITurn)
