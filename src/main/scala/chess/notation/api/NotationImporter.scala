package chess.notation.api

/** Contract for importing a [[ParsedNotation]] IR into a typed result.
 *
 *  Responsibilities:
 *  - accept a [[ParsedNotation]] produced by a [[NotationParser]]
 *  - accept an [[ImportTarget]] describing the intended destination
 *  - perform semantic validation (e.g. legal position, valid move sequence)
 *  - map the IR to the result type `A`
 *  - return a structured [[NotationFailure]] on any failure
 *
 *  The type parameter `A` is the domain-side result type (e.g. `GameState`,
 *  a replay structure, etc.).  It is left abstract here so the contract
 *  remains decoupled from domain types.
 *
 *  A [[NotationImporter]] MUST NOT re-parse raw text — that is the
 *  [[NotationParser]]'s responsibility.
 */
trait NotationImporter[A]:
  /** Map `parsed` to an [[ImportResult]] for the given `target`.
   *
   *  @param parsed the IR produced by a [[NotationParser]]
   *  @param target the intended import destination
   *  @return [[Right]] on success, [[Left]] with a structured failure otherwise
   */
  def importNotation(
    parsed: ParsedNotation,
    target: ImportTarget
  ): Either[NotationFailure, ImportResult[A]]
