package chess.notation.pgn

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class PgnGrammarSelectorSpec extends AnyWordSpec with Matchers:

  "PgnGrammarSelector.default" should {
    "return the regex grammar" in {
      PgnGrammarSelector.default shouldBe PgnRegexGrammar
    }
  }

  "PgnGrammarSelector.byTechnique" should {
    "return PgnRegexGrammar for Regex" in {
      PgnGrammarSelector.byTechnique(PgnParserTechnique.Regex) shouldBe PgnRegexGrammar
    }

    "return PgnFastParseGrammar for FastParse" in {
      PgnGrammarSelector.byTechnique(PgnParserTechnique.FastParse) shouldBe PgnFastParseGrammar
    }

    "return PgnCombinatorGrammar for Combinators" in {
      PgnGrammarSelector.byTechnique(PgnParserTechnique.Combinators) shouldBe PgnCombinatorGrammar
    }
  }