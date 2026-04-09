<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import type { BoardMatrix, BoardSquare, PieceCode } from "../api/types";
import type { PlaybackMode, SpriteCatalog, StatePlaybackEntry } from "../assets/spriteCatalog";
import { loadSpriteCatalog } from "../assets/spriteCatalog";

const props = defineProps<{
  board: BoardMatrix;
  selectedSquare?: string;
  legalMoves?: string[];
  animation?: BoardAnimation | null;
  idleAnimation?: boolean;
}>();

const emit = defineEmits<{
  (event: "select", payload: string): void;
  (event: "animation-finished", payload: number): void;
}>();

type SpriteState = "idle" | "move";
type SpriteInfo = { url: string; frameCount: number; durationMs: number; animate: boolean };
type VisualState = "idle" | "move" | "attack" | "attack1" | "dead" | "hit";
type MotionStyle =
  | { kind: "linear" }
  | { kind: "smooth" }
  | { kind: "heavy" }
  | { kind: "arc"; heightFraction: number }
  | { kind: "attack"; overshootFraction: number };

type BoardAnimation = {
  id: number;
  from: string;
  to: string;
  movingPiece: PieceCode;
  capturedPiece?: PieceCode;
  isCapture: boolean;
};

const spriteCatalog = ref<SpriteCatalog | null>(null);
const boardRef = ref<HTMLDivElement | null>(null);
const squareSize = ref(68);
const animationProgress = ref(0);
const animationActive = ref(false);
const animationPlan = ref<BoardAnimation | null>(null);
const animationFrame = ref<number | null>(null);

const moveDurationMs = 340;
const idleFps = 6;
const captureTimings = {
  approachMs: 400,
  attackMs: 500,
  attack1Ms: 500,
  deadMs: 1000,
  fadeMs: 450
};

const files = ["a", "b", "c", "d", "e", "f", "g", "h"];
const indices = Array.from({ length: 8 }, (_, index) => index);

const displayToBoardIndex = (rowIndex: number, colIndex: number) => {
  // Rotate the board 90 degrees clockwise to match the desktop GUI projection.
  return { row: 7 - colIndex, col: 7 - rowIndex };
};

const toSquare = (rowIndex: number, colIndex: number): string => {
  const mapped = displayToBoardIndex(rowIndex, colIndex);
  const file = files[mapped.col];
  const rank = 8 - mapped.row;
  return `${file}${rank}`;
};

const boardPieceAt = (rowIndex: number, colIndex: number): BoardSquare => {
  const mapped = displayToBoardIndex(rowIndex, colIndex);
  return props.board[mapped.row]?.[mapped.col] ?? null;
};

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

const lookupPlayback = (piece: PieceCode, state: VisualState): StatePlaybackEntry | null => {
  if (!spriteCatalog.value) return null;
  const key = assetKeyFor(piece, state);
  return spriteCatalog.value.statePlayback?.[key] ?? null;
};

