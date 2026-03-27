package chess.adapter.gui.assets

/** UI-facing visual state for a piece.
 *
 *  Drives which asset variant is selected for a piece at a given moment.
 *  Not all states need real assets in every phase — [[Idle]] is the only
 *  one currently backed by placeholder glyphs; the others are named here
 *  so that the asset layer is ready for future sprite variants without a
 *  structural change.
 *
 *  States intentionally have no behavior or transition logic here; any
 *  choreography belongs in a future animation/state-machine layer.
 */
enum VisualState:
  /** Standing still on the board. Default for all statically rendered pieces. */
  case Idle

  /** Travelling from one square to another. Applied to the animated moving piece. */
  case Move

  /** Delivering a capture. Reserved for future attack-sprite variants. */
  case Attack

  /** Receiving a capture blow. Reserved for future hit-sprite variants. */
  case Hit

  /** Removed from the board. Applied to the fading captured piece. */
  case Dead
