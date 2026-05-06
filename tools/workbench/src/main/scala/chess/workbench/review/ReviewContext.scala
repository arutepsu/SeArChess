package chess.workbench.review

final case class ReviewContext(
    moduleName: String,
    userQuestion: String,
    notes: Vector[String]
)
