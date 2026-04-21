/**
 * Central home for all animation timing and playback constants.
 *
 * ChessBoard imports these for playback; planAnimation does not need them
 * (the planner decides what to animate, not how long it takes).
 */

/** Duration of a non-capture move in milliseconds. */
export const moveDurationMs = 340;

/** Idle sprite animation rate in frames per second. */
export const idleFps = 6;

/**
 * Phase durations (ms) for the capture animation sequence:
 *   approach → attack → attack1 → dead → fade
 */
export const captureTimings = {
  approachMs: 400,
  attackMs: 500,
  attack1Ms: 500,
  deadMs: 1000,
  fadeMs: 450
} as const;

/**
 * Sum of all capture phase durations.
 * Derived from captureTimings so there is exactly one place to change.
 */
export const captureTotalMs =
  captureTimings.approachMs +
  captureTimings.attackMs +
  captureTimings.attack1Ms +
  captureTimings.deadMs +
  captureTimings.fadeMs;
