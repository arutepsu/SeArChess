import type { StatePlaybackEntry } from "../../assets/spriteCatalog";
import type { BoardAnimation } from "../../animation/animationTypes";
import { captureTimings, moveDurationMs } from "../../animation/animationConfig";
import { clamp, pieceType } from "./types";
import type { MotionStyle, VisualState } from "./types";
import type { PieceCode } from "../../api/types";

export function normalizeProgress(progress: number, mode: "Loop" | string): number {
  if (mode === "Loop") return progress - Math.floor(progress);
  return clamp(progress, 0, 1);
}

export function planSegment(entry: StatePlaybackEntry, progress: number) {
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
    localProgress: clamp((p - segStart) / sliceSize, 0, 1),
  };
}

export function selectFrameIndex(state: VisualState, frameCount: number, progress: number): number {
  if (frameCount <= 1) return 0;
  if (state === "idle") return 0;
  const clamped = clamp(progress, 0, 1);
  return clamp(Math.floor(clamped * frameCount), 0, frameCount - 1);
}

export function resolveCapturePhase(progress: number) {
  const total =
    captureTimings.approachMs +
    captureTimings.attackMs +
    captureTimings.attack1Ms +
    captureTimings.deadMs +
    captureTimings.fadeMs;
  const elapsed = clamp(progress, 0, 1) * total;
  const endApproach = captureTimings.approachMs;
  const endAttack = endApproach + captureTimings.attackMs;
  const endAttack1 = endAttack + captureTimings.attack1Ms;
  const endDead = endAttack1 + captureTimings.deadMs;

  const local = (start: number, end: number) =>
    end <= start ? 1 : clamp((elapsed - start) / (end - start), 0, 1);

  if (elapsed < endApproach) return { phase: "approach", local: local(0, endApproach) };
  if (elapsed < endAttack) return { phase: "attack", local: local(endApproach, endAttack) };
  if (elapsed < endAttack1) return { phase: "attack1", local: local(endAttack, endAttack1) };
  if (elapsed < endDead) return { phase: "dead", local: local(endAttack1, endDead) };
  return { phase: "fade", local: local(endDead, total) };
}

export function motionStyleFor(piece: PieceCode, isCapture: boolean): MotionStyle {
  if (isCapture) return { kind: "attack", overshootFraction: 0.15 };
  switch (pieceType(piece)) {
    case "pawn":   return { kind: "linear" };
    case "rook":   return { kind: "heavy" };
    case "knight": return { kind: "arc", heightFraction: 0.5 };
    case "bishop":
    case "queen":  return { kind: "smooth" };
    case "king":   return { kind: "heavy" };
    default:       return { kind: "smooth" };
  }
}

export function interpolate(
  style: MotionStyle,
  fromX: number, fromY: number,
  toX: number, toY: number,
  t: number
): { x: number; y: number } {
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
      const p = Math.pow(t, 2);
      return { x: lerp(fromX, toX, p), y: lerp(fromY, toY, p) };
    }
    case "arc": {
      const e = easeInOut(t);
      const arcBase = Math.max(Math.abs(toX - fromX), Math.abs(toY - fromY));
      return {
        x: lerp(fromX, toX, e),
        y: lerp(fromY, toY, e) - Math.sin(e * Math.PI) * (arcBase * style.heightFraction),
      };
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
}

export function backgroundPositionFor(frameIndex: number, frameCount: number): string {
  if (frameCount <= 1) return "0% 50%";
  return `${(frameIndex / Math.max(1, frameCount - 1)) * 100}% 50%`;
}

export function animationDurationMs(plan: BoardAnimation): number {
  if (!plan.isCapture) return moveDurationMs;
  return (
    captureTimings.approachMs +
    captureTimings.attackMs +
    captureTimings.attack1Ms +
    captureTimings.deadMs +
    captureTimings.fadeMs
  );
}
