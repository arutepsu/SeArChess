package chess.workbench.review

final case class ReviewFinding(
    severity: ReviewSeverity,
    category: ReviewCategory,
    location: Option[String],
    message: String,
    reasoning: String,
    suggestion: String
)
