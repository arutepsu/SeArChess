package chess.adapter.gui.notation

/** Whether a notation action reads from the input text field or serialises the current game state.
  */
enum ActionKind:
  /** Reads from the sidebar input text area; may emit an imported [[chess.domain.state.GameState]].
    */
  case Import

  /** Serialises the current game state; populates the sidebar output area. */
  case Export

/** Describes a single notation action that the sidebar can render and execute.
  *
  * The widget renders one button per descriptor and dispatches
  * [[SidebarAction.NotationActionRequested]] with the matching [[NotationActionId]]. The controller
  * holds the mapping from id to [[GuiNotationApi]] method.
  *
  * @param id
  *   typed action identifier consumed by the controller
  * @param label
  *   human-readable button label shown in the sidebar
  * @param kind
  *   whether this is an import or export action (used for layout grouping)
  */
final case class NotationActionDescriptor(
    id: NotationActionId,
    label: String,
    kind: ActionKind
)

object NotationActionDescriptor:

  /** The canonical set of supported notation actions in display order.
    *
    * Passed to [[NotationSidebar]] at construction; callers may substitute a filtered or reordered
    * set (e.g. to disable PGN while it is unavailable, or to add future formats without changing
    * the widget).
    */
  val defaults: Seq[NotationActionDescriptor] = Seq(
    NotationActionDescriptor(NotationActionId.FenImport, "Import FEN", ActionKind.Import),
    NotationActionDescriptor(NotationActionId.PgnImport, "Import PGN", ActionKind.Import),
    NotationActionDescriptor(NotationActionId.FenExport, "Export FEN", ActionKind.Export),
    NotationActionDescriptor(NotationActionId.PgnExport, "Export PGN", ActionKind.Export)
  )
