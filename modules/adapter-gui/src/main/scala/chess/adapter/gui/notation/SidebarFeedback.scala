package chess.adapter.gui.notation

/** Feedback message displayed inside the notation sidebar after an import or export operation.
 *
 *  Owned exclusively by the sidebar's local state.
 *  GUI callers present this without needing notation-layer error types.
 */
sealed trait SidebarFeedback

object SidebarFeedback:

  /** The last operation succeeded.
   *
   *  @param message human-readable confirmation text
   */
  final case class Success(message: String) extends SidebarFeedback

  /** The last operation failed.
   *
   *  @param message  primary human-readable description
   *  @param details  optional supplementary detail (e.g. location in the input)
   *  @param category coarse category for presentation decisions
   */
  final case class Failure(
    message:  String,
    details:  Option[String],
    category: FailureCategory
  ) extends SidebarFeedback
