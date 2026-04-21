package chess.notation.fen

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class FenGrammarSelectorSpec extends AnyWordSpec with Matchers:

  "FenGrammarSelector.default" should {
    "return the combinator grammar" in {
      FenGrammarSelector.default shouldBe FenCombinatorGrammar
    }
  }

  "FenGrammarSelector.byTechnique" should {
    "return FenCombinatorGrammar for Combinators" in {
      FenGrammarSelector.byTechnique(FenParserTechnique.Combinators) shouldBe FenCombinatorGrammar
    }

    "return FenFastParseGrammar for FastParse" in {
      FenGrammarSelector.byTechnique(FenParserTechnique.FastParse) shouldBe FenFastParseGrammar
    }

    "return FenRegexGrammar for Regex" in {
      FenGrammarSelector.byTechnique(FenParserTechnique.Regex) shouldBe FenRegexGrammar
    }
  }
