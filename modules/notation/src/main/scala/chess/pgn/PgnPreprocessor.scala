package chess.notation.pgn

/** Shared PGN-core preprocessing.
  *
  * Normalizes raw PGN text before grammar parsing by stripping:
  *   - comments `{...}`
  *   - parenthesized variations `(...)` iteratively
  *   - NAGs `$N`
  *
  * Tag pairs are intentionally NOT removed here; grammars still parse headers.
  */
private[pgn] object PgnPreprocessor:

  def stripAnnotations(input: String): String =
    val noComments = input.replaceAll("""\{[^}]*\}""", " ")
    val noVariations = stripNestedVariations(noComments)
    noVariations.replaceAll("""\$\d+""", " ")

  private def stripNestedVariations(text: String): String =
    var current = text
    var previous = ""
    while current != previous do
      previous = current
      current = current.replaceAll("""\([^()]*\)""", " ")
    current
