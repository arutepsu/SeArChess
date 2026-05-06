package chess.workbench.review

final case class ReviewReport(
    summary: String,
    findings: Vector[ReviewFinding],
    suggestedNextSteps: Vector[String]
)
