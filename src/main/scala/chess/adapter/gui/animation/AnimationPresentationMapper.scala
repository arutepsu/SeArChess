package chess.adapter.gui.animation

import chess.adapter.gui.assets.{FrameSelectionPolicy, PlaybackResolution, SpriteMetadataRepository, StatePlaybackRepository, VisualState}
import chess.adapter.gui.render.BoardProjection

/** Pure mapping layer from [[AnimationState]] to the renderer-facing
 *  [[AnimationRenderModel]].
 *
 *  This class owns all animation-presentation policy:
 *  - motion-style-driven interpolation of the moving piece position
 *    (style and trajectory chosen by [[MotionStyleResolver]] / [[MotionInterpolator]])
 *  - phased captured-piece presentation:
 *    `[0, HitStart)` Idle → `[HitStart, DeadStart)` Hit → `[DeadStart, FadeEnd)` Dead/fade → hidden
 *  - static board suppression (which square the board renderer must skip)
 *
 *  It has no ScalaFX dependencies and no mutable state.
 *
 *  @param metaRepo     sprite metadata repository for frame-count lookup
 *  @param playbackRepo state playback repository for segment/mode lookup
 */
class AnimationPresentationMapper(
    metaRepo:     SpriteMetadataRepository,
    playbackRepo: StatePlaybackRepository
):

  /** Map [[state]] to an [[AnimationRenderModel]] for the current frame.
   *
   *  @param state      current animation snapshot (progress in [0, 1])
   *  @param squareSize side length of one board square in pixels;
   *                    defaults to [[AnimationPresentationMapper.DefaultSquareSize]]
   */
  def map(
      state:      AnimationState,
      squareSize: Double = AnimationPresentationMapper.DefaultSquareSize
  ): AnimationRenderModel =
    val t    = state.clampedProgress
    val plan = state.plan

    // Delegate moving-piece position to the motion-style layer.
    val fromX = BoardProjection.toPixelX(plan.from, squareSize)
    val fromY = BoardProjection.toPixelY(plan.from, squareSize)
    val toX   = BoardProjection.toPixelX(plan.to, squareSize)
    val toY   = BoardProjection.toPixelY(plan.to, squareSize)

    val style   = MotionStyleResolver.resolve(plan.movingPiece._2, plan.isCapture)
    val motionT = if plan.isCapture then CaptureTiming.remapCapture(t) else t
    val (currentX, currentY) = MotionInterpolator.interpolate(style, fromX, fromY, toX, toY, motionT)

    val dx    = toX - fromX
    val flipX = if dx > 0 then false
                else if dx < 0 then true
                else plan.movingPiece._1 == chess.domain.model.Color.Black

    val movingState      = if plan.isCapture then VisualState.Attack else VisualState.Move
    val movingResolution = resolveSegmented(plan.movingPiece, movingState, t)
    val movingInfo = PieceRenderInfo(
      piece           = plan.movingPiece,
      x               = currentX,
      y               = currentY,
      visualState     = movingState,
      frameIndex      = movingResolution.frameIndex,
      segmentAssetKey = Some(movingResolution.segmentAssetKey),
      flipX           = flipX
    )

    // Captured piece: three visual phases keyed by presentation thresholds.
    //   [0, HitStart)      → Idle   (full opacity — defender not yet hit)
    //   [HitStart, DeadStart) → Hit (full opacity — impact reaction)
    //   [DeadStart, FadeEnd)  → Dead (linear fade — collapsing)
    //   [FadeEnd, 1.0]     → hidden
    val capturedInfo = plan.capturedPiece.flatMap { captured =>
      import CaptureTiming.{HitStart, DeadStart, FadeEnd}
      val cx = BoardProjection.toPixelX(plan.to, squareSize)
      val cy = BoardProjection.toPixelY(plan.to, squareSize)
      if t < HitStart then
        val res = resolveSegmented(captured, VisualState.Idle, t / HitStart)
        Some(PieceRenderInfo(
          piece           = captured,
          x               = cx,
          y               = cy,
          visualState     = VisualState.Idle,
          opacity         = 1.0,
          frameIndex      = res.frameIndex,
          segmentAssetKey = Some(res.segmentAssetKey)
        ))
      else if t < DeadStart then
        val lp  = (t - HitStart) / (DeadStart - HitStart)
        val res = resolveSegmented(captured, VisualState.Hit, lp)
        Some(PieceRenderInfo(
          piece           = captured,
          x               = cx,
          y               = cy,
          visualState     = VisualState.Hit,
          opacity         = 1.0,
          frameIndex      = res.frameIndex,
          segmentAssetKey = Some(res.segmentAssetKey),
          scale           = hitPopScale(lp)
        ))
      else if t < FadeEnd then
        val lp  = (t - DeadStart) / (FadeEnd - DeadStart)
        val res = resolveSegmented(captured, VisualState.Dead, lp)
        Some(PieceRenderInfo(
          piece           = captured,
          x               = cx,
          y               = cy,
          visualState     = VisualState.Dead,
          opacity         = 1.0 - lp,
          frameIndex      = res.frameIndex,
          segmentAssetKey = Some(res.segmentAssetKey)
        ))
      else
        None
    }

    AnimationRenderModel(
      movingPiece      = movingInfo,
      capturedPiece    = capturedInfo,
      suppressedSquare = Some(plan.to)
    )

  // ── Private helpers ──────────────────────────────────────────────────────────

  /** Look up the [[StatePlaybackRepository]] entry for [[piece]] + [[state]],
   *  select the active segment via [[PlaybackPlanner]], then compute the frame
   *  index via [[FrameSelectionPolicy]].
   *
   *  Falls back to a single-segment resolution at frame 0 using the primary
   *  asset key when no metadata is found (unknown piece or state). */
  private def resolveSegmented(
      piece:    (chess.domain.model.Color, chess.domain.model.PieceType),
      state:    VisualState,
      progress: Double
  ): PlaybackResolution =
    playbackRepo.lookup(piece._1, piece._2, state)
      .map { meta =>
        val planned    = PlaybackPlanner.plan(meta, progress)
        val frameCount = metaRepo.lookup(planned.segmentAssetKey).map(_.frameCount).getOrElse(1)
        PlaybackResolution(
          planned.segmentAssetKey,
          FrameSelectionPolicy.select(state, frameCount, planned.localProgress)
        )
      }
      .getOrElse(PlaybackResolution(
        s"classic/${piece._1.toString.toLowerCase}_${piece._2.toString.toLowerCase}_${state.toString.toLowerCase}",
        0
      ))

  /** Parabolic scale pop for the Hit phase.
   *
   *  Returns `1.0 + HitPopPeak × 4 × lp × (1 − lp)`, which starts and ends
   *  at `1.0` and peaks at `1.0 + HitPopPeak` when `lp = 0.5`.
   *
   *  @param lp phase-local progress in [0, 1]
   */
  private def hitPopScale(lp: Double): Double =
    1.0 + AnimationPresentationMapper.HitPopPeak * 4.0 * lp * (1.0 - lp)

/** Companion holding constants shared across the class and its tests. */
object AnimationPresentationMapper:

  /** Board square size used when a [[squareSize]] override is not supplied.
   *
   *  Must be kept in sync with the board renderer's square size constant.
   */
  val DefaultSquareSize: Double = 72.0

  /** Peak scale added above `1.0` at the midpoint of the Hit phase.
   *  Full pop magnitude = `1.0 + HitPopPeak` (e.g. `0.12` → scale reaches `1.12`).
   *
   *  Raised slightly from 0.10 because the Hit window is now shorter (17% vs 20%),
   *  so the pop needs a touch more amplitude to remain readable.
   */
  val HitPopPeak: Double = 0.12
