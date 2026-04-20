package chess.adapter.gui.assets

/** Describes one validation failure encountered while parsing or validating a
 *  sprite catalog JSON file.
 *
 *  Produced by [[SpriteCatalogParser]] and surfaced by [[SpriteCatalogLoader]]
 *  as part of a startup-time `IllegalStateException` when the catalog is
 *  malformed.
 */
enum CatalogValidationError:

  /** The JSON text could not be parsed (syntax error or unexpected structure).
   *  @param message the underlying parser error message */
  case ParseError(message: String)

  /** A required top-level section or field is absent or has the wrong type.
   *  @param section human-readable path to the missing element
   *                 (e.g. `"root.theme"`, `"root.clipSpecs"`) */
  case MissingSection(section: String)

  /** A `clipSpec` entry fails one or more structural constraints.
   *  @param key    the clip-spec key that failed
   *  @param reason description of the constraint that was violated */
  case InvalidClipSpec(key: String, reason: String)

  /** A `spriteSheets` entry fails one or more structural constraints.
   *  @param key    the sprite-sheet asset key that failed
   *  @param reason description of the constraint that was violated */
  case InvalidSpriteSheet(key: String, reason: String)

  /** A `statePlayback` entry fails one or more structural constraints.
   *  @param key    the playback entry key that failed
   *  @param reason description of the constraint that was violated */
  case InvalidStatePlayback(key: String, reason: String)