const resolveSheetByKey = (assetKey: string): { url: string; frameCount: number } | null => {
  if (!spriteCatalog.value || !assetKey) return null;
  const sheet = spriteCatalog.value.spriteSheets[assetKey];
  if (!sheet) return null;
  const clipSpec = spriteCatalog.value.clipSpecs[sheet.clipSpec];
  if (!clipSpec) return null;
  return { url: `/${sheet.path}`, frameCount: clipSpec.frameCount };
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

const resolveSegmentedSprite = (
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
};

const resolveAttackSegment = (
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
};

const pieceTone = (piece: BoardSquare): string => {
  if (!piece) return "";
  return piece.startsWith("w") ? "piece-light" : "piece-dark";
};

const squareState = (square: string): string[] => {
  const classes: string[] = [];
  if (props.selectedSquare === square) classes.push("is-selected");
  if (props.legalMoves?.includes(square)) classes.push("is-legal");
  return classes;
};

const suppressedSquare = computed(() =>
  animationActive.value && animationPlan.value ? animationPlan.value.to : undefined
);

const resolveSpriteInfo = (piece: BoardSquare): SpriteInfo | null => {
  if (!spriteCatalog.value) return null;
  const descriptor = pieceDescriptor(piece);
  if (!descriptor) return null;

  const state: SpriteState = "idle";
  const spriteKey = `classic/${descriptor.color}_${descriptor.name}_${state}`;
  const sheet = spriteCatalog.value.spriteSheets[spriteKey];
  if (!sheet) return null;
  const clipSpec = spriteCatalog.value.clipSpecs[sheet.clipSpec];
  if (!clipSpec) return null;

  const animate = props.idleAnimation !== false;
  const durationMs = animate
    ? Math.max(200, (clipSpec.frameCount / idleFps) * 1000)
    : 0;

  return {
    url: `/${sheet.path}`,
    frameCount: clipSpec.frameCount,
    durationMs,
    animate
  };
};

const spriteStyle = (piece: BoardSquare, square: string): Record<string, string> => {
  const info = resolveSpriteInfo(piece);
  if (!info) return {};
  return {
    backgroundImage: `url(${info.url})`,
    "--frame-count": info.frameCount.toString(),
    "--anim-duration": `${info.durationMs}ms`
  };
};

const spriteClasses = (piece: BoardSquare, square: string): string[] => {
  const info = resolveSpriteInfo(piece);
  if (!info) return [];
  return ["has-sprite", info.animate ? "animate-sprite" : ""];
};

const updateSquareSize = () => {
  if (!boardRef.value) return;
  const square = boardRef.value.querySelector<HTMLElement>(".board-square");
  if (!square) return;
  squareSize.value = square.getBoundingClientRect().width;
};

const squareToPixel = (square: string): { x: number; y: number } | null => {
  if (square.length !== 2) return null;
  const files = "abcdefgh";
  const file = square[0].toLowerCase();
  const rank = Number(square[1]);
  const fileIndex = files.indexOf(file);
  if (fileIndex < 0 || Number.isNaN(rank) || rank < 1 || rank > 8) return null;
  const rankIndex = rank - 1;
  return {
    x: rankIndex * squareSize.value,
    y: (7 - fileIndex) * squareSize.value
  };
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
      const e = smoothstep(t);
      return { x: lerp(fromX, toX, e), y: lerp(fromY, toY, e) };
    }
    case "heavy": {
      const e = easeInOut(t);
      return { x: lerp(fromX, toX, e), y: lerp(fromY, toY, e) };
    }
    case "arc": {
      const e = smoothstep(t);
      const baseX = lerp(fromX, toX, e);
      const baseY = lerp(fromY, toY, e);
      const dist = Math.hypot(toX - fromX, toY - fromY);
      const lift = 4 * style.heightFraction * dist * t * (1 - t);
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
      const peakT = 0.65;
      if (t <= peakT) {
        const s = smoothstep(t / peakT);
        return { x: lerp(fromX, osX, s), y: lerp(fromY, osY, s) };
      }
      const s = smoothstep((t - peakT) / (1 - peakT));
      return { x: lerp(osX, toX, s), y: lerp(osY, toY, s) };
    }
  }
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

const animationRenderModel = computed(() => {
  if (!animationPlan.value || !animationActive.value) return null;
  const plan = animationPlan.value;
  const fromPixel = squareToPixel(plan.from);
  const toPixel = squareToPixel(plan.to);
  if (!fromPixel || !toPixel) return null;

  const t = Math.max(0, Math.min(1, animationProgress.value));
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
});

const backgroundPositionFor = (frameIndex: number, frameCount: number) => {
  if (frameCount <= 1) return "0% 50%";
  const ratio = frameIndex / Math.max(1, frameCount - 1);
  return `${ratio * 100}% 50%`;
};

const movingStyle = computed(() => {
  const model = animationRenderModel.value?.moving;
  if (!model) return {};
  return {
    left: `${model.x}px`,
    top: `${model.y}px`,
    width: `${squareSize.value}px`,
    height: `${squareSize.value}px`,
    opacity: model.opacity.toString(),
    transform: `scaleX(${model.flipX ? -1 : 1}) scale(${model.scale})`,
    backgroundImage: model.sheet ? `url(${model.sheet.url})` : "",
    backgroundSize: model.sheet ? `${model.sheet.frameCount * 100}% 100%` : "100% 100%",
    backgroundPosition: model.sheet
      ? backgroundPositionFor(model.frameIndex, model.sheet.frameCount)
      : "0% 50%"
  };
});

const capturedStyle = computed(() => {
  const model = animationRenderModel.value?.captured;
  if (!model) return {};
  return {
    left: `${model.x}px`,
    top: `${model.y}px`,
    width: `${squareSize.value}px`,
    height: `${squareSize.value}px`,
    opacity: model.opacity.toString(),
    transform: `scaleX(${model.flipX ? -1 : 1})`,
    backgroundImage: model.sheet ? `url(${model.sheet.url})` : "",
    backgroundSize: model.sheet ? `${model.sheet.frameCount * 100}% 100%` : "100% 100%",
    backgroundPosition: model.sheet
      ? backgroundPositionFor(model.frameIndex, model.sheet.frameCount)
      : "0% 50%"
  };
});

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

const startAnimation = (plan: BoardAnimation) => {
  if (animationFrame.value !== null) {
    cancelAnimationFrame(animationFrame.value);
  }
  animationPlan.value = plan;
  animationActive.value = true;
  animationProgress.value = 0;
  const duration = animationDurationMs(plan);
  const start = performance.now();

  const tick = (now: number) => {
    const elapsed = now - start;
    const progress = duration > 0 ? elapsed / duration : 1;
    animationProgress.value = Math.max(0, Math.min(1, progress));
    if (progress < 1) {
      animationFrame.value = requestAnimationFrame(tick);
    } else {
      animationActive.value = false;
      animationFrame.value = null;
      emit("animation-finished", plan.id);
    }
  };

  animationFrame.value = requestAnimationFrame(tick);
};

