package chess.workbench.review

final class StubAIReviewService extends AIReviewService:
  override def review(context: ReviewContext): ReviewReport =
    ReviewReport(
      summary = s"Stub AI review for ${context.moduleName}. No external AI provider was called.",
      findings = Vector(
        ReviewFinding(
          severity = ReviewSeverity.Info,
          category = ReviewCategory.Architecture,
          location = Some(context.moduleName),
          message = "Review system is wired correctly.",
          reasoning = "The Workbench can create a review context and receive a report through the AIReviewService boundary.",
          suggestion = "Keep future provider-specific behavior behind the AIReviewService trait."
        ),
        ReviewFinding(
          severity = ReviewSeverity.Warning,
          category = ReviewCategory.Testing,
          location = None,
          message = "No test summary was provided.",
          reasoning = "The current review context is intentionally minimal and does not include test results yet.",
          suggestion = "Later, pass explicit test output or a summarized test signal into ReviewContext."
        ),
        ReviewFinding(
          severity = ReviewSeverity.Info,
          category = ReviewCategory.Coupling,
          location = Some("StubAIReviewService"),
          message = "AI review is currently using a stub implementation.",
          reasoning = "This keeps step 1 deterministic, side-effect free, and safe to run locally.",
          suggestion = "Replace this implementation with a real provider adapter only after the command boundary is stable."
        )
      ),
      suggestedNextSteps = Vector(
        "Expose the review command from the Workbench entrypoint.",
        "Add a formatter that can be reused by the TUI.",
        "Extend ReviewContext with explicit, precomputed project signals."
      )
    )
