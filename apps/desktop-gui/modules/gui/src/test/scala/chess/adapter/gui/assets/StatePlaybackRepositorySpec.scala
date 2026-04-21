package chess.adapter.gui.assets

import chess.domain.model.{Color, PieceType}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class StatePlaybackRepositorySpec extends AnyFlatSpec with Matchers:

  // ── Test fixture helpers ──────────────────────────────────────────────────

  private def singleSegMeta(
      state: VisualState,
      assetKey: String,
      mode: PlaybackMode = PlaybackMode.Clamp
  ) =
    StatePlaybackMetadata(state, Seq(PlaybackSegmentRef(assetKey)), mode)

  private def twoSegMeta(state: VisualState, key1: String, key2: String, mode: PlaybackMode) =
    StatePlaybackMetadata(state, Seq(PlaybackSegmentRef(key1), PlaybackSegmentRef(key2)), mode)

  /** Minimal repo: white pawn with all 5 states (single-segment Clamp, except Attack = Loop). */
  private val testRepo = StatePlaybackRepository(
    Map(
      "classic/white_pawn_idle" -> singleSegMeta(VisualState.Idle, "classic/white_pawn_idle"),
      "classic/white_pawn_move" -> singleSegMeta(VisualState.Move, "classic/white_pawn_move"),
      "classic/white_pawn_attack" -> twoSegMeta(
        VisualState.Attack,
        "classic/white_pawn_attack",
        "classic/white_pawn_attack1",
        PlaybackMode.Loop
      ),
      "classic/white_pawn_hit" -> singleSegMeta(VisualState.Hit, "classic/white_pawn_hit"),
      "classic/white_pawn_dead" -> singleSegMeta(VisualState.Dead, "classic/white_pawn_dead")
    )
  )

  // ── Lookup — known entries ────────────────────────────────────────────────

  "StatePlaybackRepository.lookup" should "return Some for a registered (color, piece, state) triple" in {
    testRepo.lookup(Color.White, PieceType.Pawn, VisualState.Move) shouldBe defined
  }

  it should "return None for an unregistered triple" in {
    testRepo.lookup(Color.Black, PieceType.King, VisualState.Dead) shouldBe None
  }

  it should "return the correct state for a registered entry" in {
    testRepo
      .lookup(Color.White, PieceType.Pawn, VisualState.Idle)
      .getOrElse(fail("expected Some metadata"))
      .state shouldBe VisualState.Idle
  }

  it should "return the correct mode for a Clamp entry" in {
    testRepo
      .lookup(Color.White, PieceType.Pawn, VisualState.Move)
      .getOrElse(fail("expected Some metadata"))
      .mode shouldBe PlaybackMode.Clamp
  }

  it should "return the correct mode for a Loop entry" in {
    testRepo
      .lookup(Color.White, PieceType.Pawn, VisualState.Attack)
      .getOrElse(fail("expected Some metadata"))
      .mode shouldBe PlaybackMode.Loop
  }

  it should "return two segments for the Attack entry" in {
    val meta = testRepo
      .lookup(Color.White, PieceType.Pawn, VisualState.Attack)
      .getOrElse(fail("expected Some metadata"))
    meta.segments should have length 2
    meta.segments(0).assetKey shouldBe "classic/white_pawn_attack"
    meta.segments(1).assetKey shouldBe "classic/white_pawn_attack1"
  }

  // ── Lookup uses VisualResolver key derivation ─────────────────────────────

  it should "use the primary asset key derived by VisualResolver" in {
    // VisualResolver.resolve(PieceVisualId(White, Pawn, Move)).assetKey = "classic/white_pawn_move"
    val expected =
      VisualResolver.resolve(PieceVisualId(Color.White, PieceType.Pawn, VisualState.Move)).assetKey
    val repo = StatePlaybackRepository(Map(expected -> singleSegMeta(VisualState.Move, expected)))
    repo.lookup(Color.White, PieceType.Pawn, VisualState.Move) shouldBe defined
  }

  // ── fromCatalog factory ───────────────────────────────────────────────────

  "StatePlaybackRepository.fromCatalog" should "build a single-segment Clamp entry from a Move entry" in {
    val entry =
      StatePlaybackEntry(VisualState.Move, PlaybackMode.Clamp, Seq("classic/white_pawn_move"))
    val catalog = SpriteCatalog("t", Map.empty, Map("classic/white_pawn_move" -> entry))
    val repo = StatePlaybackRepository.fromCatalog(catalog)
    val meta = repo
      .lookup(Color.White, PieceType.Pawn, VisualState.Move)
      .getOrElse(fail("expected Some metadata"))
    meta.state shouldBe VisualState.Move
    meta.mode shouldBe PlaybackMode.Clamp
    meta.segments should have length 1
    meta.segments.head.assetKey shouldBe "classic/white_pawn_move"
  }

  it should "build a two-segment Loop entry from an Attack entry" in {
    val entry = StatePlaybackEntry(
      VisualState.Attack,
      PlaybackMode.Loop,
      Seq("classic/white_pawn_attack", "classic/white_pawn_attack1")
    )
    val catalog = SpriteCatalog("t", Map.empty, Map("classic/white_pawn_attack" -> entry))
    val repo = StatePlaybackRepository.fromCatalog(catalog)
    val meta = repo
      .lookup(Color.White, PieceType.Pawn, VisualState.Attack)
      .getOrElse(fail("expected Some metadata"))
    meta.mode shouldBe PlaybackMode.Loop
    meta.segments should have length 2
    meta.segments(1).assetKey shouldBe "classic/white_pawn_attack1"
  }

  it should "convert segment strings to PlaybackSegmentRef values" in {
    val entry =
      StatePlaybackEntry(VisualState.Dead, PlaybackMode.Clamp, Seq("classic/black_queen_dead"))
    val catalog = SpriteCatalog("t", Map.empty, Map("classic/black_queen_dead" -> entry))
    val repo = StatePlaybackRepository.fromCatalog(catalog)
    val meta = repo
      .lookup(Color.Black, PieceType.Queen, VisualState.Dead)
      .getOrElse(fail("expected Some metadata"))
    meta.segments.head shouldBe a[PlaybackSegmentRef]
    meta.segments.head.assetKey shouldBe "classic/black_queen_dead"
  }

  it should "produce an empty repository from an empty catalog" in {
    val catalog = SpriteCatalog("t", Map.empty, Map.empty)
    StatePlaybackRepository
      .fromCatalog(catalog)
      .lookup(Color.White, PieceType.Pawn, VisualState.Idle) shouldBe None
  }
