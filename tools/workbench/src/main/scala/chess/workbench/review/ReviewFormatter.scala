package chess.workbench.review

object ReviewFormatter:
  def format(report: ReviewReport): Vector[String] =
    Vector("AI Review", s"Summary: ${report.summary}", "", "Findings:") ++
      findingLines(report.findings) ++
      Vector("", "Suggested next steps:") ++
      nextStepLines(report.suggestedNextSteps)

  private def findingLines(findings: Vector[ReviewFinding]): Vector[String] =
    if findings.isEmpty then Vector("none")
    else findings.zipWithIndex.flatMap { case (finding, index) =>
      val location = finding.location.fold("")(value => s" [$value]")
      Vector(
        s"${index + 1}. ${finding.severity} / ${finding.category}$location",
        s"   Message: ${finding.message}",
        s"   Reasoning: ${finding.reasoning}",
        s"   Suggestion: ${finding.suggestion}"
      )
    }

  private def nextStepLines(steps: Vector[String]): Vector[String] =
    if steps.isEmpty then Vector("none")
    else steps.map(step => s"- $step")
