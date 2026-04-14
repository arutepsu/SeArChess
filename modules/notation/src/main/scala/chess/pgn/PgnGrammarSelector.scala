package chess.notation.pgn

private[pgn] enum PgnParserTechnique:
  case Regex, FastParse, Combinators

private[pgn] object PgnGrammarSelector:
  val default: PgnGrammar = PgnRegexGrammar

  def byTechnique(technique: PgnParserTechnique): PgnGrammar = technique match
    case PgnParserTechnique.Regex       => PgnRegexGrammar
    case PgnParserTechnique.FastParse   => PgnFastParseGrammar
    case PgnParserTechnique.Combinators => PgnCombinatorGrammar