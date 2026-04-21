package chess.notation.fen

private[fen] enum FenParserTechnique:
  case Combinators, FastParse, Regex

private[fen] object FenGrammarSelector:
  val default: FenGrammar = FenCombinatorGrammar

  def byTechnique(technique: FenParserTechnique): FenGrammar = technique match
    case FenParserTechnique.Combinators => FenCombinatorGrammar
    case FenParserTechnique.FastParse   => FenFastParseGrammar
    case FenParserTechnique.Regex       => FenRegexGrammar
