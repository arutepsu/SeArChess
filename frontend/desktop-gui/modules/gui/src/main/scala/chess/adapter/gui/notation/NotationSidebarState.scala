package chess.adapter.gui.notation

/** Immutable local state of the notation sidebar.
  *
  * This is the sidebar's view model — owned and mutated only by the sidebar controller. It does NOT
  * hold authoritative application [[chess.domain.state.GameState]].
  *
  * @param inputText
  *   the current notation text typed by the user
  * @param outputText
  *   the most recent export result, if any
  * @param feedback
  *   the most recent operation result message, if any
  * @param warnings
  *   structured warnings from the most recent successful import
  */
final case class NotationSidebarState(
    inputText: String = "",
    outputText: Option[String] = None,
    feedback: Option[SidebarFeedback] = None,
    warnings: List[GuiNotationWarning] = Nil
)
