import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { BoardMatrix, BoardSquare, PieceCode } from "../api/types";
import { displayToIndex, displayToSquare, squareToDisplayCoords } from "../domain/board";
import type { PlaybackMode, SpriteCatalog, StatePlaybackEntry } from "../assets/spriteCatalog";
import { loadSpriteCatalog } from "../assets/spriteCatalog";
import type { BoardAnimation } from "../animation/animationTypes";
import { captureTimings, idleFps, moveDurationMs } from "../animation/animationConfig";
import "./ChessBoard.css";

type ChessBoardProps = {
  board: BoardMatrix;
  selectedSquare?: string;
  legalMoves?: string[];
  animation?: BoardAnimation | null;
  idleAnimation?: boolean;
  disabled?: boolean;
  onSelect: (square: string) => void;
  onAnimationFinished: (id: number) => void;
};

type SpriteState = "idle" | "move";

type SpriteInfo = {
  url: string;
  frameCount: number;
  durationMs: number;
  animate: boolean;
};

type VisualState = "idle" | "move" | "attack" | "attack1" | "dead" | "hit";

type MotionStyle =
  | { kind: "linear" }
  | { kind: "smooth" }
  | { kind: "heavy" }
  | { kind: "arc"; heightFraction: number }
  | { kind: "attack"; overshootFraction: number };


const PIECE_SCALE = 2;

const pieceTransform = (flipX: boolean, scale: number = PIECE_SCALE): string =>
  `scaleX(${flipX ? -1 : 1}) scale(${scale})`;

const indices = Array.from({ length: 8 }, (_, index) => index);

const pieceLabel = (piece: BoardSquare): string => {
  if (!piece) return "";
  const map: Record<string, string> = {
    wK: "K",
    wQ: "Q",
    wR: "R",
    wB: "B",
    wN: "N",
    wP: "P",
    bK: "K",
    bQ: "Q",
    bR: "R",
    bB: "B",
    bN: "N",
    bP: "P"
  };
  return map[piece] ?? "?";
};

const pieceDescriptor = (piece: BoardSquare): { color: "white" | "black"; name: string } | null => {
  if (!piece) return null;
  const color = piece.startsWith("w") ? "white" : "black";
  const map: Record<string, string> = {
    wK: "king",
    wQ: "queen",
    wR: "rook",
    wB: "bishop",
    wN: "knight",
    wP: "pawn",
    bK: "king",
    bQ: "queen",
    bR: "rook",
    bB: "bishop",
    bN: "knight",
    bP: "pawn"
  };
  const name = map[piece] ?? "pawn";
  return { color, name };
};

const pieceType = (piece: PieceCode): string => {
  return pieceDescriptor(piece)?.name ?? "pawn";
};

const clamp = (value: number, min: number, max: number) => Math.max(min, Math.min(max, value));

const assetKeyFor = (piece: PieceCode, state: VisualState): string => {
  const descriptor = pieceDescriptor(piece);
  if (!descriptor) return "";
  return `classic/${descriptor.color}_${descriptor.name}_${state}`;
};

const normalizeProgress = (progress: number, mode: PlaybackMode) => {
  if (mode === "Loop") return progress - Math.floor(progress);
  return clamp(progress, 0, 1);
};

const planSegment = (entry: StatePlaybackEntry, progress: number) => {
  const segmentCount = entry.segments.length;
  if (segmentCount <= 0) {
    return { segmentKey: "", localProgress: clamp(progress, 0, 1) };
  }
  const p = normalizeProgress(progress, entry.mode);
  const sliceSize = 1 / segmentCount;
  const idx = clamp(Math.floor(p / sliceSize), 0, segmentCount - 1);
  const segStart = idx * sliceSize;
  return {
    segmentKey: entry.segments[idx],
    localProgress: clamp((p - segStart) / sliceSize, 0, 1)
  };
};

const selectFrameIndex = (state: VisualState, frameCount: number, progress: number) => {
  if (frameCount <= 1) return 0;
  if (state === "idle") return 0;
  const clamped = clamp(progress, 0, 1);
  return clamp(Math.floor(clamped * frameCount), 0, frameCount - 1);
};

