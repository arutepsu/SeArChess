/** Outbound port: AI engine abstraction.
 *
 *  Responsibilities:
 *  - interface definition for AI move suggestion (e.g. AiEngine)
 *  - no implementation — implementations live in chess.adapter.* packages (Stockfish, random, etc.)
 *
 *  Example interface to introduce when AI integration is needed:
 *  {{{
 *  trait AiEngine:
 *    def suggestMove(state: GameState, config: AiConfig): Either[AiError, Move]
 *  }}}
 *
 *  Not yet populated.
 */
package chess.application.port.ai
