package chess.application.port.ai

/** Outbound port for AI move generation.
 *
 *  An AI provider receives an application-owned [[AIRequestContext]] and returns
 *  a proposed [[AIResponse]] or an [[AIError]] if no candidate can be produced.
 *
 *  The port remains intentionally narrow in authority: providers may use the
 *  context to choose an engine, correlate diagnostics, and build a remote
 *  request, but they still return only a move candidate. Game Service remains
 *  responsible for legality, persistence, lifecycle changes, and events.
 *
 *  Implementations belong in `chess.adapter.*`; the application layer depends
 *  only on this trait, never on a concrete class.
 */
trait AIProvider:

  /** Propose a move for the current player described by `context`.
   *
   *  @return [[Right]] with the proposed [[AIResponse]], or [[Left]] with an
   *          [[AIError]] when the engine cannot produce a candidate.
   */
  def suggestMove(context: AIRequestContext): Either[AIError, AIResponse]