const resolveCapturePhase = (progress: number) => {
  const total =
    captureTimings.approachMs +
    captureTimings.attackMs +
    captureTimings.attack1Ms +
    captureTimings.deadMs +
    captureTimings.fadeMs;
  const elapsed = Math.max(0, Math.min(1, progress)) * total;
  const endApproach = captureTimings.approachMs;
  const endAttack = endApproach + captureTimings.attackMs;
  const endAttack1 = endAttack + captureTimings.attack1Ms;
  const endDead = endAttack1 + captureTimings.deadMs;

  const local = (start: number, end: number) => {
    if (end <= start) return 1;
    return Math.max(0, Math.min(1, (elapsed - start) / (end - start)));
  };

  if (elapsed < endApproach) {
    return { phase: "approach", local: local(0, endApproach) };
  }
  if (elapsed < endAttack) {
    return { phase: "attack", local: local(endApproach, endAttack) };
  }
  if (elapsed < endAttack1) {
    return { phase: "attack1", local: local(endAttack, endAttack1) };
  }
  if (elapsed < endDead) {
    return { phase: "dead", local: local(endAttack1, endDead) };
  }
  return { phase: "fade", local: local(endDead, total) };
};

const motionStyleFor = (piece: PieceCode, isCapture: boolean): MotionStyle => {
  if (isCapture) return { kind: "attack", overshootFraction: 0.15 };
  switch (pieceType(piece)) {
    case "pawn":
      return { kind: "linear" };
    case "rook":
      return { kind: "heavy" };
    case "knight":
      return { kind: "arc", heightFraction: 0.5 };
    case "bishop":
    case "queen":
      return { kind: "smooth" };
    case "king":
      return { kind: "heavy" };
    default:
      return { kind: "smooth" };
  }
};

const interpolate = (
  style: MotionStyle,
  fromX: number,
  fromY: number,
  toX: number,
  toY: number,
  t: number
) => {
  const lerp = (a: number, b: number, v: number) => a + (b - a) * v;
  const smoothstep = (v: number) => v * v * (3 - 2 * v);
  const easeInOut = (v: number) => (v < 0.5 ? 4 * v * v * v : 1 - Math.pow(-2 * v + 2, 3) / 2);

  switch (style.kind) {
    case "linear":
      return { x: lerp(fromX, toX, t), y: lerp(fromY, toY, t) };
    case "smooth": {
      const e = easeInOut(t);
      return { x: lerp(fromX, toX, e), y: lerp(fromY, toY, e) };
    }
    case "heavy": {
      const MathPow = Math.pow(t, 2);
      return { x: lerp(fromX, toX, MathPow), y: lerp(fromY, toY, MathPow) };
    }
    case "arc": {
      const e = easeInOut(t);
      const baseX = lerp(fromX, toX, e);
      const baseY = lerp(fromY, toY, e);
      const arcBase = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
      const lift = Math.sin(e * Math.PI) * (arcBase * style.heightFraction);
      return { x: baseX, y: baseY - lift };
    }
    case "attack": {
      const dx = toX - fromX;
      const dy = toY - fromY;
      const dist = Math.hypot(dx, dy);
      if (dist === 0) return { x: toX, y: toY };
      const overshoot = style.overshootFraction * dist;
      const osX = toX + (dx / dist) * overshoot;
      const osY = toY + (dy / dist) * overshoot;
      const peakT = 0.6;
      if (t <= peakT) {
        const s = smoothstep(t / peakT);
        return { x: lerp(fromX, osX, s), y: lerp(fromY, osY, s) };
      }
      const s = smoothstep((t - peakT) / (1 - peakT));
      return { x: lerp(osX, toX, s), y: lerp(osY, toY, s) };
    }
  }
};

const pieceTone = (piece: BoardSquare): string => {
  if (!piece) return "";
  return piece.startsWith("w") ? "piece-light" : "piece-dark";
};

const backgroundPositionFor = (frameIndex: number, frameCount: number) => {
  if (frameCount <= 1) return "0% 50%";
  const ratio = frameIndex / Math.max(1, frameCount - 1);
  return `${ratio * 100}% 50%`;
};

