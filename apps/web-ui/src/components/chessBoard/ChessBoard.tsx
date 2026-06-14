import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { BoardMatrix, BoardSquare, PieceCode, GameStatus, PlayerColor } from "../../api/types";
import { displayToIndex, displayToSquare, indexToSquare, squareToDisplayCoords } from "../../domain/board";
import type { PlaybackMode, SpriteCatalog, StatePlaybackEntry } from "../../assets/spriteCatalog";
import { loadSpriteCatalog } from "../../assets/spriteCatalog";
import type { BoardAnimation } from "../../animation/animationTypes";
import { idleFps } from "../../animation/animationConfig";
import type { PromotionPiece } from "../../api/backendTypes";
import {
  clamp,
  assetKeyFor,
  pieceDescriptor,
  pieceLabel,
  pieceTone,
  pieceTransform,
} from "./types";
import type { SpriteInfo, VisualState, AnimationRenderModel } from "./types";
import {
  animationDurationMs,
  backgroundPositionFor,
  interpolate,
  motionStyleFor,
  planSegment,
  resolveCapturePhase,
  selectFrameIndex,
} from "./animationHelpers";
import { squareAriaLabel } from "./helpers/accessibility";
import AnimationLayer from "./components/AnimationLayer";
import PromotionDialog from "./components/PromotionDialog";
import GameOverDialog from "./components/GameOverDialog";
import "./ChessBoard.css";

export type ChessBoardProps = {
  board: BoardMatrix;
  selectedSquare?: string;
  legalMoves?: string[];
  animation?: BoardAnimation | null;
  idleAnimation?: boolean;
  disabled?: boolean;
  onSelect: (square: string) => void;
  onAnimationFinished: (id: number) => void;
  inCheck?: boolean;
  activeColor?: PlayerColor;
  gameStatus?: GameStatus;
  drawReason?: string;
  winner?: PlayerColor;
  promotionPending?: { from: string; to: string } | null;
  onResolvePromotion?: (piece: PromotionPiece) => void;
  onCancelPromotion?: () => void;
  onNewGame?: () => void;
  orientation?: PlayerColor;
  lastMove?: { from: string; to: string };
  transparentOverlay?: boolean;
};

const indices = Array.from({ length: 8 }, (_, i) => i);