onMounted(async () => {
  try {
    spriteCatalog.value = await loadSpriteCatalog();
  } catch {
    spriteCatalog.value = null;
  }
  updateSquareSize();
  window.addEventListener("resize", updateSquareSize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", updateSquareSize);
  if (animationFrame.value !== null) {
    cancelAnimationFrame(animationFrame.value);
  }
});

watch(
  () => props.animation?.id,
  (id) => {
    if (!props.animation || !id) {
      animationActive.value = false;
      animationPlan.value = null;
      animationProgress.value = 0;
      return;
    }
    updateSquareSize();
    startAnimation(props.animation);
  }
);
</script>

<template>
  <section class="board-shell" aria-label="Chess board">
    <div class="board-grid" role="grid" ref="boardRef">
      <div v-for="rowIndex in indices" :key="`row-${rowIndex}`" class="board-row" role="row">
        <button
          v-for="colIndex in indices"
          :key="`square-${rowIndex}-${colIndex}`"
          class="board-square"
          :class="[
            (rowIndex + colIndex) % 2 === 0 ? 'square-light' : 'square-dark',
            boardPieceAt(rowIndex, colIndex) ? 'has-piece' : '',
            ...squareState(toSquare(rowIndex, colIndex))
          ]"
          type="button"
          role="gridcell"
          :aria-label="`Square ${toSquare(rowIndex, colIndex)}`"
          @click="emit('select', toSquare(rowIndex, colIndex))"
        >
          <span
            v-if="
              boardPieceAt(rowIndex, colIndex) &&
              toSquare(rowIndex, colIndex) !== suppressedSquare
            "
            class="piece"
            :class="[
              pieceTone(boardPieceAt(rowIndex, colIndex)),
              ...spriteClasses(boardPieceAt(rowIndex, colIndex), toSquare(rowIndex, colIndex))
            ]"
            :style="spriteStyle(boardPieceAt(rowIndex, colIndex), toSquare(rowIndex, colIndex))"
          >
            {{ pieceLabel(boardPieceAt(rowIndex, colIndex)) }}
          </span>
        </button>
      </div>
      <div v-if="animationRenderModel" class="animation-layer" aria-hidden="true">
        <div
          class="animation-piece"
          :style="movingStyle"
        ></div>
        <div
          v-if="animationRenderModel.captured"
          class="animation-piece"
          :style="capturedStyle"
        ></div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.board-shell {
  background: #312e2b;
  padding: 16px;
  --square-size: 72px;
}


.board-grid {
  position: relative;
  display: grid;
  grid-template-rows: repeat(8, minmax(0, 1fr));
  gap: 0;
}

.board-row {
  display: grid;
  grid-template-columns: repeat(8, minmax(0, 1fr));
  gap: 0;
}

.board-square {
  position: relative;
  border: none;
  border-radius: 0;
  width: var(--square-size, 72px);
  height: var(--square-size, 72px);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
}

.square-light {
  background: #f0d9b5;
}

.square-dark {
  background: #b58863;
}

.piece {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 1.1rem;
  font-weight: 700;
  letter-spacing: 0.08rem;
  text-transform: uppercase;
  background-repeat: no-repeat;
  background-position: 0% 50%;
  background-size: calc(var(--frame-count, 1) * 100%) 100%;
  animation-duration: var(--anim-duration, 1200ms);
  animation-timing-function: steps(var(--frame-count, 1));
  animation-iteration-count: infinite;
  z-index: 1;
}

.piece-light {
  color: #f7f4ee;
}

.piece-dark {
  color: #1a1411;
}

.has-sprite {
  color: transparent;
  animation-name: none;
}

.animate-sprite {
  animation-name: sprite;
}

.animation-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.animation-piece {
  position: absolute;
  background-repeat: no-repeat;
  background-position: 0% 50%;
  background-size: 100% 100%;
  transform-origin: center;
}

.is-selected {
  outline: none;
}

.is-legal::after {
  content: "";
  position: absolute;
  z-index: 0;
  width: calc(var(--square-size, 72px) * 0.36);
  height: calc(var(--square-size, 72px) * 0.36);
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.2);
}

.board-square.has-piece.is-legal::after {
  width: calc(var(--square-size, 72px) * 0.92);
  height: calc(var(--square-size, 72px) * 0.92);
  background: transparent;
  border: calc(var(--square-size, 72px) * 0.08) solid rgba(0, 0, 0, 0.33);
}

.board-square.square-light.is-legal {
  background: #cdd16e;
}

.board-square.square-dark.is-legal {
  background: #aaa23a;
}

.board-square.square-light.is-selected {
  background: #f6f669;
}

.board-square.square-dark.is-selected {
  background: #baca2b;
}

@keyframes sprite {
  from {
    background-position: 0% 50%;
  }
  to {
    background-position: 100% 50%;
  }
}
</style>
