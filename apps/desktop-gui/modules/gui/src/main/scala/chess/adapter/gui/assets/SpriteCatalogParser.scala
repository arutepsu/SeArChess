package chess.adapter.gui.assets

import scala.util.Try

/** Pure parser and validator for the sprite catalog JSON file.
 *
 *  Transforms a raw JSON string into a validated [[SpriteCatalog]].
 *  No file or classpath I/O is performed here — see [[SpriteCatalogLoader]]
 *  for the impure loading wrapper.
 *
 *  === Validation order ===
 *  Sections are validated with fail-fast chaining:
 *  1. JSON syntax
 *  2. root object structure + `theme`
 *  3. `clipSpecs` — all entries must have valid `frameCount` and `frameSize`
 *  4. `spriteSheets` — all entries must reference an existing `clipSpec`
 *  5. `statePlayback` — all entries must have a valid state, mode, and segments
 *     that reference existing `spriteSheets` keys
 *
 *  === No IO ===
 *  This object has no side effects and no mutable state.  All methods are
 *  referentially transparent given the same input string.
 */
object SpriteCatalogParser:

  /** Parse and validate a catalog JSON string.
   *
   *  @param json raw JSON text
   *  @return [[Right]] with a fully validated [[SpriteCatalog]], or
   *          [[Left]] with a non-empty list of [[CatalogValidationError]]s
   */
  def parse(json: String): Either[List[CatalogValidationError], SpriteCatalog] =
    Try(ujson.read(json))
      .toEither
      .left.map(e => List(CatalogValidationError.ParseError(e.getMessage)))
      .flatMap(parseRoot)

  // ── Root ─────────────────────────────────────────────────────────────────────

  private def parseRoot(v: ujson.Value): Either[List[CatalogValidationError], SpriteCatalog] =
    v match
      case ujson.Obj(root) =>
        for
          theme       <- extractString(root, "theme",        "root")
          clipsObj    <- extractObj(root,    "clipSpecs",    "root")
          sheetsObj   <- extractObj(root,    "spriteSheets", "root")
          playbackObj <- extractObj(root,    "statePlayback","root")
          clipSpecs   <- validateClipSpecs(clipsObj.toMap)
          sheets      <- validateSpriteSheets(sheetsObj.toMap, clipSpecs)
          playback    <- validateStatePlayback(playbackObj.toMap, sheets)
        yield SpriteCatalog(theme, sheets, playback)
      case _ =>
        Left(List(CatalogValidationError.MissingSection("root object")))

  // ── Field extractors ─────────────────────────────────────────────────────────

  private def extractString(
      obj: collection.Map[String, ujson.Value],
      key: String,
      ctx: String
  ): Either[List[CatalogValidationError], String] =
    obj.get(key) match
      case Some(ujson.Str(s)) if s.nonEmpty => Right(s)
      case Some(ujson.Str(_))               => Left(List(CatalogValidationError.MissingSection(s"$ctx.$key (empty string)")))
      case Some(_)                          => Left(List(CatalogValidationError.MissingSection(s"$ctx.$key (not a string)")))
      case None                             => Left(List(CatalogValidationError.MissingSection(s"$ctx.$key")))

  private def extractObj(
      obj: collection.Map[String, ujson.Value],
      key: String,
      ctx: String
  ): Either[List[CatalogValidationError], collection.Map[String, ujson.Value]] =
    obj.get(key) match
      case Some(ujson.Obj(m)) => Right(m)
      case Some(_)            => Left(List(CatalogValidationError.MissingSection(s"$ctx.$key (not an object)")))
      case None               => Left(List(CatalogValidationError.MissingSection(s"$ctx.$key")))

  // ── clipSpecs ─────────────────────────────────────────────────────────────────

  private def validateClipSpecs(
      raw: Map[String, ujson.Value]
  ): Either[List[CatalogValidationError], Map[String, ClipSpecEntry]] =
    val results = raw.map { (key, v) => key -> validateClipSpec(key, v) }
    val errors  = results.values.collect { case Left(e) => e }.toList
    if errors.nonEmpty then Left(errors)
    else Right(results.collect { case (k, Right(v)) => k -> v })

  private def validateClipSpec(
      key: String,
      v:   ujson.Value
  ): Either[CatalogValidationError, ClipSpecEntry] =
    v match
      case ujson.Obj(obj) =>
        for
          frameCount  <- extractPositiveInt(obj, "frameCount",  key, CatalogValidationError.InvalidClipSpec(key, _))
          frameSize   <- extractFrameSize(obj, key)
          displaySize <- extractOptionalSize(obj, "displaySize", key, CatalogValidationError.InvalidClipSpec(key, _))
          anchor      <- extractOptionalPoint(obj, "anchor",     key, CatalogValidationError.InvalidClipSpec(key, _))
        yield ClipSpecEntry(frameCount, frameSize, displaySize, anchor)
      case _ =>
        Left(CatalogValidationError.InvalidClipSpec(key, "entry is not an object"))

  private def extractFrameSize(
      obj: collection.Map[String, ujson.Value],
      ctx: String
  ): Either[CatalogValidationError, (Int, Int)] =
    obj.get("frameSize") match
      case Some(ujson.Obj(fs)) =>
        for
          w <- extractPositiveInt(fs, "width",  ctx, CatalogValidationError.InvalidClipSpec(ctx, _))
          h <- extractPositiveInt(fs, "height", ctx, CatalogValidationError.InvalidClipSpec(ctx, _))
        yield (w, h)
      case Some(_) => Left(CatalogValidationError.InvalidClipSpec(ctx, "frameSize is not an object"))
      case None    => Left(CatalogValidationError.InvalidClipSpec(ctx, "missing frameSize"))

  private def extractOptionalSize(
      obj:     collection.Map[String, ujson.Value],
      field:   String,
      ctx:     String,
      mkError: String => CatalogValidationError
  ): Either[CatalogValidationError, Option[(Double, Double)]] =
    obj.get(field) match
      case None => Right(None)
      case Some(ujson.Obj(fs)) =>
        for
          w <- extractPositiveDouble(fs, "width",  ctx, mkError)
          h <- extractPositiveDouble(fs, "height", ctx, mkError)
        yield Some((w, h))
      case Some(_) => Left(mkError(s"$field is not an object"))

  private def extractOptionalPoint(
      obj:     collection.Map[String, ujson.Value],
      field:   String,
      ctx:     String,
      mkError: String => CatalogValidationError
  ): Either[CatalogValidationError, Option[(Double, Double)]] =
    obj.get(field) match
      case None => Right(None)
      case Some(ujson.Obj(pt)) =>
        for
          x <- extractDouble(pt, "x", ctx, mkError)
          y <- extractDouble(pt, "y", ctx, mkError)
        yield Some((x, y))
      case Some(_) => Left(mkError(s"$field is not an object"))

  // ── spriteSheets ──────────────────────────────────────────────────────────────

  private def validateSpriteSheets(
      raw:       Map[String, ujson.Value],
      clipSpecs: Map[String, ClipSpecEntry]
  ): Either[List[CatalogValidationError], Map[String, SpriteSheetEntry]] =
    val results = raw.map { (key, v) => key -> validateSpriteSheet(key, v, clipSpecs) }
    val errors  = results.values.collect { case Left(e) => e }.toList
    if errors.nonEmpty then Left(errors)
    else Right(results.collect { case (k, Right(v)) => k -> v })

  private def validateSpriteSheet(
      key:       String,
      v:         ujson.Value,
      clipSpecs: Map[String, ClipSpecEntry]
  ): Either[CatalogValidationError, SpriteSheetEntry] =
    v match
      case ujson.Obj(obj) =>
        for
          path     <- extractNonEmptyString(obj, "path",     key, CatalogValidationError.InvalidSpriteSheet(key, _))
          csKey    <- extractNonEmptyString(obj, "clipSpec", key, CatalogValidationError.InvalidSpriteSheet(key, _))
          clipSpec <- clipSpecs.get(csKey).toRight(
                        CatalogValidationError.InvalidSpriteSheet(key, s"clipSpec '$csKey' not found"))
        yield SpriteSheetEntry(key, path, clipSpec)
      case _ =>
        Left(CatalogValidationError.InvalidSpriteSheet(key, "entry is not an object"))

  // ── statePlayback ─────────────────────────────────────────────────────────────

  private def validateStatePlayback(
      raw:    Map[String, ujson.Value],
      sheets: Map[String, SpriteSheetEntry]
  ): Either[List[CatalogValidationError], Map[String, StatePlaybackEntry]] =
    val results = raw.map { (key, v) => key -> validateStatePlaybackEntry(key, v, sheets) }
    val errors  = results.values.collect { case Left(e) => e }.toList
    if errors.nonEmpty then Left(errors)
    else Right(results.collect { case (k, Right(v)) => k -> v })

  private def validateStatePlaybackEntry(
      key:    String,
      v:      ujson.Value,
      sheets: Map[String, SpriteSheetEntry]
  ): Either[CatalogValidationError, StatePlaybackEntry] =
    v match
      case ujson.Obj(obj) =>
        for
          stateStr  <- extractNonEmptyString(obj, "state", key, CatalogValidationError.InvalidStatePlayback(key, _))
          modeStr   <- extractNonEmptyString(obj, "mode",  key, CatalogValidationError.InvalidStatePlayback(key, _))
          state     <- parseVisualState(stateStr).toRight(
                         CatalogValidationError.InvalidStatePlayback(key, s"unknown state '$stateStr'"))
          mode      <- parsePlaybackMode(modeStr).toRight(
                         CatalogValidationError.InvalidStatePlayback(key, s"unknown mode '$modeStr'"))
          segments  <- extractSegments(obj, key, sheets)
        yield StatePlaybackEntry(state, mode, segments)
      case _ =>
        Left(CatalogValidationError.InvalidStatePlayback(key, "entry is not an object"))

  private def extractSegments(
      obj:    collection.Map[String, ujson.Value],
      ctx:    String,
      sheets: Map[String, SpriteSheetEntry]
  ): Either[CatalogValidationError, Seq[String]] =
    obj.get("segments") match
      case None =>
        Left(CatalogValidationError.InvalidStatePlayback(ctx, "missing segments"))
      case Some(ujson.Arr(arr)) if arr.isEmpty =>
        Left(CatalogValidationError.InvalidStatePlayback(ctx, "segments must be non-empty"))
      case Some(ujson.Arr(arr)) =>
        val keys = arr.toSeq.map {
          case ujson.Str(s) => Right(s)
          case _            => Left(CatalogValidationError.InvalidStatePlayback(ctx, "segment entry is not a string"))
        }
        val strErrors = keys.collect { case Left(e) => e }
        if strErrors.nonEmpty then Left(strErrors.head)
        else
          val segKeys = keys.collect { case Right(s) => s }
          val missing = segKeys.find(k => !sheets.contains(k))
          missing match
            case Some(k) => Left(CatalogValidationError.InvalidStatePlayback(ctx, s"segment '$k' not found in spriteSheets"))
            case None    => Right(segKeys)
      case Some(_) =>
        Left(CatalogValidationError.InvalidStatePlayback(ctx, "segments is not an array"))

  // ── Primitive extractors ─────────────────────────────────────────────────────

  private def extractPositiveInt(
      obj:     collection.Map[String, ujson.Value],
      key:     String,
      ctx:     String,
      mkError: String => CatalogValidationError
  ): Either[CatalogValidationError, Int] =
    obj.get(key) match
      case Some(ujson.Num(n)) if n.toInt > 0 => Right(n.toInt)
      case Some(ujson.Num(_))                 => Left(mkError(s"$key must be > 0"))
      case Some(_)                            => Left(mkError(s"$key is not a number"))
      case None                               => Left(mkError(s"missing $key"))

  private def extractPositiveDouble(
      obj:     collection.Map[String, ujson.Value],
      key:     String,
      ctx:     String,
      mkError: String => CatalogValidationError
  ): Either[CatalogValidationError, Double] =
    obj.get(key) match
      case Some(ujson.Num(n)) if n > 0.0 => Right(n)
      case Some(ujson.Num(_))             => Left(mkError(s"$key must be > 0"))
      case Some(_)                        => Left(mkError(s"$key is not a number"))
      case None                           => Left(mkError(s"missing $key"))

  private def extractDouble(
      obj:     collection.Map[String, ujson.Value],
      key:     String,
      ctx:     String,
      mkError: String => CatalogValidationError
  ): Either[CatalogValidationError, Double] =
    obj.get(key) match
      case Some(ujson.Num(n)) => Right(n)
      case Some(_)            => Left(mkError(s"$key is not a number"))
      case None               => Left(mkError(s"missing $key"))

  private def extractNonEmptyString(
      obj:     collection.Map[String, ujson.Value],
      key:     String,
      ctx:     String,
      mkError: String => CatalogValidationError
  ): Either[CatalogValidationError, String] =
    obj.get(key) match
      case Some(ujson.Str(s)) if s.nonEmpty => Right(s)
      case Some(ujson.Str(_))               => Left(mkError(s"$key is empty"))
      case Some(_)                          => Left(mkError(s"$key is not a string"))
      case None                             => Left(mkError(s"missing $key"))

  // ── Enum parsers ──────────────────────────────────────────────────────────────

  private def parseVisualState(s: String): Option[VisualState] = s match
    case "Idle"   => Some(VisualState.Idle)
    case "Move"   => Some(VisualState.Move)
    case "Attack" => Some(VisualState.Attack)
    case "Hit"    => Some(VisualState.Hit)
    case "Dead"   => Some(VisualState.Dead)
    case _        => None

  private def parsePlaybackMode(s: String): Option[PlaybackMode] = s match
    case "Clamp" => Some(PlaybackMode.Clamp)
    case "Loop"  => Some(PlaybackMode.Loop)
    case _       => None
