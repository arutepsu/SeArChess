package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}

/** Repository of [[StatePlaybackMetadata]] entries for all known piece/state combinations.
  *
  * In production use, instances are created via [[StatePlaybackRepository.fromCatalog]] which
  * derives all playback definitions from the validated [[SpriteCatalog]]. Test code may construct
  * instances directly with an explicit entry map.
  *
  * The lookup interface is keyed by `(Color, PieceType, VisualState)` for ergonomics; internally
  * the primary asset key is derived via [[VisualResolver]] to look up the catalog's `statePlayback`
  * map.
  *
  * @param entries
  *   playback map keyed by primary asset key (e.g. `"classic/white_pawn_attack"`)
  */
final class StatePlaybackRepository(private val entries: Map[String, StatePlaybackMetadata]):

  /** Return the [[StatePlaybackMetadata]] for the given piece and state, or [[None]] if no entry is
    * registered.
    *
    * Internally derives the lookup key via `VisualResolver.resolve(PieceVisualId(color, pieceType,
    * state)).assetKey`.
    */
  def lookup(
      color: Color,
      pieceType: PieceType,
      state: VisualState
  ): Option[StatePlaybackMetadata] =
    val key = VisualResolver.resolve(PieceVisualId(color, pieceType, state)).assetKey
    entries.get(key)

/** Companion providing the catalog-backed factory. */
object StatePlaybackRepository:

  /** Build a [[StatePlaybackRepository]] from a validated [[SpriteCatalog]].
    *
    * One [[StatePlaybackMetadata]] entry is created for every entry in `catalog.statePlayback`,
    * converting segment asset-key strings to [[PlaybackSegmentRef]] values.
    */
  def fromCatalog(catalog: SpriteCatalog): StatePlaybackRepository =
    val entries = catalog.statePlayback.map { (key, entry) =>
      key -> StatePlaybackMetadata(
        state = entry.state,
        segments = entry.segments.map(PlaybackSegmentRef(_)),
        mode = entry.mode
      )
    }
    StatePlaybackRepository(entries)
