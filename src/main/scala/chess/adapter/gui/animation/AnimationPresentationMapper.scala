package chess.adapter.gui.animation

import chess.adapter.gui.assets.{FrameSelectionPolicy, PlaybackResolution, SpriteMetadataRepository, StatePlaybackRepository, VisualState}
import chess.adapter.gui.render.BoardProjection

/** Pure mapping layer from [[AnimationState]] to the renderer-facing
 *  [[AnimationRenderModel]].
 *
 *  This class owns all animation-presentation policy:
 *  - motion-style-driven interpolation of the moving piece position
 *  - explicit phase-based capture choreography:
 *      Approach -> Attack -> Attack1 -> Dead -> Fade
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

    val fromX = BoardProjection.toPixelX(plan.from, squareSize)
    val fromY = BoardProjection.toPixelY(plan.from, squareSize)
    val toX   = BoardProjection.toPixelX(plan.to, squareSize)
    val toY   = BoardProjection.toPixelY(plan.to, squareSize)

    val dx    = toX - fromX
    val flipX =
      if dx > 0 then false
      else if dx < 0 then true
      else plan.movingPiece._1 == chess.domain.model.Color.Black

    if !plan.isCapture then
      mapRegularMove(plan, t, fromX, fromY, toX, toY, flipX)
    else
      val phase = CaptureTiming.resolve(plan, t)
      mapCapture(plan, phase, fromX, fromY, toX, toY, flipX)

  // ── Regular move mapping ───────────────────────────────────────────────────
  private def defaultFlipX(piece: (chess.domain.model.Color, chess.domain.model.PieceType)): Boolean =
    piece._1 == chess.domain.model.Color.Black
    
  private def mapRegularMove(
      plan:  AnimationPlan,
      t:     Double,
      fromX: Double,
      fromY: Double,
      toX:   Double,
      toY:   Double,
      flipX: Boolean
  ): AnimationRenderModel =
    val style = MotionStyleResolver.resolve(plan.movingPiece._2, false)
    val (currentX, currentY) =
      MotionInterpolator.interpolate(style, fromX, fromY, toX, toY, t)

    val res = resolveSegmented(plan.movingPiece, VisualState.Move, t)

    AnimationRenderModel(
      movingPiece = PieceRenderInfo(
        piece           = plan.movingPiece,
        x               = currentX,
        y               = currentY,
        visualState     = VisualState.Move,
        frameIndex      = res.frameIndex,
        segmentAssetKey = Some(res.segmentAssetKey),
        flipX           = flipX
      ),
      capturedPiece    = None,
      suppressedSquare = Some(plan.to)
    )

  // ── Capture mapping ────────────────────────────────────────────────────────

  private def mapCapture(
      plan:  AnimationPlan,
      phase: CaptureTiming.PhaseProgress,
      fromX: Double,
      fromY: Double,
      toX:   Double,
      toY:   Double,
      flipX: Boolean
  ): AnimationRenderModel =
    import CaptureTiming.Phase

    val movingInfo =
      phase.phase match
        case Phase.Approach =>
          val style = MotionStyleResolver.resolve(plan.movingPiece._2, false)
          val (x, y) =
            MotionInterpolator.interpolate(style, fromX, fromY, toX, toY, phase.localProgress)
          val res = resolveSegmented(plan.movingPiece, VisualState.Move, phase.localProgress)

          PieceRenderInfo(
            piece           = plan.movingPiece,
            x               = x,
            y               = y,
            visualState     = VisualState.Move,
            frameIndex      = res.frameIndex,
            segmentAssetKey = Some(res.segmentAssetKey),
            flipX           = flipX
          )

        case Phase.Attack =>
          val res = resolveSpecificSegment(
            plan.movingPiece,
            VisualState.Attack,
            phase.localProgress,
            segmentIndex = 0
          )

          PieceRenderInfo(
            piece           = plan.movingPiece,
            x               = toX,
            y               = toY,
            visualState     = VisualState.Attack,
            frameIndex      = res.frameIndex,
            segmentAssetKey = Some(res.segmentAssetKey),
            flipX           = flipX
          )

        case Phase.Attack1 =>
          val res = resolveSpecificSegment(
            plan.movingPiece,
            VisualState.Attack,
            phase.localProgress,
            segmentIndex = 1
          )

          PieceRenderInfo(
            piece           = plan.movingPiece,
            x               = toX,
            y               = toY,
            visualState     = VisualState.Attack,
            frameIndex      = res.frameIndex,
            segmentAssetKey = Some(res.segmentAssetKey),
            flipX           = flipX
          )

        case Phase.Dead | Phase.Fade =>
          val res = resolveSpecificSegment(
            plan.movingPiece,
            VisualState.Attack,
            1.0,
            segmentIndex = 1
          )

          PieceRenderInfo(
            piece           = plan.movingPiece,
            x               = toX,
            y               = toY,
            visualState     = VisualState.Attack,
            frameIndex      = res.frameIndex,
            segmentAssetKey = Some(res.segmentAssetKey),
            flipX           = flipX
          )

    val capturedInfo =
      plan.capturedPiece.flatMap { captured =>
        phase.phase match
          case Phase.Approach | Phase.Attack | Phase.Attack1 =>
            val res = resolveSegmented(captured, VisualState.Idle, 0.0)
            Some(PieceRenderInfo(
              piece           = captured,
              x               = toX,
              y               = toY,
              visualState     = VisualState.Idle,
              opacity         = 1.0,
              frameIndex      = res.frameIndex,
              segmentAssetKey = Some(res.segmentAssetKey),
              flipX           = defaultFlipX(captured)
            ))

          case Phase.Dead =>
            val res = resolveSegmented(captured, VisualState.Dead, phase.localProgress)
            Some(PieceRenderInfo(
              piece           = captured,
              x               = toX,
              y               = toY,
              visualState     = VisualState.Dead,
              opacity         = 1.0,
              frameIndex      = res.frameIndex,
              segmentAssetKey = Some(res.segmentAssetKey),
              flipX           = defaultFlipX(captured)
            ))

          case Phase.Fade =>
            val res = resolveSegmented(captured, VisualState.Dead, 1.0)
            Some(PieceRenderInfo(
              piece           = captured,
              x               = toX,
              y               = toY,
              visualState     = VisualState.Dead,
              opacity         = 1.0 - phase.localProgress,
              frameIndex      = res.frameIndex,
              segmentAssetKey = Some(res.segmentAssetKey),
              flipX           = defaultFlipX(captured)
            ))
      }

    AnimationRenderModel(
      movingPiece      = movingInfo,
      capturedPiece    = capturedInfo,
      suppressedSquare = Some(plan.to)
    )

  // ── Private helpers ────────────────────────────────────────────────────────

  /** Look up the [[StatePlaybackRepository]] entry for [[piece]] + [[state]],
   *  select the active segment via [[PlaybackPlanner]], then compute the frame
   *  index via [[FrameSelectionPolicy]].
   *
   *  Falls back to a single-segment resolution at frame 0 using the primary
   *  asset key when no metadata is found.
   */
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

  /** Resolve a specific segment for a multi-segment visual state.
   *
   *  Used for explicit capture choreography where we want:
   *  Attack phase  -> first attack segment
   *  Attack1 phase -> second attack segment
   */
  private def resolveSpecificSegment(
      piece:        (chess.domain.model.Color, chess.domain.model.PieceType),
      state:        VisualState,
      progress:     Double,
      segmentIndex: Int
  ): PlaybackResolution =
    playbackRepo.lookup(piece._1, piece._2, state)
      .map { meta =>
        val idx        = segmentIndex.max(0).min(meta.segments.length - 1)
        val assetKey   = meta.segments(idx).assetKey
        val frameCount = metaRepo.lookup(assetKey).map(_.frameCount).getOrElse(1)
        PlaybackResolution(
          assetKey,
          FrameSelectionPolicy.select(state, frameCount, progress)
        )
      }
      .getOrElse(PlaybackResolution(
        s"classic/${piece._1.toString.toLowerCase}_${piece._2.toString.toLowerCase}_${state.toString.toLowerCase}",
        0
      ))

/** Companion holding constants shared across the class and its tests. */
object AnimationPresentationMapper:

  /** Board square size used when a [[squareSize]] override is not supplied.
   *
   *  Must be kept in sync with the board renderer's square size constant.
   */
  val DefaultSquareSize: Double = 72.0