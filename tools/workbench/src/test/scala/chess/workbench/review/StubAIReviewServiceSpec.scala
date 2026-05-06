package chess.workbench.review

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StubAIReviewServiceSpec extends AnyFlatSpec with Matchers:

  "StubAIReviewService.review" should "return a non-empty report with findings" in {
    val report = new StubAIReviewService().review(context)

    report.summary should not be empty
    report.findings should not be empty
  }

  it should "preserve severity and category values" in {
    val report = new StubAIReviewService().review(context)

    report.findings.map(_.severity) should contain allOf (
      ReviewSeverity.Info,
      ReviewSeverity.Warning
    )
    report.findings.map(_.category) should contain allOf (
      ReviewCategory.Architecture,
      ReviewCategory.Testing,
      ReviewCategory.Coupling
    )
  }

  it should "include suggested next steps" in {
    val report = new StubAIReviewService().review(context)

    report.suggestedNextSteps should not be empty
  }

  private val context =
    ReviewContext(
      moduleName = "workbench",
      userQuestion = "Review the Workbench review foundation.",
      notes = Vector("No test summary supplied.")
    )
