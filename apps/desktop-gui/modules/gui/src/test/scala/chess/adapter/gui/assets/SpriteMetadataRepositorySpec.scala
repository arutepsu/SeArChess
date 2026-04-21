package chess.adapter.gui.assets

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpriteMetadataRepositorySpec extends AnyFlatSpec with Matchers:

  // ── Test fixtures ─────────────────────────────────────────────────────────

  private val knownMeta = SpriteMetadata(
    assetKey = "classic/white_pawn_move",
    path = "assets/classic/pawn/white_pawn_move.png",
    frameCount = 8,
    frameSize = (200, 200),
    displaySize = None,
    anchor = None
  )

  private val repo = SpriteMetadataRepository(Map("classic/white_pawn_move" -> knownMeta))

  // ── Lookup behaviour ──────────────────────────────────────────────────────

  "SpriteMetadataRepository.lookup" should "return Some for a registered key" in {
    repo.lookup("classic/white_pawn_move") shouldBe defined
  }

  it should "return the correct metadata for a registered key" in {
    val meta = repo.lookup("classic/white_pawn_move").getOrElse(fail("expected Some metadata"))
    meta.assetKey shouldBe "classic/white_pawn_move"
    meta.path shouldBe "assets/classic/pawn/white_pawn_move.png"
    meta.frameCount shouldBe 8
    meta.frameSize shouldBe (200, 200)
  }

  it should "return None for an unknown key" in {
    repo.lookup("classic/white_pawn_flying") shouldBe None
  }

  // ── fromCatalog factory ───────────────────────────────────────────────────

  "SpriteMetadataRepository.fromCatalog" should "build entries from all spriteSheet entries" in {
    val clip = ClipSpecEntry(4, (64, 64), None, None)
    val sheet = SpriteSheetEntry("classic/white_pawn_move", "assets/pawn/move.png", clip)
    val catalog = SpriteCatalog("test", Map("classic/white_pawn_move" -> sheet), Map.empty)
    val builtRepo = SpriteMetadataRepository.fromCatalog(catalog)
    builtRepo.lookup("classic/white_pawn_move") shouldBe defined
  }

  it should "inline frameCount from the clipSpec" in {
    val clip = ClipSpecEntry(8, (200, 200), None, None)
    val sheet = SpriteSheetEntry("classic/white_pawn_move", "assets/pawn/move.png", clip)
    val catalog = SpriteCatalog("test", Map("classic/white_pawn_move" -> sheet), Map.empty)
    SpriteMetadataRepository
      .fromCatalog(catalog)
      .lookup("classic/white_pawn_move")
      .getOrElse(fail("expected Some metadata"))
      .frameCount shouldBe 8
  }

  it should "inline frameSize from the clipSpec" in {
    val clip = ClipSpecEntry(4, (200, 200), None, None)
    val sheet = SpriteSheetEntry("classic/white_pawn_move", "assets/pawn/move.png", clip)
    val catalog = SpriteCatalog("test", Map("classic/white_pawn_move" -> sheet), Map.empty)
    SpriteMetadataRepository
      .fromCatalog(catalog)
      .lookup("classic/white_pawn_move")
      .getOrElse(fail("expected Some metadata"))
      .frameSize shouldBe (200, 200)
  }

  it should "use the sheet path as the SpriteMetadata path" in {
    val clip = ClipSpecEntry(4, (64, 64), None, None)
    val sheet =
      SpriteSheetEntry("classic/white_pawn_move", "assets/classic/pawn/white_pawn_move.png", clip)
    val catalog = SpriteCatalog("test", Map("classic/white_pawn_move" -> sheet), Map.empty)
    SpriteMetadataRepository
      .fromCatalog(catalog)
      .lookup("classic/white_pawn_move")
      .getOrElse(fail("expected Some metadata"))
      .path shouldBe "assets/classic/pawn/white_pawn_move.png"
  }

  it should "propagate displaySize from the clipSpec" in {
    val clip = ClipSpecEntry(4, (64, 64), Some((72.0, 72.0)), None)
    val sheet = SpriteSheetEntry("classic/white_pawn_move", "assets/pawn/move.png", clip)
    val catalog = SpriteCatalog("test", Map("classic/white_pawn_move" -> sheet), Map.empty)
    SpriteMetadataRepository
      .fromCatalog(catalog)
      .lookup("classic/white_pawn_move")
      .getOrElse(fail("expected Some metadata"))
      .displaySize shouldBe Some((72.0, 72.0))
  }

  it should "propagate anchor from the clipSpec" in {
    val clip = ClipSpecEntry(4, (64, 64), None, Some((0.5, 0.5)))
    val sheet = SpriteSheetEntry("classic/white_pawn_move", "assets/pawn/move.png", clip)
    val catalog = SpriteCatalog("test", Map("classic/white_pawn_move" -> sheet), Map.empty)
    SpriteMetadataRepository
      .fromCatalog(catalog)
      .lookup("classic/white_pawn_move")
      .getOrElse(fail("expected Some metadata"))
      .anchor shouldBe Some((0.5, 0.5))
  }

  it should "produce an empty repository from an empty catalog" in {
    val catalog = SpriteCatalog("test", Map.empty, Map.empty)
    SpriteMetadataRepository.fromCatalog(catalog).lookup("anything") shouldBe None
  }

  // ── Frame counts and sizes from real catalog data ─────────────────────────

  it should "expose distinct frame counts when built from a multi-entry catalog" in {
    val c1 = ClipSpecEntry(1, (64, 64), None, None)
    val c4 = ClipSpecEntry(4, (64, 64), None, None)
    val entries = Map(
      "classic/white_pawn_idle" -> SpriteSheetEntry("classic/white_pawn_idle", "p/idle.png", c1),
      "classic/white_pawn_move" -> SpriteSheetEntry("classic/white_pawn_move", "p/move.png", c4)
    )
    val catalog = SpriteCatalog("test", entries, Map.empty)
    val builtRepo = SpriteMetadataRepository.fromCatalog(catalog)
    builtRepo
      .lookup("classic/white_pawn_idle")
      .getOrElse(fail("expected Some metadata"))
      .frameCount shouldBe 1
    builtRepo
      .lookup("classic/white_pawn_move")
      .getOrElse(fail("expected Some metadata"))
      .frameCount shouldBe 4
  }
