package chess.adapter.gui.assets

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpriteCatalogParserSpec extends AnyFlatSpec with Matchers:

  // ── JSON fixture helpers ──────────────────────────────────────────────────

  /** Minimal valid clip specs covering all 5 visual states (for full branch coverage of the state
    * parser).
    */
  private val allClips: String =
    """|"pawn_idle":    {"frameCount": 1, "frameSize": {"width": 64, "height": 64}},
       |"pawn_move":    {"frameCount": 4, "frameSize": {"width": 64, "height": 64}},
       |"pawn_attack":  {"frameCount": 6, "frameSize": {"width": 64, "height": 64}},
       |"pawn_attack1": {"frameCount": 6, "frameSize": {"width": 64, "height": 64}},
       |"pawn_hit":     {"frameCount": 3, "frameSize": {"width": 64, "height": 64}},
       |"pawn_dead":    {"frameCount": 8, "frameSize": {"width": 64, "height": 64}}""".stripMargin

  private val allSheets: String =
    """|"classic/white_pawn_idle":    {"path": "assets/pawn/idle.png",    "clipSpec": "pawn_idle"},
       |"classic/white_pawn_move":    {"path": "assets/pawn/move.png",    "clipSpec": "pawn_move"},
       |"classic/white_pawn_attack":  {"path": "assets/pawn/attack.png",  "clipSpec": "pawn_attack"},
       |"classic/white_pawn_attack1": {"path": "assets/pawn/attack1.png", "clipSpec": "pawn_attack1"},
       |"classic/white_pawn_hit":     {"path": "assets/pawn/hit.png",     "clipSpec": "pawn_hit"},
       |"classic/white_pawn_dead":    {"path": "assets/pawn/dead.png",    "clipSpec": "pawn_dead"}""".stripMargin

  /** One playback entry per state, covering Loop and Clamp modes. */
  private val allPlayback: String =
    """|"classic/white_pawn_idle":   {"state": "Idle",   "mode": "Clamp", "segments": ["classic/white_pawn_idle"]},
       |"classic/white_pawn_move":   {"state": "Move",   "mode": "Clamp", "segments": ["classic/white_pawn_move"]},
       |"classic/white_pawn_attack": {"state": "Attack", "mode": "Loop",  "segments": ["classic/white_pawn_attack", "classic/white_pawn_attack1"]},
       |"classic/white_pawn_hit":    {"state": "Hit",    "mode": "Clamp", "segments": ["classic/white_pawn_hit"]},
       |"classic/white_pawn_dead":   {"state": "Dead",   "mode": "Clamp", "segments": ["classic/white_pawn_dead"]}""".stripMargin

  private def fullCatalog(
      theme: String = """"classic"""",
      clips: String = allClips,
      sheets: String = allSheets,
      playback: String = allPlayback
  ): String =
    s"""{"theme": $theme, "clipSpecs": {$clips}, "spriteSheets": {$sheets}, "statePlayback": {$playback}}"""

  private def singleCatalog(
      clipKey: String = "pawn_move",
      clipVal: String = """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}}""",
      sheetKey: String = "classic/white_pawn_move",
      sheetVal: String = """{"path": "assets/pawn/move.png", "clipSpec": "pawn_move"}""",
      playbackKey: String = "classic/white_pawn_move",
      playbackVal: String =
        """{"state": "Move", "mode": "Clamp", "segments": ["classic/white_pawn_move"]}"""
  ): String =
    s"""{"theme": "t", "clipSpecs": {"$clipKey": $clipVal}, "spriteSheets": {"$sheetKey": $sheetVal}, "statePlayback": {"$playbackKey": $playbackVal}}"""

  // ── Happy path ────────────────────────────────────────────────────────────

  "SpriteCatalogParser.parse" should "parse a valid minimal catalog" in {
    val result = SpriteCatalogParser.parse(singleCatalog())
    result shouldBe a[Right[?, ?]]
    val catalog = result.toOption.getOrElse(fail("expected parse success"))
    catalog.theme shouldBe "t"
    catalog.spriteSheets should have size 1
    catalog.statePlayback should have size 1
  }

  it should "return the theme string from the JSON" in {
    val catalog =
      SpriteCatalogParser.parse(fullCatalog()).toOption.getOrElse(fail("expected parse success"))
    catalog.theme shouldBe "classic"
  }

  it should "inline the clipSpec into each SpriteSheetEntry" in {
    val catalog =
      SpriteCatalogParser.parse(singleCatalog()).toOption.getOrElse(fail("expected parse success"))
    val sheet = catalog.spriteSheets("classic/white_pawn_move")
    sheet.assetKey shouldBe "classic/white_pawn_move"
    sheet.path shouldBe "assets/pawn/move.png"
    sheet.clipSpec.frameCount shouldBe 4
    sheet.clipSpec.frameSize shouldBe (64, 64)
  }

  it should "parse all 5 VisualState values" in {
    val catalog =
      SpriteCatalogParser.parse(fullCatalog()).toOption.getOrElse(fail("expected parse success"))
    catalog.statePlayback("classic/white_pawn_idle").state shouldBe VisualState.Idle
    catalog.statePlayback("classic/white_pawn_move").state shouldBe VisualState.Move
    catalog.statePlayback("classic/white_pawn_attack").state shouldBe VisualState.Attack
    catalog.statePlayback("classic/white_pawn_hit").state shouldBe VisualState.Hit
    catalog.statePlayback("classic/white_pawn_dead").state shouldBe VisualState.Dead
  }

  it should "parse both PlaybackMode values" in {
    val catalog =
      SpriteCatalogParser.parse(fullCatalog()).toOption.getOrElse(fail("expected parse success"))
    catalog.statePlayback("classic/white_pawn_move").mode shouldBe PlaybackMode.Clamp
    catalog.statePlayback("classic/white_pawn_attack").mode shouldBe PlaybackMode.Loop
  }

  it should "parse a multi-segment Attack entry" in {
    val catalog =
      SpriteCatalogParser.parse(fullCatalog()).toOption.getOrElse(fail("expected parse success"))
    val entry = catalog.statePlayback("classic/white_pawn_attack")
    entry.segments shouldBe Seq("classic/white_pawn_attack", "classic/white_pawn_attack1")
  }

  it should "parse displaySize when present" in {
    val json = singleCatalog(clipVal =
      """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "displaySize": {"width": 80.0, "height": 80.0}}"""
    )
    val catalog = SpriteCatalogParser.parse(json).toOption.getOrElse(fail("expected parse success"))
    catalog.spriteSheets("classic/white_pawn_move").clipSpec.displaySize shouldBe Some((80.0, 80.0))
  }

  it should "parse anchor when present" in {
    val json = singleCatalog(clipVal =
      """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "anchor": {"x": 0.5, "y": 0.0}}"""
    )
    val catalog = SpriteCatalogParser.parse(json).toOption.getOrElse(fail("expected parse success"))
    catalog.spriteSheets("classic/white_pawn_move").clipSpec.anchor shouldBe Some((0.5, 0.0))
  }

  it should "leave displaySize as None when absent" in {
    val catalog =
      SpriteCatalogParser.parse(singleCatalog()).toOption.getOrElse(fail("expected parse success"))
    catalog.spriteSheets("classic/white_pawn_move").clipSpec.displaySize shouldBe None
  }

  it should "leave anchor as None when absent" in {
    val catalog =
      SpriteCatalogParser.parse(singleCatalog()).toOption.getOrElse(fail("expected parse success"))
    catalog.spriteSheets("classic/white_pawn_move").clipSpec.anchor shouldBe None
  }

  // ── JSON syntax errors ────────────────────────────────────────────────────

  it should "return ParseError for invalid JSON" in {
    val result = SpriteCatalogParser.parse("{not valid json")
    result shouldBe a[Left[?, ?]]
    result.left.toOption
      .getOrElse(fail("expected parse failure"))
      .head shouldBe a[CatalogValidationError.ParseError]
  }

  // ── Root structure errors ─────────────────────────────────────────────────

  it should "return MissingSection for a non-object root" in {
    val result = SpriteCatalogParser.parse("[]")
    result shouldBe a[Left[?, ?]]
    result.left.toOption
      .getOrElse(fail("expected parse failure"))
      .head shouldBe CatalogValidationError.MissingSection("root object")
  }

  it should "return MissingSection for missing theme" in {
    val json = """{"clipSpecs": {}, "spriteSheets": {}, "statePlayback": {}}"""
    val err =
      SpriteCatalogParser.parse(json).left.toOption.getOrElse(fail("expected parse failure")).head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "theme"
    )
  }

  it should "return MissingSection for an empty theme string" in {
    val err = SpriteCatalogParser
      .parse(fullCatalog(theme = """"""""))
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "empty"
    )
  }

  it should "return MissingSection for a non-string theme" in {
    val err = SpriteCatalogParser
      .parse(fullCatalog(theme = "42"))
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "not a string"
    )
  }

  it should "return MissingSection for missing clipSpecs section" in {
    val json = """{"theme": "t", "spriteSheets": {}, "statePlayback": {}}"""
    val err =
      SpriteCatalogParser.parse(json).left.toOption.getOrElse(fail("expected parse failure")).head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "clipSpecs"
    )
  }

  it should "return MissingSection when clipSpecs is not an object" in {
    val json = """{"theme": "t", "clipSpecs": "wrong", "spriteSheets": {}, "statePlayback": {}}"""
    val err =
      SpriteCatalogParser.parse(json).left.toOption.getOrElse(fail("expected parse failure")).head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "not an object"
    )
  }

  it should "return MissingSection for missing spriteSheets section" in {
    val json = """{"theme": "t", "clipSpecs": {}, "statePlayback": {}}"""
    val err =
      SpriteCatalogParser.parse(json).left.toOption.getOrElse(fail("expected parse failure")).head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "spriteSheets"
    )
  }

  it should "return MissingSection when spriteSheets is not an object" in {
    val json = """{"theme": "t", "clipSpecs": {}, "spriteSheets": [], "statePlayback": {}}"""
    val err =
      SpriteCatalogParser.parse(json).left.toOption.getOrElse(fail("expected parse failure")).head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "not an object"
    )
  }

  it should "return MissingSection for missing statePlayback section" in {
    val json = """{"theme": "t", "clipSpecs": {}, "spriteSheets": {}}"""
    val err =
      SpriteCatalogParser.parse(json).left.toOption.getOrElse(fail("expected parse failure")).head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "statePlayback"
    )
  }

  it should "return MissingSection when statePlayback is not an object" in {
    val json = """{"theme": "t", "clipSpecs": {}, "spriteSheets": {}, "statePlayback": "bad"}"""
    val err =
      SpriteCatalogParser.parse(json).left.toOption.getOrElse(fail("expected parse failure")).head
    err shouldBe a[CatalogValidationError.MissingSection]
    (err match { case e: CatalogValidationError.MissingSection => e }).section should include(
      "not an object"
    )
  }

  // ── clipSpec validation errors ────────────────────────────────────────────

  it should "return InvalidClipSpec when the entry is not an object" in {
    val err = SpriteCatalogParser
      .parse(singleCatalog(clipVal = "42"))
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  it should "return InvalidClipSpec for frameCount = 0" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 0, "frameSize": {"width": 64, "height": 64}}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "frameCount"
    )
  }

  it should "return InvalidClipSpec for negative frameCount" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": -1, "frameSize": {"width": 64, "height": 64}}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  it should "return InvalidClipSpec for missing frameCount" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameSize": {"width": 64, "height": 64}}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "missing frameCount"
    )
  }

  it should "return InvalidClipSpec for frameCount that is not a number" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal =
          """{"frameCount": "four", "frameSize": {"width": 64, "height": 64}}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "not a number"
    )
  }

  it should "return InvalidClipSpec for missing frameSize" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "missing frameSize"
    )
  }

  it should "return InvalidClipSpec when frameSize is not an object" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4, "frameSize": 64}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "frameSize is not an object"
    )
  }

  it should "return InvalidClipSpec for frameSize width = 0" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4, "frameSize": {"width": 0, "height": 64}}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "width"
    )
  }

  it should "return InvalidClipSpec for frameSize height = 0" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4, "frameSize": {"width": 64, "height": 0}}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "height"
    )
  }

  it should "return InvalidClipSpec when displaySize width <= 0" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal =
          """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "displaySize": {"width": 0.0, "height": 80.0}}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  it should "return InvalidClipSpec when displaySize height <= 0" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal =
          """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "displaySize": {"width": 80.0, "height": 0.0}}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  it should "return InvalidClipSpec when displaySize is not an object" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal =
          """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "displaySize": 80}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  it should "return InvalidClipSpec when anchor is not an object" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal =
          """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "anchor": "center"}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  it should "return InvalidClipSpec when anchor x is not a number" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal =
          """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "anchor": {"x": "half", "y": 0.0}}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  it should "return InvalidClipSpec when anchor y is not a number" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal =
          """{"frameCount": 4, "frameSize": {"width": 64, "height": 64}, "anchor": {"x": 0.5, "y": "half"}}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidClipSpec]
  }

  // ── spriteSheet validation errors ─────────────────────────────────────────

  it should "return InvalidSpriteSheet when the entry is not an object" in {
    val err = SpriteCatalogParser
      .parse(singleCatalog(sheetVal = "42"))
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidSpriteSheet]
  }

  it should "return InvalidSpriteSheet for an empty path" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(sheetVal = """{"path": "", "clipSpec": "pawn_move"}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidSpriteSheet]
    (err match { case e: CatalogValidationError.InvalidSpriteSheet => e }).reason should include(
      "path"
    )
  }

  it should "return InvalidSpriteSheet for a missing path" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(sheetVal = """{"clipSpec": "pawn_move"}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidSpriteSheet]
  }

  it should "return InvalidSpriteSheet when path is not a string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(sheetVal = """{"path": 42, "clipSpec": "pawn_move"}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidSpriteSheet]
    (err match { case e: CatalogValidationError.InvalidSpriteSheet => e }).reason should include(
      "not a string"
    )
  }

  it should "return InvalidSpriteSheet for a missing clipSpec field" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(sheetVal = """{"path": "assets/pawn/move.png"}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidSpriteSheet]
    (err match { case e: CatalogValidationError.InvalidSpriteSheet => e }).reason should include(
      "clipSpec"
    )
  }

  it should "return InvalidSpriteSheet for an empty clipSpec string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(sheetVal = """{"path": "assets/pawn/move.png", "clipSpec": ""}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidSpriteSheet]
    (err match { case e: CatalogValidationError.InvalidSpriteSheet => e }).reason should include(
      "clipSpec"
    )
  }

  it should "return InvalidSpriteSheet when clipSpec key references a non-existent clip" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(sheetVal = """{"path": "assets/pawn/move.png", "clipSpec": "nonexistent"}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidSpriteSheet]
    (err match { case e: CatalogValidationError.InvalidSpriteSheet => e }).reason should include(
      "nonexistent"
    )
  }

  // ── statePlayback validation errors ──────────────────────────────────────

  it should "return InvalidStatePlayback when the entry is not an object" in {
    val err = SpriteCatalogParser
      .parse(singleCatalog(playbackVal = "42"))
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
  }

  it should "return InvalidStatePlayback for a missing state field" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"mode": "Clamp", "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "state"
    )
  }

  it should "return InvalidStatePlayback for an empty state string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "", "mode": "Clamp", "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "state"
    )
  }

  it should "return InvalidStatePlayback when state is not a string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": 5, "mode": "Clamp", "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
  }

  it should "return InvalidStatePlayback for an empty mode string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "Move", "mode": "", "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "mode"
    )
  }

  it should "return InvalidStatePlayback when mode is not a string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "Move", "mode": 1, "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
  }

  it should "return InvalidStatePlayback for an unknown state string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "Fly", "mode": "Clamp", "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "Fly"
    )
  }

  it should "return InvalidStatePlayback for a missing mode field" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "Move", "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "mode"
    )
  }

  it should "return InvalidStatePlayback for an unknown mode string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "Move", "mode": "Bounce", "segments": ["classic/white_pawn_move"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "Bounce"
    )
  }

  it should "return InvalidStatePlayback for missing segments" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal = """{"state": "Move", "mode": "Clamp"}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "segments"
    )
  }

  it should "return InvalidStatePlayback for an empty segments array" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal = """{"state": "Move", "mode": "Clamp", "segments": []}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "non-empty"
    )
  }

  it should "return InvalidStatePlayback when segments is not an array" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "Move", "mode": "Clamp", "segments": "classic/white_pawn_move"}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "not an array"
    )
  }

  it should "return InvalidStatePlayback when a segment entry is not a string" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal = """{"state": "Move", "mode": "Clamp", "segments": [42]}""")
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "not a string"
    )
  }

  it should "return InvalidStatePlayback when a segment key is absent from spriteSheets" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(playbackVal =
          """{"state": "Move", "mode": "Clamp", "segments": ["classic/nonexistent"]}"""
        )
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head
    err shouldBe a[CatalogValidationError.InvalidStatePlayback]
    (err match { case e: CatalogValidationError.InvalidStatePlayback => e }).reason should include(
      "nonexistent"
    )
  }

  // ── Error accumulation across sections ───────────────────────────────────

  it should "surface multiple clipSpec errors when several entries are invalid" in {
    val twoInvalidClips =
      """"a": {"frameCount": 0, "frameSize": {"width": 64, "height": 64}},
        |"b": {"frameCount": 0, "frameSize": {"width": 64, "height": 64}}""".stripMargin
    val result = SpriteCatalogParser.parse(fullCatalog(clips = twoInvalidClips))
    result shouldBe a[Left[?, ?]]
    result.left.toOption.getOrElse(fail("expected parse failure")) should have size 2
  }

  it should "return InvalidClipSpec when displaySize width is not a number" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4, "frameSize": {"width": 64, "height": 64},
          | "displaySize": {"width": "wide", "height": 80.0}}""".stripMargin)
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head

    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "width is not a number"
    )
  }

  it should "return InvalidClipSpec when displaySize height is missing" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4, "frameSize": {"width": 64, "height": 64},
          | "displaySize": {"width": 80.0}}""".stripMargin)
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head

    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "missing height"
    )
  }

  it should "return InvalidClipSpec when anchor x is missing" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4, "frameSize": {"width": 64, "height": 64},
          | "anchor": {"y": 0.0}}""".stripMargin)
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head

    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "missing x"
    )
  }

  it should "return InvalidClipSpec when anchor y is missing" in {
    val err = SpriteCatalogParser
      .parse(
        singleCatalog(clipVal = """{"frameCount": 4, "frameSize": {"width": 64, "height": 64},
          | "anchor": {"x": 0.5}}""".stripMargin)
      )
      .left
      .toOption
      .getOrElse(fail("expected parse failure"))
      .head

    err shouldBe a[CatalogValidationError.InvalidClipSpec]
    (err match { case e: CatalogValidationError.InvalidClipSpec => e }).reason should include(
      "missing y"
    )
  }
