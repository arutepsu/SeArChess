package chess.workbench.review

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ReviewFormatterSpec extends AnyFlatSpec with Matchers:

  "ReviewFormatter.format" should "include summary, findings, and suggested next steps" in {
    val report = ReviewReport(
      summary = "Review summary",
      findings = Vector(
        ReviewFinding(
          severity = ReviewSeverity.Critical,
          category = ReviewCategory.RuleDesign,
          location = Some("rules"),
          message = "Finding message",
          reasoning = "Finding reasoning",
          suggestion = "Finding suggestion"
        )
      ),
      suggestedNextSteps = Vector("Next step")
    )

    val output = ReviewFormatter.format(report)

    output should contain("AI Review")
    output.mkString("\n") should include("Summary: Review summary")
    output.mkString("\n") should include("Critical / RuleDesign [rules]")
    output.mkString("\n") should include("Message: Finding message")
    output.mkString("\n") should include("Reasoning: Finding reasoning")
    output.mkString("\n") should include("Suggestion: Finding suggestion")
    output.mkString("\n") should include("- Next step")
  }