export default function ChessBoard({
  board,
  selectedSquare,
  legalMoves,
  animation,
  idleAnimation,
  disabled = false,
  onSelect,
  onAnimationFinished,
  inCheck = false,
  activeColor,
  gameStatus,
  drawReason,
  winner,
  promotionPending,
  onResolvePromotion,
  onCancelPromotion,
  onNewGame,
  orientation = "white",
  lastMove,
  transparentOverlay = false,
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
  const [dismissedGameOver, setDismissedGameOver] = useState(false);

  useEffect(() => { setDismissedGameOver(false); }, [board]);

  // ── Derived state ────────────────────────────────────────────────────────────

  const checkSquare = useMemo(() => {
    if (!inCheck || !activeColor) return undefined;
    const kingCode = activeColor === "white" ? "wK" : "bK";
    for (let r = 0; r < 8; r++) {
      for (let c = 0; c < 8; c++) {
        if (board[r][c] === kingCode) return indexToSquare(r, c);
      }
    }
    return undefined;
  }, [inCheck, activeColor, board]);

  const showGameOver =
    !dismissedGameOver &&
    (gameStatus === "checkmate" || gameStatus === "draw" || gameStatus === "resigned");

  const gameOverDetails = useMemo(() => {
    if (gameStatus === "checkmate") {
      return {
        title: "Checkmate",
        message: winner ? `${winner.toUpperCase()} wins!` : "The game has ended.",
      };
    }
    if (gameStatus === "resigned") {
      return {
        title: "Resigned",
        message: winner
          ? `${winner.toUpperCase()} wins by resignation!`
          : "Game over by resignation.",
      };
    }
    if (gameStatus === "draw") {
      const reasonMap: Record<string, string> = {
        stalemate: "Stalemate",
        fiftyMoves: "50-move rule",
        threefoldRepetition: "Threefold repetition",
        insufficientMaterial: "Insufficient material",
      };
      return {
        title: "Draw",
        message: drawReason ? `Draw by ${reasonMap[drawReason] ?? drawReason}.` : "Draw.",
      };
    }
    return null;
  }, [gameStatus, winner, drawReason]);

  const promotionOptions = useMemo(() => {
    if (!promotionPending || !activeColor) return [];
    const prefix = activeColor === "white" ? "w" : "b";
    return [
      { pieceType: "Queen" as PromotionPiece, code: `${prefix}Q` as PieceCode, name: "Queen" },
      { pieceType: "Rook" as PromotionPiece, code: `${prefix}R` as PieceCode, name: "Rook" },
      { pieceType: "Bishop" as PromotionPiece, code: `${prefix}B` as PieceCode, name: "Bishop" },
      { pieceType: "Knight" as PromotionPiece, code: `${prefix}N` as PieceCode, name: "Knight" },
    ];
  }, [promotionPending, activeColor]);

  // ── Board helpers ────────────────────────────────────────────────────────────

  const boardPieceAt = useCallback(
    (rowIndex: number, colIndex: number): BoardSquare => {
      const mapped = displayToIndex(rowIndex, colIndex);
      return board[mapped.row]?.[mapped.col] ?? null;
    },
    [board]
  );

  const squareStateClasses = useCallback(
    (square: string): string[] => {
      const classes: string[] = [];
      if (lastMove && (lastMove.from === square || lastMove.to === square))
        classes.push("is-last-move");
      if (selectedSquare === square) classes.push("is-selected");
      if (legalMoves?.includes(square)) classes.push("is-legal");
      if (checkSquare === square) classes.push("is-check");
      return classes;
    },
    [legalMoves, selectedSquare, checkSquare, lastMove]
  );

  const suppressedSquare = useMemo(
    () => (animationActive && animationPlan ? animationPlan.to : undefined),
    [animationActive, animationPlan]
  );

  const suppressedCapturedSquare = useMemo(
    () =>
      animationActive && animationPlan
        ? (animationPlan.capturedSquare ?? animationPlan.to)
        : undefined,
    [animationActive, animationPlan]
  );

  const suppressedCastlingRookSquare = useMemo(
    () =>
      animationActive && animationPlan?.castlingRook
        ? animationPlan.castlingRook.from
        : undefined,
    [animationActive, animationPlan]
  );

  // ── Sprite resolution ────────────────────────────────────────────────────────

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
      return spriteCatalog.statePlayback?.[assetKeyFor(piece, state)] ?? null;
    },
    [spriteCatalog]
  );

  const resolveSegmentedSprite = useCallback(
    (piece: PieceCode, state: VisualState, progress: number) => {
      const playback = lookupPlayback(piece, state);
      if (playback) {
        const planned = planSegment(
          playback as StatePlaybackEntry & { mode: PlaybackMode },
          progress
        );
        const sheet = resolveSheetByKey(planned.segmentKey);
        if (sheet) {
          return {
            sheet,
            frameIndex: selectFrameIndex(state, sheet.frameCount, planned.localProgress),
          };
        }
      }
      const fallback = resolveSheetByKey(assetKeyFor(piece, state));
      return {
        sheet: fallback,
        frameIndex: fallback ? selectFrameIndex(state, fallback.frameCount, progress) : 0,
      };
    },
    [lookupPlayback, resolveSheetByKey]
  );

  const resolveAttackSegment = useCallback(
    (piece: PieceCode, segmentIndex: number, progress: number) => {
      const playback = lookupPlayback(piece, "attack");
      if (playback && playback.segments.length > 0) {
        const idx = clamp(segmentIndex, 0, playback.segments.length - 1);
        const sheet = resolveSheetByKey(playback.segments[idx]);
        if (sheet) {
          return { sheet, frameIndex: selectFrameIndex("attack", sheet.frameCount, progress) };
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
      const spriteKey = `classic/${descriptor.color}_${descriptor.name}_idle`;
      const sheet = spriteCatalog.spriteSheets[spriteKey];
      if (!sheet) return null;
      const clipSpec = spriteCatalog.clipSpecs[sheet.clipSpec];
      if (!clipSpec) return null;
      const animate = idleAnimation !== false;
      const durationMs = animate ? Math.max(200, (clipSpec.frameCount / idleFps) * 1000) : 0;
      return { url: `/${sheet.path}`, frameCount: clipSpec.frameCount, durationMs, animate };
    },
    [idleAnimation, spriteCatalog]
  );

  const spriteStyle = useCallback(
    (piece: BoardSquare): React.CSSProperties => {
      const isBlack = piece?.startsWith("b") ?? false;
      const transform = pieceTransform(isBlack);
      const info = resolveSpriteInfo(piece);
      if (!info) return { transform };
      const frameIndex =
        info.frameCount <= 1 || !info.animate ? 0 : currentFrameTick % info.frameCount;
      return {
        transform,
        backgroundImage: `url(${info.url})`,
        backgroundSize: `${info.frameCount * 100}% 100%`,
        backgroundPosition: backgroundPositionFor(frameIndex, info.frameCount),
      };
    },
    [resolveSpriteInfo, currentFrameTick]
  );

  const spriteClasses = useCallback(
    (piece: BoardSquare): string => (resolveSpriteInfo(piece) ? "has-sprite" : ""),
    [resolveSpriteInfo]
  );

  // ── Sizing ───────────────────────────────────────────────────────────────────

  const updateSquareSize = useCallback(() => {
    const square = boardRef.current?.querySelector<HTMLElement>(".board-square");
    if (square) setSquareSize(square.getBoundingClientRect().width);
  }, []);

  const squareToPixel = useCallback(
    (square: string): { x: number; y: number } | null => {
      const coords = squareToDisplayCoords(square);
      if (!coords) return null;
      const displayCol = orientation === "black" ? 7 - coords.displayCol : coords.displayCol;
      const displayRow = orientation === "black" ? 7 - coords.displayRow : coords.displayRow;
      return { x: displayCol * squareSize, y: displayRow * squareSize };
    },
    [squareSize, orientation]
  );

  // ── Animation render model ───────────────────────────────────────────────────

  const animationRenderModel = useMemo((): AnimationRenderModel | null => {
    if (!animationPlan || !animationActive) return null;
    const plan = animationPlan;
    const fromPixel = squareToPixel(plan.from);
    const toPixel = squareToPixel(plan.to);
    if (!fromPixel || !toPixel) return null;

    const t = clamp(animationProgress, 0, 1);
    const dx = toPixel.x - fromPixel.x;
    const flipX = dx > 0 ? false : dx < 0 ? true : plan.movingPiece.startsWith("b");

    let castlingRook: AnimationRenderModel["castlingRook"] = null;
    if (plan.castlingRook) {
      const rp = plan.castlingRook;
      const rfrom = squareToPixel(rp.from);
      const rto = squareToPixel(rp.to);
      if (rfrom && rto) {
        const pos = interpolate(motionStyleFor(rp.piece, false), rfrom.x, rfrom.y, rto.x, rto.y, t);
        const sp = resolveSegmentedSprite(rp.piece, "move", t);
        castlingRook = {
          piece: rp.piece,
          x: pos.x, y: pos.y, opacity: 1, scale: 1,
          flipX: rp.piece.startsWith("b"),
          visualState: "move",
          frameIndex: sp.frameIndex,
          sheet: sp.sheet,
        };
      }
    }

    if (!plan.isCapture) {
      const style = motionStyleFor(plan.movingPiece, false);
      const pos = interpolate(style, fromPixel.x, fromPixel.y, toPixel.x, toPixel.y, t);
      const sp = resolveSegmentedSprite(plan.movingPiece, "move", t);
      return {
        moving: {
          piece: plan.movingPiece, x: pos.x, y: pos.y,
          opacity: 1, scale: 1, flipX,
          visualState: "move", frameIndex: sp.frameIndex, sheet: sp.sheet,
        },
        captured: null,
        castlingRook,
      };
    }

    const phase = resolveCapturePhase(t);
    const moveStyle = motionStyleFor(plan.movingPiece, false);

    const resolveMoving = () => {
      if (phase.phase === "approach") {
        const pos = interpolate(
          moveStyle, fromPixel.x, fromPixel.y, toPixel.x, toPixel.y, phase.local
        );
        const sp = resolveSegmentedSprite(plan.movingPiece, "move", phase.local);
        return { x: pos.x, y: pos.y, visualState: "move" as VisualState, frameIndex: sp.frameIndex, sheet: sp.sheet };
      }
      const isAttack1 = ["attack1", "dead", "fade"].includes(phase.phase);
      const progress = ["dead", "fade"].includes(phase.phase) ? 1 : phase.local;
      const sp = resolveAttackSegment(plan.movingPiece, isAttack1 ? 1 : 0, progress);
      return {
        x: toPixel.x, y: toPixel.y,
        visualState: (isAttack1 ? "attack1" : "attack") as VisualState,
        frameIndex: sp.frameIndex, sheet: sp.sheet,
      };
    };

    const moving = resolveMoving();

    let captured: AnimationRenderModel["captured"] = null;
    const capturedSquare = plan.capturedSquare ?? plan.to;
    const capturedPixel = squareToPixel(capturedSquare);

    if (plan.capturedPiece && capturedPixel) {
      const cp = plan.capturedPiece;
      if (["approach", "attack", "attack1"].includes(phase.phase)) {
        const sp = resolveSegmentedSprite(cp, "idle", 0);
        captured = {
          piece: cp, x: capturedPixel.x, y: capturedPixel.y,
          opacity: 1, scale: 1, flipX: cp.startsWith("b"),
          visualState: "idle", frameIndex: sp.frameIndex, sheet: sp.sheet,
        };
      } else {
        const deadProgress = phase.phase === "fade" ? 1 : phase.local;
        const sp = resolveSegmentedSprite(cp, "dead", deadProgress);
        captured = {
          piece: cp, x: capturedPixel.x, y: capturedPixel.y,
          opacity: phase.phase === "fade" ? 1 - phase.local : 1,
          scale: 1, flipX: cp.startsWith("b"),
          visualState: "dead", frameIndex: sp.frameIndex, sheet: sp.sheet,
        };
      }
    }

    return {
      moving: {
        piece: plan.movingPiece, x: moving.x, y: moving.y,
        opacity: 1, scale: 1, flipX,
        visualState: moving.visualState, frameIndex: moving.frameIndex, sheet: moving.sheet,
      },
      captured,
      castlingRook,
    };
  }, [animationActive, animationPlan, animationProgress, resolveAttackSegment, resolveSegmentedSprite, squareToPixel]);

  // ── Animation ticker ─────────────────────────────────────────────────────────

  const startAnimation = useCallback(
    (plan: BoardAnimation) => {
      if (animationFrame.current !== null) cancelAnimationFrame(animationFrame.current);
      setAnimationPlan(plan);
      setAnimationActive(true);
      setAnimationProgress(0);
      const duration = animationDurationMs(plan);
      const start = performance.now();
      const tick = () => {
        const progress = duration > 0 ? (performance.now() - start) / duration : 1;
        setAnimationProgress(clamp(progress, 0, 1));
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
      .then((catalog) => { if (active) setSpriteCatalog(catalog); })
      .catch(() => { if (active) setSpriteCatalog(null); });

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
      if (animationFrame.current !== null) cancelAnimationFrame(animationFrame.current);
      if (idleTimerFrame.current !== null) cancelAnimationFrame(idleTimerFrame.current);
    };
  }, [updateSquareSize]);

  useEffect(() => {
    if (!animation?.id) {
      setAnimationActive(false);
      setAnimationPlan(null);
      setAnimationProgress(0);
      return;
    }
    updateSquareSize();
    startAnimation(animation);
  }, [animation, startAnimation, updateSquareSize]);

  // ── Render ───────────────────────────────────────────────────────────────────

  return (
    <section
      className={`board-shell${transparentOverlay ? " board-shell--transparent-overlay" : ""}`}
      aria-label="Chess board"
    >
      <div className="board-grid" role="grid" ref={boardRef}>
        {indices.map((rowIndex) => (
          <div key={`row-${rowIndex}`} className="board-row" role="row">
            {indices.map((colIndex) => {
              const mappedRow = orientation === "black" ? 7 - rowIndex : rowIndex;
              const mappedCol = orientation === "black" ? 7 - colIndex : colIndex;
              const square = displayToSquare(mappedRow, mappedCol);
              const piece = boardPieceAt(mappedRow, mappedCol);
              const suppressed =
                square === suppressedSquare ||
                square === suppressedCapturedSquare ||
                square === suppressedCastlingRookSquare;
              const stateClasses = squareStateClasses(square);
              return (
                <button
                  key={`square-${rowIndex}-${colIndex}`}
                  className={[
                    "board-square",
                    (rowIndex + colIndex) % 2 === 0 ? "square-light" : "square-dark",
                    piece ? "has-piece" : "",
                    ...stateClasses,
                  ].join(" ")}
                  type="button"
                  role="gridcell"
                  aria-label={squareAriaLabel(square, piece)}
                  aria-selected={stateClasses.includes("is-selected")}
                  disabled={disabled || Boolean(promotionPending) || showGameOver}
                  onClick={() => onSelect(square)}
                >
                  {piece && !suppressed ? (
                    <span
                      className={`piece ${pieceTone(piece)} ${spriteClasses(piece)}`}
                      style={spriteStyle(piece)}
                      aria-hidden="true"
                    >
                      {pieceLabel(piece)}
                    </span>
                  ) : null}
                </button>
              );
            })}
          </div>
        ))}

        {animationRenderModel && (
          <AnimationLayer model={animationRenderModel} squareSize={squareSize} />
        )}

        {promotionPending && (
          <PromotionDialog
            options={promotionOptions}
            onResolve={(piece) => onResolvePromotion?.(piece)}
            onCancel={() => onCancelPromotion?.()}
            spriteStyle={spriteStyle}
          />
        )}

        {showGameOver && gameOverDetails && (
          <GameOverDialog
            title={gameOverDetails.title}
            message={gameOverDetails.message}
            onNewGame={
              onNewGame
                ? () => { setDismissedGameOver(false); onNewGame(); }
                : undefined
            }
            onDismiss={() => setDismissedGameOver(true)}
          />
        )}
      </div>

    </section>
  );
}
