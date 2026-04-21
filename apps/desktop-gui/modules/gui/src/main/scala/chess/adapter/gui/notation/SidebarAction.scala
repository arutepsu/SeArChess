package chess.adapter.gui.notation

/** Semantic actions produced by the notation sidebar widget.
  *
  * Consumed by [[NotationSidebarController]] to drive state transitions. The widget dispatches only
  * these two abstract cases; it does not hardcode FEN-specific or PGN-specific event types.
  */
enum SidebarAction:
  /** User changed the notation input field. */
  case InputTextChanged(text: String)

  /** User requested the notation action identified by `id`.
    *
    * Which API method to call and how to interpret the outcome is the controller's responsibility,
    * not the widget's.
    */
  case NotationActionRequested(id: NotationActionId)
