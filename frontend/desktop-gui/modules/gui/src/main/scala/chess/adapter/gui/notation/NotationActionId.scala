package chess.adapter.gui.notation

/** Typed identifier for a notation action available in the sidebar.
  *
  * Used in [[SidebarAction.NotationActionRequested]] and [[NotationActionDescriptor]] so the
  * sidebar can route actions generically without embedding FEN/PGN concepts in the widget's
  * fundamental event type.
  *
  * The controller maps each id to the corresponding [[GuiNotationApi]] call. The widget knows only
  * ids, not API methods.
  */
enum NotationActionId:
  case FenImport
  case FenExport
  case PgnImport
  case PgnExport
