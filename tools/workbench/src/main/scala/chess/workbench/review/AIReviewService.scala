package chess.workbench.review

trait AIReviewService:
  def review(context: ReviewContext): ReviewReport