const animationDurationMs = (plan: BoardAnimation) => {
  if (!plan.isCapture) return moveDurationMs;
  return (
    captureTimings.approachMs +
    captureTimings.attackMs +
    captureTimings.attack1Ms +
    captureTimings.deadMs +
    captureTimings.fadeMs
  );
};

export default function ChessBoard({
  board,
  selectedSquare,
  legalMoves,
  animation,
  idleAnimation,
  disabled = false,
  onSelect,
  onAnimationFinished
}: ChessBoardProps) {
  const [spriteCatalog, setSpriteCatalog] = useState<SpriteCatalog | null>(null);
  const boardRef = useRef<HTMLDivElement | null>(null);
  const [squareSize, setSquareSize] = useState(68);
  const [animationProgress, setAnimationProgress] = useState(0);
  const [animationActive, setAnimationActive] = useState(false);
  const [animationPlan, setAnimationPlan] = useState<BoardAnimation | null>(null);
  const animationFrame = useRef<number | null>(null);
  const [currentFrameTick, setCurrentFrameTick] = useState(0);
  const idleTimerFrame = useRef<number | null>(null);

  const boardPieceAt = useCallback(
    (rowIndex: number, colIndex: number): BoardSquare => {
      const mapped = displayToIndex(rowIndex, colIndex);
      return board[mapped.row]?.[mapped.col] ?? null;
    },
    [board]
  );

  const squareState = useCallback(
    (square: string): string[] => {
      const classes: string[] = [];
      if (selectedSquare === square) classes.push("is-selected");
      if (legalMoves?.includes(square)) classes.push("is-legal");
      return classes;
    },
    [legalMoves, selectedSquare]
  );

  const suppressedSquare = useMemo(
    () => (animationActive && animationPlan ? animationPlan.to : undefined),
    [animationActive, animationPlan]
  );

  const resolveSheetByKey = useCallback(
    (assetKey: string): { url: string; frameCount: number } | null => {
      if (!spriteCatalog || !assetKey) return null;
      const sheet = spriteCatalog.spriteSheets[assetKey];
      if (!sheet) return null;
      const clipSpec = spriteCatalog.clipSpecs[sheet.clipSpec];
      if (!clipSpec) return null;
      return { url: `/${sheet.path}`, frameCount: clipSpec.frameCount };
    },
    [spriteCatalog]
  );

  const lookupPlayback = useCallback(
    (piece: PieceCode, state: VisualState): StatePlaybackEntry | null => {
      if (!spriteCatalog) return null;
      const key = assetKeyFor(piece, state);
      return spriteCatalog.statePlayback?.[key] ?? null;
    },
    [spriteCatalog]
  );

  const resolveSegmentedSprite = useCallback(
    (
      piece: PieceCode,
      state: VisualState,
      progress: number
    ): { sheet: { url: string; frameCount: number } | null; frameIndex: number } => {
      const playback = lookupPlayback(piece, state);
      if (playback) {
        const planned = planSegment(playback, progress);
        const sheet = resolveSheetByKey(planned.segmentKey);
        if (sheet) {
          return {
            sheet,
            frameIndex: selectFrameIndex(state, sheet.frameCount, planned.localProgress)
          };
        }
      }

      const fallbackKey = assetKeyFor(piece, state);
      const fallback = resolveSheetByKey(fallbackKey);
      return {
        sheet: fallback,
        frameIndex: fallback ? selectFrameIndex(state, fallback.frameCount, progress) : 0
      };
    },
    [lookupPlayback, resolveSheetByKey]
  );

  const resolveAttackSegment = useCallback(
    (
      piece: PieceCode,
      segmentIndex: number,
      progress: number
    ): { sheet: { url: string; frameCount: number } | null; frameIndex: number } => {
      const playback = lookupPlayback(piece, "attack");
      if (playback && playback.segments.length > 0) {
        const idx = clamp(segmentIndex, 0, playback.segments.length - 1);
        const segmentKey = playback.segments[idx];
        const sheet = resolveSheetByKey(segmentKey);
        if (sheet) {
          return {
            sheet,
            frameIndex: selectFrameIndex("attack", sheet.frameCount, progress)
          };
        }
      }

      const fallbackState: VisualState = segmentIndex > 0 ? "attack1" : "attack";
      return resolveSegmentedSprite(piece, fallbackState, progress);
    },
    [lookupPlayback, resolveSegmentedSprite, resolveSheetByKey]
  );

  const resolveSpriteInfo = useCallback(
    (piece: BoardSquare): SpriteInfo | null => {
      if (!spriteCatalog) return null;
      const descriptor = pieceDescriptor(piece);
      if (!descriptor) return null;

      const state: SpriteState = "idle";
      const spriteKey = `classic/${descriptor.color}_${descriptor.name}_${state}`;
      const sheet = spriteCatalog.spriteSheets[spriteKey];
      if (!sheet) return null;
      const clipSpec = spriteCatalog.clipSpecs[sheet.clipSpec];
      if (!clipSpec) return null;

      const animate = idleAnimation !== false;
      const durationMs = animate
        ? Math.max(200, (clipSpec.frameCount / idleFps) * 1000)
        : 0;

      return {
        url: `/${sheet.path}`,
        frameCount: clipSpec.frameCount,
        durationMs,
        animate
      };
    },
    [idleAnimation, spriteCatalog]
  );

  const spriteStyle = useCallback(
    (piece: BoardSquare): React.CSSProperties => {
      const isBlack = piece?.startsWith("b") ?? false;
      const transform = pieceTransform(isBlack);
      const info = resolveSpriteInfo(piece);
      if (!info) return { transform };

      const frameIndex = (info.frameCount <= 1 || !info.animate)
        ? 0
        : currentFrameTick % info.frameCount;

      return {
        transform,
        backgroundImage: `url(${info.url})`,
        backgroundSize: `${info.frameCount * 100}% 100%`,
        backgroundPosition: backgroundPositionFor(frameIndex, info.frameCount)
      } as React.CSSProperties;
    },
    [resolveSpriteInfo, currentFrameTick]
  );

  const spriteClasses = useCallback(
    (piece: BoardSquare): string => {
      const info = resolveSpriteInfo(piece);
      if (!info) return "";
      return ["has-sprite"].filter(Boolean).join(" ");
    },
    [resolveSpriteInfo]
  );

  const updateSquareSize = useCallback(() => {
    if (!boardRef.current) return;
    const square = boardRef.current.querySelector<HTMLElement>(".board-square");
    if (!square) return;
    setSquareSize(square.getBoundingClientRect().width);
  }, []);

  const squareToPixel = useCallback(
    (square: string): { x: number; y: number } | null => {
      const coords = squareToDisplayCoords(square);
      if (!coords) return null;
      return {
        x: coords.displayCol * squareSize,
        y: coords.displayRow * squareSize
      };
    },
    [squareSize]
  );

  const animationRenderModel = useMemo(() => {
    if (!animationPlan || !animationActive) return null;
    const plan = animationPlan;
    const fromPixel = squareToPixel(plan.from);
    const toPixel = squareToPixel(plan.to);
    if (!fromPixel || !toPixel) return null;

    const t = Math.max(0, Math.min(1, animationProgress));
    const dx = toPixel.x - fromPixel.x;
    const flipX = dx > 0 ? false : dx < 0 ? true : plan.movingPiece.startsWith("b");

    if (!plan.isCapture) {
      const style = motionStyleFor(plan.movingPiece, false);
      const position = interpolate(style, fromPixel.x, fromPixel.y, toPixel.x, toPixel.y, t);
      const moveSprite = resolveSegmentedSprite(plan.movingPiece, "move", t);
      return {
        moving: {
          piece: plan.movingPiece,
          x: position.x,
          y: position.y,
          opacity: 1,
          scale: 1,
          flipX,
          visualState: "move" as VisualState,
          frameIndex: moveSprite.frameIndex,
          sheet: moveSprite.sheet
        },
        captured: null
      };
    }

    const phase = resolveCapturePhase(t);
    const style = motionStyleFor(plan.movingPiece, false);

    const resolveMoving = () => {
      if (phase.phase === "approach") {
        const position = interpolate(style, fromPixel.x, fromPixel.y, toPixel.x, toPixel.y, phase.local);
        const moveSprite = resolveSegmentedSprite(plan.movingPiece, "move", phase.local);
        return {
          x: position.x,
          y: position.y,
          visualState: "move" as VisualState,
          frameIndex: moveSprite.frameIndex,
          sheet: moveSprite.sheet
        };
      }

      const isAttack1 = phase.phase === "attack1" || phase.phase === "dead" || phase.phase === "fade";
      const progress = phase.phase === "dead" || phase.phase === "fade" ? 1 : phase.local;
      const attackSprite = resolveAttackSegment(plan.movingPiece, isAttack1 ? 1 : 0, progress);
      return {
        x: toPixel.x,
        y: toPixel.y,
        visualState: (isAttack1 ? "attack1" : "attack") as VisualState,
        frameIndex: attackSprite.frameIndex,
        sheet: attackSprite.sheet
      };
    };

    const moving = resolveMoving();

    let captured = null as null | {
      piece: PieceCode;
      x: number;
      y: number;
      opacity: number;
      visualState: VisualState;
      frameIndex: number;
      sheet: { url: string; frameCount: number } | null;
      flipX: boolean;
    };

    if (plan.capturedPiece) {
      const capturedPiece = plan.capturedPiece;
      if (phase.phase === "approach" || phase.phase === "attack" || phase.phase === "attack1") {
        const idleSprite = resolveSegmentedSprite(capturedPiece, "idle", 0);
        captured = {
          piece: capturedPiece,
          x: toPixel.x,
          y: toPixel.y,
          opacity: 1,
          visualState: "idle",
          frameIndex: idleSprite.frameIndex,
          sheet: idleSprite.sheet,
          flipX: capturedPiece.startsWith("b")
        };
      } else {
        const deadProgress = phase.phase === "fade" ? 1 : phase.local;
        const deadSprite = resolveSegmentedSprite(capturedPiece, "dead", deadProgress);
        const opacity = phase.phase === "fade" ? 1 - phase.local : 1;
        captured = {
          piece: capturedPiece,
          x: toPixel.x,
          y: toPixel.y,
          opacity,
          visualState: "dead",
          frameIndex: deadSprite.frameIndex,
          sheet: deadSprite.sheet,
          flipX: capturedPiece.startsWith("b")
        };
      }
    }

    return {
      moving: {
        piece: plan.movingPiece,
        x: moving.x,
        y: moving.y,
        opacity: 1,
        scale: 1,
        flipX,
        visualState: moving.visualState,
        frameIndex: moving.frameIndex,
        sheet: moving.sheet
      },
      captured
    };
  }, [animationActive, animationPlan, animationProgress, resolveAttackSegment, resolveSegmentedSprite, squareToPixel]);

  const movingStyle = useMemo(() => {
    const model = animationRenderModel?.moving;
    if (!model) return {};
    return {
      left: `${model.x}px`,
      top: `${model.y}px`,
      width: `${squareSize}px`,
      height: `${squareSize}px`,
      opacity: model.opacity.toString(),
      transform: pieceTransform(model.flipX, model.scale * PIECE_SCALE),
      backgroundImage: model.sheet ? `url(${model.sheet.url})` : "",
      backgroundSize: model.sheet ? `${model.sheet.frameCount * 100}% 100%` : "100% 100%",
      backgroundPosition: model.sheet
        ? backgroundPositionFor(model.frameIndex, model.sheet.frameCount)
        : "0% 50%"
    } as React.CSSProperties;
  }, [animationRenderModel, squareSize]);

  const capturedStyle = useMemo(() => {
    const model = animationRenderModel?.captured;
    if (!model) return {};
    return {
      left: `${model.x}px`,
      top: `${model.y}px`,
      width: `${squareSize}px`,
      height: `${squareSize}px`,
      opacity: model.opacity.toString(),
      transform: pieceTransform(model.flipX),
      backgroundImage: model.sheet ? `url(${model.sheet.url})` : "",
      backgroundSize: model.sheet ? `${model.sheet.frameCount * 100}% 100%` : "100% 100%",
      backgroundPosition: model.sheet
        ? backgroundPositionFor(model.frameIndex, model.sheet.frameCount)
        : "0% 50%"
    } as React.CSSProperties;
  }, [animationRenderModel, squareSize]);

  const startAnimation = useCallback(
    (plan: BoardAnimation) => {
      if (animationFrame.current !== null) {
        cancelAnimationFrame(animationFrame.current);
      }
      setAnimationPlan(plan);
      setAnimationActive(true);
      setAnimationProgress(0);
      const duration = animationDurationMs(plan);
      const start = performance.now();

      const tick = () => {
        const now = performance.now();
        const elapsed = now - start;
        const progress = duration > 0 ? elapsed / duration : 1;
        setAnimationProgress(Math.max(0, Math.min(1, progress)));
        if (progress < 1) {
          animationFrame.current = requestAnimationFrame(tick);
        } else {
          setAnimationActive(false);
          animationFrame.current = null;
          onAnimationFinished(plan.id);
        }
      };

      animationFrame.current = requestAnimationFrame(tick);
    },
    [onAnimationFinished]
  );

  useEffect(() => {
    let active = true;
    loadSpriteCatalog()
      .then((catalog) => {
        if (active) setSpriteCatalog(catalog);
      })
      .catch(() => {
        if (active) setSpriteCatalog(null);
      });
    updateSquareSize();
    window.addEventListener("resize", updateSquareSize);

    const tickIdle = () => {
      setCurrentFrameTick(Math.floor((performance.now() / 1000) * idleFps));
      idleTimerFrame.current = requestAnimationFrame(tickIdle);
    };
    idleTimerFrame.current = requestAnimationFrame(tickIdle);

    return () => {
      active = false;
      window.removeEventListener("resize", updateSquareSize);
      if (animationFrame.current !== null) {
        cancelAnimationFrame(animationFrame.current);
      }
      if (idleTimerFrame.current !== null) {
        cancelAnimationFrame(idleTimerFrame.current);
      }
    };
  }, [updateSquareSize]);

  useEffect(() => {
    if (!animation || !animation.id) {
      setAnimationActive(false);
      setAnimationPlan(null);
      setAnimationProgress(0);
      return;
    }
    updateSquareSize();
    startAnimation(animation);
  }, [animation, startAnimation, updateSquareSize]);

  return (
    <section className="board-shell" aria-label="Chess board">
      <div className="board-grid" role="grid" ref={boardRef}>
        {indices.map((rowIndex) => (
          <div key={`row-${rowIndex}`} className="board-row" role="row">
            {indices.map((colIndex) => {
              const square = displayToSquare(rowIndex, colIndex);
              const piece = boardPieceAt(rowIndex, colIndex);
              const suppressed = square === suppressedSquare;
              const spriteClass = piece ? spriteClasses(piece) : "";
              return (
                <button
                  key={`square-${rowIndex}-${colIndex}`}
                  className={`board-square ${(rowIndex + colIndex) % 2 === 0 ? "square-light" : "square-dark"} ${piece ? "has-piece" : ""
                    } ${squareState(square).join(" ")}`}
                  type="button"
                  role="gridcell"
                  aria-label={`Square ${square}`}
                  disabled={disabled}
                  onClick={() => onSelect(square)}
                >
                  {piece && !suppressed ? (
                    <span
                      className={`piece ${pieceTone(piece)} ${spriteClass}`}
                      style={spriteStyle(piece)}
                    >
                      {pieceLabel(piece)}
                    </span>
                  ) : null}
                </button>
              );
            })}
          </div>
        ))}
        {animationRenderModel ? (
          <div className="animation-layer" aria-hidden="true">
            <div className="animation-piece" style={movingStyle}></div>
            {animationRenderModel.captured ? (
              <div className="animation-piece" style={capturedStyle}></div>
            ) : null}
          </div>
        ) : null}
      </div>
    </section>
  );
}
