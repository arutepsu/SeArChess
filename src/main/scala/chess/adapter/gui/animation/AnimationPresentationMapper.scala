package chess.adapter.gui.animation

import chess.adapter.gui.assets.{FrameSelectionPolicy, PieceVisualId, SpriteMetadataRepository, VisualResolver, VisualState}

/** Pure mapping layer from [[AnimationState]] to the renderer-facing
 *  [[AnimationRenderModel]].
 *
 *  This object owns all animation-presentation policy:
 *  - linear interpolation of the moving piece position
 *  - captured-piece visibility and fade-out
 *  - static board suppression (which square the board renderer must skip)
 *
 *  It has no ScalaFX dependencies and no mutable state.
 */
object AnimationPresentationMapper:

  /** Board square size used when a [[squareSize]] override is not supplied.
   *
   *  Must be kept in sync with the board renderer's square size constant.
   */
  val DefaultSquareSize: Double = 72.0

  /** Map [[state]] to an [[AnimationRenderModel]] for the current frame.
   *
   *  @param state      current animation snapshot (progress in [0, 1])
   *  @param squareSize side length of one board square in pixels;
   *                    defaults to [[DefaultSquareSize]]
   */
  def map(
      state:      AnimationState,
      squareSize: Double = DefaultSquareSize
  ): AnimationRenderModel =
    val t    = state.clampedProgress
    val plan = state.plan

    // Board-local pixel coordinate helpers (top-left corner of a square).
    // Rank 7 is at the top of the screen (y = 0); rank 0 is at the bottom.
    def squareX(file: Int): Double = file * squareSize
    def squareY(rank: Int): Double = (7 - rank) * squareSize

    // Delegate moving-piece position to the motion-style layer.
    val fromX = squareX(plan.from.file)
    val fromY = squareY(plan.from.rank)
    val toX   = squareX(plan.to.file)
    val toY   = squareY(plan.to.rank)

    val style = MotionStyleResolver.resolve(plan.movingPiece._2)
    val (currentX, currentY) = MotionInterpolator.interpolate(style, fromX, fromY, toX, toY, t)

    val movingInfo = PieceRenderInfo(
      piece      = plan.movingPiece,
      x          = currentX,
      y          = currentY,
      frameIndex = frameIndexFor(plan.movingPiece, VisualState.Move, t)
    )

    // Captured piece: fully visible at t=0, fades linearly to invisible at
    // CaptureThreshold, then disappears completely.
    val capturedInfo = plan.capturedPiece.flatMap { captured =>
      if t < AnimationState.CaptureThreshold then
        val fade = 1.0 - t / AnimationState.CaptureThreshold
        Some(PieceRenderInfo(
          piece      = captured,
          x          = squareX(plan.to.file),
          y          = squareY(plan.to.rank),
          opacity    = fade,
          frameIndex = frameIndexFor(captured, VisualState.Dead, t)
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

  /** Look up the sprite-sheet frame count for [[piece]] in [[state]], then
   *  delegate to [[FrameSelectionPolicy]] to convert [[progress]] to an index.
   *  Falls back to frame count 1 (→ frame 0) when metadata is absent. */
  private def frameIndexFor(
      piece:    (chess.domain.model.Color, chess.domain.model.PieceType),
      state:    VisualState,
      progress: Double
  ): Int =
    val assetKey   = VisualResolver.resolve(PieceVisualId(piece._1, piece._2, state)).assetKey
    val frameCount = SpriteMetadataRepository.lookup(assetKey).map(_.frameCount).getOrElse(1)
    FrameSelectionPolicy.select(state, frameCount, progress)
