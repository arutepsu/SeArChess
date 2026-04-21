/** Outbound port: AI engine abstraction.
  *
  * Responsibilities:
  *   - interface definition for AI move suggestion (e.g. AiEngine)
  *   - no implementation — implementations live in chess.adapter.* packages (Stockfish, random,
  *     etc.)
  *
  * Example interface shape:
  * {{{
  *  trait AiEngine:
  *    def suggestMove(context: AIRequestContext): Either[AIError, AIResponse]
  * }}}
  */
package chess.application.port.ai
